package com.campus.lab.reservation;

import com.campus.lab.checkin.CheckinRuleService;
import com.campus.lab.common.BusinessException;
import com.campus.lab.credit.CreditService;
import java.time.Clock;
import java.time.LocalDateTime;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ReservationScheduledTransactionService {

    private static final int NO_SHOW_POINTS = 20;

    private final ReservationRepository repository;
    private final ReservationReleaseService releaseService;
    private final CreditService creditService;
    private final CheckinRuleService ruleService;
    private final Clock clock;

    public ReservationScheduledTransactionService(
            ReservationRepository repository,
            ReservationReleaseService releaseService,
            CreditService creditService,
            CheckinRuleService ruleService,
            Clock clock) {
        this.repository = repository;
        this.releaseService = releaseService;
        this.creditService = creditService;
        this.ruleService = ruleService;
        this.clock = clock;
    }

    @Transactional(rollbackFor = Exception.class)
    public void noShow(long reservationId, LabLockKey acquiredLock) {
        ReservationRepository.CancellationReservation reservation =
                repository.lockReservationForCancellation(reservationId);
        if (reservation == null || reservation.status() != ReservationStatus.APPROVED
                || reservation.labId() == null || reservation.startTime() == null
                || repository.hasCheckin(reservationId)) {
            return;
        }
        LabLockKey requiredLock = new LabLockKey(reservation.labId(), reservation.date());
        if (!requiredLock.equals(acquiredLock)) {
            throw new BusinessException(HttpStatus.CONFLICT, "预约分配已变化，请重试");
        }
        LocalDateTime now = LocalDateTime.now(clock);
        int graceMinutes = ruleService.rules().graceMinutes();
        if (!now.isAfter(reservation.startTime().plusMinutes(graceMinutes))) {
            return;
        }
        releaseService.releaseRemaining(reservation, now);
        int updated = repository.markNoShow(reservationId, graceMinutes, now);
        if (updated != 1) {
            throw new BusinessException(HttpStatus.CONFLICT, "预约状态已变化，无法登记爽约");
        }
        CreditService.CreditAdjustment adjustment = creditService.deduct(
                reservation.userId(), reservationId, "NO_SHOW", NO_SHOW_POINTS, null);
        repository.insertSystemLog(
                reservationId,
                ReservationStatus.APPROVED,
                ReservationStatus.NO_SHOW,
                "超过签到宽限期未签到，实际扣除信用分" + adjustment.actualPoints() + "分");
    }

    @Transactional(rollbackFor = Exception.class)
    public void completeEnded(long reservationId) {
        ReservationRepository.CancellationReservation reservation =
                repository.lockReservationForCancellation(reservationId);
        if (reservation == null || reservation.status() != ReservationStatus.IN_USE
                || reservation.endTime() == null) {
            return;
        }
        LocalDateTime now = LocalDateTime.now(clock);
        if (now.isBefore(reservation.endTime())) {
            return;
        }
        int updated = repository.completeEndedReservation(reservationId, now);
        if (updated == 1) {
            repository.insertSystemLog(
                    reservationId,
                    ReservationStatus.IN_USE,
                    ReservationStatus.COMPLETED,
                    "预约到期自动结束");
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public void expirePending(long reservationId) {
        ReservationRepository.CancellationReservation reservation =
                repository.lockReservationForCancellation(reservationId);
        if (reservation == null || reservation.status() != ReservationStatus.PENDING_SCHEDULE) {
            return;
        }
        int updated = repository.expirePendingReservation(reservationId, LocalDateTime.now(clock));
        if (updated == 1) {
            repository.insertSystemLog(
                    reservationId,
                    ReservationStatus.PENDING_SCHEDULE,
                    ReservationStatus.EXPIRED,
                    "请求排期窗口已过，自动过期");
        }
    }
}
