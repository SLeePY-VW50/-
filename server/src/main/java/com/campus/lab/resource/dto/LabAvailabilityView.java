package com.campus.lab.resource.dto;

import com.campus.lab.reservation.ResourceStatus;
import java.time.LocalDate;
import java.util.List;

public record LabAvailabilityView(
        long labId,
        LocalDate date,
        ResourceStatus labStatus,
        String roomMask,
        List<SeatAvailabilityView> seats,
        List<SlotAvailabilityView> slots) {
}
