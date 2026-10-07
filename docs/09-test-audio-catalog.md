# Test audio catalog

Checked 2026-10-07. The owner authorized sourcing free test music. These three
creator upload pages explicitly identify their recordings as CC0 1.0:

| Track | Creator | Source | Original SHA-256 |
|---|---|---|---|
| Electric | Sudocolon | [Creator upload](https://opengameart.org/content/electric-0) | `0708443fcc4aad2231a12e862312b191d6245c57a698108a5f14444b2f441180` |
| Technological Messup | Centurion_of_war | [Creator upload](https://opengameart.org/content/technological-messup) | `d782c36a82cfdb02772001fb5830a8d4e0e82392dd157fe4410d07d11f01e45e` |
| Technomania101 | Fupi | [Creator upload](https://opengameart.org/content/technomania101-2000s-europop-electronic-dance-music) | `6d4cbd78b9407accab39811877ccaa771968e890dc1430768c4ef4d413e3fca8` |

[CC0 1.0](https://creativecommons.org/publicdomain/zero/1.0/) permits copying,
adaptation, distribution, and performance, including commercial use, to the
extent of the rights waived by the creator. It is not a warranty of ownership
or clearance of unrelated rights. Retain creator credits and source links in
the app, do not imply endorsement, and review provenance again before release.

Originals are downloaded under `.local-tools/music-sources/`. They are not yet
included in a build or served by the control API. The playback increment will
record normalized MP4/AAC hashes and durations before using them as one shared
catalog. Every phone will read its own copy; no phone rebroadcasts media.

## Free To Use review

The [dance catalog](https://freetouse.com/music/category/dance) was suggested by
the owner. The [license](https://freetouse.com/license) restricts the free grant
to user-generated social content and prohibits distributing assets to third
parties. The [API article](https://freetouse.com/blog/royalty-free-music-api-for-the-apps-you-build)
describes noncommercial app playback more broadly. Do not assume the article
overrides the license for a music-listening product. No tracks from that site
are bundled or redistributed; written clarification is required if chosen.
