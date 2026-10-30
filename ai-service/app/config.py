"""Settings come from environment variables or a local .env file (see .env.example)."""

from __future__ import annotations

from functools import lru_cache
from typing import Literal

from pydantic import SecretStr
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_file=".env", env_file_encoding="utf-8", extra="ignore")

    # "auto" = OpenAI when OPENAI_API_KEY is set, otherwise the offline keyword rules
    ai_provider: Literal["auto", "openai", "rules"] = "auto"

    openai_api_key: SecretStr | None = None
    openai_model: str = "gpt-5-mini"
    openai_timeout_seconds: float = 20.0

    # Optional shared secret. When set, callers must send it in the X-Api-Key header.
    service_api_key: SecretStr | None = None

    # Stage 3: the reply-drafting agent calls back into helpdesk-api for its tools
    helpdesk_api_url: str = "http://localhost:8080"
    helpdesk_api_timeout_seconds: float = 10.0
    agent_model: str | None = None  # defaults to openai_model
    agent_max_steps: int = 8
    agent_timeout_seconds: float = 30.0  # per model call

    # Stage 5b: consume ticket.created from RabbitMQ and publish ticket.triaged.
    # Off by default (no broker needed locally); docker-compose turns it on.
    auto_triage_worker_enabled: bool = False
    rabbitmq_url: str = "amqp://helpdesk:helpdesk@localhost:5672/"
    worker_prefetch: int = 4
    worker_max_attempts: int = 3
      
    # Stage 5c: key for helpdesk-api's internal agent API. Same default as Java's
    # helpdesk.internal-api.key so local runs work; docker-compose sets both.
    helpdesk_api_key: SecretStr = SecretStr("local-dev-secret") 

    log_level: str = "INFO"

    def resolved_provider(self) -> Literal["openai", "rules"]:
        if self.ai_provider == "auto":
            return "openai" if self.openai_api_key and self.openai_api_key.get_secret_value() else "rules"
        return self.ai_provider


@lru_cache
def get_settings() -> Settings:
    return Settings()
