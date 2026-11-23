package com.campus.lab.reservation;

import com.campus.lab.common.BusinessException;
import com.campus.lab.period.PeriodCatalog;
import com.campus.lab.period.PeriodSchedule;
import com.campus.lab.reservation.dto.SlotRecommendationView;
import com.campus.lab.reservation.dto.SlotRecommendationsView;
import com.campus.lab.resource.EffectiveTimeRule;
import com.campus.lab.resource.ResourceService;
import com.campus.lab.resource.RuleValidationRequest;
import com.campus.lab.resource.RuleValidator;
import com.campus.lab.resource.dto.LabAvailabilityView;
import com.campus.lab.resource.dto.LabView;
import com.campus.lab.resource.dto.SeatAvailabilityView;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SlotRecommendationService {

    static final int MAX_RECOMMENDATIONS = 5;

    private final ReservationRepository repository;
    private final ResourceService resourceService;
    private final RuleValidator ruleValidator;
    private final PeriodCatalog periodCatalog;
    private final Clock clock;
    private final BitmapConflictDetector conflictDetector = new BitmapConflictDetector();

    public SlotRecommendationService(
            ReservationRepository repository,
            ResourceService resourceService,
            RuleValidator ruleValidator,
            PeriodCatalog periodCatalog,
            Clock clock) {
        this.repository = repository;
        this.resourceService = resourceService;
        this.ruleValidator = ruleValidator;
        this.periodCatalog = periodCatalog;
        this.clock = clock;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public SlotRecommendationsView recommend(
            long userId,
            long labId,
            LocalDate date,
            int durationPeriods,
            ReservationMode mode,
            int attendeeCount) {
        if (date == null || mode == null || attendeeCount <= 0) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "预约参数不完整或无效");
        }
        ReservationRepository.UserAdmission user = repository.findUser(userId);
        if (user == null) {
            throw new BusinessException(HttpStatus.UNAUTHORIZED, "用户不存在或不可用");
        }
        if (user.creditScore() < repository.creditThreshold()) {
            return empty(RecommendationOutcome.CREDIT_BLOCKED);
        }
        if (!"ACTIVE".equals(user.status())) {
            return empty(RecommendationOutcome.RULE_BLOCKED);
        }
        if (durationPeriods < 1 || durationPeriods > TimeSlotMask.MAX_BITS) {
            return empty(RecommendationOutcome.DURATION_UNFIT);
        }

        LabView laboratory = resourceService.getLab(labId);
        if (laboratory.status() != ResourceStatus.ACTIVE || attendeeCount > laboratory.capacity()) {
            return empty(RecommendationOutcome.RULE_BLOCKED);
        }
        EffectiveTimeRule rule = resourceService.getRules(labId).weeklyRules().stream()
                .filter(candidate -> candidate.dayOfWeek() == date.getDayOfWeek().getValue())
                .findFirst()
                .orElse(null);
        if (rule == null) {
            return empty(RecommendationOutcome.RULE_BLOCKED);
        }
        long advanceDays = ChronoUnit.DAYS.between(LocalDate.now(clock), date);
        if (advanceDays < rule.advanceMinDays() || advanceDays > rule.advanceMaxDays()) {
            return empty(RecommendationOutcome.RULE_BLOCKED);
        }
        if (durationPeriods < rule.minPeriods() || durationPeriods > rule.maxPeriods()) {
            return empty(RecommendationOutcome.DURATION_UNFIT);
        }

        PeriodSchedule schedule = periodCatalog.forLab(labId);
        long bookable = schedule.bookableMask(
                rule.openTime(), rule.closeTime(), rule.openStartPeriod(), rule.openEndPeriod());
        List<TimeSlotMask> probes = schedule.placements(0, 24 * 60, durationPeriods, bookable);
        if (probes.isEmpty()) {
            return empty(RecommendationOutcome.DURATION_UNFIT);
        }
        TimeSlotMask probe = probes.getFirst();
        try {
            ruleValidator.validate(
                    new RuleValidationRequest(
                            labId, date, schedule.startTime(probe.startSlot()),
                            schedule.endTime(probe.endSlot()), List.of()),
                    laboratory.status(), rule, List.of(), schedule);
        } catch (IllegalArgumentException exception) {
            return empty(ruleFailure(exception.getMessage()));
        }

        LabAvailabilityView availability = resourceService.getAvailability(labId, date);
        if (mode == ReservationMode.ROOM && availability.seats().stream()
                .noneMatch(seat -> seat.status() == ResourceStatus.ACTIVE)) {
            return empty(RecommendationOutcome.RULE_BLOCKED);
        }
        ResourceSnapshot snapshot = snapshot(availability);
        List<ReservationRepository.OccupiedWindow> windows = repository.effectiveWindows(userId, date);
        List<SlotRecommendationView> found = new ArrayList<>();
        for (TimeSlotMask mask : probes) {
            if (found.size() >= MAX_RECOMMENDATIONS) {
                break;
            }
            int startMinute = minuteOfDay(schedule.startTime(mask.startSlot()));
            int endMinute = minuteOfDay(schedule.endTime(mask.endSlot()));
            if (overlaps(startMinute, endMinute, windows)) {
                continue;
            }
            int seatCount = mode == ReservationMode.SEAT ? attendeeCount : 0;
            ConflictDecision decision = conflictDetector.detect(
                    snapshot, new ConflictQuery(mask, mode, seatCount, null, null, false));
            if (!decision.available()) {
                continue;
            }
            found.add(new SlotRecommendationView(
                    mask.startSlot(), mask.endSlot(),
                    schedule.format(schedule.startTime(mask.startSlot())),
                    schedule.format(schedule.endTime(mask.endSlot())),
                    durationPeriods));
        }
        return new SlotRecommendationsView(
                found.isEmpty() ? RecommendationOutcome.NONE_FREE : RecommendationOutcome.AVAILABLE, found);
    }

    private static SlotRecommendationsView empty(RecommendationOutcome outcome) {
        return new SlotRecommendationsView(outcome, List.of());
    }

    private static RecommendationOutcome ruleFailure(String message) {
        if (message != null && (message.contains("时长") || message.contains("开放时间")
                || message.contains("对齐") || message.contains("时间片"))) {
            return RecommendationOutcome.DURATION_UNFIT;
        }
        return RecommendationOutcome.RULE_BLOCKED;
    }

    private static boolean overlaps(
            int startMinute, int endMinute, List<ReservationRepository.OccupiedWindow> windows) {
        for (ReservationRepository.OccupiedWindow window : windows) {
            if (startMinute < window.endMinute() && endMinute > window.startMinute()) {
                return true;
            }
        }
        return false;
    }

    private static ResourceSnapshot snapshot(LabAvailabilityView availability) {
        long roomMask = Long.parseUnsignedLong(availability.roomMask());
        List<SeatSnapshot> seats = new ArrayList<>(availability.seats().size());
        for (SeatAvailabilityView seat : availability.seats()) {
            seats.add(new SeatSnapshot(
                    seat.seatId(), seat.seatCode(), seat.zoneCode(), seat.seatIndex(),
                    seat.specLevel(), seat.nearWindow(), seat.status(),
                    Long.parseUnsignedLong(seat.mask())));
        }
        return new ResourceSnapshot(roomMask, seats);
    }

    private static int minuteOfDay(LocalTime time) {
        return time.getHour() * 60 + time.getMinute();
    }
}
