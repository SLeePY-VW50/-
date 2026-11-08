package com.campus.lab.auth;

import com.campus.lab.auth.dto.InternalUserResponse;
import com.campus.lab.auth.dto.LoginRequest;
import com.campus.lab.auth.dto.LoginResponse;
import com.campus.lab.auth.dto.UserView;
import com.campus.lab.auth.wechat.WeChatAuthService;
import com.campus.lab.common.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class AuthController {

    private final AuthService authService;
    private final WeChatAuthService wechatAuthService;

    public AuthController(AuthService authService, WeChatAuthService wechatAuthService) {
        this.authService = authService;
        this.wechatAuthService = wechatAuthService;
    }

    @PostMapping("/auth/login")
    public ApiResponse<LoginResponse> login(@Valid @RequestBody LoginRequest request) {
        return ApiResponse.success(authService.login(request));
    }

    @PostMapping("/auth/wechat/login")
    public ApiResponse<com.campus.lab.auth.wechat.dto.WeChatLoginResponse> wechatLogin(
            @Valid @RequestBody com.campus.lab.auth.wechat.dto.WeChatLoginRequest request) {
        return ApiResponse.success(wechatAuthService.login(request.code()));
    }

    @PostMapping("/auth/wechat/bind")
    public ApiResponse<LoginResponse> wechatBind(
            @Valid @RequestBody com.campus.lab.auth.wechat.dto.WeChatBindRequest request) {
        return ApiResponse.success(wechatAuthService.bind(request));
    }

    @GetMapping("/auth/me")
    public ApiResponse<UserView> me(Authentication authentication) {
        return ApiResponse.success(authService.currentUser(authentication.getName()));
    }

    @GetMapping("/internal/auth/verify")
    public ApiResponse<InternalUserResponse> verifyInternal(Authentication authentication) {
        UserView user = authService.currentUser(authentication.getName());
        return ApiResponse.success(InternalUserResponse.from(user));
    }
}
