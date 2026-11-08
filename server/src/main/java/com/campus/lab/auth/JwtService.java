package com.campus.lab.auth;

import com.campus.lab.auth.dto.UserView;
import java.time.Duration;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

@Service
public class JwtService {

    private final JwtEncoder jwtEncoder;
    private final Duration expiration;

    public JwtService(JwtEncoder jwtEncoder, @Value("${app.jwt.expiration}") Duration expiration) {
        if (expiration.isZero() || expiration.isNegative()) {
            throw new IllegalStateException("JWT 过期时间必须大于零");
        }
        this.jwtEncoder = jwtEncoder;
        this.expiration = expiration;
    }

    public String issueToken(UserView user) {
        Instant expiresAt = Instant.now().plus(expiration);
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .subject(user.userId().toString())
                .claim("roles", user.roles())
                .expiresAt(expiresAt)
                .build();
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        return jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }
}
