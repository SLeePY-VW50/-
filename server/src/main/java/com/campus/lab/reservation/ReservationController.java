package com.campus.lab.reservation;

import com.campus.lab.common.ApiResponse;
import com.campus.lab.reservation.dto.BatchReservationRequest;
import com.campus.lab.reservation.dto.BatchReservationView;
import com.campus.lab.reservation.dto.CancelReservationRequest;
import com.campus.lab.reservation.dto.CreateReservationRequest;
import com.campus.lab.reservation.dto.PageView;
import com.campus.lab.reservation.dto.ReservationView;
import jakarta.validation.Valid;
import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/reservations")
public class ReservationController {

    private final ReservationService reservationService;
    private final ReservationQueryService queryService;
    private final ReservationLifecycleService lifecycleService;

    public ReservationController(
            ReservationService reservationService,
            ReservationQueryService queryService,
            ReservationLifecycleService lifecycleService) {
        this.reservationService = reservationService;
        this.queryService = queryService;
        this.lifecycleService = lifecycleService;
    }

    @PostMapping
    @org.springframework.security.access.prepost.PreAuthorize("hasAnyRole('STUDENT','TEACHER')")
    public ApiResponse<ReservationView> create(
            @RequestBody CreateReservationRequest request, Authentication authentication) {
        long userId = Long.parseLong(authentication.getName());
        return ApiResponse.success(reservationService.create(userId, request));
    }

    @PostMapping("/{reservationId}/cancel")
    @org.springframework.security.access.prepost.PreAuthorize("hasAnyRole('STUDENT','TEACHER')")
    public ApiResponse<ReservationView> cancel(
            @PathVariable long reservationId,
            @Valid @RequestBody(required = false) CancelReservationRequest request,
            Authentication authentication) {
        long userId = Long.parseLong(authentication.getName());
        return ApiResponse.success(lifecycleService.cancel(
                reservationId, userId, request == null ? null : request.reason()));
    }

    @PostMapping("/batches")
    @org.springframework.security.access.prepost.PreAuthorize("hasRole('TEACHER')")
    public ApiResponse<BatchReservationView> createBatch(
            @RequestBody BatchReservationRequest request, Authentication authentication) {
        long userId = Long.parseLong(authentication.getName());
        return ApiResponse.success(reservationService.createBatch(userId, request));
    }

    @GetMapping("/my")
    public ApiResponse<PageView<ReservationView>> my(
            @RequestParam(required = false) ReservationStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            Authentication authentication) {
        long userId = Long.parseLong(authentication.getName());
        return ApiResponse.success(queryService.list(userId, false, null, null, status, page, size));
    }

    @GetMapping
    public ApiResponse<PageView<ReservationView>> list(
            @RequestParam(required = false) Long labId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(required = false) ReservationStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            Authentication authentication) {
        long userId = Long.parseLong(authentication.getName());
        return ApiResponse.success(queryService.list(
                userId, isAdministrator(authentication), labId, date, status, page, size));
    }

    @GetMapping("/{reservationId}")
    public ApiResponse<ReservationView> detail(
            @PathVariable long reservationId, Authentication authentication) {
        long userId = Long.parseLong(authentication.getName());
        return ApiResponse.success(queryService.getAuthorized(
                reservationId, userId, isAdministrator(authentication)));
    }

    private static boolean isAdministrator(Authentication authentication) {
        return authentication.getAuthorities().stream()
                .anyMatch(authority -> authority.getAuthority().equals("ROLE_LAB_ADMIN")
                        || authority.getAuthority().equals("ROLE_SYS_ADMIN"));
    }
}
