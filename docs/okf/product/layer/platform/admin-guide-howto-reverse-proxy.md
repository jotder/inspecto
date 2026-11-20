---
type: How-to
title: "Put Inspecto behind a reverse proxy"
description: "Bind the server to loopback, front it with the deploy kit's Caddy or nginx template for HTTPS, fence /metrics and /health/details, and prove it with the preflight."
item: layer.platform
audience: external
status: current
verified: 2026-11-20 code-reviewed
code: [deploy/kit/proxy, deploy/kit/bin]
---

# Put Inspecto behind a reverse proxy

## Goal

Serve Inspecto over HTTPS on your own name through Caddy or nginx, with the server itself reachable only from the
same machine, and the monitoring endpoints closed to everyone but your monitoring network.

## Before you start

- **Edition:** every installed edition (a demo bundle has no deploy kit). On Basic, which has no sign-in, the proxy
  is the only fence.
- **Who:** an operator with administrator rights on the server.
- **What you need:** Caddy (Windows or Linux) or nginx (Linux; nginx for Windows is not supported), a certificate for
  your server's name (Caddy can obtain one), and Inspecto installed on the same machine
  ([Install an edition](admin-guide-howto-install-an-edition.md)).

## Steps

1. **Bind the server to loopback and trust the proxy.** Add to `INSPECTO_JAVA_OPTS`:

   ```text
   -Dcontrol.bind=127.0.0.1 -Dcontrol.trustedProxies=127.0.0.1
   ```

   Restart Inspecto. Trusting the proxy lets the audit trail and the per-address rate limit see the real caller.
2. **Copy a template** from the bundle's `deploy\proxy\` folder — `Caddyfile` or `nginx-inspecto.conf` — and edit the
   lines marked `EDIT`: your server's name, the certificate and key (nginx) or `tls` line (Caddy), the address of
   Inspecto (`127.0.0.1:8080`) and your monitoring network.

   Keep the rest: it redirects HTTP to HTTPS, streams answers unbuffered, accepts uploads up to 64 MB and refuses
   `/metrics` and `/health/details` (under any Space prefix) to everyone except loopback and your monitoring network.
   Those two answer without sign-in by design, so the proxy is their fence.
3. **Preflight the pair** from the bundle folder:

   ```powershell
   .\bin\preflight.ps1 -ProxyConfig C:\caddy\Caddyfile
   ```

   On Linux: `bash bin/preflight.sh --proxy-config /etc/nginx/conf.d/inspecto.conf`.
4. **Load it:** reload Caddy, or `nginx -t && systemctl reload nginx`.

Without a proxy, Inspecto can serve HTTPS itself: set `HTTPS_KEYSTORE` and `HTTPS_KEYSTORE_PASSWORD` (a PKCS12 file)
and preflight with `-Direct`.

## Check it worked

The preflight says `PASS proxy … refuses /metrics and /health/details` and `READY`. From another machine,
`https://<name>/health` answers `{"status":"UP"}`, `https://<name>/api/v1/metrics` answers 403, and the server's own
port does not answer at all. Through the proxy, the API answers:

```check
GET /health
expect status 200
```

## What can go wrong

- `FAIL bind -Dcontrol.bind is not set, so the server listens on every interface…` — step 1 is missing.
- `FAIL bind … is not a loopback address` — the kit supports a proxy on the same machine only.
- `WARN trusted-proxies …` — without it the audit trail sees every caller as the proxy.
- `FAIL proxy no proxy config at …`, or the config has nothing refusing `/metrics` and `/health/details` — check the
  path, and keep the template's fence.
- `FAIL proxy … no caddy or nginx on PATH` — install the proxy, or put it on `PATH`.
- `FAIL tls-keystore -Direct without HTTPS_KEYSTORE` — set the keystore, or use a proxy.

## Next

- [Connect the identity provider](../../module/oidc/admin-guide-howto-connect-the-identity-provider.md) — add the
  proxy's HTTPS address to the console client's redirect URIs.
- [Upgrade the platform](admin-guide-howto-upgrade-the-platform.md)
