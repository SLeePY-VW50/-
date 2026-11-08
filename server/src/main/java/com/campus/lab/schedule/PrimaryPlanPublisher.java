package com.campus.lab.schedule;

import com.campus.lab.common.BusinessException;
import com.campus.lab.reservation.LabLockKey;
import com.campus.lab.reservation.LockManager;
import com.campus.lab.reservation.ReservationLock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class PrimaryPlanPublisher {

    private static final Logger log = LoggerFactory.getLogger(PrimaryPlanPublisher.class);

    private final ScheduleRepository scheduleRepository;
    private final SchedulePublicationRepository publicationRepository;
    private final PrimaryPlanTransactionService transactionService;
    private final SchedulePublicationFailureService failureService;
    private final LockManager lockManager;

    public PrimaryPlanPublisher(
            ScheduleRepository scheduleRepository,
            SchedulePublicationRepository publicationRepository,
            PrimaryPlanTransactionService transactionService,
            SchedulePublicationFailureService failureService,
            LockManager lockManager) {
        this.scheduleRepository = scheduleRepository;
        this.publicationRepository = publicationRepository;
        this.transactionService = transactionService;
        this.failureService = failureService;
        this.lockManager = lockManager;
    }

    public void publish(long taskId) {
        List<ReservationLock> locks = new ArrayList<>();
        try {
            ScheduleRepository.ScheduleTask task = scheduleRepository.findTask(taskId);
            if (task == null) {
                throw new SchedulePublicationException("SCHEDULE_TASK_NOT_FOUND");
            }
            if (task.status() == ScheduleTaskStatus.PUBLISHED) {
                return;
            }
            if (!task.primary()) {
                throw new SchedulePublicationException("PRIMARY_TASK_REQUIRED");
            }
            if (task.status() != ScheduleTaskStatus.SUCCEEDED) {
                throw new SchedulePublicationException("PRIMARY_TASK_NOT_PUBLISHABLE");
            }
            List<LabLockKey> keys = publicationRepository.listPublicationKeys(taskId).stream()
                    .distinct()
                    .sorted(Comparator.comparingLong(LabLockKey::labId).thenComparing(LabLockKey::date))
                    .toList();
            if (keys.isEmpty()) {
                throw new SchedulePublicationException("UNRESOLVED_PRIMARY_PLAN");
            }
            locks.addAll(lockManager.acquireAll(keys));
            transactionService.publish(taskId);
        } catch (RuntimeException exception) {
            failureService.markFailed(taskId, failureReason(exception));
            throw exception;
        } finally {
            releaseReverse(locks);
        }
    }

    private void releaseReverse(List<ReservationLock> locks) {
        for (int index = locks.size() - 1; index >= 0; index--) {
            try {
                lockManager.release(locks.get(index));
            } catch (RuntimeException exception) {
                log.warn("释放排期发布预约锁失败: {} ({})",
                        locks.get(index).key(), exception.getClass().getSimpleName());
            }
        }
    }

    private static String failureReason(RuntimeException exception) {
        if (exception instanceof SchedulePublicationException) {
            return exception.getMessage();
        }
        if (exception instanceof BusinessException business
                && business.getStatus() == HttpStatus.CONFLICT) {
            return "PUBLICATION_CONFLICT";
        }
        return "PUBLISH_EXCEPTION:" + exception.getClass().getSimpleName();
    }
}
