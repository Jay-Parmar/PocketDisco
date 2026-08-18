# API, realtime protocol, and data model

## REST surface (initial)

```text
POST   /v1/auth/guest                    create upgradeable guest session
POST   /v1/auth/refresh                  rotate refresh token
POST   /v1/realtime/tickets              mint one-use, ~60-second WebSocket ticket

POST   /v1/rooms                         create room
POST   /v1/rooms/{invite}/join           join after rate-limited invite validation
GET    /v1/rooms/{room_id}/snapshot      authoritative recovery state
PATCH  /v1/rooms/{room_id}               settings/privacy (host only)
POST   /v1/rooms/{room_id}/leave
POST   /v1/rooms/{room_id}/host-transfer

GET    /v1/rooms/{room_id}/queue
POST   /v1/rooms/{room_id}/queue/items
PATCH  /v1/rooms/{room_id}/queue/order
DELETE /v1/rooms/{room_id}/queue/items/{item_id}

POST   /v1/providers/resolve              validate a supported URL; no media download
GET    /v1/providers/{provider}/status    client/app policy and availability hints

GET    /v1/rooms/{room_id}/messages
POST   /v1/rooms/{room_id}/reports
```

Playback commands use the WebSocket while connected. A REST fallback may accept the same command envelope, but it must enter the same coordinator and revision check.

## WebSocket lifecycle

1. Client obtains a short-lived one-use ticket over authenticated REST.
2. Connect to `wss://.../v1/realtime?ticket=...`.
3. Server sends `hello` with connection ID, protocol version, heartbeat, and server time.
4. Client subscribes to one active room and performs clock sync.
5. Server sends an authoritative snapshot before incremental events.
6. Client acknowledges applied `revision`; any gap triggers `sync.request`.
7. Heartbeats maintain presence but are not persisted as chat/audit history.

Do not put long-lived access/refresh/provider tokens in WebSocket URLs or logs.

## Event envelope

```json
{
  "v": 1,
  "type": "playback.commit",
  "event_id": "01J...",
  "room_id": "uuid",
  "revision": 43,
  "server_time_ms": 1786898998000,
  "effective_at_ms": 1786899000000,
  "causation_id": "client-command-uuid",
  "payload": {
    "status": "playing",
    "position_ms": 18000,
    "item_id": "queue-item-uuid"
  }
}
```

Command envelope:

```json
{
  "v": 1,
  "type": "playback.command",
  "command_id": "uuid-created-on-client",
  "room_id": "uuid",
  "expected_revision": 42,
  "payload": {"action": "play"}
}
```

Rules:

- `command_id` is idempotent within a room.
- Only authorized roles may mutate playback/queue.
- The server assigns event/revision/effective time.
- Unknown additive payload fields are ignored; unknown major protocol versions are rejected.
- A stale revision returns `conflict` plus the latest snapshot.
- Client UI state changes optimistically only where rollback is harmless. Playback waits for the authoritative commit.

## Important event families

```text
session:     hello, ping, pong, error
room:        snapshot, member.joined, member.left, role.changed
presence:    member.ready, member.degraded, member.typing
playback:    prepare, ready, commit, state.report, correction, unavailable
queue:       item.added, item.removed, reordered, replaced
chat:        message.created, reaction.created, message.moderated
recovery:    sync.request, sync.snapshot
```

## PostgreSQL model

| Table | Core fields / notes |
|---|---|
| `users` | id, display_name, status, created_at; guest accounts can later upgrade |
| `devices` | id, user_id, platform, app_version, push token encrypted/reference, last_seen_at |
| `auth_sessions` | refresh-token hash/family, device_id, expiry, revoked_at; rotate and detect reuse |
| `rooms` | id, owner_id, name, privacy, provider, status, durable settings, created/ended timestamps |
| `room_members` | room_id, user_id, role, joined_at, left_at, ban status; live connection presence stays in Redis |
| `room_invites` | hashed token, room_id, creator, expiry, max_uses, use_count, revoked_at |
| `queue_items` | id, room_id, provider, provider_item_id, canonical metadata, position, added_by, revision |
| `room_events` | selected durable domain events, room revision, actor, command ID, payload JSONB, timestamp |
| `chat_messages` | id, room_id, user_id, body, reply_to, moderation state, timestamps |
| `blocks_reports` | reporter, target user/message/room, reason, state, timestamps |
| `provider_accounts` | user/provider identity and scopes only; encrypted token reference if server storage is unavoidable |

Use UUIDv7/ULID-like sortable IDs or ordinary UUIDs consistently; avoid exposing sequential numeric IDs. Add uniqueness on `(room_id, command_id)` and `(room_id, revision)`.

## Redis model

```text
room:{id}:snapshot            HASH/JSON, TTL after room ends
room:{id}:members             HASH of live member readiness/capabilities, short TTL
room:{id}:commands            bounded STREAM for short recovery/debugging
room:{id}:events              PUB/SUB channel for connected gateways
invite-rate:{ip-or-device}    expiring counter
ws-ticket:{token-hash}        one-use value, ~60-second TTL
```

An atomic Lua/function operation should validate expected revision, deduplicate command ID, write the new snapshot, and append the command before publication. Never rely on Pub/Sub as the only copy of canonical state.

## Provider identifiers and metadata

Store provider IDs/URIs, not playable URLs copied from network traffic. Retain only metadata allowed by the provider and refresh/delete it according to its policy. Every queue item includes provider, territory/playability information when available, duration, explicit flag, and original attribution/link.

Do not store third-party OAuth tokens in plaintext. Prefer on-device PKCE and Android Keystore when the backend does not need the token. If server-side refresh is required, use envelope encryption with a managed key service, least privilege, rotation, redaction, and revocation.

