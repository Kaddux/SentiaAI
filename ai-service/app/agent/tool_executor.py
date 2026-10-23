"""Tool execution (plan Sections 9, 15).

Security posture:
- tool_name must be in ALLOWED_TOOLS (rejects hallucinated names).
- arguments are parsed defensively (malformed JSON -> friendly error).
- The user identity always comes from the forwarded JWT (never from LLM args),
  so the backend re-validates ownership (Section 15.3).
"""

from __future__ import annotations

import json
import logging
from datetime import date as _date
from typing import Any

from sqlalchemy.ext.asyncio import AsyncSession

from app.agent.tools import ALL_TOOLS
from app.services.health_client import health_client
from app.services.summary import get_daily_summary, get_weekly_summary

log = logging.getLogger("sentiaai.tools")

ALLOWED_TOOLS = {t["function"]["name"] for t in ALL_TOOLS}

FRIENDLY_METRICS = {
    "sleep_hours": "sleep",
    "resting_heart_rate": "resting heart rate",
    "steps": "daily steps",
    "active_minutes": "active time",
    "temperature_c": "body temperature",
}


def _friendly_wellness(payload: Any) -> Any:
    """Translate statistical fields into plain language before the LLM sees them.

    The model tends to echo whatever it is given, so z-scores / "anomaly" keys are
    removed here and replaced with human phrases ("active time is a little lower
    than usual").
    """
    if not isinstance(payload, dict) or "anomalies" not in payload:
        return payload

    notes: list[str] = []
    for a in payload.get("anomalies") or []:
        if not isinstance(a, dict):
            continue
        try:
            z = float(a.get("z_score", 0))
        except (TypeError, ValueError):
            z = 0.0
        area = FRIENDLY_METRICS.get(str(a.get("metric")), "one of your readings")
        direction = "lower" if z < 0 else "higher"
        magnitude = "much" if abs(z) > 7 else "a little"
        notes.append(f"{area} is {magnitude} {direction} than usual")

    out = {k: v for k, v in payload.items() if k not in ("anomalies", "confidence")}
    out["notable_changes"] = notes
    return out


def _today() -> str:
    return _date.today().isoformat()


async def execute_tool(tool_name: str, arguments: str, jwt: str,
                       user_id: str = "", db: AsyncSession | None = None) -> dict[str, Any] | list[Any]:
    """Dispatch a tool call, scoped to the authenticated user (via `jwt`)."""
    if tool_name not in ALLOWED_TOOLS:
        log.warning("unknown_tool", tool=tool_name)
        return {"error": "I couldn't find that capability. Please try again."}

    try:
        args = json.loads(arguments or "{}")
    except (json.JSONDecodeError, TypeError):
        log.warning("malformed_tool_args", tool=tool_name)
        return {"error": "I had trouble with that request. Could you rephrase?"}
    if not isinstance(args, dict):
        return {"error": "Invalid tool arguments."}

    ref_date = args.get("date") or _today()
    log.info("tool_call", tool=tool_name)

    # ---- data fetchers ----
    if tool_name == "get_health_snapshot":
        return await health_client.health_snapshot(jwt, ref_date)
    if tool_name == "get_sleep_data":
        return await health_client.sleep(jwt, ref_date)
    if tool_name == "get_heart_rate_data":
        return await health_client.heart_rate(jwt, ref_date)
    if tool_name == "get_activity_data":
        return await health_client.activity(jwt, ref_date)
    if tool_name == "get_temperature_data":
        return await health_client.temperature(jwt, ref_date)
    if tool_name == "get_health_trends":
        return await health_client.trends(jwt, args.get("metric", "sleep"), int(args.get("days", 7)))
    if tool_name == "get_personal_baseline":
        return await health_client.baseline(jwt)
    if tool_name == "compare_to_baseline":
        return await health_client.compare_baseline(jwt, ref_date)
    if tool_name == "get_wellness_assessment":
        return _friendly_wellness(await health_client.wellness(jwt, ref_date))
    if tool_name == "get_daily_summary":
        if db is None:
            return {"error": "Summary store unavailable."}
        return await get_daily_summary(db, user_id, jwt, parse_date(args, ref_date))
    if tool_name == "get_weekly_summary":
        if db is None:
            return {"error": "Summary store unavailable."}
        return await get_weekly_summary(db, user_id, jwt, parse_date(args, ref_date))

    # ---- reminder actions ----
    if tool_name == "create_reminder":
        payload = {
            "title": args.get("title", ""),
            "description": args.get("description"),
            "reminderTime": args.get("time"),
            "recurrence": args.get("recurrence", "none"),
        }
        return await health_client.create_reminder(jwt, payload)
    if tool_name == "list_reminders":
        return await health_client.list_reminders(jwt)
    if tool_name == "update_reminder":
        payload = {
            "title": args.get("title"),
            "description": args.get("description"),
            "reminderTime": args.get("time"),
            "recurrence": args.get("recurrence"),
        }
        return await health_client.update_reminder(jwt, int(args.get("reminder_id", 0)), payload)
    if tool_name == "delete_reminder":
        return await health_client.delete_reminder(jwt, int(args.get("reminder_id", 0)))

    return {"error": "Unknown tool."}


def parse_date(args: dict, fallback: str):
    """Return a date object for summary tools (date arg or today)."""
    from datetime import date as _d

    raw = args.get("date") or fallback
    try:
        return _d.fromisoformat(str(raw)[:10])
    except ValueError:
        return _d.fromisoformat(_today())
