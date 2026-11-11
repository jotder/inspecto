---
type: Feature
title: "Platform — installing Apps on a running server"
description: "How an administrator installs, upgrades and removes a signed App on a running Platform product build: the trusted signers, the dry run, what an upgrade offers each Space, and what removal keeps."
tags: [platform, admin-guide, app, install, upgrade]
timestamp: 2026-11-11T00:00:00Z
---

# Platform — installing Apps on a running server

A **Platform product** build (`products/inspecto.toon`) can take an App while it runs: a partner's App made with the App
kit, or a first-party one. The App must be a **signed package**. Its signer must be trusted here, and this install keeps
every Space's own configuration. (Product assembly PA-7d; decisions D-PA22…D-PA25.)

## Trusted signers

An App package is signed with `node tools/inspecto-app.mjs package --app-root <app> --key <signer.pem>`. The server
installs it only when the signer's key id is trusted:

- **first-party Apps** are signed with the issuer key the core jar carries (`inspecto-issuer-1`);
- **a partner's Apps** are signed with their own key. The partner runs `inspecto-app.mjs keygen --key-id <id>` and
  sends you `<id>.properties`, and you add its one line to `conf/app-signers.properties` in the bundle. Set
  `-Dapps.signers` to keep the file elsewhere.

A key id never redefines one that is already trusted. `GET /apps` lists the trusted signers.

## Install, upgrade, remove

All three are administrator-only (`canAdminister`). Each takes `?dryRun=true` to answer the plan and change nothing.

| Request | Body | What it does |
|---|---|---|
| `GET /apps` | — | The build's own Apps, the Apps installed here, the trusted signers, and whether this server can install (`liveInstall`) |
| `POST /apps` | the package zip (`application/zip`) | **Install.** The App's templates go into the Space gallery and its nav into `/bootstrap`, at once. |
| `PUT /apps/{id}` | the new package | **Upgrade.** The templates and nav are replaced, so new Spaces get the new version. Existing Spaces are offered the difference (below). |
| `DELETE /apps/{id}` | — | **Remove.** The App's templates and nav go. Spaces made from it keep every file and are listed in `spacesKept`. |

The App itself is unpacked into the install's `apps/<id>/` folder (`-Dapps.root`). Its templates go into
`spaces/_templates/<template>/`, each marked as the App's, so a template the build ships is never replaced and removal
takes only the App's own.

### What an upgrade offers each Space (D-PA25)

A Space made from one of the App's templates records where it came from (`<space>/.inspecto/created-from.toon`). An
upgrade **never changes that Space.** It answers, per such Space and per config file, one of four changes:

- `new`: the new template adds a file the Space does not have;
- `update`: the Space has not changed the file since it was created, so taking the new file loses nothing;
- `customised`: the Space changed the file, so it is left alone;
- `dropped`: the new template no longer ships a file the Space has. The Space keeps it.

Whether a file changed is judged against its SHA-256 when the Space was created (`<space>/.inspecto/created-from.sha256`).
That holds whichever App version made the Space, and whatever the server stamped onto a Job at creation. An
administrator applies an offered change through the normal configuration routes, which keeps the config-safety check
and the audit trail. A Space made before PA-7d records no origin, so it is offered nothing.

## Refusals

- **503:** the server hosts no Spaces root (`-Dspaces.root`), so a template has nowhere to go.
- **409:**
  - the server is not a Product build, or is a Domain product (its Apps are its definition);
  - the App is installed already (use `PUT`), or is not installed (use `POST`), or ships with the build;
  - a template collides with one the build ships or another App owns.
- **422:** the package does not verify. That covers an unsigned package, an untrusted signer, a changed file, an
  unlisted or missing file, a path that escapes its folder or that Windows would rewrite (a trailing dot or space, a
  reserved character or device name, two names that differ only in case, a hidden file), two App folders, or an
  `app.toon` whose id is not the folder. It also covers an App whose `platform` range this server does not satisfy, and, with an entitlement
  installed, an Add-on it does not list.
- **423:** the install is read-only (an expired entitlement, see the
  [entitlements guide](admin-guide-entitlements.md)).

Every install, upgrade and removal is audited (`app.installed`, `app.upgraded`, `app.removed`).

## References

- The design: `docs/superpower/2026-11-10-product-assembly-plan.md` §11 (PA-7) and §12 (D-PA22…D-PA25). Making and
  signing an App: the [builder-kit guide](user-guide-builder-kit.md) §5.
- Code: `AppInstallRoutes`, `InstalledApps`, `AppPackage` (`com.gamma.control`). Test: `ControlApiAppInstallTest`.
