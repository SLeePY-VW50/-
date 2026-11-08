package com.campus.lab;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;

import com.campus.lab.reservation.AllocationMode;
import com.campus.lab.reservation.LockManager;
import com.campus.lab.reservation.ReservationMode;
import com.campus.lab.reservation.ReservationRepository;
import com.campus.lab.reservation.ReservationService;
import com.campus.lab.reservation.ReservationStatus;
import com.campus.lab.reservation.ReservationLock;
import com.campus.lab.reservation.dto.CreateReservationRequest;
import com.campus.lab.reservation.dto.ReservationPreferences;
import com.campus.lab.reservation.dto.ReservationView;
import java.time.LocalDate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class ReservationRollbackIntegrationTest extends AbstractReservationNonTransactionalTest {

    private static final LocalDate DATE = LocalDate.of(2026, 11, 2);

    @Autowired
    private ReservationService reservationService;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @MockitoSpyBean
    private ReservationRepository reservationRepository;

    @MockitoSpyBean
    private LockManager lockManager;

    private long labId;
    private long userId;

    @BeforeEach
    void createIndependentFixture() {
        labId = createLaboratory(4);
        userId = userIdByAccount(createTestAccount("STUDENT"));
    }

    @AfterEach
    void resetSpiesAndLockKey() {
        Mockito.reset(reservationRepository, lockManager);
        redisTemplate.delete(lockKey());
    }

    @Test
    void initialLogFailureRollsBackReservationAssignmentsAndBitmaps() {
        doThrow(new IllegalStateException("注入初始日志失败"))
                .when(reservationRepository)
                .insertInitialLog(anyLong(), anyLong(), any(ReservationStatus.class));

        assertThatThrownBy(() -> reservationService.create(userId, fixedRequest()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("初始日志失败");

        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM t_reservation WHERE user_id = ?", Integer.class, userId)).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM t_slot_bitmap WHERE lab_id = ?", Integer.class, labId)).isZero();
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM t_seat_bitmap b
                JOIN t_lab_seat s ON s.seat_id = b.seat_id WHERE s.lab_id = ?
                """, Integer.class, labId)).isZero();
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM t_reservation_log l
                JOIN t_reservation r ON r.resv_id = l.resv_id WHERE r.user_id = ?
                """, Integer.class, userId)).isZero();
        assertThat(redisTemplate.hasKey(lockKey())).isFalse();
    }

    @Test
    void lockIsReleasedOnlyAfterBusinessTransactionHasCommitted() {
        doAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM t_reservation WHERE user_id = ?", Integer.class, userId))
                    .isEqualTo(1);
            return invocation.callRealMethod();
        }).when(lockManager).release(any(ReservationLock.class));

        ReservationView created = reservationService.create(userId, fixedRequest());

        assertThat(created.status()).isEqualTo(ReservationStatus.APPROVED);
        assertThat(redisTemplate.hasKey(lockKey())).isFalse();
    }

    @Test
    void lockReleaseFailureCannotOverrideCommittedBusinessResult() {
        doThrow(new IllegalStateException("注入锁释放失败"))
                .when(lockManager).release(any(ReservationLock.class));

        ReservationView created = reservationService.create(userId, fixedRequest());

        assertThat(created.status()).isEqualTo(ReservationStatus.APPROVED);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM t_reservation WHERE resv_id = ? AND status = 'APPROVED'",
                Integer.class, created.resvId())).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM t_reservation_seat WHERE resv_id = ?",
                Integer.class, created.resvId())).isEqualTo(1);
    }

    private CreateReservationRequest fixedRequest() {
        return new CreateReservationRequest(
                AllocationMode.FIXED, labId, DATE, "09:00", "10:00",
                null, null, null, ReservationMode.SEAT, 1, 1,
                "独立回滚测试", ReservationPreferences.empty());
    }

    private String lockKey() {
        return "resv:lab:" + labId + ":" + DATE;
    }
}
