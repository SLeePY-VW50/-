package com.campus.lab.internal;

import com.campus.lab.common.ApiResponse;
import com.campus.lab.reservation.ReservationMode;
import com.campus.lab.reservation.ReservationQueryService;
import com.campus.lab.reservation.ReservationStatus;
import com.campus.lab.reservation.dto.PageView;
import com.campus.lab.resource.ResourceService;
import com.campus.lab.resource.dto.LabRulesView;
import java.time.LocalDate;
import java.time.LocalDateTime;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/internal")
public class InternalToolController {

    private final ResourceService resourceService;
    private final ReservationQueryService reservationQueryService;

    public InternalToolController(
            ResourceService resourceService, ReservationQueryService reservationQueryService) {
        this.resourceService = resourceService;
        this.reservationQueryService = reservationQueryService;
    }

    @GetMapping("/rules")
    public ApiResponse<LabRulesView> rules(@RequestParam long labId) {
        return ApiResponse.success(resourceService.getRules(labId));
    }

    @GetMapping("/availability")
    public ApiResponse<InternalAvailabilityView> availability(
            @RequestParam long labId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam ReservationMode mode) {
        return ApiResponse.success(InternalAvailabilityView.from(
                resourceService.getAvailability(labId, date), mode, LocalDateTime.now()));
    }

    @GetMapping("/users/{userId}/reservations")
    public ApiResponse<PageView<InternalReservationView>> reservations(
            @PathVariable long userId,
            @RequestParam(required = false) ReservationStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        PageView<com.campus.lab.reservation.dto.ReservationView> result = reservationQueryService.list(
                userId, false, null, null, status, page, size);
        return ApiResponse.success(new PageView<>(
                result.items().stream().map(InternalReservationView::from).toList(),
                result.page(), result.size(), result.total()));
    }
}
