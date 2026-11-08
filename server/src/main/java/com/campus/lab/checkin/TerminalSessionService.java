package com.campus.lab.checkin;

import com.campus.lab.checkin.dto.TerminalSessionView;
import com.campus.lab.common.BusinessException;
import com.campus.lab.reservation.ResourceStatus;
import com.campus.lab.resource.dto.LabView;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class TerminalSessionService {

    public static final String COOKIE_NAME = "lab_terminal";
    public static final String COOKIE_PATH = "/api/checkin/terminal";
    public static final Duration SESSION_TTL = Duration.ofHours(8);
    private static final String SESSION_KEY_PREFIX = "checkin:terminal:session:";

    private final JdbcTemplate jdbcTemplate;
    private final StringRedisTemplate redisTemplate;
    private final PasswordEncoder passwordEncoder;
    private final ObjectMapper objectMapper;
    private final SecureRandom secureRandom = new SecureRandom();
    private final String unavailableTerminalHash;

    public TerminalSessionService(
            JdbcTemplate jdbcTemplate,
            StringRedisTemplate redisTemplate,
            PasswordEncoder passwordEncoder,
            ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.redisTemplate = redisTemplate;
        this.passwordEncoder = passwordEncoder;
        this.objectMapper = objectMapper;
        this.unavailableTerminalHash = passwordEncoder.encode("unavailable-terminal-placeholder");
    }

    public SessionCreation create(String terminalCode, String terminalSecret) {
        String normalizedCode = terminalCode == null ? "" : terminalCode;
        String candidateSecret = terminalSecret == null ? "" : terminalSecret;
        boolean secretLengthValid = candidateSecret.getBytes(StandardCharsets.UTF_8).length <= 72;
        List<TerminalRecord> terminals = jdbcTemplate.query("""
                SELECT d.device_id, d.lab_id, d.status AS device_status,
                       d.terminal_secret_hash, l.status AS lab_status,
                       l.lab_code, l.name, l.location, l.capacity
                FROM t_device d
                JOIN t_lab l ON l.lab_id = d.lab_id
                WHERE d.terminal_code = ? AND d.device_type = 'CHECKIN_TERMINAL'
                """, (rs, rowNum) -> new TerminalRecord(
                        rs.getLong("device_id"), rs.getLong("lab_id"),
                        rs.getString("device_status"), rs.getString("terminal_secret_hash"),
                        rs.getString("lab_status"), rs.getString("lab_code"),
                        rs.getString("name"), rs.getString("location"), rs.getInt("capacity")),
                normalizedCode);
        TerminalRecord terminal = terminals.isEmpty() ? null : terminals.getFirst();
        String hash = terminal == null ? unavailableTerminalHash : terminal.secretHash();
        boolean passwordMatches = false;
        if (secretLengthValid) {
            try {
                passwordMatches = passwordEncoder.matches(candidateSecret, hash);
            } catch (IllegalArgumentException exception) {
                passwordMatches = false;
            }
        }
        if (terminal == null || !passwordMatches
                || !"ACTIVE".equals(terminal.deviceStatus())
                || !"ACTIVE".equals(terminal.labStatus())) {
            throw invalidTerminal();
        }

        String token = newToken();
        String key = SESSION_KEY_PREFIX + token;
        try {
            redisTemplate.opsForValue().set(
                    key,
                    objectMapper.writeValueAsString(new StoredSession(terminal.deviceId(), terminal.labId())),
                    SESSION_TTL);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("终端会话无法序列化", exception);
        }
        LabView lab = new LabView(
                terminal.labId(), terminal.labCode(), terminal.labName(), terminal.location(),
                terminal.capacity(), ResourceStatus.ACTIVE);
        return new SessionCreation(
                token,
                new TerminalSessionView(terminal.deviceId(), lab, SESSION_TTL.toSeconds()));
    }

    public TerminalContext requireSession(String token) {
        if (token == null || token.isBlank() || token.length() > 256) {
            throw invalidSession();
        }
        String key = SESSION_KEY_PREFIX + token;
        String stored = redisTemplate.opsForValue().get(key);
        if (stored == null) {
            throw invalidSession();
        }
        StoredSession session;
        try {
            session = objectMapper.readValue(stored, StoredSession.class);
        } catch (JsonProcessingException exception) {
            redisTemplate.delete(key);
            throw invalidSession();
        }
        List<TerminalContext> contexts = jdbcTemplate.query("""
                SELECT d.device_id, d.lab_id
                FROM t_device d
                JOIN t_lab l ON l.lab_id = d.lab_id
                WHERE d.device_id = ? AND d.lab_id = ?
                  AND d.device_type = 'CHECKIN_TERMINAL'
                  AND d.status = 'ACTIVE' AND l.status = 'ACTIVE'
                """, (rs, rowNum) -> new TerminalContext(rs.getLong(1), rs.getLong(2)),
                session.deviceId(), session.labId());
        if (contexts.size() != 1) {
            throw invalidSession();
        }
        return contexts.getFirst();
    }

    private String newToken() {
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static BusinessException invalidTerminal() {
        return new BusinessException(HttpStatus.UNAUTHORIZED, "终端凭证无效");
    }

    private static BusinessException invalidSession() {
        return new BusinessException(HttpStatus.UNAUTHORIZED, "终端会话无效");
    }

    public record SessionCreation(String token, TerminalSessionView view) {
    }

    public record TerminalContext(long deviceId, long labId) {
    }

    private record StoredSession(long deviceId, long labId) {
    }

    private record TerminalRecord(
            long deviceId,
            long labId,
            String deviceStatus,
            String secretHash,
            String labStatus,
            String labCode,
            String labName,
            String location,
            int capacity) {
    }
}
