"""Async SQLAlchemy engine/session limited to the `ai_schema` (Section 14).

The AI service touches PostgreSQL directly ONLY for its own `ai_schema` tables.
Health data is read via the Spring Boot REST proxy (health_client.py).
"""

from __future__ import annotations

import uuid
from collections.abc import AsyncIterator
from datetime import date, datetime, timezone

from sqlalchemy import BigInteger, Date, DateTime, ForeignKey, Integer, Numeric, String, Text, text
from sqlalchemy.dialects.postgresql import JSONB, UUID
from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker, create_async_engine
from sqlalchemy.orm import DeclarativeBase, Mapped, mapped_column

from app.config import settings

engine = create_async_engine(settings.DATABASE_URL, echo=False, pool_pre_ping=True)
async_session = async_sessionmaker(engine, expire_on_commit=False, class_=AsyncSession)


class Base(DeclarativeBase):
    pass


class Conversation(Base):
    __tablename__ = "conversations"
    __table_args__ = {"schema": "ai_schema"}

    id: Mapped[int] = mapped_column(BigInteger, primary_key=True)
    user_id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), nullable=False)
    started_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=lambda: datetime.now(timezone.utc))
    ended_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)
    is_active: Mapped[bool] = mapped_column(default=True)


class ConversationMessage(Base):
    __tablename__ = "conversation_messages"
    __table_args__ = {"schema": "ai_schema"}

    id: Mapped[int] = mapped_column(BigInteger, primary_key=True)
    conversation_id: Mapped[int] = mapped_column(ForeignKey("ai_schema.conversations.id", ondelete="CASCADE"))
    role: Mapped[str] = mapped_column(String(20), nullable=False)
    content: Mapped[str] = mapped_column(Text, nullable=False)
    tool_calls: Mapped[list | dict | None] = mapped_column(JSONB, nullable=True)  # jsonb
    tool_call_id: Mapped[str | None] = mapped_column(String(100), nullable=True)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=lambda: datetime.now(timezone.utc))


class DailySummary(Base):
    __tablename__ = "daily_summaries"
    __table_args__ = {"schema": "ai_schema"}

    id: Mapped[int] = mapped_column(BigInteger, primary_key=True)
    user_id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), nullable=False)
    summary_date: Mapped[date] = mapped_column(Date, nullable=False)
    version: Mapped[int] = mapped_column(default=1)
    sleep_hours: Mapped[float | None] = mapped_column(Numeric(4, 2), nullable=True)
    steps: Mapped[int | None] = mapped_column(Integer, nullable=True)
    resting_hr: Mapped[float | None] = mapped_column(Numeric(5, 1), nullable=True)
    avg_hr: Mapped[float | None] = mapped_column(Numeric(5, 1), nullable=True)
    max_hr: Mapped[int | None] = mapped_column(Integer, nullable=True)
    active_minutes: Mapped[int | None] = mapped_column(Integer, nullable=True)
    temperature_avg: Mapped[float | None] = mapped_column(Numeric(4, 1), nullable=True)
    wellness_score: Mapped[float | None] = mapped_column(Numeric(4, 1), nullable=True)
    summary_text: Mapped[str | None] = mapped_column(Text, nullable=True)
    recommendations: Mapped[str | None] = mapped_column(Text, nullable=True)
    health_context: Mapped[dict | None] = mapped_column(JSONB, nullable=True)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=lambda: datetime.now(timezone.utc))


class WeeklySummary(Base):
    __tablename__ = "weekly_summaries"
    __table_args__ = {"schema": "ai_schema"}

    id: Mapped[int] = mapped_column(BigInteger, primary_key=True)
    user_id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), nullable=False)
    week_start: Mapped[date] = mapped_column(Date, nullable=False)
    week_end: Mapped[date] = mapped_column(Date, nullable=False)
    version: Mapped[int] = mapped_column(default=1)
    avg_sleep_hours: Mapped[float | None] = mapped_column(Numeric(4, 2), nullable=True)
    avg_steps: Mapped[int | None] = mapped_column(Integer, nullable=True)
    avg_resting_hr: Mapped[float | None] = mapped_column(Numeric(5, 1), nullable=True)
    avg_active_minutes: Mapped[int | None] = mapped_column(Integer, nullable=True)
    sleep_consistency: Mapped[float | None] = mapped_column(Numeric(4, 3), nullable=True)
    exercise_consistency: Mapped[float | None] = mapped_column(Numeric(4, 3), nullable=True)
    wellness_trend: Mapped[str | None] = mapped_column(String(20), nullable=True)
    vs_previous_week: Mapped[dict | None] = mapped_column(JSONB, nullable=True)
    summary_text: Mapped[str | None] = mapped_column(Text, nullable=True)
    recommendations: Mapped[str | None] = mapped_column(Text, nullable=True)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=lambda: datetime.now(timezone.utc))


async def get_db() -> AsyncIterator[AsyncSession]:
    """Yield a session scoped to the ai_schema search path."""
    async with async_session() as session:
        # Ensure the schema is the default for this session (Section 14.2).
        await session.execute(text("SET search_path TO ai_schema"))
        yield session

