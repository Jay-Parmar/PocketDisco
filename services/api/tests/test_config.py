import pytest
from pydantic import ValidationError

from pocketdisco.config import Settings
from pocketdisco.database import Database


def test_no_implicit_local_store():
    with pytest.raises(ValidationError, match="PostgreSQL"):
        Settings(mode="production", _env_file=None, database_url="", redis_url="")


def test_production_rejects_sqlite():
    with pytest.raises(ValidationError, match="PostgreSQL"):
        Settings(
            mode="production",
            database_url="sqlite+aiosqlite:///:memory:",
            redis_url="redis://localhost",
        )


def test_production_requires_redis():
    with pytest.raises(ValidationError, match="Redis"):
        Settings(
            mode="production",
            database_url="postgresql+asyncpg://localhost/pocketdisco",
            redis_url="",
        )


def test_local_store_is_explicit():
    with pytest.raises(ValidationError, match="explicit SQLite"):
        Settings(mode="local_test", database_url="")


@pytest.mark.parametrize(
    "overrides",
    [
        {"database_url": "invalid://user:database-canary@localhost/pocketdisco"},
        {"redis_url": "invalid://:redis-canary@localhost/0"},
        {"access_seconds": "access-canary"},
    ],
)
def test_config_errors_hide_input_values(overrides):
    values = {
        "database_url": "postgresql+asyncpg://user:database-canary@localhost/pocketdisco",
        "redis_url": "redis://:redis-canary@localhost/0",
        **overrides,
    }
    with pytest.raises(ValidationError) as error:
        Settings(**values)

    message = str(error.value)
    assert "input_value" not in message
    assert "canary" not in message


async def test_local_database_starts():
    db = Database(Settings(mode="local_test", database_url="sqlite+aiosqlite:///:memory:"))
    await db.start()
    await db.close()
