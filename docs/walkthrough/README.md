# PocketDisco field guide

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

The current edition is not final until the phone/emulator results and actual
screenshots have been reviewed. For layout work before then:

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
[deployment notes](../11-deployment-proposal.md) and
[capture checkpoint](../13-synchronized-capture.md).
