# Mobile alpha

## Scope update: 2026-10-07

The owner has authorized product development and asked to pause desktop work.
Build the Android private-room slice now while the outstanding Phase 0 acoustic
and media-rights gates remain release blockers. This does not mark Phase 0 as
passed. The existing experiment PRs remain separate and are not merged here.

First deliverable: an installable React Native app with a polished welcome,
create/join flow, live member list, readiness, text chat, leave, and snapshot
recovery. FastAPI uses PostgreSQL for durable state and Redis for tickets,
presence, rate limits, and fan-out. A local test mode must be explicit and must
not be confused with a deployable service.

Next deliverable: Kotlin playback behind a provider-neutral contract, generated
demo audio and the separately reviewed CC0 test catalog, native clock estimation,
scheduled starts, pause, seek, late join, and reconnect. Test tracks are not an
approved launch catalog. No YouTube extraction,
Spotify integration, public rooms, uploads, or desktop features are included.

## Local playback increment: 2026-10-08

The Android player now has a provider-neutral timed adapter and a local preview
card for one bundled generated AAC clip. Native preparation, scheduled start,
pause, seek, state reporting and cleanup are implemented. No room command or
ready toggle starts audio in this increment. Each listener explicitly previews
on their own phone, with an unsynchronized label in the UI.

Native deadlines use elapsed-realtime milliseconds, not server Unix time.
Clock estimation and room prepare/ready/commit are not connected yet. Background
and focus interruptions cancel queued starts; returning does not auto-resume.
Local playback-state checks now pass on the phone and emulator; see the
[device checkpoint](10-mobile-checkpoint.md). Measured acoustic behavior,
shared starts and real focus/headset interruption still need verification.

## Private-room wire contract

IDs are UUID strings. Times are Unix milliseconds. JSON uses snake_case.

- `POST /v1/auth/guest`: `{display_name}` -> `{access_token, refresh_token,
  expires_in, user: {id, display_name}}`.
- `POST /v1/auth/refresh`: `{refresh_token}` -> the same session response.
- `POST /v1/rooms`: `{name}` -> `{snapshot, invite_code}`. Default provider is
  `generated_demo` until an approved media catalog is configured.
- `POST /v1/rooms/{invite_code}/join`: `{}` -> `{snapshot}`.
- `GET /v1/rooms/{room_id}/snapshot`: returns the snapshot directly.
- `POST /v1/rooms/{room_id}/leave`: `{}` -> `{ok: true}`.
- `POST /v1/realtime/tickets`: `{room_id}` -> `{ticket, expires_in}`.
- `GET /v1/realtime?ticket=...`: a one-use WebSocket ticket, never an access token.

All room REST requests use `Authorization: Bearer <access_token>`.

Snapshot fields:

```ts
type RoomSnapshot = {
  room_id: string;
  name: string;
  revision: number;
  provider: 'generated_demo';
  host_id: string;
  members: {user_id: string; display_name: string; role: 'host' | 'listener';
    ready: boolean; connected: boolean}[];
  messages: {id: string; user_id: string; display_name: string; body: string;
    created_at_ms: number}[];
};
```

The server emits `hello`, then `room.snapshot`. Every snapshot event is
`{v: 1, type: 'room.snapshot', room_id, revision, server_time_ms,
payload: RoomSnapshot}`. The first slice broadcasts complete snapshots after
changes; clients ignore stale snapshots and request recovery after reconnect.
Messages in a snapshot are bounded to the latest 50. Room capacity is 25.

Client socket messages are `{v: 1, type, command_id, payload}`:

- `member.ready`: `{ready: boolean}`.
- `chat.send`: `{body: string}`, 1 to 1000 characters after trimming.
- `sync.request`: `{}`.
- `ping`: `{client_time_ms: number}`, echoed by `pong` with `server_time_ms`.

Chat command IDs are idempotent per room and sender. Errors are
`{v: 1, type: 'error', payload: {code, message, command_id?}}`. Successful
chat/readiness mutations receive `{v: 1, type: 'command.ack',
payload: {command_id}}` after commit, including duplicate requests. Chat drafts
clear only after this acknowledgement; clients do not replay uncertain sends.
REST errors use
`{detail: {code, message}}`. Leaving removes membership; if the host leaves,
the earliest remaining member becomes host. An empty room closes.

## Release gates

- Two physical Android phones and measured acoustic starts, not emulator timing.
- Authorized launch media and provider decisions.
- Deployed HTTPS/WSS API with PostgreSQL/Redis integration and recovery tests.
- Refresh rotation, secret storage, deletion, moderation, rate limits, and audit.
- Privacy policy, Data Safety, support contact, retention, and account deletion.
- Signed release bundle, package ownership, current Play requirements, and
  internal/closed testing on the intended device matrix.

Only verified checks are recorded as passed. The owner reviews and merges PRs.
