"""Print a reviewed Caddy configuration without installing it."""

import argparse
import ipaddress
import sys
from pathlib import Path

CADDY_VERSION = "2.11.7"
LINUX_AMD64_SHA256 = "727b91701a392de6ebc5027509f548bf39979e5216340d0faed8fa5e69c84f8b"
MAX_TESTERS = 16
DIRECTORIES = {
    "staging": "https://acme-staging-v02.api.letsencrypt.org/directory",
    "production": "https://acme-v02.api.letsencrypt.org/directory",
}
TEMPLATE = Path(__file__).with_name("Caddyfile.template")


def public_address(value, ipv4=False):
    message = "Use a single public IPv4 address" if ipv4 else "Use a single public IP address"
    if not isinstance(value, str) or value != value.strip() or "%" in value:
        raise ValueError(message)
    try:
        address = ipaddress.ip_address(value)
    except ValueError:
        raise ValueError(message) from None
    if (
        not address.is_global
        or address.is_multicast
        or address.is_reserved
        or (ipv4 and address.version != 4)
    ):
        raise ValueError(message)
    return str(address)


def render(public_ip, tester_ips=(), acme="staging"):
    address = public_address(public_ip, ipv4=True)
    if acme not in DIRECTORIES:
        raise ValueError("Choose staging or production ACME")
    if len(tester_ips) > MAX_TESTERS:
        raise ValueError(f"Use at most {MAX_TESTERS} tester addresses")
    testers = sorted({public_address(value) for value in tester_ips})
    rooms = ""
    if testers:
        rooms = "\n".join(
            (
                "\t@rooms {",
                "\t\tpath /v1/*",
                "\t\tremote_ip " + " ".join(testers),
                "\t}",
                "\thandle @rooms {",
                "\t\timport pocketdisco_backend",
                "\t}",
                "",
            )
        )
    return (
        TEMPLATE.read_text(encoding="utf-8")
        .replace("@PUBLIC_IP@", address)
        .replace("@ACME_DIRECTORY@", DIRECTORIES[acme])
        .replace("\t@ROOM_ROUTE@\n", rooms)
    )


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--public-ip", required=True)
    parser.add_argument("--tester-ip", action="append", default=[])
    parser.add_argument("--acme", choices=tuple(DIRECTORIES), default="staging")
    args = parser.parse_args(argv)
    try:
        config = render(args.public_ip, args.tester_ip, args.acme)
    except ValueError as error:
        parser.error(str(error))
    except OSError:
        parser.error("Could not read the configuration template")
    sys.stdout.write(config)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
