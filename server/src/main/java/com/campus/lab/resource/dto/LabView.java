package com.campus.lab.resource.dto;

import com.campus.lab.reservation.ResourceStatus;

public record LabView(
        long labId,
        String code,
        String name,
        String location,
        int capacity,
        ResourceStatus status) {
}
