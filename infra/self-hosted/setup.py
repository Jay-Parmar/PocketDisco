"""First installation and reviewed migrations on Ubuntu 24.04."""

import argparse
import hashlib
import os
import re
import secrets
import socket
import subprocess
import sys
import time
from pathlib import Path

SOURCE = Path(__file__).resolve().parent
CURRENT = Path("/opt/pocketdisco/current")
CONFIG = Path("/etc/pocketdisco")
DATA = Path("/var/lib/pocketdisco")
PG_BIN = Path("/usr/lib/postgresql/16/bin")
UNITS = Path("/etc/systemd/system")
USERS = ("pocketdisco", "pocketdisco-db", "pocketdisco-redis", "pocketdisco-migrate")
SERVICES = tuple(f"pocketdisco-{name}.service" for name in ("api", "postgres", "redis"))
CLEAN_ENV = {"PATH": "/usr/sbin:/usr/bin:/sbin:/bin", "LANG": "C.UTF-8"}
POST_MIGRATION_SQL = """\
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO pocketdisco_runtime;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO pocketdisco_runtime;
REVOKE INSERT, UPDATE, DELETE ON public.alembic_version FROM pocketdisco_runtime;
"""


def checked_passwords(*values):
    if any(re.fullmatch(r"[a-f0-9]{64}", value) is None for value in values):
        raise ValueError("Expected generated hexadecimal credentials")


def secret_files(runtime, migration, redis):
    checked_passwords(runtime, migration, redis)
    prefix = "POCKETDISCO_MODE=production\n"
    api = (
        prefix + f"POCKETDISCO_DATABASE_URL=postgresql+asyncpg://pocketdisco_runtime:{runtime}"
        "@127.0.0.1:55432/pocketdisco\n"
        + f"POCKETDISCO_REDIS_URL=redis://pocketdisco:{redis}@127.0.0.1:56379/0\n"
        "POCKETDISCO_BIND_HOST=127.0.0.1\n"
        "POCKETDISCO_PORT=18080\n"
        "POCKETDISCO_TRUSTED_PROXY_IPS=\n"
        "POCKETDISCO_CONCURRENCY_LIMIT=64\n"
        "POCKETDISCO_SHUTDOWN_SECONDS=15\n"
    )
    migrate = (
        prefix + f"POCKETDISCO_DATABASE_URL=postgresql+asyncpg://pocketdisco_migrate:{migration}"
        "@127.0.0.1:55432/pocketdisco\n"
        "POCKETDISCO_REDIS_URL=redis://127.0.0.1:56379/0\n"
    )
    digest = hashlib.sha256(redis.encode()).hexdigest()
    acl = (
        "user default off\n"
        f"user pocketdisco on #{digest} ~pocketdisco:v1:* &pocketdisco:v1:* -@all "
        "+auth +hello +ping +quit +reset +client|setinfo +client|setname "
        "+set +getdel +incr +expire +eval +zremrangebyscore +zrange +zscore "
        "+smembers +srem +zadd +multi +exec +discard +zrem +sadd "
        "+publish +subscribe +unsubscribe\n"
    )
    return {"api.env": api, "migrate.env": migrate, "redis.acl": acl}


def bootstrap_sql(runtime, migration):
    checked_passwords(runtime, migration)
    return f"""\
CREATE ROLE pocketdisco_migrate LOGIN PASSWORD '{migration}'
    NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS CONNECTION LIMIT 5;
CREATE ROLE pocketdisco_runtime LOGIN PASSWORD '{runtime}'
    NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS CONNECTION LIMIT 12;
ALTER ROLE pocketdisco_migrate SET statement_timeout = '60s';
ALTER ROLE pocketdisco_migrate SET lock_timeout = '5s';
CREATE DATABASE pocketdisco OWNER pocketdisco_migrate;
REVOKE ALL ON DATABASE pocketdisco FROM PUBLIC;
REVOKE ALL ON DATABASE postgres FROM PUBLIC;
REVOKE ALL ON DATABASE template1 FROM PUBLIC;
GRANT CONNECT ON DATABASE pocketdisco TO pocketdisco_runtime;
\\connect pocketdisco
REVOKE ALL ON SCHEMA public FROM PUBLIC;
GRANT USAGE ON SCHEMA public TO pocketdisco_runtime;
ALTER DEFAULT PRIVILEGES FOR ROLE pocketdisco_migrate IN SCHEMA public
    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO pocketdisco_runtime;
ALTER DEFAULT PRIVILEGES FOR ROLE pocketdisco_migrate IN SCHEMA public
    GRANT USAGE, SELECT ON SEQUENCES TO pocketdisco_runtime;
"""


