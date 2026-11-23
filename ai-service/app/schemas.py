from datetime import date, datetime, timezone
from uuid import UUID
from typing import Any, Literal

from pydantic import BaseModel, ConfigDict, Field, field_validator, model_validator


class UserContext(BaseModel):
    model_config = ConfigDict(extra="ignore", populate_by_name=True)

    user_id: int = Field(alias="userId", gt=0)
    roles: list[str]


class FixedReservationDraft(BaseModel):
    model_config = ConfigDict(extra="forbid", populate_by_name=True)

    lab_id: int = Field(alias="labId", gt=0)
    date: date
    start_time: str = Field(alias="startTime")
    end_time: str = Field(alias="endTime")
    allocation_mode: Literal["FIXED"] = Field(default="FIXED", alias="allocationMode")
    reservation_mode: Literal["ROOM", "SEAT"] = Field(alias="reservationMode")
    seat_count: int = Field(alias="seatCount", gt=0)
    preferences: dict[str, Any] = Field(default_factory=dict)

    @field_validator("start_time", "end_time")
    @classmethod
    def validate_time(cls, value: str, info):
        if _time_minutes(value, allow_end_of_day=info.field_name == "end_time") is None:
            raise ValueError("时间必须为HH:MM，仅endTime允许24:00")
        return value

    @model_validator(mode="after")
    def validate_window(self):
        if _time_minutes(self.end_time, True) <= _time_minutes(self.start_time, False):
            raise ValueError("endTime必须晚于startTime")
        return self


class SchedulableReservationDraft(BaseModel):
    model_config = ConfigDict(extra="forbid", populate_by_name=True)

    allocation_mode: Literal["SCHEDULABLE"] = Field(alias="allocationMode")
    date: date
    window_start: str = Field(alias="windowStart")
    window_end: str = Field(alias="windowEnd")
    duration_minutes: int = Field(alias="durationMinutes")
    reservation_mode: Literal["ROOM", "SEAT"] = Field(alias="reservationMode")
    attendee_count: int = Field(alias="attendeeCount", gt=0)
    lab_id: int | None = Field(default=None, alias="labId")
    seat_count: int | None = Field(default=None, alias="seatCount")
    purpose: str | None = None
    preferences: dict[str, Any] = Field(default_factory=dict)

    @field_validator("window_start", "window_end")
    @classmethod
    def validate_time(cls, value: str, info):
        if _time_minutes(value, allow_end_of_day=info.field_name == "window_end") is None:
            raise ValueError("时间必须为HH:MM，仅windowEnd允许24:00")
        return value

    @field_validator("lab_id")
    @classmethod
    def validate_lab(cls, value: int | None):
        if value is not None and value <= 0:
            raise ValueError("labId必须大于0")
        return value

    @model_validator(mode="after")
    def validate_schedulable_window(self):
        if self.duration_minutes < 30 or self.duration_minutes > 240 or self.duration_minutes % 30 != 0:
            raise ValueError("可调度预约时长必须为30的倍数且满足30到240分钟")
        start = _time_minutes(self.window_start, False)
        end = _time_minutes(self.window_end, True)
        if end <= start:
            raise ValueError("windowEnd必须晚于windowStart")
        if self.duration_minutes > end - start:
            raise ValueError("预约时长不能超过可调度请求窗口")
        if self.reservation_mode == "SEAT" and self.seat_count not in (None, self.attendee_count):
            raise ValueError("SEAT草稿的seatCount必须等于attendeeCount")
        if self.purpose is not None and (not self.purpose.strip() or len(self.purpose.strip()) > 500):
            raise ValueError("用途长度无效")
        return self


ReservationDraft = FixedReservationDraft | SchedulableReservationDraft


def parse_reservation_draft(value: Any) -> ReservationDraft:
    if not isinstance(value, dict):
        raise ValueError("草稿必须是对象")
    mode = value.get("allocationMode", value.get("allocation_mode", "FIXED"))
    if mode == "FIXED":
        return FixedReservationDraft.model_validate(value)
    if mode == "SCHEDULABLE":
        return SchedulableReservationDraft.model_validate(value)
    raise ValueError("allocationMode必须是FIXED或SCHEDULABLE")


class ChatRequest(BaseModel):
    model_config = ConfigDict(extra="forbid", populate_by_name=True)

    thread_id: UUID = Field(alias="threadId")
    message: str = Field(min_length=1, max_length=4000)


class AgentResult(BaseModel):
    model_config = ConfigDict(populate_by_name=True)

    answer: str
    draft: ReservationDraft | None = None
    sources: list[str] = Field(default_factory=list)
    generated_at: datetime = Field(
        default_factory=lambda: datetime.now(timezone.utc), alias="generatedAt"
    )
    degraded: bool = False
    fallback: str | None = None

    @field_validator("draft", mode="before")
    @classmethod
    def parse_draft(cls, value: Any):
        if value is None or isinstance(value, (FixedReservationDraft, SchedulableReservationDraft)):
            return value
        return parse_reservation_draft(value)


def _time_minutes(value: str, allow_end_of_day: bool = False) -> int | None:
    if allow_end_of_day and value == "24:00":
        return 1440
    if len(value) != 5 or value[2] != ":":
        return None
    try:
        hour = int(value[:2])
        minute = int(value[3:])
    except ValueError:
        return None
    if hour < 0 or hour > 23 or minute < 0 or minute > 59:
        return None
    return hour * 60 + minute
