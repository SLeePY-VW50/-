package com.campus.lab.reservation;

import com.campus.lab.common.BusinessException;
import com.campus.lab.reservation.dto.BatchReservationView;
import com.campus.lab.reservation.dto.PageView;
import com.campus.lab.reservation.dto.ReservationView;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ReservationQueryService {

    private final ReservationRepository repository;

    public ReservationQueryService(ReservationRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public ReservationView getOwned(long reservationId, long userId) {
        ReservationView view = repository.findView(reservationId);
        if (view == null || view.userId() != userId) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "预约不存在");
        }
        return view;
    }

    @Transactional(readOnly = true)
    public ReservationView getAuthorized(long reservationId, long userId, boolean administrator) {
        ReservationView view = repository.findView(reservationId);
        if (view == null || (!administrator && view.userId() != userId)) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "预约不存在");
        }
        return view;
    }

    @Transactional(readOnly = true)
    public PageView<ReservationView> list(
            long userId,
            boolean administrator,
            Long labId,
            java.time.LocalDate date,
            ReservationStatus status,
            int page,
            int size) {
        if (page < 0) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "页码不能为负数");
        }
        if (size < 1 || size > 100) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "每页大小必须在1到100之间");
        }
        Long ownerId = administrator ? null : userId;
        long total = repository.countViews(ownerId, labId, date, status);
        List<Long> reservationIds = repository.listViewIds(ownerId, labId, date, status, page, size);
        return new PageView<>(repository.listViewsByIds(reservationIds), page, size, total);
    }

    @Transactional(readOnly = true)
    public BatchReservationView getBatchOwned(long batchId, long userId) {
        ReservationBatchSummary batch = repository.findBatchSummary(batchId);
        if (batch == null || batch.userId() != userId) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "预约批次不存在");
        }
        List<ReservationView> items = repository.listViewsByBatch(batchId);
        int attendeeCount = items.isEmpty() ? 0 : items.getFirst().attendeeCount();
        return new BatchReservationView(
                userId, batchId, batch.batchNo(), batch.courseName(), batch.labId(), batch.labName(),
                attendeeCount, batch.purpose(), items);
    }
}
