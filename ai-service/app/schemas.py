from datetime import date, datetime, timezone
from uuid import UUID
from typing import Any, Literal

from pydantic import BaseModel, ConfigDict, Field, field_validator, model_validator


class UserContext(BaseModel):
    model_config = ConfigDict(extra="ignore", populate_by_name=True)

    user_id: int = Field(alias="userId", gt=0)
    roles: list[str]


class ReservationDraft(BaseModel):
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
