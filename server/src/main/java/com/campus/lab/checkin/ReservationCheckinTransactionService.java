package com.campus.lab.checkin;

import com.campus.lab.common.BusinessException;
import com.campus.lab.reservation.LabLockKey;
import com.campus.lab.reservation.ReservationRepository;
import com.campus.lab.reservation.ReservationStatus;
import java.time.Clock;
import java.time.LocalDateTime;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ReservationCheckinTransactionService {

    private final ReservationRepository repository;
    private final CheckinRuleService ruleService;
    private final Clock clock;

    public ReservationCheckinTransactionService(
            ReservationRepository repository, CheckinRuleService ruleService, Clock clock) {
        this.repository = repository;
        this.ruleService = ruleService;
        this.clock = clock;
    }

    @Transactional(rollbackFor = Exception.class)
    public CheckinOutcome checkin(
            long reservationId, long userId, long terminalLabId, LabLockKey acquiredLock,
            CheckinChannel channel) {
        ReservationRepository.CancellationReservation reservation =
                repository.lockReservationForCancellation(reservationId);
        if (reservation == null || reservation.userId() != userId
                || reservation.labId() == null || reservation.labId() != terminalLabId) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "预约不存在");
        }
        LabLockKey requiredLock = new LabLockKey(reservation.labId(), reservation.date());
        if (!requiredLock.equals(acquiredLock)) {
            throw new BusinessException(HttpStatus.CONFLICT, "预约分配已变化，请重试");
        }
        if (reservation.status() == ReservationStatus.IN_USE) {
            return new CheckinOutcome(reservationId, true);
        }
        if (reservation.status() != ReservationStatus.APPROVED || reservation.startTime() == null) {
            throw new BusinessException(HttpStatus.CONFLICT, "当前预约状态不可签到");
        }
        LocalDateTime now = LocalDateTime.now(clock);
        CheckinRuleService.CheckinRules rules = ruleService.rules();
        if (now.isBefore(reservation.startTime().minusMinutes(rules.beforeMinutes()))
                || now.isAfter(reservation.startTime().plusMinutes(rules.graceMinutes()))) {
            throw new BusinessException(HttpStatus.CONFLICT, "当前不在签到时间窗口");
        }
        int updated = repository.checkinReservation(reservationId, userId, terminalLabId, now, channel);
        if (updated != 1) {
            throw new BusinessException(HttpStatus.CONFLICT, "预约状态已变化，请重试");
        }
        repository.insertCheckinLog(reservationId, userId, channel);
        return new CheckinOutcome(reservationId, false);
    }

    public record CheckinOutcome(long reservationId, boolean alreadyCheckedIn) {
    }
}
