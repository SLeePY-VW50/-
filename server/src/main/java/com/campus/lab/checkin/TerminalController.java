package com.campus.lab.checkin;

import com.campus.lab.checkin.dto.TerminalAccountRequest;
import com.campus.lab.checkin.dto.TerminalCheckinView;
import com.campus.lab.checkin.dto.TerminalChoiceRequest;
import com.campus.lab.checkin.dto.TerminalSessionRequest;
import com.campus.lab.checkin.dto.TerminalSessionView;
import com.campus.lab.common.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/checkin/terminal")
public class TerminalController {

    private final TerminalSessionService sessionService;
    private final TerminalCheckinService checkinService;
    private final PublicCheckinRateLimiter rateLimiter;
    private final boolean secureCookie;

    public TerminalController(
            TerminalSessionService sessionService,
            TerminalCheckinService checkinService,
            PublicCheckinRateLimiter rateLimiter,
            @Value("${app.checkin.terminal.cookie.secure:false}") boolean secureCookie) {
        this.sessionService = sessionService;
        this.checkinService = checkinService;
        this.rateLimiter = rateLimiter;
        this.secureCookie = secureCookie;
    }

    @PostMapping("/session")
    public ResponseEntity<ApiResponse<TerminalSessionView>> createSession(
            @Valid @RequestBody TerminalSessionRequest request,
            HttpServletRequest httpRequest) {
        rateLimiter.checkSession(request.terminalCode(), httpRequest.getRemoteAddr());
        TerminalSessionService.SessionCreation creation = sessionService.create(
                request.terminalCode(), request.terminalSecret());
        ResponseCookie cookie = ResponseCookie.from(TerminalSessionService.COOKIE_NAME, creation.token())
                .httpOnly(true)
                .secure(secureCookie)
                .sameSite("Strict")
                .path(TerminalSessionService.COOKIE_PATH)
                .maxAge(TerminalSessionService.SESSION_TTL)
                .build();
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, cookie.toString())
                .body(ApiResponse.success(creation.view()));
    }

    @PostMapping
    public ApiResponse<TerminalCheckinView> checkin(
            @CookieValue(value = TerminalSessionService.COOKIE_NAME, required = false) String sessionToken,
            @Valid @RequestBody TerminalAccountRequest request,
            HttpServletRequest httpRequest) {
        TerminalSessionService.TerminalContext terminal = sessionService.requireSession(sessionToken);
        rateLimiter.checkAccount(terminal.deviceId(), httpRequest.getRemoteAddr());
        return ApiResponse.success(checkinService.checkin(
                terminal, request.account(), request.password()));
    }

    @PostMapping("/select")
    public ApiResponse<TerminalCheckinView> select(
            @CookieValue(value = TerminalSessionService.COOKIE_NAME, required = false) String sessionToken,
            @RequestBody TerminalChoiceRequest request) {
        TerminalSessionService.TerminalContext terminal = sessionService.requireSession(sessionToken);
        return ApiResponse.success(checkinService.select(
                terminal, request.choiceToken(), request.reservationId()));
    }
}
