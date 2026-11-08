package com.campus.lab.checkin.qr.dto;

import com.campus.lab.reservation.dto.ReservationView;

public record QrCheckinView(ReservationView reservation, boolean alreadyCheckedIn) {
}
