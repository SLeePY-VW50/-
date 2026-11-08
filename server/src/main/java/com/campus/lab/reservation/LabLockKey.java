package com.campus.lab.reservation;

import java.time.LocalDate;

public record LabLockKey(long labId, LocalDate date) {
}
