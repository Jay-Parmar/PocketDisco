# Private backend installation

These files target the reviewed Ubuntu 24.04 host. They do not provision public
ingress, change a firewall, install packages, or touch the host's existing
PostgreSQL cluster. This is a private-beta setup, not a public-launch approval.

## Prerequisites

- Existing PostgreSQL 16 binaries under `/usr/lib/postgresql/16/bin`.
- Root-owned release under `/opt/pocketdisco/releases/<commit>`, with the API
  installed into its `.venv`; root-owned `current` symlink selects that release.
- Root-owned Redis 7.4.11 binary at `/opt/pocketdisco/bin/redis-server`.
  Build from the [official source](https://download.redis.io/releases/redis-7.4.11.tar.gz)
  only after checking SHA-256
  `3c266ece0abd54ed3b1c912c6eb86b7508cf382cb690ee6649d3843f018f6357`
  against [Redis release hashes](https://github.com/redis/redis-hashes/blob/master/README).
- No existing PocketDisco users, groups, configs, data, or named service units.
  Loopback ports 18080, 55432, and 56379 must be free.

Verify the entire release and binary tree is root-owned and not group/world
writable before running code as a service. Do not use editable pip installs
pointing into another user's checkout.

## First installation

Run from the staged release as root:

```sh
python3 infra/self-hosted/setup.py check
python3 infra/self-hosted/setup.py install
python3 infra/self-hosted/setup.py migrate
systemctl start pocketdisco-api.service
curl --fail http://127.0.0.1:18080/healthz
```

`install` generates credentials locally and starts only the dedicated datastores.
It refuses existing targets instead of overwriting them. If it stops midway,
inspect the named service status and created paths before any manual repair;
rerunning the installer is not an upgrade or recovery mechanism. It never deletes
data. Command errors suppress output because SQL or connection errors may contain
secrets. Do not run with shell tracing or print the generated environment files.

After migration, room/chat tests, restart recovery, and a backup restore check:

```sh
systemctl enable pocketdisco-postgres.service pocketdisco-redis.service pocketdisco-api.service
```

Enablement does not make the API public. All three processes bind loopback;
use a reviewed SSH tunnel for initial device testing. Never forward datastore
ports from the router. Public HTTPS/WSS ingress and its exact trusted proxy
address need a separate configuration review.

## Service boundaries

| Process | User | Port | Memory cap |
|---|---|---|---|
| API | `pocketdisco` | 18080 | 512 MB |
| Separate PostgreSQL cluster | `pocketdisco-db` | 55432 | 512 MB |
| Redis live state | `pocketdisco-redis` | 56379 | 256 MB |

The [systemd service sandbox](https://manpages.ubuntu.com/manpages/noble/man5/systemd.exec.5.html)
limits writes and privileges. These are initial small-beta budgets, not measured
capacity. Services have no Linux capabilities, no core dumps, finite process/file
limits, and loopback-only network access. The API cannot write its release tree.

`/etc/pocketdisco/api.env` and `migrate.env` are root-only. Systemd reads the API
environment before dropping privileges. Migrations run under a fourth nonlogin
OS user, `pocketdisco-migrate`, with a separate non-superuser database owner login.
That user cannot read the database files or use peer superuser authentication.
The runtime login
has CONNECT, schema USAGE and table DML, but no schema CREATE, database CREATE,
temporary objects, or role administration. The Alembic version marker is
read-only to the runtime login. Peer superuser access is limited to the dedicated
database OS user and its private socket directory.

PostgreSQL stores durable data under `/var/lib/pocketdisco/postgres`. Its
[logging settings](https://www.postgresql.org/docs/16/runtime-config-logging.html)
suppress statements, parameters, and error detail. Redis uses an
[ACL file](https://redis.io/docs/latest/operate/oss_and_stack/management/security/acl/)
with a password hash, prefix-scoped keys/channels and only required commands.
Redis does not persist ephemeral tickets/presence; restart invalidates them.
No-eviction mode fails requests if its memory budget fills.

`/var/lib/pocketdisco/backups` is root-only. A separate verified backup and restore
procedure is required before real user data. There is no scheduled backup or
retention job here. Keep an encrypted copy off this server before public use.

## Updates

Stage a new root-owned release and its own virtual environment. Back up first.
Stop only `pocketdisco-api.service`, switch `current`, run `setup.py migrate`,
and restart that API unit. Check health and room recovery. Do not automatically
roll back a schema migration; review whether the previous code supports it.
No command here stops or restarts another application's service.

## Local checks

```sh
python -m unittest discover -s infra/self-hosted/tests -v
```

These tests check configuration, secret handling, and refusal behavior. They do
not replace PostgreSQL/Redis integration tests or on-host systemd validation.
