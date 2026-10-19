---
type: Guide
title: Demo authenticator — admin guide
description: Build and run a demo bundle with Demo User sign-in, author demo-users.toon, and read its boot and sign-in failures.
resource: providers/inspecto-demo-auth/
tags: [module, demo-auth, admin-guide, demo, packaging]
timestamp: 2026-10-19T00:00:00Z
---

# Demo authenticator — admin guide

> ⚠ Demo sign-in has **no password**: anyone who can reach the port signs in as any Demo User. It is for
> offline, single-machine demos and evaluation only. It is never staged in a customer bundle.

## Build

```
inspecto\package.ps1 -Edition Advanced -DemoAuth -Ui la-app
```

`-DemoAuth` accepts `-Edition Advanced` or `-Edition Preview` only. It builds `inspecto-demo-auth.jar`, removes
`inspecto-oidc.jar` from the bundle, and writes `serve-demo.bat` / `serve-demo.sh`. Known defect: the jlink step
fails on this combination; add `-NoRuntime` until `MODULE-REORG-P3-THIN-JARS` (1) is fixed
([BACKLOG](../../../../BACKLOG.md)). Full walk-through:
[admin-guide-local-testing](../../layer/platform/admin-guide-local-testing.md) (Path A) and, for the Link
Analysis demo, [admin-guide-demo](../la-core/admin-guide-demo.md).

## Run

`serve-demo` passes the required flags:

| Flag | Why |
|---|---|
| `-Dcontrol.bind=127.0.0.1` | Mandatory. Any non-loopback value, or none, makes the module refuse to load and the server fail to boot. |
| `-Dauth.mode=demo` | Tells the SPA sign-in is required and to show the picker. |
| `-Dspaces.root` / `-Dassist.write.root` | Where Demo Users are read from (all Spaces, or the single legacy root). |

There are no IdP settings, certificates or keystores. Do not expose the port through a proxy or tunnel: that
defeats the loopback guard.

## Author Demo Users

Per Space, `<space>/config/demo-users.toon`:

```
users[2]{id,displayName,title,roles,landing}:
  ra.analyst,Demo RA Analyst,Revenue Assurance analyst,operations,
  admin,Demo Admin,Platform administrator,admin;super,
```

* `roles` — `;`-separated names from that Space's `roles.toon` (over the built-in seed); unknown names grant nothing.
* `landing` — optional menu item id from the Space's `nav-menus.toon`; blank means the Space's landing.
* Edits apply immediately (the file is re-read on every call). No committed Space ships this file.
* An id may appear in several Spaces only with identical definitions.

## Failure modes

| Symptom | Cause and fix |
|---|---|
| Boot fails: "inspecto-demo-auth refuses to load: set -Dcontrol.bind=127.0.0.1" | Started without the loopback bind; use `serve-demo`. |
| Log block "DEMO SIGN-IN IS DISABLED"; sign-in answers 409 `DEMO_ROSTER_CONFLICT` | The same Demo User is defined differently in two Spaces. Align or remove one `demo-users.toon`; no restart needed. |
| Picker empty | No Space under `spaces.root`, or none defines Demo Users. |
| 401 right after sign-in | The bundle is not a `-DemoAuth` build, or the user was removed from the roster. |
| Everyone signed out | The server restarted (new signing secret, by design). |
| Signed in but menus missing | The user's role names do not exist in that Space's role table. |
