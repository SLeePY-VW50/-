package com.campus.lab.checkin.qr;

import com.campus.lab.checkin.CheckinChannel;
import com.campus.lab.checkin.ReservationCheckinTransactionService;
import com.campus.lab.checkin.qr.dto.QrCheckinView;
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
public class QrCheckinService {

    private final CheckinQrService qrService;
    private final ReservationRepository repository;
    private final ReservationCheckinTransactionService transactionService;
    private final LockManager lockManager;

    public QrCheckinService(
            CheckinQrService qrService,
            ReservationRepository repository,
            ReservationCheckinTransactionService transactionService,
            LockManager lockManager) {
        this.qrService = qrService;
        this.repository = repository;
        this.transactionService = transactionService;
        this.lockManager = lockManager;
    }

    public QrCheckinView checkin(long reservationId, long userId, String content) {
        long labId = qrService.verify(content);
        ReservationRepository.CancellationTarget target = repository.findCancellationTarget(reservationId);
        if (target == null) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "预约不存在");
        }
        if (target.labId() == null || target.labId() != labId) {
            throw new BusinessException(HttpStatus.CONFLICT, "预约与二维码实验室不匹配");
        }
        LabLockKey key = new LabLockKey(labId, target.date());
        List<ReservationLock> acquiredLocks = lockManager.acquireAll(List.of(key));
        ReservationCheckinTransactionService.CheckinOutcome outcome;
        try {
            outcome = transactionService.checkin(
                    reservationId, userId, labId, key, CheckinChannel.WECHAT_QR);
        } finally {
            for (int index = acquiredLocks.size() - 1; index >= 0; index--) {
                lockManager.release(acquiredLocks.get(index));
            }
        }
        ReservationView view = repository.findView(reservationId);
        return new QrCheckinView(view, outcome.alreadyCheckedIn());
    }
}
