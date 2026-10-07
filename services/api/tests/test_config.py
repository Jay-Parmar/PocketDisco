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


async def test_local_database_starts():
    db = Database(Settings(mode="local_test", database_url="sqlite+aiosqlite:///:memory:"))
    await db.start()
    await db.close()
