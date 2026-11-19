---
type: Tutorial
title: "Tutorial: run it — install, sign-in, backup, upgrade and audit"
description: "Install a Standard bundle, connect sign-in through an OpenID Connect provider, put the server behind a reverse proxy, back up and restore a Space, upgrade the bundle in place, verify the audit chain and open the Help."
item: layer.platform
audience: external
---

# Tutorial: run it — install, sign-in, backup, upgrade and audit

This tutorial takes one server from a delivered zip to a running installation you can trust: people sign in through
your identity provider, only a reverse proxy reaches the server, the Order-to-cash Space is backed up and can be
restored, the installation is upgraded in place, and its audit trail is checked for tampering.

**What you need**

| | Edition |
|---|---|
| A Standard bundle zip, `inspecto-deploy-windows_amd64.zip`, with the `.sha256` file delivered beside it | Standard (Advanced works the same way, plus PostgreSQL) |
| An OpenID Connect identity provider, or Docker on the machine for a trial one (step 2) | Standard and above |
| nginx (Linux) or Caddy (Windows) for the reverse proxy | Standard and above |
| An administrator account at the identity provider | — |

The commands are PowerShell on Windows. Every script in `bin\` also ships as a `.sh` for Linux, run with `bash`
(for example `bash bin/preflight.sh`), and the bundle's own `README.md` gives the Linux service (systemd) form.
Read [Edition and Add-on](../product/user-guide-concept-edition-addon.md) and
[Roles and Access Policies](../../module/access/user-guide-concept-roles-access-policies.md) if the words are new.

## 1. Check the zip and unpack it

Unpack the zip, for example to `C:\inspecto`, then check the zip you were given against its checksum:

```powershell
Expand-Archive C:\drop\inspecto-deploy-windows_amd64.zip C:\inspecto
Set-Location C:\inspecto\inspecto-deploy
.\bin\verify-bundle.ps1 C:\drop\inspecto-deploy-windows_amd64.zip
```

You see `PASS sha256 …` and `OK`. When the release also ships a `.asc` signature, the script checks it too;
without one it warns that only the integrity was checked. The folder holds `bin\` (the launchers and the deploy kit),
`deploy\proxy\` (nginx and Caddy templates), `docs\`, `lib\`, `runtime\` (Java is included) and an empty `spaces\`.

## 2. Connect sign-in

Standard signs people in through your OpenID Connect identity provider and does not start without one. Point it at
yours with these settings (here Keycloak; `generic` and `wso2` are the other providers):

```powershell
$env:AUTH_OIDC_PROVIDER      = 'keycloak'
$env:AUTH_OIDC_DISCOVERY_URL = 'https://idp.example.com/realms/inspecto/.well-known/openid-configuration'
$env:AUTH_OIDC_CLIENT_ID     = 'inspecto-spa'
$env:AUTH_OIDC_AUDIENCE      = 'inspecto-spa'
```

Your provider's client must be a **public** client using the authorization code flow with PKCE, with
`https://<your server>/*` as a valid redirect address. Every access token must carry:

- `aud` equal to the client id and nothing else: a token for several audiences is refused;
- a `sub` (subject) claim;
- the person's role names in a `roles` claim (Keycloak: realm roles). A role name is an Inspecto Role: `admin`
  administers the server and its Spaces, `power` builds and operates (it may author Alert Rules), `business` reads.
  A name Inspecto does not know grants nothing.

**For a trial without your own provider**, run Keycloak on the same machine and start from the realm the bundle
ships (`docs\api\deployment\keycloak-realm-blueprint.json`, realm `inspecto`, clients `inspecto-spa` and
`inspecto-api`, the six Roles):

```powershell
New-Item -ItemType Directory C:\trial-idp | Out-Null
Copy-Item .\docs\api\deployment\keycloak-realm-blueprint.json C:\trial-idp\inspecto-realm.json
docker run -d --name trial-idp -p 127.0.0.1:8181:8080 -e KC_BOOTSTRAP_ADMIN_USERNAME=admin -e KC_BOOTSTRAP_ADMIN_PASSWORD=<choose one> -v C:\trial-idp:/opt/keycloak/data/import:ro quay.io/keycloak/keycloak:26.8.1 start-dev --import-realm
```

