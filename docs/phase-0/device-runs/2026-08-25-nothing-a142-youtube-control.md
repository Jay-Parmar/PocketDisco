# One-phone YouTube control trial: Nothing A142

This record covers a control-only YouTube trial on one physical phone. It does
not establish two-phone synchronization, audible skew, provider approval, or a
Phase 0 device pass.

## Run record

| Field | Value |
|---|---|
| Evidence ID | `P0-SMOKE-20260825-01` |
| Date, local | `2026-08-25`, Asia/Calcutta |
| Branch and app source | `phase/0-feasibility` at `09dbc1c` |
| Harness build | `versionName 0.1`, `versionCode 1`, debug APK |
| APK | 3,751,865 bytes, SHA-256 `f0cdf852528f61aa21bf46910ced278fb8615a9a7f822254fcc85c82cb861b24` |
| Phone | Nothing A142, Android 16, API 36 |
| WebView | `com.google.android.webview` 151.0.7922.169 |
| Control connection | Local coordinator through `adb reverse`; not a shared LAN result |
| YouTube item | Public IFrame API example video ID `M7lc1UVf-VE` |

The device serial, bearer token, account identifiers, unrelated device data,
and full trial UUID are not recorded.

## Results

- The updated debug APK installed successfully. A cold MainActivity launch
  completed in 339 ms.
- The WebView used the installed application ID as its HTTPS base and IFrame
  origin. The official IFrame reached ready state on the phone.
- The readiness button remained disabled as `Waiting for media` before a cue.
  This verifies the guard added at `09dbc1c`.
- The public sample video cued successfully. Standard YouTube content, controls,
  branding, attribution, and the `Watch on YouTube` surface stayed visible.
- Seven authenticated coordinator time requests completed. The phone reported
  4 ms clock uncertainty and a 4 ms best round-trip time through `adb reverse`.
- The phone created a YouTube control trial. A server fetch confirmed the stored
  item type, official item ID, zero requested position, and numeric future
  effective time.
- An ADB-injected tap produced `onAutoplayBlocked`. The app showed the local
  recovery prompt and did not retry or bypass the provider response.
- The trial was not armed by a trusted physical gesture before its effective
  time. Scheduled playback is not claimed.
- No second phone was connected, so app-to-app synchronization and skew were not
  measured.

## Network boundary

The coordinator request and response contained control data only: item type,
official item ID, requested position, future effective time, and trial ID. The
PocketDisco coordinator did not receive or send YouTube media URLs, cookies,
headers, audio bytes, or video bytes. The phone fetched the visible player media
directly from YouTube.

## Gate interpretation

| Gate | Result from this probe |
|---|---|
| `P0-CODE-05` | `Pass` remains supported for the visible IFrame, readiness guard, and autoplay-blocked handling. |
| `P0-DEV-07` | `Pending`. Only one phone and a subset of cases were exercised. |
| `P0-DEV-08` | `Pending`. Written YouTube guidance and the full two-phone case matrix remain absent. |
| Phase 0 decision | `Pending` |

The next YouTube run needs a trusted tap on each of two physical phones, a shared
ordinary network, repeated future-effective starts, and separate observations
for ads, buffering, lifecycle changes, availability, and playlist transitions.
