package com.campus.lab.reservation;

import com.campus.lab.period.PeriodCatalog;
import com.campus.lab.period.PeriodSchedule;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class ReservationReleaseService {

    private final ReservationRepository repository;
    private final PeriodCatalog periodCatalog;

    public ReservationReleaseService(ReservationRepository repository, PeriodCatalog periodCatalog) {
        this.repository = repository;
        this.periodCatalog = periodCatalog;
    }

    public void release(ReservationRepository.CancellationReservation reservation) {
        ReservationRepository.LabBitmap room = repository.lockLabBitmap(
                reservation.labId(), reservation.date());
        if (reservation.reservationMode() == ReservationMode.ROOM) {
            LocalTime end = reservation.endTime().toLocalTime();
            long requestMask = schedule(reservation).between(
                    reservation.startTime().toLocalTime(), end).value();
            repository.releaseRoomMask(
                    reservation.labId(), reservation.date(), room.version(), requestMask);
            return;
        }

        List<ReservationRepository.AssignedSeatBitmap> assignments =
                lockAndValidateAssignments(reservation);
        for (ReservationRepository.AssignedSeatBitmap assignment : assignments) {
            repository.releaseSeatMask(
                    assignment.seatId(), reservation.date(), assignment.version(), assignment.assignmentMask());
        }
    }

    public void releaseRemaining(
            ReservationRepository.CancellationReservation reservation, LocalDateTime now) {
        PeriodSchedule schedule = schedule(reservation);
        TimeSlotMask original = schedule.between(
                reservation.startTime().toLocalTime(), reservation.endTime().toLocalTime());
        if (now.toLocalDate().isAfter(reservation.date())) {
            return;
        }
        int firstReleasableSlot = 0;
        if (now.toLocalDate().isEqual(reservation.date())) {
            firstReleasableSlot = schedule.firstReleasableSlot(now.toLocalTime());
        }
        int releaseStart = Math.max(original.startSlot(), firstReleasableSlot);
        if (releaseStart >= original.endSlot()) {
            return;
        }
        long remainingMask = TimeSlotMask.of(releaseStart, original.endSlot()).value();
        ReservationRepository.LabBitmap room = repository.lockLabBitmap(
                reservation.labId(), reservation.date());
        if (reservation.reservationMode() == ReservationMode.ROOM) {
            repository.releaseRoomMask(
                    reservation.labId(), reservation.date(), room.version(), remainingMask);
            return;
        }

        List<ReservationRepository.AssignedSeatBitmap> assignments =
                lockAndValidateAssignments(reservation);
        for (ReservationRepository.AssignedSeatBitmap assignment : assignments) {
            long releaseMask = assignment.assignmentMask() & remainingMask;
            if (releaseMask != 0) {
                repository.releaseSeatMask(
                        assignment.seatId(), reservation.date(), assignment.version(), releaseMask);
            }
        }
    }

    private PeriodSchedule schedule(ReservationRepository.CancellationReservation reservation) {
        return periodCatalog.forLab(reservation.labId());
    }

    private List<ReservationRepository.AssignedSeatBitmap> lockAndValidateAssignments(
            ReservationRepository.CancellationReservation reservation) {
        List<ReservationRepository.AssignedSeatBitmap> assignments =
                repository.lockAssignedSeatBitmaps(reservation.resvId(), reservation.date());
        if (reservation.seatCount() == null || assignments.size() != reservation.seatCount()) {
            throw new IllegalStateException("正式工位分配与预约数量不一致");
        }
        return assignments;
    }
}
