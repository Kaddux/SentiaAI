"""Tool schemas (plan Section 9) + mode-dependent selection.

NVIDIA NIM (default) and DeepSeek get the full 15-tool set. The local Ollama
model (optional) gets a reduced set (Section 9.5) because of its smaller context
and lower tool-calling reliability.
"""

from __future__ import annotations

from app.config import settings

# ---------------------------------------------------------------- full set (15)
ALL_TOOLS: list[dict] = [
    {"type": "function", "function": {
        "name": "get_health_snapshot",
        "description": "Complete health overview for a date (sleep, HR, activity, temperature).",
        "parameters": {"type": "object", "properties": {
            "date": {"type": "string", "description": "YYYY-MM-DD. Defaults to today."}
        }, "required": []}}},
    {"type": "function", "function": {
        "name": "get_sleep_data",
        "description": "Sleep the user logged: latest night plus the last 7 nights' totals. This is manually logged by the user, not measured.",
        "parameters": {"type": "object", "properties": {
            "date": {"type": "string", "description": "Optional YYYY-MM-DD; latest night is returned by default."}
        }, "required": []}}},
    {"type": "function", "function": {
        "name": "get_heart_rate_data",
        "description": "Heart rate stats for a date: resting, average, max, min.",
        "parameters": {"type": "object", "properties": {
            "date": {"type": "string", "description": "YYYY-MM-DD."}
        }, "required": ["date"]}}},
    {"type": "function", "function": {
        "name": "get_activity_data",
        "description": "Activity breakdown for a date: steps, active minutes, dominant activity.",
        "parameters": {"type": "object", "properties": {
            "date": {"type": "string", "description": "YYYY-MM-DD."}
        }, "required": ["date"]}}},
    {"type": "function", "function": {
        "name": "get_temperature_data",
        "description": "Temperature readings for a date: average, min, max.",
        "parameters": {"type": "object", "properties": {
            "date": {"type": "string", "description": "YYYY-MM-DD."}
        }, "required": ["date"]}}},
    {"type": "function", "function": {
        "name": "get_health_trends",
        "description": "Trend for a metric over N days: sleep, steps, heart_rate, activity, temperature, wellness.",
        "parameters": {"type": "object", "properties": {
            "metric": {"type": "string", "enum": ["sleep", "steps", "heart_rate", "activity", "temperature", "wellness"]},
            "days": {"type": "integer", "description": "Look-back days. Default 7."}
        }, "required": ["metric"]}}},
    {"type": "function", "function": {
        "name": "get_personal_baseline",
        "description": "User's personal baselines: typical sleep, steps, resting HR, activity.",
        "parameters": {"type": "object", "properties": {}, "required": []}}},
    {"type": "function", "function": {
        "name": "compare_to_baseline",
        "description": "Compare a date's metrics against the personal baseline, returning deviations.",
        "parameters": {"type": "object", "properties": {
            "date": {"type": "string", "description": "YYYY-MM-DD. Defaults to today."}
        }, "required": []}}},
    {"type": "function", "function": {
        "name": "create_reminder",
        "description": "Create a reminder ('remind me to...').",
        "parameters": {"type": "object", "properties": {
            "title": {"type": "string"},
            "time": {"type": "string", "description": "HH:MM 24-hour."},
            "recurrence": {"type": "string", "enum": ["none", "daily", "weekly", "monthly"]},
            "description": {"type": "string"}
        }, "required": ["title", "time"]}}},
    {"type": "function", "function": {
        "name": "list_reminders",
        "description": "List all active reminders.",
        "parameters": {"type": "object", "properties": {}, "required": []}}},
    {"type": "function", "function": {
        "name": "update_reminder",
        "description": "Update a reminder's title, time, recurrence, or active state.",
        "parameters": {"type": "object", "properties": {
            "reminder_id": {"type": "integer"},
            "title": {"type": "string"}, "time": {"type": "string"},
            "recurrence": {"type": "string", "enum": ["none", "daily", "weekly", "monthly"]},
            "is_active": {"type": "boolean"}
        }, "required": ["reminder_id"]}}},
    {"type": "function", "function": {
        "name": "delete_reminder",
        "description": "Delete/cancel a reminder.",
        "parameters": {"type": "object", "properties": {
            "reminder_id": {"type": "integer"}
        }, "required": ["reminder_id"]}}},
    {"type": "function", "function": {
        "name": "get_wellness_assessment",
        "description": "ML wellness assessment: wellness score, stress level, contributing factors.",
        "parameters": {"type": "object", "properties": {
            "date": {"type": "string", "description": "YYYY-MM-DD. Defaults to today."}
        }, "required": []}}},
    {"type": "function", "function": {
        "name": "get_daily_summary",
        "description": "Get the daily health summary for a date.",
        "parameters": {"type": "object", "properties": {
            "date": {"type": "string", "description": "YYYY-MM-DD. Defaults to today."}
        }, "required": []}}},
    {"type": "function", "function": {
        "name": "get_weekly_summary",
        "description": "Get the weekly summary (current vs previous week).",
        "parameters": {"type": "object", "properties": {
            "week_start": {"type": "string", "description": "YYYY-MM-DD (Monday). Defaults to current week."}
        }, "required": []}}},
]

# Reduced set for local Ollama (Section 9.5) — NVIDIA/DeepSeek use the full set.
LOCAL_TOOL_NAMES = {
    "get_health_snapshot", "get_sleep_data", "get_heart_rate_data",
    "get_activity_data", "get_personal_baseline", "create_reminder", "list_reminders",
}


def tools_for_provider() -> list[dict]:
    """Return the tool set for the active provider."""
    if settings.LLM_PROVIDER == "ollama":
        return [t for t in ALL_TOOLS if t["function"]["name"] in LOCAL_TOOL_NAMES]
    return ALL_TOOLS
