ALTER TABLE t_reservation
    ADD COLUMN occupancy_source ENUM('PERSONAL', 'TIMETABLE') NOT NULL DEFAULT 'PERSONAL' AFTER reservation_mode;

CREATE TABLE t_semester (
    semester_id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    code VARCHAR(32) NOT NULL,
    name VARCHAR(128) NOT NULL,
    week1_monday DATE NOT NULL,
    week_count TINYINT UNSIGNED NOT NULL,
    status ENUM('ACTIVE', 'ARCHIVED') NOT NULL DEFAULT 'ACTIVE',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (semester_id),
    CONSTRAINT uk_t_semester_code UNIQUE (code),
    CONSTRAINT ck_t_semester_week1 CHECK (DAYOFWEEK(week1_monday) = 2),
    CONSTRAINT ck_t_semester_weeks CHECK (week_count BETWEEN 1 AND 30)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE t_lab_admin (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    lab_id BIGINT UNSIGNED NOT NULL,
    user_id BIGINT UNSIGNED NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    CONSTRAINT uk_t_lab_admin UNIQUE (lab_id, user_id),
    KEY idx_t_lab_admin_user (user_id),
    CONSTRAINT fk_t_lab_admin_lab FOREIGN KEY (lab_id) REFERENCES t_lab (lab_id) ON DELETE RESTRICT,
    CONSTRAINT fk_t_lab_admin_user FOREIGN KEY (user_id) REFERENCES sys_user (user_id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE t_timetable_entry (
    entry_id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    semester_id BIGINT UNSIGNED NOT NULL,
    lab_id BIGINT UNSIGNED NOT NULL,
    course_name VARCHAR(128) NOT NULL,
    teacher_user_id BIGINT UNSIGNED NOT NULL,
    class_name VARCHAR(128) NOT NULL,
    day_of_week TINYINT UNSIGNED NOT NULL,
    start_period TINYINT UNSIGNED NOT NULL,
    end_period TINYINT UNSIGNED NOT NULL,
    week_start TINYINT UNSIGNED NOT NULL,
    week_end TINYINT UNSIGNED NOT NULL,
    status ENUM('ACTIVE', 'CANCELLED') NOT NULL DEFAULT 'ACTIVE',
    created_by BIGINT UNSIGNED NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (entry_id),
    CONSTRAINT ck_t_timetable_entry_day CHECK (day_of_week BETWEEN 1 AND 7),
    CONSTRAINT ck_t_timetable_entry_period CHECK (start_period >= 1 AND end_period >= start_period),
    CONSTRAINT ck_t_timetable_entry_week CHECK (week_start >= 1 AND week_end >= week_start),
    KEY idx_t_timetable_entry_lab (lab_id, semester_id, status),
    CONSTRAINT fk_t_timetable_entry_semester FOREIGN KEY (semester_id) REFERENCES t_semester (semester_id) ON DELETE RESTRICT,
    CONSTRAINT fk_t_timetable_entry_lab FOREIGN KEY (lab_id) REFERENCES t_lab (lab_id) ON DELETE RESTRICT,
    CONSTRAINT fk_t_timetable_entry_teacher FOREIGN KEY (teacher_user_id) REFERENCES sys_user (user_id) ON DELETE RESTRICT,
    CONSTRAINT fk_t_timetable_entry_creator FOREIGN KEY (created_by) REFERENCES sys_user (user_id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE t_timetable_roster (
    roster_id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    entry_id BIGINT UNSIGNED NOT NULL,
    user_id BIGINT UNSIGNED NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (roster_id),
    CONSTRAINT uk_t_timetable_roster UNIQUE (entry_id, user_id),
    KEY idx_t_timetable_roster_user (user_id),
    CONSTRAINT fk_t_timetable_roster_entry FOREIGN KEY (entry_id) REFERENCES t_timetable_entry (entry_id) ON DELETE RESTRICT,
    CONSTRAINT fk_t_timetable_roster_user FOREIGN KEY (user_id) REFERENCES sys_user (user_id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE t_timetable_session (
    session_id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    entry_id BIGINT UNSIGNED NOT NULL,
    week_no TINYINT UNSIGNED NOT NULL,
    session_date DATE NOT NULL,
    resv_id BIGINT UNSIGNED NOT NULL,
    status ENUM('SCHEDULED', 'CANCELLED') NOT NULL DEFAULT 'SCHEDULED',
    PRIMARY KEY (session_id),
    CONSTRAINT uk_t_timetable_session_entry_date UNIQUE (entry_id, session_date),
    CONSTRAINT uk_t_timetable_session_resv UNIQUE (resv_id),
    KEY idx_t_timetable_session_date (session_date, status),
    CONSTRAINT fk_t_timetable_session_entry FOREIGN KEY (entry_id) REFERENCES t_timetable_entry (entry_id) ON DELETE RESTRICT,
    CONSTRAINT fk_t_timetable_session_resv FOREIGN KEY (resv_id) REFERENCES t_reservation (resv_id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE t_timetable_attendance (
    attendance_id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    session_id BIGINT UNSIGNED NOT NULL,
    user_id BIGINT UNSIGNED NOT NULL,
    checkin_time DATETIME(3) NOT NULL,
    channel ENUM('WECHAT_QR', 'TERMINAL_ACCOUNT') NOT NULL,
    PRIMARY KEY (attendance_id),
    CONSTRAINT uk_t_timetable_attendance UNIQUE (session_id, user_id),
    CONSTRAINT fk_t_timetable_attendance_session FOREIGN KEY (session_id) REFERENCES t_timetable_session (session_id) ON DELETE RESTRICT,
    CONSTRAINT fk_t_timetable_attendance_user FOREIGN KEY (user_id) REFERENCES sys_user (user_id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

INSERT INTO t_semester (code, name, week1_monday, week_count, status)
VALUES ('2026-2027-1', '2026-2027学年第一学期', '2026-09-07', 18, 'ACTIVE');
