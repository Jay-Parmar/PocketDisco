"""Private rooms and guest sessions."""

import sqlalchemy as sa
from alembic import op

revision = "0001_private_rooms"
down_revision = None
branch_labels = None
depends_on = None


def upgrade():
    op.create_table(
        "users",
        sa.Column("id", sa.String(36), primary_key=True),
        sa.Column("display_name", sa.String(40), nullable=False),
        sa.Column("created_at_ms", sa.BigInteger(), nullable=False),
    )
    op.create_table(
        "auth_sessions",
        sa.Column("id", sa.String(36), primary_key=True),
        sa.Column("user_id", sa.String(36), sa.ForeignKey("users.id"), nullable=False),
        sa.Column("expires_at_ms", sa.BigInteger(), nullable=False),
        sa.Column("revoked", sa.Boolean(), nullable=False),
    )
    op.create_index("ix_auth_sessions_user_id", "auth_sessions", ["user_id"])
    op.create_table(
        "refresh_tokens",
        sa.Column("token_hash", sa.String(64), primary_key=True),
        sa.Column("session_id", sa.String(36), sa.ForeignKey("auth_sessions.id"), nullable=False),
        sa.Column("used", sa.Boolean(), nullable=False),
    )
    op.create_index("ix_refresh_tokens_session_id", "refresh_tokens", ["session_id"])
    op.create_table(
        "access_tokens",
        sa.Column("token_hash", sa.String(64), primary_key=True),
        sa.Column("session_id", sa.String(36), sa.ForeignKey("auth_sessions.id"), nullable=False),
        sa.Column("expires_at_ms", sa.BigInteger(), nullable=False),
    )
    op.create_index("ix_access_tokens_session_id", "access_tokens", ["session_id"])
    op.create_index("ix_access_tokens_expires_at_ms", "access_tokens", ["expires_at_ms"])
    op.create_table(
        "rooms",
        sa.Column("id", sa.String(36), primary_key=True),
        sa.Column("name", sa.String(80), nullable=False),
        sa.Column("host_id", sa.String(36), sa.ForeignKey("users.id"), nullable=False),
        sa.Column("revision", sa.BigInteger(), nullable=False),
        sa.Column("presence_fingerprint", sa.String(64), nullable=False),
        sa.Column("closed", sa.Boolean(), nullable=False),
        sa.Column("created_at_ms", sa.BigInteger(), nullable=False),
    )
    op.create_table(
        "room_invites",
        sa.Column("token_hash", sa.String(64), primary_key=True),
        sa.Column("room_id", sa.String(36), sa.ForeignKey("rooms.id"), nullable=False),
        sa.Column("expires_at_ms", sa.BigInteger(), nullable=False),
    )
    op.create_index("ix_room_invites_room_id", "room_invites", ["room_id"])
    op.create_table(
        "room_members",
        sa.Column("room_id", sa.String(36), sa.ForeignKey("rooms.id"), primary_key=True),
        sa.Column("user_id", sa.String(36), sa.ForeignKey("users.id"), primary_key=True),
        sa.Column("joined_at_ms", sa.BigInteger(), nullable=False),
    )
    op.create_table(
        "chat_messages",
        sa.Column("id", sa.String(36), primary_key=True),
        sa.Column("room_id", sa.String(36), sa.ForeignKey("rooms.id"), nullable=False),
        sa.Column("user_id", sa.String(36), sa.ForeignKey("users.id"), nullable=False),
        sa.Column("command_id", sa.String(36), nullable=False),
        sa.Column("room_revision", sa.BigInteger(), nullable=False),
        sa.Column("body", sa.Text(), nullable=False),
        sa.Column("created_at_ms", sa.BigInteger(), nullable=False),
        sa.UniqueConstraint("room_id", "user_id", "command_id", name="uq_message_command"),
        sa.UniqueConstraint("room_id", "room_revision", name="uq_message_revision"),
    )
    op.create_index("ix_message_room_revision", "chat_messages", ["room_id", "room_revision"])


def downgrade():
    for table in (
        "chat_messages",
        "room_members",
        "room_invites",
        "rooms",
        "access_tokens",
        "refresh_tokens",
        "auth_sessions",
        "users",
    ):
        op.drop_table(table)
