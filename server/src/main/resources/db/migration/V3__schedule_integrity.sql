ALTER TABLE t_reservation
    DROP CONSTRAINT ck_t_reservation_schedule,
    ADD CONSTRAINT ck_t_reservation_schedule CHECK (
        (allocation_mode = 'FIXED' AND status <> 'PENDING_SCHEDULE')
        OR (
            allocation_mode = 'SCHEDULABLE'
            AND (
                status NOT IN ('APPROVED', 'IN_USE', 'COMPLETED', 'NO_SHOW', 'VIOLATION_TERMINATED')
                OR (lab_id IS NOT NULL AND start_time IS NOT NULL AND end_time IS NOT NULL)
            )
        )
    );

ALTER TABLE t_schedule_result
    ADD CONSTRAINT ck_t_schedule_result_publication_time CHECK (
        is_published = FALSE OR published_at IS NOT NULL
    );
