from __future__ import annotations

import argparse
import ipaddress
import logging
import secrets
import sys
from collections.abc import Sequence

from .server import create_server


DEFAULT_BIND = "127.0.0.1"
DEFAULT_PORT = 8765


def _port(value: str) -> int:
    try:
        port = int(value)
    except ValueError as error:
        raise argparse.ArgumentTypeError("port must be an integer") from error
    if not 1 <= port <= 65535:
        raise argparse.ArgumentTypeError("port must be from 1 through 65535")
    return port


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(prog="python -m tools.phase0_coordinator")
    parser.add_argument(
        "--bind",
        default=DEFAULT_BIND,
        help="IPv4 address to bind. Default: 127.0.0.1",
    )
    parser.add_argument(
        "--port",
        default=DEFAULT_PORT,
        type=_port,
        help="TCP port. Default: 8765",
    )
    return parser


def _is_loopback(value: str) -> bool:
    if value.lower() == "localhost":
        return True
    try:
        return ipaddress.ip_address(value).is_loopback
    except ValueError:
        return False


def main(argv: Sequence[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    logging.basicConfig(level=logging.INFO, format="%(levelname)s %(message)s")
    token = secrets.token_urlsafe(32)
    try:
        server = create_server(args.bind, args.port, token)
    except OSError as error:
        print(f"Could not bind coordinator: {error}", file=sys.stderr)
        return 1

    host, port = server.server_address[:2]
    print(f"Listening on http://{host}:{port}", flush=True)
    print(f"Bearer token: {token}", flush=True)
    print("Audio serving is disabled.", flush=True)
    if not _is_loopback(args.bind):
        print("LAN binding enabled. Use only on a trusted, isolated network.", flush=True)

    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("Stopping coordinator.", flush=True)
    finally:
        server.server_close()
    return 0
