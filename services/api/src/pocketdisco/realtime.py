import asyncio
import logging
from contextlib import suppress
from uuid import uuid4

from fastapi import WebSocket, WebSocketDisconnect
from pydantic import ValidationError
from redis.exceptions import RedisError
from sqlalchemy.exc import SQLAlchemyError

from .auth import now_ms
from .errors import ApiError
from .rooms import snapshot_event
from .schemas import ChatPayload, Command, PingPayload, ReadyPayload

logger = logging.getLogger(__name__)


class Connection:
    def __init__(self, websocket, identity, room_id, auth, rooms, live):
        self.websocket = websocket
        self.identity = identity
        self.room_id = room_id
        self.auth = auth
        self.rooms = rooms
        self.live = live
        self.id = str(uuid4())
        self.last_revision = -1
        self.send_lock = asyncio.Lock()
        self.closed = False

    async def send(self, event):
        async with self.send_lock:
            if not self.closed:
                await asyncio.wait_for(self.websocket.send_json(event), timeout=10)

    async def close(self, code):
        async with self.send_lock:
            if not self.closed:
                self.closed = True
                with suppress(WebSocketDisconnect):
                    await self.websocket.close(code)

    async def check_access(self):
        await self.auth.session_identity(self.identity.session_id)
        await self.rooms.authorize(self.identity, self.room_id)

    async def send_snapshot(self, event, force=False):
        if force or event["revision"] > self.last_revision:
            self.last_revision = max(self.last_revision, event["revision"])
            await self.send(event)

    async def forward(self, subscription):
        while not self.closed:
            try:
                event = await asyncio.wait_for(subscription.queue.get(), timeout=15)
            except asyncio.TimeoutError:
                await self.check_access()
                event = snapshot_event(await self.rooms.snapshot(self.identity, self.room_id))
            if event is None:
                await self.close(1013)
                return
            await self.check_access()
            await self.send_snapshot(event)

    async def receive(self):
        invalid_count = 0
        while not self.closed:
            command = None
            try:
                message = await asyncio.wait_for(self.websocket.receive(), timeout=45)
            except asyncio.TimeoutError:
                await self.close(1001)
                return
            if message["type"] == "websocket.disconnect":
                raise WebSocketDisconnect(message.get("code", 1000))
            raw = message.get("text")
            if raw is None:
                await self.close(1003)
                return
            if len(raw.encode()) > 8192:
                await self.close(1009)
                return
            try:
                command = Command.model_validate_json(raw)
                await self.live.rate_limit(f"ws:{self.id}", 60, 10)
                await self.check_access()
                if not await self.live.touch(self.room_id, self.identity.user_id, self.id):
                    await self.close(1001)
                    return
                await self.command(command)
            except ValidationError:
                invalid_count += 1
                payload = {"code": "invalid_command", "message": "Check the command fields."}
                if command:
                    payload["command_id"] = str(command.command_id)
                await self.send({"v": 1, "type": "error", "payload": payload})
                if invalid_count >= 3:
                    await self.close(1008)
                    return
            except ApiError as error:
                payload = error.detail()
                if command:
                    payload["command_id"] = str(command.command_id)
                await self.send({"v": 1, "type": "error", "payload": payload})
                if error.status in (401, 403, 404):
                    await self.close(4401 if error.status == 401 else 4403)
                    return

    async def command(self, command):
        if command.type == "ping":
            payload = PingPayload.model_validate(command.payload)
            await self.send(
                {
                    "v": 1,
                    "type": "pong",
                    "server_time_ms": now_ms(),
                    "payload": {"client_time_ms": payload.client_time_ms},
                }
            )
        elif command.type == "sync.request":
            snapshot = await self.rooms.snapshot(self.identity, self.room_id)
            await self.send_snapshot(snapshot_event(snapshot), force=True)
        elif command.type == "member.ready":
            payload = ReadyPayload.model_validate(command.payload)
            await self.rooms.set_ready(self.identity, self.room_id, payload.ready)
            await self.ack(command)
        elif command.type == "chat.send":
            payload = ChatPayload.model_validate(command.payload)
            await self.rooms.chat(
                self.identity, self.room_id, str(command.command_id), payload.body
            )
            await self.ack(command)

    async def ack(self, command):
        await self.send(
            {"v": 1, "type": "command.ack", "payload": {"command_id": str(command.command_id)}}
        )


def register_realtime(app, auth, rooms, live):
    @app.websocket("/v1/realtime")
    async def realtime(websocket: WebSocket):
        connection = None
        tasks = []
        connected = False
        try:
            ticket = websocket.query_params.get("ticket", "")
            if not 32 <= len(ticket) <= 128:
                await websocket.close(4401)
                return
            record = await live.consume_ticket(ticket)
            if record is None:
                await websocket.close(4401)
                return
            identity = await auth.session_identity(record["session_id"])
            room_id = record["room_id"]
            await rooms.authorize(identity, room_id)
            connection = Connection(websocket, identity, room_id, auth, rooms, live)
            async with live.subscribe(room_id) as subscription:
                await websocket.accept()
                await connection.send(
                    {
                        "v": 1,
                        "type": "hello",
                        "server_time_ms": now_ms(),
                        "payload": {"heartbeat_interval_ms": 15000},
                    }
                )
                snapshot = await rooms.connect(identity, room_id, connection.id)
                connected = True
                await connection.send_snapshot(snapshot_event(snapshot))
                tasks = [
                    asyncio.create_task(connection.receive()),
                    asyncio.create_task(connection.forward(subscription)),
                ]
                done, pending = await asyncio.wait(tasks, return_when=asyncio.FIRST_COMPLETED)
                for task in done:
                    task.result()
        except WebSocketDisconnect:
            if connection:
                connection.closed = True
        except ApiError as error:
            code = 4401 if error.status == 401 else 4403
            if error.status == 429:
                code = 1008
            if connection:
                await connection.send({"v": 1, "type": "error", "payload": error.detail()})
                await connection.close(code)
            else:
                await websocket.close(code)
        except (RedisError, SQLAlchemyError, asyncio.TimeoutError):
            if connection:
                await connection.close(1013)
            else:
                await websocket.close(1013)
        finally:
            for task in tasks:
                task.cancel()
            await asyncio.gather(*tasks, return_exceptions=True)
            if connected:
                try:
                    await rooms.disconnect(connection.identity, connection.room_id, connection.id)
                except (ApiError, RedisError, SQLAlchemyError):
                    logger.warning("Presence cleanup deferred until expiry")
