ALTER TABLE t_reservation
    ADD COLUMN seat_count INT UNSIGNED NULL AFTER attendee_count;

UPDATE t_reservation r
LEFT JOIN (
    SELECT resv_id, COUNT(*) AS assignment_count
    FROM t_reservation_seat
    GROUP BY resv_id
) assignments ON assignments.resv_id = r.resv_id
SET r.seat_count = CASE
    WHEN assignments.assignment_count IS NOT NULL THEN assignments.assignment_count
    ELSE r.attendee_count
END
WHERE r.reservation_mode = 'SEAT';

ALTER TABLE t_reservation
    ADD CONSTRAINT ck_t_reservation_seat_count CHECK (
        (reservation_mode = 'SEAT'
            AND seat_count IS NOT NULL AND seat_count > 0 AND seat_count = attendee_count)
        OR (reservation_mode = 'ROOM' AND (seat_count IS NULL OR seat_count > 0))
    );