def run(command, label, **kwargs):
    options = {"env": CLEAN_ENV, "timeout": 120, "text": True}
    options.update(kwargs)
    try:
        result = subprocess.run(
            command, check=False, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, **options
        )
    except (OSError, subprocess.TimeoutExpired):
        raise RuntimeError(f"{label} failed; command output suppressed") from None
    if result.returncode:
        raise RuntimeError(f"{label} failed; command output suppressed")


def refuse_existing(paths):
    for path in paths:
        if path.exists() or path.is_symlink():
            raise RuntimeError(f"Refusing existing path: {path}")


def write_new(path, text, mode, uid=0, gid=0):
    flags = os.O_WRONLY | os.O_CREAT | os.O_EXCL | getattr(os, "O_NOFOLLOW", 0)
    descriptor = os.open(path, flags, mode)
    with os.fdopen(descriptor, "w", encoding="utf-8", newline="\n") as stream:
        os.fchown(stream.fileno(), uid, gid)
        os.fchmod(stream.fileno(), mode)
        stream.write(text)


def require_root():
    if sys.platform != "linux" or os.geteuid() != 0:
        raise RuntimeError("Run as root on the reviewed Ubuntu host")


def require_root_owned(path):
    owner = path.lstat()
    target = path.stat()
    if owner.st_uid != 0 or target.st_uid != 0 or target.st_mode & 0o022:
        raise RuntimeError(f"Expected root ownership without shared write access: {path}")


def check_release():
    release = CURRENT.resolve(strict=True)
    if not CURRENT.is_symlink() or not release.is_relative_to(Path("/opt/pocketdisco/releases")):
        raise RuntimeError("current must point inside /opt/pocketdisco/releases")
    for directory, children, files in os.walk(release):
        for path in (Path(directory), *(Path(directory) / name for name in children + files)):
            require_root_owned(path)
            if path.is_symlink():
                target = path.resolve(strict=True)
                if not target.is_relative_to(release) and target != Path("/usr/bin/python3.12"):
                    raise RuntimeError("Release symlink leaves the release or reviewed interpreter")
    for path in (
        Path("/opt/pocketdisco"),
        Path("/opt/pocketdisco/releases"),
        CURRENT,
        CURRENT / ".venv/bin/python",
        CURRENT / "services/api/alembic.ini",
        Path("/opt/pocketdisco/bin/redis-server"),
    ):
        for ancestor in (path, *path.parents, *path.resolve(strict=True).parents):
            require_root_owned(ancestor)


def check_install():
    require_root()

    import grp
    import pwd

    check_release()
    refuse_existing([CONFIG, DATA, Path("/run/pocketdisco-postgres")])
    for directory in (UNITS, Path("/run/systemd/system"), Path("/usr/lib/systemd/system")):
        refuse_existing([directory / name for name in SERVICES])
        refuse_existing([directory / (name + ".d") for name in SERVICES])
    for user in USERS:
        for lookup in (pwd.getpwnam, grp.getgrnam):
            try:
                lookup(user)
            except KeyError:
                continue
            raise RuntimeError(f"Refusing existing user or group: {user}")
    for binary in ("initdb", "postgres", "psql", "pg_isready"):
        require_root_owned(PG_BIN / binary)
    for port in (18080, 55432, 56379):
        with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as listener:
            try:
                listener.bind(("127.0.0.1", port))
            except OSError:
                raise RuntimeError(f"Required loopback port unavailable: {port}") from None


def admin_sql(sql, database="postgres"):
    run(
        [
            "runuser",
            "-u",
            "pocketdisco-db",
            "--",
            str(PG_BIN / "psql"),
            "-X",
            "--no-password",
            "-q",
            "-v",
            "ON_ERROR_STOP=1",
            "-h",
            "/run/pocketdisco-postgres",
            "-p",
            "55432",
            "-d",
            database,
        ],
        "database setup",
        input=sql,
    )


