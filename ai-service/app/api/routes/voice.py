"""Voice input: audio -> text (server-side, no browser cloud dependency)."""

from __future__ import annotations

import logging

from fastapi import APIRouter, Depends, File, HTTPException, UploadFile

from app.api.dependencies import get_current_user
from app.services.stt import transcribe_bytes

router = APIRouter(prefix="/api/assistant", tags=["voice"])
log = logging.getLogger("sentiaai.voice")

MAX_AUDIO_BYTES = 20 * 1024 * 1024  # 20 MB (~several minutes of Opus)


@router.post("/transcribe")
async def transcribe(
    file: UploadFile = File(...),
    user_id: str = Depends(get_current_user),
) -> dict:
    data = await file.read()
    log.info("transcribe_request user=%s bytes=%s type=%s", user_id, len(data), file.content_type)
    if not data:
        raise HTTPException(status_code=400, detail="Empty audio upload")
    if len(data) > MAX_AUDIO_BYTES:
        raise HTTPException(status_code=413, detail="Audio too large")
    try:
        text = await transcribe_bytes(data)
    except Exception as exc:  # noqa: BLE001
        log.exception("transcribe_failed")
        raise HTTPException(status_code=502, detail="Transcription failed") from exc
    log.info("transcribe_result bytes=%s text_len=%s", len(data), len(text))
    return {"text": text}
