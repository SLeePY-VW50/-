package com.campus.lab.resource;

import com.campus.lab.common.ApiResponse;
import com.campus.lab.resource.dto.LabAvailabilityView;
import com.campus.lab.resource.dto.LabRulesView;
import com.campus.lab.resource.dto.LabView;
import java.time.LocalDate;
import java.util.List;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/labs")
public class ResourceController {

    private final ResourceService resourceService;

    public ResourceController(ResourceService resourceService) {
        this.resourceService = resourceService;
    }

    @GetMapping
    public ApiResponse<List<LabView>> listLabs() {
        return ApiResponse.success(resourceService.listLabs());
    }

    @GetMapping("/{labId}/rules")
    public ApiResponse<LabRulesView> getRules(@PathVariable long labId) {
        return ApiResponse.success(resourceService.getRules(labId));
    }

    @GetMapping("/{labId}/availability")
    public ApiResponse<LabAvailabilityView> getAvailability(
            @PathVariable long labId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return ApiResponse.success(resourceService.getAvailability(labId, date));
    }
}
