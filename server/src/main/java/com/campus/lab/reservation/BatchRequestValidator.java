package com.campus.lab.reservation;

import com.campus.lab.common.BusinessException;
import com.campus.lab.reservation.dto.BatchReservationRequest;
import com.campus.lab.reservation.dto.CreateReservationRequest;
import com.campus.lab.reservation.dto.ReservationPreferences;
import java.util.Comparator;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
public class BatchRequestValidator {

    private final ReservationRequestValidator reservationValidator;

    public BatchRequestValidator(ReservationRequestValidator reservationValidator) {
        this.reservationValidator = reservationValidator;
    }

    public NormalizedBatch normalize(BatchReservationRequest request) {
        if (request == null || request.courseName() == null || request.courseName().isBlank()
                || request.courseName().length() > 128 || request.labId() == null
                || request.attendeeCount() == null || request.attendeeCount() <= 0
                || request.purpose() == null || request.purpose().isBlank()
                || request.items() == null || request.items().isEmpty()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "教师批次参数不完整或无效");
        }
        if (request.items().size() > 14) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "教师批次最多允许14条明细");
        }
        if (request.items().stream().anyMatch(java.util.Objects::isNull)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "批次明细不能为null");
        }
        List<NormalizedReservation> items = request.items().stream()
                .map(item -> reservationValidator.normalize(new CreateReservationRequest(
                        AllocationMode.FIXED, request.labId(), item.date(), item.startTime(), item.endTime(),
                        null, null, null, ReservationMode.ROOM, request.attendeeCount(), null,
                        request.purpose(), ReservationPreferences.empty())))
                .sorted(Comparator.comparing(NormalizedReservation::date)
                        .thenComparingInt(item -> item.timeMask().startSlot())
                        .thenComparingInt(item -> item.timeMask().endSlot()))
                .toList();
        for (int index = 1; index < items.size(); index++) {
            NormalizedReservation previous = items.get(index - 1);
            NormalizedReservation current = items.get(index);
            if (previous.date().equals(current.date())
                    && previous.timeMask().endSlot() > current.timeMask().startSlot()) {
                throw new BusinessException(HttpStatus.BAD_REQUEST, "同一批次的日期时段不能重叠");
            }
        }
        return new NormalizedBatch(
                request.courseName().trim(), request.labId(), request.attendeeCount(),
                request.purpose().trim(), items);
    }
}
