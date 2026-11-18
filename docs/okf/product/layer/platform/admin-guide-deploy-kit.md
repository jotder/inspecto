---
type: Feature
title: "Platform — the deploy kit: reverse proxy, preflight, upgrade, bundle verify and off-site backup"
description: "How to install a bundle behind a reverse proxy that fences /metrics and /health/details, check the host before the first start, upgrade in place with rollback, verify a delivered zip, and copy backups off the host; what each script checks and what has been proven."
tags: [platform, admin-guide, deploy, proxy, tls, upgrade, backup, install]
timestamp: 2026-11-16T00:00:00Z
---

# Platform — the deploy kit

`/metrics` and `/health/details` answer without sign-in **by design** (operator decision 2026-11-16, MoSCoW S11 in
[`plan-architecture-moscow.md`](plan-architecture-moscow.md) §8): a Prometheus scraper carries no credentials, and
`/health/details` needs only a sign-in (no capability) and is open on Basic, which has no sign-in at all. It names the
write and data root paths. The server also listens on every interface unless `-Dcontrol.bind` is set
([launch flags](admin-guide-launch-flags.md)). So the fence is the network, and the deploy kit makes that fence the
default install rather than an instruction: the server on loopback, a reverse proxy in front that ends TLS and refuses
both paths to everyone but the scraper network, and a preflight that fails when either half is missing. There is no
product change and no token (`DEPLOY-EXPOSURE-KIT-1`).

## What ships

Every bundle except a demo build carries the kit (`package.ps1` step 6b-4 copies `deploy/kit/` from the repo):

| In the bundle | What it is |
|---|---|
| `deploy/proxy/nginx-inspecto.conf` | nginx server blocks: TLS, HTTP→HTTPS redirect, the fence, Server-Sent Events unbuffered, 64 MB uploads |
| `deploy/proxy/Caddyfile` | the same in Caddy: automatic certificates, or your own on a closed network |
| `bin/preflight.sh` · `bin/preflight.ps1` | checks the host before the first start; one PASS / WARN / FAIL line per check |
| `bin/upgrade.sh` · `bin/upgrade.ps1` | upgrades in place from a new zip: verify, stop, keep the old bundle, swap, preflight, start, health, roll back on failure |
| `bin/verify-bundle.sh` · `bin/verify-bundle.ps1` | checks a delivered zip against the `.sha256` (and `.asc`) the release ships with |
| `bin/backup-offsite.sh` · `bin/backup-offsite.ps1` | copies the newest verified backup archive off the host |

A demo bundle gets none of it: it listens on loopback for a desk demo and must never be put behind a proxy.

**Why both nginx and Caddy.** nginx is what most Linux estates already run and know how to operate. Caddy is one
binary that runs as a Windows service (nginx for Windows is not built for production use) and obtains and renews
certificates by itself where the host has a public name. Both templates carry the same fence regex, which
`tools/deploy-kit.test.mjs` pins.

## Install behind the proxy (Linux, nginx)

1. Unzip the bundle (for example to `/opt/inspecto/inspecto-deploy`) and install the service:
   `sudo bash bin/install-service.sh --user inspecto` ([README](../../../../../inspecto/README.md) *Run as an OS service*).
2. In `inspecto.env` (created by the installer, mode 600), put the server on loopback and trust the proxy's forwarded
   address:

   ```
   INSPECTO_JAVA_OPTS=-Dcontrol.bind=127.0.0.1 -Dcontrol.trustedProxies=127.0.0.1
   ```

   plus `AUTH_OIDC_*` (Standard and Advanced) and `INSPECTO_DB_URL` (Advanced). Without `control.trustedProxies` the
   audit trail and the per-address rate limit see every caller as `127.0.0.1`.
3. Copy `deploy/proxy/nginx-inspecto.conf` to `/etc/nginx/conf.d/inspecto.conf`. Edit the lines marked `EDIT`: the
   server name, the certificate and key, the port if you changed `PORT`, and the scraper network on the `allow` line
   of the fence (only loopback is allowed until you add one). `nginx -t && systemctl reload nginx`.
4. Run the preflight as the service account: `sudo -u inspecto bash bin/preflight.sh --proxy-config /etc/nginx/conf.d/inspecto.conf`.
   Fix every FAIL, then `systemctl restart inspecto`.
5. From another host: `curl -I https://<name>/api/v1/metrics` answers 403; `curl https://<name>/health` answers
   `{"status":"UP"}`; from the scraper network `/metrics` answers.

