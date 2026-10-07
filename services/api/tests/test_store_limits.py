from unittest.mock import Mock

import pytest
from pydantic import ValidationError

from pocketdisco.config import Settings
from pocketdisco.database import Database
from pocketdisco.live import RedisLive


def production_settings(**overrides):
    return Settings(
        **{
            "database_url": "postgresql+asyncpg://localhost/pocketdisco",
            "redis_url": "redis://localhost/0",
            **overrides,
        }
    )


def test_postgres_pool_and_query_limits(monkeypatch):
    create_engine = Mock()
    monkeypatch.setattr("pocketdisco.database.create_async_engine", create_engine)
    settings = production_settings()
    Database(settings)
    create_engine.assert_called_once_with(
        settings.database_url,
        pool_pre_ping=True,
        hide_parameters=True,
        pool_size=5,
        max_overflow=5,
        pool_timeout=3,
        connect_args={
            "timeout": 3,
            "command_timeout": 5,
            "server_settings": {
                "statement_timeout": "5000",
                "lock_timeout": "2000",
                "idle_in_transaction_session_timeout": "10000",
            },
        },
    )


@pytest.mark.parametrize(
    "overrides",
    [
        {"database_pool_size": 0},
        {"database_max_overflow": -1},
        {"database_pool_timeout_seconds": 0},
        {"database_connect_seconds": float("inf")},
        {"database_statement_seconds": 0},
        {"database_lock_seconds": 0},
        {"redis_max_connections": 0},
    ],
)
def test_store_budgets_cannot_be_unbounded(overrides):
    with pytest.raises(ValidationError):
        production_settings(**overrides)


async def test_redis_pool_is_bounded_even_with_url_overrides():
    settings = production_settings(
        redis_url=(
            "redis://localhost/0?max_connections=0&socket_timeout=0&socket_connect_timeout=0"
            "&retry_on_timeout=true"
        ),
        redis_max_connections=32,
    )
    live = RedisLive(settings)
    try:
        pool = live.redis.connection_pool
        assert pool.max_connections == 32
        assert pool.connection_kwargs["socket_timeout"] == 5
        assert pool.connection_kwargs["socket_connect_timeout"] == 3
        assert pool.connection_kwargs["retry_on_timeout"] is False
        assert live.redis.auto_close_connection_pool is True
    finally:
        await live.close()


async def test_sqlite_keeps_its_own_connection_options(settings):
    database = Database(settings)
    try:
        await database.start()
        assert database.engine.sync_engine.hide_parameters is True
    finally:
        await database.close()
