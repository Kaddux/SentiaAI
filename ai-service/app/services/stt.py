"""Server-side speech-to-text with faster-whisper.

Runs locally in the ai-service container so voice input does not depend on the
browser's cloud speech service. The model is loaded lazily (and warmed at
startup); downloads are cached under STT_MODEL_DIR (mounted as a volume).
"""

from __future__ import annotations

import asyncio
import logging
import os
import tempfile

from faster_whisper import WhisperModel

from app.config import settings

log = logging.getLogger("sentiaai.stt")

_model: WhisperModel | None = None
_lock = asyncio.Lock()


def _load() -> WhisperModel:
    log.info("stt_model_loading model=%s dir=%s", settings.STT_MODEL, settings.STT_MODEL_DIR)
    model = WhisperModel(
        settings.STT_MODEL,
        device="cpu",
        compute_type="int8",
        download_root=settings.STT_MODEL_DIR,
    )
    log.info("stt_model_ready model=%s", settings.STT_MODEL)
    return model


async def _get_model() -> WhisperModel:
    global _model
    if _model is None:
        async with _lock:
            if _model is None:
                _model = await asyncio.to_thread(_load)
    return _model


async def warm() -> None:
    """Pre-load/download the model so the first transcription isn't slow."""
    try:
        await _get_model()
        log.info("stt_warm_complete")
    except Exception as exc:  # noqa: BLE001
        log.warning("stt_warm_failed %s", exc)


def _transcribe_sync(model: WhisperModel, path: str) -> str:
    segments, _info = model.transcribe(
        path,
        language="en",
        vad_filter=True,
        beam_size=1,
        condition_on_previous_text=False,
    )
    return " ".join(seg.text.strip() for seg in segments).strip()


async def transcribe_bytes(data: bytes, suffix: str = ".webm") -> str:
    if not data:
        return ""
    model = await _get_model()
    fd, path = tempfile.mkstemp(suffix=suffix)
    try:
        with os.fdopen(fd, "wb") as fh:
            fh.write(data)
        return await asyncio.to_thread(_transcribe_sync, model, path)
    finally:
        try:
            os.unlink(path)
        except OSError:
            pass
