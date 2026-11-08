package com.campus.lab.reservation;

import com.campus.lab.reservation.dto.BatchReservationRequest;
import com.campus.lab.reservation.dto.BatchReservationView;
import com.campus.lab.reservation.dto.CreateReservationRequest;
import com.campus.lab.reservation.dto.ReservationView;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class ReservationService {

    private final ReservationRequestValidator requestValidator;
    private final BatchRequestValidator batchRequestValidator;
    private final ReservationTransactionService transactionService;
    private final ReservationQueryService queryService;
    private final LockManager lockManager;

    public ReservationService(
            ReservationRequestValidator requestValidator,
            BatchRequestValidator batchRequestValidator,
            ReservationTransactionService transactionService,
            ReservationQueryService queryService,
            LockManager lockManager) {
        this.requestValidator = requestValidator;
        this.batchRequestValidator = batchRequestValidator;
        this.transactionService = transactionService;
        this.queryService = queryService;
        this.lockManager = lockManager;
    }

    public ReservationView create(long userId, CreateReservationRequest request) {
        NormalizedReservation normalized = requestValidator.normalize(request);
        long reservationId;
        if (normalized.allocationMode() == AllocationMode.SCHEDULABLE) {
            reservationId = transactionService.createSchedulable(userId, normalized);
        } else {
            List<ReservationLock> locks = lockManager.acquireAll(
                    List.of(new LabLockKey(normalized.labId(), normalized.date())));
            try {
                reservationId = transactionService.createFixed(userId, normalized);
            } finally {
                releaseReverse(locks);
            }
        }
        return queryService.getOwned(reservationId, userId);
    }

    public BatchReservationView createBatch(long userId, BatchReservationRequest request) {
        NormalizedBatch normalized = batchRequestValidator.normalize(request);
        List<LabLockKey> keys = normalized.items().stream()
                .map(item -> new LabLockKey(item.labId(), item.date()))
                .toList();
        List<ReservationLock> locks = lockManager.acquireAll(keys);
        long batchId;
        try {
            batchId = transactionService.createBatch(userId, normalized);
        } finally {
            releaseReverse(locks);
        }
        return queryService.getBatchOwned(batchId, userId);
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
