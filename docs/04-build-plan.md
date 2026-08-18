# Build plan

Estimate for one experienced full-time developer learning the Android-native portions: roughly 12–16 weeks to a small closed beta using licensed test audio. Part-time work can reasonably take 5–7 months. Provider review/approval is external and may take much longer or never arrive.

## Phase 0 — feasibility gates (week 1)

Deliverables:

- Write the exact personal/private-room use case to Spotify and Apple developer support; ask for written permission/interpretation.
- Confirm the license for 3–5 test tracks.
- Run a throwaway two-phone Media3 test with seek-friendly MP4/AAC assets: preload, start at a monotonic future instant, log position and audible onset.
- Run a separate visible YouTube IFrame test on two Android phones: user gesture, cue, play/seek, autoplay-blocked handling, ads, screen lock, app switch, and playlist transition.
- Decide initial target: built-in speakers and wired output; mark Bluetooth best-effort.

Exit criteria:

- Owned/licensed audio starts within a provisional 250 ms p95 skew on two target phones.
- No assumption remains that the backend sends music bytes.
- YouTube is either explicitly experimental or removed based on results/policy guidance.
- Spotify remains absent unless written approval is received.

## Phase 1 — repository and vertical slice (weeks 2–3)

- Create React Native Android app with TypeScript strict mode.
- Add Kotlin native playback/timing module with the adapter interface.
- Create FastAPI service, PostgreSQL migrations, Redis, Docker Compose, lint/type/test CI.
- Implement guest auth, private room create/join, one-use WS tickets.
- Define versioned event schemas and generate/validate client/server types.
- Build a single vertical slice: two authenticated phones join, receive snapshot, and see presence.

Exit: an automated dev setup and reconnect-safe room with no playback yet.

## Phase 2 — synchronization engine (weeks 4–5)

- Implement canonical timeline, atomic revision/idempotency, clock estimation, prepare/ready/commit.
- Implement licensed-audio Media3 adapter: prepare/play/pause/seek/state/ended/error.
- Add late join, host reconnect grace period, host transfer, stale-command recovery.
- Add drift reports/correction and manual output-delay control.
- Build a deterministic network simulator for delay, jitter, loss, duplicate commands, and reconnects.

Exit: 2, 5, and 10 phone/simulator runs meet targets and never diverge permanently after reconnect.

## Phase 3 — usable room product (weeks 6–7)

- Queue add/remove/reorder, skip, track transition.
- Ready/degraded/unsupported participant UI.
- Invite deep links, QR code, expiry/revocation, abuse throttling.
- Text chat/reactions, block/report, basic moderation.
- App lifecycle, network-change handling, clear provider/error states.
- Basic accessibility, privacy policy/data inventory, analytics opt-in decision.

Exit: friends can complete a 30-minute licensed-audio session without developer intervention.

## Phase 4 — YouTube compliance experiment (weeks 8–9, gated)

- Accept a YouTube/YouTube Music playlist link and resolve only official IDs/metadata.
- Embed official IFrame player in an Android WebView with required identity/referrer, visible size, controls, branding, metadata, and ads.
- Require participant gesture/readiness; handle `onAutoplayBlocked` and unavailable/region-restricted items.
- Measure actual multi-device behavior with ads and playlist transitions.
- Submit for policy guidance/audit if the product depends on it.

Exit options:

- ship as clearly labeled foreground/best-effort support after compliance review;
- retain as an internal experiment;
- remove it if ads, policy, or control precision makes the experience misleading.

## Phase 5 — reliability and closed beta (weeks 10–12)

- Device matrix across supported Android versions, manufacturers, Wi-Fi/mobile data, wired/Bluetooth.
- Load test expected connections and hot rooms; chaos-test API node loss and Redis Pub/Sub gaps.
- Add dashboards for connection count, command propagation, readiness, clock uncertainty, drift, corrections, and provider errors.
- Security review, dependency scanning, token-log audit, backups/restore drill.
- Google Play internal/closed testing; complete Data Safety and foreground-service declarations if applicable.
- Recruit 20–50 invited testers for licensed-audio rooms; do not exceed provider development limits.

Exit: published SLOs are met for two weeks and serious auth/data-loss/sync bugs are closed.

## Phase 6 — launch preparation (weeks 13–16+)

- Incorporate beta findings and accessibility/localization basics.
- Final provider, music-license, privacy, terms, moderation, and app-store review.
- Capacity test at 2× expected launch peak.
- Incident runbook, status page/contact, account deletion/export, retention jobs.
- Release gradually with feature flags and provider kill switches.

## First engineering backlog

Build in this order:

1. `RoomSnapshot` and pure reducer with revision/conflict tests.
2. Clock estimator with recorded/synthetic RTT tests.
3. Native licensed-audio `prepare` and `playAt` on one phone.
4. FastAPI WS hello/ticket/snapshot.
5. Two-phone prepare/commit.
6. Position reports and drift dashboard.
7. Pause/seek/late join/reconnect.
8. Queue/track transitions.
9. Product UI and chat.
10. Provider experiments.

Starting with OAuth, polished screens, public discovery, or a playlist importer would postpone the hardest uncertainty: whether phones can sound acceptably synchronized.

## Future phases

### Public discovery

Add only with searchable profiles/rooms, age policy, reporting, blocking, moderator tools, room visibility rules, rate limits, spam controls, and a staffed abuse process. Invite-only rooms avoid a large trust-and-safety surface during validation.

### Voice

Prototype WebRTC separately. Decide whether talk pauses music, uses push-to-talk, or ducks licensed audio; obtain provider approval before mixing. Use an SFU for groups, not peer-to-peer mesh beyond very small calls.

### Windows

Reuse `domain`, `protocol`, networking, and much of the React UI. Implement a separate Windows playback/timing adapter. Choose React Native Windows if UI reuse is the priority and required native modules exist; choose React web + Tauri/Electron if official providers primarily expose browser SDKs. Repeat provider approval because Android authorization does not imply Windows authorization.
