package com.campus.lab.checkin.dto;

import jakarta.validation.constraints.Size;

public record TerminalAccountRequest(
        @Size(max = 32) String account,
        @Size(max = 128) String password) {
}
