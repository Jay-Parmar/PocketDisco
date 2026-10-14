# PocketDisco field guide

[Open the illustrated PDF](PocketDisco-Walkthrough.pdf).

The illustrated Android walkthrough is generated locally from `build_guide.py`,
`edition.json` and reviewed device screenshots in `assets/`. It uses vector
artwork, searchable text, ten navigation bookmarks and an interactive test
checklist with feedback fields. The PDF is for the
internal test build, not a Play release or a synchronization certification.

## Rebuild

From the repository root, use a dedicated local virtual environment:

```powershell
.local-tools/api-venv/Scripts/python.exe -m venv .local-tools/walkthrough-venv
.local-tools/walkthrough-venv/Scripts/python.exe -m pip install --cache-dir .local-tools/walkthrough-pip-cache -r docs/walkthrough/requirements.txt
.local-tools/walkthrough-venv/Scripts/python.exe docs/walkthrough/build_guide.py
.local-tools/walkthrough-venv/Scripts/python.exe -m unittest discover -s docs/walkthrough -p "test_*.py" -v
.local-tools/walkthrough-venv/Scripts/python.exe docs/walkthrough/check_guide.py --render-dir .local-tools/walkthrough-render
```

The 14 October 2026 edition records runtime `c9ebd01`. Its three screenshots
are unchanged captures from the final emulator build, reviewed for private
content and matched by SHA256. For layout work on a future edition:

```powershell
.local-tools/walkthrough-venv/Scripts/python.exe docs/walkthrough/build_guide.py --draft --output .local-tools/walkthrough-draft.pdf
```

No fonts or images are fetched during generation. ReportLab's standard PDF
fonts keep the source portable. PyMuPDF is used only by the tests and page-render
checker. Their upstream licenses apply to those development dependencies.

## Screenshot and evidence review

Only actual app screens belong in `assets/`. Each entry in `edition.json`
records a description, SHA256 and an explicit no-invite review. The generator
rejects images outside that directory and hash mismatches. Check every image
visually for private chat, room/nearby invitation codes, account information
and unrelated apps before adding it. Do not add mock screens in their place.

The final edition records the runtime revision, date, actual check results and
evidence paths. A successful PDF build does not establish a product test pass.
Keep failed or interrupted runs as separate evidence. The automated PDF checker
checks page count, bookmarks, text bounds and key limitations. Review all ten
rendered pages as well, including screenshot readability and text overlap.

## Recorded results

Nothing A142 and the API 36 emulator used the deployed backend through the
private PC SSH tunnel and per-device ADB route. These are not public-network
or Play-release results. Raw reports remain inside the repository at
`.local-tools/mobile-polish-20261014/`; the committed summary is in the
[UI and device checkpoint](../14-mobile-ui-walkthrough.md).

| Run on `c9ebd01` | Result | Time, UTC on 14 October |
| --- | --- | --- |
| `rooms-two-c9ebd01` | All 10 checks in one uninterrupted two-client run | 13:24:15 to 13:29:52 |
| `demo-nothing-c9ebd01` | All 12 generated local-preview checks | 13:16:29 to 13:19:26 |
| `demo-emulator-c9ebd01` | All 12 generated local-preview checks | 13:21:33 to 13:24:08 |
| `nearby-c9ebd01` | All 4 generated-source USB/TCP handoff checks | 13:30:15 to 13:32:26 |

Each directory contains `results.json`. The room run covers membership,
readiness, chat both ways, background/missed-chat recovery, process restart,
offline cold-start retry and clean exit. It does not play audio.

The demo checks cover explicit play/pause, paused five-second seeks, natural
completion, replay, background pause, process recovery without autoplay and
native-player stop on Leave. Both inactive native observations completed
before the 22-second proof deadline, before the 24-second clip could finish
naturally. No acoustic recording was made.

The nearby run used generated audio, an authenticated receiver and matching
sent/received PCM hashes. The playing snapshot had one scheduled start, zero
late frames, zero resyncs and zero dropped output frames. The later shutdown
snapshot has no peer and is waiting for a clock; it is not another playing
observation. Back returned to the product room and stopped capture and
receiver timing. This short USB/TCP check is not a Bluetooth, multi-hop,
source-speaker alignment or acoustic synchronization result.

`nearby-c9ebd01/cleanup.json` records the final cleanup: the synthetic room
was left, the test's product/lab/source apps and capture were stopped, text
scales were restored and both devices' API reverse mappings were preserved.

Earlier failed and interrupted runs are retained with their own revisions.
The final uninterrupted pass does not relabel those earlier runs as passing.

## Automated verification

| Area | Result |
| --- | --- |
| Mobile | 168 tests; TypeScript and ESLint pass |
| Android product, capture core and protocol | 142 tests; internal/release builds pass; lint has zero errors and 15/16 existing warnings |
| Domain and API | 80 domain tests plus TypeScript; 108 API tests, 8 local integration skips; 9 deployed API smoke checks |
| Device and capture harnesses | 111 device tests, Ruff lint/format; 79 capture tests, not a clean-lint claim for the older capture directory |
| Generated audio and operations | 11 audio, 18 HTTPS preparation, 18 remote smoke harness and 4 backup tests |
| Illustrated guide | 10 tests, deterministic rebuild, three image hashes, page/text checks and saved interactive form fields |

The final PDF has ten pages, ten bookmarks, three real app screenshots,
nine interactive checkboxes and two feedback fields. All ten rendered pages
were reviewed for clipping, overlaps and private content. No raster artwork
was fabricated to represent app or test results.

## Review coverage

Keyboard and chat-layout checks covered the Nothing phone and the API 36
emulator in portrait at normal and 150% text size. TalkBack traversal,
landscape, floating keyboards and text sizes above 150% were not tested.
These checks are not a complete accessibility audit.

The replacement POCO received the APK in Downloads, but installation was
blocked by its USB security gate. It is not a third-device test pass.

## Product boundaries

- The internal app's loopback address needs a running PC SSH tunnel and an
  explicit ADB mapping for each phone. USB tethering is not sufficient.
- Public HTTPS is not configured. Router ports 50080 and 50443 alone do not
  make the backend a trusted public endpoint.
- Rooms share membership, readiness and text chat, not a music timeline.
- The local 24-second generated demo plays only on the tapped phone.
- Nearby capture is an internal Android 10+ experiment with a different code
  and admission system. It does not turn a phone into a Bluetooth speaker.
- The latest retained Bluetooth timing regression is unresolved. No acoustic
  alignment result or provider-catalog playback permission is implied.

The source of truth remains the [mobile checkpoint](../10-mobile-checkpoint.md),
[deployment notes](../11-deployment-proposal.md),
[capture checkpoint](../13-synchronized-capture.md) and
[current UI and device verification](../14-mobile-ui-walkthrough.md).
