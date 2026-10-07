import asyncio
from uuid import uuid4

import pytest
from sqlalchemy import select

from pocketdisco.auth import Auth
from pocketdisco.errors import ApiError
from pocketdisco.live import MemoryLive
from pocketdisco.models import Invite, Message
from pocketdisco.rooms import Rooms


@pytest.fixture
async def room_setup(database, settings):
    auth = Auth(database, settings)
    host = await auth.guest("Mira")
    friend = await auth.guest("Sam")
    identities = [await auth.authenticate(item.access_token) for item in (host, friend)]
    live = MemoryLive(settings)
    rooms = Rooms(database, live, settings)
    snapshot, invite = await rooms.create(identities[0], "Friday night")
    return rooms, live, identities[0], identities[1], snapshot, invite


async def test_create_private_room(room_setup):
    rooms, live, host, friend, snapshot, invite = room_setup
    assert snapshot.name == "Friday night"
    assert snapshot.provider == "generated_demo"
    assert snapshot.host_id == host.user_id
    assert snapshot.revision == 1
    assert len(invite) == 12
    assert snapshot.members[0].role == "host"
    assert not snapshot.members[0].connected


async def test_room_requires_membership(room_setup):
    rooms, live, host, friend, snapshot, invite = room_setup
    for action in (
        rooms.snapshot(friend, snapshot.room_id),
        rooms.leave(friend, snapshot.room_id),
        rooms.set_ready(friend, snapshot.room_id, True),
        rooms.chat(friend, snapshot.room_id, str(uuid4()), "No access"),
    ):
        with pytest.raises(ApiError) as error:
            await action
        assert error.value.code == "not_a_member"


async def test_join_is_idempotent_and_case_insensitive(room_setup):
    rooms, live, host, friend, snapshot, invite = room_setup
    joined = await rooms.join(friend, " " + invite.lower() + " ")
    again = await rooms.join(friend, invite)
    assert joined.revision == 2 == again.revision
    assert len(again.members) == 2


async def test_expired_invite_cannot_join(room_setup, database):
    rooms, live, host, friend, snapshot, invite = room_setup
    async with database.transaction() as db:
        record = await db.scalar(select(Invite))
        record.expires_at_ms = 0
    with pytest.raises(ApiError) as error:
        await rooms.join(friend, invite)
    assert error.value.code == "invite_unavailable"


async def test_host_leave_transfers_to_remaining_member(room_setup):
    rooms, live, host, friend, snapshot, invite = room_setup
    await rooms.join(friend, invite)
    await rooms.leave(host, snapshot.room_id)
    result = await rooms.snapshot(friend, snapshot.room_id)
    assert result.host_id == friend.user_id
    assert result.members[0].role == "host"
    assert result.revision == 3


async def test_last_member_leave_closes_room(room_setup):
    rooms, live, host, friend, snapshot, invite = room_setup
    await rooms.leave(host, snapshot.room_id)
    with pytest.raises(ApiError) as error:
        await rooms.join(friend, invite)
    assert error.value.code == "room_unavailable"


async def test_readiness_requires_connection_and_resets_on_disconnect(room_setup):
    rooms, live, host, friend, snapshot, invite = room_setup
    with pytest.raises(ApiError) as error:
        await rooms.set_ready(host, snapshot.room_id, True)
    assert error.value.code == "not_connected"
    await rooms.connect(host, snapshot.room_id, "one")
    ready = await rooms.set_ready(host, snapshot.room_id, True)
    assert ready.members[0].ready
    repeated = await rooms.set_ready(host, snapshot.room_id, True)
    assert repeated.revision == ready.revision
    await rooms.disconnect(host, snapshot.room_id, "one")
    result = await rooms.snapshot(host, snapshot.room_id)
    assert not result.members[0].ready
    assert not result.members[0].connected


async def test_duplicate_chat_is_inserted_once(room_setup, database):
    rooms, live, host, friend, snapshot, invite = room_setup
    command_id = str(uuid4())
    results = await asyncio.gather(
        rooms.chat(host, snapshot.room_id, command_id, "Hello"),
        rooms.chat(host, snapshot.room_id, command_id, "Different body"),
    )
    assert results[0].revision == results[1].revision == 2
    async with database.sessions() as db:
        assert len((await db.scalars(select(Message))).all()) == 1


async def test_chat_dedup_is_per_sender(room_setup):
    rooms, live, host, friend, snapshot, invite = room_setup
    await rooms.join(friend, invite)
    command_id = str(uuid4())
    await rooms.chat(host, snapshot.room_id, command_id, "Hello")
    result = await rooms.chat(friend, snapshot.room_id, command_id, "Hi")
    assert len(result.messages) == 2


async def test_snapshot_bounds_chat_to_latest_fifty(room_setup):
    rooms, live, host, friend, snapshot, invite = room_setup
    for index in range(55):
        live.rates.clear()
        result = await rooms.chat(host, snapshot.room_id, str(uuid4()), f"Message {index}")
    assert len(result.messages) == 50
    assert result.messages[-1].body == "Message 54"
    assert "Message 0" not in {message.body for message in result.messages}


async def test_room_capacity_is_atomic(room_setup, database, settings):
    rooms, live, host, friend, snapshot, invite = room_setup
    auth = Auth(database, settings)
    for index in range(23):
        guest = await auth.guest(str(index))
        await rooms.join(await auth.authenticate(guest.access_token), invite)
    another = await auth.guest("Another")
    another_identity = await auth.authenticate(another.access_token)
    results = await asyncio.gather(
        rooms.join(friend, invite), rooms.join(another_identity, invite), return_exceptions=True
    )
    assert sum(isinstance(result, ApiError) for result in results) == 1
    assert len((await rooms.snapshot(host, snapshot.room_id)).members) == 25
