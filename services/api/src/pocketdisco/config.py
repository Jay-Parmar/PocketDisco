from typing import Literal

from pydantic import Field, model_validator
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_prefix="POCKETDISCO_", extra="ignore")

    mode: Literal["production", "local_test"] = "production"
    database_url: str = ""
    redis_url: str = ""
    redis_prefix: str = "pocketdisco:v1"
    access_seconds: int = Field(default=900, ge=60, le=3600)
    refresh_seconds: int = Field(default=2592000, ge=3600, le=2592000)
    ticket_seconds: int = Field(default=60, ge=5, le=60)
    presence_seconds: int = Field(default=60, ge=30, le=120)
    invite_seconds: int = Field(default=86400, ge=60, le=604800)

    @model_validator(mode="after")
    def require_stores(self):
        if self.mode == "production":
            if not self.database_url.startswith("postgresql+asyncpg://"):
                raise ValueError("Production requires a PostgreSQL asyncpg URL")
            if not self.redis_url.startswith(("redis://", "rediss://")):
                raise ValueError("Production requires Redis")
        elif not self.database_url.startswith("sqlite+aiosqlite:///"):
            raise ValueError("local_test requires an explicit SQLite URL")
        return self
