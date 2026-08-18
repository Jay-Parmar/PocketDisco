from __future__ import annotations

import http.client
import json
import logging
import threading
import unittest

from tools.phase0_coordinator.server import MAX_BODY_BYTES, create_server


TOKEN = "test-token"
NOW_MS = 1_786_899_000_000
ASSET_HASH = "A" * 64


class FakeClock:
    def __init__(self, value: int) -> None:
        self.value = value

    def __call__(self) -> int:
        return self.value


class CoordinatorServerTests(unittest.TestCase):
    def setUp(self) -> None:
        self.clock = FakeClock(NOW_MS)
        self.server = create_server("127.0.0.1", 0, TOKEN, self.clock)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()
        self.port = self.server.server_address[1]

    def tearDown(self) -> None:
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(timeout=2)

    def request(
        self,
        method: str,
        path: str,
        body: bytes | None = None,
        headers: dict[str, str] | None = None,
    ) -> tuple[int, dict[str, object], dict[str, str]]:
        connection = http.client.HTTPConnection("127.0.0.1", self.port, timeout=2)
        connection.request(method, path, body=body, headers=headers or {})
        response = connection.getresponse()
        response_body = response.read()
        response_headers = {name.lower(): value for name, value in response.getheaders()}
        connection.close()
        parsed = json.loads(response_body) if response_body else {}
        return response.status, parsed, response_headers

    def auth_headers(self) -> dict[str, str]:
        return {"Authorization": f"Bearer {TOKEN}"}

    def trial_body(self, **changes: object) -> bytes:
        payload: dict[str, object] = {
            "asset_id": "test-track-1",
            "asset_sha256": ASSET_HASH,
            "requested_position_ms": 0,
            "effective_at_unix_ms": NOW_MS + 2_000,
        }
        payload.update(changes)
        return json.dumps(payload).encode("utf-8")

    def post_trial(
        self,
        key: str = "trial-key-1",
        body: bytes | None = None,
    ) -> tuple[int, dict[str, object], dict[str, str]]:
        headers = self.auth_headers()
        headers.update({"Content-Type": "application/json", "Idempotency-Key": key})
        return self.request("POST", "/v1/trials", body or self.trial_body(), headers)

    def test_time_returns_receive_and_send_unix_milliseconds(self) -> None:
        status, payload, _ = self.request("GET", "/v1/time", headers=self.auth_headers())

        self.assertEqual(200, status)
        self.assertEqual(
            {
                "server_receive_unix_ms": NOW_MS,
                "server_send_unix_ms": NOW_MS,
            },
            payload,
        )

    def test_every_endpoint_requires_bearer_authentication(self) -> None:
        status, payload, headers = self.request("GET", "/v1/time")

        self.assertEqual(401, status)
        self.assertEqual("unauthorized", payload["error"]["code"])
        self.assertEqual("Bearer", headers["www-authenticate"])

    def test_creates_and_fetches_a_trial(self) -> None:
        status, payload, _ = self.post_trial()

        self.assertEqual(201, status)
        trial = payload["trial"]
        self.assertEqual("test-track-1", trial["asset_id"])
        self.assertEqual(ASSET_HASH.lower(), trial["asset_sha256"])
        self.assertEqual(NOW_MS, trial["created_at_unix_ms"])

        get_status, get_payload, _ = self.request(
            "GET",
            f"/v1/trials/{trial['id']}",
            headers=self.auth_headers(),
        )
        self.assertEqual(200, get_status)
        self.assertEqual(trial, get_payload["trial"])

    def test_exact_idempotent_replay_returns_the_same_trial(self) -> None:
        first_status, first_payload, _ = self.post_trial()
        self.clock.value = NOW_MS + 60_000
        second_status, second_payload, second_headers = self.post_trial()

        self.assertEqual(201, first_status)
        self.assertEqual(200, second_status)
        self.assertEqual(first_payload, second_payload)
        self.assertEqual("true", second_headers["idempotency-replayed"])

    def test_idempotency_key_reuse_with_different_payload_conflicts(self) -> None:
        self.post_trial()
        status, payload, _ = self.post_trial(body=self.trial_body(requested_position_ms=100))

        self.assertEqual(409, status)
        self.assertEqual("idempotency_conflict", payload["error"]["code"])

    def test_lead_time_boundaries_are_inclusive(self) -> None:
        low_status, _, _ = self.post_trial("low", self.trial_body(effective_at_unix_ms=NOW_MS + 2_000))
        high_status, _, _ = self.post_trial("high", self.trial_body(effective_at_unix_ms=NOW_MS + 30_000))

        self.assertEqual(201, low_status)
        self.assertEqual(201, high_status)

    def test_lead_time_outside_range_is_rejected(self) -> None:
        early_status, early_payload, _ = self.post_trial(
            "early",
            self.trial_body(effective_at_unix_ms=NOW_MS + 1_999),
        )
        late_status, late_payload, _ = self.post_trial(
            "late",
            self.trial_body(effective_at_unix_ms=NOW_MS + 30_001),
        )

        self.assertEqual(422, early_status)
        self.assertEqual("effective_time_out_of_range", early_payload["error"]["code"])
        self.assertEqual(422, late_status)
        self.assertEqual("effective_time_out_of_range", late_payload["error"]["code"])

    def test_unknown_trial_returns_json_error(self) -> None:
        status, payload, headers = self.request(
            "GET",
            "/v1/trials/00000000-0000-0000-0000-000000000000",
            headers=self.auth_headers(),
        )

        self.assertEqual(404, status)
        self.assertEqual("application/json; charset=utf-8", headers["content-type"])
        self.assertEqual("not_found", payload["error"]["code"])

    def test_media_and_unknown_routes_do_not_exist(self) -> None:
        status, payload, _ = self.request("GET", "/audio/test.m4a", headers=self.auth_headers())

        self.assertEqual(404, status)
        self.assertEqual("not_found", payload["error"]["code"])

    def test_body_size_limit_returns_json_error(self) -> None:
        headers = self.auth_headers()
        headers.update({"Content-Type": "application/json", "Idempotency-Key": "large"})
        status, payload, _ = self.request(
            "POST",
            "/v1/trials",
            b"x" * (MAX_BODY_BYTES + 1),
            headers,
        )

        self.assertEqual(413, status)
        self.assertEqual("request_body_too_large", payload["error"]["code"])

    def test_oversized_content_length_returns_json_error(self) -> None:
        connection = http.client.HTTPConnection("127.0.0.1", self.port, timeout=2)
        connection.putrequest("POST", "/v1/trials")
        connection.putheader("Authorization", f"Bearer {TOKEN}")
        connection.putheader("Content-Type", "application/json")
        connection.putheader("Idempotency-Key", "oversized-length")
        connection.putheader("Content-Length", "9" * 5_000)
        connection.endheaders()

        response = connection.getresponse()
        payload = json.loads(response.read())
        connection.close()

        self.assertEqual(413, response.status)
        self.assertEqual("request_body_too_large", payload["error"]["code"])

    def test_request_target_size_limit_returns_json_error(self) -> None:
        path = "/v1/time/" + ("x" * 2050)
        status, payload, _ = self.request("GET", path, headers=self.auth_headers())

        self.assertEqual(414, status)
        self.assertEqual("request_target_too_long", payload["error"]["code"])

    def test_header_size_limit_returns_json_error(self) -> None:
        headers = self.auth_headers()
        headers["X-Fill"] = "x" * 8200
        status, payload, _ = self.request("GET", "/v1/time", headers=headers)

        self.assertEqual(431, status)
        self.assertEqual("request_headers_too_large", payload["error"]["code"])

    def test_invalid_json_and_duplicate_fields_are_rejected(self) -> None:
        invalid_status, invalid_payload, _ = self.post_trial("invalid", b"{")
        duplicate = (
            b'{"asset_id":"a","asset_id":"b","asset_sha256":"'
            + (b"a" * 64)
            + b'","requested_position_ms":0,"effective_at_unix_ms":1786899002000}'
        )
        duplicate_status, duplicate_payload, _ = self.post_trial("duplicate", duplicate)

        self.assertEqual(400, invalid_status)
        self.assertEqual("invalid_json", invalid_payload["error"]["code"])
        self.assertEqual(400, duplicate_status)
        self.assertEqual("invalid_json", duplicate_payload["error"]["code"])

    def test_trial_fields_are_strictly_validated(self) -> None:
        bool_status, bool_payload, _ = self.post_trial(
            "bool-position",
            self.trial_body(requested_position_ms=True),
        )
        url_status, url_payload, _ = self.post_trial(
            "url-asset",
            self.trial_body(asset_id="https://example.test/audio.m4a"),
        )

        self.assertEqual(422, bool_status)
        self.assertEqual("validation_error", bool_payload["error"]["code"])
        self.assertEqual(422, url_status)
        self.assertEqual("validation_error", url_payload["error"]["code"])

    def test_unsupported_method_returns_json_error(self) -> None:
        status, payload, headers = self.request("PUT", "/v1/time", headers=self.auth_headers())

        self.assertEqual(405, status)
        self.assertEqual("method_not_allowed", payload["error"]["code"])
        self.assertEqual("GET, POST", headers["allow"])

    def test_logs_do_not_contain_tokens_paths_or_query_values(self) -> None:
        logger = logging.getLogger("pocketdisco.phase0_coordinator.http")
        with self.assertLogs(logger, level="INFO") as captured:
            self.request(
                "GET",
                "/v1/trials/sensitive-trial-id?private=value",
                headers=self.auth_headers(),
            )

        output = " ".join(captured.output)
        self.assertNotIn(TOKEN, output)
        self.assertNotIn("sensitive-trial-id", output)
        self.assertNotIn("private=value", output)
        self.assertIn("/v1/trials/{id}", output)


if __name__ == "__main__":
    unittest.main()
