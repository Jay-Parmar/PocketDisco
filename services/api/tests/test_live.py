import asyncio
import time

import pytest

from pocketdisco.auth import token_hash
from pocketdisco.errors import ApiError
from pocketdisco.live import MemoryLive, Subscription


async def test_ticket_can_only_be_consumed_once(settings):
    live = MemoryLive(settings)
    ticket = await live.issue_ticket({"user": "test"})
    results = await asyncio.gather(live.consume_ticket(ticket), live.consume_ticket(ticket))
    assert results.count({"user": "test"}) == 1
    assert results.count(None) == 1
    assert ticket not in live.tickets


async def test_expired_ticket_is_rejected(settings):
    live = MemoryLive(settings)
    ticket = await live.issue_ticket({})
    live.tickets[token_hash(ticket)] = (time.monotonic() - 1, {})
    assert await live.consume_ticket(ticket) is None


async def test_rate_limit_expires(settings):
    live = MemoryLive(settings)
    await live.rate_limit("guest", 1, 60)
    with pytest.raises(ApiError) as failure:
        await live.rate_limit("guest", 1, 60)
    assert failure.value.status == 429
    live.rates["guest"] = (1, time.monotonic() - 1)
    await live.rate_limit("guest", 1, 60)


async def test_presence_tracks_multiple_connections(settings):
    live = MemoryLive(settings)
    await live.connect("room", "user", "a")
    await live.connect("room", "user", "b")
    await live.set_ready("room", "user", True)
    await live.disconnect("room", "user", "a")
    assert await live.presence("room") == ({"user"}, {"user"})
    await live.disconnect("room", "user", "b")
    assert await live.presence("room") == (set(), set())


async def test_presence_expires_and_clears_ready(settings):
    live = MemoryLive(settings)
    await live.connect("room", "user", "a")
    await live.set_ready("room", "user", True)
    live.connections["room"]["user:a"] = time.monotonic() - 1
    assert not await live.touch("room", "user", "a")
    assert await live.presence("room") == (set(), set())


async def test_connections_are_bounded(settings):
    live = MemoryLive(settings)
    for index in range(3):
        await live.connect("room", "user", str(index))
    with pytest.raises(ApiError):
        await live.connect("room", "user", "extra")


async def test_fanout_is_room_scoped(settings):
    live = MemoryLive(settings)
    async with live.subscribe("one") as first, live.subscribe("two") as second:
        await live.publish("one", {"revision": 1})
        assert await first.queue.get() == {"revision": 1}
        assert second.queue.empty()


def test_slow_consumer_fails_with_bounded_queue():
    subscription = Subscription()
    for revision in range(10):
        subscription.offer({"revision": revision})
    assert subscription.failed
    assert subscription.queue.qsize() == 1
    assert subscription.queue.get_nowait() is None
