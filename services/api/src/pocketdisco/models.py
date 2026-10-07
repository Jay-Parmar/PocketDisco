from sqlalchemy import BigInteger, Boolean, ForeignKey, Index, String, Text, UniqueConstraint
from sqlalchemy.orm import DeclarativeBase, Mapped, mapped_column


class Base(DeclarativeBase):
    pass


class User(Base):
    __tablename__ = "users"

    id: Mapped[str] = mapped_column(String(36), primary_key=True)
    display_name: Mapped[str] = mapped_column(String(40))
    created_at_ms: Mapped[int] = mapped_column(BigInteger)


class AuthSession(Base):
    __tablename__ = "auth_sessions"

    id: Mapped[str] = mapped_column(String(36), primary_key=True)
    user_id: Mapped[str] = mapped_column(ForeignKey("users.id"), index=True)
    expires_at_ms: Mapped[int] = mapped_column(BigInteger)
    revoked: Mapped[bool] = mapped_column(Boolean, default=False)


class RefreshToken(Base):
    __tablename__ = "refresh_tokens"

    token_hash: Mapped[str] = mapped_column(String(64), primary_key=True)
    session_id: Mapped[str] = mapped_column(ForeignKey("auth_sessions.id"), index=True)
    used: Mapped[bool] = mapped_column(Boolean, default=False)


class AccessToken(Base):
    __tablename__ = "access_tokens"

    token_hash: Mapped[str] = mapped_column(String(64), primary_key=True)
    session_id: Mapped[str] = mapped_column(ForeignKey("auth_sessions.id"), index=True)
    expires_at_ms: Mapped[int] = mapped_column(BigInteger, index=True)


class Room(Base):
    __tablename__ = "rooms"

    id: Mapped[str] = mapped_column(String(36), primary_key=True)
    name: Mapped[str] = mapped_column(String(80))
    host_id: Mapped[str] = mapped_column(ForeignKey("users.id"))
    revision: Mapped[int] = mapped_column(BigInteger, default=1)
    presence_fingerprint: Mapped[str] = mapped_column(String(64), default="")
    closed: Mapped[bool] = mapped_column(Boolean, default=False)
    created_at_ms: Mapped[int] = mapped_column(BigInteger)


class Invite(Base):
    __tablename__ = "room_invites"

    token_hash: Mapped[str] = mapped_column(String(64), primary_key=True)
    room_id: Mapped[str] = mapped_column(ForeignKey("rooms.id"), index=True)
    expires_at_ms: Mapped[int] = mapped_column(BigInteger)


class Member(Base):
    __tablename__ = "room_members"

    room_id: Mapped[str] = mapped_column(ForeignKey("rooms.id"), primary_key=True)
    user_id: Mapped[str] = mapped_column(ForeignKey("users.id"), primary_key=True)
    joined_at_ms: Mapped[int] = mapped_column(BigInteger)


class Message(Base):
    __tablename__ = "chat_messages"
    __table_args__ = (
        UniqueConstraint("room_id", "user_id", "command_id", name="uq_message_command"),
        UniqueConstraint("room_id", "room_revision", name="uq_message_revision"),
        Index("ix_message_room_revision", "room_id", "room_revision"),
    )

    id: Mapped[str] = mapped_column(String(36), primary_key=True)
    room_id: Mapped[str] = mapped_column(ForeignKey("rooms.id"))
    user_id: Mapped[str] = mapped_column(ForeignKey("users.id"))
    command_id: Mapped[str] = mapped_column(String(36))
    room_revision: Mapped[int] = mapped_column(BigInteger)
    body: Mapped[str] = mapped_column(Text)
    created_at_ms: Mapped[int] = mapped_column(BigInteger)
