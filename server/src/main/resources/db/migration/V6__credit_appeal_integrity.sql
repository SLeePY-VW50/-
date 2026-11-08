ALTER TABLE t_appeal
    ADD KEY idx_t_appeal_status_created_id (status, created_at, appeal_id),
    ADD KEY idx_t_appeal_created_id (created_at, appeal_id);

ALTER TABLE t_credit_record
    ADD CONSTRAINT uk_t_credit_record_violation_type UNIQUE (violation_id, change_type);