Then, in the Keycloak administration console (`http://localhost:8181/admin`, realm `inspecto`), finish the realm:

1. Client `inspecto-spa`: add `http://localhost:8080/*` to the valid redirect URIs and `+` to the web origins.
2. Client `inspecto-spa`: add the client scope `basic` as a default scope (without it the token has no `sub`).
3. Client `inspecto-spa`: add an **Audience** mapper that puts `inspecto-spa` in the access token.
4. Realm role `default-roles-inspecto`: remove the `account` client roles (`manage-account`, `view-profile`), which
   would add a second audience, `account`, to every token.
5. Add a user, give them a password, and assign the realm roles `admin` and `power`.

and use `http://localhost:8181/realms/inspecto/.well-known/openid-configuration` as the discovery address. A trial
provider in development mode is for a trial only: never put it in front of real data.

## 3. Start it on loopback and sign in

The server listens on every network interface unless told otherwise. Put it on loopback, so that only the reverse
proxy (step 4) reaches it, and let it trust the proxy's forwarded address:

```powershell
$env:INSPECTO_JAVA_OPTS = '-Dcontrol.bind=127.0.0.1 -Dcontrol.trustedProxies=127.0.0.1'
.\bin\preflight.ps1
.\bin\serve.bat
```

The preflight prints one line per check (Java, edition, sign-in, bind, trusted proxies, proxy, spaces folder, logs,
port) and `READY` once nothing fails. Until the proxy of step 4 is in place it fails the `proxy` check: fix it there,
or pass `-Direct` to serve TLS from Inspecto itself instead of a proxy. `serve.bat` says
`ControlApi started on port 8080 (authentication enforced …)`. To run it as a Windows service instead, use
`.\bin\install-service.ps1` in an elevated PowerShell, with the settings above as machine-wide environment variables.

Open `http://localhost:8080/`, choose **Sign in with SSO**, and sign in at your provider. You land on the home page,
*Welcome back, <your name>*, with **Your access here** listing what your Roles allow.

### Check it worked

The server runs the Standard edition with sign-in on, refuses a call without a token, and accepts a signed-in
administrator:

```check
GET /bootstrap
expect status 200
expect data.edition = "standard"
expect data.features.authMode = "oidc"
```

```check
GET /spaces
expect status 401
```

```check
GET /spaces
as admin
expect status 200
```

## 4. Put it behind the reverse proxy

The deploy kit's proxy templates end TLS, redirect HTTP to HTTPS, pass the console's live updates through
unbuffered, accept uploads up to 64 MB, and refuse `/metrics` and `/health/details` to everyone except your
monitoring network. Those two answer without sign-in by design, so the proxy is their fence.

**Windows, Caddy:** copy `deploy\proxy\Caddyfile` and edit the lines marked `EDIT`: your server's name, your
certificate (or let Caddy obtain one), your monitoring network on the `not remote_ip` line, and the port if you
changed it. For a trial on one machine:

```
localhost:8443 {
	tls internal
	…
	reverse_proxy 127.0.0.1:8080 {
```

**Linux, nginx:** copy `deploy/proxy/nginx-inspecto.conf` to `/etc/nginx/conf.d/inspecto.conf`, edit its `EDIT` lines
the same way, then `nginx -t && systemctl reload nginx`.

Run the preflight again, naming the proxy configuration:

```powershell
.\bin\preflight.ps1 -ProxyConfig C:\caddy\Caddyfile
```

It now says `PASS proxy … refuses /metrics and /health/details`. From another machine, `https://<name>/health`
answers `{"status":"UP"}`, `https://<name>/api/v1/metrics` answers 403, and the server's own port does not answer at
all.

