# Deployment proposal

Checked 2026-10-07. Proposal only, awaiting owner approval. No cloud resources,
billing accounts, domains, or DNS records have been created or changed.

## Recommended starting point

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

Owner approval needed: hosting provider, initial budget, and who owns the
hosting account. Approval of this proposal is not permission to purchase a
domain or publish the app.
