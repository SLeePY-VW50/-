package com.campus.lab.auth;

import com.campus.lab.auth.dto.UpdateUserStatusRequest;
import com.campus.lab.auth.dto.UserView;
import com.campus.lab.common.ApiResponse;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/users")
public class AdminUserController {

    private final AuthService authService;

    public AdminUserController(AuthService authService) {
        this.authService = authService;
    }

    @GetMapping
    public ApiResponse<List<UserView>> listUsers() {
        return ApiResponse.success(authService.listUsers());
    }

    @PatchMapping("/{id}/status")
    public ApiResponse<UserView> updateStatus(
            @PathVariable long id,
            @Valid @RequestBody UpdateUserStatusRequest request) {
        return ApiResponse.success(authService.updateUserStatus(id, request.status()));
    }
}
