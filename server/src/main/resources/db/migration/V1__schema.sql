CREATE TABLE sys_user (
    user_id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    account VARCHAR(32) NOT NULL,
    name VARCHAR(64) NOT NULL,
    phone VARCHAR(20) NULL,
    password_hash VARCHAR(100) NOT NULL,
    credit_score INT NOT NULL DEFAULT 100,
    status ENUM('ACTIVE', 'DISABLED') NOT NULL DEFAULT 'ACTIVE',
    wechat_openid VARCHAR(64) NULL,
    wechat_bind_time DATETIME(3) NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (user_id),
    CONSTRAINT uk_sys_user_account UNIQUE (account),
    CONSTRAINT uk_sys_user_wechat_openid UNIQUE (wechat_openid),
    CONSTRAINT ck_sys_user_credit CHECK (credit_score BETWEEN 0 AND 100),
    KEY idx_sys_user_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE sys_role (
    role_id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    role_code VARCHAR(32) NOT NULL,
    role_name VARCHAR(64) NOT NULL,
    PRIMARY KEY (role_id),
    CONSTRAINT uk_sys_role_code UNIQUE (role_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE sys_permission (
    perm_id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    perm_code VARCHAR(64) NOT NULL,
    perm_name VARCHAR(64) NOT NULL,
    PRIMARY KEY (perm_id),
    CONSTRAINT uk_sys_permission_code UNIQUE (perm_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE sys_user_role (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    user_id BIGINT UNSIGNED NOT NULL,
    role_id BIGINT UNSIGNED NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_sys_user_role UNIQUE (user_id, role_id),
    CONSTRAINT fk_sys_user_role_user FOREIGN KEY (user_id) REFERENCES sys_user (user_id) ON DELETE CASCADE,
    CONSTRAINT fk_sys_user_role_role FOREIGN KEY (role_id) REFERENCES sys_role (role_id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE sys_role_permission (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    role_id BIGINT UNSIGNED NOT NULL,
    perm_id BIGINT UNSIGNED NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_sys_role_permission UNIQUE (role_id, perm_id),
    CONSTRAINT fk_sys_role_permission_role FOREIGN KEY (role_id) REFERENCES sys_role (role_id) ON DELETE CASCADE,
    CONSTRAINT fk_sys_role_permission_perm FOREIGN KEY (perm_id) REFERENCES sys_permission (perm_id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE t_lab (
    lab_id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    lab_code VARCHAR(32) NOT NULL,
    name VARCHAR(100) NOT NULL,
    location VARCHAR(200) NOT NULL,
    capacity INT UNSIGNED NOT NULL,
    status ENUM('ACTIVE', 'DISABLED', 'MAINTENANCE') NOT NULL DEFAULT 'ACTIVE',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (lab_id),
    CONSTRAINT uk_t_lab_code UNIQUE (lab_code),
    CONSTRAINT ck_t_lab_capacity CHECK (capacity > 0),
    KEY idx_t_lab_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE t_lab_seat (
    seat_id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    lab_id BIGINT UNSIGNED NOT NULL,
    seat_code VARCHAR(32) NOT NULL,
    zone_code VARCHAR(32) NOT NULL,
    seat_index INT UNSIGNED NOT NULL,
    spec_level VARCHAR(32) NOT NULL,
    near_window BOOLEAN NOT NULL DEFAULT FALSE,
    seat_status ENUM('ACTIVE', 'DISABLED', 'MAINTENANCE') NOT NULL DEFAULT 'ACTIVE',
    PRIMARY KEY (seat_id),
    CONSTRAINT uk_t_lab_seat_code UNIQUE (lab_id, seat_code),
    CONSTRAINT uk_t_lab_seat_index UNIQUE (lab_id, seat_index),
    CONSTRAINT ck_t_lab_seat_index CHECK (seat_index > 0),
    KEY idx_t_lab_seat_status (lab_id, seat_status),
    CONSTRAINT fk_t_lab_seat_lab FOREIGN KEY (lab_id) REFERENCES t_lab (lab_id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE t_device (
    device_id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    lab_id BIGINT UNSIGNED NOT NULL,
    device_type ENUM('GENERAL', 'CHECKIN_TERMINAL') NOT NULL DEFAULT 'GENERAL',
    device_name VARCHAR(100) NOT NULL,
    model VARCHAR(100) NULL,
    status ENUM('ACTIVE', 'DISABLED', 'MAINTENANCE') NOT NULL DEFAULT 'ACTIVE',
    terminal_code VARCHAR(64) NULL,
    terminal_secret_hash VARCHAR(255) NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (device_id),
    CONSTRAINT uk_t_device_terminal_code UNIQUE (terminal_code),
    CONSTRAINT ck_t_device_terminal CHECK (
        device_type <> 'CHECKIN_TERMINAL'
        OR (terminal_code IS NOT NULL AND terminal_secret_hash IS NOT NULL)
    ),
    KEY idx_t_device_lab_type_status (lab_id, device_type, status),
    CONSTRAINT fk_t_device_lab FOREIGN KEY (lab_id) REFERENCES t_lab (lab_id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE t_time_rule (
    rule_id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    lab_id BIGINT UNSIGNED NOT NULL,
    day_of_week TINYINT UNSIGNED NOT NULL,
    open_time TIME NOT NULL,
    close_time TIME NOT NULL,
    min_duration_minutes INT UNSIGNED NULL,
    max_duration_minutes INT UNSIGNED NULL,
    advance_min_days INT UNSIGNED NULL,
    advance_max_days INT UNSIGNED NULL,
    PRIMARY KEY (rule_id),
    CONSTRAINT uk_t_time_rule_lab_day UNIQUE (lab_id, day_of_week),
    CONSTRAINT ck_t_time_rule_day CHECK (day_of_week BETWEEN 1 AND 7),
    CONSTRAINT ck_t_time_rule_hours CHECK (close_time > open_time),
    CONSTRAINT ck_t_time_rule_duration CHECK (
        min_duration_minutes IS NULL OR max_duration_minutes IS NULL
        OR max_duration_minutes >= min_duration_minutes
    ),
    CONSTRAINT ck_t_time_rule_advance CHECK (
        advance_min_days IS NULL OR advance_max_days IS NULL
        OR advance_max_days >= advance_min_days
    ),
    CONSTRAINT fk_t_time_rule_lab FOREIGN KEY (lab_id) REFERENCES t_lab (lab_id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE t_slot_bitmap (
    bitmap_id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    lab_id BIGINT UNSIGNED NOT NULL,
    bitmap_date DATE NOT NULL,
    mask BIGINT UNSIGNED NOT NULL DEFAULT 0,
    version INT UNSIGNED NOT NULL DEFAULT 0,
    PRIMARY KEY (bitmap_id),
    CONSTRAINT uk_t_slot_bitmap_lab_date UNIQUE (lab_id, bitmap_date),
    CONSTRAINT ck_t_slot_bitmap_mask CHECK (mask <= 281474976710655),
    CONSTRAINT fk_t_slot_bitmap_lab FOREIGN KEY (lab_id) REFERENCES t_lab (lab_id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE t_seat_bitmap (
    bitmap_id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    seat_id BIGINT UNSIGNED NOT NULL,
    bitmap_date DATE NOT NULL,
    mask BIGINT UNSIGNED NOT NULL DEFAULT 0,
    version INT UNSIGNED NOT NULL DEFAULT 0,
    PRIMARY KEY (bitmap_id),
    CONSTRAINT uk_t_seat_bitmap_seat_date UNIQUE (seat_id, bitmap_date),
    CONSTRAINT ck_t_seat_bitmap_mask CHECK (mask <= 281474976710655),
    CONSTRAINT fk_t_seat_bitmap_seat FOREIGN KEY (seat_id) REFERENCES t_lab_seat (seat_id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE t_sys_config (
    config_id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    config_key VARCHAR(64) NOT NULL,
    config_value VARCHAR(1024) NOT NULL,
    value_type ENUM('STRING', 'INTEGER', 'DECIMAL', 'BOOLEAN', 'JSON') NOT NULL,
    config_group VARCHAR(32) NOT NULL,
    description VARCHAR(255) NOT NULL,
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (config_id),
    CONSTRAINT uk_t_sys_config_key UNIQUE (config_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE t_reservation_batch (
    batch_id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    batch_no VARCHAR(32) NOT NULL,
    user_id BIGINT UNSIGNED NOT NULL,
    lab_id BIGINT UNSIGNED NOT NULL,
    course_name VARCHAR(128) NOT NULL,
    purpose VARCHAR(500) NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (batch_id),
    CONSTRAINT uk_t_reservation_batch_no UNIQUE (batch_no),
    KEY idx_t_reservation_batch_user (user_id),
    CONSTRAINT fk_t_reservation_batch_user FOREIGN KEY (user_id) REFERENCES sys_user (user_id) ON DELETE RESTRICT,
    CONSTRAINT fk_t_reservation_batch_lab FOREIGN KEY (lab_id) REFERENCES t_lab (lab_id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE t_reservation (
    resv_id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    batch_id BIGINT UNSIGNED NULL,
    user_id BIGINT UNSIGNED NOT NULL,
    allocation_mode ENUM('FIXED', 'SCHEDULABLE') NOT NULL,
    reservation_mode ENUM('ROOM', 'SEAT') NOT NULL,
    request_date DATE NOT NULL,
    request_window_start TIME NOT NULL,
    request_window_end TIME NOT NULL,
    requested_duration_minutes INT UNSIGNED NOT NULL,
    lab_id BIGINT UNSIGNED NULL,
    start_time DATETIME(3) NULL,
    end_time DATETIME(3) NULL,
    attendee_count INT UNSIGNED NOT NULL,
    purpose VARCHAR(500) NOT NULL,
    preferences JSON NULL,
    checkin_channel ENUM('WECHAT_QR', 'TERMINAL_ACCOUNT') NULL,
    checkin_time DATETIME(3) NULL,
    checkout_channel ENUM('WECHAT_QR', 'TERMINAL_ACCOUNT') NULL,
    checkout_time DATETIME(3) NULL,
    status ENUM('PENDING_SCHEDULE', 'APPROVED', 'IN_USE', 'COMPLETED', 'CANCELLED', 'NO_SHOW', 'EXPIRED', 'VIOLATION_TERMINATED') NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (resv_id),
    CONSTRAINT ck_t_reservation_request_window CHECK (request_window_end > request_window_start),
    CONSTRAINT ck_t_reservation_duration CHECK (requested_duration_minutes > 0),
    CONSTRAINT ck_t_reservation_attendees CHECK (attendee_count > 0),
    CONSTRAINT ck_t_reservation_final_all_or_none CHECK (
        (lab_id IS NULL AND start_time IS NULL AND end_time IS NULL)
        OR (lab_id IS NOT NULL AND start_time IS NOT NULL AND end_time IS NOT NULL AND end_time > start_time)
    ),
    CONSTRAINT ck_t_reservation_fixed_request CHECK (
        allocation_mode <> 'FIXED'
        OR (
            lab_id IS NOT NULL AND start_time IS NOT NULL AND end_time IS NOT NULL
            AND request_date = DATE(start_time)
            AND request_window_start = TIME(start_time)
            AND request_window_end = TIME(end_time)
            AND requested_duration_minutes = TIMESTAMPDIFF(MINUTE, start_time, end_time)
        )
    ),
    CONSTRAINT ck_t_reservation_schedule CHECK (
        (allocation_mode = 'FIXED' AND status <> 'PENDING_SCHEDULE')
        OR (allocation_mode = 'SCHEDULABLE')
    ),
    KEY idx_t_reservation_lab_start (lab_id, start_time),
    KEY idx_t_reservation_user_status (user_id, status),
    KEY idx_t_reservation_status_start (status, start_time),
    KEY idx_t_reservation_batch (batch_id),
    CONSTRAINT fk_t_reservation_batch FOREIGN KEY (batch_id) REFERENCES t_reservation_batch (batch_id) ON DELETE RESTRICT,
    CONSTRAINT fk_t_reservation_user FOREIGN KEY (user_id) REFERENCES sys_user (user_id) ON DELETE RESTRICT,
    CONSTRAINT fk_t_reservation_lab FOREIGN KEY (lab_id) REFERENCES t_lab (lab_id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE t_reservation_seat (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    resv_id BIGINT UNSIGNED NOT NULL,
    seat_id BIGINT UNSIGNED NOT NULL,
    time_mask BIGINT UNSIGNED NOT NULL,
    assigned_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    assignment_method ENUM('FIXED', 'AUTO_PUBLISH') NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_t_reservation_seat UNIQUE (resv_id, seat_id),
    CONSTRAINT ck_t_reservation_seat_mask CHECK (time_mask > 0 AND time_mask <= 281474976710655),
    KEY idx_t_reservation_seat_reverse (seat_id, resv_id),
    CONSTRAINT fk_t_reservation_seat_resv FOREIGN KEY (resv_id) REFERENCES t_reservation (resv_id) ON DELETE RESTRICT,
    CONSTRAINT fk_t_reservation_seat_seat FOREIGN KEY (seat_id) REFERENCES t_lab_seat (seat_id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE t_reservation_log (
    log_id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    resv_id BIGINT UNSIGNED NOT NULL,
    from_status VARCHAR(32) NULL,
    to_status VARCHAR(32) NOT NULL,
    operator_id BIGINT UNSIGNED NULL,
    reason VARCHAR(500) NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (log_id),
    KEY idx_t_reservation_log_resv (resv_id, created_at),
    CONSTRAINT fk_t_reservation_log_resv FOREIGN KEY (resv_id) REFERENCES t_reservation (resv_id) ON DELETE RESTRICT,
    CONSTRAINT fk_t_reservation_log_operator FOREIGN KEY (operator_id) REFERENCES sys_user (user_id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE t_violation (
    violation_id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    user_id BIGINT UNSIGNED NOT NULL,
    resv_id BIGINT UNSIGNED NOT NULL,
    violation_type VARCHAR(32) NOT NULL,
    deduct_points INT NOT NULL,
    recorded_by BIGINT UNSIGNED NULL,
    status ENUM('ACTIVE', 'REVOKED') NOT NULL DEFAULT 'ACTIVE',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    revoked_at DATETIME(3) NULL,
    PRIMARY KEY (violation_id),
    CONSTRAINT ck_t_violation_points CHECK (deduct_points >= 0),
    KEY idx_t_violation_user (user_id, created_at),
    KEY idx_t_violation_resv (resv_id),
    CONSTRAINT fk_t_violation_user FOREIGN KEY (user_id) REFERENCES sys_user (user_id) ON DELETE RESTRICT,
    CONSTRAINT fk_t_violation_resv FOREIGN KEY (resv_id) REFERENCES t_reservation (resv_id) ON DELETE RESTRICT,
    CONSTRAINT fk_t_violation_recorder FOREIGN KEY (recorded_by) REFERENCES sys_user (user_id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE t_credit_record (
    record_id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    user_id BIGINT UNSIGNED NOT NULL,
    violation_id BIGINT UNSIGNED NULL,
    change_type VARCHAR(32) NOT NULL,
    change_amount INT NOT NULL,
    balance_before INT NOT NULL,
    balance_after INT NOT NULL,
    operator_id BIGINT UNSIGNED NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (record_id),
    CONSTRAINT ck_t_credit_record_balance CHECK (balance_after = balance_before + change_amount),
    KEY idx_t_credit_record_user_time (user_id, created_at),
    KEY idx_t_credit_record_violation (violation_id),
    CONSTRAINT fk_t_credit_record_user FOREIGN KEY (user_id) REFERENCES sys_user (user_id) ON DELETE RESTRICT,
    CONSTRAINT fk_t_credit_record_violation FOREIGN KEY (violation_id) REFERENCES t_violation (violation_id) ON DELETE RESTRICT,
    CONSTRAINT fk_t_credit_record_operator FOREIGN KEY (operator_id) REFERENCES sys_user (user_id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE t_appeal (
    appeal_id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    violation_id BIGINT UNSIGNED NOT NULL,
    user_id BIGINT UNSIGNED NOT NULL,
    reason VARCHAR(1000) NOT NULL,
    status ENUM('PENDING', 'APPROVED', 'REJECTED') NOT NULL DEFAULT 'PENDING',
    reviewer_id BIGINT UNSIGNED NULL,
    review_opinion VARCHAR(1000) NULL,
    reviewed_at DATETIME(3) NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (appeal_id),
    CONSTRAINT uk_t_appeal_violation UNIQUE (violation_id),
    KEY idx_t_appeal_user (user_id, status),
    CONSTRAINT fk_t_appeal_violation FOREIGN KEY (violation_id) REFERENCES t_violation (violation_id) ON DELETE RESTRICT,
    CONSTRAINT fk_t_appeal_user FOREIGN KEY (user_id) REFERENCES sys_user (user_id) ON DELETE RESTRICT,
    CONSTRAINT fk_t_appeal_reviewer FOREIGN KEY (reviewer_id) REFERENCES sys_user (user_id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE t_schedule_task (
    task_id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    schedule_batch_no VARCHAR(64) NOT NULL,
    algorithm_type ENUM('FCFS', 'GREEDY', 'GENETIC') NOT NULL,
    is_primary BOOLEAN NOT NULL DEFAULT FALSE,
    dataset_id VARCHAR(64) NOT NULL,
    demand_snapshot JSON NOT NULL,
    resource_snapshot JSON NOT NULL,
    parameters JSON NOT NULL,
    random_seed BIGINT NULL,
    repeat_index INT UNSIGNED NOT NULL DEFAULT 0,
    runtime_environment JSON NOT NULL,
    status ENUM('PENDING', 'RUNNING', 'SUCCEEDED', 'FAILED') NOT NULL DEFAULT 'PENDING',
    progress DECIMAL(5,2) NOT NULL DEFAULT 0,
    started_at DATETIME(3) NULL,
    finished_at DATETIME(3) NULL,
    duration_ms BIGINT UNSIGNED NULL,
    error_message VARCHAR(1000) NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (task_id),
    CONSTRAINT ck_t_schedule_task_progress CHECK (progress BETWEEN 0 AND 100),
    KEY idx_t_schedule_task_batch_primary (schedule_batch_no, is_primary),
    KEY idx_t_schedule_task_status (status, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE t_schedule_result (
    result_id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    task_id BIGINT UNSIGNED NOT NULL,
    resv_id BIGINT UNSIGNED NOT NULL,
    assigned_lab_id BIGINT UNSIGNED NULL,
    assigned_start_time DATETIME(3) NULL,
    assigned_end_time DATETIME(3) NULL,
    objective_value DOUBLE NULL,
    candidate_seat_allocations JSON NULL,
    is_published BOOLEAN NOT NULL DEFAULT FALSE,
    published_at DATETIME(3) NULL,
    PRIMARY KEY (result_id),
    CONSTRAINT uk_t_schedule_result_task_resv UNIQUE (task_id, resv_id),
    CONSTRAINT ck_t_schedule_result_times CHECK (
        assigned_end_time IS NULL OR assigned_start_time IS NULL
        OR assigned_end_time > assigned_start_time
    ),
    KEY idx_t_schedule_result_published (task_id, is_published),
    CONSTRAINT fk_t_schedule_result_task FOREIGN KEY (task_id) REFERENCES t_schedule_task (task_id) ON DELETE RESTRICT,
    CONSTRAINT fk_t_schedule_result_resv FOREIGN KEY (resv_id) REFERENCES t_reservation (resv_id) ON DELETE RESTRICT,
    CONSTRAINT fk_t_schedule_result_lab FOREIGN KEY (assigned_lab_id) REFERENCES t_lab (lab_id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE t_algorithm_experiment (
    exp_id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    task_id BIGINT UNSIGNED NOT NULL,
    algorithm_type ENUM('FCFS', 'GREEDY', 'GENETIC') NOT NULL,
    data_scale INT UNSIGNED NOT NULL,
    allocation_success_rate DECIMAL(9,6) NOT NULL,
    average_seat_utilization DECIMAL(9,6) NOT NULL,
    seat_preference_mismatch_rate DECIMAL(9,6) NOT NULL,
    time_fragmentation_rate DECIMAL(9,6) NOT NULL,
    seat_allocation_failure_rate DECIMAL(9,6) NULL,
    phase_one_duration_ms BIGINT UNSIGNED NOT NULL,
    phase_two_duration_ms BIGINT UNSIGNED NOT NULL,
    total_duration_ms BIGINT UNSIGNED NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (exp_id),
    CONSTRAINT uk_t_algorithm_experiment_task UNIQUE (task_id),
    CONSTRAINT ck_t_algorithm_experiment_duration CHECK (
        total_duration_ms = phase_one_duration_ms + phase_two_duration_ms
    ),
    KEY idx_t_algorithm_experiment_algo_scale (algorithm_type, data_scale),
    CONSTRAINT fk_t_algorithm_experiment_task FOREIGN KEY (task_id) REFERENCES t_schedule_task (task_id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE t_stat_daily (
    metric_id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    lab_id BIGINT UNSIGNED NOT NULL,
    stat_date DATE NOT NULL,
    planned_utilization DECIMAL(9,6) NOT NULL,
    actual_utilization DECIMAL(9,6) NOT NULL,
    no_show_rate DECIMAL(9,6) NOT NULL,
    reservation_count INT UNSIGNED NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (metric_id),
    CONSTRAINT uk_t_stat_daily_lab_date UNIQUE (lab_id, stat_date),
    CONSTRAINT fk_t_stat_daily_lab FOREIGN KEY (lab_id) REFERENCES t_lab (lab_id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
