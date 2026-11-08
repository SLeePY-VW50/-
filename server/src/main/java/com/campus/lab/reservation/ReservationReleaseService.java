package com.campus.lab.reservation;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class ReservationReleaseService {

    private final ReservationRepository repository;

    public ReservationReleaseService(ReservationRepository repository) {
        this.repository = repository;
    }

    public void release(ReservationRepository.CancellationReservation reservation) {
        ReservationRepository.LabBitmap room = repository.lockLabBitmap(
                reservation.labId(), reservation.date());
        if (reservation.reservationMode() == ReservationMode.ROOM) {
            LocalTime end = reservation.endTime().toLocalTime();
            long requestMask = TimeSlotMask.between(
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
        TimeSlotMask original = TimeSlotMask.between(
                reservation.startTime().toLocalTime(), reservation.endTime().toLocalTime());
        if (now.toLocalDate().isAfter(reservation.date())) {
            return;
        }
        int firstReleasableSlot = 0;
        if (now.toLocalDate().isEqual(reservation.date())) {
            firstReleasableSlot = (now.getHour() * 60 + now.getMinute()) / 30 + 1;
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
