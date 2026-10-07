# Private-room API

FastAPI control plane for the Android alpha. It carries room state and text,
never music bytes. The wire contract is in `docs/08-mobile-alpha.md`.

## Local phone testing

The isolated test mode is opt-in. It uses SQLite and in-memory tickets,
presence, and fan-out. Run exactly one worker. Restarting loses live state and
invalidates socket tickets. This mode is not a deployment option.

From the repository root in PowerShell:

```powershell
python -m venv .local-tools/api-venv
$env:PIP_CACHE_DIR = "$PWD/.local-tools/pip-cache"
.local-tools/api-venv/Scripts/python -m pip install -e './services/api[test]'
$env:POCKETDISCO_MODE = 'local_test'
$env:POCKETDISCO_DATABASE_URL = "sqlite+aiosqlite:///$($PWD.Path.Replace('\', '/'))/.local-tools/mobile-alpha.db"
.local-tools/api-venv/Scripts/python -m pocketdisco
```

The server binds only to `127.0.0.1:8000`. Use `adb reverse tcp:8000 tcp:8000`
on each selected device and configure the development app for
`http://127.0.0.1:8000`. Cleartext transport is only for local debug builds.
The device needs no incoming Windows firewall rule.

`GET /healthz` reports the mode. Guest creation returns access and refresh
tokens, so do not print or commit full responses. The supplied runner disables
request logs because invite paths and socket query strings contain secrets.
Any proxy must also omit query strings, invite paths, authorization headers,
and request/response bodies from logs.

## Authentication and room limits

- Random opaque access tokens expire after 15 minutes.
- Refresh sessions have a 30-day absolute lifetime. Every refresh rotates the
  token; reusing an old token revokes that session and all its access tokens.
  Clients must serialize refresh requests. A lost refresh response requires a
  new guest session rather than replaying the old token.
- Only token hashes are stored. Tokens are never used in the socket URL.
- Room-bound, single-use socket tickets expire after 60 seconds.
- Invites contain 12 uppercase characters, are hashed at rest, and expire in
  24 hours. Joining is throttled by IP and account. Membership caps at 25.
- Names are trimmed, with display names capped at 40 and room names at 80.
- Chat is trimmed to 1 through 1000 characters. Snapshots include the latest
  50 messages. Chat commands deduplicate by room, sender, and command ID.
- Room changes lock the PostgreSQL room row. Durable changes commit before
  publication; snapshots recover missed Pub/Sub events. A periodic socket
  snapshot check also repairs gaps without waiting for another mutation.
- Presence expires after 60 seconds without heartbeats. Readiness clears on
  the last disconnect or expiry. One member may have at most three sockets.
- Outbound queues hold eight snapshots, then disconnect slow consumers.
  Incoming HTTP and socket messages are capped at 8 KiB.

The socket sends `command.ack` only after a chat/readiness mutation commits,
including idempotent repeats. Errors include the command ID when available.
Clients must not replay chat automatically after an uncertain disconnect.

## Tests

```powershell
.local-tools/api-venv/Scripts/python -m pytest services/api/tests
.local-tools/api-venv/Scripts/python -m ruff check services/api
.local-tools/api-venv/Scripts/python -m ruff format --check services/api
```

Local tests exercise SQLite and memory state. They do not prove PostgreSQL
locking or Redis behavior. The integration job uses both real services.
Greenlet is pinned to 3.2.4 on Windows because 3.5.6's native extension was
rejected by this development PC's application-control policy. No Windows
security settings were changed.

## PostgreSQL and Redis

On a machine with Docker Compose, set fresh local `POSTGRES_PASSWORD` and
`REDIS_PASSWORD` environment variables, then run:

```text
docker compose -f infra/compose/compose.yaml up -d --wait
```

The databases bind to loopback only. Set `POCKETDISCO_MODE=production`,
`POCKETDISCO_DATABASE_URL=postgresql+asyncpg://pocketdisco:<password>@127.0.0.1:5432/pocketdisco`,
and `POCKETDISCO_REDIS_URL=redis://:<password>@127.0.0.1:6379/0`. URL-encode
passwords in connection URLs. Then, from `services/api`:

```text
python -m alembic upgrade head
python -m alembic check
python -m pocketdisco
```

Production startup checks both stores and never creates tables or falls back
to local storage. Apply reviewed migrations before starting workers. Put the
service behind HTTPS/WSS with a body-size limit and request timeout. The
supplied runner trusts no forwarded IP headers; a deployed proxy needs an
explicit trusted-proxy configuration before relying on per-client IP limits.
Use deployment-owned secrets, managed backups, and pinned image digests when
deploying. The Compose configuration is local infrastructure, not a deployment.

Real integration tests require `POCKETDISCO_TEST_DATABASE_URL` for a disposable
database whose name ends in `_test`, plus `POCKETDISCO_TEST_REDIS_URL`.
Run migrations on that database first. Tests use a random Redis key prefix and
never flush a Redis database. PostgreSQL test rows remain until the disposable
database is removed. The API CI job tests concurrent room capacity and chat,
refresh reuse, Redis tickets/rate limits/presence, and cross-instance sockets.
It also applies, checks, rolls back, and reapplies migrations.

Docker is not installed on the current development PC, so local green tests
must not be reported as PostgreSQL/Redis integration evidence. Those results
come from the real-service CI job.

## Not release-ready

This slice has no playback, provider integration, deployed HTTPS endpoint,
account deletion/export, retention worker, moderation, or production secrets
management. Those remain release gates. Guest sessions are installation-local;
there is no account recovery or account upgrade screen yet.
