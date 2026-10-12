# Restricted HTTPS preparation

This is a configuration generator and a proposed service, not an installer.
Nothing here changes the private backend, router, firewall or mobile endpoint.
Keep the [private deployment gates](../../../docs/11-deployment-proposal.md) open
until the corresponding checks have actually passed.

The owner now allows only public 50080 and 50443. The IP/HTTP-01 template below
cannot obtain or renew certificates through those ports. Keep it uninstalled;
the next path needs an owner-controlled hostname and DNS-01 automation, with
the app using `https://HOSTNAME:50443`. Exact forwarding targets remain
unconfirmed. See the [updated port constraint](../../../docs/11-deployment-proposal.md#restricted-external-ports-2026-10-12).

## Certificate and binary

A domain is useful for a stable app address, but is not required. Let's Encrypt
made public IPv4/IPv6 certificates generally available on January 15, 2026.
They last 160 hours and require the `shortlived` ACME profile. IP validation uses
HTTP-01 or TLS-ALPN-01, not DNS-01. This template selects HTTP-01 on TCP 80.
See [the announcement](https://letsencrypt.org/2026/01/15/6day-and-ip-general-availability)
and [IP validation requirements](https://letsencrypt.org/2025/07/01/issuing-our-first-ip-address-certificate).

Use the unmodified **Caddy 2.11.7** Linux amd64 release only after confirming
the server architecture. It includes fixes for the previous release's idle
timeout regressions. Do not install an unverified distro package or run an
unattended upgrade. The official
[release asset](https://github.com/caddyserver/caddy/releases/expanded_assets/v2.11.7)
is `caddy_2.11.7_linux_amd64.tar.gz`, with SHA-256:

```text
727b91701a392de6ebc5027509f548bf39979e5216340d0faed8fa5e69c84f8b
```

Check the downloaded archive against that digest before extracting or running
it. Keep the upstream signature and provenance with the staged artifact. The
[release notes](https://github.com/caddyserver/caddy/releases/tag/v2.11.7) and
[pinned certificate code](https://github.com/caddyserver/certmagic/blob/v0.25.6/acmeissuer.go)
are the version references. No custom Caddy plugins are needed.

## Generate and review

Replace the placeholders with verified addresses. The generator prints only
configuration to stdout; it has no output-path, installation or network option.

```text
python infra/self-hosted/https/generate.py --public-ip PUBLIC_IPV4
python infra/self-hosted/https/generate.py --public-ip PUBLIC_IPV4 --acme production --tester-ip TESTER_PUBLIC_IP
```

The default is ACME staging and public `GET /healthz` only. All other application
paths return 404. Repeated `--tester-ip` options admit `/v1/*` only from those
actual connecting addresses, up to 16 entries. CIDRs, wildcards, hostnames,
private addresses and configuration fragments are rejected. Use each tester's
observed public egress address, not their phone's LAN address. Carrier NAT may
share that address with other people; this is a small, temporary beta boundary,
not account-level admission. Do not remove the allowlist to open registration.

The listener is fixed to the reviewed server LAN address `192.168.5.187` and
the upstream to `127.0.0.1:18080`. Confirm a reservation for that LAN address.
The observed outbound address `117.252.78.56` is only a candidate until the
owner confirms the router WAN address and an external reachability check.
Double NAT or CGNAT can prevent ingress even when an outbound address exists.

The explicit ACME issuer avoids Caddy's default private CA for IP sites.
`default_sni` selects the public IP certificate for clients that omit SNI,
including IP connections behind NAT. HTTP serves no API or redirects; Caddy
handles its ACME challenge before the 404 route. Only TCP 80 and 443 are needed.
HTTP/3 is disabled. Never forward 18080, 55432, 56379, SSH or the admin socket.
See [TLS configuration](https://caddyserver.com/docs/caddyfile/directives/tls),
[default SNI](https://caddyserver.com/docs/caddyfile/options#default_sni) and the
[pinned challenge handler](https://github.com/caddyserver/caddy/blob/v2.11.7/modules/caddyhttp/server.go).

## Limits and logs

The proxy retains the backend's 64-request budget, limits bodies to 8 KiB and
headers to 16 KiB, and bounds header/body reads and upstream connection/header
waits. It does not replay failed requests. WebSocket upgrades are native; no
short total stream timeout is imposed. The four-second upstream keepalive is
below Uvicorn's five-second default. These limits are not load-test evidence or
protection against a saturated internet link. See [proxy settings](https://caddyserver.com/docs/caddyfile/directives/reverse_proxy)
and [body limits](https://caddyserver.com/docs/caddyfile/directives/request_body).

There is no access logger. The default sink excludes `http` and `admin.api`:
turning off access logs alone still leaves request-bearing proxy error logs.
Certificate and service diagnostics remain. Do not enable debug, trace,
`log_credentials`, raw request logs or externally exposed metrics. The
[pinned HTTP logger](https://github.com/caddyserver/caddy/blob/v2.11.7/modules/caddyhttp/server.go)
and [namespace filtering](https://github.com/caddyserver/caddy/blob/v2.11.7/logging.go)
explain this choice. Verify it with synthetic failure requests before promotion.

## Private staging, then activation

1. Keep the router closed while reviewing. Check architecture, port ownership,
   existing units/drop-ins/users and root ownership of every target parent.
   Refuse collisions or writable/symlinked release/config paths. Do not replace
   an existing Caddy installation or change another application's service.
2. Stage the verified binary at `/opt/pocketdisco/bin/caddy-2.11.7`, root-owned
   mode 0755. Create a dedicated nonlogin `pocketdisco-proxy` user/group with
   home `/var/lib/pocketdisco-proxy`. The service creates its state/runtime
   directories with mode 0700. It has only the bind-port capability and cannot
   read the API credentials or data directory. Outbound DNS/ACME is required;
   do not copy the API's loopback-only network sandbox onto this unit.
3. Save the reviewed, health-only staging output as
   `/etc/pocketdisco/ingress/Caddyfile`, root-owned, group `pocketdisco-proxy`,
   mode 0640. Its new parent directory is root-owned, the same group, mode 0750.
   The existing setup creates `/etc/pocketdisco` as root-owned mode 0711.
   Verify the live parent permissions with `namei -l`, then check readability
   as `pocketdisco-proxy`. Do not widen permissions on existing secret files or
   assume that a readable leaf can be reached through every parent directory.
   Stage `pocketdisco-proxy.service` under `/etc/systemd/system`, root-owned
   mode 0644. No existing environment file is read or changed by this generator.
4. With the pinned binary, run the following checks. `validate` checks syntax
   and provisions modules; it may create proxy state. It does not prove external
   reachability or certificate issuance. Prepare the dedicated state directory
   first and validate as the service user, never as root.

   ```sh
   namei -l /etc/pocketdisco/ingress/Caddyfile
   sudo -u pocketdisco-proxy test -r /etc/pocketdisco/ingress/Caddyfile
   sudo install -d -o pocketdisco-proxy -g pocketdisco-proxy -m 0700 /var/lib/pocketdisco-proxy
   sudo -u pocketdisco-proxy /opt/pocketdisco/bin/caddy-2.11.7 validate --config /etc/pocketdisco/ingress/Caddyfile --adapter caddyfile
   systemd-analyze verify /etc/systemd/system/pocketdisco-proxy.service
   curl --fail --silent --show-error --max-time 7 http://127.0.0.1:18080/healthz
   ```

5. After owner confirmation, forward router TCP 80 to `192.168.5.187:80` and
   TCP 443 to `192.168.5.187:443`. Review the host firewall separately. Start
   only the new proxy: `systemctl daemon-reload`, then
   `systemctl start pocketdisco-proxy.service`. Check staging issuance from an
   independent network. Staging certificates are not publicly trusted; do not
   install a test CA on the phones or call a `--insecure` result a pass.
6. Regenerate health-only configuration with `--acme production`, review and
   validate it, then `systemctl reload pocketdisco-proxy.service`. Check normal
   certificate verification, the IP SAN, validity period and `/healthz` from
   outside the LAN. Test an SNI-less connection too. Configure certificate
   expiry/failure monitoring before relying on six-day renewal; alert with at
   least 48 hours remaining. Retain the private TLS state across restarts.
7. Before admitting testers, apply the proxy-trust step below, then explicitly
   regenerate with their addresses. Test denied clients with forged forwarding
   headers, oversized bodies, HTTP rejection, room HTTP/WSS, reconnect and
   redacted failure logs. Run the remote smoke and Android checks without SSH
   tunnels or ADB reverse. Only then enable the new unit for boot.

The [Caddy service guidance](https://caddyserver.com/docs/running) covers reloads.
Reloading can close existing WebSockets, so check room reconnect afterward.
Local generator tests do not validate the Caddy binary, systemd sandbox,
certificate renewal, actual header handling or public networking.

## Trust only the local proxy

The existing `api.env` already contains an empty `POCKETDISCO_TRUSTED_PROXY_IPS`.
An `Environment=` line in a systemd drop-in would not override it. Install
`proxy.env.example` as `/etc/pocketdisco/ingress/proxy.env`, root-owned mode 0600,
and `pocketdisco-api-https.conf` as
`/etc/systemd/system/pocketdisco-api.service.d/https-proxy.conf`, root-owned mode
0644. Refuse an existing file at either new target. The additional environment
file must follow `/etc/pocketdisco/api.env`; do not reset that existing list.

```sh
systemctl daemon-reload
systemctl show pocketdisco-api.service --property=EnvironmentFiles
systemctl restart pocketdisco-api.service
curl --fail --silent --show-error --max-time 7 http://127.0.0.1:18080/healthz
```

Inspect only the file ordering, never the full environment or credential file.
[systemd's precedence rules](https://github.com/systemd/systemd/blob/v255/man/systemd.exec.xml)
make the later environment file win. Only `127.0.0.1` is trusted; Caddy connects
to the explicit IPv4 loopback upstream and replaces forwarding headers with
the actual peer address. Caddy trusts no incoming proxies, and its tester
matcher uses `remote_ip`, not headers. Do not widen trust to a LAN or `*`.
Processes already running on this server remain inside this loopback trust
boundary. See [Uvicorn's proxy rules](https://uvicorn.dev/deployment/#proxies-and-forwarded-headers).
No API code, database migration or datastore restart is needed.

## Rollback

Remove the two router forwards and stop/disable only
`pocketdisco-proxy.service`. Keep its certificate state private for recovery.
Rename only the newly installed `https-proxy.conf` to
`https-proxy.conf.disabled`, run `systemctl daemon-reload`, restart only
`pocketdisco-api.service`, and verify its loopback health. This restores the
original empty proxy trust without opening or editing `api.env`. Leave all
other drop-ins, services, credentials and data untouched. For a config-only
rollback, restore the reviewed health-only Caddyfile and reload the proxy.

## Local and CI checks

```text
python -m unittest discover -s infra/self-hosted/https/tests -v
python -m ruff check infra/self-hosted/https
python -m ruff format --check infra/self-hosted/https
```

CI must invoke this discovery path explicitly; the existing discovery under
`infra/self-hosted/tests` does not include this sibling directory. Tests cover
validation, fail-closed route generation, limits, log configuration and service
boundaries without network, root privileges, certificates or a Caddy install.
HTTPS alone does not complete beta admission, deletion/retention, backups,
moderation, Play signing or the other release gates.
