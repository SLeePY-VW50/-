ALTER TABLE t_schedule_task
    MODIFY COLUMN status ENUM(
        'PENDING', 'RUNNING', 'SUCCEEDED', 'FAILED', 'PUBLISHED', 'PUBLISH_FAILED'
    ) NOT NULL DEFAULT 'PENDING';

ALTER TABLE t_schedule_task
    ADD INDEX idx_t_schedule_task_batch_algorithm (schedule_batch_no, algorithm_type);
