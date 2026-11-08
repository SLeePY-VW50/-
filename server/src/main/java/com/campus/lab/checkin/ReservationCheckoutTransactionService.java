package com.campus.lab.checkin;

import com.campus.lab.common.BusinessException;
import com.campus.lab.reservation.LabLockKey;
import com.campus.lab.reservation.ReservationReleaseService;
import com.campus.lab.reservation.ReservationRepository;
import com.campus.lab.reservation.ReservationStatus;
import java.time.Clock;
import java.time.LocalDateTime;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ReservationCheckoutTransactionService {

    private final ReservationRepository repository;
    private final ReservationReleaseService releaseService;
    private final Clock clock;

    public ReservationCheckoutTransactionService(
            ReservationRepository repository, ReservationReleaseService releaseService, Clock clock) {
        this.repository = repository;
        this.releaseService = releaseService;
        this.clock = clock;
    }

    @Transactional(rollbackFor = Exception.class)
    public void checkout(
            long reservationId, long userId, LabLockKey acquiredLock, CheckoutChannel channel) {
        ReservationRepository.CancellationReservation reservation =
                repository.lockReservationForCancellation(reservationId);
        if (reservation == null || reservation.userId() != userId) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "预约不存在");
        }
        if (reservation.status() == ReservationStatus.COMPLETED) {
            return;
        }
        if (reservation.status() != ReservationStatus.IN_USE || reservation.labId() == null) {
            throw new BusinessException(HttpStatus.CONFLICT, "当前预约状态不可签退");
        }
        LabLockKey requiredLock = new LabLockKey(reservation.labId(), reservation.date());
        if (!requiredLock.equals(acquiredLock)) {
            throw new BusinessException(HttpStatus.CONFLICT, "预约分配已变化，请重试");
        }
        LocalDateTime now = LocalDateTime.now(clock);
        if (reservation.endTime() != null && !now.isBefore(reservation.endTime())) {
            int updated = repository.completeEndedReservation(reservationId, now);
            if (updated != 1) {
                throw new BusinessException(HttpStatus.CONFLICT, "预约状态已变化，请重试");
            }
            repository.insertSystemLog(
                    reservationId,
                    ReservationStatus.IN_USE,
                    ReservationStatus.COMPLETED,
                    "预约到期自动结束");
            return;
        }
        releaseService.releaseRemaining(reservation, now);
        int updated = repository.checkoutReservation(reservationId, userId, now, channel);
        if (updated != 1) {
            throw new BusinessException(HttpStatus.CONFLICT, "预约状态已变化，请重试");
        }
        repository.insertCheckoutLog(reservationId, userId);
    }
}
