package com.campus.lab.internal;

import com.campus.lab.reservation.ReservationMode;
import com.campus.lab.reservation.ResourceStatus;
import com.campus.lab.resource.dto.LabAvailabilityView;
import com.campus.lab.resource.dto.SeatAvailabilityView;
import com.campus.lab.resource.dto.SlotAvailabilityView;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public record InternalAvailabilityView(
        long labId,
        LocalDate date,
        ReservationMode mode,
        LocalDateTime dataTime,
        ResourceStatus labStatus,
        String roomMask,
        List<SeatAvailabilityView> seats,
        List<SlotAvailabilityView> slots) {

    static InternalAvailabilityView from(
            LabAvailabilityView view, ReservationMode mode, LocalDateTime dataTime) {
        return new InternalAvailabilityView(
                view.labId(), view.date(), mode, dataTime, view.labStatus(),
                view.roomMask(), view.seats(), view.slots());
    }
}
