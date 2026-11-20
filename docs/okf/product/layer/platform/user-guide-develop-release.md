---
type: How-to
title: "On Inspecto — Release: package, sign and ship your App"
description: "The sixth page of the developer track: version an App and its Platform range, sign it with your own key, verify the package the way a server will, install it into a running server, ship a new version without changing the Spaces already made from it, and remove it."
item: layer.platform
audience: external
status: current
verified: 2026-11-20 code-read
---

# On Inspecto — Release: package, sign and ship your App

## Goal

Ship a tested App as a signed package that a server you trust installs, and ship each later version to that server
without changing the Spaces people already work in.

## Before you start

| You need | Notes |
|---|---|
| an App that passes `test`, with `--bundle`, on every edition it claims | [Test](user-guide-develop-test.md) |
| the **App kit** and Node.js | run from the Inspecto developer kit folder |
| a server to install on, and an administrator there | the install and upgrade routes need the administer capability; the server must be a build of the Platform product with a Spaces folder |

[Tutorial: ship it](user-guide-tutorial-ship-an-app.md) walks every step below on a real App, including version 2.

## Steps

### 1. Set the version and the Platform range

In `app.toon`:

- **`version`** — a semantic version (`1.0.0`, then `1.1.0`, `2.0.0`). Raise it for every package you ship. Keep the
  `version` in each of the App's `template.toon` files in step, so the server can tell a Space which template version
  it came from.
- **`platform`** — the Platform versions the App works with, such as `>=4.0 <5`. A server outside the range refuses the
  package. Widen it only after testing on the new Platform version.

### 2. Make your signing key — once

```bash
node tools/inspecto-app.mjs keygen --key-id acme-signing-1 --out ../keys
```

It writes `acme-signing-1.pem`, your private Ed25519 key — keep it private, out of the App folder and out of any
repository — and `acme-signing-1.properties`, the one line a server adds to trust your packages.

### 3. Package and sign

```bash
node tools/inspecto-app.mjs package --app-root ../acme-spend --key ../keys/acme-signing-1.pem
```

It writes `dist/acme-spend-<version>.zip`: the App folder, a `MANIFEST.sha256` with every file's hash, and
`MANIFEST.sig`, your signature over the manifest naming your key id. A package made without `--key` is not signed,
and no server installs it.

### 4. Verify the package the way a server will

```bash
node tools/inspecto-app.mjs verify --package ../acme-spend/dist/acme-spend-0.1.0.zip --trust ../keys/acme-signing-1.properties
```

It checks every file against the manifest and the manifest's signature against the trusted keys.

### 5. Install on a server

An administrator adds your key to the server once: append the line from `acme-signing-1.properties` to
`conf/app-signers.properties` in the installation folder. Then send the package — first with `?dryRun=true` to see the
plan without changing anything:

```bash
curl -X POST "$API/apps?dryRun=true" -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/zip" --data-binary @../acme-spend/dist/acme-spend-0.1.0.zip
curl -X POST "$API/apps"             -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/zip" --data-binary @../acme-spend/dist/acme-spend-0.1.0.zip
```

`$API` is the server's `/api/v1` address and `$TOKEN` an administrator's access token. The answer is the plan — the
templates and files added, the menu entries — and the installed App. New Spaces can now be created from its
templates.

### 6. Ship a new version

Raise `version`, test and package again, then upgrade the installed App with `PUT /apps/{id}` and the new package
(again `?dryRun=true` first). The upgrade replaces the App's templates and menu, so **new** Spaces get the new
version. **A Space already made from the App is never changed**: the answer lists, for each such Space, every file as
`new`, `update`, `customised` (changed in that Space, left alone) or `dropped`. The Space's administrator reads its
proposal with `GET /spaces/{id}/origin/upgrade` and applies it with `POST /spaces/{id}/origin/upgrade` and the body
`{"toVersion": "<version>"}`. Applying never overwrites a file the Space changed and never deletes one.

### 7. Remove an App

`DELETE /apps/{id}` takes away the templates the App installed and its menu entries. Spaces made from it keep every
file; the answer names them. An App that ships inside the build is not removed this way.

### Another way to ship: inside a bundle

Instead of installing into a running server, the App can ride inside a bundle built for a customer, as an optional App
of the Platform product, with its templates staged from your folder. That build is made from the Inspecto source kit
(`package.ps1 -AppRoot <your App folder>`).

## Check it worked

`GET /apps` lists your App under `installed` (its id, version and `signedBy`) and your key under `signers`. On the
worked example's server, the call answers with both lists:

```check
GET /apps
as admin
expect status 200
expect data.installed exists
expect data.signers exists
```

[Tutorial: ship it](user-guide-tutorial-ship-an-app.md) checks a named App and signer the same way.

and a Space made from a template records which template and version it came from:

```check
GET /spaces/{space}/origin
as admin
expect status 200
expect data.template = "order-to-cash"
```

## What can go wrong

| Answer | Cause | Fix |
|---|---|---|
| `422` — the package does not verify, or its signer is not trusted | unsigned package, a file changed after packaging, or your key's line is not in `conf/app-signers.properties` | package with `--key`; never edit the zip; add the line and retry |
| `422` — the App's Platform range | the server's Platform version is outside `platform` | test on that version, then widen the range |
| `422` — an Add-on the entitlement leaves out | the customer's build may not switch on an Add-on the App requires | the customer needs that Add-on in their entitlement |
| `409` — installed already | the App is installed | ship a new version with `PUT /apps/{id}` |
| `409` — a template collides, or the App ships with the build | the template or App id is taken | rename your template or App |
| `409` — not a Platform product build | the server is a build of a product that does not take installed Apps | install on a Platform product build, or ship inside a bundle |
| `503` | the server has no Spaces folder | install on a server that hosts Spaces |

## Next

- [Requirements](user-guide-develop-requirements.md) — the next version starts here
- [Tutorial: ship it](user-guide-tutorial-ship-an-app.md)
- [App](../product/user-guide-concept-app.md)
