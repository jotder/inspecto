---
type: Guide
title: Regulatory Reporting — admin guide
description: Enabling Regulatory Reporting, who may draft and approve, templates and drop folders, report storage, backup exclusion and common failures.
resource: features/inspecto-regulatory-reporting/src/main/resources/META-INF/inspecto/module.toon
tags: [regulatory-reporting, admin-guide, four-eyes, configuration]
timestamp: 2026-10-19T00:00:00Z
---

# Regulatory Reporting — admin guide

## Enabling

Optional module `inspecto-regulatory-reporting` (module id `regulatory-reporting`, feature id
`regulatoryReporting`, `bindingTime: boot`), add-on `regulatoryReporting` of Standard edition and above; it
`requires` the module `ops`
([module.toon](../../../../../features/inspecto-regulatory-reporting/src/main/resources/META-INF/inspecto/module.toon);
[offerings/standard.toon](../../../../../offerings/standard.toon)). Absent, every `/regulatory-reports*` route
answers 503 naming the module. A Space can switch it off in `modules.toon` (`GET|PUT /settings/modules`).

## Permissions

| Act | Capability |
|---|---|
| read templates and reports, verify a delivered file | `canWorkIncidents` or `canApproveChanges`, and the report's Case / Incident visible to the caller (else 404) |
| draft, request approval | `canWorkIncidents` |
| approve, decline, retry; author or delete a Space template | `canApproveChanges` |

Deciding is always four-eyes: the author and whoever requested approval are makers and are refused (403), and
under OIDC the approver must be on the Space approver roster (`ApproverRoster.requireOnRoster`). Gate order:
[spec, *Lifecycle and gates*](spec.md).

## Configuration

| Item | Where | Meaning |
|---|---|---|
| Space templates | `<config root>/regulatory-report-templates/<id>.toon`, or `PUT /regulatory-reports/templates/{id}` | a Space template of a built-in id overrides it |
| Built-in templates | module classpath `META-INF/inspecto/regulatory-templates/` | `sample-sar` (illustrative) |
| XSD | `schema: <file>.xsd` beside the Space templates, else beside the built-ins | xml templates only; no external imports |
| Drop folder | template `delivery.dir` | relative = against the Space config root; must pass the path jail |
| Expiry | `PendingChanges.expiryHours` | a draft or pending report past `expiresAt` becomes `expired` |

## Storage and retention

One JSON per report at `<write-root>/regulatory-reports/<id>.json`, atomic, jailed, signed with the Space's
Pending Change key (domain `regulatory-report`). The directory is reserved against import, skipped by Space export
and by backup: a restored Space has no report records; the delivered files (with their `.sha256` sidecars) and
the audit chain are the durable trail. No automatic purge is documented ([spec, *Storage*](spec.md)).

## Common failures

| Symptom | Cause |
|---|---|
| 503 on `/regulatory-reports*` | module not in the bundle, no write root, or no object engine (`ops`) |
| 403 on draft | a member Incident is outside the caller's scope (partial filing refused) |
| 403 on approve | the caller is a maker, or not on the OIDC approver roster |
| 409 on decide | record MAC failed (`invalid`), or the status is wrong (including just expired) |
| 422 on draft | unknown template or one that does not load, bad inputs, drop folder refused by the path jail, render or XSD validation failed |
| 409 on template delete | built-in with no Space override, or still referenced by a report |
| status `failed` | delivery to the drop folder failed; `retry` re-submits the same bytes to the same file name |
| `verify` reports `mismatch` / `missing` | the delivered file or sidecar changed or was removed after submission |
