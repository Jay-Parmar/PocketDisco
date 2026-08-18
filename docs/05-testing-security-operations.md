# Testing, security, and operations

## Sync acceptance targets

Initial targets are hypotheses to validate, not marketing claims.

| Measure | Licensed audio target | Third-party black-box target |
|---|---:|---:|
| Regional command delivery p95 | ≤250 ms | ≤250 ms |
| Built-in-speaker playhead/audible skew p95 after prepare | ≤250 ms | ≤750 ms, best effort |
| Recovery to correct item/position after reconnect | ≤5 s | ≤10 s |
| Permanent divergence after revision gap | 0 | 0; otherwise adapter becomes unsupported |

Report results separately for phone speakers, wired, Bluetooth, and provider. Do not average away a broken category.

## Test pyramid

### Pure unit/property tests

- Room state reducer and legal state transitions
- Revision/idempotency under duplicate and reordered commands
- Timeline desired-position calculations
- Clock offset/outlier/slew logic
- Queue reordering and concurrent host actions
- Invite/token expiry and authorization matrix

### Protocol simulation

Create hundreds of in-process virtual clients with controlled latency, jitter, loss, clock skew, disconnects, and buffer time. Assert eventual convergence to one revision/item/status/position. This tests control logic, not real audio.

### Integration tests

- FastAPI + disposable PostgreSQL/Redis
- Atomic room command, publish, snapshot recovery
- WebSocket reconnect and backpressure
- Refresh-token rotation/reuse detection
- Provider URL parser with malicious/ambiguous URLs

### Real-device/acoustic tests

- At least two manufacturers and low/mid/high-performance devices
- Built-in, wired, common Bluetooth codecs/devices
- Wi-Fi, mobile data, network handoff, high jitter, screen/app lifecycle
- Record multiple phone outputs with an external microphone and measure known test-track onset/correlation for owned audio
- Compare provider-reported playhead skew with audible skew; they are not interchangeable
- Ten or more trials per scenario; publish median/p95/max and failures

Never inject, extract, or alter third-party content to make a test easier.

### Load/chaos tests

- Expected concurrent connections and room sizes, then 2× launch forecast
- One hot room plus many small rooms
- Slow WebSocket consumer and bounded outbound queues
- Kill an API instance; clients reconnect and fetch snapshot
- Drop Pub/Sub events; revision gap triggers snapshot
- Redis restart and PostgreSQL failover/restore

## Security baseline

- TLS everywhere; HSTS on web endpoints.
- Short-lived access tokens; rotating, hashed refresh tokens bound to device sessions.
- One-use short-lived WebSocket tickets.
- OAuth Authorization Code + PKCE, state, nonce where applicable, and claimed HTTPS/app links.
- Android Keystore for device secrets; no provider token in AsyncStorage, logs, crash reports, URLs, or analytics.
- Envelope encryption/KMS for any provider refresh token that truly must be server-side.
- Strict room/role authorization on every command; never trust a UI-hidden button.
- High-entropy invite token in deep links. If a short human code is offered, rate-limit by IP/device/account and expire it.
- Message length/content limits, per-action rate limits, payload schemas, and outbound backpressure.
- Dependency/secret scanning, signed release builds, backup encryption, tested account deletion.
- Data minimization and retention schedule. Do not retain precise IP/location unless necessary.

## Abuse and privacy

Invite-only rooms still need kick/ban, block, report, host transfer, chat deletion/moderation state, and an operator path. Public discovery needs a much larger safety program and should not be silently enabled.

Create a data inventory before beta:

- what is collected;
- why and under which consent/legal basis;
- where it is stored and encrypted;
- who can access it;
- retention/deletion/export behavior;
- which data is shared with Google/YouTube, Apple, notification, crash, or analytics services.

Provider identity/metadata is governed by provider terms in addition to the app's privacy policy.

## Operational telemetry

Useful low-cardinality metrics:

- active connections/rooms/members and reconnects;
- command accept-to-deliver latency;
- ready duration and commit lead time;
- clock RTT/uncertainty buckets;
- drift buckets and correction result;
- snapshot gap recoveries;
- provider auth/unavailable/autoplay/buffer failures;
- Redis/PostgreSQL latency, WS outbound queue depth, error rate.

Use randomly rotated/pseudonymous identifiers in telemetry, sample noisy events, and never log OAuth tokens, invite secrets, chat bodies, or provider response payloads by default.

## Failure behavior

- Backend unreachable: keep current authorized playback only if provider policy permits; freeze room controls and show reconnecting. On reconnect, accept server snapshot as authoritative.
- Host disconnects: grace period, then pause or transfer based on room setting.
- Participant not ready: do not delay forever; mark late and join at current position when possible.
- Wrong/unavailable item: isolate that participant and show the reason; do not change the whole room unless the host chooses skip.
- Clock uncertainty too high: avoid correction loops; degrade status and retry estimation.
- Provider API/SDK policy change: remote feature flag/kill switch disables the adapter without an app release.

