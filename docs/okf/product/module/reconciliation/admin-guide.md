---
type: Guide
title: Reconciliation — admin guide
description: Enabling Reconciliation, its capabilities, the recon.run Job, the day-cache flag, run-state storage and retention, and common failures.
resource: features/inspecto-reconciliation/src/main/resources/META-INF/inspecto/module.toon
tags: [reconciliation, admin-guide, configuration, retention]
timestamp: 2026-10-19T00:00:00Z
---

# Reconciliation — admin guide

## Enabling

Optional module `inspecto-reconciliation` (module id `reconciliation`, feature id `reconciliation`,
`bindingTime: boot`), add-on `reconciliation` of Standard edition and above
([module.toon](../../../../../features/inspecto-reconciliation/src/main/resources/META-INF/inspecto/module.toon);
[offerings/standard.toon](../../../../../offerings/standard.toon)). It contributes the config kind
`reconciliation`, the Job Type `recon.run` and ten `/recon/*` routes; absent, those routes answer 503 with the
module's `absentMessage`. A Space can switch it off in `modules.toon` (`GET|PUT /settings/modules`).

Promotion to Incidents and the Job's breach Incident need the `objects` Platform Service from `inspecto-ops`;
without it `POST /recon/promote` answers 503 and the Job stays signal-only.

## Permissions

| Act | Capability |
|---|---|
| create / edit / delete a Reconciliation (`/components/reconciliation`) | `canAuthorWorkbench` |
| record a run, change Break status / assignee (`/recon/{id}/record`, `/recon/{id}/breaks/status`) | `canOperateRuns` |
| promote a Break to an Incident (`POST /recon/promote`) | `canManageIncidents` |

The compute and read routes (`/recon/run`, `/recon/breaks`, `/recon/rows`, `/recon/columns`, the state reads)
carry no capability of their own ([api-routes](api-routes.md)).

## Configuration

| Key | Default | Meaning |
|---|---|---|
| `-Drecon.cache.max_bytes` | max heap / 8, clamped 64 MiB..1 GiB | byte budget of the per-day result cache (`ReconDay.Cache`) |
| `recon.run` Job, `reconciliation` | required | saved Reconciliation id |
| `recon.run` Job, `day` | latest day on any side | `yyyy-mm-dd` |

Every side's Dataset must declare a temporal column (`role: temporal`) or a `dateField`; the component keys are in
[architecture § 5](architecture-overview.md).

## Storage and retention

One JSON per Reconciliation at `<write-root>/recon-state/<id>.json`, written atomically, jailed under the write
root. A run with more than 50 000 Breaks is refused; days older than the 90 most recent recorded days are dropped
whole (open Breaks included); the file holds at most 200 000 Breaks. Deleting the component deletes its state
(`ReconComponentDeleteHook`). Detail: [spec](spec.md).

## Common failures

| Symptom | Cause |
|---|---|
| 503 on `/recon/*` | module not in the bundle |
| 503 on `POST /recon/promote` | `inspecto-ops` absent (no `objects` service) |
| 422 naming a side | that side's Dataset has no temporal column; there is no whole-period fallback |
| 422 on save | bad `bands` (`0 <= okBelow <= warnAbove <= 100`) |
| every key missing on *Test definition* | sides dated at different grains; the latest day on any side was read (`RECON-UPLIFT-RESIDUALS-1` (3)) |
| a scheduled run succeeds but records nothing | recording from the Job is best-effort; the refusal (e.g. over `MAX_BREAKS`) is in the log |
| a resolved Break reads open again | Break identity drift between SPA and server (`ReconBreaks.keyText`) |
| a Job fire `SKIPPED` | the module is switched off in that Space |
