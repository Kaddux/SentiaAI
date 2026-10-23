# pyrefly: ignore [missing-import]
from fastapi import FastAPI
# pyrefly: ignore [missing-import]
from fastapi.middleware.cors import CORSMiddleware

import asyncio
import logging

from app.api.routes.chat import router as chat_router
from app.api.routes.voice import router as voice_router
from app.config import settings
from app.services.retention import retention_loop
from app.services.stt import warm as warm_stt

log = logging.getLogger("sentiaai.main")

app = FastAPI(title="Personal Health AI Assistant", version="0.1.0")

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

app.include_router(chat_router)
app.include_router(voice_router)


@app.on_event("startup")
async def _start_retention():
    # Prunes ai_schema conversation history (90d) at startup and daily thereafter.
    asyncio.create_task(retention_loop())
    log.info("conversation_retention_task_started days=%s", settings.CONVERSATION_RETENTION_DAYS)


@app.on_event("startup")
async def _start_voice():
    # Pre-load/download the local STT model so the first voice request is fast.
    asyncio.create_task(warm_stt())
    log.info("stt_warm_task_started model=%s", settings.STT_MODEL)


@app.get("/health")
async def health_check():
    provider = settings.LLM_PROVIDER
    return {
        "status": "ok",
        "service": "ai-service",
        "version": "0.1.0",
        "llm_provider": provider,
        "llm_model": active_model(provider),
        "llm_fallback": settings.LLM_FALLBACK_PROVIDER,
    }


def active_model(provider: str) -> str:
    if provider == "ollama":
        return settings.LLM_MODEL
    if provider == "deepseek":
        return settings.DEEPSEEK_MODEL
    return settings.NVIDIA_MODEL
