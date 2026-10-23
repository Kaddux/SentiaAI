"""Conversation retention (ai_schema) — plan Section 7.3.

The AI service owns `ai_schema`, so it prunes its own history: messages older
than CONVERSATION_RETENTION_DAYS (default 90) are deleted, and conversations
left with no messages are removed. Runs once at startup and then daily.
"""

from __future__ import annotations

import asyncio
import logging
from datetime import datetime, timedelta, timezone

from sqlalchemy import text

from app.config import settings
from app.services.database import async_session

log = logging.getLogger("sentiaai.retention")


async def purge_once() -> None:
    days = settings.CONVERSATION_RETENTION_DAYS
    cutoff = (datetime.now(timezone.utc) - timedelta(days=days)).replace(tzinfo=None)
    try:
        async with async_session() as session:
            await session.execute(text("SET search_path TO ai_schema"))
            msgs = await session.execute(
                text("DELETE FROM conversation_messages WHERE created_at < :cutoff"),
                {"cutoff": cutoff},
            )
            convs = await session.execute(
                text(
                    "DELETE FROM conversations c WHERE NOT EXISTS ("
                    "  SELECT 1 FROM conversation_messages m WHERE m.conversation_id = c.id)"
                )
            )
            await session.commit()
            log.info(
                "conversation_retention_purged messages=%s conversations=%s days=%s",
                msgs.rowcount, convs.rowcount, days,
            )
    except Exception as exc:  # noqa: BLE001 — never let retention crash the app
        log.warning("conversation_retention_failed %s", exc)


async def retention_loop() -> None:
    while True:
        await purge_once()
        await asyncio.sleep(24 * 3600)
