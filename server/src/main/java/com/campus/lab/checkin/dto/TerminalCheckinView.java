package com.campus.lab.checkin.dto;

import com.campus.lab.reservation.ReservationStatus;
import com.campus.lab.reservation.dto.ReservationView;
import java.time.LocalDateTime;
import java.util.List;

public record TerminalCheckinView(
        boolean choiceRequired,
        String choiceToken,
        Long expiresIn,
        List<TerminalReservationOption> reservations,
        Long reservationId,
        ReservationStatus status,
        String checkinChannel,
        LocalDateTime checkinTime,
        boolean alreadyCheckedIn) {

    public TerminalCheckinView {
        reservations = List.copyOf(reservations);
    }

    public static TerminalCheckinView choice(
            String token, long expiresIn, List<TerminalReservationOption> options) {
        return new TerminalCheckinView(
                true, token, expiresIn, options, null, null, null, null, false);
    }

    public static TerminalCheckinView checkedIn(ReservationView view, boolean alreadyCheckedIn) {
        return new TerminalCheckinView(
                false, null, null, List.of(), view.resvId(), view.status(),
                view.checkinChannel(), view.checkinTime(), alreadyCheckedIn);
    }
}
