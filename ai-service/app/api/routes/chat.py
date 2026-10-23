"""Chat endpoints (plan Section 8.1)."""

from __future__ import annotations

import logging
import uuid
from datetime import date as dt_date, datetime, timedelta, timezone

from fastapi import APIRouter, Depends, HTTPException
from pydantic import BaseModel, Field
from sqlalchemy import text
from sqlalchemy.ext.asyncio import AsyncSession

from app.agent.orchestrator import run_assistant_turn
from app.api.dependencies import get_bearer_token, get_current_user, get_db_session
from app.memory.manager import get_or_create_conversation, load_history
from app.services.summary import get_daily_summary, get_weekly_summary

log = logging.getLogger("sentiaai.chat")

router = APIRouter(prefix="/api/assistant", tags=["assistant"])


class ChatRequest(BaseModel):
    message: str = Field(min_length=1, max_length=4000)
    conversation_id: int | None = None


class ChatMessage(BaseModel):
    role: str
    content: str


class ChatResponse(BaseModel):
    conversation_id: int
    message: ChatMessage
    tool_calls_made: list[str] = []
    timestamp: str


@router.post("/chat", response_model=ChatResponse)
async def chat(
    req: ChatRequest,
    user_id: str = Depends(get_current_user),
    token: str = Depends(get_bearer_token),
    db: AsyncSession = Depends(get_db_session),
) -> ChatResponse:
    conversation_id = await get_or_create_conversation(db, user_id, req.conversation_id)
    try:
        answer, tools_used = await run_assistant_turn(db, user_id, token, conversation_id, req.message)
    except Exception as exc:
        log.exception("chat_failed", exc_info=exc)
        # Keep the API stable: if the LLM is unavailable, respond gracefully.
        raise HTTPException(status_code=502, detail="The assistant is temporarily unavailable.") from exc
    return ChatResponse(
        conversation_id=conversation_id,
        message=ChatMessage(role="assistant", content=answer),
        tool_calls_made=tools_used,
        timestamp=datetime.now(timezone.utc).isoformat(),
    )


@router.get("/history/{conversation_id}", response_model=list[ChatMessage])
async def history(
    conversation_id: int,
    user_id: str = Depends(get_current_user),
    db: AsyncSession = Depends(get_db_session),
) -> list[ChatMessage]:
    # Ownership check: conversation must belong to this user (Section 15).
    row = await db.execute(
        text(
            """
            SELECT id FROM ai_schema.conversations
            WHERE id = :cid AND user_id = :uid
            """
        ),
        {"cid": conversation_id, "uid": uuid.UUID(str(user_id))},
    )
    if row.scalar_one_or_none() is None:
        raise HTTPException(status_code=404, detail="Conversation not found")
    turns = await load_history(db, conversation_id, limit=500)
    return [ChatMessage(role=t["role"], content=t["content"]) for t in turns]


@router.get("/daily-summary")
async def daily_summary(
    user_id: str = Depends(get_current_user),
    token: str = Depends(get_bearer_token),
    db: AsyncSession = Depends(get_db_session),
    summary_date: dt_date | None = None,
) -> dict:
    d = summary_date or dt_date.today()
    return await get_daily_summary(db, user_id, token, d)


@router.get("/weekly-summary")
async def weekly_summary(
    user_id: str = Depends(get_current_user),
    token: str = Depends(get_bearer_token),
    db: AsyncSession = Depends(get_db_session),
    week_start: dt_date | None = None,
) -> dict:
    ws = week_start or (dt_date.today() - timedelta(days=dt_date.today().weekday()))
    return await get_weekly_summary(db, user_id, token, ws)
