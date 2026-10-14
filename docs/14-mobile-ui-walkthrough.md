# Mobile UI and walkthrough checkpoint

2026-10-14. Work is on `feature/mobile-ui-walkthrough`, stacked on
`feature/mobile-server-connection`. The owner reviews and merges the PRs.
The internal UI build is ready for the owner's private-route test lap. It is
not a public release or a shared-music milestone.

## What changed

- A compact record illustration brings create/join closer to the top. The
  welcome screen describes private chat and local audio without promising
  synchronized room playback. Loopback server addresses get USB route help.
- Connection status, Leave and the Room / Audio / Chat shortcuts stay visible.
  The shortcuts scroll one screen; they do not remount the player, close a
  nearby session or discard an unsent chat draft.
- Audio corner places the generated local demo before the separate nearby
  experiment. Readiness explicitly does not start or synchronize audio.
- Failed room actions add a persistent View error hint to the Room shortcut.
  It announces the failure and jumps to its details, including after a failed
  Leave from Audio or Chat.
- Room and Audio jumps resist stale scroll events during keyboard resizing.
  The explicit target clears on arrival, user drag or chat focus, with a
  one-second fallback bound. Chat can still follow new messages at the bottom.
- Short conversations fill the measured chat viewport, so Chat does not leave
  most of the screen occupied by the preceding nearby experiment card.
