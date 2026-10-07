import asyncio
import os
from uuid import uuid4

import pytest
from fastapi.testclient import TestClient

from pocketdisco.app import create_app
from pocketdisco.auth import Auth
from pocketdisco.config import Settings
from pocketdisco.database import Database
from pocketdisco.errors import ApiError
from pocketdisco.live import RedisLive
from pocketdisco.rooms import Rooms

pytestmark = pytest.mark.integration


@pytest.fixture
def integration_settings():
    url = os.environ.get("POCKETDISCO_TEST_DATABASE_URL")
    redis_url = os.environ.get("POCKETDISCO_TEST_REDIS_URL")
    if not url or not redis_url:
        pytest.skip("Set both test store URLs to run real-service integration")
    if not url.rsplit("/", 1)[-1].endswith("_test"):
        pytest.fail("Integration database name must end in _test")
    return Settings(
        mode="production",
        database_url=url,
        redis_url=redis_url,
        redis_prefix=f"pocketdisco:test:{uuid4()}",
    )


@pytest.fixture
async def real_stores(integration_settings):
    database = Database(integration_settings)
    live = RedisLive(integration_settings)
    await database.start()
    await live.start()
    try:
        yield database, live
    finally:
        keys = [key async for key in live.redis.scan_iter(live.key("*"))]
        if keys:
            await live.redis.delete(*keys)
        await live.close()
        await database.close()


async def test_real_concurrent_refresh_revokes_reused_family(real_stores, integration_settings):
    database, live = real_stores
    auth = Auth(database, integration_settings)
    guest = await auth.guest("Integration guest")
    results = await asyncio.gather(
        auth.refresh(guest.refresh_token), auth.refresh(guest.refresh_token), return_exceptions=True
    )
    assert sum(isinstance(result, ApiError) for result in results) == 1
    with pytest.raises(ApiError):
        await auth.authenticate(guest.access_token)


async def test_real_ticket_is_atomic_across_clients(real_stores, integration_settings):
    database, first = real_stores
    second = RedisLive(integration_settings)
    try:
        value = await first.issue_ticket({"session_id": "test", "room_id": "test"})
        results = await asyncio.gather(first.consume_ticket(value), second.consume_ticket(value))
        assert results.count(None) == 1
        assert results.count({"session_id": "test", "room_id": "test"}) == 1
    finally:
        await second.close()


async def test_real_fanout_crosses_instances(real_stores, integration_settings):
    database, first = real_stores
    second = RedisLive(integration_settings)
    try:
        async with first.subscribe("room") as subscriber:
            await asyncio.sleep(5.2)
            assert not subscriber.failed
            await second.publish("room", {"revision": 2})
            assert await asyncio.wait_for(subscriber.queue.get(), 5) == {"revision": 2}
    finally:
        await second.close()


async def test_real_presence_expiry_clears_ready(real_stores):
    database, live = real_stores
    await live.connect("room", "user", "a")
    await live.set_ready("room", "user", True)
    assert await live.touch("room", "user", "a")
    assert await live.presence("room") == ({"user"}, {"user"})
    await live.redis.zadd(live.key("room:room:connections"), {"user:a": 0})
    assert not await live.touch("room", "user", "a")
    assert await live.presence("room") == (set(), set())


async def test_real_rate_limit_is_atomic(real_stores):
    database, live = real_stores
    results = await asyncio.gather(
        *(live.rate_limit("guest", 5, 60) for _ in range(20)), return_exceptions=True
    )
    assert sum(isinstance(result, ApiError) for result in results) == 15
    assert await live.redis.ttl(live.key("rate:guest")) > 0


async def test_real_concurrent_chat_is_idempotent(real_stores, integration_settings):
    database, live = real_stores
    auth = Auth(database, integration_settings)
    session = await auth.guest("Mira")
    user = await auth.authenticate(session.access_token)
    rooms = Rooms(database, live, integration_settings)
    snapshot, invite = await rooms.create(user, "Integration")
    command_id = str(uuid4())
    results = await asyncio.gather(
        *(rooms.chat(user, snapshot.room_id, command_id, "Hello") for _ in range(5))
    )
    assert {result.revision for result in results} == {2}
    assert all(len(result.messages) == 1 for result in results)


async def test_real_capacity_lock_prevents_overfill(real_stores, integration_settings):
    database, live = real_stores
    auth = Auth(database, integration_settings)
    sessions = await asyncio.gather(*(auth.guest(f"Guest {index}") for index in range(27)))
    users = await asyncio.gather(*(auth.authenticate(item.access_token) for item in sessions))
    rooms = Rooms(database, live, integration_settings)
    snapshot, invite = await rooms.create(users[0], "Capacity")
    results = await asyncio.gather(
        *(rooms.join(user, invite) for user in users[1:]), return_exceptions=True
    )
    assert sum(isinstance(result, ApiError) for result in results) == 2
    assert len((await rooms.snapshot(users[0], snapshot.room_id)).members) == 25


def test_real_sockets_recover_across_api_instances(integration_settings):
    first_app = create_app(integration_settings)
    second_app = create_app(integration_settings)
    with TestClient(first_app) as first, TestClient(second_app) as second:
        host = first.post("/v1/auth/guest", json={"display_name": "Mira"}).json()
        friend = second.post("/v1/auth/guest", json={"display_name": "Sam"}).json()
        headers = {"Authorization": f"Bearer {host['access_token']}"}
        friend_headers = {"Authorization": f"Bearer {friend['access_token']}"}
        room = first.post("/v1/rooms", json={"name": "Cross instance"}, headers=headers).json()
        room_id = room["snapshot"]["room_id"]
        assert (
            second.post(
                f"/v1/rooms/{room['invite_code']}/join", json={}, headers=friend_headers
            ).status_code
            == 200
        )
        first_ticket = first.post(
            "/v1/realtime/tickets", json={"room_id": room_id}, headers=headers
        ).json()["ticket"]
        second_ticket = first.post(
            "/v1/realtime/tickets", json={"room_id": room_id}, headers=friend_headers
        ).json()["ticket"]
        with first.websocket_connect(f"/v1/realtime?ticket={first_ticket}") as host_socket:
            assert host_socket.receive_json()["type"] == "hello"
            assert host_socket.receive_json()["type"] == "room.snapshot"
            with second.websocket_connect(f"/v1/realtime?ticket={second_ticket}") as friend_socket:
                assert friend_socket.receive_json()["type"] == "hello"
                assert friend_socket.receive_json()["type"] == "room.snapshot"
                friend_socket.send_json(
                    {
                        "v": 1,
                        "type": "chat.send",
                        "command_id": str(uuid4()),
                        "payload": {"body": "Across workers"},
                    }
                )
                for _ in range(5):
                    event = host_socket.receive_json()
                    if event["type"] == "room.snapshot" and event["payload"]["messages"]:
                        assert event["payload"]["messages"][-1]["body"] == "Across workers"
                        break
                else:
                    pytest.fail("Cross-instance chat was not delivered")
        recovered = second.get(f"/v1/rooms/{room_id}/snapshot", headers=headers).json()
        assert recovered["messages"][-1]["body"] == "Across workers"
        assert not any(member["connected"] for member in recovered["members"])
