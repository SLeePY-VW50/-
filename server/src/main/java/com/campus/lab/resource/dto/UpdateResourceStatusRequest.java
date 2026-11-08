package com.campus.lab.resource.dto;

import com.campus.lab.reservation.ResourceStatus;
import jakarta.validation.constraints.NotNull;

public record UpdateResourceStatusRequest(@NotNull ResourceStatus status) {
}
