from ipaddress import ip_address, ip_network
from typing import Literal

from pydantic import Field, field_validator, model_validator
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(
        env_prefix="POCKETDISCO_", extra="ignore", hide_input_in_errors=True
    )

    mode: Literal["production", "local_test"] = "production"
    database_url: str = ""
    redis_url: str = ""
    redis_prefix: str = "pocketdisco:v1"
    bind_host: str = "127.0.0.1"
    port: int = Field(default=8000, ge=1, le=65535)
    trusted_proxy_ips: str = ""
    concurrency_limit: int = Field(default=64, ge=1, le=1024)
    shutdown_seconds: int = Field(default=15, ge=1, le=60)
    access_seconds: int = Field(default=900, ge=60, le=3600)
    refresh_seconds: int = Field(default=2592000, ge=3600, le=2592000)
    ticket_seconds: int = Field(default=60, ge=5, le=60)
    presence_seconds: int = Field(default=60, ge=30, le=120)
    invite_seconds: int = Field(default=86400, ge=60, le=604800)

    @field_validator("bind_host")
    @classmethod
    def literal_bind_address(cls, value):
        try:
            return str(ip_address(value))
        except ValueError:
            raise ValueError("Bind host must be an IP address") from None

    @field_validator("trusted_proxy_ips")
    @classmethod
    def explicit_proxy_peers(cls, value):
        if not value.strip():
            return ""
        peers = [peer.strip() for peer in value.split(",")]
        try:
            for peer in peers:
                network = ip_network(peer)
                if network.prefixlen == 0:
                    raise ValueError
        except ValueError:
            raise ValueError(
                "Proxy peers must be explicit IP addresses or bounded networks"
            ) from None
        return ",".join(peers)

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
