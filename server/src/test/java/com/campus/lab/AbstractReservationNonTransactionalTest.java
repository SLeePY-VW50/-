package com.campus.lab;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles({"local", "test"})
@Import(TestDatabaseConfiguration.class)
public abstract class AbstractReservationNonTransactionalTest {

    @Autowired
    protected MockMvc mockMvc;

    @Autowired
    protected ObjectMapper objectMapper;

    @Autowired
    protected JdbcTemplate jdbcTemplate;

    @Autowired
    protected StringRedisTemplate redisTemplate;

    private final List<Long> createdLabIds = new ArrayList<>();
    private final List<Long> createdUserIds = new ArrayList<>();

    @AfterEach
    void cleanIndependentFixture() {
        for (long userId : createdUserIds) {
            deleteReservationsByUser(userId);
        }
        for (long labId : createdLabIds) {
            deleteReservationsByLab(labId);
            jdbcTemplate.update("""
                    DELETE b FROM t_seat_bitmap b
                    JOIN t_lab_seat s ON s.seat_id = b.seat_id WHERE s.lab_id = ?
                    """, labId);
            jdbcTemplate.update("DELETE FROM t_slot_bitmap WHERE lab_id = ?", labId);
            jdbcTemplate.update("DELETE FROM t_lab_seat WHERE lab_id = ?", labId);
            jdbcTemplate.update("DELETE FROM t_time_rule WHERE lab_id = ?", labId);
            jdbcTemplate.update("DELETE FROM t_device WHERE lab_id = ?", labId);
            jdbcTemplate.update("DELETE FROM t_lab WHERE lab_id = ?", labId);
        }
        for (long userId : createdUserIds) {
            jdbcTemplate.update("DELETE FROM sys_user_role WHERE user_id = ?", userId);
            jdbcTemplate.update("DELETE FROM sys_user WHERE user_id = ?", userId);
        }
    }

    protected String loginAndGetToken(String account, String password) throws Exception {
        String response = mockMvc.perform(post("/api/auth/login")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "account", account, "password", password))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode body = objectMapper.readTree(response);
        assertThat(body.path("code").asInt()).isZero();
        return body.path("data").path("token").asText();
    }

    protected String createTestAccount(String roleCode) {
        String account = "U" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
        jdbcTemplate.update("""
                INSERT INTO sys_user (account, name, password_hash, credit_score, status)
                SELECT ?, '独立预约测试账号', password_hash, 100, 'ACTIVE'
                FROM sys_user WHERE account = '20240001'
                """, account);
        Long userId = jdbcTemplate.queryForObject(
                "SELECT user_id FROM sys_user WHERE account = ?", Long.class, account);
        assertThat(userId).isNotNull();
        createdUserIds.add(userId);
        jdbcTemplate.update("""
                INSERT INTO sys_user_role (user_id, role_id)
                SELECT ?, role_id FROM sys_role WHERE role_code = ?
                """, userId, roleCode);
        return account;
    }

    protected long userIdByAccount(String account) {
        Long userId = jdbcTemplate.queryForObject(
                "SELECT user_id FROM sys_user WHERE account = ?", Long.class, account);
        assertThat(userId).isNotNull();
        return userId;
    }

    protected long createLaboratory(int seatCount) {
        String code = "T" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
        jdbcTemplate.update("""
                INSERT INTO t_lab (lab_code, name, location, capacity, status)
                VALUES (?, '独立预约测试实验室', '测试位置', ?, 'ACTIVE')
                """, code, seatCount);
        Long labId = jdbcTemplate.queryForObject(
                "SELECT lab_id FROM t_lab WHERE lab_code = ?", Long.class, code);
        assertThat(labId).isNotNull();
        createdLabIds.add(labId);
        String[] specifications = {"STANDARD", "HIGH", "GPU"};
        for (int index = 1; index <= seatCount; index++) {
            jdbcTemplate.update("""
                    INSERT INTO t_lab_seat
                        (lab_id, seat_code, zone_code, seat_index, spec_level, near_window, seat_status)
                    VALUES (?, ?, ?, ?, ?, ?, 'ACTIVE')
                    """, labId, "S" + index, index <= (seatCount + 1) / 2 ? "NORTH" : "SOUTH",
                    index, specifications[(index - 1) % specifications.length], index % 3 == 0);
        }
        for (int day = 1; day <= 7; day++) {
            jdbcTemplate.update("""
                    INSERT INTO t_time_rule (lab_id, day_of_week, open_time, close_time)
                    VALUES (?, ?, '08:00:00', '22:00:00')
                    """, labId, day);
        }
        return labId;
    }

    protected JsonNode responseJson(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    protected Set<String> scanKeys(String pattern) {
        return redisTemplate.execute((RedisCallback<Set<String>>) connection -> {
            Set<String> keys = new HashSet<>();
            ScanOptions options = ScanOptions.scanOptions().match(pattern).count(200).build();
            try (Cursor<byte[]> cursor = connection.scan(options)) {
                cursor.forEachRemaining(key -> keys.add(redisTemplate.getStringSerializer().deserialize(key)));
            }
            return keys;
        });
    }

    protected void cleanOwnedRedisKeys() {
        for (String pattern : new String[] {"checkin:*", "resv:*"}) {
            Set<String> keys = scanKeys(pattern);
            if (!keys.isEmpty()) {
                redisTemplate.delete(keys);
            }
        }
    }

    @AfterEach
    void cleanOwnedRedisKeysAfterEach() {
        cleanOwnedRedisKeys();
    }

    private void deleteReservationsByUser(long userId) {
        jdbcTemplate.update("DELETE FROM t_appeal WHERE user_id = ?", userId);
        jdbcTemplate.update("DELETE FROM t_credit_record WHERE user_id = ?", userId);
        jdbcTemplate.update("DELETE FROM t_violation WHERE user_id = ?", userId);
        jdbcTemplate.update("""
                DELETE l FROM t_reservation_log l
                JOIN t_reservation r ON r.resv_id = l.resv_id WHERE r.user_id = ?
                """, userId);
        jdbcTemplate.update("""
                DELETE rs FROM t_reservation_seat rs
                JOIN t_reservation r ON r.resv_id = rs.resv_id WHERE r.user_id = ?
                """, userId);
        jdbcTemplate.update("DELETE FROM t_reservation WHERE user_id = ?", userId);
        jdbcTemplate.update("DELETE FROM t_reservation_batch WHERE user_id = ?", userId);
    }

    private void deleteReservationsByLab(long labId) {
        jdbcTemplate.update("""
                DELETE l FROM t_reservation_log l
                JOIN t_reservation r ON r.resv_id = l.resv_id WHERE r.lab_id = ?
                """, labId);
        jdbcTemplate.update("""
                DELETE rs FROM t_reservation_seat rs
                JOIN t_reservation r ON r.resv_id = rs.resv_id WHERE r.lab_id = ?
                """, labId);
        jdbcTemplate.update("DELETE FROM t_reservation WHERE lab_id = ?", labId);
        jdbcTemplate.update("DELETE FROM t_reservation_batch WHERE lab_id = ?", labId);
    }
}
