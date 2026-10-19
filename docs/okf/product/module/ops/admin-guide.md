---
type: Concept
title: Case management and objects — admin guide
description: Enabling the operational-object module, its capabilities, the object backend flags, SLA sweep, retention and common failures.
resource: features/inspecto-ops/src/main/java/com/gamma/ops/OpsStoreFamily.java
tags: [ops, admin-guide, operations, incidents]
timestamp: 2026-10-19T00:00:00Z
---

# Case management and objects — admin guide

The long-form operator reference is the platform
[operations reference](../../layer/platform/admin-guide-operations-reference.md) ("Object backend", "Incident
Tracker", "Case Management", `incident_purge`). This page is the module checklist.

## Enabling

- Module `ops` is optional and bound at boot
  ([`module.toon`](../../../../../features/inspecto-ops/src/main/resources/META-INF/inspecto/module.toon)), staged in
  Standard and every higher Edition — add-ons `incidents` and `workflowSla`
  ([`offerings/standard.toon`](../../../../../offerings/standard.toon)).
- Absent, every object, note, tag and workflow route answers 503 with the module's absent message, Alert Rules are
  refused, and `incident_purge` / `objects.analytics` are unknown names.

## Permissions

| Action | Capability |
|---|---|
| Create an object | `canManageIncidents` |
| Ack, assign, transition, resolve, category, impact, postmortem | `canWorkIncidents` |
| `PATCH /objects/{id}` (priority, severity, assignee, attributes, `legalHold`) | `canAdminister` |
| Create, rename, delete Tags and Tag rules | `canAuthorWorkbench` |

Every route: [api-routes](api-routes.md).

## Configuration

| Key | Default | Effect |
|---|---|---|
| `-Dobjects.backend` | `db` | `db` (DuckDB in the Space's `duckdb/`), `postgres` (refuses boot unless every family is a `jdbc:postgresql:` URL), `memory` (non-durable opt-in); any other value fails the boot |
| `-Dobjects.db.url` · `objects.links.db.url` · `objects.notes.db.url` · `objects.tags.db.url` | per Space | one URL per store family |
| `-Dobjects.db.user` / `-Dobjects.db.password` | — | credentials shared by the four families |
| `-Dobjects.sla.sweep.seconds` | `60` | SLA sweep cadence; `0` disables it |

Config kinds: `workflow`, `sla-policy`, `escalation-rule` (in `registry/`, picked up without restart), plus
`*_workflow.toon` overrides read at boot.

## Retention

Nothing is purged by default. Stand up a maintenance Job with `task: incident_purge` and a required
`retention_days` (optional `max_count`, default 1000); run it dry first. A `legalHold` attribute keeps an Incident;
the event-log history is never purged. Recipe: [operations reference](../../layer/platform/admin-guide-operations-reference.md).

## Health and common failures

| Symptom | Cause |
|---|---|
| 503 on `/objects` | module not in the bundle |
| Objects lost on restart | `-Dobjects.backend=memory`, or a `db` store that would not open and degraded to in-memory (check the boot log) |
| Boot refused with `postgres` | a family's URL is not `jdbc:postgresql:` |
| 422 on resolve of an Incident | the resolve gate (timeline, cause analysis, corrective action, `dueAt`) is not met |
| An edited Workflow did not apply | the file failed re-validation; its last valid version stays in force (WARN logged) |
| SLA breaches never appear | sweep disabled (`0`) or no `dueAt` / `dueInMinutes` set on the Incident |
