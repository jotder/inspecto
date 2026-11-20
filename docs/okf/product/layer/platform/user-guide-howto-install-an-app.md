---
type: How-to
title: "Install an App into a running server"
description: "Trust the App's signer, preview the install, install the signed package through the Control API without a restart, and create a Space from the App's template."
item: layer.platform
audience: external
status: current
verified: 2026-11-20 code-reviewed
code: [inspecto/src/main/java/com/gamma/control/AppInstallRoutes.java]
---

# Install an App into a running server

## Goal

Install a signed App package into a server that is already running, so its Space Templates appear in
**New from template** — no restart, no new bundle.

## Before you start

- **Edition:** a Platform build of Standard, Advanced or Preview whose entitlement lists every Add-on the App
  requires. On Basic there is no sign-in, so anyone who reaches the server can install.
- **Who:** an administrator (capability `canAdminister`).
- **What you need:** the App's zip and its signer's `.properties` line
  ([Package and sign an App](user-guide-howto-package-and-sign-an-app.md)), an administrator's access token, and a
  shell on a machine that can reach the server. Installing has no console screen: it is a Control API call.

## Steps

1. **Trust the signer, once.** In the installation folder, append the signer's line (`o2c-signing-1=<public key>`) to
   `conf\app-signers.properties` (create `conf\` if needed). The server re-reads it on every request.
2. **Preview** — nothing changes; the answer lists every template file the App would add:

   ```powershell
   $api = 'https://inspecto.example.com/api/v1'
   $h   = @{ Authorization = "Bearer $token" }
   $zip = 'C:\work\o2c-assurance\dist\o2c-assurance-1.0.0.zip'
   Invoke-RestMethod -Method Post -Uri "$api/apps?dryRun=true" -Headers $h -ContentType 'application/zip' -InFile $zip
   ```

3. **Install** — the same call without `?dryRun=true`. The answer names the App, its version, `signedBy` and its
   templates. The files go to the server's `apps\<id>\` folder.
4. **Use it:** **Settings → Spaces → New from template**, pick the App's template, give the Space an id and click
   **Create space**.
5. On a set of several servers, repeat the install on **each** server: an install lands on the server that answered.

To remove an App later: `DELETE /apps/<id>` (add `?dryRun=true` first). Its Spaces are kept.

## Check it worked

The server lists the App as installed and its signer as trusted, and a Space can be made from its template:

```check
GET /apps
as admin
expect status 200
expect data.installed[*].id has o2c-assurance
expect data.signers has o2c-signing-1
```

```check
POST /spaces
as admin
body {"id": "{space}", "template": "o2c-assurance", "loadSamples": true}
expect status 200|409
```

## What can go wrong

- 422 `the package is refused: the package is not signed (no MANIFEST.sig)` — package it with `--key`.
- 422 `… signed by key 'x', which this install does not trust` — step 1 is missing or the line is wrong.
- 422 `App 'x' needs Platform >=…, and this server is …` — the App was built for another Platform version.
- 422 `needs Add-on(s) […], which this installation's entitlement does not list` — the server's edition or
  entitlement lacks an Add-on the App requires.
- 409 `App 'x' is installed already (version N) - upgrade it with PUT /apps/x` — see
  [Upgrade an App](user-guide-howto-upgrade-an-app.md).
- 409 `a template this build ships - an App never replaces it` — the App reuses a shipped template id.
- 409 when the server is not a Platform build; 503 when it hosts no Spaces root; 403 for a non-administrator.
- **New from template** answers 403 for a template with an Alert Rule unless you may author Alert Rules
  (`canAuthorAlertRules` — the seed `power` Role has it, `admin` alone does not).

## Next

- [Upgrade an App](user-guide-howto-upgrade-an-app.md)
- [Tutorial: ship it](user-guide-tutorial-ship-an-app.md)
