# Provider support request drafts

These drafts describe the current private-room use case. They have not been sent
and do not authorize implementation. Replace bracketed fields, obtain any needed
legal review, and send them only through an official provider support or review
channel.

For each submission, record a sanitized evidence entry:

| Field | Value |
|---|---|
| Evidence ID | `P0-PROV-[provider]-[date]-[sequence]` |
| Provider | |
| Official channel | |
| Submitted at, UTC | |
| Sender role | Do not record a personal email address here |
| Ticket or case reference | Redact if it grants account access |
| Exact draft revision or commit | |
| Response received at, UTC | |
| Response evidence reference | |
| Decision recorded in | |

## Spotify

**Subject:** Written policy interpretation for opt-in private synchronized rooms

Hello Spotify Developer Support,

We are evaluating PocketDisco, an Android-first social listening product for
private, invite-only rooms. We have not implemented Spotify playback and will
not implement, publicly test, market, or monetize it without written approval.

The proposed Spotify use case would work as follows:

- Every participant uses their own Spotify account and any subscription required
  by Spotify.
- Every participant explicitly joins the room and taps a readiness control.
- One participant acts as host and coordinates track selection, play, pause,
  seek, and skip for that room.
- Each phone uses an official Spotify playback surface to request its own
  authorized stream from Spotify.
- PocketDisco sends only room state and future-effective control commands. Its
  backend never receives, proxies, records, downloads, mixes, alters, or
  redistributes Spotify audio.
- Rooms are single-provider. Spotify content would not be matched to or combined
  with another provider's stream.
- The first proof would be private, foreground use by no more users than the
  applicable development limit. The intended MVP room limit is 25 only if the
  provider and quota terms permit it.

We understand that the current Spotify Developer Policy gives a prohibited-app
example involving one source playing to several simultaneous listeners and
restricts commercial streaming integrations. Please answer in writing:

1. Does the proposed private, opt-in use case remain prohibited when each person
   independently authenticates and receives their own Spotify stream?
2. If any version is permitted, what product, audience, room-size, subscription,
   quota, and commercial restrictions apply?
3. Is partner approval or another review required before development or a closed
   test, and what is the official application path?
4. Are future-effective play, pause, seek, and skip commands allowed after every
   participant has explicitly opted in?
5. What playback-state data may be retained for short-lived synchronization and
   operational error measurement?

If the use case is not permitted, a direct confirmation would help us keep the
adapter absent from the product.

Regards,

`[name and role]`

`[organization, if applicable]`

`[official reply address]`

## Apple Music

**Subject:** MusicKit interpretation for independently authorized private rooms

Hello Apple Developer Support,

We are evaluating PocketDisco, an Android-first app for private, invite-only
social listening rooms. We are requesting a written interpretation before
committing an Apple Music integration.

The proposed MusicKit use case would work as follows:

- Every participant authorizes MusicKit and has any Apple Music subscription
  required by Apple.
- Every participant explicitly joins the room and taps a readiness control.
- One participant acts as host and coordinates track selection, play, pause,
  seek, and skip.
- Each Android phone uses the official MusicKit playback surface and receives its
  own authorized stream from Apple.
- Standard playback controls and required attribution remain available.
- PocketDisco sends only room state and future-effective control commands. Its
  backend never receives, proxies, records, downloads, uploads, modifies, mixes,
  or redistributes Apple Music content.
- Rooms are single-provider, private, foreground-first, and limited to 25 people
  in the intended MVP.

The initial prototype is noncommercial. No later business model has been chosen,
and access to Apple Music would not be sold by PocketDisco. Please answer in
writing:

1. Does Apple permit coordinating independently authorized MusicKit playback in
   this private multi-user room?
2. Does each person's explicit room readiness action satisfy user-initiation
   requirements for later room-wide play, pause, seek, skip, and track-transition
   commands, or is a new local gesture required for any of those actions?
3. Are there restrictions on scheduling those commands for a future instant to
   reduce playback skew?
4. What playback-state data may be retained briefly for synchronization, error
   handling, and aggregate operational measurement?
5. What review, entitlement, attribution, account, and subscription requirements
   apply before a closed test?
6. Which app business models would be allowed if Apple Music access itself is not
   sold or indirectly monetized?

Please point us to the controlling agreement sections and any formal review path.

Regards,

`[name and role]`

`[organization, if applicable]`

`[official reply address]`

## YouTube

This request is justified by the open policy question in
`docs/06-decisions-and-open-questions.md`. It is guidance for a separate
experiment, not approval to treat YouTube as a launch dependency.

**Subject:** Guidance for visible, foreground IFrame players in opt-in private rooms

Hello YouTube API Services team,

We are evaluating a limited PocketDisco experiment in which two Android users in
a private room watch and listen through separate official YouTube IFrame players.

The experiment would work as follows:

- The player runs in an Android WebView supported by the IFrame Player API and
  supplies the required API client identity and referrer information.
- The audiovisual player remains visible at or above the required size while it
  plays. Controls, branding, metadata, related content, and advertisements are
  not hidden, covered, altered, skipped, or suppressed.
- Every participant explicitly joins and taps a readiness control before room
  playback begins.
- After readiness, a control service may ask each visible IFrame player to play,
  pause, or seek at approximately the same future time.
- Playback stops or is treated as unavailable when the app is backgrounded or the
  screen is locked. The product does not offer background YouTube audio.
- The backend never receives, extracts, downloads, records, proxies, modifies, or
  redistributes YouTube media.
- Playlist links are resolved only through official YouTube identifiers and APIs.
  No private or reverse-engineered YouTube Music API is used.

Please answer in writing:

1. Are room-wide IFrame API commands compliant after each viewer explicitly opts
   in and the player remains visible and unaltered?
2. Is a new local gesture required for later play, pause, seek, skip, or playlist
   transition commands?
3. What is the required behavior when `onAutoplayBlocked` fires, the screen locks,
   or the app leaves the foreground?
4. May the app collect short-lived player state, command timing, buffering, and
   error events to measure synchronization without deriving prohibited metrics or
   retaining media data?
5. May a `music.youtube.com` playlist URL be reduced to its YouTube playlist ID
   and opened through the official Data and IFrame APIs when that playlist is
   accessible?
6. Is there an audit or compliance review path before this experiment is offered
   to invited testers?

Regards,

`[name and role]`

`[organization, if applicable]`

`[official reply address]`
