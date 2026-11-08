ALTER TABLE t_reservation
    DROP CONSTRAINT ck_t_reservation_fixed_request,
    ADD CONSTRAINT ck_t_reservation_fixed_request CHECK (
        allocation_mode <> 'FIXED'
        OR (
            lab_id IS NOT NULL AND start_time IS NOT NULL AND end_time IS NOT NULL
            AND request_date = DATE(start_time)
            AND request_window_start = TIME(start_time)
            AND (
                request_window_end = TIME(end_time)
                OR (
                    request_window_end = '24:00:00'
                    AND end_time = DATE_ADD(DATE(start_time), INTERVAL 1 DAY)
                )
            )
            AND requested_duration_minutes = TIMESTAMPDIFF(MINUTE, start_time, end_time)
        )
    );
