# Phase 0 LAN coordinator

This stdlib-only server gives two phones a shared clock sample and trial record. It stores only an asset label, SHA-256 digest, playback position, and timing metadata in memory. It has no route or code path for audio files, URLs, redirects, downloads, or proxying.

Use Python 3.10 or newer from the repository root.

```powershell
python -m tools.phase0_coordinator
```

The default bind is `127.0.0.1`. To let phones on the same trusted LAN connect, opt in with an address reachable from that LAN:

```powershell
python -m tools.phase0_coordinator --bind 0.0.0.0 --port 8765
```

Use the computer's LAN address on each phone, not `0.0.0.0`. The process prints a new bearer token at every start. Send it only in the `Authorization` header:

```text
Authorization: Bearer <startup-token>
```

This experiment uses plain HTTP. Run it only on a trusted, isolated network, do not expose it to the internet, and stop it after the trial. Restarting clears all trials and invalidates the token.

## API

`GET /v1/time` returns:

```json
{"server_receive_unix_ms":1786899000000,"server_send_unix_ms":1786899000001}
```

`POST /v1/trials` also requires `Content-Type: application/json` and an `Idempotency-Key` containing 1 through 128 letters, digits, `.`, `_`, `:`, or `-`.

```json
{
  "asset_id": "test-track-1",
  "asset_sha256": "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
  "requested_position_ms": 0,
  "effective_at_unix_ms": 1786899005000
}
```

The effective time must be 2,000 through 30,000 ms after the server receives the first request. A new trial returns `201`. Repeating the same key and normalized payload returns the same trial with `200`. Reusing the key with different data returns `409`.

```json
{
  "trial": {
    "id": "bdc2bc6e-eb31-469b-a101-44ef4732352f",
    "asset_id": "test-track-1",
    "asset_sha256": "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
    "requested_position_ms": 0,
    "effective_at_unix_ms": 1786899005000,
    "created_at_unix_ms": 1786899000000
  }
}
```

Both phones fetch the record with `GET /v1/trials/{id}`. Errors are JSON under an `error` object.

Request lines are limited to 4,096 bytes, targets to 2,048 bytes, headers to 8,192 bytes, and JSON bodies to 4,096 bytes. Logs contain only a normalized route, method, status, and response size. They exclude authorization values, query values, bodies, asset identifiers, and trial identifiers.

## Tests

```powershell
python -m unittest discover -s tools/phase0_coordinator/tests -v
```
