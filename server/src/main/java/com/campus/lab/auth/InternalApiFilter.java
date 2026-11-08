package com.campus.lab.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class InternalApiFilter extends OncePerRequestFilter {

    public static final String INTERNAL_TOKEN_HEADER = "X-Internal-Service-Token";

    private final byte[] expectedToken;
    private final SecurityErrorWriter errorWriter;

    public InternalApiFilter(
            @Value("${app.internal-token}") String internalToken,
            SecurityErrorWriter errorWriter) {
        if (internalToken.isBlank()) {
            throw new IllegalStateException("内部服务凭据不能为空");
        }
        this.expectedToken = internalToken.getBytes(StandardCharsets.UTF_8);
        this.errorWriter = errorWriter;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return !path.equals("/api/internal") && !path.startsWith("/api/internal/");
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        String actualToken = request.getHeader(INTERNAL_TOKEN_HEADER);
        boolean valid = actualToken != null && MessageDigest.isEqual(
                expectedToken, actualToken.getBytes(StandardCharsets.UTF_8));
        if (!valid) {
            errorWriter.write(response, HttpStatus.UNAUTHORIZED, "内部服务凭据无效");
            return;
        }
        filterChain.doFilter(request, response);
    }
}
