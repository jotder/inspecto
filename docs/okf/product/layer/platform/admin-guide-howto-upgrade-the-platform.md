---
type: How-to
title: "Upgrade the platform"
description: "Upgrade an Inspecto installation in place with the deploy kit's upgrade script — verified zip, backup check, preflight, health wait, automatic roll-back — keeping Spaces, installed Apps and your settings."
item: layer.platform
audience: external
status: current
verified: 2026-11-20 code-reviewed
code: [deploy/kit/bin]
---

# Upgrade the platform

## Goal

Move a running installation to a new Inspecto release with one command, keeping its Spaces, data, installed Apps and
settings, and getting the old version back automatically if the new one does not come up.

## Before you start

- **Edition:** every installed edition. The new zip must be the **same** edition; changing edition needs
  `-AllowEditionChange` (Linux `--allow-edition-change`).
- **Who:** an operator with administrator rights (an elevated shell, or `sudo`).
- **What you need:** the new bundle zip with its `.sha256` file, a verified backup of each Space
  ([Back up and restore a Space](../../module/backup/admin-guide-howto-back-up-and-restore.md)), and a few minutes of
  downtime.

## Steps

1. **Run the upgrade** from the **current** bundle's folder, in an elevated shell. Give the backup folder as a full
   path:

   ```powershell
   Set-Location C:\inspecto\inspecto-deploy
   .\bin\upgrade.ps1 C:\drop\inspecto-deploy-windows_amd64.zip -BackupDir C:\inspecto\spaces\o2c\data\backups
   ```

   On Linux: `sudo bash bin/upgrade.sh <zip> --backup-dir <dir>`. Add `-ProxyConfig <file>` (`--proxy-config`) when
   a reverse proxy fronts it, `-RequireSignature` to insist on a signed zip, and `-NoService` when the server does not
   run as a service (stop it first).
2. **It then, in order:** verifies the zip, checks the newest backup, unpacks the new bundle and checks its edition,
   stops the service, keeps the old bundle as `<bundle>.pre-upgrade-<time>`, preflights the new one, starts it and
   waits for `/health` and `/ready`. It ends `DONE: <old> -> <new>. Remove <pre> once you are satisfied.`
3. **What stays:** the Space folders and their data, `logs\`, `spaces.ref`, `apps\` (installed Apps), `conf\` and files
   of your own.
4. **Afterwards:** re-import a private certificate authority into the new `runtime\lib\security\cacerts` if your
   identity provider needs one — the runtime was replaced. When you are satisfied, delete the `.pre-upgrade-` folder.

## Check it worked

The server is healthy and ready, the bootstrap names the new version and the same edition, and installed Apps are
still installed:

```check
GET /ready
expect status 200
```

```check
GET /bootstrap
as admin
expect status 200
expect data.version exists
expect data.edition exists
```

```check
GET /apps
as admin
expect status 200
```

## What can go wrong

- `FAIL …` then `putting the previous bundle back` — the new bundle failed its preflight or health wait; the old one
  runs again and the new one is left unpacked for you to look at.
- The health wait fails right after a runtime change — usually the identity provider's private certificate is not
  trusted by the new runtime (step 4); the script rolls back.
- `-NoService: the server must already be stopped` — stop it, or install it as a service.
- `WARN no -BackupDir: the Space data is not checked to have a verified backup`.
- A relative `-BackupDir` (`.\spaces\…`) is not found — the script changes folder before it looks; give a full path,
  and change back to your folder afterwards.
- An edition change is refused unless you pass `-AllowEditionChange`.

## Next

- [Verify the audit chain](../../module/event/admin-guide-howto-verify-the-audit-chain.md)
- [Upgrade an App](user-guide-howto-upgrade-an-app.md)
