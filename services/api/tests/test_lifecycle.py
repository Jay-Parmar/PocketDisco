import asyncio
import traceback
from contextlib import asynccontextmanager
from unittest.mock import AsyncMock

import pytest
from fastapi.testclient import TestClient
from pydantic import ValidationError

from pocketdisco.app import create_app
from pocketdisco.config import Settings


@pytest.mark.parametrize("field", ["health_seconds", "startup_seconds", "cleanup_seconds"])
@pytest.mark.parametrize("value", [0, float("inf"), float("nan")])
def test_lifecycle_deadlines_must_be_finite_and_positive(field, value):
    with pytest.raises(ValidationError):
        Settings(mode="local_test", database_url="sqlite+aiosqlite:///:memory:", **{field: value})


@pytest.mark.parametrize("store_name", ["database", "live"])
async def test_shutdown_closes_both_stores_after_one_failure(
    settings, monkeypatch, caplog, store_name
):
    app = create_app(settings)
    for store in (app.state.database, app.state.live):
        monkeypatch.setattr(store, "start", AsyncMock())
        monkeypatch.setattr(store, "close", AsyncMock())
    failed_store = getattr(app.state, store_name)
    failed_store.close.side_effect = RuntimeError("credential-canary")

    async with app.router.lifespan_context(app):
        pass

    app.state.database.close.assert_awaited_once()
    app.state.live.close.assert_awaited_once()
    assert "Datastore shutdown incomplete" in caplog.text
    assert "credential-canary" not in caplog.text


async def test_shutdown_timeout_does_not_block_other_store(settings, monkeypatch, caplog):
    settings.cleanup_seconds = 0.1
    app = create_app(settings)
    cancelled = []

    async def hanging_close():
        try:
            await asyncio.Event().wait()
        finally:
            cancelled.append(True)

    for store in (app.state.database, app.state.live):
        monkeypatch.setattr(store, "start", AsyncMock())
    monkeypatch.setattr(app.state.live, "close", hanging_close)
    monkeypatch.setattr(app.state.database, "close", AsyncMock())

    async def lifecycle():
        async with app.router.lifespan_context(app):
            pass

    await asyncio.wait_for(lifecycle(), 1)
    app.state.database.close.assert_awaited_once()
    assert cancelled == [True]
    assert "Datastore shutdown incomplete" in caplog.text


@pytest.mark.parametrize("store_name", ["database", "live"])
async def test_startup_errors_hide_credentials_and_close_both_stores(
    settings, monkeypatch, store_name
):
    app = create_app(settings)
    for store in (app.state.database, app.state.live):
        monkeypatch.setattr(store, "start", AsyncMock())
        monkeypatch.setattr(store, "close", AsyncMock())
    getattr(app.state, store_name).start.side_effect = RuntimeError("credential-canary")

    with pytest.raises(RuntimeError, match="Datastore startup failed") as error:
        async with app.router.lifespan_context(app):
            pytest.fail("Startup should fail")

    assert "credential-canary" not in "".join(traceback.format_exception(error.value))
    app.state.database.close.assert_awaited_once()
    app.state.live.close.assert_awaited_once()


async def test_startup_has_a_total_deadline(settings, monkeypatch):
    settings.startup_seconds = 0.1
    app = create_app(settings)

    async def hanging_start():
        await asyncio.Event().wait()

    monkeypatch.setattr(app.state.database, "start", hanging_start)
    monkeypatch.setattr(app.state.database, "close", AsyncMock())
    monkeypatch.setattr(app.state.live, "start", AsyncMock())
    monkeypatch.setattr(app.state.live, "close", AsyncMock())

    async def lifecycle():
        async with app.router.lifespan_context(app):
            pytest.fail("Startup should time out")

    with pytest.raises(RuntimeError, match="Datastore startup failed"):
        await asyncio.wait_for(lifecycle(), 1)
    app.state.live.start.assert_not_awaited()
    app.state.database.close.assert_awaited_once()
    app.state.live.close.assert_awaited_once()


@pytest.mark.parametrize("store_name", ["database", "live"])
def test_health_times_out_without_exposing_store_errors(settings, monkeypatch, store_name):
    settings.health_seconds = 0.1
    app = create_app(settings)
    cancelled = []

    async def hanging_check():
        try:
            await asyncio.Event().wait()
        finally:
            cancelled.append(True)

    @asynccontextmanager
    async def hanging_transaction():
        await hanging_check()
        yield

    with TestClient(app) as client:
        if store_name == "live":
            monkeypatch.setattr(app.state.live, "start", hanging_check)
        else:
            monkeypatch.setattr(app.state.database, "transaction", hanging_transaction)
        response = client.get("/healthz")

    assert response.status_code == 503
    assert response.json() == {
        "detail": {"code": "service_unavailable", "message": "Please try again shortly."}
    }
    assert cancelled == [True]


@pytest.mark.parametrize("store_name", ["database", "redis"])
def test_store_construction_errors_hide_connection_inputs(store_name):
    values = {
        "database_url": "postgresql+asyncpg://localhost/pocketdisco",
        "redis_url": "redis://localhost/0",
    }
    values[f"{store_name}_url"] = values[f"{store_name}_url"].replace(
        "localhost", "localhost:credential-canary"
    )
    with pytest.raises(RuntimeError, match="Datastore configuration is invalid") as error:
        create_app(Settings(**values))
    assert "credential-canary" not in "".join(traceback.format_exception(error.value))
