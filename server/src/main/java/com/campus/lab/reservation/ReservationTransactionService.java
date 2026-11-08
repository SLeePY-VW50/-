package com.campus.lab.reservation;

import com.campus.lab.common.BusinessException;
import com.campus.lab.resource.EffectiveTimeRule;
import com.campus.lab.resource.ResourceService;
import com.campus.lab.resource.RuleValidationRequest;
import com.campus.lab.resource.RuleValidator;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ReservationTransactionService {

    private final ReservationRepository repository;
    private final ResourceService resourceService;
    private final RuleValidator ruleValidator;
    private final BitmapConflictDetector conflictDetector = new BitmapConflictDetector();

    public ReservationTransactionService(
            ReservationRepository repository, ResourceService resourceService, RuleValidator ruleValidator) {
        this.repository = repository;
        this.resourceService = resourceService;
        this.ruleValidator = ruleValidator;
    }

    @Transactional(rollbackFor = Exception.class)
    public long createFixed(long userId, NormalizedReservation request) {
        return createFixedInTransaction(userId, null, request);
    }

    private long createFixedInTransaction(long userId, Long batchId, NormalizedReservation request) {
        ReservationRepository.UserAdmission user = repository.lockUser(userId);
        if (!"ACTIVE".equals(user.status())) {
            throw new BusinessException(HttpStatus.UNAUTHORIZED, "用户不可用");
        }
        if (user.creditScore() < repository.creditThreshold()) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "信用分不足，无法预约");
        }
        if (repository.hasEffectiveOverlap(userId, request)) {
            throw new BusinessException(HttpStatus.CONFLICT, "同一用户在此时段已有有效预约");
        }
        EffectiveTimeRule rule = effectiveRule(request);
        repository.ensureLabBitmap(request.labId(), request.date());
        ReservationRepository.LabBitmap room = repository.lockLabBitmap(request.labId(), request.date());
        ReservationRepository.LabDetails laboratory = repository.lockLabForUpdate(request.labId());
        repository.ensureSeatBitmaps(request.labId(), request.date());
        List<ReservationRepository.SeatBitmap> seatBitmaps =
                repository.loadSeatBitmaps(request.labId(), request.date());
        List<SeatSnapshot> seats = seatBitmaps.stream().map(ReservationRepository.SeatBitmap::snapshot).toList();
        validateRule(request, laboratory, rule, seats);
        if (request.attendeeCount() > laboratory.capacity()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "预约人数不能超过实验室容量");
        }

        int requestedSeatCount = request.reservationMode() == ReservationMode.SEAT
                ? request.seatCount() : 0;
        ConflictQuery query = new ConflictQuery(
                request.timeMask(), request.reservationMode(), requestedSeatCount,
                request.preferences().minSpecLevel(), request.preferences().zoneCode(),
                Boolean.TRUE.equals(request.preferences().nearWindow()));
        ConflictDecision decision;
        try {
            decision = conflictDetector.detect(new ResourceSnapshot(room.mask(), seats), query);
        } catch (IllegalArgumentException exception) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, exception.getMessage());
        }
        if (!decision.available()) {
            throw new BusinessException(HttpStatus.CONFLICT, "预约时段冲突或可用工位不足");
        }

        List<SeatAssignment> assignments = decision.selectedSeats();
        if (request.reservationMode() == ReservationMode.ROOM) {
            assignments = seats.stream().filter(seat -> seat.status() == ResourceStatus.ACTIVE)
                    .map(seat -> new SeatAssignment(
                            seat.seatId(), seat.seatCode(), seat.seatIndex(), false, false))
                    .toList();
            if (assignments.isEmpty()) {
                throw new BusinessException(HttpStatus.CONFLICT, "实验室没有可分配的ACTIVE工位");
            }
        }
        LocalDateTime start = request.date().atTime(request.startTime());
        LocalDateTime end = LocalTime.MIDNIGHT.equals(request.endTime())
                ? request.date().plusDays(1).atStartOfDay() : request.date().atTime(request.endTime());
        long reservationId = repository.insertReservation(
                userId, batchId, request, request.labId(), start, end, assignments.size(),
                ReservationStatus.APPROVED);
        for (SeatAssignment assignment : assignments) {
            repository.insertSeatAssignment(reservationId, assignment.seatId(), request.timeMask().value());
            if (request.reservationMode() == ReservationMode.SEAT) {
                ReservationRepository.SeatBitmap seat = seatBitmaps.stream()
                        .filter(candidate -> candidate.snapshot().seatId() == assignment.seatId())
                        .findFirst().orElseThrow();
                repository.updateSeatMask(
                        assignment.seatId(), request.date(), seat.version(), request.timeMask().value());
            }
        }
        if (request.reservationMode() == ReservationMode.ROOM) {
            repository.updateRoomMask(
                    request.labId(), request.date(), room.version(), request.timeMask().value());
        }
        repository.insertInitialLog(reservationId, userId, ReservationStatus.APPROVED);
        return reservationId;
    }

    @Transactional(rollbackFor = Exception.class)
    public long createBatch(long userId, NormalizedBatch batch) {
        ReservationRepository.UserAdmission user = repository.lockUser(userId);
        if (!"ACTIVE".equals(user.status())) {
            throw new BusinessException(HttpStatus.UNAUTHORIZED, "用户不可用");
        }
        if (user.creditScore() < repository.creditThreshold()) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "信用分不足，无法预约");
        }
        repository.requireLab(batch.labId());
        String batchNo = "B" + java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 31);
        long batchId = repository.insertBatch(
                userId, batch.labId(), batchNo, batch.courseName(), batch.purpose());
        for (NormalizedReservation item : batch.items()) {
            createFixedInTransaction(userId, batchId, item);
        }
        return batchId;
    }

    @Transactional(rollbackFor = Exception.class)
    public long createSchedulable(long userId, NormalizedReservation request) {
        ReservationRepository.UserAdmission user = repository.lockUser(userId);
        if (!"ACTIVE".equals(user.status())) {
            throw new BusinessException(HttpStatus.UNAUTHORIZED, "用户不可用");
        }
        if (user.creditScore() < repository.creditThreshold()) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "信用分不足，无法预约");
        }
        if (repository.hasEffectiveOverlap(userId, request)) {
            throw new BusinessException(HttpStatus.CONFLICT, "同一用户在此时段已有有效预约");
        }
        List<Long> candidateLabIds = candidateLabIds(request);
        if (candidateLabIds.isEmpty()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "没有可用的候选实验室");
        }
        List<ReservationRepository.LabDetails> laboratories = candidateLabIds.stream()
                .map(this::requireCandidateLab)
                .toList();
        int suitableCandidates = 0;
        for (ReservationRepository.LabDetails laboratory : laboratories) {
            if (laboratory.status() != ResourceStatus.ACTIVE
                    || request.attendeeCount() > laboratory.capacity()) {
                continue;
            }
            try {
                EffectiveTimeRule rule = effectiveRule(laboratory.labId(), request);
                ruleValidator.validateWindow(
                        laboratory.labId(), request.date(), request.startTime(), request.endTime(),
                        request.durationMinutes(), laboratory.status(), rule);
                suitableCandidates++;
            } catch (BusinessException | IllegalArgumentException exception) {
            }
        }
        if (suitableCandidates == 0) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "没有满足需求的候选实验室");
        }
        long reservationId = repository.insertReservation(
                userId, null, request, null, null, null, request.seatCount(),
                ReservationStatus.PENDING_SCHEDULE);
        repository.insertInitialLog(reservationId, userId, ReservationStatus.PENDING_SCHEDULE);
        return reservationId;
    }

    private ReservationRepository.LabDetails requireCandidateLab(long labId) {
        try {
            return repository.requireLab(labId);
        } catch (BusinessException exception) {
            if (exception.getStatus() == HttpStatus.NOT_FOUND) {
                throw new BusinessException(HttpStatus.BAD_REQUEST, "候选实验室不存在");
            }
            throw exception;
        }
    }

    private List<Long> candidateLabIds(NormalizedReservation request) {
        if (!request.preferences().allowedLabIds().isEmpty()) {
            return request.preferences().allowedLabIds().stream().distinct().sorted().toList();
        }
        if (request.labId() != null) {
            return List.of(request.labId());
        }
        return repository.listActiveLabIds();
    }

    private EffectiveTimeRule effectiveRule(NormalizedReservation request) {
        return effectiveRule(request.labId(), request);
    }

    private EffectiveTimeRule effectiveRule(long labId, NormalizedReservation request) {
        return resourceService.getRules(labId).weeklyRules().stream()
                .filter(candidate -> candidate.dayOfWeek() == request.date().getDayOfWeek().getValue())
                .findFirst()
                .orElseThrow(() -> new BusinessException(
                        HttpStatus.BAD_REQUEST, "实验室当日没有适用开放规则"));
    }

    private void validateRule(
            NormalizedReservation request,
            ReservationRepository.LabDetails laboratory,
            EffectiveTimeRule rule,
            List<SeatSnapshot> seats) {
        try {
            ruleValidator.validate(
                    new RuleValidationRequest(
                            request.labId(), request.date(), request.startTime(), request.endTime(), List.of()),
                    laboratory.status(), rule, seats);
        } catch (IllegalArgumentException exception) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, exception.getMessage());
        }
    }
}
