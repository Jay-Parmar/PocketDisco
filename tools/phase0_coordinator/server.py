from __future__ import annotations

import json
import logging
import re
import secrets
import threading
import time
import uuid
from dataclasses import dataclass
from http import HTTPStatus
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from typing import Callable, Mapping, cast
from urllib.parse import urlsplit


MAX_REQUEST_LINE_BYTES = 4096
MAX_REQUEST_TARGET_BYTES = 2048
MAX_HEADER_BYTES = 8192
MAX_BODY_BYTES = 4096
MIN_LEAD_MS = 2_000
MAX_LEAD_MS = 30_000
MAX_POSITION_MS = 86_400_000

_ASSET_ID_PATTERN = re.compile(r"[A-Za-z0-9][A-Za-z0-9._:-]{0,127}\Z")
_SHA256_PATTERN = re.compile(r"[0-9a-fA-F]{64}\Z")
_IDEMPOTENCY_KEY_PATTERN = re.compile(r"[A-Za-z0-9][A-Za-z0-9._:-]{0,127}\Z")
_TRIAL_PATH_PATTERN = re.compile(r"/v1/trials/([0-9a-fA-F-]{36})\Z")
_HTTP_LOG = logging.getLogger("pocketdisco.phase0_coordinator.http")


def unix_time_ms() -> int:
    return time.time_ns() // 1_000_000


@dataclass(frozen=True)
class TrialPayload:
    asset_id: str
    asset_sha256: str
    requested_position_ms: int
    effective_at_unix_ms: int


@dataclass(frozen=True)
class Trial:
    id: str
    asset_id: str
    asset_sha256: str
    requested_position_ms: int
    effective_at_unix_ms: int
    created_at_unix_ms: int

    def as_dict(self) -> dict[str, object]:
        return {
            "id": self.id,
            "asset_id": self.asset_id,
            "asset_sha256": self.asset_sha256,
            "requested_position_ms": self.requested_position_ms,
            "effective_at_unix_ms": self.effective_at_unix_ms,
            "created_at_unix_ms": self.created_at_unix_ms,
        }


class ApiError(Exception):
    def __init__(
        self,
        status: int,
        code: str,
        message: str,
        headers: Mapping[str, str] | None = None,
    ) -> None:
        super().__init__(message)
        self.status = status
        self.code = code
        self.message = message
        self.headers = dict(headers or {})


class DuplicateJsonKey(ValueError):
    pass


class TrialStore:
    def __init__(self) -> None:
        self._lock = threading.Lock()
        self._trials: dict[str, Trial] = {}
        self._idempotency: dict[str, tuple[TrialPayload, str]] = {}

    def create(
        self,
        idempotency_key: str,
        payload: TrialPayload,
        received_at_unix_ms: int,
    ) -> tuple[Trial, bool]:
        with self._lock:
            previous = self._idempotency.get(idempotency_key)
            if previous is not None:
                previous_payload, trial_id = previous
                if previous_payload != payload:
                    raise ApiError(
                        HTTPStatus.CONFLICT,
                        "idempotency_conflict",
                        "The idempotency key was already used with a different payload.",
                    )
                return self._trials[trial_id], True

            lead_ms = payload.effective_at_unix_ms - received_at_unix_ms
            if not MIN_LEAD_MS <= lead_ms <= MAX_LEAD_MS:
                raise ApiError(
                    HTTPStatus.UNPROCESSABLE_ENTITY,
                    "effective_time_out_of_range",
                    "effective_at_unix_ms must be 2000 through 30000 ms after server receipt.",
                )

            trial_id = str(uuid.uuid4())
            trial = Trial(
                id=trial_id,
                asset_id=payload.asset_id,
                asset_sha256=payload.asset_sha256,
                requested_position_ms=payload.requested_position_ms,
                effective_at_unix_ms=payload.effective_at_unix_ms,
                created_at_unix_ms=received_at_unix_ms,
            )
            self._trials[trial_id] = trial
            self._idempotency[idempotency_key] = (payload, trial_id)
            return trial, False

    def get(self, trial_id: str) -> Trial | None:
        with self._lock:
            return self._trials.get(trial_id)


class CoordinatorServer(ThreadingHTTPServer):
    daemon_threads = True
    allow_reuse_address = True

    def __init__(
        self,
        server_address: tuple[str, int],
        token: str,
        clock: Callable[[], int] = unix_time_ms,
    ) -> None:
        self.token = token
        self.clock = clock
        self.trials = TrialStore()
        super().__init__(server_address, CoordinatorRequestHandler)


