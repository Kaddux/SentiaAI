"""LLM client abstraction (plan Section 11.5).

Primary provider is NVIDIA NIM (build.nvidia.com) — an OpenAI-compatible
endpoint at https://integrate.api.nvidia.com/v1. Local Ollama and the DeepSeek
API are kept as optional documented alternatives (not active by default).
"""

from __future__ import annotations

import logging
from typing import Any

from openai import AsyncOpenAI

from app.config import settings

log = logging.getLogger("sentiaai.llm")


class LLMClient:
    """Minimal interface implemented by every provider."""

    async def chat_completion(self, messages: list[dict], tools: list | None = None) -> Any:
        raise NotImplementedError


class NvidiaClient(LLMClient):
    """NVIDIA NIM (build.nvidia.com) — OpenAI-compatible, strong tool-calling."""

    def __init__(self) -> None:
        self.client = AsyncOpenAI(
            api_key=settings.NVIDIA_API_KEY,
            base_url=settings.NVIDIA_BASE_URL,
            timeout=settings.LLM_TIMEOUT_SECONDS,
        )

    async def chat_completion(self, messages: list[dict], tools: list | None = None) -> Any:
        # gpt-oss is a reasoning model and honors `reasoning_effort`. Other NIM
        # models (e.g. nemotron-lightning) don't need it, so only send it for gpt-oss.
        extra_body: dict[str, Any] = {}
        if settings.LLM_REASONING_EFFORT and settings.NVIDIA_MODEL.startswith("openai/"):
            extra_body["reasoning_effort"] = settings.LLM_REASONING_EFFORT
        return await self.client.chat.completions.create(
            model=settings.NVIDIA_MODEL,
            messages=messages,
            tools=tools or [],
            tool_choice="auto",
            temperature=settings.LLM_TEMPERATURE,
            max_tokens=settings.LLM_MAX_TOKENS,
            extra_body=extra_body or None,
        )


class LocalOllamaClient(LLMClient):
    """Local model via Ollama's OpenAI-compatible endpoint (Qwen3-8B Q4)."""

    def __init__(self) -> None:
        # Ollama ignores the API key; a placeholder is required by the SDK.
        self.client = AsyncOpenAI(
            api_key="ollama",
            base_url=settings.OLLAMA_BASE_URL,
            timeout=settings.LLM_TIMEOUT_SECONDS,
        )

    async def chat_completion(self, messages: list[dict], tools: list | None = None) -> Any:
        return await self.client.chat.completions.create(
            model=settings.LLM_MODEL,
            messages=messages,
            tools=tools or [],
            tool_choice="auto",
            temperature=settings.LLM_TEMPERATURE,
            max_tokens=settings.LLM_MAX_TOKENS,
        )


class DeepSeekClient(LLMClient):
    """External fallback (deepseek-chat)."""

    def __init__(self) -> None:
        self.client = AsyncOpenAI(
            api_key=settings.DEEPSEEK_API_KEY,
            base_url=settings.DEEPSEEK_BASE_URL,
            timeout=settings.LLM_TIMEOUT_SECONDS,
        )

    async def chat_completion(self, messages: list[dict], tools: list | None = None) -> Any:
        return await self.client.chat.completions.create(
            model=settings.DEEPSEEK_MODEL,
            messages=messages,
            tools=tools or [],
            tool_choice="auto",
            temperature=settings.LLM_TEMPERATURE,
            max_tokens=settings.LLM_MAX_TOKENS,
        )


def _build_clients() -> tuple[LLMClient, LLMClient | None]:
    """Return (primary, fallback). Fallback is None when only one provider is active.

    Default is NVIDIA-only. Ollama / DeepSeek can be re-enabled via LLM_PROVIDER.
    """
    provider = settings.LLM_PROVIDER
    if provider == "ollama":
        return LocalOllamaClient(), None
    if provider == "deepseek":
        return DeepSeekClient(), None
    # default: NVIDIA NIM only
    return NvidiaClient(), None


# Instantiated at import.
PRIMARY_CLIENT, FALLBACK_CLIENT = _build_clients()


async def chat_with_fallback(messages: list[dict], tools: list | None = None) -> Any:
    """Primary first; on exception, retry once with a fallback if one is set."""
    clients = [PRIMARY_CLIENT]
    if FALLBACK_CLIENT is not None:
        clients.append(FALLBACK_CLIENT)

    last_error: Exception | None = None
    for client in clients:
        try:
            return await client.chat_completion(messages, tools)
        except Exception as exc:  # timeout, 5xx, malformed request
            log.warning("llm_provider_failed: %s — %s", type(client).__name__, exc)
            last_error = exc
    if last_error is not None:
        raise last_error
    raise RuntimeError("no LLM provider configured")
