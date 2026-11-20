---
type: How-to
title: "Create an App with the App kit"
description: "Scaffold a new App folder with the App kit — app.toon, conformance.toon, a Space Template with a Pipeline, two Datasets, a Job and sample data — and validate it."
item: layer.platform
audience: external
status: current
verified: 2026-11-20 code-reviewed
code: [tools/inspecto-app.mjs]
---

# Create an App with the App kit

## Goal

Start a new **App** — your Space Templates, golden data and the Add-ons they need, under one id and version — from
the App kit's starter, and see it validate.

## Before you start

- **Edition:** none needed to create it — the App kit runs on your workstation. The App later installs on the
  editions whose Add-ons cover what it declares (Standard and up for most Apps).
- **Who:** a partner or customer developer. No Inspecto Role is needed.
- **What you need:** Node.js and the Inspecto developer kit folder (the App kit, `tools/inspecto-app.mjs`, runs from
  it). An App id: lower-case letters, digits and hyphens, 2 to 41 characters, starting with a letter.

Concepts: [App](../product/user-guide-concept-app.md) ·
[Space Template](../../module/config/user-guide-concept-space-template.md).

## Steps

1. Pick an App id that no Space Template on your target server already uses. An App never replaces a template the
   server ships; the check below lists those ids.
2. Scaffold the App into a new, empty folder:

   ```powershell
   node tools/inspecto-app.mjs new o2c-assurance --dir C:\work\o2c-assurance --title "Order-to-cash assurance" --addons incidents,reconciliation
   ```

   `--addons` takes the Add-on ids your App needs (for example `incidents`, `caseManagement`, `reconciliation`,
   `screening`); `--industry <id>` is optional. The kit answers
   `✓ App 'o2c-assurance' scaffolded in C:\work\o2c-assurance (10 files)`.
3. Look at what it wrote:
   - `app.toon` — id, title, `version`, the Platform range it runs on (`platform: >=4.0 <5`), the Add-ons it
     `requires`, its templates, menu and docs.
   - `conformance.toon` — the golden expectations the App is held to
     ([Add golden data and conformance](user-guide-howto-golden-data-conformance.md)).
   - `docs/index.md` — the App's own page.
   - `templates/o2c-assurance/` — one Space Template with the same id as the App: `template.toon`, a Pipeline and its
     Schema, two Datasets, a Job and a sample CSV.
4. Validate it:

   ```powershell
   node tools/inspecto-app.mjs validate --app-root C:\work\o2c-assurance
   ```

   It answers `✓ … the App validates`.
5. Now make the template yours: put your Space's configuration into `templates/<id>/config/` and its sample files into
   `templates/<id>/data/samples/<feed>/`.
   [Tutorial: ship it](user-guide-tutorial-ship-an-app.md) does this with the Order-to-cash Space.

## Check it worked

`validate` exits with 0 and says the App validates. On the server you will install to, the template ids it already
ships are listed here; your App's id must not be one of them:

```check
GET /spaces/templates
as admin
expect status 200
expect data[*].id has order-to-cash
```

## What can go wrong

- `App id 'Bad_Id' must be lower-case letters, digits and hyphens, 2 to 41 characters, starting with a letter` —
  choose another id.
- `… is not empty - a new App goes into an empty or new folder` — `new` never writes into a folder that has files.
- `requires Add-on 'bogus', which no Offering names` (from `validate`) — an Add-on id is misspelled.
- A template id that equals a template the Platform ships is refused when you validate or test; rename it.
- The starter has one template and one feed; there is no flag for a second one — copy the folders and list them in
  `app.toon` yourself.

## Next

- [Add golden data and conformance](user-guide-howto-golden-data-conformance.md)
- [Package and sign an App](user-guide-howto-package-and-sign-an-app.md)
