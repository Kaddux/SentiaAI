from typing import Optional

from pydantic_settings import BaseSettings


class Settings(BaseSettings):
    # MUST include the +asyncpg driver prefix — create_async_engine (Section 14.2)
    # requires it. Without it, SQLAlchemy falls back to the sync psycopg2 driver.
    DATABASE_URL: str = "postgresql+asyncpg://health_user:change-me@localhost:5432/health_assistant"
    # LLM provider selection (Section 11.5): "nvidia" (default) | "ollama" | "deepseek"
    LLM_PROVIDER: str = "nvidia"
    LLM_FALLBACK_PROVIDER: str = "deepseek"
    NVIDIA_API_KEY: str = ""    # set NVIDIA_API_KEY in .env or the environment
    NVIDIA_BASE_URL: str = "https://integrate.api.nvidia.com/v1"
    NVIDIA_MODEL: str = "nvidia/nemotron-3.5-lightning-30b-a3b"
    OLLAMA_BASE_URL: str = "http://ollama:11434/v1"
    DEEPSEEK_API_KEY: str = ""
    DEEPSEEK_BASE_URL: str = "https://api.deepseek.com"
    DEEPSEEK_MODEL: str = "deepseek-chat"
    LLM_MODEL: str = "qwen3:8b"              # Ollama model (Q4 quant)
    LLM_TEMPERATURE: float = 0.3
    LLM_MAX_TOKENS: int = 1024
    LLM_REASONING_EFFORT: str = "low"   # gpt-oss reasoning budget (low = fast replies)
    LLM_TIMEOUT_SECONDS: float = 45.0
    # Server-side speech-to-text (no browser/cloud dependency).
    STT_MODEL: str = "base.en"
    STT_MODEL_DIR: str = "/models"
    GEMINI_API_KEY: Optional[str] = None
    GEMINI_MODEL: str = "gemini-2.0-flash"
    BACKEND_URL: str = "http://localhost:8080"
    ML_SERVICE_URL: str = "http://localhost:8001"
    JWT_SECRET: str = "dev-secret-change-in-production-dev-secret-change"
    MAX_SHORT_TERM_MESSAGES: int = 20
    MAX_TOOL_CALLS_PER_TURN: int = 5
    BASELINE_WINDOW_DAYS: int = 14
    CONVERSATION_RETENTION_DAYS: int = 90   # prune ai_schema conversation history older than this

    model_config = {"env_file": ".env", "extra": "ignore"}


settings = Settings()
