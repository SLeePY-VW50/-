import unittest

from pydantic import ValidationError

from app.schemas import ReservationDraft


class ReservationDraftTest(unittest.TestCase):
    def valid_draft(self, **changes):
        values = {
            "labId": 1,
            "date": "2026-11-02",
            "startTime": "22:30",
            "endTime": "24:00",
            "allocationMode": "FIXED",
            "reservationMode": "SEAT",
            "seatCount": 1,
            "preferences": {"nearWindow": True},
        }
        values.update(changes)
        return values

    def test_fixed_draft_accepts_end_of_day(self):
        draft = ReservationDraft.model_validate(self.valid_draft())

        self.assertEqual(draft.allocation_mode, "FIXED")
        self.assertEqual(draft.end_time, "24:00")

    def test_schedulable_draft_is_rejected(self):
        with self.assertRaises(ValidationError):
            ReservationDraft.model_validate(self.valid_draft(allocationMode="SCHEDULABLE"))

    def test_end_must_be_after_start(self):
        with self.assertRaises(ValidationError):
            ReservationDraft.model_validate(self.valid_draft(startTime="10:00", endTime="09:30"))

    def test_start_cannot_be_end_of_day(self):
        with self.assertRaises(ValidationError):
            ReservationDraft.model_validate(self.valid_draft(startTime="24:00"))

    def test_midnight_is_a_valid_start(self):
        draft = ReservationDraft.model_validate(self.valid_draft(startTime="00:00", endTime="00:30"))

        self.assertEqual(draft.start_time, "00:00")

    def test_unexpected_write_fields_are_rejected(self):
        with self.assertRaises(ValidationError):
            ReservationDraft.model_validate(self.valid_draft(operation="create"))


if __name__ == "__main__":
    unittest.main()
