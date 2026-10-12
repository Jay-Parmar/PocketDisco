# Backend hosting decision

## Selected: owner-managed server

The owner has authorized SSH deployment to their existing server instead of
Render, AWS, or another hosting provider. Retain FastAPI, PostgreSQL, and Redis;
the Valkey proposal below is not adopted. No hosting purchase is required.

## Private deployment checkpoint: 2026-10-08

SSH access returned after the owner's internet outage. Runtime commit
`6a63a4bdf45cba53d4fb399246d8f497af531c3c` is installed on the Ubuntu 24.04 host.
The [native deployment runbook](../infra/self-hosted/README.md) records the setup.

| Service | Loopback port | Isolation |
|---|---|---|
| FastAPI | 18080 | Dedicated runtime user and read-only release |
| PostgreSQL 16.15 | 55432 | Separate cluster, data directory and database roles |
| Redis 7.4.11 | 56379 | Separate process, restricted ACL and no eviction |

The host already had PostgreSQL 16 binaries. Reusing them for a separate cluster
avoids replacing or restarting its existing database. CI now covers PostgreSQL
16 and 17. Redis was built from checksum-verified source. No host packages,
firewall rules, Tailscale routes, or existing application configs were changed.
Existing backend and database process IDs remained unchanged. No reboot occurred.

All three PocketDisco units are enabled for boot and supervised by systemd.
Their connection, memory, CPU and shutdown limits are explicit. Credentials
were generated on the server in root-only environment files, not saved in Git.
Runtime and migration processes have separate OS and database identities.
Permission checks confirmed that the API cannot read migration credentials,
database files, or backups, and cannot create schemas, temporary tables or roles.

Verified:

