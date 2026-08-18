# Visible YouTube experiment protocol

## Purpose

Evaluate the official YouTube IFrame Player on two Android phones without
changing the PocketDisco playback or policy boundary. The experiment measures
foreground behavior, controls, ads, and divergence. It does not establish
provider permission or a production integration.

## Compliance setup

Before each run, verify:

- [ ] The official IFrame Player API runs in a supported Android WebView.
- [ ] API client identity and referrer signals are configured.
- [ ] The audiovisual player is visible and at least 200 by 200 CSS pixels while
  playing.
- [ ] No overlay obscures controls, branding, metadata, related content, or ads.
- [ ] Ads are not hidden, altered, skipped, synchronized, or suppressed.
- [ ] Each participant must make an explicit readiness gesture.
- [ ] `onAutoplayBlocked` produces a visible local action prompt and is not
  bypassed.
- [ ] Background and screen-locked playback are not offered.
- [ ] Only official video or playlist identifiers and permitted Data API metadata
  are used.
- [ ] No media is downloaded, extracted, recorded, proxied, modified, or routed
  through the PocketDisco backend.

Stop the run and record a compliance concern if the player becomes hidden while
audio continues, required surfaces are obscured, or the harness bypasses a user
gesture or advertisement.

## Run record

| Field | Value |
|---|---|
| Evidence ID | `P0-YT-[date]-[sequence]` |
| Date and operator, UTC | |
| Branch and commit | |
| Harness build ID | |
| Phone A model, Android build, WebView version | |
| Phone B model, Android build, WebView version | |
| IFrame API configuration revision | |
| Test video and playlist IDs | Do not copy media URLs with account data |
| Account class and territory | Do not record account identifiers |
| Network | |
| Player dimensions | |
| Control, branding, metadata, and ad visibility verified by | |

Collect IFrame state events, current-time samples, buffering, errors, and operator
observations. Do not capture or redistribute YouTube audio, video, or ad content
as test evidence.

## Cases

Run every case on both phones. `Not observed` is a valid observation for an ad or
browser policy event, but it is not proof that the event cannot occur.

| ID | Case | Procedure | Required evidence |
|---|---|---|---|
| `YT-01` | Visible player | Load the same official test item on both phones. Inspect size, visibility, controls, branding, metadata, related content, and ad surfaces. | Dimensions, configuration, state events, compliance checklist |
| `YT-02` | Explicit readiness | Start a fresh session. Require a local tap from each participant before cue or play eligibility. | Gesture timestamp and ready state per phone |
| `YT-03` | Coordinated play | After both readiness gestures, cue the same item and issue a future-effective play command to both visible players. Sample current time and state. | Command time, state timeline, buffering, descriptive playhead skew |
| `YT-04` | Pause and seek | While both players stay visible, issue pause, then seek, then play commands. Record state and current time until both settle. | Command and state timeline, errors, divergence |
| `YT-05` | Autoplay blocked | In a fresh context, attempt the documented condition that may trigger `onAutoplayBlocked`. Never add a bypass. If triggered, use the visible local recovery prompt. | Event or `Not observed`, prompt behavior, recovery gesture |
| `YT-06` | Advertisements | Observe runs long enough for naturally served ads. Do not influence, skip, hide, or normalize them. | Per-phone ad occurrence and timing as operator notes, divergence outcome |
| `YT-07` | Screen lock | During playback, lock each phone in turn. Confirm that PocketDisco does not advertise or maintain background YouTube playback. | Player state, audible observation without recording content, recovery path |
| `YT-08` | App switch | Switch away from the app, then return. Record visibility, playback state, socket state, and any required new gesture. | Lifecycle and player events, recovery path |
| `YT-09` | Playlist transition | Use an accessible two-item playlist and allow a natural transition on both phones. Do not force ad alignment. | Per-phone item, state, transition time, unavailable or divergent outcome |

If an item is unavailable or region-restricted during a case, preserve the error
as an outcome. Do not replace it silently.

## Result record

| Case | Phone A result | Phone B result | Ads or availability differed | Foreground rule met | Gesture rule met | Sync observation | Evidence reference |
|---|---|---|---|---|---|---|---|
| `YT-01` | | | | | | | |
| `YT-02` | | | | | | | |
| `YT-03` | | | | | | | |
| `YT-04` | | | | | | | |
| `YT-05` | | | | | | | |
| `YT-06` | | | | | | | |
| `YT-07` | | | | | | | |
| `YT-08` | | | | | | | |
| `YT-09` | | | | | | | |

Report timing results separately for runs with equal ad state, different ad
state, buffering, and item availability. A provisional black-box p95 observation
of 750 ms or less may be reported, but it is not the Phase 0 licensed-audio gate
and must not be presented as guaranteed synchronization.

## Decision record

Choose one outcome after the cases and any written guidance are reviewed:

1. `Internal experiment`: compliance basics worked, but permission, ads,
   lifecycle behavior, or control precision still needs review.
2. `Remove or defer`: the experience is misleading, noncompliant, or not
   controllable enough for the stated product.
3. `Foreground best-effort candidate`: evidence and written guidance support
   continued gated work. This remains subject to the later Phase 4 review and is
   not a Phase 0 production approval.

| Field | Value |
|---|---|
| Outcome | |
| Evidence IDs | |
| Written guidance reference | |
| Known ad, availability, and lifecycle limits | |
| User-facing claim allowed by evidence | |
| Feature flag or removal action | |
| Reviewer and date, UTC | |
