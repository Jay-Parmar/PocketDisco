import asyncio
from unittest.mock import AsyncMock, Mock

import pytest
from pydantic import ValidationError

from pocketdisco.config import Settings


def runtime_settings(**overrides):
    return Settings(mode="local_test", database_url="sqlite+aiosqlite:///:memory:", **overrides)


def test_runtime_defaults_are_local_and_bounded():
    settings = runtime_settings()
    assert settings.bind_host == "127.0.0.1"
    assert settings.port == 8000
    assert settings.trusted_proxy_ips == ""
    assert settings.concurrency_limit == 64
    assert settings.shutdown_seconds == 15


@pytest.mark.parametrize("value", ["*", "127.0.0.1,*", "0.0.0.0/0", "::/0", "proxy.example"])
def test_proxy_trust_rejects_wildcards_and_hostnames(value):
    with pytest.raises(ValidationError, match="Proxy peers"):
        runtime_settings(trusted_proxy_ips=value)


def test_proxy_trust_accepts_explicit_addresses_and_networks():
    settings = runtime_settings(trusted_proxy_ips=" 127.0.0.1, ::1, 172.20.0.0/28 ")
    assert settings.trusted_proxy_ips == "127.0.0.1,::1,172.20.0.0/28"


@pytest.mark.parametrize(
    "overrides",
    [
        {"bind_host": "host.example"},
        {"port": 0},
        {"port": 65536},
        {"concurrency_limit": 0},
        {"shutdown_seconds": 0},
    ],
)
def test_runtime_limits_reject_invalid_values(overrides):
    with pytest.raises(ValidationError):
        runtime_settings(**overrides)


def test_runner_uses_runtime_settings_without_access_logs(monkeypatch):
    from pocketdisco import __main__ as runner

    settings = runtime_settings(
        bind_host="0.0.0.0", port=8765, trusted_proxy_ips="127.0.0.1", concurrency_limit=32
    )
    run = Mock()
    monkeypatch.setattr(runner, "Settings", lambda: settings)
    monkeypatch.setattr(runner.uvicorn, "run", run)
    runner.main()
    run.assert_called_once_with(
        "pocketdisco.app:create_app",
        factory=True,
        workers=1,
        host="0.0.0.0",
        port=8765,
        log_level="warning",
        access_log=False,
        forwarded_allow_ips="127.0.0.1",
        ws_max_size=8192,
        limit_concurrency=32,
        timeout_graceful_shutdown=15,
    )


@pytest.mark.parametrize("scope_type", ["http", "websocket"])
async def test_concurrency_limit_rejects_excess_requests_and_sockets(scope_type):
    from pocketdisco.app import ConcurrencyLimit

    entered = asyncio.Event()
    release = asyncio.Event()

    async def held_app(scope, receive, send):
        entered.set()
        await release.wait()

    middleware = ConcurrencyLimit(held_app, limit=1)
    first = asyncio.create_task(middleware({"type": "websocket"}, AsyncMock(), AsyncMock()))
    try:
        await asyncio.wait_for(entered.wait(), 1)
        send = AsyncMock()
        await middleware({"type": scope_type}, AsyncMock(), send)
        first_message = send.call_args_list[0].args[0]
        if scope_type == "http":
            assert first_message["status"] == 503
            assert (b"cache-control", b"no-store") in first_message["headers"]
        else:
            assert first_message == {"type": "websocket.close", "code": 1013}
        assert middleware.active == 1
    finally:
        release.set()
        await first
    assert middleware.active == 0


async def test_concurrency_limit_releases_slot_after_failure():
    from pocketdisco.app import ConcurrencyLimit

    middleware = ConcurrencyLimit(AsyncMock(side_effect=RuntimeError("failure")), limit=1)
    with pytest.raises(RuntimeError):
        await middleware({"type": "http"}, AsyncMock(), AsyncMock())
    assert middleware.active == 0


async def test_concurrency_limit_excludes_lifespan():
    from pocketdisco.app import ConcurrencyLimit

    app = AsyncMock()
    middleware = ConcurrencyLimit(app, limit=1)
    middleware.active = 1
    await middleware({"type": "lifespan"}, AsyncMock(), AsyncMock())
    app.assert_awaited_once()
    assert middleware.active == 1