- [API CI](https://github.com/Jay-Parmar/PocketDisco/actions/runs/37667228118):
  116 tests pass in each Python/PostgreSQL matrix job, including real Redis.
  Local Windows: 108 pass, eight integration tests skipped.
- [Deployment-tools CI](https://github.com/Jay-Parmar/PocketDisco/actions/runs/37667228146):
  installer, smoke, backup and device-harness tests pass. All 17 installer tests
  also pass as root on the actual server, including release-symlink checks.
- Migration `0001_private_rooms` and Alembic schema check pass.
- Nine [remote room-control checks](../tools/remote_api_smoke/README.md) pass
  through an encrypted SSH tunnel: health, guests, join, presence, readiness,
  chat, reconnect, single-use tickets and leave.
- Restarting only the API preserved authentication, room state and committed
  chat. A new socket restored the snapshot and accepted another chat message.
- A [backup restore drill](../tools/postgres_backup/README.md) restored a custom
  dump into a fresh scratch database, verified the revision and table readability,
  then removed that scratch database. The root-only archive remains on the host.
  This does not verify off-site recovery or ownership/ACL reconstruction.

The initial remote device run was blocked by another test session using both
devices. After the owner confirmed an exclusive slot on 2026-10-08, all eight
room/chat UI checks passed on the phone and emulator with the new local-playback
build. Create/join, presence, readiness, chat, foreground recovery, process
recovery and offline saved-room retry were verified through the SSH tunnel.
This does not establish public HTTPS, separate-network behavior or audible sync.
No other app was stopped, reset or modified. The
[mobile checkpoint](10-mobile-checkpoint.md) records the additional playback run.

Sanitized reports are under `.local-tools/remote-api-smoke/`,
`.local-tools/remote-api-restart.json`, `.local-tools/mobile-e2e-server/` and
`.local-tools/mobile-e2e-playback-20261008/`.
Screenshots may contain synthetic invites and are not committed.

## Connection recovery: 2026-10-12

The API and both datastores are still healthy on the installed runtime above.
The API has reported no automatic restarts since its October 8 start. Backend
code has not changed since that deployment, so no reinstall, migration or
datastore restart was needed.

The internal app was pointing at device loopback while the development tunnel
and ADB reverse mappings were stopped. Restoring an SSH forward from PC
`127.0.0.1:18080` to server `127.0.0.1:18080`, then mapping each selected
device's port 8000 to PC port 18080, restored the private connection. Nothing
and the emulator resumed their existing server rooms without clearing data.
This route depends on the PC, USB/ADB and the SSH process staying connected.
It is not the public endpoint or an offline backend.

All nine remote room checks passed again. The sanitized report is
`.local-tools/remote-api-smoke/deployment-20261012-1.json`. Local API regression
tests passed 108 checks with eight real-datastore tests skipped. The remote
smoke exercises the deployed PostgreSQL and Redis, but is not a substitute for
the full integration suite.

The router serving `satark` should forward TCP 80 and 443 to its verified LAN
address, `192.168.5.187`, on the same destination ports. Reserve that address.
Do not forward port 18080, SSH or datastore ports. No listener was present on
80 or 443 at this check; forwarding alone does not provide HTTPS.

An owned hostname remains preferable for a stable app endpoint, but is not
mandatory for a static public IP. Let's Encrypt now supports IPv4 and IPv6
certificates with a 160-hour lifetime and the `shortlived` ACME profile.
An IP endpoint therefore needs tested automatic renewal and certificate-expiry
monitoring before use. This updates the earlier hostname-only ingress gate,
not the control-plane architecture. See the
[official January 2026 announcement](https://letsencrypt.org/2026/01/15/6day-and-ip-general-availability).
The observed outbound address is not proof that incoming router traffic reaches
this server. Public forwarding, certificate issuance and independent-network
phone access remain unverified.

The [restricted HTTPS preparation](../infra/self-hosted/https/README.md) uses
pinned Caddy 2.11.7, isolated service state, exact tester-IP admission and
request-free logs. Its 18 generator tests and all four health-only/allowlisted,
staging/production configuration variants pass local parser checks with the
checksum-verified binary. This is preparation only: no proxy service, certificate,
trusted-proxy override or public mobile endpoint has been installed.

## Restricted external ports: 2026-10-12

The owner reports forwarding public ports 50080 and 50443. Public 80 and 443
cannot be forwarded. This supersedes the standard-port activation request
above; do not keep requesting those ports or expose the raw API as a fallback.
The exact LAN destination ports and router WAN address still need confirmation.
Router rules target the server's LAN address, not its public address.

At 15:54 UTC the server still had LAN address `192.168.5.187`, outbound public
address `117.252.78.56`, and a healthy API on `127.0.0.1:18080`. All three
PocketDisco units were active with zero restarts. There were no listeners on
80, 443, 50080 or 50443. TCP probes from the development PC to the two public
high ports did not connect. With no ingress listener, this does not establish
whether the router rules are correct. No services or firewall rules were changed.

HTTPS/WSS can use port 50443, but the prepared IP-certificate flow cannot
validate through these high ports: Let's Encrypt HTTP-01 requires public 80,
TLS-ALPN-01 requires public 443, and DNS-01 cannot validate IP addresses.
Use an owner-controlled hostname with DNS-01 and automated renewal instead.
The intended app origin is `https://HOSTNAME:50443`, once DNS, certificate and
external access are verified. A certificate is for the hostname, not the port.
See [challenge requirements](https://letsencrypt.org/docs/challenge-types/)
and [Caddy DNS validation](https://caddyserver.com/docs/automatic-https#dns-challenge).

Next input is a hostname and DNS provider, not a password or API key in chat.
Review scoped DNS credentials and renewal support before choosing an ACME
integration. The existing unmodified-Caddy template supports neither this DNS
flow nor an activated public endpoint yet. Do not deploy it unchanged, disable
certificate checks, install a test CA on phones, or move guest traffic to HTTP.
Port 50080 is not needed for DNS-01 and must not serve API credentials or chat.

## Before public access

1. Confirm the public router can reach the Linux server. The development PC and
   server currently use different LAN subnets; forwarding to the wrong one will
   not expose this backend. Do not install an unapproved relay on the PC.
2. Under the current high-port constraint, select an owner-controlled hostname
   and DNS provider, then configure DNS-01 renewal and HTTPS/WSS on public
   port 50443. Confirm which LAN port that rule reaches before binding ingress.
   Never forward SSH, either datastore, or the internal API port.
3. Set proxy body/header timeouts, connection limits and token-safe logs. Trust
   only the actual proxy peer. The API currently trusts no forwarded headers.
4. Restrict beta access before opening guest registration. Add measured load
   tests, monitored backups, encrypted off-host storage, retention and recovery.
5. Coordinate the host's reported pending security updates and required restart
   with its owner. They were not applied during this isolated deployment.
6. Complete the remaining mobile, playback, privacy and Play release gates.

The hostname currently resolves to a Tailscale address. That address can be
stable without being public. [Tailscale documents this distinction](https://tailscale.com/docs/concepts/tailscale-ip-addresses).
The public static address and HTTPS route still need verification; ordinary
Play users must not need access to the owner's private tailnet.

## Earlier Render proposal, not selected

The following comparison was checked on 2026-10-07 and is retained as background.
No cloud resources, billing accounts, domains, or DNS records were created.

### Previous suggested baseline

Render, with the API and both datastores in Singapore. This is a starting-region
choice for an India-first test group, not a measured latency claim. Keep the
existing FastAPI, PostgreSQL, and Redis-protocol design. Render supports
[WebSockets](https://render.com/docs/websocket) and
[same-region private networking](https://render.com/docs/regions).

Small closed-beta baseline:

| Component | Starting size | Monthly compute |
|---|---|---:|
| FastAPI web service | 0.5 CPU, 512 MB | $7 |
| Managed PostgreSQL | 0.1 CPU, 256 MB | $6 |
| Redis-compatible Key Value | 256 MB | $10 |
| Privacy/support static pages | Static site | $0 |

About $23/month on the $0 Hobby workspace plan, before tax, storage growth,
bandwidth, build-minute overages, a domain, or any later worker. Treat $30-40 as
an initial planning budget, not a hard cap or a capacity guarantee. The provider's
[current price list](https://render.com/pricing) and
[published component example](https://render.com/articles/production-rails-hosting-guide)
support these rates. Load-test before choosing public-launch sizes. A paid
workspace upgrade is a separate charge, not included here.

Do not use free database or sleeping web-service tiers for production. Render
[describes those limitations](https://render.com/docs/free). Paid Postgres has
[point-in-time recovery](https://render.com/docs/postgresql-backups), with a
three-day recovery window on Hobby. Test a restore before launch.

### Explicit compatibility gate

New Render Key Value instances run
[Valkey 8](https://render.com/docs/key-value), not Redis 7.4. Our integration CI
currently tests Redis 7.4. Before adopting Render, run the same ticket, Lua,
expiry, rate-limit, Pub/Sub, and reconnect tests against Valkey 8 and a staging
instance. Configure no eviction for control-state keys and size connection
pools within provider limits. This is a proposed provider choice, not a silent
change to the architecture decision.

### Domains and media

Use the assigned `onrender.com` HTTPS address for the first remote test. A
domain purchase is not needed to start. Later attach `api.<owner-domain>` and
public privacy/support pages. Render supplies
[managed TLS](https://render.com/docs/tls); no certificate purchase is needed.
Domain availability and ownership are not yet checked.

Bundle the small reviewed test catalog for the first playback build. Each
phone reads its own copy. The API carries room commands and chat, not audio.
A separate media origin/CDN can be considered when catalog size requires it.

### Work after approval

1. Add a deployment branch with a container, Render configuration, production
   bind/port settings, migration step, health checks, and rollback instructions.
2. Use private datastore connections, provider-managed secrets, safe proxy-IP
   handling, and logs that omit tokens, tickets, invites, and chat bodies.
3. Deploy only reviewed commits. Keep automatic production deploys disabled
   initially. Set usage alerts and measure memory, connections, and bandwidth.
4. Verify internet room flows on separate networks without ADB reverse, restart
   recovery, backup restoration, and a measured load test.
5. Finish deletion/retention/moderation, privacy/Data Safety, playback gates,
   native compatibility, signing, and Play testing before public release.

Railway is a reasonable alternative if usage-based billing is preferred. Its
[Hobby $5 and Pro $20 minimums](https://railway.com/pricing) include that amount
of resource use, not unlimited hosting. Render is preferred here for a simple
managed-database baseline with explicit per-service pricing.

The managed-hosting proposal is superseded by the owner's server selection.
It does not authorize purchases, domain registration, or app publication.
