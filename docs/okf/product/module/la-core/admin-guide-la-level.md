---
type: Guide
title: Inspecto LA - the Link Analysis level, the Investigations add-on and the masking default
description: How an administrator runs Link Analysis at the Explorer or the Investigations level, what changes for users, and what decides the masking default.
tags: [module, la-core, link-analysis, editions, add-on, masking, admin-guide]
timestamp: 2026-10-22T00:00:00Z
---

# The Link Analysis level, the Investigations add-on and the masking default

*As built 2026-10-22 (`LA-ONBOARD-1` phase 1: OB-5, OB-6, OB-10). Design and seams:
[architecture](architecture.md) §8 "Trust vs level"; placement: [`EDITIONS.md`](../../../../EDITIONS.md).*

Three things are decided separately:

| Question | Decided by | Where |
|---|---|---|
| Who the user is: sign-in, roles, enforced masking | The **edition** (Basic: no sign-in; Standard and above: sign-in) | The bundle you install |
| Which Link Analysis screens exist | The **LA level**: *Explorer*, or *Investigations* when the installation holds the Investigations add-on | The install setting below |
| Where data comes from | The Space (`LA-ONBOARD-1` phase 2) | Space settings |

## The Investigations add-on

- **Order Code** `INS-AO-LA-INVESTIGATIONS` (Product Map `addon.laInvestigations`), billable, **included by default on
  Standard, Advanced and Preview**. Basic never has it.
- **Remove it** from an installation by starting the server with the JVM setting `-Daddons.laInvestigations=false`, for
  example `set "INSPECTO_JAVA_OPTS=-Daddons.laInvestigations=false"` in the `.bat` that calls `serve.bat` (any value other
  than `true` removes it). Restart to change it.
- **What users see without it:** Link Analysis at the Explorer level - the graph, Grow, the browser algorithms, Entity Lists
  and saved views. Investigation actions stay on screen, **locked**, with one line saying why ("Needs the Investigations
  add-on, which this installation does not hold."). Sign-in, roles and enforced masking are unchanged.
- **What the API answers without it:** every Investigation route answers `503 CAPABILITY_UNAVAILABLE` naming the add-on and
  its Order Code; `GET /api/v1/bootstrap` reports `laLevel: "explorer"` and `featureReasons.investigations`.
- ⚠ Until a signed entitlement exists, whoever runs the server can claim the add-on (the setting is a plain JVM flag).

Check an installation: `GET /api/v1/bootstrap` → `data.laLevel` is `investigations` or `explorer`, and `data.featureReasons`
lists what is withheld (empty when nothing is).

## The masking default follows sign-in

A Space that does not state a `masking_mode` (Settings → Link Analysis) masks entity ids as `typed` wherever people sign in,
**with or without the Investigations add-on**, and masks nothing where nobody signs in (Basic). A mode the Space states always
wins. `GET /api/v1/settings/link-analysis` → `maskingModeInForce` shows the mode in force.
