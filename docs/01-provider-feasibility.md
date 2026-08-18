# Provider feasibility and policy gates

Status checked: 2026-08-16. Provider terms and APIs change; re-check before each release.

## Feasibility matrix

| Source | Technical route on Android | Product status | Main constraints |
|---|---|---|---|
| Owned/licensed HTTP audio | Native AndroidX Media3/ExoPlayer adapter | **Build first** | You need streaming rights; do not accept arbitrary copyrighted uploads/URLs |
| YouTube / YouTube Music playlist link | Visible YouTube IFrame Player inside an Android WebView; Data API for permitted metadata | **Conditional experiment** | No background audio, player must remain visible, branding/controls/ads cannot be hidden or altered, autoplay may be blocked, per-device ads/availability make tight sync unreliable |
| Spotify | Spotify Android App Remote or Player Web API, technically | **No-go without written approval** | Current policy expressly prohibits one source playing to several simultaneous listeners; streaming SDA cannot be commercial; Premium is required; development mode is limited to five allowlisted users |
| Apple Music | MusicKit for Android + Apple Music API | **Investigate with Apple** | Each user needs authorization/subscription; playback must be user-initiated with standard controls; cannot charge for or indirectly monetize access to Apple Music; obtain written interpretation for group sync |
| Local files | Media3, after content hash confirms every member has the identical asset | **Possible later** | Rights remain the user's responsibility; different encodes are not synchronizable by track title alone |
| Other commercial services | A dedicated official adapter only | **Case by case** | Never use reverse-engineered/private playback APIs; require a written policy and monetization review |

## Spotify: current blocker

Spotify's effective 15 May 2025 Developer Policy says developers must not create “any non-interactive internet webcasting service” and gives as its example an app that plays content from one source to several simultaneous listeners. The same policy says:

- music streaming through the platform is Premium-only;
- commercial use of a streaming integration is not permitted;
- a product may not integrate Spotify streams/content with another service;
- Spotify content may not be mixed, overlapped, altered, or used for business/public playback.

The Android App Remote SDK can technically start a playlist, seek, and subscribe to player state, but technical capability is not authorization. New development-mode apps are limited to five allowlisted users. Extended quota applications currently require an established organization, a launched service, and at least 250,000 monthly active users, among other conditions.

**Decision:** keep the core provider-neutral, but do not ship, monetize, or publicly test Spotify synchronized rooms without written Spotify approval. A Spotify link may be displayed as an ordinary outbound link only after a separate policy review; do not represent that as synchronized integration.

## YouTube / YouTube Music: conditional path

There is no public YouTube Music-specific playback SDK in the official developer surface used here. A `music.youtube.com` playlist normally carries a YouTube playlist ID, so a link resolver can extract the ID and use official YouTube playlist/IFrame facilities where the playlist is accessible. Do not use unofficial `ytmusicapi`-style reverse-engineered endpoints for a production app.

The official IFrame API can cue/load videos or playlists, play, pause, seek, and report current time. On Android it must run in an OS WebView/Custom Tab. Important product constraints:

- the audiovisual player must stay visible (minimum 200 × 200 px; larger is recommended);
- the app must not obscure branding, controls, metadata, related content, or ads;
- background/minimized playback is prohibited;
- automated/scripted playback may be blocked, so every participant needs an explicit initial play/readiness gesture;
- the embed must send API-client identity/referrer signals;
- regional restrictions, unavailable items, different advertisements, and buffering can produce different timelines on different phones.

Because advertisements cannot be suppressed and are not guaranteed to be identical, YouTube cannot honestly promise tight uninterrupted sync. Build a two-to-five-phone compliance proof and ask YouTube for guidance/audit before making it central to the product.

## Apple Music: plausible, not pre-approved

MusicKit exposes Android authentication, queues, and playback. Apple's current program agreement requires user-initiated full-song playback and standard media controls, prohibits modification/download/upload of MusicKit content, and prohibits charging for or indirectly monetizing access to Apple Music. The wording found does not expressly approve synchronized multi-user rooms. Ask Apple Developer Support for a written use-case interpretation before committing the roadmap.

## Content and licensing boundary

“Every device downloads its own copy” prevents your backend from rebroadcasting bytes, but it does not automatically satisfy a provider's developer policy, music licensing, public-performance rules, or app-store rules. Private friends listening is different from a bar, event, store, or paid public room.

For the controllable MVP, use one of:

- audio created and owned by the team;
- properly licensed production-music tracks whose license covers on-demand synchronized streams to multiple end users;
- public-domain recordings (the composition and the particular recording must both qualify in the relevant territories).

Do not add arbitrary user uploads until there are clear grants of rights, content moderation, repeat-infringer handling, takedown procedures, storage security, and legal review.

## Go/no-go checklist for each provider

1. Official playback API/SDK exists on Android and later Windows.
2. Written terms permit remote group synchronization and the intended audience.
3. Monetization model is allowed.
4. Each participant's authentication/subscription requirements are clear.
5. Controls, attribution, artwork, ads, and background behavior comply.
6. Playback position and seek precision are sufficient for the product target.
7. App review/quota/partner access is realistically attainable.
8. Provider outage, unavailable-track, region, explicit-content, and child-user behavior is designed.

