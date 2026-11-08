package com.campus.lab.credit;

import com.campus.lab.common.ApiResponse;
import com.campus.lab.credit.dto.AppealView;
import com.campus.lab.credit.dto.ReviewAppealRequest;
import com.campus.lab.reservation.dto.PageView;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/appeals")
@PreAuthorize("hasRole('SYS_ADMIN')")
public class AdminAppealController {

    private final AppealService appealService;

    public AdminAppealController(AppealService appealService) {
        this.appealService = appealService;
    }

    @GetMapping
    public ApiResponse<PageView<AppealView>> list(
            @RequestParam(required = false) AppealStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.success(appealService.list(status, page, size));
    }

    @PostMapping("/{appealId}/review")
    public ApiResponse<AppealView> review(
            @PathVariable long appealId,
            @Valid @RequestBody ReviewAppealRequest request,
            Authentication authentication) {
        long reviewerId = Long.parseLong(authentication.getName());
        return ApiResponse.success(appealService.review(
                appealId, reviewerId, request.decision(), request.opinion()));
    }
}
