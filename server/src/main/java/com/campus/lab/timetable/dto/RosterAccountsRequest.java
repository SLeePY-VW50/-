package com.campus.lab.timetable.dto;

import jakarta.validation.constraints.NotBlank;
import java.util.List;

public record RosterAccountsRequest(List<@NotBlank String> studentAccounts) {
}