**Windows (Caddy).** Set `INSPECTO_JAVA_OPTS` as a machine-wide environment variable (the Scheduled Task runs as
SYSTEM and sees only those), install the task with `.\bin\install-service.ps1`, run Caddy with
`deploy\proxy\Caddyfile` (its `EDIT` lines as above; the scraper network goes on the `not remote_ip` line), then
`.\bin\preflight.ps1 -ProxyConfig <path to the Caddyfile>` in a new elevated shell.

**No proxy (`--direct` / `-Direct`).** Inspecto can serve TLS itself (`HTTPS_KEYSTORE`, `HTTPS_KEYSTORE_PASSWORD`;
TLS 1.3, PKCS12). The preflight then FAILs without a readable keystore and WARNs that `/metrics` and
`/health/details` answer anyone who reaches the port: the host firewall is the only fence. A proxy or load balancer on
**another** host is outside the kit: bind to the private interface it reaches, firewall the port to its address, and
expect the preflight's bind check to FAIL.

## What the preflight checks

Settings are read the way `serve.sh` / `serve.bat` read them: `PORT`, `SPACES_ROOT` (or `spaces.ref`),
`SPACES_DATA_ROOT`, `HTTPS_KEYSTORE`, `INSPECTO_JAVA_OPTS` (or `EXTRA_JAVA_OPTS`; the last `-D` of a name wins, as in the
JVM), `AUTH_OIDC_*`, `INSPECTO_DB_URL`. `preflight.sh` reads them from the environment and then from `inspecto.env`
when it exists (`KEY=VALUE` lines, never executed), because the systemd service reads that file and not your shell.
`SPACES_ALLOWED_ROOTS` (the folders Settings ▸ Spaces may put a Space folder or data folder under,
[launch flags](admin-guide-launch-flags.md)) goes in `inspecto.env` the same way; the preflight does not check it, and
the server applies the same `MIN_FREE_GB` rule to each folder it is given there.

| Check | FAIL when |
|---|---|
| `runtime` | no `runtime/bin/java` and no `java` on PATH, or a Java older than 27 |
| `edition` | no or an unknown `lib/edition.properties` (Basic WARNs that it has no sign-in) |
| `sign-in` | Standard, Advanced or Preview without `AUTH_OIDC_DISCOVERY_URL`, or the issuer and JWKS pair |
| `database` | Advanced without `INSPECTO_DB_URL` |
| `bind` | behind a proxy: `-Dcontrol.bind` unset or not loopback |
| `trusted-proxies` | never; WARN when `-Dcontrol.trustedProxies` does not name `127.0.0.1` |
| `tls-keystore` | a keystore is named but missing or unreadable; `--direct` with none |
| `proxy` | behind a proxy: no `nginx` / `caddy` on PATH and no `--proxy-config`, or a config with no rule refusing both paths. Comments are ignored; nginx needs a `location` naming `metrics` and `health/details` whose block holds `deny all` or `return 403`, Caddy a named matcher whose `path_regexp` names both, answered by `respond @<matcher> 403`. A config split over `include` files is read as the one file given |
| `spaces-root`, `data-root`, `logs` | missing, not writable by the running account, or under `MIN_FREE_GB` (default 5) free |
| `port` | something already listens on `PORT` (any address) |

Exit 1 when any check FAILs. `--endpoint` prints the base URL the server answers on locally (`http(s)://<bind or
127.0.0.1>:<PORT>`) and `--spaces-root` (`-SpacesRoot`) the spaces root it will use (`SPACES_ROOT`, else `spaces.ref`,
else `./spaces`); the upgrade script reads both before it moves anything.

Every `.sh` is run through `bash` in this guide: a bundle zipped on Windows carries no executable bits, so
`bin/upgrade.sh` on its own is refused (`upgrade.sh` sets them on the bundle it installs).

## Upgrade in place

```
sudo bash bin/upgrade.sh /tmp/inspecto-deploy-linux_amd64.zip --backup-dir /srv/inspecto/spaces/default/backups --proxy-config /etc/nginx/conf.d/inspecto.conf
.\bin\upgrade.ps1 C:\drop\inspecto-deploy-windows_amd64.zip -BackupDir D:\inspecto\backups -ProxyConfig C:\caddy\Caddyfile
```

The steps, stopping at the first failure:

