package com.campus.lab.reservation;

import com.campus.lab.common.BusinessException;
import com.campus.lab.reservation.dto.ReservationView;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class ReservationLifecycleService {

    private final ReservationRepository repository;
    private final ReservationCancellationTransactionService transactionService;
    private final ReservationQueryService queryService;
    private final LockManager lockManager;

    public ReservationLifecycleService(
            ReservationRepository repository,
            ReservationCancellationTransactionService transactionService,
            ReservationQueryService queryService,
            LockManager lockManager) {
        this.repository = repository;
        this.transactionService = transactionService;
        this.queryService = queryService;
        this.lockManager = lockManager;
    }

    public ReservationView cancel(long reservationId, long userId, String reason) {
        String normalizedReason = reason == null || reason.isBlank() ? "用户取消预约" : reason.trim();
        for (int attempt = 0; attempt < 2; attempt++) {
            ReservationRepository.CancellationTarget target = repository.findCancellationTarget(reservationId);
            LabLockKey key = target != null && target.labId() != null
                    ? new LabLockKey(target.labId(), target.date()) : null;
            List<ReservationLock> locks = key == null
                    ? List.of() : lockManager.acquireAll(List.of(key));
            try {
                transactionService.cancel(reservationId, userId, normalizedReason, key);
                return queryService.getOwned(reservationId, userId);
            } catch (ReservationCancellationTransactionService.LockRequiredException exception) {
                if (attempt == 1) {
                    throw new BusinessException(HttpStatus.CONFLICT, "预约分配已变化，请重试");
                }
            } finally {
                releaseReverse(locks);
            }
        }
        throw new IllegalStateException("取消重试未结束");
    }

    private void releaseReverse(List<ReservationLock> locks) {
        for (int index = locks.size() - 1; index >= 0; index--) {
            try {
                lockManager.release(locks.get(index));
            } catch (RuntimeException ignored) {
            }
        }
    }
}
