package com.campus.lab.experiment;

import com.campus.lab.common.ApiResponse;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/experiments")
public class ExperimentController {

    private final ExperimentService experimentService;

    public ExperimentController(ExperimentService experimentService) {
        this.experimentService = experimentService;
    }

    @PostMapping("/run")
    @PreAuthorize("hasRole('SYS_ADMIN')")
    public ApiResponse<ExperimentViews.RunView> run(
            @RequestBody ExperimentViews.RunRequest request) {
        return ApiResponse.success(experimentService.start(request));
    }

    @GetMapping("/{batchNo}")
    @PreAuthorize("hasAnyRole('LAB_ADMIN','SYS_ADMIN')")
    public ApiResponse<ExperimentViews.BatchView> get(@PathVariable String batchNo) {
        return ApiResponse.success(experimentService.get(batchNo));
    }
}
