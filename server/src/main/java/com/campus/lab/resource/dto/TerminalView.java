package com.campus.lab.resource.dto;

import com.campus.lab.reservation.ResourceStatus;

public record TerminalView(
        long deviceId,
        long labId,
        String deviceName,
        String model,
        String terminalCode,
        ResourceStatus status) {
}
