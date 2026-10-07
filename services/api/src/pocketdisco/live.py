import asyncio
import json
import secrets
import time
from collections import defaultdict
from contextlib import asynccontextmanager, suppress

from redis.asyncio import Redis
from redis.exceptions import RedisError

from .auth import token_hash
from .config import Settings
from .errors import ApiError


class Subscription:
    def __init__(self):
        self.queue = asyncio.Queue(maxsize=8)
        self.failed = False

    def offer(self, message):
        if self.failed:
            return
        if message is None or self.queue.full():
            self.failed = True
            while not self.queue.empty():
                self.queue.get_nowait()
            self.queue.put_nowait(None)
        else:
            self.queue.put_nowait(message)


class MemoryLive:
    def __init__(self, settings: Settings):
        if settings.mode != "local_test":
            raise ValueError("Memory state is only available in local_test mode")
        self.settings = settings
        self.tickets = {}
        self.rates = {}
        self.connections = defaultdict(dict)
        self.ready = defaultdict(set)
        self.subscribers = defaultdict(set)

    async def start(self):
        pass

    async def close(self):
        pass

    async def issue_ticket(self, value):
        ticket = secrets.token_urlsafe(32)
        self.tickets[token_hash(ticket)] = (time.monotonic() + self.settings.ticket_seconds, value)
        return ticket

    async def consume_ticket(self, ticket):
        record = self.tickets.pop(token_hash(ticket), None)
        if record and record[0] > time.monotonic():
            return record[1]
        return None

    async def rate_limit(self, key, limit, seconds):
        now = time.monotonic()
        count, expiry = self.rates.get(key, (0, now + seconds))
        if expiry <= now:
            count, expiry = 0, now + seconds
        self.rates[key] = (count + 1, expiry)
        if count >= limit:
            raise ApiError(429, "rate_limited", "Please wait before trying again.")

    async def presence(self, room_id):
        now = time.monotonic()
        records = self.connections[room_id]
        for key in list(records):
            if records[key] <= now:
                del records[key]
        connected = {key.split(":")[0] for key in records}
        self.ready[room_id].intersection_update(connected)
        return connected, self.ready[room_id].copy()

    async def connect(self, room_id, user_id, connection_id):
        await self.presence(room_id)
        records = self.connections[room_id]
        if sum(key.startswith(user_id + ":") for key in records) >= 3:
            raise ApiError(409, "connection_limit", "Close another connection and try again.")
        records[f"{user_id}:{connection_id}"] = time.monotonic() + self.settings.presence_seconds

    async def touch(self, room_id, user_id, connection_id):
        await self.presence(room_id)
        key = f"{user_id}:{connection_id}"
        if key not in self.connections[room_id]:
            return False
        self.connections[room_id][key] = time.monotonic() + self.settings.presence_seconds
        return True

    async def disconnect(self, room_id, user_id, connection_id):
        self.connections[room_id].pop(f"{user_id}:{connection_id}", None)
        await self.presence(room_id)

    async def remove_user(self, room_id, user_id):
        records = self.connections[room_id]
        for key in list(records):
            if key.startswith(user_id + ":"):
                del records[key]
        self.ready[room_id].discard(user_id)

    async def set_ready(self, room_id, user_id, ready):
        if ready:
            self.ready[room_id].add(user_id)
        else:
            self.ready[room_id].discard(user_id)

    async def publish(self, room_id, event):
        for subscriber in self.subscribers[room_id]:
            subscriber.offer(event)

    @asynccontextmanager
    async def subscribe(self, room_id):
        subscriber = Subscription()
        self.subscribers[room_id].add(subscriber)
        try:
            yield subscriber
        finally:
            self.subscribers[room_id].discard(subscriber)


