import re
from typing import Annotated, Literal
from uuid import UUID

from pydantic import BaseModel, Field, StringConstraints, field_validator

DisplayName = Annotated[str, StringConstraints(strip_whitespace=True, min_length=1, max_length=40)]
RoomName = Annotated[str, StringConstraints(strip_whitespace=True, min_length=1, max_length=80)]
Token = Annotated[str, StringConstraints(min_length=32, max_length=128)]


class GuestRequest(BaseModel):
    display_name: DisplayName

    @field_validator("display_name")
    @classmethod
    def plain_name(cls, value):
        if re.search(r"[\x00-\x1f\x7f]", value):
            raise ValueError("Display name contains control characters")
        return value


class RefreshRequest(BaseModel):
    refresh_token: Token


class RoomRequest(BaseModel):
    name: RoomName

    @field_validator("name")
    @classmethod
    def plain_name(cls, value):
        if re.search(r"[\x00-\x1f\x7f]", value):
            raise ValueError("Room name contains control characters")
        return value


class TicketRequest(BaseModel):
    room_id: UUID


class UserView(BaseModel):
    id: str
    display_name: str


class SessionView(BaseModel):
    access_token: str
    refresh_token: str
    expires_in: int
    user: UserView


class MemberView(BaseModel):
    user_id: str
    display_name: str
    role: Literal["host", "listener"]
    ready: bool
    connected: bool


class MessageView(BaseModel):
    id: str
    user_id: str
    display_name: str
    body: str
    created_at_ms: int


class Snapshot(BaseModel):
    room_id: str
    name: str
    revision: int
    provider: Literal["generated_demo"] = "generated_demo"
    host_id: str
    members: list[MemberView]
    messages: list[MessageView]


class Command(BaseModel):
    v: Literal[1]
    type: Literal["member.ready", "chat.send", "sync.request", "ping"]
    command_id: UUID
    payload: dict = Field(default_factory=dict)


class ReadyPayload(BaseModel):
    ready: bool = Field(strict=True)


class ChatPayload(BaseModel):
    body: Annotated[str, StringConstraints(strip_whitespace=True, min_length=1, max_length=1000)]

    @field_validator("body")
    @classmethod
    def plain_body(cls, value):
        if re.search(r"[\x00-\x08\x0b\x0c\x0e-\x1f\x7f]", value):
            raise ValueError("Message contains control characters")
        return value


class PingPayload(BaseModel):
    client_time_ms: int = Field(ge=0, le=9007199254740991, strict=True)
