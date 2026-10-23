"""Rule-based signal computation (plan Sections 19.2-19.4).

No models are trained or loaded — these are deterministic heuristics over the
5-min aggregate windows. Activity uses accel-magnitude + HR thresholds; sleep
uses still-motion clustering; wellness uses z-scores against a personal baseline.
"""

from __future__ import annotations

import math
from datetime import datetime, timedelta

# ------------------------------------------------------------------ activity
# Thresholds on a 5-min window (accel magnitude = sqrt(x^2+y^2+z^2) in g units).
SITTING_STD_MAX = 0.06
WALKING_STD_MAX = 0.35
RUNNING_HR_MIN = 120.0

STEPS_PER_MIN = {"sitting": 0, "walking": 105, "running": 165, "unknown": 0}


def classify_activity(mag_std: float | None, mag_avg: float | None,
                      hr_avg: float | None, motion_ratio: float | None = None) -> tuple[str, float, int]:
    """Return (activity_type, confidence, steps_estimate) for one 5-min window."""
    if mag_std is None:
        return "unknown", 0.2, 0

    steps_per_min = STEPS_PER_MIN["unknown"]
    if mag_std < SITTING_STD_MAX:
        a_type, conf = "sitting", 0.8
    elif mag_std < WALKING_STD_MAX:
        # walking vs running distinguished by HR effort
        if hr_avg is not None and hr_avg >= RUNNING_HR_MIN:
            a_type, conf = "running", 0.7
        else:
            a_type, conf = "walking", 0.75
    else:
        a_type, conf = "running", 0.7

    steps_per_min = STEPS_PER_MIN.get(a_type, 0)
    steps = 0
    if a_type in ("walking", "running"):
        # Count only the moving portion of the 5-min window. `motion_ratio` is the
        # fraction of samples that deviate from gravity; fall back to the std
        # intensity for older rows that predate the feature.
        if motion_ratio is not None:
            ratio = min(1.0, max(0.0, float(motion_ratio)))
        else:
            span = WALKING_STD_MAX - SITTING_STD_MAX
            ratio = min(1.0, max(0.0, (mag_std - SITTING_STD_MAX) / span)) if span > 0 else 1.0
        steps = int(round(5 * steps_per_min * ratio))
    return a_type, conf, steps


# --------------------------------------------------------------------- sleep
def _parse(ts: str) -> datetime:
    return datetime.fromisoformat(ts.replace("Z", "+00:00"))


def estimate_sleep(windows: list[dict]) -> tuple[datetime | None, datetime | None, int, int]:
    """Return (sleep_start, sleep_end, duration_min, interruptions) or (None,None,0,0).

    Windows are 5-min. rest = accel_mag_std < 0.08. Cluster contiguous rest
    windows (tolerating a 1-window gap); the longest cluster >= 60 min counts as sleep.
    """
    threshold = 0.08
    indexed = []
    for w in windows:
        std = w.get("accel_mag_std")
        indexed.append((_parse(w["window_start"]), std is not None and std < threshold))

    # Build clusters of consecutive rest windows (gap tolerance = 1 non-rest window).
    clusters: list[list[int]] = []
    cur: list[int] = []
    last_rest_index: int | None = None
    for i, (_, is_rest) in enumerate(indexed):
        if is_rest:
            if cur and last_rest_index is not None and i - last_rest_index <= 2:
                cur.append(i)          # within a 1-window gap
            else:
                if cur:
                    clusters.append(cur)
                cur = [i]
            last_rest_index = i
    if cur:
        clusters.append(cur)

    if not clusters:
        return None, None, 0, 0

    longest = max(clusters, key=len)
    duration_min = len(longest) * 5
    if duration_min < 60:
        return None, None, 0, 0

    start = indexed[longest[0]][0]
    end = indexed[longest[-1]][0] + timedelta(minutes=5)
    # Interruptions: non-rest windows that fall within the cluster's span.
    span_start, span_end = longest[0], longest[-1]
    interruptions = sum(
        1 for j in range(span_start + 1, span_end) if not indexed[j][1]
    )
    return start, end, duration_min, interruptions


# ------------------------------------------------------------------ wellness
# Weights + tolerances for the wellness/stress score. |z| is capped so a single
# outlier can't dominate; the gentler slope keeps ordinary days in the green.
METRIC_WEIGHTS = {
    "sleep_hours": 0.30,
    "resting_heart_rate": 0.25,
    "steps": 0.15,
    "active_minutes": 0.15,
    "temperature_c": 0.15,
}
Z_CAP = 4.0
SCORE_SLOPE = 12.0


def _z(value: float | None, avg: float | None, std: float | None) -> float:
    if value is None or avg is None or std is None or std == 0:
        return math.nan
    return (value - avg) / std


def assess_wellness(date: str, stats: dict, baseline: dict) -> dict:
    """Return wellness_score/stress_level/factors/anomalies from weighted z-scores."""
    metrics = [
        ("sleep_hours", stats.get("sleep_hours"),
         baseline.get("avg_sleep_hours"), baseline.get("std_sleep_hours")),
        ("resting_heart_rate", stats.get("resting_hr"),
         baseline.get("avg_resting_heart_rate"), baseline.get("std_resting_heart_rate")),
        ("steps", stats.get("steps"),
         baseline.get("avg_daily_steps"), baseline.get("std_daily_steps")),
        ("active_minutes", stats.get("active_minutes"),
         baseline.get("avg_active_minutes"), baseline.get("std_active_minutes")),
        ("temperature_c", stats.get("temperature_c"),
         baseline.get("avg_temperature_c"), baseline.get("std_temperature_c")),
    ]

    zs: list[tuple[str, float, float | None, float | None]] = []
    weighted = 0.0
    weight_sum = 0.0
    for metric, value, avg, std in metrics:
        z = _z(value, avg, std)
        if math.isnan(z):
            continue
        w = METRIC_WEIGHTS.get(metric, 0.0)
        weighted += w * min(abs(z), Z_CAP)
        weight_sum += w
        zs.append((metric, z, value, avg))

    if not zs or weight_sum == 0:
        return {"date": date, "wellness_score": 50, "stress_level": "moderate",
                "confidence": 0.72, "factors": [], "anomalies": []}

    mean_abs = weighted / weight_sum
    score = int(round(max(0, min(100, 100 - SCORE_SLOPE * mean_abs))))
    stress = "low" if score >= 75 else ("moderate" if score >= 50 else "elevated")

    factors = [f"{m} (z={z:.1f})" for m, z, _, _ in zs if abs(z) > 0.5]
    anomalies = [
        {"metric": m, "value": v, "baseline": avg, "z_score": round(z, 2)}
        for m, z, v, avg in zs if abs(z) > 3
    ]

    return {"date": date, "wellness_score": score, "stress_level": stress,
            "confidence": 0.72, "factors": factors, "anomalies": anomalies}
