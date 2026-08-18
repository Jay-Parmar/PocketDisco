# Track rights checklist and evidence register

Complete this record for 3 to 5 MP4/AAC test tracks before a device test uses
them. Every phone must fetch its own authorized copy directly from the licensed
media host. The PocketDisco control backend must not deliver audio bytes.

This checklist records diligence. It is not legal advice.

## Status values

- `Pending`: evidence is missing or under review.
- `Verified`: the evidence expressly covers the planned use.
- `Restricted`: usable only within recorded limits.
- `Rejected`: the evidence does not cover the planned use.
- `Not selected`: the slot is outside the chosen 3 to 5 tracks.

Do not infer `Verified` from a purchase receipt, a publicly reachable URL, a
stock-library label, or the word "royalty-free."

## Per-track verification

### Identity and file

- [ ] Assign an internal track ID and exact asset filename.
- [ ] Record title, version, artist or creator, duration, container, codec, and
  bitrate.
- [ ] Record the SHA-256 digest of the exact test file.
- [ ] Confirm that every phone uses the same asset ID and digest.
- [ ] Confirm that the asset is seek-friendly MP4/AAC, not a variable-bitrate MP3.

### Rights chain

- [ ] Identify the owner or licensor of the sound recording.
- [ ] Identify the owner or licensor of the musical composition.
- [ ] Clear performers, samples, and third-party material where applicable.
- [ ] For public-domain material, verify both the composition and the particular
  recording in every test territory.
- [ ] Verify that the grant comes from a party authorized to grant the rights.

### Exact planned use

- [ ] The grant covers storing the test asset with the selected media host.
- [ ] The grant covers any required encoding or transcoding to MP4/AAC.
- [ ] The grant covers on-demand delivery to each participant's device.
- [ ] The grant expressly covers simultaneous independent streams to multiple
  participants in a synchronized private-room test.
- [ ] The grant covers the planned number and type of testers.
- [ ] The grant covers every test territory.
- [ ] The grant remains valid for the full test period.
- [ ] Private beta, commercial-use, attribution, reporting, and usage-count limits
  are recorded.
- [ ] CDN, object-storage, and other service-provider use is permitted.
- [ ] Any prohibition on editing, rate adjustment, waveform analysis, or test
  recording is recorded before those actions occur.

### Evidence and decision

- [ ] A signed agreement, owner declaration, or controlling license text is
  available. An invoice alone is not treated as the rights grant.
- [ ] Conflicting terms and linked policies have been reviewed.
- [ ] Required attribution and reporting steps have an owner.
- [ ] A reviewer, review date, decision, and evidence IDs are recorded.
- [ ] Sensitive source documents stay outside Git; the repository contains a
  sanitized decision record and a checksum or secure reference.

## Track decision register

Select 3 to 5 rows. A row can be marked `Verified` only after every applicable
item above is complete.

| Track ID | Exact asset and SHA-256 | Composition basis | Recording basis | Simultaneous on-demand grant | Territories and term | Evidence IDs | Reviewer and date | Status |
|---|---|---|---|---|---|---|---|---|
| `TRK-01` | | | | | | | | `Pending` |
| `TRK-02` | | | | | | | | `Pending` |
| `TRK-03` | | | | | | | | `Pending` |
| `TRK-04` | | | | | | | | `Pending` |
| `TRK-05` | | | | | | | | `Pending` |

## Evidence register

Create one row for each source document, clarification, owner declaration, asset
manifest, or review decision. Do not paste private contract text into this table.

| Evidence ID | Track IDs | Evidence type | Issuer or authority | Issued at | Rights or restriction supported | Territory and term | Sanitized repository path or secure reference | SHA-256 | Reviewed by and at | Status |
|---|---|---|---|---|---|---|---|---|---|---|
| `P0-RIGHTS-[date]-01` | | | | | | | | | | `Pending` |

## Test-use signoff

| Field | Value |
|---|---|
| Selected track IDs, 3 to 5 | |
| All selected rows verified | `Yes / No` |
| Media host | Record hostname only, never a signed URL |
| Control backend host | |
| Confirmed as separate data paths | `Yes / No` |
| Open restrictions | |
| Reviewer | |
| Reviewed at, UTC | |
| Decision evidence ID | |
