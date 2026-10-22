"""HTTP client to the Spring Boot health-proxy (plan Section 8.2).

The AI service NEVER talks to Postgres for health data. It forwards the user's
JWT to the backend, which re-validates it (Section 15.3). Failures degrade
gracefully to an empty context so chat still works when the backend is down.
"""

from __future__ import annotations

import logging
from typing import Any

import httpx

from app.config import settings

log = logging.getLogger("sentiaai.health")


class HealthClient:
    def __init__(self) -> None:
        self._client = httpx.AsyncClient(base_url=settings.BACKEND_URL, timeout=httpx.Timeout(5.0))

    async def _get(self, path: str, jwt: str) -> dict[str, Any]:
        try:
            resp = await self._client.get(path, headers={"Authorization": f"Bearer {jwt}"})
            resp.raise_for_status()
            return resp.json()
        except Exception as exc:
            log.warning("health_proxy_unavailable", path=path, error=str(exc))
            return {}

    async def profile(self, jwt: str) -> dict[str, Any]:
        return await self._get("/api/health/profile", jwt)

    async def baseline(self, jwt: str) -> dict[str, Any]:
        return await self._get("/api/health/baseline", jwt)

    async def tier1_context(self, jwt: str) -> dict[str, Any]:
        """Small always-on context (plan Section 18.4 Tier 1): profile + baseline + sleep."""
        profile = await self.profile(jwt)
        baseline = await self.baseline(jwt)
        sleep = await self.sleep_summary(jwt, 7)
        return {"profile": profile, "baseline": baseline, "sleep": sleep}

    async def sleep_summary(self, jwt: str, days: int = 7) -> dict[str, Any]:
        return await self._get(f"/api/sleep-sessions/summary?days={days}", jwt)

    # ------------------------------------------------------ tool data fetchers
    async def health_snapshot(self, jwt: str, date: str) -> dict[str, Any]:
        return await self._get(f"/api/health/today?date={date}", jwt) if date else await self._get("/api/health/today", jwt)

    async def sleep(self, jwt: str, date: str) -> dict[str, Any]:
        # Manual sleep: latest night + last 7 nights' totals.
        return await self.sleep_summary(jwt, 7)

    async def heart_rate(self, jwt: str, date: str) -> dict[str, Any]:
        return await self._get(f"/api/health/heart-rate?date={date}", jwt)

    async def activity(self, jwt: str, date: str) -> dict[str, Any]:
        return await self._get(f"/api/health/activity?date={date}", jwt)

    async def temperature(self, jwt: str, date: str) -> dict[str, Any]:
        return await self._get(f"/api/health/temperature?date={date}", jwt)

    async def trends(self, jwt: str, metric: str, days: int) -> dict[str, Any]:
        if metric == "sleep":
            return await self.sleep_summary(jwt, days)
        return await self._get(f"/api/health/trends?metric={metric}&days={days}", jwt)

    async def compare_baseline(self, jwt: str, date: str) -> dict[str, Any]:
        return await self._get(f"/api/health/compare-baseline?date={date}", jwt)

    async def wellness(self, jwt: str, date: str) -> dict[str, Any]:
        return await self._get(f"/api/health/wellness?date={date}", jwt)

    # -------------------------------------------------------- reminder actions
    async def create_reminder(self, jwt: str, payload: dict[str, Any]) -> dict[str, Any]:
        return await self._post("/api/reminders", jwt, payload)

    async def list_reminders(self, jwt: str) -> list[Any]:
        return await self._get_list("/api/reminders", jwt)

    async def update_reminder(self, jwt: str, reminder_id: int, payload: dict[str, Any]) -> dict[str, Any]:
        return await self._put(f"/api/reminders/{reminder_id}", jwt, payload)

    async def delete_reminder(self, jwt: str, reminder_id: int) -> dict[str, Any]:
        return await self._delete(f"/api/reminders/{reminder_id}", jwt)

    async def _post(self, path: str, jwt: str, payload: dict[str, Any]) -> dict[str, Any]:
        try:
            resp = await self._client.post(path, headers={"Authorization": f"Bearer {jwt}"}, json=payload)
            resp.raise_for_status()
            return resp.json()
        except Exception as exc:
            log.warning("health_proxy_error", path=path, error=str(exc))
            return {"error": str(exc)}

    async def _put(self, path: str, jwt: str, payload: dict[str, Any]) -> dict[str, Any]:
        try:
            resp = await self._client.put(path, headers={"Authorization": f"Bearer {jwt}"}, json=payload)
            resp.raise_for_status()
            return resp.json()
        except Exception as exc:
            log.warning("health_proxy_error", path=path, error=str(exc))
            return {"error": str(exc)}

    async def _delete(self, path: str, jwt: str) -> dict[str, Any]:
        try:
            resp = await self._client.delete(path, headers={"Authorization": f"Bearer {jwt}"})
            resp.raise_for_status()
            return resp.json()
        except Exception as exc:
            log.warning("health_proxy_error", path=path, error=str(exc))
            return {"error": str(exc)}

    async def _get_list(self, path: str, jwt: str) -> list[Any]:
        try:
            resp = await self._client.get(path, headers={"Authorization": f"Bearer {jwt}"})
            resp.raise_for_status()
            return resp.json()
        except Exception as exc:
            log.warning("health_proxy_unavailable", path=path, error=str(exc))
            return []


health_client = HealthClient()
