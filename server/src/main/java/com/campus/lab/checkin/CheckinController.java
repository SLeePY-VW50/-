package com.campus.lab.checkin;

import com.campus.lab.checkin.qr.QrCheckinService;
import com.campus.lab.checkin.qr.dto.QrCheckinRequest;
import com.campus.lab.checkin.qr.dto.QrCheckinView;
import com.campus.lab.common.ApiResponse;
import com.campus.lab.reservation.dto.ReservationView;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/checkin")
public class CheckinController {

    private final ReservationCheckoutService checkoutService;
    private final QrCheckinService qrCheckinService;

    public CheckinController(
            ReservationCheckoutService checkoutService, QrCheckinService qrCheckinService) {
        this.checkoutService = checkoutService;
        this.qrCheckinService = qrCheckinService;
    }

    @PostMapping("/qr")
    @PreAuthorize("hasAnyRole('STUDENT','TEACHER')")
    public ApiResponse<QrCheckinView> qrCheckin(
            @Valid @RequestBody QrCheckinRequest request, Authentication authentication) {
        return ApiResponse.success(qrCheckinService.checkin(request.reservationId(),
                Long.parseLong(authentication.getName()), request.content()));
    }

    @PostMapping("/{reservationId}/checkout")
    @PreAuthorize("hasAnyRole('STUDENT','TEACHER')")
    public ApiResponse<ReservationView> checkout(
            @PathVariable long reservationId,
            @RequestBody(required = false) com.campus.lab.checkin.dto.CheckoutReservationRequest request,
            Authentication authentication) {
        CheckoutChannel channel = request == null || request.channel() == null
                ? CheckoutChannel.WEB_ACCOUNT : request.channel();
        long userId = Long.parseLong(authentication.getName());
        return ApiResponse.success(checkoutService.checkout(reservationId, userId, channel));
    }
}