def install():
    import pwd

    check_install()
    os.umask(0o077)
    for user in USERS:
        run(
            [
                "useradd",
                "--system",
                "--user-group",
                "--home-dir",
                "/nonexistent",
                "--no-create-home",
                "--shell",
                "/usr/sbin/nologin",
                user,
            ],
            "service user creation",
        )
    db = pwd.getpwnam("pocketdisco-db")
    redis = pwd.getpwnam("pocketdisco-redis")
    for directory, mode, uid, gid in (
        (CONFIG, 0o711, 0, 0),
        (DATA, 0o711, 0, 0),
        (DATA / "postgres", 0o700, db.pw_uid, db.pw_gid),
        (DATA / "redis", 0o700, redis.pw_uid, redis.pw_gid),
        (DATA / "backups", 0o700, 0, 0),
    ):
        directory.mkdir(mode=mode)
        os.chown(directory, uid, gid)
        directory.chmod(mode)
    passwords = [secrets.token_hex(32) for _ in range(3)]
    for name, contents in secret_files(*passwords).items():
        group = redis.pw_gid if name == "redis.acl" else 0
        write_new(CONFIG / name, contents, 0o640 if group else 0o600, gid=group)
    for name in ("postgres.conf", "pg_hba.conf", "redis.conf"):
        group = redis.pw_gid if name == "redis.conf" else db.pw_gid
        write_new(CONFIG / name, (SOURCE / name).read_text(), 0o640, gid=group)
    run(
        [
            "runuser",
            "-u",
            "pocketdisco-db",
            "--",
            str(PG_BIN / "initdb"),
            "-D",
            str(DATA / "postgres"),
            "--auth-local=peer",
            "--auth-host=scram-sha-256",
            "--encoding=UTF8",
            "--no-locale",
            "--username=pocketdisco-db",
            "--data-checksums",
        ],
        "cluster initialization",
    )
    for name in SERVICES:
        write_new(UNITS / name, (SOURCE / name).read_text(), 0o644)
    run(["systemctl", "daemon-reload"], "unit discovery")
    run(
        ["systemctl", "start", "pocketdisco-postgres.service", "pocketdisco-redis.service"],
        "private datastores start",
    )
    for attempt in range(30):
        try:
            admin_sql("SELECT 1;")
            break
        except RuntimeError:
            if attempt == 29:
                raise RuntimeError("Isolated database did not become ready") from None
            time.sleep(0.5)
    admin_sql(bootstrap_sql(passwords[0], passwords[1]))
    print("Private datastores installed. Run migrate before starting the API.")


def migrate():
    require_root()

    import pwd

    check_release()
    path = CONFIG / "migrate.env"
    require_root_owned(path)
    if path.is_symlink() or path.stat().st_mode & 0o077:
        raise RuntimeError("Migration environment must be a root-only regular file")
    environment = dict(CLEAN_ENV, PYTHONDONTWRITEBYTECODE="1", PYTHONUNBUFFERED="1")
    environment.update(line.split("=", 1) for line in path.read_text().splitlines())
    account = pwd.getpwnam("pocketdisco-migrate")
    for command in (("upgrade", "head"), ("check",)):
        run(
            [str(CURRENT / ".venv/bin/python"), "-m", "alembic", *command],
            "schema migration",
            cwd=CURRENT / "services/api",
            env=environment,
            user=account.pw_uid,
            group=account.pw_gid,
            extra_groups=[],
        )
    admin_sql(POST_MIGRATION_SQL, "pocketdisco")
    print("Schema migration and runtime grants verified.")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=("check", "install", "migrate"))
    arguments = parser.parse_args()
    try:
        {"check": check_install, "install": install, "migrate": migrate}[arguments.action]()
    except RuntimeError as error:
        print(str(error), file=sys.stderr)
        return 1
    except (OSError, ValueError):
        print(
            "Setup stopped. Inspect prerequisites and named service status; secrets suppressed.",
            file=sys.stderr,
        )
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
