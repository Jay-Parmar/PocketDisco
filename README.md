# PocketDisco

Planning workspace for an Android-first social listening app. A host creates a room, selects a queue, and participants' phones play their own authorized copy of the same item on a shared timeline.

## Recommendation in one paragraph

Build the room and synchronization engine first with audio that you own or are licensed to stream. Use React Native + TypeScript for the Android UI, a small Kotlin playback/timing module, FastAPI WebSockets for the control plane, PostgreSQL for durable data, and Redis for live room state and fan-out. Add a policy-compliant YouTube foreground-player experiment only after the sync engine works. Do **not** make Spotify a launch dependency: its current policy expressly prohibits a product that plays one source to several simultaneous listeners, limits new development-mode apps to five allowlisted users, and forbids commercial streaming integrations. Seek written provider approval before implementing or marketing Spotify group playback.

## Documents

- [Product brief](docs/00-product-brief.md)
- [Provider feasibility and policy gates](docs/01-provider-feasibility.md)
- [System design](docs/02-system-design.md)
- [API, realtime protocol, and data model](docs/03-api-and-data-model.md)
- [Build plan](docs/04-build-plan.md)
- [Testing, security, and operations](docs/05-testing-security-operations.md)
- [Decisions and open questions](docs/06-decisions-and-open-questions.md)
- [Research sources](docs/07-sources.md)

## Proposed eventual repository shape

```text
PocketDisco/
  apps/
    mobile/                 # React Native Android app
    windows/                # future; framework decision deferred
  services/
    api/                    # FastAPI REST + WebSocket modular monolith
  packages/
    domain/                 # pure TypeScript room/playback state
    protocol/               # versioned JSON schemas and generated types
    ui/                     # only genuinely portable UI primitives
  infra/
    compose/                # local PostgreSQL + Redis
    deploy/                 # deployment definitions, later
  docs/
```

Only the planning files exist now. Application scaffolding should begin after the Phase 0 policy and two-phone proof-of-concept gates in the build plan.

## First success criteria

Phase 0 timing criterion: two Android phones on ordinary Wi-Fi play the same licensed test file with a measured built-in-speaker skew below 250 ms at the 95th percentile after ten starts.

Early product criterion: after the reconnect-safe room slice exists, a disconnected phone recovers by fetching a fresh room snapshot. Phase 1 adds room snapshot recovery and Phase 2 adds playback reconnect recovery.

These are product experiments, not promises of sample-accurate playback.
