package com.campus.lab.checkin.dto;

import com.campus.lab.resource.dto.LabView;

public record TerminalSessionView(long deviceId, LabView lab, long expiresIn) {
}