class RedisLive:
    def __init__(self, settings: Settings):
        self.settings = settings
        self.redis = Redis.from_url(
            settings.redis_url, decode_responses=True, socket_connect_timeout=3, socket_timeout=5
        )

    def key(self, value):
        return f"{self.settings.redis_prefix}:{value}"

    async def start(self):
        await self.redis.ping()

    async def close(self):
        await self.redis.aclose()

    async def issue_ticket(self, value):
        ticket = secrets.token_urlsafe(32)
        await self.redis.set(
            self.key(f"ticket:{token_hash(ticket)}"),
            json.dumps(value),
            ex=self.settings.ticket_seconds,
            nx=True,
        )
        return ticket

    async def consume_ticket(self, ticket):
        value = await self.redis.getdel(self.key(f"ticket:{token_hash(ticket)}"))
        return json.loads(value) if value else None

    async def rate_limit(self, key, limit, seconds):
        count = await self.redis.eval(
            "local n = redis.call('INCR', KEYS[1]); "
            "if n == 1 then redis.call('EXPIRE', KEYS[1], ARGV[1]) end; return n",
            1,
            self.key(f"rate:{key}"),
            seconds,
        )
        if count > limit:
            raise ApiError(429, "rate_limited", "Please wait before trying again.")

    async def presence(self, room_id):
        connections = self.key(f"room:{room_id}:connections")
        ready_key = self.key(f"room:{room_id}:ready")
        async with self.redis.pipeline(transaction=True) as pipe:
            pipe.zremrangebyscore(connections, "-inf", time.time())
            pipe.zrange(connections, 0, -1)
            pipe.smembers(ready_key)
            _, records, ready = await pipe.execute()
        connected = {key.split(":")[0] for key in records}
        stale = ready - connected
        if stale:
            await self.redis.srem(ready_key, *stale)
        return connected, ready & connected

    async def connect(self, room_id, user_id, connection_id):
        await self.presence(room_id)
        key = self.key(f"room:{room_id}:connections")
        records = await self.redis.zrange(key, 0, -1)
        if sum(record.startswith(user_id + ":") for record in records) >= 3:
            raise ApiError(409, "connection_limit", "Close another connection and try again.")
        async with self.redis.pipeline(transaction=True) as pipe:
            pipe.zadd(
                key, {f"{user_id}:{connection_id}": time.time() + self.settings.presence_seconds}
            )
            pipe.expire(key, self.settings.presence_seconds * 2)
            await pipe.execute()

    async def touch(self, room_id, user_id, connection_id):
        key = self.key(f"room:{room_id}:connections")
        member = f"{user_id}:{connection_id}"
        result = await self.redis.eval(
            "local score = redis.call('ZSCORE', KEYS[1], ARGV[1]); "
            "if not score or tonumber(score) <= tonumber(ARGV[2]) then return 0 end; "
            "redis.call('ZADD', KEYS[1], ARGV[3], ARGV[1]); "
            "redis.call('EXPIRE', KEYS[1], ARGV[4]); return 1",
            1,
            key,
            member,
            time.time(),
            time.time() + self.settings.presence_seconds,
            self.settings.presence_seconds * 2,
        )
        await self.redis.expire(
            self.key(f"room:{room_id}:ready"), self.settings.presence_seconds * 2
        )
        return bool(result)

    async def disconnect(self, room_id, user_id, connection_id):
        await self.redis.zrem(self.key(f"room:{room_id}:connections"), f"{user_id}:{connection_id}")
        await self.presence(room_id)

    async def remove_user(self, room_id, user_id):
        key = self.key(f"room:{room_id}:connections")
        records = await self.redis.zrange(key, 0, -1)
        removed = [record for record in records if record.startswith(user_id + ":")]
        if removed:
            await self.redis.zrem(key, *removed)
        await self.redis.srem(self.key(f"room:{room_id}:ready"), user_id)

    async def set_ready(self, room_id, user_id, ready):
        key = self.key(f"room:{room_id}:ready")
        if ready:
            await self.redis.sadd(key, user_id)
            await self.redis.expire(key, self.settings.presence_seconds * 2)
        else:
            await self.redis.srem(key, user_id)

    async def publish(self, room_id, event):
        await self.redis.publish(self.key(f"room:{room_id}:events"), json.dumps(event))

    @asynccontextmanager
    async def subscribe(self, room_id):
        subscription = Subscription()
        async with self.redis.pubsub() as pubsub:
            await pubsub.subscribe(self.key(f"room:{room_id}:events"))
            confirmation = await pubsub.get_message(ignore_subscribe_messages=False, timeout=5)
            if confirmation is None or confirmation["type"] != "subscribe":
                raise RedisError("Room subscription did not become ready")

            async def receive():
                try:
                    while True:
                        message = await pubsub.get_message(
                            ignore_subscribe_messages=True, timeout=1
                        )
                        if message and message["type"] == "message":
                            subscription.offer(json.loads(message["data"]))
                except (RedisError, ValueError):
                    subscription.offer(None)

            task = asyncio.create_task(receive())
            try:
                yield subscription
            finally:
                task.cancel()
                with suppress(asyncio.CancelledError):
                    await task


def create_live(settings: Settings):
    return MemoryLive(settings) if settings.mode == "local_test" else RedisLive(settings)
