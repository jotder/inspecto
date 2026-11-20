---
type: How-to
title: "Install an edition"
description: "Verify an Inspecto bundle, unzip it, run the preflight, start the server by hand or as a service, and sign in for the first time."
item: layer.platform
audience: external
status: current
verified: 2026-11-20 code-reviewed
code: [inspecto/package.ps1, deploy/kit]
---

# Install an edition

## Goal

Get one Inspecto server running from its bundle — Basic, Standard, Advanced or Preview — and open the console.

## Before you start

- **Edition:** the one you bought; see [the editions on one page](../../../../EDITIONS.md). Standard does not start
  without an OpenID Connect provider; Advanced also needs a PostgreSQL database.
- **Who:** an operator with a shell on the server; administrator rights to install it as a service.
- **What you need:** the bundle zip and its `.sha256` file (and `.asc` signature, if you were given one), a free port
  (8080 by default), and Java 27 or later only if the bundle has no `runtime\` folder of its own. For Standard and
  Advanced, the identity provider's details ([Connect the identity provider](../../module/oidc/admin-guide-howto-connect-the-identity-provider.md)).

## Steps

1. **Unzip** it into its own folder, for example `C:\inspecto`. Which edition you have is written in
   `lib\edition.properties`.
2. **Check the zip** against its checksum (the `.sha256` file sits beside the zip) before you go on:

   ```powershell
   Set-Location C:\inspecto\inspecto-deploy
   .\bin\verify-bundle.ps1 C:\downloads\inspecto-deploy-windows_amd64.zip
   ```

   It says `PASS sha256` and `OK`. Add `-RequireSignature` to refuse a zip without a valid signature.
3. **Set the launch settings** for your edition as environment variables: `PORT`, `SPACES_ROOT` (where Spaces live),
   `CORS_ORIGIN`, and for Standard and up the `AUTH_OIDC_*` settings; anything else goes in `INSPECTO_JAVA_OPTS`
   (for example `-Dcontrol.bind=127.0.0.1` behind a reverse proxy). Advanced also needs `INSPECTO_DB_URL`.
4. **Run the preflight** from the bundle folder:

   ```powershell
   .\bin\preflight.ps1
   ```

   It checks the runtime, edition, sign-in, bind address, trusted proxies, proxy, Spaces folder, logs and port, and
   ends `READY` — or `NOT READY - fix every FAIL…`. On Linux: `bash bin/preflight.sh`.
5. **Start it** by hand with `.\bin\serve.bat` (Linux `bash bin/serve.sh`), or install it as a service with
   `.\bin\install-service.ps1` in an elevated shell (Linux `sudo bash bin/install-service.sh`). The log says
   `ControlApi started on port 8080` and how sign-in is enforced.
6. **Open the console** at the server's address. On Standard and up click **Sign in with SSO**; Home greets you and
   shows *Your access here*. Make your first Space from **Settings → Spaces → New from template**, or from Home's
   **Try it with sample data**.

## Check it worked

`GET /health` answers `{"status":"UP"}`, and the bootstrap names the edition and version:

```check
GET /health
expect status 200
```

```check
GET /bootstrap
as admin
expect status 200
expect data.edition exists
expect data.version exists
```

## What can go wrong

- `FAIL runtime … Inspecto needs Java 27 or later` — use the bundle's own `runtime\`, or install Java 27.
- `FAIL sign-in … does not start without AUTH_OIDC_DISCOVERY_URL, or AUTH_OIDC_ISSUER and AUTH_OIDC_JWKS_URI` —
  Standard and up need the identity provider's settings.
- `FAIL database Advanced does not start without INSPECTO_DB_URL (PostgreSQL)`.
- `FAIL port something already listens on port 8080…` — stop the other program or set `PORT`.
- `FAIL bind -Dcontrol.bind is not set…` — the server would listen on every interface; bind it to `127.0.0.1` and put
  a reverse proxy in front ([Put Inspecto behind a reverse proxy](admin-guide-howto-reverse-proxy.md)), or run the
  preflight with `-Direct` and an HTTPS keystore.
- `FAIL sha256 no <zip>.sha256 beside the zip` — download the hash file too.
- Basic has no sign-in and listens on every interface by default: anyone who reaches the port is in.

## Next

- [Put Inspecto behind a reverse proxy](admin-guide-howto-reverse-proxy.md)
- [Connect the identity provider](../../module/oidc/admin-guide-howto-connect-the-identity-provider.md)
- [Tutorial: run it](user-guide-tutorial-run-the-platform.md)
