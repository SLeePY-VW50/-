import unittest

from pydantic import ValidationError

from app.agent import SYSTEM_PROMPT
from app.schemas import AgentResult, parse_reservation_draft


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
        draft = parse_reservation_draft(self.valid_draft())

        self.assertEqual(draft.allocation_mode, "FIXED")
        self.assertEqual(draft.end_time, "24:00")
        dumped = AgentResult(answer="固定草稿", draft=draft).model_dump(mode="json", by_alias=True)
        self.assertEqual(dumped["draft"]["allocationMode"], "FIXED")
        self.assertEqual(dumped["draft"]["endTime"], "24:00")
        self.assertNotIn("windowStart", dumped["draft"])

    def test_schedulable_draft_keeps_window_duration_and_party_size(self):
        draft = parse_reservation_draft({
            "allocationMode": "SCHEDULABLE",
            "date": "2026-11-02",
            "windowStart": "08:00",
            "windowEnd": "12:00",
            "durationMinutes": 90,
            "reservationMode": "SEAT",
            "attendeeCount": 3,
            "purpose": "嵌入式实验",
        })

        self.assertEqual(draft.allocation_mode, "SCHEDULABLE")
        self.assertEqual(draft.duration_minutes, 90)
        self.assertEqual(draft.attendee_count, 3)
        dumped = AgentResult(answer="可调度草稿", draft=draft).model_dump(mode="json", by_alias=True)
        self.assertEqual(dumped["draft"]["windowStart"], "08:00")
        self.assertEqual(dumped["draft"]["windowEnd"], "12:00")
        self.assertEqual(dumped["draft"]["durationMinutes"], 90)
        self.assertEqual(dumped["draft"]["attendeeCount"], 3)
        self.assertNotIn("startTime", dumped["draft"])

    def test_schedulable_draft_rejects_unusable_window_or_write_fields(self):
        with self.assertRaises(ValidationError):
            parse_reservation_draft(self.valid_draft(allocationMode="SCHEDULABLE"))
        with self.assertRaises(ValidationError):
            parse_reservation_draft({
                "allocationMode": "SCHEDULABLE",
                "date": "2026-11-02",
                "windowStart": "08:00",
                "windowEnd": "09:00",
                "durationMinutes": 90,
                "reservationMode": "SEAT",
                "attendeeCount": 1,
            })
        with self.assertRaises(ValidationError):
            parse_reservation_draft({
                "allocationMode": "SCHEDULABLE",
                "date": "2026-11-02",
                "windowStart": "08:00",
                "windowEnd": "12:00",
                "durationMinutes": 60,
                "reservationMode": "SEAT",
                "attendeeCount": 2,
                "operation": "create",
            })

    def test_prompt_can_draft_either_mode_but_cannot_submit(self):
        self.assertIn("FIXED", SYSTEM_PROMPT)
        self.assertIn("SCHEDULABLE", SYSTEM_PROMPT)
        self.assertNotIn("不得生成SCHEDULABLE", SYSTEM_PROMPT)
        self.assertIn("不得代替用户提交", SYSTEM_PROMPT)

    def test_end_must_be_after_start(self):
        with self.assertRaises(ValidationError):
            parse_reservation_draft(self.valid_draft(startTime="10:00", endTime="09:30"))

    def test_start_cannot_be_end_of_day(self):
        with self.assertRaises(ValidationError):
            parse_reservation_draft(self.valid_draft(startTime="24:00"))

    def test_midnight_is_a_valid_start(self):
        draft = parse_reservation_draft(self.valid_draft(startTime="00:00", endTime="00:30"))

        self.assertEqual(draft.start_time, "00:00")

    def test_unexpected_write_fields_are_rejected(self):
        with self.assertRaises(ValidationError):
            parse_reservation_draft(self.valid_draft(operation="create"))


if __name__ == "__main__":
    unittest.main()
