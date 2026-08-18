# Product brief

## Product statement

PocketDisco is an internet room where people listen together. A room has one canonical queue and timeline. Each participant authenticates with the relevant content provider and each phone independently streams an authorized copy. The PocketDisco server coordinates state; it never proxies, records, mixes, downloads, or redistributes provider audio.

## MVP user journey

1. Sign in to PocketDisco.
2. Create a private room and share an invite link/code.
3. Joiners enter the room, choose a supported playback source, and explicitly tap **Ready to play**.
4. The host selects an item or playlist, then controls play, pause, seek, and skip.
5. Every ready phone prepares the item and starts at a future server timestamp.
6. The room shows who is ready, reconnecting, unsupported, or out of sync.
7. Members can send text chat and reactions. Host transfer is supported.

## MVP scope

- Android, foreground use
- Private invite-only rooms; maximum 25 participants
- Owned/royalty-free hosted audio adapter
- Shared queue, play/pause/seek/skip, late join, reconnect
- Text chat and reactions
- Host/moderator/listener roles
- Sync health indicator and manual per-device delay adjustment
- A separately gated YouTube IFrame proof of concept

## Explicitly outside MVP

- Spotify launch integration without written approval
- Guaranteed sample-accurate sync
- Background YouTube playback
- Cross-provider matching (for example, host on Spotify and listener on YouTube)
- Voice chat, public room discovery, user uploads, payments, ads, offline playback
- DJ mixing, crossfades, equalizers, audio extraction, or provider-content modification

## Why cross-provider playback is deferred

The “same song” is not always the same recording. Catalogs contain different masters, edits, live versions, regional substitutions, durations, silence, and availability. ISRC helps identify recordings but is not a perfect playback mapping key. Cross-provider matching also conflicts with Spotify's current prohibition on integrating its content with streams/content from another service. A room should therefore be single-provider. A future mapping service needs provider permission, confidence scoring, duration checks, and a user-visible fallback.

## Meaning of “at the same time”

There are three different clocks:

- **Command sync:** phones receive play/pause quickly.
- **Playhead sync:** providers report approximately the same content position.
- **Audible sync:** sound reaches listeners at the same moment.

The app can control the first two. Audible sync also depends on decoding, Android's audio pipeline, Bluetooth latency, speakers, and provider buffering. Bluetooth can make a playhead-synchronized room sound like an echo. The app should advertise “best-effort synchronized listening,” show sync health, and offer a manual delay calibration. Exact multiroom-speaker synchronization is a materially harder product.

## Product success measures

- Room creation-to-first-play completion rate
- Median/p95 time for all ready participants to start
- Measured playhead skew by adapter, device, and network class
- Corrections per participant-minute
- Disconnect and recovery rate
- Provider auth/playability failures
- Percentage of sessions completed without host intervention
- Seven-day return rate for people who joined at least one successful room

Do not invent a single “sync score” from third-party provider content if that would violate provider analytics restrictions. Operational drift metrics should be minimal, access-controlled, and reviewed against each provider's terms.
