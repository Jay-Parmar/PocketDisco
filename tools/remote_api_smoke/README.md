# Backend room smoke test

Checks a deployed API through real HTTP and WebSocket connections. It creates
two named synthetic guests, a private room, and three synthetic chat messages.
Both guests leave after the checks, closing the room. On failure it attempts
the same cleanup without replaying uncertain mutations.

Guest accounts, expired sessions, chat, and the closed room remain in the
database. The API does not yet have account deletion or retention jobs. This
is a manual deployment check, not a recurring health probe. Each run uses two
of the 30 guest sessions allowed per IP per hour.

## Run

Use the existing API test environment, which provides `httpx` and `websockets`.
From the repository root:

```powershell
.local-tools/api-venv/Scripts/python tools/remote_api_smoke/run.py --base-url https://api.example.test --report .local-tools/remote-api-smoke/deployment-1.json
```

Replace the example with the deployed origin. Paths, credentials, query
strings, and fragments are rejected. HTTPS verifies certificates and requires
the API to report production mode. There is no insecure TLS option. HTTP and
WebSocket redirects are refused, and ambient proxy settings are ignored.

For an isolated local API or an existing SSH loopback tunnel:

```powershell
.local-tools/api-venv/Scripts/python tools/remote_api_smoke/run.py --base-url http://127.0.0.1:8000 --allow-local-http
```

Cleartext is allowed only with that flag and a literal loopback IP, not a
hostname, LAN address, or Tailscale address. Local mode results do not prove a
PostgreSQL/Redis deployment or public HTTPS access.

## Results

The nine checks cover health, distinct guest sessions, room create/join,
two-socket presence, readiness, two-way chat, reconnect snapshot recovery,
one-use ticket rejection, and leave with lost membership access. Reconnect
also checks readiness reset and a chat message sent while the guest was away.
Ticket reuse must be rejected before its expiry; an expired ticket alone is
not counted as evidence that the ticket was single-use.

Output contains fixed check names and failure categories only. Tokens, ticket
URLs, invite codes, room IDs, response bodies, and exception details are never
printed or saved. Network-library logging is disabled during the CLI run.
The optional JSON report must be a new file under the workspace `.local-tools`
directory. Existing reports are not overwritten.

HTTP and socket handshakes have 10-second timeouts. Socket checks have a
15-second deadline and a 64-event cap. The full scenario is capped at 120
seconds, followed by up to 15 seconds of cleanup. Requests are not retried.

Exit codes: `0` passed, `1` failed or incomplete cleanup/reporting, `2` invalid
arguments, `130` interrupted. Only completed checks are marked passed. A run
does not verify Android UI, playback, audible sync, Bluetooth, load capacity,
or Play release readiness.

## Tests

```powershell
.local-tools/api-venv/Scripts/python -m unittest discover -s tools/remote_api_smoke -v
.local-tools/api-venv/Scripts/python -m ruff check tools/remote_api_smoke
.local-tools/api-venv/Scripts/python -m ruff format --check tools/remote_api_smoke
```

Tests start an isolated loopback API with SQLite and in-memory live state.
They cover the complete control flow, failure cleanup, bounded reads, URL and
report-path restrictions, and credential-free output with debug logging enabled.
They do not contact the deployment server or prove PostgreSQL/Redis behavior.
