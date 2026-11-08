package com.campus.lab.credit;

import com.campus.lab.common.ApiResponse;
import com.campus.lab.credit.dto.AppealView;
import com.campus.lab.credit.dto.CreateAppealRequest;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/appeals")
public class AppealController {

    private final AppealService appealService;

    public AppealController(AppealService appealService) {
        this.appealService = appealService;
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('STUDENT','TEACHER')")
    public ApiResponse<AppealView> create(
            @Valid @RequestBody CreateAppealRequest request, Authentication authentication) {
        long userId = Long.parseLong(authentication.getName());
        return ApiResponse.success(appealService.create(
                userId, request.violationId(), request.reason()));
    }
}
