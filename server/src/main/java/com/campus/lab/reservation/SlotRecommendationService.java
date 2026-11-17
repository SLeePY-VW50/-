package com.campus.lab.reservation;

import com.campus.lab.common.BusinessException;
import com.campus.lab.common.DayTimes;
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
import java.util.Locale;
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
    private final Clock clock;
    private final BitmapConflictDetector conflictDetector = new BitmapConflictDetector();

    public SlotRecommendationService(
            ReservationRepository repository,
            ResourceService resourceService,
            RuleValidator ruleValidator,
            Clock clock) {
        this.repository = repository;
        this.resourceService = resourceService;
        this.ruleValidator = ruleValidator;
        this.clock = clock;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public SlotRecommendationsView recommend(
            long userId,
            long labId,
            LocalDate date,
            int durationMinutes,
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
        if (durationMinutes % 30 != 0 || durationMinutes < 30 || durationMinutes > TimeSlotMask.MAX_SLOTS * 30) {
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
        if (durationMinutes < rule.minDurationMinutes() || durationMinutes > rule.maxDurationMinutes()) {
            return empty(RecommendationOutcome.DURATION_UNFIT);
        }

        int openMinute = minuteOfDay(rule.openTime());
        int closeMinute = DayTimes.minuteOfDay(rule.closeTime(), true);
        int alignedOpen = ((openMinute + 29) / 30) * 30;
        int alignedClose = (closeMinute / 30) * 30;
        if (durationMinutes > alignedClose - alignedOpen) {
            return empty(RecommendationOutcome.DURATION_UNFIT);
        }
        LocalTime probeStart = minutesToTime(alignedOpen);
        LocalTime probeEnd = minutesToTime(alignedOpen + durationMinutes);
        try {
            ruleValidator.validate(
                    new RuleValidationRequest(labId, date, probeStart, probeEnd, List.of()),
                    laboratory.status(), rule, List.of());
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
        int needed = durationMinutes / 30;
        int openSlot = alignedOpen / 30;
        int closeSlot = alignedClose / 30;
        List<SlotRecommendationView> found = new ArrayList<>();
        for (int start = openSlot; start + needed <= closeSlot && found.size() < MAX_RECOMMENDATIONS; start++) {
            int startMinute = start * 30;
            int endMinute = (start + needed) * 30;
            if (overlaps(startMinute, endMinute, windows)) {
                continue;
            }
            TimeSlotMask mask = TimeSlotMask.of(start, start + needed);
            int seatCount = mode == ReservationMode.SEAT ? attendeeCount : 0;
            ConflictDecision decision = conflictDetector.detect(
                    snapshot, new ConflictQuery(mask, mode, seatCount, null, null, false));
            if (!decision.available()) {
                continue;
            }
            found.add(new SlotRecommendationView(
                    start, start + needed, slotTime(start), slotTime(start + needed), durationMinutes));
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

    private static LocalTime minutesToTime(int minute) {
        if (minute == 24 * 60) {
            return LocalTime.MIDNIGHT;
        }
        return LocalTime.of(minute / 60, minute % 60);
    }

    private static int minuteOfDay(LocalTime time) {
        return time.getHour() * 60 + time.getMinute();
    }

    private static String slotTime(int slot) {
        if (slot == TimeSlotMask.SLOTS_PER_DAY) {
            return "24:00";
        }
        return String.format(Locale.ROOT, "%02d:%02d", slot / 2, (slot % 2) * 30);
    }
}
