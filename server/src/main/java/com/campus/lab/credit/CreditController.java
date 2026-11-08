package com.campus.lab.credit;

import com.campus.lab.common.ApiResponse;
import com.campus.lab.credit.dto.CreditAccountView;
import com.campus.lab.credit.dto.ViolationView;
import com.campus.lab.reservation.dto.PageView;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/credit")
@PreAuthorize("hasAnyRole('STUDENT','TEACHER')")
public class CreditController {

    private final CreditService creditService;

    public CreditController(CreditService creditService) {
        this.creditService = creditService;
    }

    @GetMapping("/me")
    public ApiResponse<CreditAccountView> me(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            Authentication authentication) {
        return ApiResponse.success(creditService.getMyCredit(userId(authentication), page, size));
    }

    @GetMapping("/violations")
    public ApiResponse<PageView<ViolationView>> violations(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            Authentication authentication) {
        return ApiResponse.success(creditService.getMyViolations(userId(authentication), page, size));
    }

    private static long userId(Authentication authentication) {
        return Long.parseLong(authentication.getName());
    }
}
