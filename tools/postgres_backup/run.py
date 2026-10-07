"""Back up the private cluster and restore into a disposable database."""

import argparse
import hashlib
import json
import os
import re
import subprocess
import sys
from datetime import datetime, timezone
from pathlib import Path
from uuid import uuid4

BACKUPS = Path("/var/lib/pocketdisco/backups")
PG_BIN = Path("/usr/lib/postgresql/16/bin")
ENV = {"PATH": "/usr/sbin:/usr/bin:/sbin:/bin", "LANG": "C.UTF-8"}


def run_pg(binary, *arguments, **streams):
    options = {"stdout": subprocess.PIPE, "stderr": subprocess.DEVNULL, **streams}
    try:
        result = subprocess.run(
            [
                "runuser",
                "-u",
                "pocketdisco-db",
                "--",
                str(PG_BIN / binary),
                "-h",
                "/run/pocketdisco-postgres",
                "-p",
                "55432",
                "--no-password",
                *arguments,
            ],
            env=ENV,
            timeout=120,
            check=False,
            **options,
        )
    except (OSError, subprocess.TimeoutExpired):
        raise RuntimeError("Backup command failed; output suppressed") from None
    if result.returncode:
        raise RuntimeError("Backup command failed; output suppressed")
    return result.stdout


def remove_restore_database(name):
    if re.fullmatch(r"pocketdisco_restore_[a-f0-9]{32}_test", name) is None:
        raise ValueError("Refusing non-scratch database")
    run_pg("dropdb", name)


def preflight():
    if sys.platform != "linux" or os.geteuid() != 0:
        raise RuntimeError("Run as root on the PocketDisco server")
    for path in (BACKUPS, *BACKUPS.parents):
        info = path.stat()
        if path.is_symlink() or info.st_uid != 0 or info.st_mode & 0o022:
            raise RuntimeError(
                "Backup directory must be root-owned and not shared writable"
            )
    if BACKUPS.stat().st_mode & 0o077:
        raise RuntimeError("Backup directory must be private")
    identity = run_pg(
        "psql",
        "-XAt",
        "-d",
        "postgres",
        "-c",
        "SELECT current_user || '|' || current_setting('data_directory');",
    )
    if identity.strip() != b"pocketdisco-db|/var/lib/pocketdisco/postgres":
        raise RuntimeError("Unexpected database cluster")


def backup_and_verify(expected_revision):
    preflight()
    os.umask(0o077)
    suffix = uuid4().hex
    timestamp = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    archive = BACKUPS / f"pocketdisco-{timestamp}-{suffix[:8]}.dump"
    descriptor = os.open(
        archive, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600
    )
    with os.fdopen(descriptor, "wb") as output:
        run_pg(
            "pg_dump",
            "--format=custom",
            "--no-owner",
            "--no-acl",
            "-d",
            "pocketdisco",
            stdout=output,
        )
        output.flush()
        os.fsync(output.fileno())

    target = f"pocketdisco_restore_{suffix}_test"
    run_pg("createdb", "--template=template0", target)
    try:
        with archive.open("rb") as source:
            run_pg(
                "pg_restore",
                "--exit-on-error",
                "--no-owner",
                "--no-acl",
                "-d",
                target,
                stdin=source,
            )
        revision = run_pg(
            "psql",
            "-XAt",
            "-d",
            target,
            "-c",
            "SELECT version_num FROM alembic_version;",
        )
        if revision.decode().strip() != expected_revision:
            raise RuntimeError("Restored migration revision does not match")
        run_pg(
            "psql",
            "-XAt",
            "-v",
            "ON_ERROR_STOP=1",
            "-d",
            target,
            "-c",
            "SELECT count(*) FROM users; SELECT count(*) FROM rooms; "
            "SELECT count(*) FROM room_members; SELECT count(*) FROM chat_messages;",
        )
    finally:
        remove_restore_database(target)

    digest = hashlib.sha256()
    with archive.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    report = {
        "archive": archive.name,
        "bytes": archive.stat().st_size,
        "sha256": digest.hexdigest(),
        "migration_revision": expected_revision,
        "restore_verified": True,
        "scratch_database_removed": True,
    }
    with archive.with_suffix(".json").open("x", encoding="utf-8") as output:
        json.dump(report, output, indent=2)
        output.write("\n")
    print(json.dumps(report))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--expected-revision", required=True)
    args = parser.parse_args()
    if re.fullmatch(r"[A-Za-z0-9_]{1,32}", args.expected_revision) is None:
        parser.error("Invalid migration revision")
    try:
        backup_and_verify(args.expected_revision)
    except (RuntimeError, OSError, ValueError):
        print(
            "Backup verification failed. Keep the archive and inspect the private cluster.",
            file=sys.stderr,
        )
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