- Welcome and Room now explicitly avoid Android keyboard overlap. Screenshot
  review caught the composer hidden behind the keyboard on the earlier build.
  The fix uses the bundled React Native keyboard measurements, with no native
  overrides or fixed keyboard heights. Event tests cover overlapping and
  already-resized windows. See the [React Native reference](https://reactnative.dev/docs/keyboardavoidingview).
- New navigation and Leave targets are at least 48 dp. Text remains scalable;
  cards grow rather than clipping content to fixed heights.

Existing control IDs, field placeholders, member/message accessibility labels
and playback state labels are preserved. This is a UI milestone, not a new
audio provider or a change to the synchronization architecture.

## Current product boundary

Private rooms, invitations, presence, readiness, text chat and recovery remain
the product slice. Pocket pulse is a 24-second generated local preview with
play, pause, seek and replay. It plays on the phone that taps Play demo, not
across the room. Entering a room and changing readiness do not start audio.

Nearby audio remains an internal experiment with its own admission code and
Android capture consent. Room membership does not grant nearby access. It
cannot carry music to remote Internet-room members, and it does not delay the
original source app's speaker. Capture restrictions and media rights still
apply. The [latest Bluetooth timing regression](13-synchronized-capture.md#connection-recovery-regression-2026-10-12)
remains open; a USB result does not prove acoustic synchronization.

The backend still has no public HTTPS/WSS endpoint. These checks use the
private SSH tunnel and per-device ADB loopback route. Disconnecting USB or
stopping the tunnel breaks that route. The existing
[release gates](10-mobile-checkpoint.md#remaining-before-release) still apply.
Desktop work remains paused.

## Verified automated checks

App runtime through `c9ebd01`, with device harness through `87fdc98`:

- All 168 mobile Jest tests pass across 11 suites.
- Mobile TypeScript and full ESLint checks pass.
- Android: 142 tests pass across product, shared capture core and protocol.
  Internal and release builds pass. Lint has zero errors, with 15 internal
  and 16 release warnings retained from the existing native dependencies.
- Domain: 80 tests and TypeScript pass. API: 108 tests pass; eight real-store
  integration tests are skipped locally. All nine deployed API smoke checks
  pass through the private SSH route with PostgreSQL and Redis.
- Device harness: 111 tests pass. Its Ruff lint and format checks pass.
  Capture harness: 79 tests pass. A broader exploratory Ruff check also found
  existing formatting and lint issues in that older harness; it is not a
  clean-lint result for the entire capture directory.
- Generated audio: 11 tests pass. HTTPS preparation: 18 tests pass. Remote
  API smoke harness: 18 tests pass. Backup tooling: four tests pass.
- Walkthrough generator: ten tests pass, including reproducibility, image
  hashes, text bounds and saving filled checklist fields.
- New coverage includes USB-only connection guidance, persistent navigation,
  safe unmeasured offsets, draft/player preservation, error discovery,
  keyboard-resize event ordering, user cancellation and bounded scroll intent.

These are component and application checks. They do not establish a device
pass, audible synchronization, public-network access or Play readiness.

## Device evidence and handoff

The primary current test pair is Nothing A142 and the PocketDisco API 36
emulator through the private backend route. Samsung was replaced by a POCO
phone running Android 14. POCO has additional USB install/input restrictions;
owner approval and its final test coverage must be recorded separately. Do not
count earlier Samsung results as POCO results or bypass its device prompts.
The owner reports that both restricted USB settings require a SIM. The final
APK was copied to `Download/PocketDisco-c9ebd01.apk` and its SHA256 checked;
this is a file transfer, not a successful installation or device test.
The separate API mapping was added without replacing any existing mapping.
A normal, user-approved install through File Manager is an optional next step.
It does not enable automated taps. See [Xiaomi's APK instructions](https://www.mi.com/uk/support/faq/details/KA-113603/).

Evidence for this session belongs under
`.local-tools/mobile-polish-20261014/`. Failed and interrupted runs must remain
alongside successful reruns.

- `rooms-three-01` stopped when Samsung was unplugged after welcome preflight
  and room creation. It is not a three-device pass.
- `rooms-two-02` on `8782232` passed create/join, presence, readiness and chat,
  then failed a generic scrolling lookup during recovery. Follow-up confirmed
  the missed message on the emulator. The harness now uses section shortcuts
  and small one-direction searches, without relaxing its assertions.
- `rooms-two-03` on `a211ea9` passed through background return, missed chat and
  restored presence. Stopping the host app exposed another test app underneath
  it, so the foreground guard halted the restart step. Nothing in that app was
  touched. The harness now goes Home and confirms the launcher before stopping
  only PocketDisco.
- `rooms-recovery-04` continued that room on the same runtime and passed host
  restart, offline cold-start retry and clean exit on both devices. This does
  not relabel the preceding interrupted full run as passing.
- `demo-nothing-01` passed all 12 checks on `8782232`: play/pause, paused seeks,
  natural end, replay, background pause, process recovery without autoplay,
  clean Leave and a UID-scoped native-player stop observation. No acoustic
  recording was made.
- `demo-emulator-final-02` passed all 12 checks on `a211ea9`. Review then
  tightened the runner's native-stop proof: both inactive observations must
  finish before 22 seconds, so a slow query cannot mistake natural completion
  of the 24-second clip for stopping on Leave. The earlier saved proofs were
  fast enough, under approximately 11 and 12.1 seconds respectively, but are
  retained with their original runner revision.
- `keyboard-nothing-01` and `keyboard-emulator-01` pass on `c9ebd01`. Both
  verify input and Send bounds above the visible Android IME, send without
  hiding the keyboard at 100% and 150% text, then exercise failed Leave from
  Audio, the persistent error shortcut, route restoration and clean exit.
  Original text scales and API mappings were restored. The tests interrupt
  only each phone's ADB API route, not the shared backend.
- `demo-nothing-c9ebd01` and `demo-emulator-c9ebd01` each pass all 12 checks
  with the bounded native-stop proof. Both pause at three seconds, seek to
  eight and back to three, and replay near two seconds. Leave completes
  8.782 and 8.172 seconds after starting the clip, respectively; the two
  inactive native observations complete before the 22-second deadline.
- `guide-c9ebd01` captures the reviewed Welcome, synthetic Chat and Audio
  screens. Two keyboard-open navigation laps at 150% text preserve the draft
  and reach Room and Audio without bouncing back. The test clears its own
  draft, leaves its room and restores the original text scale.
- `rooms-two-c9ebd01` passes all ten checks in one uninterrupted run,
  13:24:15 to 13:29:52 UTC. Nothing and the emulator create/join, see both
  members, update readiness, exchange chat, recover missed chat and presence
  after backgrounding, restore the host after process death, retry after an
  offline cold start, and leave cleanly. The private API route is restored.
- `nearby-c9ebd01` passes all four product handoff checks, 13:30:15 to
  13:32:26 UTC. The Nothing host admits the emulator over USB/TCP, generated
  non-silent PCM hashes match, scheduled receiver output advances, and Back
  returns to the room while capture and receiver timing stop. The retained
  playing observation has one scheduled start and no late frames, drops or
  resyncs. The later shutdown observation is kept separately, not presented
  as a playing result. This short software-path check is not Bluetooth,
  third-party catalog playback, an endurance run or an acoustic measurement.
  `cleanup.json` confirms the synthetic room was left, owned apps and capture
  stopped on both devices, text scales restored and existing API routes kept.
- Final runtime `c9ebd01` is installed in place on Nothing and the API 36
  emulator. No apps were removed and no app data was cleared. APKs and their
  checksum/signer manifest are in
  `.local-tools/mobile-polish-20261014/artifacts/c9ebd01/`.
  `PocketDisco-internal-nothing-emulator.apk` matches those existing installs
  and is suitable for a fresh phone. The Samsung alternative retains its
  different existing development signer. Both contain the same final bundle.
- The [illustrated walkthrough](walkthrough/PocketDisco-Walkthrough.pdf) has
  ten pages, three reviewed actual app screenshots, setup and relay diagrams,
  a fillable acceptance checklist and feedback fields. All ten rendered pages
  were visually reviewed. [Rebuild instructions and provenance](walkthrough/README.md)
  stay beside the generator and pinned development dependencies.
- [PR 12](https://github.com/Jay-Parmar/PocketDisco/pull/12) is stacked on
  `feature/mobile-server-connection` (PR 11). Commits remain focused and
  one-line. CI results are attached to the PR; the owner reviews and merges.
  No parent branch or PR was merged during this work.

Full TalkBack traversal, landscape and floating keyboards, and text above
150% were not tested. Unsent drafts remain memory-only, as before.

## Three-minute emulator UX smoke

This is a proposed manual check, not a recorded pass. Reserve the PocketDisco
emulator first. Use a synthetic test room with a healthy private route. Save
its existing text scale, set text to 150%, and leave unrelated apps and other
emulators untouched.

1. First minute: open Chat and type a two-line synthetic draft. With the
   keyboard open, tap Room, then Audio, then Chat. Repeat twice. Each shortcut
   must reach its section without bouncing back to chat. The draft stays
   unchanged, Leave remains reachable and audio never starts by itself.
2. Second minute: extend that draft to several lines. Check that the input can
   scroll, Send is reachable and the fixed header does not overlap it. Dismiss
   the keyboard, scroll through the invite and member cards, then use Audio.
   Labels must wrap without horizontal clipping or overlapping controls.
3. Final minute: clear only that synthetic draft. Choose Leave, then Stay;
   confirm the room remains open. Leave again and confirm Leave room. Welcome
   should appear without a stale error. Restore the original text scale.

If a room action fails, View error must remain visible in the Room shortcut
and open the detailed message. Record the failure instead of marking the
smoke passed. Do not interrupt a shared backend just to manufacture an error.
