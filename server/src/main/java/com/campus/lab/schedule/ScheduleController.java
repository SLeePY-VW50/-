package com.campus.lab.schedule;

import com.campus.lab.common.ApiResponse;
import com.campus.lab.reservation.dto.PageView;
import com.campus.lab.schedule.dto.ScheduleBatchDetailView;
import com.campus.lab.schedule.dto.ScheduleBatchView;
import com.campus.lab.schedule.dto.ScheduleTaskView;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/schedules")
@PreAuthorize("hasAnyRole('LAB_ADMIN','SYS_ADMIN')")
public class ScheduleController {

    private final ScheduleQueryService queryService;

    public ScheduleController(ScheduleQueryService queryService) {
        this.queryService = queryService;
    }

    @GetMapping("/batches")
    public ApiResponse<PageView<ScheduleBatchView>> batches(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.success(queryService.listBatches(page, size));
    }

    @GetMapping("/batches/{batchNo}")
    public ApiResponse<ScheduleBatchDetailView> batch(@PathVariable String batchNo) {
        return ApiResponse.success(queryService.getBatch(batchNo));
    }

    @GetMapping("/tasks/{taskId}")
    public ApiResponse<ScheduleTaskView> task(@PathVariable long taskId) {
        return ApiResponse.success(queryService.getTask(taskId));
    }
}