## 5. Back up and restore a Space

Create the Order-to-cash Space if you do not have one yet (**Settings → Spaces → New from template**,
*Order-to-cash assurance*, then **Create space**). Its sample data is loaded:

```check
POST /spaces
as admin
body {"id": "{space}", "template": "order-to-cash", "loadSamples": true}
expect status 200|409
```

A backup is a **maintenance Job** in the Space: it zips the Space's configuration into its `data\backups` folder
with a manifest of SHA-256 hashes, and a second Job checks the newest archive against them. Create both and run them
(`o2c` is the Space, `$token` an administrator's access token from your identity provider):

```powershell
$api = 'https://inspecto.example.com/api/v1'
$h   = @{ Authorization = "Bearer $token" }
$backup = '{"name":"config_backup","type":"maintenance","cron":"0 4 * * *","task":"backup","dir":".","backup_dir":"../data/backups","prefix":"o2c_config"}'
$verify = '{"name":"backup_verify","type":"maintenance","cron":"30 4 * * *","task":"backup_verify","backup_dir":"../data/backups"}'
Invoke-RestMethod -Method Post -Uri "$api/spaces/o2c/jobs" -Headers $h -ContentType 'application/json' -Body $backup
Invoke-RestMethod -Method Post -Uri "$api/spaces/o2c/jobs" -Headers $h -ContentType 'application/json' -Body $verify
```

Both then run every night (4:00 and 4:30). Run them now: **Jobs → config_backup → Run now**, then the same for
`backup_verify`. The first Run says `backup: archived … file(s) … → …\data\backups\o2c_config_<time>.zip`, the
second `backup_verify: 1 archive(s) OK`. The **Maintenance** page shows the last backup.

### Check it worked

```check
POST /spaces/{space}/jobs
as admin
body {"name": "config_backup", "type": "maintenance", "cron": "0 4 * * *", "task": "backup", "dir": ".", "backup_dir": "../data/backups", "prefix": "o2c_config"}
expect status 200|409
```

```check
POST /spaces/{space}/jobs/config_backup/trigger
as admin
expect status 202
save data.runId as backup
```

```check
GET /spaces/{space}/jobs/runs/{backup}
as admin
expect data.status = "SUCCESS"
expect data.message matches /^backup: archived/
retry 30 every 2s
```

```check
POST /spaces/{space}/jobs
as admin
body {"name": "backup_verify", "type": "maintenance", "cron": "30 4 * * *", "task": "backup_verify", "backup_dir": "../data/backups"}
expect status 200|409
```

```check
POST /spaces/{space}/jobs/backup_verify/trigger
as admin
expect status 202
save data.runId as verify
```

```check
GET /spaces/{space}/jobs/runs/{verify}
as admin
expect data.status = "SUCCESS"
expect data.message matches /archive\(s\) OK/
retry 30 every 2s
```

**Keep a copy off the machine.** The backup stays inside the Space; copy the newest verified archive and its manifest
to a share or another disk (or to object storage with `-Command`), for example after the backup Job each night:

```powershell
.\bin\backup-offsite.ps1 -From .\spaces\o2c\data\backups -To \\backup-server\inspecto
```

It checks the archive's hash first and copies nothing when it does not match.

**Restore.** A restore is a maintenance Job too. It refuses an archive whose hash does not match its manifest before
writing a byte. Name the archive the backup Run reported, preview, then restore over the Space's configuration:

```powershell
$restore = '{"name":"restore_config","type":"maintenance","cron":"0 0 1 1 *","enabled":false,"task":"restore","archive":"../data/backups/o2c_config_<time>.zip","target_dir":".","overwrite":"true"}'
Invoke-RestMethod -Method Post -Uri "$api/spaces/o2c/jobs" -Headers $h -ContentType 'application/json' -Body $restore
Invoke-RestMethod -Method Post -Uri "$api/spaces/o2c/jobs/restore_config/trigger?dryRun=true" -Headers $h
Invoke-RestMethod -Method Post -Uri "$api/spaces/o2c/jobs/restore_config/trigger" -Headers $h
```

The preview Run says `restore[dry-run]: would restore … file(s) … (… conflict(s))`; the real Run says
`restore: … file(s) … (… overwritten)`, and every configuration file is back as it was at the backup. Back up the
current configuration first when you restore over a Space in use: that backup is your way back.

## 6. Upgrade the bundle in place

Run the backup Jobs first. Then stop the server, upgrade, and start it again:

```powershell
.\bin\upgrade.ps1 C:\drop\inspecto-deploy-windows_amd64.zip -BackupDir .\spaces\o2c\data\backups -ProxyConfig C:\caddy\Caddyfile -NoService
.\bin\serve.bat
```

The script works in seven steps and stops at the first failure: it verifies the zip, checks the newest backup in
`-BackupDir`, unpacks and checks the new bundle (a different edition is refused), stops the server, keeps the previous
bundle as `inspecto-deploy.pre-upgrade-<time>`, preflights the new one, and starts it and waits for `/health` and
`/ready`. It ends with `DONE: Standard -> Standard`. Your Spaces and their data, installed Apps, `logs\` and your own
files stay where they are.

- `-NoService` is for a server you start yourself, as in this tutorial: stop it before the script, start it after.
  Installed as a service, leave `-NoService` out and the script stops and starts the service, and puts the previous
  bundle back if the new one does not come up healthy.
- The upgrade replaces `runtime\`. If your identity provider's certificate is signed by your own certificate
  authority, keep its trust store outside the bundle: copy `runtime\lib\security\cacerts` elsewhere, import your
  authority into the copy, and add `-Djavax.net.ssl.trustStore=<the copy>` to `INSPECTO_JAVA_OPTS`. Otherwise the
  new bundle cannot reach the provider, fails its health check, and the upgrade rolls back.

Delete the `.pre-upgrade-` folder once you have accepted the new version.

## 7. Verify the audit chain

Every change in a Space is written to its audit trail, and each entry is chained to the one before it by a hash. Ask
the server to recompute the chain, and anchor today's end of it:

```powershell
(Invoke-RestMethod -Uri "$api/spaces/o2c/audit/verify" -Headers $h).data
Invoke-RestMethod -Method Post -Uri "$api/spaces/o2c/audit/anchors" -Headers $h
```

`ok: true` with `checked` (how many entries were recomputed) and `firstBad: null` means no entry was changed, removed
or re-ordered. A broken chain still answers 200, with `ok: false` and the first bad entry. An anchor is a signed record
of the chain's end; keep `GET …/audit/anchors` exports off the machine, and the next verify checks them too
(`anchorsChecked`). Only an administrator may verify or anchor.

### Check it worked

```check
GET /spaces/{space}/audit/verify
as admin
expect status 200
expect data.ok = true
expect data.firstBad absent
```

## 8. Open the Help

The Help you are reading ships in every bundle: open `https://<your server>/help/`. It needs no sign-in. Its
**Run the platform** chapter holds this tutorial and the Operations guide; **Understand** explains each concept.

## Which edition each step needs

| Step | Edition |
|---|---|
| 1, 3, 4, 6 — check, start, proxy, upgrade | every edition. Basic has no sign-in, so its proxy is its only fence |
| 2 — sign-in | Standard and above |
| 5 — backup, verify and restore Jobs | Standard and above |
| 7 — audit verification | every edition; on Standard and above each entry names the signed-in person |
| Advanced | as Standard, plus PostgreSQL: set `INSPECTO_DB_URL`, `INSPECTO_DB_USER` and `INSPECTO_DB_PASSWORD` before step 3 |

## Read next

- [Operations guide](../../../../ADVANCED_GUIDE.md)
- [Roles and Access Policies](../../module/access/user-guide-concept-roles-access-policies.md)
- [Space](../../module/config/user-guide-concept-space.md)
- [The editions on one page](../../../../EDITIONS.md)
