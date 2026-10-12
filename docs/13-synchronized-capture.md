# Synchronized capture milestone

Started on 2026-10-12 on `feature/synchronized-capture-playback`, stacked on
`feature/android-capture-relay-lab`. The owner requested synchronized playback,
private rooms using the installed music app, and Play readiness. A second
physical phone is connected for testing.

## Scope

The next implementation slice schedules captured PCM against one source clock.
Receivers share a fixed future deadline rather than playing frames on arrival.
Clock probes travel to the original source through the same authenticated links.
Relay hops do not add another playback delay. Queues, clock estimates and
reconnect state remain bounded. Late or stale audio must not play as a backlog.

The Android product will get an internal-only entry to the shared nearby
engine. Nearby sessions use a separate 128-bit invitation code and paired
Bluetooth. They are not automatically authorized by Internet-room membership,
and cannot reach remote friends over the Internet. Existing private-room chat
and the independent-file provider remain separate. No backend audio proxy is
being added. Capture stays disabled in the public release build until its
product, privacy and distribution gates are settled.

Use generated music opened in the installed music app for repeatable checks.
Do not replace or patch that app, bypass capture restrictions, extract protected
streams, record microphones, or redistribute catalog tracks without permission.
The installed APK is not evidence of those rights. Spotify remains gated.

[Android playback capture](https://developer.android.com/media/platform/av-capture)
does not delay the source app. Only receiving outputs can share the new timeline.
The sending phone's original speaker is not a synchronized receiver. A quiet
sender and two independent receivers are needed to assess receiver-to-receiver
sound. AudioTrack timestamps are software evidence, not acoustic measurements.

## Test loop

1. Pure tests for clock offsets, jitter, stale probes, routing, deadlines,
   bounded queues, missing frames and cancellation.
2. Android build and lint; preserve blocked-source, selected-UID, wrong-code,
   consent-revocation and background-stop checks.
3. USB/TCP sender plus two receivers, including the added physical phone.
   Compare received content and scheduled output observations.
4. Paired physical Bluetooth, then mixed Bluetooth and USB relay. Keep
   software evidence separate from measured speaker alignment and radio range.
5. Build and test the internal product entry. Record exact artifacts, results
   and remaining gates before requesting review.

## Play gates remain open

This milestone alone is not a release approval. Public HTTPS/WSS, upload signing,
privacy and account deletion, chat safety, media distribution rights, native
16 KB compatibility, Console declarations and required tester access remain
separate work. See the [mobile checkpoint](10-mobile-checkpoint.md) and
[deployment notes](11-deployment-proposal.md). Do not describe a debug APK or
software clock result as a Play-ready or acoustically synchronized release.
