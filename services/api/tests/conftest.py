import pytest

from pocketdisco.config import Settings
from pocketdisco.database import Database


@pytest.fixture
def settings():
    return Settings(mode="local_test", database_url="sqlite+aiosqlite:///:memory:")


@pytest.fixture
async def database(settings):
    db = Database(settings)
    await db.start()
    yield db
    await db.close()
