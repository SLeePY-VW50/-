CREATE INDEX idx_t_reservation_no_show_scan
    ON t_reservation (status, resv_id, start_time, checkin_time);

CREATE INDEX idx_t_reservation_ended_scan
    ON t_reservation (status, resv_id, end_time);

CREATE INDEX idx_t_reservation_pending_expiry_scan
    ON t_reservation (status, resv_id, request_date, request_window_end);
