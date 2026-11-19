---
type: Plan
title: module.observability - board row detail
description: The narrative behind module.observability's long rows on the board (docs/BACKLOG.md), moved here verbatim so each board row stays a short summary.
tags: [module, observability, plan, backlog]
timestamp: 2026-11-19T00:00:00Z
item: module.observability
audience: internal
status: plan
verified: 2026-11-19 last-edit
code: features/inspecto-observability
---

# module.observability - board row detail

Each section below is one row of [the board](../../../../BACKLOG.md), headed by its id or name. The board row keeps
the row's priority, a one-line summary of what is open, its state and its tag, and stays the only list of open
work; this page keeps the narrative the row carried, verbatim as it stood on the board on 2026-11-19
(`INFO-RESTRUCTURE-1` W3). Close or narrow the board row first, then trim its section here.

## Open rows

### DEPLOY-EXPOSURE-KIT-1

- `DEPLOY-EXPOSURE-KIT-1` — **`/metrics` and `/health/details` are fenced only by the network, and the deploy kit the docs assume is unbuilt (filed 2026-11-15, verified from `ARCH-MOSCOW-UNTRACKED-1` item 6).** `/metrics` is in `PUBLIC_PATHS` (`ControlApi.java:215-217`; Standard and Advanced) and the server binds every interface unless `-Dcontrol.bind` is set; `/health/details` needs only sign-in, no capability, returns the write and data root paths, and is open on Basic, which has no authenticator. Decide an opt-in fence (loopback-only or sign-in for `/metrics`, a capability for `/health/details`), then build the kit: a reverse-proxy template, preflight, upgrade and bundle-verify scripts, an off-site backup copy (`backup_dir` is path-jailed, so today only a mounted share). Native TLS (`-Dhttps.keystore`) and `backup_verify` already exist. **2026-11-16 (operator, MoSCoW S11):** ship the deploy kit. The two endpoints stay open in the product (no product token); the proxy, TLS and preflight templates and the backup, verify and upgrade scripts make the fence the default install. `hold: operator` removed. **2026-11-16, kit built; the row narrows to its real-host proof (kind feature → chore).** Every non-demo bundle ships `deploy/proxy/` (nginx and Caddy: TLS, the server on loopback, `/metrics` and `/health/details` refused except to an allow-listed scraper network, SSE unbuffered, 64 MB bodies) and `bin/preflight`, `upgrade`, `verify-bundle` and `backup-offsite`, each as `.sh` and `.ps1` (`package.ps1` step 6b-4 stages `deploy/kit/`; `verify-bundle` checks the `.sha256` / `.asc` package.ps1 already emits; `backup-offsite` copies the newest archive that matches its sidecar manifest, leaving the path jail as it is). `tools/deploy-kit.test.mjs` (CI guards) runs them in fake bundles, falsified. Open: run on a real host `nginx -t` and `caddy validate`, a request through each proxy proving the 403 from outside (including encoded and doubled-slash paths) and the pass from the scraper network, the refresh cookie's `Secure` rewrite and SSE through each; `upgrade.sh` against a running systemd service and `upgrade.ps1` against a running Scheduled Task, including the health wait and the rollback restart; `backup-offsite` over rsync and one object-store CLI; `gpg --verify` of a signed release. Guide: `okf/product/layer/platform/admin-guide-deploy-kit.md`.
