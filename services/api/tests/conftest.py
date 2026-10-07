from pathlib import Path
from tempfile import TemporaryDirectory

import pytest

from pocketdisco.config import Settings
from pocketdisco.database import Database


@pytest.fixture
def settings():
    test_dir = Path(__file__).resolve().parents[1] / ".local-tools"
    test_dir.mkdir(exist_ok=True)
    with TemporaryDirectory(prefix="api-test-", dir=test_dir) as directory:
        database_file = (Path(directory) / "test.db").as_posix()
        yield Settings(mode="local_test", database_url=f"sqlite+aiosqlite:///{database_file}")


@pytest.fixture
async def database(settings):
    db = Database(settings)
    await db.start()
    yield db
    await db.close()
