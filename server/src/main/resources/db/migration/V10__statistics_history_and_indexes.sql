ALTER TABLE t_stat_daily
    ADD COLUMN available_seat_minutes BIGINT UNSIGNED NULL;

CREATE INDEX idx_t_reservation_request_date
    ON t_reservation (request_date);

CREATE INDEX idx_t_reservation_lab_request_date
    ON t_reservation (lab_id, request_date);

CREATE INDEX idx_t_reservation_user_request_date
    ON t_reservation (user_id, request_date);