class CoordinatorRequestHandler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def setup(self) -> None:
        super().setup()
        self.connection.settimeout(10)

    @property
    def coordinator(self) -> CoordinatorServer:
        return cast(CoordinatorServer, self.server)

    def version_string(self) -> str:
        return "PocketDisco-Phase0"

    def handle_one_request(self) -> None:
        try:
            self.raw_requestline = self.rfile.readline(MAX_REQUEST_LINE_BYTES + 1)
            if len(self.raw_requestline) > MAX_REQUEST_LINE_BYTES:
                self.requestline = ""
                self.request_version = ""
                self.command = ""
                self.close_connection = True
                self.send_error(HTTPStatus.REQUEST_URI_TOO_LONG)
                return
            if not self.raw_requestline:
                self.close_connection = True
                return
            if not self.parse_request():
                return
            method_name = "do_" + self.command
            if not hasattr(self, method_name):
                self.send_error(HTTPStatus.NOT_IMPLEMENTED)
                return
            method = getattr(self, method_name)
            method()
            self.wfile.flush()
        except TimeoutError:
            self.close_connection = True
            self.log_error("request timeout")

    def parse_request(self) -> bool:
        if not super().parse_request():
            return False
        target_size = len(self.path.encode("utf-8", "replace"))
        if target_size > MAX_REQUEST_TARGET_BYTES:
            self.close_connection = True
            self._send_error(
                HTTPStatus.REQUEST_URI_TOO_LONG,
                "request_target_too_long",
                "The request target is too long.",
            )
            return False
        header_size = sum(
            len(name.encode("utf-8", "replace"))
            + len(value.encode("utf-8", "replace"))
            + 4
            for name, value in self.headers.raw_items()
        )
        if header_size > MAX_HEADER_BYTES:
            self.close_connection = True
            self._send_error(
                HTTPStatus.REQUEST_HEADER_FIELDS_TOO_LARGE,
                "request_headers_too_large",
                "The request headers are too large.",
            )
            return False
        return True

    def do_GET(self) -> None:
        received_at_unix_ms = self.coordinator.clock()
        try:
            self._authenticate()
            path = self._path_without_query()
            if path == "/v1/time":
                response = {
                    "server_receive_unix_ms": received_at_unix_ms,
                    "server_send_unix_ms": self.coordinator.clock(),
                }
                self._send_json(HTTPStatus.OK, response)
                return

            match = _TRIAL_PATH_PATTERN.fullmatch(path)
            if match is None:
                raise ApiError(HTTPStatus.NOT_FOUND, "not_found", "The endpoint was not found.")
            try:
                trial_id = str(uuid.UUID(match.group(1)))
            except ValueError:
                raise ApiError(HTTPStatus.NOT_FOUND, "not_found", "The trial was not found.")
            trial = self.coordinator.trials.get(trial_id)
            if trial is None:
                raise ApiError(HTTPStatus.NOT_FOUND, "not_found", "The trial was not found.")
            self._send_json(HTTPStatus.OK, {"trial": trial.as_dict()})
        except ApiError as error:
            self._send_api_error(error)

    def do_POST(self) -> None:
        received_at_unix_ms = self.coordinator.clock()
        try:
            self._authenticate()
            path = self._path_without_query()
            if path != "/v1/trials":
                raise ApiError(HTTPStatus.NOT_FOUND, "not_found", "The endpoint was not found.")
            idempotency_key = self._idempotency_key()
            payload = parse_trial_payload(self._read_json_body())
            trial, replayed = self.coordinator.trials.create(
                idempotency_key,
                payload,
                received_at_unix_ms,
            )
            status = HTTPStatus.OK if replayed else HTTPStatus.CREATED
            headers = {"Idempotency-Replayed": "true"} if replayed else {}
            self._send_json(status, {"trial": trial.as_dict()}, headers)
        except ApiError as error:
            self._send_api_error(error)

    def do_HEAD(self) -> None:
        self._method_not_allowed()

    def do_OPTIONS(self) -> None:
        self._method_not_allowed()

    def do_PUT(self) -> None:
        self._method_not_allowed()

    def do_PATCH(self) -> None:
        self._method_not_allowed()

    def do_DELETE(self) -> None:
        self._method_not_allowed()

    def _method_not_allowed(self) -> None:
        try:
            self._authenticate()
            raise ApiError(
                HTTPStatus.METHOD_NOT_ALLOWED,
                "method_not_allowed",
                "The method is not allowed.",
                {"Allow": "GET, POST"},
            )
        except ApiError as error:
            self._send_api_error(error)

    def _authenticate(self) -> None:
        values = self.headers.get_all("Authorization", [])
        expected = f"Bearer {self.coordinator.token}"
        if len(values) != 1 or not secrets.compare_digest(values[0], expected):
            raise ApiError(
                HTTPStatus.UNAUTHORIZED,
                "unauthorized",
                "A valid bearer token is required.",
                {"WWW-Authenticate": "Bearer"},
            )

    def _path_without_query(self) -> str:
        parsed = urlsplit(self.path)
        if parsed.query or parsed.fragment:
            raise ApiError(
                HTTPStatus.BAD_REQUEST,
                "query_not_allowed",
                "Query parameters are not accepted.",
            )
        return parsed.path

    def _idempotency_key(self) -> str:
        values = self.headers.get_all("Idempotency-Key", [])
        if len(values) != 1 or _IDEMPOTENCY_KEY_PATTERN.fullmatch(values[0]) is None:
            raise ApiError(
                HTTPStatus.BAD_REQUEST,
                "invalid_idempotency_key",
                "Idempotency-Key must contain 1 through 128 safe ASCII characters.",
            )
        return values[0]

    def _read_json_body(self) -> object:
        if self.headers.get("Transfer-Encoding") is not None:
            raise ApiError(
                HTTPStatus.BAD_REQUEST,
                "unsupported_transfer_encoding",
                "Transfer-Encoding is not supported.",
            )
        lengths = self.headers.get_all("Content-Length", [])
        if len(lengths) != 1 or not lengths[0].isascii() or not lengths[0].isdigit():
            raise ApiError(
                HTTPStatus.LENGTH_REQUIRED,
                "content_length_required",
                "One valid Content-Length header is required.",
            )
        raw_length = lengths[0]
        if len(raw_length) > len(str(MAX_BODY_BYTES)):
            raise ApiError(
                HTTPStatus.REQUEST_ENTITY_TOO_LARGE,
                "request_body_too_large",
                "The JSON request body exceeds 4096 bytes.",
            )
        length = int(raw_length)
        if length > MAX_BODY_BYTES:
            raise ApiError(
                HTTPStatus.REQUEST_ENTITY_TOO_LARGE,
                "request_body_too_large",
                "The JSON request body exceeds 4096 bytes.",
            )
        media_type = self.headers.get("Content-Type", "").partition(";")[0].strip().lower()
        if media_type != "application/json":
            raise ApiError(
                HTTPStatus.UNSUPPORTED_MEDIA_TYPE,
                "unsupported_media_type",
                "Content-Type must be application/json.",
            )
        body = self.rfile.read(length)
        if len(body) != length:
            raise ApiError(
                HTTPStatus.BAD_REQUEST,
                "incomplete_body",
                "The request body was incomplete.",
            )
        try:
            return json.loads(body.decode("utf-8"), object_pairs_hook=_unique_object)
        except (UnicodeDecodeError, json.JSONDecodeError, DuplicateJsonKey):
            raise ApiError(HTTPStatus.BAD_REQUEST, "invalid_json", "The request body is not valid JSON.")

    def _send_api_error(self, error: ApiError) -> None:
        self._send_error(error.status, error.code, error.message, error.headers)

    def _send_error(
        self,
        status: int,
        code: str,
        message: str,
        headers: Mapping[str, str] | None = None,
    ) -> None:
        self._send_json(status, {"error": {"code": code, "message": message}}, headers)

    def _send_json(
        self,
        status: int,
        payload: object,
        headers: Mapping[str, str] | None = None,
    ) -> None:
        encoded = json.dumps(payload, separators=(",", ":"), sort_keys=True).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(encoded)))
        self.send_header("Cache-Control", "no-store")
        self.send_header("Connection", "close")
        self.send_header("X-Content-Type-Options", "nosniff")
        for name, value in (headers or {}).items():
            self.send_header(name, value)
        self.end_headers()
        self.close_connection = True
        if self.command != "HEAD":
            self.wfile.write(encoded)

    def send_error(
        self,
        code: int,
        message: str | None = None,
        explain: str | None = None,
    ) -> None:
        errors = {
            HTTPStatus.BAD_REQUEST: ("bad_request", "The request could not be parsed."),
            HTTPStatus.REQUEST_URI_TOO_LONG: (
                "request_target_too_long",
                "The request target is too long.",
            ),
            HTTPStatus.REQUEST_HEADER_FIELDS_TOO_LARGE: (
                "request_headers_too_large",
                "The request headers are too large.",
            ),
            HTTPStatus.NOT_IMPLEMENTED: ("method_not_implemented", "The method is not implemented."),
            HTTPStatus.HTTP_VERSION_NOT_SUPPORTED: (
                "http_version_not_supported",
                "The HTTP version is not supported.",
            ),
        }
        error_code, error_message = errors.get(
            HTTPStatus(code),
            ("http_error", "The request could not be completed."),
        )
        self._send_error(code, error_code, error_message)

    def log_request(self, code: int | str = "-", size: int | str = "-") -> None:
        method = self.command if self.command in {"GET", "POST", "HEAD", "OPTIONS", "PUT", "PATCH", "DELETE"} else "OTHER"
        _HTTP_LOG.info("method=%s route=%s status=%s bytes=%s", method, self._route_label(), code, size)

    def log_error(self, format: str, *args: object) -> None:
        _HTTP_LOG.warning("request_error")

    def log_message(self, format: str, *args: object) -> None:
        _HTTP_LOG.info("request_event")

    def _route_label(self) -> str:
        path = urlsplit(getattr(self, "path", "")).path
        if path == "/v1/time":
            return "/v1/time"
        if path == "/v1/trials":
            return "/v1/trials"
        if path.startswith("/v1/trials/"):
            return "/v1/trials/{id}"
        return "other"


