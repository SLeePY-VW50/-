package com.campus.lab.checkin.dto;

import jakarta.validation.constraints.Size;

public record TerminalSessionRequest(
        @Size(max = 64) String terminalCode,
        @Size(max = 128) String terminalSecret) {
}