1. **Verify the zip** with `verify-bundle` (below). Nothing is touched before it passes.
2. **Check the newest backup** in `--backup-dir` with `backup-offsite --verify-only`. Without the option it is a WARN.
   Run the backup Job before upgrading.
3. **Unpack** beside the bundle (`.inspecto-upgrade-<time>/`) and check the shape (`bin/serve.*`, `bin/preflight.*`,
   `lib/modules.list`, `lib/edition.properties`) and the **edition**: a different one is refused unless
   `--allow-edition-change`. On Linux, run as root, the unpacked files get the owner of the bundle they replace.
4. **Stop** the systemd service (`--service`, default `inspecto`) or the Scheduled Task (`-Name`, default `Inspecto`),
   then wait until nothing listens on the port. systemd stops every process of the unit. On Windows, stopping the task
   ends `cmd.exe` only, and a JVM it started can outlive it and hold the files, so the script also stops every
   process **of the bundle**: its executable is under `<bundle>\`, or its command line names a path under `<bundle>\`
   (at a path separator, so `C:\apps\inspecto-deploy-staging\...` is not `C:\apps\inspecto-deploy`), plus a
   `java.exe` listening on the port (a java from PATH started by `serve.bat` names only relative paths). With
   `-NoService` it stops nothing and only waits for them to be gone.
5. **Keep the previous bundle** as `<bundle>.pre-upgrade-<time>/`. `settings/` holds a copy of `inspecto.env`,
   `spaces.ref`, `conf/`, and of each Space's `config/` and top-level files (not its data) **from the spaces root the
   server uses** (`--spaces-root` above; an `<id>.ref` Space is followed to the folder it names). `product/` holds every
   entry the new bundle replaced. **Replaced:** every top-level entry the new bundle ships, except `spaces/` and
   `logs/`. In the bundle's own `spaces/_templates`, each template the new bundle ships replaces its namesake, a
   template only the old bundle shipped is moved to `product/`, and a template an App installed at run time (it holds
   `.installed-by`, `InstalledApps.java`) stays; when the new bundle ships a template of the same name, the shipped
   one wins and the installed one goes to `product/`. **Kept in place:** your Spaces and their data, `apps/`, `logs/`,
   `inspecto.env`, `spaces.ref`, `runtime-natives/` and any file of your own. When the spaces root is outside the
   bundle (`SPACES_ROOT`, `spaces.ref`), nothing in it is moved, its `_templates` included. This is the README's manual
   upgrade rule, scripted.
6. **Preflight** the new bundle (`--direct` and `--proxy-config` are passed to it).
7. **Start** and require `/health` and `/ready` within `HEALTH_TIMEOUT` (default 180 s), answered by the server the
   start just launched: on Linux the unit's main process, running from the bundle folder and started after the start
   was issued; on Windows the process listening on the port (or the `cmd.exe` running `serve.bat` above it), a process
   of the bundle started after the start. A leftover process answering on the port does not pass.

A failure at 6 or 7 stops what runs from the bundle (on Windows, the bundle's processes as in step 4, waiting until
they are gone and the port is free), moves the new entries back to the unpack folder, puts the previous ones back,
restarts the service, checks it the same way and exits 1. `--no-service` / `-NoService` skips stop and start: you stop
the server first and start it after. Delete the `.pre-upgrade-` folder once the new version is accepted.

**CA certificates.** The upgrade replaces `runtime/`, so certificates imported into the bundled Java's
`runtime/lib/security/cacerts` (an identity provider or PostgreSQL signed by a private CA) are gone afterwards, and a
Standard or Advanced server then fails its OIDC discovery with an `SSLHandshakeException`: the health check fails and
the upgrade rolls back. Before upgrading such an install, either keep the trust store outside the bundle (copy
`runtime/lib/security/cacerts` elsewhere, import into the copy, and add `-Djavax.net.ssl.trustStore=<copy>` to
`INSPECTO_JAVA_OPTS`; that copy no longer follows the JDK's CA updates), or upgrade with `--no-service`, re-import,
then start. The previous file stays in
`<bundle>.pre-upgrade-<time>/product/runtime/lib/security/cacerts`, and the script prints a note when the two differ.
Re-import with the bundle's own keytool:
`runtime/bin/keytool -importcert -alias <name> -file <ca>.crt -keystore runtime/lib/security/cacerts -storepass changeit -noprompt`.

## Verify a delivered bundle

`package.ps1` already writes `<zip>.sha256` (`sha256sum` format) next to every zip, and `<zip>.asc` with `-Sign`
(SOC 2 CC8-04), so the kit adds no manifest: `verify-bundle` checks the zip against those. The `.sha256` travels with
the zip, so it proves the copy is whole, not who made it; only the signature proves origin. Without an `.asc` the
signature check WARNs, or FAILs with `--require-signature` / `-RequireSignature`; an `.asc` without `gpg` installed
FAILs. For a first install, before any bundle is unpacked, `sha256sum -c <zip>.sha256` or
`Get-FileHash <zip> -Algorithm SHA256` does the same check.

## Copy backups off the host

The backup Job's `backup_dir` is path-jailed to the Space (`JOB-DIR-CWD-CONTAINMENT-1`), so the server cannot write a
backup off the host, and the kit does not widen the jail. `backup-offsite`, run by the operator outside the server
(cron, a systemd timer, a Scheduled Task, after the backup Job), does:

1. takes the newest `*.zip` in `--from` by name, as `backup_verify` does (archive names sort by time);
2. checks its SHA-256 against `archiveSha256` in the sidecar `<archive>.manifest.json`, the first check of
   `backup_verify`; a damaged archive FAILs and nothing is copied;
3. copies the archive, then the sidecar, so a target that holds the sidecar holds the whole backup.

The target is the operator's choice: an existing folder (a mounted NFS or SMB share; copied, then re-hashed there),
`host:/path` (rsync over ssh, `.sh` only; on Windows use a UNC path), or any CLI given with `--cmd` / `-Command` and run
as `<command> <file> <target>` per file, for example `--cmd "aws s3 cp" --to s3://bucket/inspecto/` or
`--cmd "rclone copy" --to remote:inspecto`. `--verify-only` checks and copies nothing; the upgrade script uses it.

