CREATE TABLE t_period_set (
    period_set_id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    code VARCHAR(64) NOT NULL,
    name VARCHAR(128) NOT NULL,
    is_default BOOLEAN NOT NULL DEFAULT FALSE,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (period_set_id),
    CONSTRAINT uk_t_period_set_code UNIQUE (code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE t_period (
    period_id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    period_set_id BIGINT UNSIGNED NOT NULL,
    period_no TINYINT UNSIGNED NOT NULL,
    start_time TIME NOT NULL,
    end_time TIME NOT NULL,
    session ENUM('MORNING', 'AFTERNOON', 'EVENING') NOT NULL,
    PRIMARY KEY (period_id),
    CONSTRAINT uk_t_period_set_no UNIQUE (period_set_id, period_no),
    CONSTRAINT ck_t_period_no CHECK (period_no >= 1),
    CONSTRAINT ck_t_period_span CHECK (end_time > start_time),
    CONSTRAINT fk_t_period_set FOREIGN KEY (period_set_id) REFERENCES t_period_set (period_set_id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

INSERT INTO t_period_set (code, name, is_default)
VALUES ('DONGFANG-2026-2027', '浙江财经大学东方学院2026-2027学年教学作息', TRUE);

INSERT INTO t_period (period_set_id, period_no, start_time, end_time, session)
SELECT period_set_id, period_no, start_time, end_time, session
FROM t_period_set
CROSS JOIN (
    SELECT 1 AS period_no, '08:30:00' AS start_time, '09:15:00' AS end_time, 'MORNING' AS session
    UNION ALL SELECT 2, '09:15:00', '10:00:00', 'MORNING'
    UNION ALL SELECT 3, '10:05:00', '10:50:00', 'MORNING'
    UNION ALL SELECT 4, '11:00:00', '11:45:00', 'MORNING'
    UNION ALL SELECT 5, '11:45:00', '12:30:00', 'MORNING'
    UNION ALL SELECT 6, '13:30:00', '14:15:00', 'AFTERNOON'
    UNION ALL SELECT 7, '14:15:00', '15:00:00', 'AFTERNOON'
    UNION ALL SELECT 8, '15:05:00', '15:50:00', 'AFTERNOON'
    UNION ALL SELECT 9, '16:00:00', '16:45:00', 'AFTERNOON'
    UNION ALL SELECT 10, '16:45:00', '17:30:00', 'AFTERNOON'
    UNION ALL SELECT 11, '18:30:00', '19:15:00', 'EVENING'
    UNION ALL SELECT 12, '19:15:00', '20:00:00', 'EVENING'
    UNION ALL SELECT 13, '20:05:00', '20:50:00', 'EVENING'
) periods
WHERE code = 'DONGFANG-2026-2027';

ALTER TABLE t_lab
    ADD COLUMN period_set_id BIGINT UNSIGNED NULL,
    ADD CONSTRAINT fk_t_lab_period_set FOREIGN KEY (period_set_id)
        REFERENCES t_period_set (period_set_id) ON DELETE RESTRICT;

ALTER TABLE t_time_rule
    ADD COLUMN open_start_period TINYINT UNSIGNED NOT NULL DEFAULT 1,
    ADD COLUMN open_end_period TINYINT UNSIGNED NOT NULL DEFAULT 10,
    ADD COLUMN min_periods INT UNSIGNED NULL,
    ADD COLUMN max_periods INT UNSIGNED NULL,
    ADD CONSTRAINT ck_t_time_rule_open_periods CHECK (
        open_start_period >= 1 AND open_end_period >= open_start_period
    ),
    ADD CONSTRAINT ck_t_time_rule_periods CHECK (
        min_periods IS NULL OR max_periods IS NULL OR max_periods >= min_periods
    );

ALTER TABLE t_reservation
    ADD COLUMN requested_periods INT UNSIGNED NOT NULL DEFAULT 1;

INSERT INTO t_sys_config (config_key, config_value, value_type, config_group, description)
VALUES
    ('reservation.duration.min.periods', '1', 'INTEGER', 'reservation', '全局最短预约节数'),
    ('reservation.duration.max.periods', '5', 'INTEGER', 'reservation', '全局最长预约节数');

ALTER TABLE t_slot_bitmap DROP CHECK ck_t_slot_bitmap_mask;
ALTER TABLE t_slot_bitmap ADD CONSTRAINT ck_t_slot_bitmap_mask CHECK (mask <= 9223372036854775807);
ALTER TABLE t_seat_bitmap DROP CHECK ck_t_seat_bitmap_mask;
ALTER TABLE t_seat_bitmap ADD CONSTRAINT ck_t_seat_bitmap_mask CHECK (mask <= 9223372036854775807);
ALTER TABLE t_reservation_seat DROP CHECK ck_t_reservation_seat_mask;
ALTER TABLE t_reservation_seat
    ADD CONSTRAINT ck_t_reservation_seat_mask CHECK (time_mask > 0 AND time_mask <= 9223372036854775807);
