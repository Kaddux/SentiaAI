"""Conversation history helpers (plan Section 17) — short-term memory + pruning.

Messages live in `ai_schema.conversation_messages`. The manager loads the most
recent N messages for context, keeps the conversation within a token budget, and
persists both user and assistant turns.
"""

from __future__ import annotations

import uuid
from typing import TypedDict

from sqlalchemy import select, text
from sqlalchemy.ext.asyncio import AsyncSession

from app.config import settings
from app.services.database import Conversation, ConversationMessage


class ChatTurn(TypedDict):
    role: str      # user | assistant | tool | system
    content: str
    tool_calls: str | None
    tool_call_id: str | None


async def get_or_create_conversation(db: AsyncSession, user_id: str, conversation_id: int | None) -> int:
    """Return an active conversation id for the user, creating one if needed."""
    uid = uuid.UUID(str(user_id))
    if conversation_id is not None:
        # Ownership check: conversation must belong to this user (Section 15).
        row = await db.execute(
            select(Conversation.id).where(
                Conversation.id == conversation_id,
                Conversation.user_id == uid,
                Conversation.is_active.is_(True),
            )
        )
        existing = row.scalar_one_or_none()
        if existing is not None:
            return existing
    # Create a fresh conversation.
    conv = Conversation(user_id=uid, is_active=True)
    db.add(conv)
    await db.commit()
    await db.refresh(conv)
    return conv.id


async def load_history(db: AsyncSession, conversation_id: int, limit: int | None = None) -> list[ChatTurn]:
    """Load the most recent N messages, oldest-first, as plain dict messages."""
    limit = limit or settings.MAX_SHORT_TERM_MESSAGES
    rows = (
        await db.execute(
            text(
                """
                SELECT role, content, tool_calls, tool_call_id
                FROM ai_schema.conversation_messages
                WHERE conversation_id = :cid
                ORDER BY created_at DESC
                LIMIT :lim
                """
            ),
            {"cid": conversation_id, "lim": limit},
        )
    ).mappings().all()
    rows = list(reversed(rows))
    return [
        ChatTurn(role=r["role"], content=r["content"] or "", tool_calls=r["tool_calls"], tool_call_id=r["tool_call_id"])
        for r in rows
    ]


async def save_message(
    db: AsyncSession,
    conversation_id: int,
    role: str,
    content: str,
    tool_calls: str | None = None,
    tool_call_id: str | None = None,
) -> None:
    db.add(
        ConversationMessage(
            conversation_id=conversation_id,
            role=role,
            content=content or "",
            tool_calls=tool_calls,
            tool_call_id=tool_call_id,
        )
    )
    await db.commit()


def prune_history(messages: list[dict], budget_tokens: int) -> list[dict]:
    """Drop oldest messages (from the middle) so history fits the token budget.

    Tool messages must be removed with their paired assistant tool_calls message.
    """
    if not messages:
        return messages
    total = sum(len(str(m.get("content", ""))) // 3 for m in messages)
    while total > budget_tokens and len(messages) > 2:
        # Drop the oldest message; if it's a tool result, also drop its pair.
        dropped = messages.pop(0)
        total -= len(str(dropped.get("content", ""))) // 3
        if dropped.get("role") == "tool" and messages and messages[0].get("role") == "assistant":
            other = messages.pop(0)
            total -= len(str(other.get("content", ""))) // 3
    return messages