## What has been proven, and what has not

`tools/deploy-kit.test.mjs` (CI guards job) runs the shipped scripts in throwaway fake bundles: the `.sh` forms under
bash, the `.ps1` forms under pwsh, `upgrade.ps1` on Windows only and the `upgrade.sh` swap on Linux only (Windows
refuses to move `bin/` while bash runs a script from it). Each check below was falsified: the test went red when the
check was broken.

- preflight: an unset, wildcard or non-loopback bind FAILs behind a proxy, a loopback one PASSes; a missing keystore
  FAILs (named but absent, or `--direct` with none); a proxy config without the fence FAILs, and so does each template
  with its refusal removed, commented out or pointed at another matcher; an edition's missing sign-in or database
  FAILs; a listening port FAILs; a Java older than 27 FAILs; `inspecto.env` is read; `--spaces-root` follows
  `SPACES_ROOT` and `spaces.ref`.
- verify-bundle: a tampered zip, a missing `.sha256` and `--require-signature` without `.asc` FAIL.
- backup-offsite: only the newest archive and its sidecar are copied; a damaged archive copies nothing.
- upgrade: the swap keeps Spaces, data, logs and own files and keeps the previous bundle; `_templates` is merged (an
  installed App's template stays, a retired shipped one is moved aside); a `.ref` Space's config is copied; a failing
  preflight rolls back; a tampered zip or a different edition changes nothing. `upgrade.ps1`: a process under a
  sibling folder whose name starts with the bundle's is left alone, and a rollback stops a process started from the
  new bundle that locks a file in `lib\` and listens on the port, then restores.
- the fence regex matches every route form that reaches either endpoint (`/metrics`, `/metrics/acquisition`, with or
  without `/api/v1` and `/spaces/<id>`) and no other route.
- `package.ps1` step 6b-4 stages the kit into `bin/` and `deploy/proxy/`, and not into a demo bundle.

**Not run — needs a real host** (open on the `DEPLOY-EXPOSURE-KIT-1` row): `nginx -t` and `caddy validate` on the two
templates, and a request through each proving the 403 from outside and the pass from the scraper network, including
encoded and doubled-slash paths (nginx and Caddy both match on the decoded path with duplicate slashes merged, which is
also how the server resolves routes, but that is their documented behavior, not observed here); the refresh cookie's
`Secure` rewrite; SSE through each proxy; `upgrade.sh` against a running systemd service and `upgrade.ps1` against a
running Scheduled Task, including the health wait, its proof that the new process answers (main PID, cwd and start
time; listener owner and creation time) and the rollback restart; `backup-offsite` over rsync and an object
store CLI; `gpg --verify` of a signed release.
