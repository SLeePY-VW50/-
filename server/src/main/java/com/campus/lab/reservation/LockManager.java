package com.campus.lab.reservation;

import java.time.LocalDate;
import java.util.List;

public interface LockManager {

    ReservationLock acquire(long labId, LocalDate date);

    List<ReservationLock> acquireAll(List<LabLockKey> keys);

    void release(ReservationLock lock);
}
