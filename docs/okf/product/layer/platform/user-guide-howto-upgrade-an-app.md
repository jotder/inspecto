---
type: How-to
title: "Upgrade an App, then the Spaces made from it"
description: "Install a new version of an App over the old one, read what it would change in each Space made from it, and bring a Space up to the new template version without overwriting anything the Space changed."
item: layer.platform
audience: external
status: current
verified: 2026-11-20 code-reviewed
code: [inspecto/src/main/java/com/gamma/control/AppInstallRoutes.java, platform/inspecto-config]
---

# Upgrade an App, then the Spaces made from it

## Goal

Ship version 2 of an App to a server that runs version 1, and bring an existing Space up to it — keeping the Space's
data and every file the Space changed itself.

## Before you start

- **Edition:** Standard, Advanced or Preview (a Platform build with the App installed).
- **Who:** an administrator (`canAdminister`) to upgrade the App and apply a Space upgrade; reading a Space's upgrade
  proposal needs `canAuthorWorkbench`.
- **What you need:** the new signed zip with a higher `version` in `app.toon` and in the changed template's
  `template.toon` ([Package and sign an App](user-guide-howto-package-and-sign-an-app.md)), an administrator's access
  token. Both upgrades are Control API calls; there is no console screen for them.

## Steps

Upgrading is two moves: the **App** on the server, then each **Space** when its owner is ready.

1. **Preview the App upgrade.** It never writes a Space; per Space made from the App it lists each file as `new`,
   `update`, `customised` (the Space changed it — left alone) or `dropped`:

   ```powershell
   $zip = 'C:\work\o2c-assurance\dist\o2c-assurance-2.0.0.zip'
   Invoke-RestMethod -Method Put -Uri "$api/apps/o2c-assurance?dryRun=true" -Headers $h -ContentType 'application/zip' -InFile $zip
   ```

2. **Upgrade the App** — the same call without `?dryRun=true`. New Spaces are now made from version 2.
3. **Read a Space's proposal:**

   ```powershell
   (Invoke-RestMethod -Uri "$api/spaces/o2c-live/origin/upgrade" -Headers $h).data
   ```

   It says `fromVersion`, `toVersion` and, per file, `update`, `add`, `kept` (the Space changed it, the template did
   not), `conflict` (both changed it) or `template-removed`.
4. **Apply it:**

   ```powershell
   Invoke-RestMethod -Method Post -Uri "$api/spaces/o2c-live/origin/upgrade" -Headers $h -ContentType 'application/json' -Body '{"toVersion":"2.0.0"}'
   ```

   Every `update` and `add` is written through the same safety checks and audit trail as any configuration change. A
   `kept` or `conflict` file is never overwritten — change it yourself on its own screen. To apply only some files,
   add `"files": [...]` to the body. If an approval policy covers the Space, the answer is 202 and the change waits
   for an approver.

## Check it worked

The server runs version 2 of the App; applying the Space's upgrade (step 4) records the new template version:

```check
GET /apps
as admin
expect status 200
expect data.installed[*].version has 2.0.0
```

```check
POST /spaces/{space}/origin/upgrade
as admin
body {"toVersion": "2.0.0"}
expect status 200|409
```

```check
GET /spaces/{space}/origin
as admin
expect status 200
expect data.templateVersion = "2.0.0"
```

## What can go wrong

- 409 `App 'x' is not installed - install it with POST /apps` — use
  [Install an App](user-guide-howto-install-an-app.md) first.
- 422 `the package is App 'a', not 'other'` — the zip is a different App from the one in the address.
- 409 on the Space upgrade with no origin recorded — a Space made before origins were recorded must be adopted first
  (`POST /spaces/<id>/origin/adopt`).
- 409 `already at the template's version`, or a stale proposal — read the proposal again and apply its `toVersion`.
- A Space that edited a Job the template seeded reads `conflict` even when the template left it alone — the safe side.
- On a set of several servers, upgrade the App on each server.

## Next

- [Tutorial: ship it](user-guide-tutorial-ship-an-app.md) — version 2 of the Order-to-cash App, end to end.
- [Upgrade the platform](admin-guide-howto-upgrade-the-platform.md) — installed Apps stay installed.
