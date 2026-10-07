# Backend hosting decision

## Selected: owner-managed server

The owner has authorized SSH deployment to their existing server instead of
Render, AWS, or another hosting provider. Retain FastAPI, PostgreSQL, and Redis;
the Valkey proposal below is not adopted. No hosting purchase is required.

The first connectivity check reached no SSH authentication prompt. The local
Tailscale client is online, but the target is reported offline; TCP port 22 and
two Tailscale probes timed out. No remote files, services, firewall rules, or
databases were changed. Credentials are not saved in this repository.

The owner confirmed that the server's internet connection is down. Deployment
is paused until connectivity returns. No remote installation has started.

Local preparation hides input values from formatted settings-validation errors
so a bad connection URL does not print its credentials during startup. Three
regression cases failed before the fix and pass afterward. The local API suite
passes 62 tests; eight PostgreSQL/Redis integration tests are skipped locally.
Lint and formatting checks pass. This is not deployment or remote-test evidence.

Before deployment:

1. Restore SSH reachability and inspect the host OS, capacity, occupied ports,
   service manager, container runtime, and existing applications.
2. Isolate PocketDisco's processes, secrets, databases, and persistent storage.
   Do not replace another application's proxy configuration or shared services.
3. Configure HTTPS/WSS ingress and trust only the actual reverse proxy for
   client IPs. Preserve disabled access logs and the existing request/socket
   limits. Expose neither PostgreSQL nor Redis to the public internet.
4. Apply reviewed migrations, configure restart supervision, and verify health,
   room/chat/reconnect flows, and a backup restore before public use.

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

## Explicit compatibility gate

New Render Key Value instances run
[Valkey 8](https://render.com/docs/key-value), not Redis 7.4. Our integration CI
currently tests Redis 7.4. Before adopting Render, run the same ticket, Lua,
expiry, rate-limit, Pub/Sub, and reconnect tests against Valkey 8 and a staging
instance. Configure no eviction for control-state keys and size connection
pools within provider limits. This is a proposed provider choice, not a silent
change to the architecture decision.

## Domains and media

Use the assigned `onrender.com` HTTPS address for the first remote test. A
domain purchase is not needed to start. Later attach `api.<owner-domain>` and
public privacy/support pages. Render supplies
[managed TLS](https://render.com/docs/tls); no certificate purchase is needed.
Domain availability and ownership are not yet checked.

Bundle the small reviewed test catalog for the first playback build. Each
phone reads its own copy. The API carries room commands and chat, not audio.
A separate media origin/CDN can be considered when catalog size requires it.

## Work after approval

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
