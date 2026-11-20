---
type: How-to
title: "Package and sign an App"
description: "Make a signing key, package an App into a signed zip with a SHA-256 manifest and an Ed25519 signature, and verify the package before you send it."
item: layer.platform
audience: external
status: current
verified: 2026-11-20 code-reviewed
code: [tools/inspecto-app.mjs]
---

# Package and sign an App

## Goal

Turn your App folder into one signed zip that an Inspecto server will install — and only from a signer it trusts.

## Before you start

- **Edition:** none — this runs on your workstation with the App kit.
- **Who:** the App's developer, holding the signing key.
- **What you need:** an App that validates and passes its conformance
  ([Add golden data and conformance](user-guide-howto-golden-data-conformance.md)), and a folder **outside** the App
  folder for keys.

## Steps

1. Make a signing key once. A key id is lower-case letters, digits and hyphens, and is never reused:

   ```powershell
   node tools/inspecto-app.mjs keygen --key-id o2c-signing-1 --out C:\work\keys
   ```

   It writes the private key `o2c-signing-1.pem` (keep it secret, never in the App folder) and
   `o2c-signing-1.properties` — the one line `o2c-signing-1=<public key>` that a server administrator adds to trust you.
2. Raise `version` in `app.toon` (and in each changed template's `template.toon`) for every release you send.
3. Package and sign:

   ```powershell
   node tools/inspecto-app.mjs package --app-root C:\work\o2c-assurance --key C:\work\keys\o2c-signing-1.pem
   ```

   It writes `dist\o2c-assurance-<version>.zip` and says `signed`. The zip holds the App's files plus
   `MANIFEST.sha256` (a hash of every file) and `MANIFEST.sig` (an Ed25519 signature over that manifest). Dotfiles,
   `node_modules`, `dist`, zips and `.pem` files are left out. The same App always gives the same bytes.
4. Verify the package as a server would:

   ```powershell
   node tools/inspecto-app.mjs verify --package C:\work\o2c-assurance\dist\o2c-assurance-1.0.0.zip --trust C:\work\keys\o2c-signing-1.properties
   ```

   It answers `✓ …: App 'o2c-assurance' 1.0.0, <n> files, signed by 'o2c-signing-1'`.
5. Send the zip and the `.properties` line to the server's administrator.

## Check it worked

`verify` exits with 0 and names your key. On the server, once the administrator has added your line to
`conf\app-signers.properties`, your key is listed among the trusted signers — no restart needed:

```check
GET /apps
as admin
expect status 200
expect data.signers has o2c-signing-1
```

## What can go wrong

- `NOT signed - a server installs only a signed package` — you left out `--key`.
- `the App does not validate - fix it first:` — `package` refuses an App that does not validate.
- `… exists already - a key id is never reused` — pick a new key id for a new key.
- `'inspecto-issuer-1' is an issuer key id - a partner key takes an id of its own` — that id is Inspecto's own.
- `signed by key 'x', which is not trusted here` (from `verify`) — pass `--trust` the `.properties` file of the key
  that signed it.
- `the manifest signature does not match…` or `<file> does not match its manifest hash` — the zip was changed after
  signing; package it again.

There is no key revocation or rotation step yet: to stop trusting a key, the administrator removes its line.

## Next

- [Install an App into a running server](user-guide-howto-install-an-app.md)
- [Upgrade an App](user-guide-howto-upgrade-an-app.md)
