# Decisions and open questions

## Decisions recorded now

| Decision | Reason |
|---|---|
| Android first, foreground first | Reduces lifecycle variables and matches the desired first platform |
| React Native UI + Kotlin playback core | Uses current skills without putting precise scheduling on JavaScript timers |
| FastAPI modular monolith | Familiar stack and adequate for MVP; easier correctness than early services |
| PostgreSQL + Redis | Durable product data plus low-latency expiring room state/fan-out |
| Provider-neutral playback interface | Provider capability and permission vary; Windows can add another implementation |
| Owned/licensed audio proves sync | Removes provider jitter/policy from the core technical experiment |
| Private rooms before discovery | Avoids premature moderation, spam, and child-safety scope |
| Text before voice | Voice competes for audio focus and raises WebRTC/provider-mixing issues |
| No Spotify implementation yet | Current policy directly conflicts with the core use case |
| No cross-provider matching in MVP | Matching is unreliable and introduces additional policy conflicts |

## Product questions to answer through prototypes/interviews

1. Is the primary experience remote friends on headphones, or multiple phone speakers in one physical place? The latency and echo problem differs greatly.
2. Does “talk” mean text chat, voice chat, or only that phones communicate over the internet?
3. Must playback continue with the screen off? If yes, YouTube cannot satisfy the requirement via its embedded API.
4. Are rooms private friends-only, discoverable/public, or both?
5. Who can control playback: host only, moderators, voting, or democratic queue?
6. Is this intended to make money? Spotify streaming currently cannot be a commercial integration; Apple also restricts monetizing access.
7. What countries and age groups launch first? Catalog, privacy, child safety, and music/public-performance rights differ.
8. What room size matters: 5, 25, 100, or thousands? A party app and a broadcast product have different rights and architecture.

These do not block the two-phone sync proof. Defaults for the proof: remote/private adult friends, host controls, text only, foreground, built-in/wired audio, maximum five devices.

## Technical questions the proof must measure

- Media3 scheduled-start accuracy across representative Android devices
- Audible latency difference versus reported player position
- Bluetooth latency variance and whether manual delay is usable
- Lead time needed to reach a reliable ready state on mobile data
- WebView IFrame control/position precision and advertisement divergence
- How Android backgrounding, Doze, audio focus, and network handoff affect the room socket
- Whether native correction creates audible artifacts

## External questions

- Will Spotify provide written approval/partner access for private interactive rooms despite the current prohibited-app example?
- Does Apple approve coordinating independently authorized MusicKit playback across users, and what business model is permitted?
- Will YouTube confirm that room-wide scheduled IFrame commands are compliant when every viewer explicitly opts in and the visible players/ads remain untouched?
- What exact test-audio license covers simultaneous on-demand streams to participants in launch territories?

## Naming note

“PocketDisco” is the chosen project name but has not received formal trademark clearance. Before public use, search app stores, domains, trademarks, and provider branding rules. Do not use branding that implies endorsement by a music provider.
