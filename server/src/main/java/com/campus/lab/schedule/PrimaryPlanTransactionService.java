package com.campus.lab.schedule;

import com.campus.lab.common.DayTimes;
import com.campus.lab.reservation.LabLockKey;
import com.campus.lab.reservation.ReservationMode;
import com.campus.lab.reservation.ReservationRepository;
import com.campus.lab.reservation.ReservationStatus;
import com.campus.lab.reservation.SeatAssignment;
import com.campus.lab.reservation.SeatSnapshot;
import com.campus.lab.reservation.TimeSlotMask;
import com.campus.lab.resource.EffectiveTimeRule;
import com.campus.lab.resource.RuleValidationRequest;
import com.campus.lab.resource.RuleValidator;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PrimaryPlanTransactionService {

    private final ScheduleRepository scheduleRepository;
    private final SchedulePublicationRepository publicationRepository;
    private final ReservationRepository reservationRepository;
    private final RuleValidator ruleValidator;
    private final Clock clock;
    private final ScheduleSeatingService seatingService = new ScheduleSeatingService();

    public PrimaryPlanTransactionService(
            ScheduleRepository scheduleRepository,
            SchedulePublicationRepository publicationRepository,
            ReservationRepository reservationRepository,
            RuleValidator ruleValidator,
            Clock clock) {
        this.scheduleRepository = scheduleRepository;
        this.publicationRepository = publicationRepository;
        this.reservationRepository = reservationRepository;
        this.ruleValidator = ruleValidator;
        this.clock = clock;
    }

    @Transactional(
            rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public boolean publish(long taskId) {
        ScheduleRepository.ScheduleTask task = scheduleRepository.lockTask(taskId);
        if (task.status() == ScheduleTaskStatus.PUBLISHED) {
            return false;
        }
        if (!task.primary()) {
            throw new SchedulePublicationException("PRIMARY_TASK_REQUIRED");
        }
        if (task.status() != ScheduleTaskStatus.SUCCEEDED) {
            throw new SchedulePublicationException("PRIMARY_TASK_NOT_PUBLISHABLE");
        }
        List<Long> demandIds = scheduleRepository.snapshotDemandIds(task.demandSnapshot());
        Map<Long, SchedulePublicationRepository.PublicationResult> results = lockAndValidateResults(
                taskId, demandIds);
        Map<Long, SchedulePublicationRepository.PublicationReservation> reservations =
                lockAndValidateReservations(demandIds);
        Map<Long, ReservationRepository.UserAdmission> users = lockAndValidateUsers(reservations);
        Map<LabLockKey, CurrentLab> currentLabs = lockCurrentResources(results, reservations);
        ScheduleProblem currentProblem = currentProblem(demandIds, reservations, users, currentLabs);
        ScheduleSeatingPlan currentSeating = recheckSeating(task, results, reservations, currentProblem);
        validateCurrentRules(results, reservations, currentSeating, currentLabs);
        validateUserOverlaps(demandIds, results, reservations);
        writePublication(task, results, reservations, currentSeating, currentLabs);
        return true;
    }

    private Map<Long, SchedulePublicationRepository.PublicationResult> lockAndValidateResults(
            long taskId, List<Long> demandIds) {
        List<SchedulePublicationRepository.PublicationResult> locked =
                publicationRepository.lockResults(taskId);
        if (demandIds.isEmpty() || locked.size() != demandIds.size()) {
            throw new SchedulePublicationException("UNRESOLVED_PRIMARY_PLAN");
        }
        Map<Long, SchedulePublicationRepository.PublicationResult> results = new LinkedHashMap<>();
        for (SchedulePublicationRepository.PublicationResult result : locked) {
            if (result.published() || result.labId() == null
                    || result.start() == null || result.end() == null) {
                throw new SchedulePublicationException("UNRESOLVED_PRIMARY_PLAN");
            }
            results.put(result.resvId(), result);
        }
        if (results.size() != demandIds.size()
                || !results.keySet().containsAll(demandIds)) {
            throw new SchedulePublicationException("UNRESOLVED_PRIMARY_PLAN");
        }
        return results;
    }

    private Map<Long, SchedulePublicationRepository.PublicationReservation> lockAndValidateReservations(
            List<Long> demandIds) {
        Map<Long, SchedulePublicationRepository.PublicationReservation> reservations =
                publicationRepository.lockReservations(demandIds).stream()
                        .collect(Collectors.toMap(
                                SchedulePublicationRepository.PublicationReservation::resvId,
                                Function.identity(), (left, right) -> left, LinkedHashMap::new));
        if (reservations.size() != demandIds.size()) {
            throw new SchedulePublicationException("RESERVATION_STATE_CHANGED");
        }
        for (long demandId : demandIds) {
            SchedulePublicationRepository.PublicationReservation reservation = reservations.get(demandId);
            if (reservation == null
                    || reservation.status() != ReservationStatus.PENDING_SCHEDULE
                    || reservation.allocationMode() != com.campus.lab.reservation.AllocationMode.SCHEDULABLE) {
                throw new SchedulePublicationException("RESERVATION_STATE_CHANGED");
            }
        }
        return reservations;
    }

    private Map<Long, ReservationRepository.UserAdmission> lockAndValidateUsers(
            Map<Long, SchedulePublicationRepository.PublicationReservation> reservations) {
        int threshold = reservationRepository.creditThreshold();
        Map<Long, ReservationRepository.UserAdmission> users = new HashMap<>();
        List<Long> userIds = reservations.values().stream()
                .map(SchedulePublicationRepository.PublicationReservation::userId)
                .distinct().sorted().toList();
        for (long userId : userIds) {
            ReservationRepository.UserAdmission user = reservationRepository.lockUser(userId);
            if (!"ACTIVE".equals(user.status()) || user.creditScore() < threshold) {
                throw new SchedulePublicationException("USER_NOT_ELIGIBLE");
            }
            users.put(userId, user);
        }
        return users;
    }

    private Map<LabLockKey, CurrentLab> lockCurrentResources(
            Map<Long, SchedulePublicationRepository.PublicationResult> results,
            Map<Long, SchedulePublicationRepository.PublicationReservation> reservations) {
        List<LabLockKey> keys = results.values().stream()
                .map(result -> new LabLockKey(
                        result.labId(), reservations.get(result.resvId()).date()))
                .distinct()
                .sorted(Comparator.comparingLong(LabLockKey::labId).thenComparing(LabLockKey::date))
                .toList();
        Map<LabLockKey, CurrentLab> current = new LinkedHashMap<>();
        for (LabLockKey key : keys) {
            reservationRepository.ensureLabBitmap(key.labId(), key.date());
            ReservationRepository.LabBitmap room =
                    reservationRepository.lockLabBitmap(key.labId(), key.date());
            ReservationRepository.LabDetails laboratory =
                    reservationRepository.lockLabForUpdate(key.labId());
            reservationRepository.ensureSeatBitmaps(key.labId(), key.date());
            List<ReservationRepository.SeatBitmap> seatBitmaps =
                    reservationRepository.loadSeatBitmaps(key.labId(), key.date());
            List<SeatSnapshot> seats = seatBitmaps.stream()
                    .map(ReservationRepository.SeatBitmap::snapshot).toList();
            EffectiveTimeRule rule = scheduleRepository.lockEffectiveRule(key.labId(), key.date());
            ScheduleLabResource resource = new ScheduleLabResource(
                    key.labId(), key.date(), laboratory.status(), laboratory.capacity(),
                    ScheduleRepository.openMask(rule), room.mask(), seats, rule);
            Map<Long, Long> seatVersions = new HashMap<>();
            seatBitmaps.forEach(seat -> seatVersions.put(seat.snapshot().seatId(), seat.version()));
            current.put(key, new CurrentLab(resource, room.version(), seatVersions));
        }
        return current;
    }

    private ScheduleProblem currentProblem(
            List<Long> demandIds,
            Map<Long, SchedulePublicationRepository.PublicationReservation> reservations,
            Map<Long, ReservationRepository.UserAdmission> users,
            Map<LabLockKey, CurrentLab> currentLabs) {
        List<ScheduleDemand> demands = new ArrayList<>();
        for (long demandId : demandIds) {
            SchedulePublicationRepository.PublicationReservation reservation = reservations.get(demandId);
            demands.add(new ScheduleDemand(
                    reservation.resvId(), reservation.userId(), reservation.createdAt(), reservation.date(),
                    DayTimes.minuteOfDay(reservation.windowStart(), false),
                    DayTimes.minuteOfDay(reservation.windowEnd(), true), reservation.durationMinutes(),
                    reservation.mode(), reservation.attendeeCount(),
                    reservation.mode() == ReservationMode.SEAT ? reservation.seatCount() : 0,
                    users.get(reservation.userId()).creditScore(),
                    reservation.preferences().minSpecLevel(), reservation.preferences().zoneCode(),
                    Boolean.TRUE.equals(reservation.preferences().nearWindow()),
                    java.util.Set.copyOf(reservation.preferences().allowedLabIds())));
        }
        return new ScheduleProblem(
                demands, currentLabs.values().stream().map(CurrentLab::resource).toList());
    }

    private ScheduleSeatingPlan recheckSeating(
            ScheduleRepository.ScheduleTask task,
            Map<Long, SchedulePublicationRepository.PublicationResult> results,
            Map<Long, SchedulePublicationRepository.PublicationReservation> reservations,
            ScheduleProblem currentProblem) {
        List<ScheduleAssignment> firstStage = new ArrayList<>();
        for (SchedulePublicationRepository.PublicationResult result : results.values()) {
            SchedulePublicationRepository.PublicationReservation reservation =
                    reservations.get(result.resvId());
            validateResultTimes(result, reservation);
            firstStage.add(new ScheduleAssignment(
                    result.resvId(), result.labId(),
                    TimeSlotMask.between(result.start().toLocalTime(), result.end().toLocalTime()).startSlot(),
                    TimeSlotMask.between(result.start().toLocalTime(), result.end().toLocalTime()).endSlot()));
        }
        ScheduleSeatingPlan seated = seatingService.allocate(
                currentProblem, new SchedulePlan(task.algorithmType(), firstStage, Map.of()));
        if (!seated.unresolvedReasons().isEmpty()
                || seated.assignments().size() != results.size()) {
            throw new SchedulePublicationException("SNAPSHOT_STALE");
        }
        Map<Long, ScheduleSeatedAssignment> seatedByReservation = seated.assignments().stream()
                .collect(Collectors.toMap(ScheduleSeatedAssignment::resvId, Function.identity()));
        for (SchedulePublicationRepository.PublicationResult result : results.values()) {
            ScheduleSeatedAssignment rechecked = seatedByReservation.get(result.resvId());
            List<SeatAssignment> persistedSeats = publicationRepository.seatAssignments(result);
            TimeSlotMask resultMask = TimeSlotMask.between(
                    result.start().toLocalTime(), result.end().toLocalTime());
            if (rechecked.labId() != result.labId()
                    || rechecked.startSlot() != resultMask.startSlot()
                    || rechecked.endSlot() != resultMask.endSlot()
                    || !persistedSeats.equals(rechecked.candidateSeatAllocations())) {
                throw new SchedulePublicationException("SNAPSHOT_STALE");
            }
        }
        return seated;
    }

    private void validateResultTimes(
            SchedulePublicationRepository.PublicationResult result,
            SchedulePublicationRepository.PublicationReservation reservation) {
        if (!result.start().toLocalDate().equals(reservation.date())
                || !result.end().isAfter(result.start())
                || !(result.end().toLocalDate().equals(reservation.date())
                    || result.end().toLocalDate().equals(reservation.date().plusDays(1)))) {
            throw new SchedulePublicationException("ASSIGNMENT_TIME_INVALID");
        }
    }

    private void validateCurrentRules(
            Map<Long, SchedulePublicationRepository.PublicationResult> results,
            Map<Long, SchedulePublicationRepository.PublicationReservation> reservations,
            ScheduleSeatingPlan seated,
            Map<LabLockKey, CurrentLab> currentLabs) {
        Map<Long, ScheduleSeatedAssignment> assignments = seated.assignments().stream()
                .collect(Collectors.toMap(ScheduleSeatedAssignment::resvId, Function.identity()));
        for (SchedulePublicationRepository.PublicationResult result : results.values()) {
            SchedulePublicationRepository.PublicationReservation reservation =
                    reservations.get(result.resvId());
            CurrentLab current = currentLabs.get(new LabLockKey(result.labId(), reservation.date()));
            if (current == null || current.resource().effectiveRule() == null) {
                throw new SchedulePublicationException("CURRENT_RULE_INVALID");
            }
            try {
                ruleValidator.validate(
                        new RuleValidationRequest(
                                result.labId(), reservation.date(), result.start().toLocalTime(),
                                result.end().toLocalTime(),
                                assignments.get(result.resvId()).candidateSeatAllocations().stream()
                                        .map(SeatAssignment::seatId).toList()),
                        current.resource().status(), current.resource().effectiveRule(),
                        current.resource().seats());
            } catch (IllegalArgumentException exception) {
                throw new SchedulePublicationException("CURRENT_RULE_INVALID", exception);
            }
        }
    }

    private void validateUserOverlaps(
            List<Long> demandIds,
            Map<Long, SchedulePublicationRepository.PublicationResult> results,
            Map<Long, SchedulePublicationRepository.PublicationReservation> reservations) {
        List<Long> userIds = reservations.values().stream()
                .map(SchedulePublicationRepository.PublicationReservation::userId)
                .distinct().sorted().toList();
        List<java.time.LocalDate> dates = reservations.values().stream()
                .map(SchedulePublicationRepository.PublicationReservation::date)
                .distinct().sorted().toList();
        List<SchedulePublicationRepository.EffectiveReservation> otherReservations =
                publicationRepository.listEffectiveReservations(userIds, dates, demandIds);
        for (SchedulePublicationRepository.PublicationResult result : results.values()) {
            SchedulePublicationRepository.PublicationReservation reservation =
                    reservations.get(result.resvId());
            boolean overlap = otherReservations.stream().anyMatch(other ->
                    other.userId() == reservation.userId()
                            && other.date().equals(reservation.date())
                            && other.start().isBefore(result.end())
                            && other.end().isAfter(result.start()));
            if (overlap) {
                throw new SchedulePublicationException("USER_TIME_CONFLICT");
            }
        }
    }

    private void writePublication(
            ScheduleRepository.ScheduleTask task,
            Map<Long, SchedulePublicationRepository.PublicationResult> results,
            Map<Long, SchedulePublicationRepository.PublicationReservation> reservations,
            ScheduleSeatingPlan seated,
            Map<LabLockKey, CurrentLab> currentLabs) {
        LocalDateTime publishedAt = LocalDateTime.now(clock);
        Map<Long, ScheduleSeatedAssignment> assignments = seated.assignments().stream()
                .collect(Collectors.toMap(ScheduleSeatedAssignment::resvId, Function.identity()));
        Map<LabLockKey, Long> roomVersions = currentLabs.entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, entry -> entry.getValue().roomVersion()));
        for (SchedulePublicationRepository.PublicationResult result : results.values()) {
            SchedulePublicationRepository.PublicationReservation reservation =
                    reservations.get(result.resvId());
            ScheduleSeatedAssignment assignment = assignments.get(result.resvId());
            LabLockKey key = new LabLockKey(result.labId(), reservation.date());
            CurrentLab current = currentLabs.get(key);
            if (publicationRepository.markResultPublished(result.resultId(), publishedAt) != 1
                    || publicationRepository.approveReservation(
                            result.resvId(), result.labId(), result.start(), result.end(),
                            assignment.candidateSeatAllocations().size()) != 1) {
                throw new SchedulePublicationException("PUBLICATION_STATE_CHANGED");
            }
            for (SeatAssignment seat : assignment.candidateSeatAllocations()) {
                publicationRepository.insertPublishedSeat(
                        result.resvId(), seat.seatId(), assignment.timeMask().value());
            }
            if (reservation.mode() == ReservationMode.ROOM) {
                long version = roomVersions.get(key);
                reservationRepository.updateRoomMask(
                        key.labId(), key.date(), version, assignment.timeMask().value());
                roomVersions.put(key, version + 1);
            } else {
                for (SeatAssignment seat : assignment.candidateSeatAllocations()) {
                    long version = current.seatVersions().get(seat.seatId());
                    reservationRepository.updateSeatMask(
                            seat.seatId(), key.date(), version, assignment.timeMask().value());
                    current.seatVersions().put(seat.seatId(), version + 1);
                }
            }
            reservationRepository.insertSystemLog(
                    result.resvId(), ReservationStatus.PENDING_SCHEDULE,
                    ReservationStatus.APPROVED, "排期主方案发布");
        }
        if (publicationRepository.markTaskPublished(task.taskId()) != 1) {
            throw new SchedulePublicationException("PUBLICATION_STATE_CHANGED");
        }
    }

    private record CurrentLab(
            ScheduleLabResource resource, long roomVersion, Map<Long, Long> seatVersions) {
    }
}
