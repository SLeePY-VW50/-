package com.campus.lab.resource;

import com.campus.lab.checkin.qr.CheckinQrService;
import com.campus.lab.checkin.qr.dto.CheckinCodeView;
import com.campus.lab.common.ApiResponse;
import com.campus.lab.resource.dto.CreateLabRequest;
import com.campus.lab.resource.dto.CreateTerminalRequest;
import com.campus.lab.resource.dto.LabView;
import com.campus.lab.resource.dto.SeatView;
import com.campus.lab.resource.dto.TerminalView;
import com.campus.lab.resource.dto.UpdateResourceStatusRequest;
import com.campus.lab.resource.dto.UpdateTimeRuleRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin")
public class AdminResourceController {

    private final AdminResourceService adminResourceService;
    private final CheckinQrService checkinQrService;
    private final ResourceService resourceService;

    public AdminResourceController(
            AdminResourceService adminResourceService,
            CheckinQrService checkinQrService,
            ResourceService resourceService) {
        this.adminResourceService = adminResourceService;
        this.checkinQrService = checkinQrService;
        this.resourceService = resourceService;
    }

    @PostMapping("/labs")
    public ApiResponse<LabView> createLab(@Valid @RequestBody CreateLabRequest request) {
        return ApiResponse.success(adminResourceService.createLab(request));
    }

    @PatchMapping("/labs/{id}/status")
    public ApiResponse<LabView> updateLabStatus(
            @PathVariable long id,
            @Valid @RequestBody UpdateResourceStatusRequest request) {
        return ApiResponse.success(adminResourceService.updateLabStatus(id, request.status()));
    }

    @PatchMapping("/seats/{id}/status")
    public ApiResponse<SeatView> updateSeatStatus(
            @PathVariable long id,
            @Valid @RequestBody UpdateResourceStatusRequest request) {
        return ApiResponse.success(adminResourceService.updateSeatStatus(id, request.status()));
    }

    @PutMapping("/labs/{labId}/rules/{ruleId}")
    public ApiResponse<com.campus.lab.resource.dto.TimeRuleView> updateRule(
            @PathVariable long labId,
            @PathVariable long ruleId,
            @Valid @RequestBody UpdateTimeRuleRequest request) {
        return ApiResponse.success(adminResourceService.updateRule(labId, ruleId, request));
    }

    @PostMapping("/terminals")
    public ApiResponse<TerminalView> createTerminal(@Valid @RequestBody CreateTerminalRequest request) {
        return ApiResponse.success(adminResourceService.createTerminal(request));
    }

    @GetMapping("/resources/labs/{labId}/checkin-code")
    public ApiResponse<CheckinCodeView> checkinCode(@PathVariable long labId) {
        resourceService.getLab(labId);
        return ApiResponse.success(new CheckinCodeView(labId, checkinQrService.contentForLab(labId)));
    }
}
