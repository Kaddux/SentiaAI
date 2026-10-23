"""Auth + DB dependencies.

The AI service validates the JWT issued by Spring Boot (Section 15.3). The
backend keys with HMAC; we accept the common HS* algorithms and use the user id
(subject) as the authenticated principal for all tool/memory scoping.
"""

from __future__ import annotations

from collections.abc import AsyncIterator

import jwt
from fastapi import Depends, HTTPException, status
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer
from sqlalchemy.ext.asyncio import AsyncSession

from app.config import settings
from app.services.database import get_db

_bearer = HTTPBearer(auto_error=False)

# Backend picks the HMAC algorithm by key length; try the common HS* set.
_ALGORITHMS = ["HS256", "HS384", "HS512"]


async def get_current_user(
    credentials: HTTPAuthorizationCredentials | None = Depends(_bearer),
) -> str:
    """Return the authenticated user_id (from the JWT), or 401."""
    if credentials is None:
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="Missing bearer token")
    token = credentials.credentials
    try:
        payload = jwt.decode(token, settings.JWT_SECRET, algorithms=_ALGORITHMS)
    except jwt.PyJWTError as exc:
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="Invalid or expired token") from exc
    subject = payload.get("sub")
    if not subject:
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="Token missing subject")
    return subject


async def get_bearer_token(
    credentials: HTTPAuthorizationCredentials | None = Depends(_bearer),
) -> str:
    """Return the raw JWT (needed to forward to the backend for context)."""
    if credentials is None:
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="Missing bearer token")
    return credentials.credentials


async def get_db_session() -> AsyncIterator[AsyncSession]:
    async for session in get_db():
        yield session
