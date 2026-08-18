# Phase 0 evidence pack

This directory contains the operator material for the feasibility gates in
`docs/04-build-plan.md`. It does not replace the product, architecture, API, or
testing documents.

## Contents

- [Provider support request drafts](provider-support-requests.md)
- [Track rights checklist and evidence register](track-rights-register.md)
- [Two-phone Media3 test protocol](two-phone-test-protocol.md)
- [Visible YouTube experiment protocol](youtube-experiment-protocol.md)
- [Phase 0 gate checklist](gate-checklist.md)

## Scope

Phase 0 answers four questions:

1. Can two Android phones start the same owned or licensed MP4/AAC asset within
   the provisional built-in-speaker skew target?
2. Are 3 to 5 test tracks covered by written rights evidence for the planned
   test?
3. What written guidance do Spotify, Apple, and YouTube provide for the exact
   private-room use case?
4. Is a visible, foreground-only YouTube IFrame experiment honest and workable,
   or should it be retained internally or removed?

Phase 0 does not add Spotify playback, public discovery, voice chat, production
room services, or a complete application scaffold. The control backend must not
transport music bytes.

## Evidence handling

- Use UTC timestamps in ISO 8601 format.
- Give every artifact a stable evidence ID, such as `P0-M3-20260818-01`.
- Record the branch, commit, app build, device build, and test configuration.
- Keep raw attempts, including failed starts and inconclusive provider cases.
- Do not commit access tokens, signed media URLs, account identifiers, support
  credentials, private contract text, or unredacted device logs.
- Commit a sanitized decision record and a checksum or secure evidence reference
  when the source artifact cannot safely live in the repository.
- Do not commit provider audio or video unless its written license permits that
  storage and distribution.

The request drafts are not permissions. An empty rights register or an unrun
protocol is not evidence that a gate passed.
