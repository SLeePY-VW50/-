package com.campus.lab.reservation;

import com.campus.lab.common.BusinessException;
import com.campus.lab.credit.CreditService;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ReservationCancellationTransactionService {

    private static final int LATE_CANCEL_POINTS = 10;

    private final ReservationRepository repository;
    private final ReservationReleaseService releaseService;
    private final CreditService creditService;
    private final Clock clock;

    public ReservationCancellationTransactionService(
            ReservationRepository repository,
            ReservationReleaseService releaseService,
            CreditService creditService,
            Clock clock) {
        this.repository = repository;
        this.releaseService = releaseService;
        this.creditService = creditService;
        this.clock = clock;
    }

    @Transactional(rollbackFor = Exception.class)
    public void cancel(long reservationId, long userId, String reason, LabLockKey acquiredLock) {
        ReservationRepository.CancellationReservation reservation =
                repository.lockReservationForCancellation(reservationId);
        if (reservation == null || reservation.userId() != userId) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "预约不存在");
        }
        if (reservation.status() == ReservationStatus.CANCELLED) {
            return;
        }
        ReservationRepository.UserAdmission user = repository.lockUser(userId);
        if (!"ACTIVE".equals(user.status())) {
            throw new BusinessException(HttpStatus.UNAUTHORIZED, "用户不可用");
        }
        if (reservation.status() != ReservationStatus.PENDING_SCHEDULE
                && reservation.status() != ReservationStatus.APPROVED) {
            throw new BusinessException(HttpStatus.CONFLICT, "当前预约状态不可取消");
        }

        LocalDateTime start = reservation.startTime() != null
                ? reservation.startTime()
                : reservation.date().atTime(reservation.requestWindowStart());
        LocalDateTime now = LocalDateTime.now(clock);
        if (!now.isBefore(start)) {
            throw new BusinessException(HttpStatus.CONFLICT, "预约已开始，不能取消");
        }
        if (reservation.status() == ReservationStatus.APPROVED && reservation.labId() != null) {
            LabLockKey requiredLock = new LabLockKey(reservation.labId(), reservation.date());
            if (!requiredLock.equals(acquiredLock)) {
                throw new LockRequiredException();
            }
            releaseService.release(reservation);
        }
        int updated = repository.cancelReservation(
                reservation.resvId(), userId, reservation.status());
        if (updated != 1) {
            throw new BusinessException(HttpStatus.CONFLICT, "预约状态已变化，请重试");
        }
        Duration freeWindow = Duration.ofHours(repository.cancellationFreeBeforeHours());
        if (Duration.between(now, start).compareTo(freeWindow) < 0) {
            creditService.deduct(
                    userId, reservationId, "LATE_CANCEL", LATE_CANCEL_POINTS, userId);
        }
        repository.insertCancellationLog(
                reservationId, reservation.status(), userId, reason);
    }

    static class LockRequiredException extends RuntimeException {
    }
}
