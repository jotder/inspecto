---
type: Guide
title: Case Management — admin guide
description: Enabling the Case Management module, capabilities, Case Rule files, scheduled evaluation, storage and common failures.
resource: features/inspecto-case-management/src/main/resources/META-INF/inspecto/module.toon
tags: [case-management, admin-guide, case-rule, configuration]
timestamp: 2026-10-19T00:00:00Z
---

# Case Management — admin guide

## Enabling

Optional module `inspecto-case-management` (module id `case-management`, feature id `cases`,
`bindingTime: boot`), add-on `caseManagement` in the Standard Offering and above; it `requires` module `ops`
([module.toon](../../../../../features/inspecto-case-management/src/main/resources/META-INF/inspecto/module.toon)).
Absent, Case Rules, merge / split and from-entities are not installed.

## Permissions

| Action | Capability |
|---|---|
| save / delete a Case Rule | `canAuthorWorkbench` |
| evaluate a rule, merge, split | `canAdminister` (+ data-scope guard on merge / split) |
| open a Case from entities | `canManageIncidents` (a classified Dataset also needs `canRevealLinkEntities`) |
| Case transitions | `canWorkIncidents` |

Detail: [API](api-case-management.md), [architecture § 8](architecture-overview.md).

## Configuration

- Case Rules: `*_caserule.toon` files with a `case_rule { ... }` block, loaded at boot (an invalid file is
  skipped with a warning), or saved through `POST /cases/rules`. Keys: [architecture § 5](architecture-overview.md).
- Scheduled evaluation: a Job of Type `caserule.evaluate` with parameter `rule` ([jobs](../processor/spec-jobs.md)).
- SLA: an SLA Policy accepts `objectType: CASE`; `CLOSED` stops the clock.
- Storage: `-Dobjects.backend=db|postgres` selects the ops object store ([DB layer](../engine/spec-db-layer.md)).

## Common failures

| Symptom | Cause |
|---|---|
| 422 on from-entities | more than 100 entities in one call |
| merge refused | non-Case, merge into itself, or a closed / already-merged source |
| split refused | the Case is closed, or a member it does not contain |
| 409 `CONFLICT_STALE_VERSION` | concurrent write on the same Case; reload and retry |
| a Case Rule never opens a Case | `threshold` not met within `windowMinutes`, or the Incidents are already in a Case |
| evaluation reports nothing | run under a dry run (the `objects` service is substituted) |
