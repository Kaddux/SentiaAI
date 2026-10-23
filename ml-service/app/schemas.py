# ML Service schemas (pydantic request/response models).
from __future__ import annotations

from typing import Any

from pydantic import BaseModel, Field


# ------------------------------------------------------------------ activity
class ActivityWindow(BaseModel):
    user_id: str
    window_start: str = Field(..., description="ISO timestamp")
    accel_mag_avg: float | None = None
    accel_mag_std: float | None = None
    motion_ratio: float | None = None
    hr_avg: float | None = None
    hour: int | None = None


class ActivityWindowResult(BaseModel):
    user_id: str
    window_start: str
    activity_type: str
    confidence: float
    steps_estimate: int
    accel_mag_avg: float | None = None


class ActivityResponse(BaseModel):
    results: list[ActivityWindowResult]


# --------------------------------------------------------------------- sleep
class SleepWindow(BaseModel):
    window_start: str = Field(..., description="ISO timestamp")
    accel_mag_std: float | None = None


class SleepResponse(BaseModel):
    sleep_start: str | None = None
    sleep_end: str | None = None
    duration_min: int | None = None
    interruptions: int = 0
    detected: bool = False


# ------------------------------------------------------------------ wellness
class WellStats(BaseModel):
    sleep_hours: float | None = None
    steps: int | None = None
    resting_hr: float | None = None
    active_minutes: int | None = None


class WellBaseline(BaseModel):
    avg_sleep_hours: float | None = None
    std_sleep_hours: float | None = None
    avg_daily_steps: int | None = None
    std_daily_steps: int | None = None
    avg_resting_heart_rate: float | None = None
    std_resting_heart_rate: float | None = None
    avg_active_minutes: int | None = None
    std_active_minutes: int | None = None


class WellnessRequest(BaseModel):
    date: str
    stats: WellStats
    baseline: WellBaseline


class Anomaly(BaseModel):
    metric: str
    value: float | None = None
    baseline: float | None = None
    z_score: float


class WellnessResponse(BaseModel):
    date: str
    wellness_score: int
    stress_level: str
    confidence: float = 0.72
    factors: list[str] = Field(default_factory=list)
    anomalies: list[Anomaly] = Field(default_factory=list)


__all__ = [
    "ActivityWindow", "ActivityWindowResult", "ActivityResponse",
    "SleepWindow", "SleepResponse",
    "WellStats", "WellBaseline", "WellnessRequest", "WellnessResponse",
]
