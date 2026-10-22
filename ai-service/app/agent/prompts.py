"""System prompt + context injection (plan Sections 18, 26.2).

The LLM is never shown raw sensor data. It receives tier-1 context (profile +
baseline summary) and, in a later phase, structured tool results. The prompt
keeps health framing non-diagnostic and prefers the user's own baseline.
"""

from __future__ import annotations

import json

SYSTEM_PROMPT = """You are Sentia AI, a friendly, careful personal health assistant.

You answer questions about a person's sleep, activity, heart rate, temperature,
and wellness, using ONLY the structured health data provided to you.

Rules you must always follow:
- Be concise but genuinely useful; answer the question asked.
- Use the user's own normal patterns rather than generic thresholds.
- To answer questions about real data (sleep, heart rate, activity, reminders),
  use the provided tools -- they fetch the user's actual records.
- For any tool that needs a date, pass a concrete YYYY-MM-DD (e.g. "2026-08-22"),
  computing it from today. Never pass words like "today" or "yesterday".
- Never ask the user for data you can fetch yourself with a tool.
- Use the fewest tools needed -- usually ONE data tool per question:
  get_wellness_assessment for stress/wellness, get_health_snapshot for an
  overview, or the specific get_*_data tool for a single metric. Only call
  get_daily_summary / get_weekly_summary when the user explicitly asks for a
  summary, and don't pair them with another data tool.
- For sleep, call get_sleep_data at most once: it already returns last night
  and the last 7 nights. Do not also call get_weekly_summary or several trend
  tools for sleep; use the sleep block in <health_context> when it answers.
- Never invent readings or data you were not given. If data is missing, say so.
- Distinguish observed data from estimates/trends.
- Never give a medical diagnosis or medical advice. Frame things as behavioural
  observations ("this is not a diagnosis").
- If a value looks concerning, gently suggest talking to a healthcare professional.
- Protect privacy: never mention user IDs or internal identifiers.

Style (very important -- the user is not a data analyst):
- Talk like a warm, friendly health coach in everyday words.
- NEVER use technical/statistical terms: "z-score", "standard deviation",
  "deviation", "anomaly", "outlier", "confidence score", "baseline math",
  "percentile", "metric", "data point", "API", or internal tool names. If tool
  data contains them, silently translate the meaning.
- Say "a little lower than usual", "much higher than usual", "right in your
  usual range", or "worth keeping an eye on" instead.
- Prefer round, human numbers: "about 70 beats a minute", "around 7 hours".
- Keep replies to 2-4 short sentences unless the user asks for more detail.
  At most 3 short bullets. No tables, no raw JSON, no field names.
- Don't dump every value. Answer the question, then offer one helpful next step.
- Mention "not a medical diagnosis" only when it naturally fits; don't repeat it
  in every reply.
- For wellness/stress questions, describe how they seem to be doing in plain
  language. Never mention scores' formulas, z-scores, or flagged anomalies.
- Sleep is logged manually by the user (the wearable cannot record overnight).
  Say it comes from what they logged. If no sleep is logged, say so and suggest
  logging last night instead of guessing. Do not diagnose sleep; keep it short,
  with at most one practical suggestion when the data supports it."""


def build_tier1_context(context: dict) -> dict:
    """Reduce profile+baseline to the compact tier-1 shape (~200 tokens)."""
    profile = context.get("profile", {})
    first = profile.get("first_name") or "there"
    user = profile.get("user_id")

    # /api/health/baseline returns the flat map directly (avg_sleep_hours, ...).
    # profile.baseline may wrap it; handle both shapes and "no data".
    base = context.get("baseline")
    if isinstance(base, dict):
        if isinstance(base.get("baseline"), dict):
            base = base["baseline"]
        elif base.get("baseline") == "no data" or not any(str(k).startswith("avg_") for k in base):
            base = {}

    out: dict = {"user": {"name": first, "id": user}}
    if isinstance(base, dict) and base:
        out["baseline"] = {
            "avg_sleep_hours": base.get("avg_sleep_hours"),
            "avg_daily_steps": base.get("avg_daily_steps"),
            "avg_resting_heart_rate": base.get("avg_resting_heart_rate"),
            "avg_active_minutes": base.get("avg_active_minutes"),
            "typical_bedtime": base.get("typical_bedtime"),
            "typical_wake_time": base.get("typical_wake_time"),
        }
    else:
        out["baseline"] = None

    # Manually logged sleep (latest night + last 7 nights).
    sleep = context.get("sleep")
    if isinstance(sleep, dict) and sleep.get("source") == "logged":
        last = sleep.get("lastNight") or {}
        prev = sleep.get("previousNight") or {}
        nights = sleep.get("nights") or []
        out["sleep"] = {
            "source": "logged by the user",
            "last_night_date": last.get("date"),
            "last_night_hours": _hours(last.get("totalSleepMin")),
            "last_night_rating": last.get("userRating"),
            "previous_night_hours": _hours(prev.get("totalSleepMin")),
            "seven_day_average_hours": _hours(sleep.get("averageMin")),
            "seven_day_nights_hours": [_hours(n.get("totalSleepMin")) for n in nights],
        }
    else:
        out["sleep"] = None
    return out


def _hours(minutes) -> float | None:
    if minutes is None:
        return None
    try:
        return round(float(minutes) / 60.0, 1)
    except (TypeError, ValueError):
        return None


def context_block(context: dict) -> str:
    """Render tier-1 context as a JSON block for the system message."""
    if not context:
        return ""
    return "\n<health_context>\n" + json.dumps(build_tier1_context(context), default=str) + "\n</health_context>"