def _unique_object(pairs: list[tuple[str, object]]) -> dict[str, object]:
    result: dict[str, object] = {}
    for key, value in pairs:
        if key in result:
            raise DuplicateJsonKey(key)
        result[key] = value
    return result


def parse_trial_payload(value: object) -> TrialPayload:
    if not isinstance(value, dict):
        raise ApiError(
            HTTPStatus.UNPROCESSABLE_ENTITY,
            "validation_error",
            "The request body must be a JSON object.",
        )
    required = {
        "asset_id",
        "asset_sha256",
        "requested_position_ms",
        "effective_at_unix_ms",
    }
    if set(value) != required:
        raise ApiError(
            HTTPStatus.UNPROCESSABLE_ENTITY,
            "validation_error",
            "The request body must contain exactly the documented trial fields.",
        )

    asset_id = value["asset_id"]
    asset_sha256 = value["asset_sha256"]
    requested_position_ms = value["requested_position_ms"]
    effective_at_unix_ms = value["effective_at_unix_ms"]

    if not isinstance(asset_id, str) or _ASSET_ID_PATTERN.fullmatch(asset_id) is None:
        raise ApiError(
            HTTPStatus.UNPROCESSABLE_ENTITY,
            "validation_error",
            "asset_id must contain 1 through 128 safe ASCII characters.",
        )
    if not isinstance(asset_sha256, str) or _SHA256_PATTERN.fullmatch(asset_sha256) is None:
        raise ApiError(
            HTTPStatus.UNPROCESSABLE_ENTITY,
            "validation_error",
            "asset_sha256 must be a 64-character hexadecimal SHA-256 digest.",
        )
    if type(requested_position_ms) is not int or not 0 <= requested_position_ms <= MAX_POSITION_MS:
        raise ApiError(
            HTTPStatus.UNPROCESSABLE_ENTITY,
            "validation_error",
            "requested_position_ms must be an integer from 0 through 86400000.",
        )
    if type(effective_at_unix_ms) is not int or effective_at_unix_ms < 0:
        raise ApiError(
            HTTPStatus.UNPROCESSABLE_ENTITY,
            "validation_error",
            "effective_at_unix_ms must be a non-negative integer.",
        )

    return TrialPayload(
        asset_id=asset_id,
        asset_sha256=asset_sha256.lower(),
        requested_position_ms=requested_position_ms,
        effective_at_unix_ms=effective_at_unix_ms,
    )


def create_server(
    bind: str,
    port: int,
    token: str,
    clock: Callable[[], int] = unix_time_ms,
) -> CoordinatorServer:
    if not token:
        raise ValueError("token must not be empty")
    return CoordinatorServer((bind, port), token, clock)
