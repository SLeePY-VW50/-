package com.campus.lab.period;

import com.campus.lab.common.ApiResponse;
import com.campus.lab.period.dto.AssignPeriodSetRequest;
import com.campus.lab.period.dto.PeriodSetView;
import com.campus.lab.period.dto.SavePeriodSetRequest;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/period-sets")
public class PeriodAdminController {

    private final PeriodAdminService service;

    public PeriodAdminController(PeriodAdminService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<List<PeriodSetView>> list() {
        return ApiResponse.success(service.list());
    }

    @GetMapping("/{periodSetId}")
    public ApiResponse<PeriodSetView> get(@PathVariable long periodSetId) {
        return ApiResponse.success(service.get(periodSetId));
    }

    @PostMapping
    public ApiResponse<PeriodSetView> create(@Valid @RequestBody SavePeriodSetRequest request) {
        return ApiResponse.success(service.create(request));
    }

    @PutMapping("/{periodSetId}")
    public ApiResponse<PeriodSetView> replace(
            @PathVariable long periodSetId, @Valid @RequestBody SavePeriodSetRequest request) {
        return ApiResponse.success(service.replace(periodSetId, request));
    }

    @PutMapping("/labs/{labId}")
    public ApiResponse<Void> assign(
            @PathVariable long labId, @Valid @RequestBody AssignPeriodSetRequest request) {
        service.assignToLab(labId, request);
        return ApiResponse.success(null);
    }
}
