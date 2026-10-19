---
type: Plan
title: Demo authenticator — plan of record
description: DEMO-AUTH-1 — passwordless Demo User sign-in for offline demo bundles; what shipped, what was decided, what is left.
resource: providers/inspecto-demo-auth/
tags: [module, demo-auth, plan, demo]
timestamp: 2026-10-19T00:00:00Z
---

# Demo authenticator — plan of record

**Requirement (DEMO-AUTH-1):** evaluate Advanced capabilities on one machine with no identity provider and no
Postgres, signed in as named Demo Users who hold real roles, so a demo sees exactly the access production would
give the same roles. It must never be able to reach a customer bundle.

## Built

* `DemoTokenRelay` and `DemoAuthenticator` behind the existing `Authenticator` / `TokenRelay` contracts; no
  new route ([spec](spec.md)).
* Per-Space Demo User roster `demo-users.toon`, with an optional landing menu item (UIE-7).
* Sign-in picker in the SPA under a "Not secure, local only" notice
  (`inspecto-ui/src/app/inspecto/auth/sign-in.component.ts`).
* Packaging: `inspecto/package.ps1 -DemoAuth` swaps `inspecto-oidc.jar` for `inspecto-demo-auth.jar` and writes
  `serve-demo` launchers.
* Guardrails: loopback-only binding (`LoopbackOnly`), and the CI guard `tools/check-demo-auth-isolation.mjs`.
* Global identity across Spaces (fix 2026-09-25) and the typed roster-conflict refusal `DEMO_ROSTER_CONFLICT`.

## Decided

* **D-4** no password and no IdP; **D-5** a random signing secret per JVM, so a restart signs everyone out.
* Roster conflicts do not stop the server: sign-in answers 409 until the rosters agree.

## Left

* `MODULE-REORG-P3-THIN-JARS` packaging defect (1): `package.ps1 -Edition Advanced -DemoAuth -Ui la-app`
  fails at the jlink step because the derived module set expects `inspecto-oidc.jar`; `-NoRuntime` works around
  it ([BACKLOG](../../../../BACKLOG.md)).
* `LA-EDITIONS-RESIDUALS-1` (7): a jlink advisory fires for demo builds.
