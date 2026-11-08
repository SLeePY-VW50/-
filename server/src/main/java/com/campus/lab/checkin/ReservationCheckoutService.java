package com.campus.lab.checkin;

import com.campus.lab.common.BusinessException;
import com.campus.lab.reservation.LabLockKey;
import com.campus.lab.reservation.LockManager;
import com.campus.lab.reservation.ReservationLock;
import com.campus.lab.reservation.ReservationRepository;
import com.campus.lab.reservation.dto.ReservationView;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class ReservationCheckoutService {

    private final ReservationRepository repository;
    private final ReservationCheckoutTransactionService transactionService;
    private final LockManager lockManager;

    public ReservationCheckoutService(
            ReservationRepository repository,
            ReservationCheckoutTransactionService transactionService,
            LockManager lockManager) {
        this.repository = repository;
        this.transactionService = transactionService;
        this.lockManager = lockManager;
    }

    public ReservationView checkout(long reservationId, long userId) {
        return checkout(reservationId, userId, CheckoutChannel.WEB_ACCOUNT);
    }

    public ReservationView checkout(long reservationId, long userId, CheckoutChannel channel) {
        ReservationRepository.CancellationTarget target = repository.findCancellationTarget(reservationId);
        if (target == null) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "预约不存在");
        }
        LabLockKey key = target.labId() == null ? null : new LabLockKey(target.labId(), target.date());
        List<ReservationLock> locks = key == null
                ? List.of() : lockManager.acquireAll(List.of(key));
        try {
            transactionService.checkout(reservationId, userId, key, channel);
        } finally {
            for (int index = locks.size() - 1; index >= 0; index--) {
                lockManager.release(locks.get(index));
            }
        }
        ReservationView view = repository.findView(reservationId);
        if (view == null) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "预约不存在");
        }
        return view;
    }
}
