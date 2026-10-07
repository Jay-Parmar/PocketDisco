import hashlib
import secrets
import time
from dataclasses import dataclass
from uuid import uuid4

from sqlalchemy import select

from .config import Settings
from .database import Database
from .errors import unauthorized
from .models import AccessToken, AuthSession, RefreshToken, User
from .schemas import SessionView, UserView


def now_ms():
    return time.time_ns() // 1_000_000


def token_hash(value: str):
    return hashlib.sha256(value.encode()).hexdigest()


@dataclass(frozen=True)
class Identity:
    user_id: str
    session_id: str
    display_name: str


class Auth:
    def __init__(self, database: Database, settings: Settings):
        self.database = database
        self.settings = settings

    def _issue(self, db, session: AuthSession, user: User):
        access = secrets.token_urlsafe(32)
        refresh = secrets.token_urlsafe(32)
        expires = min(now_ms() + self.settings.access_seconds * 1000, session.expires_at_ms)
        db.add(
            AccessToken(token_hash=token_hash(access), session_id=session.id, expires_at_ms=expires)
        )
        db.add(RefreshToken(token_hash=token_hash(refresh), session_id=session.id, used=False))
        return SessionView(
            access_token=access,
            refresh_token=refresh,
            expires_in=max(0, (expires - now_ms()) // 1000),
            user=UserView(id=user.id, display_name=user.display_name),
        )

    async def guest(self, display_name: str):
        async with self.database.transaction() as db:
            user = User(id=str(uuid4()), display_name=display_name, created_at_ms=now_ms())
            db.add(user)
            await db.flush()
            session = AuthSession(
                id=str(uuid4()),
                user_id=user.id,
                expires_at_ms=now_ms() + self.settings.refresh_seconds * 1000,
                revoked=False,
            )
            db.add(session)
            await db.flush()
            return self._issue(db, session, user)

    async def refresh(self, value: str):
        result = None
        async with self.database.transaction() as db:
            token = await db.get(RefreshToken, token_hash(value))
            if token is None:
                raise unauthorized()
            session = await db.scalar(
                select(AuthSession).where(AuthSession.id == token.session_id).with_for_update()
            )
            await db.refresh(token)
            if session.revoked or session.expires_at_ms <= now_ms():
                raise unauthorized()
            if token.used:
                session.revoked = True
            else:
                token.used = True
                user = await db.get(User, session.user_id)
                result = self._issue(db, session, user)
        if result is None:
            raise unauthorized()
        return result

    async def authenticate(self, value: str):
        if len(value) > 128:
            raise unauthorized()
        async with self.database.transaction() as db:
            record = (
                await db.execute(
                    select(User, AuthSession)
                    .join(AuthSession, AuthSession.user_id == User.id)
                    .join(AccessToken, AccessToken.session_id == AuthSession.id)
                    .where(
                        AccessToken.token_hash == token_hash(value),
                        AccessToken.expires_at_ms > now_ms(),
                        AuthSession.expires_at_ms > now_ms(),
                        AuthSession.revoked.is_(False),
                    )
                )
            ).first()
            if record is None:
                raise unauthorized()
            user, session = record
            return Identity(user.id, session.id, user.display_name)

    async def session_identity(self, session_id: str):
        async with self.database.transaction() as db:
            record = (
                await db.execute(
                    select(User, AuthSession)
                    .join(AuthSession, AuthSession.user_id == User.id)
                    .where(
                        AuthSession.id == session_id,
                        AuthSession.expires_at_ms > now_ms(),
                        AuthSession.revoked.is_(False),
                    )
                )
            ).first()
            if record is None:
                raise unauthorized()
            user, session = record
            return Identity(user.id, session.id, user.display_name)
