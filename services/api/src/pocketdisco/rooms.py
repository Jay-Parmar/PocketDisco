import secrets
from uuid import uuid4

from sqlalchemy import func, select

from .auth import Identity, now_ms, token_hash
from .config import Settings
from .database import Database
from .errors import ApiError
from .models import Invite, Member, Message, Room, User
from .schemas import MemberView, MessageView, Snapshot


def snapshot_event(snapshot: Snapshot):
    return {
        "v": 1,
        "type": "room.snapshot",
        "room_id": snapshot.room_id,
        "revision": snapshot.revision,
        "server_time_ms": now_ms(),
        "payload": snapshot.model_dump(),
    }


class Rooms:
    def __init__(self, database: Database, live, settings: Settings):
        self.database = database
        self.live = live
        self.settings = settings

    async def _room(self, db, room_id, allow_closed=False):
        room = await db.scalar(select(Room).where(Room.id == room_id).with_for_update())
        if room is None or (room.closed and not allow_closed):
            raise ApiError(404, "room_unavailable", "This room is no longer available.")
        return room

    async def _member(self, db, room_id, user_id):
        member = await db.get(Member, (room_id, user_id))
        if member is None:
            raise ApiError(403, "not_a_member", "Join the room before using it.")
        return member

    async def _snapshot(self, db, room: Room):
        await db.flush()
        connected, ready = await self.live.presence(room.id)
        members = (
            await db.execute(
                select(Member, User)
                .join(User, User.id == Member.user_id)
                .where(Member.room_id == room.id)
                .order_by(Member.joined_at_ms, Member.user_id)
            )
        ).all()
        member_ids = {user.id for member, user in members}
        connected &= member_ids
        ready &= member_ids
        fingerprint = token_hash(",".join(sorted(connected)) + "/" + ",".join(sorted(ready)))
        if room.presence_fingerprint and fingerprint != room.presence_fingerprint:
            room.revision += 1
        room.presence_fingerprint = fingerprint
        messages = (
            await db.execute(
                select(Message, User)
                .join(User, User.id == Message.user_id)
                .where(Message.room_id == room.id)
                .order_by(Message.room_revision.desc())
                .limit(50)
            )
        ).all()
        return Snapshot(
            room_id=room.id,
            name=room.name,
            revision=room.revision,
            host_id=room.host_id,
            members=[
                MemberView(
                    user_id=user.id,
                    display_name=user.display_name,
                    role="host" if user.id == room.host_id else "listener",
                    ready=user.id in ready,
                    connected=user.id in connected,
                )
                for member, user in members
            ],
            messages=[
                MessageView(
                    id=message.id,
                    user_id=user.id,
                    display_name=user.display_name,
                    body=message.body,
                    created_at_ms=message.created_at_ms,
                )
                for message, user in reversed(messages)
            ],
        )

    async def publish(self, snapshot):
        await self.live.publish(snapshot.room_id, snapshot_event(snapshot))

    async def create(self, identity: Identity, name: str):
        invite_code = "".join(secrets.choice("0123456789ABCDEFGHJKMNPQRSTVWXYZ") for _ in range(12))
        async with self.database.transaction() as db:
            room = Room(
                id=str(uuid4()),
                name=name,
                host_id=identity.user_id,
                revision=1,
                closed=False,
                created_at_ms=now_ms(),
            )
            db.add(room)
            await db.flush()
            db.add(Member(room_id=room.id, user_id=identity.user_id, joined_at_ms=now_ms()))
            db.add(
                Invite(
                    token_hash=token_hash(invite_code),
                    room_id=room.id,
                    expires_at_ms=now_ms() + self.settings.invite_seconds * 1000,
                )
            )
            snapshot = await self._snapshot(db, room)
        return snapshot, invite_code

    async def join(self, identity: Identity, invite_code: str):
        async with self.database.transaction() as db:
            invite = await db.get(Invite, token_hash(invite_code.strip().upper()))
            if invite is None or invite.expires_at_ms <= now_ms():
                raise ApiError(404, "invite_unavailable", "Check the invite code and try again.")
            room = await self._room(db, invite.room_id)
            member = await db.get(Member, (room.id, identity.user_id))
            if member is None:
                count = await db.scalar(
                    select(func.count()).select_from(Member).where(Member.room_id == room.id)
                )
                if count >= 25:
                    raise ApiError(409, "room_full", "This room already has 25 people.")
                db.add(Member(room_id=room.id, user_id=identity.user_id, joined_at_ms=now_ms()))
                room.revision += 1
            snapshot = await self._snapshot(db, room)
        await self.publish(snapshot)
        return snapshot

    async def snapshot(self, identity: Identity, room_id: str):
        async with self.database.transaction() as db:
            room = await self._room(db, room_id)
            await self._member(db, room_id, identity.user_id)
            return await self._snapshot(db, room)

    async def authorize(self, identity: Identity, room_id: str):
        async with self.database.transaction() as db:
            await self._room(db, room_id)
            await self._member(db, room_id, identity.user_id)

    async def leave(self, identity: Identity, room_id: str):
        async with self.database.transaction() as db:
            room = await self._room(db, room_id)
            member = await self._member(db, room_id, identity.user_id)
            await db.delete(member)
            await db.flush()
            remaining = await db.scalar(
                select(Member)
                .where(Member.room_id == room_id)
                .order_by(Member.joined_at_ms, Member.user_id)
                .limit(1)
            )
            if remaining is None:
                room.closed = True
            elif room.host_id == identity.user_id:
                room.host_id = remaining.user_id
            room.revision += 1
            await self.live.remove_user(room_id, identity.user_id)
            snapshot = await self._snapshot(db, room)
        await self.publish(snapshot)

    async def connect(self, identity: Identity, room_id: str, connection_id: str):
        async with self.database.transaction() as db:
            room = await self._room(db, room_id)
            await self._member(db, room_id, identity.user_id)
            await self.live.connect(room_id, identity.user_id, connection_id)
            snapshot = await self._snapshot(db, room)
        await self.publish(snapshot)
        return snapshot

    async def disconnect(self, identity: Identity, room_id: str, connection_id: str):
        async with self.database.transaction() as db:
            room = await self._room(db, room_id, allow_closed=True)
            await self.live.disconnect(room_id, identity.user_id, connection_id)
            if room.closed:
                return
            snapshot = await self._snapshot(db, room)
        await self.publish(snapshot)

    async def set_ready(self, identity: Identity, room_id: str, ready: bool):
        async with self.database.transaction() as db:
            room = await self._room(db, room_id)
            await self._member(db, room_id, identity.user_id)
            connected, current = await self.live.presence(room_id)
            if identity.user_id not in connected:
                raise ApiError(409, "not_connected", "Reconnect before changing readiness.")
            if (identity.user_id in current) != ready:
                await self.live.set_ready(room_id, identity.user_id, ready)
            snapshot = await self._snapshot(db, room)
        await self.publish(snapshot)
        return snapshot

    async def chat(self, identity: Identity, room_id: str, command_id: str, body: str):
        async with self.database.transaction() as db:
            room = await self._room(db, room_id)
            await self._member(db, room_id, identity.user_id)
            existing = await db.scalar(
                select(Message).where(
                    Message.room_id == room_id,
                    Message.user_id == identity.user_id,
                    Message.command_id == command_id,
                )
            )
            if existing is None:
                await self.live.rate_limit(f"chat:{room_id}:{identity.user_id}", 20, 10)
                room.revision += 1
                db.add(
                    Message(
                        id=str(uuid4()),
                        room_id=room_id,
                        user_id=identity.user_id,
                        command_id=command_id,
                        room_revision=room.revision,
                        body=body,
                        created_at_ms=now_ms(),
                    )
                )
            snapshot = await self._snapshot(db, room)
        await self.publish(snapshot)
        return snapshot
