---
type: Concept
title: Case management and objects — implementation spec
description: Packages, classes, stores, config kinds, contributed seams and gates of the inspecto-ops module.
resource: features/inspecto-ops/src/main/java/com/gamma/ops/ObjectService.java
tags: [ops, spec, objects, incidents, cases]
timestamp: 2026-10-19T00:00:00Z
---

# Case management and objects — implementation spec

Module `ops` ([`module.toon`](../../../../../features/inspecto-ops/src/main/resources/META-INF/inspecto/module.toon)),
code under [`features/inspecto-ops/src/main/java/com/gamma/`](../../../../../features/inspecto-ops/src/main/java/com/gamma/).
Domain behaviour (lifecycles, resolve gate, Cases, Findings) is specified in
[plan-incidents §3](../../addon/incidents/plan-incidents.md); routes in [api-routes](api-routes.md).

## Packages

| Package | Contents |
|---|---|
| `com.gamma.ops` | `ObjectService` (object engine + workflow engine), `OperationalObject`, `ObjectStore` with `DbObjectStore` / `InMemoryObjectStore`, `ObjectSubstrate` (read-apply-persist with optimistic-lock retry, compensation cascade), `GovernanceRegistry`, `Impact`, `OpsEngineProvider`, `OpsStoreFamily(Provider)`, `CreateIncidentConsequence`, `OpsLinkedSubjects`, `OpsEditionFeatures`, `ObjectEngineExtension` |
| `com.gamma.ops.link` | `ObjectLink`, `LinkRelationship`, `LinkStore` (Db / InMemory) — append-only correlation edges |
| `com.gamma.ops.note` | `ObjectNote`, `NoteKind`, `NoteService`, `NoteStore` (Db / InMemory) — comments and attachment references |
| `com.gamma.ops.tag` | `Tag`, `TagRule`, `TagAssignmentStore` (Db / InMemory) |
| `com.gamma.opsapi` | `ObjectRoutes`, `NoteRoutes`, `TagRoutes`, `EntityHistory`, `OpsEngine` |
| `com.gamma.opsjob` | `ObjectsAnalyticsJob`, `IncidentPurgeTask`, `OpsJobTypes`, `OpsMaintenanceTasks`, `OpsSignals` |

## What the module contributes (`module.toon`)

- Feature `ops`; config kinds `workflow`, `sla-policy`, `escalation-rule`.
- Contracts: `ConsequenceProvider` (`create-incident`), `JobTypeProvider` (`objects.analytics`),
  `LinkedSubjectProvider` (Incident and Case as Action Request subjects), `MaintenanceTaskProvider`
  (`incident_purge`), `ObjectEngineProvider`.
- Store families `OBJECTS`, `LINKS`, `NOTES`, `TAGS`; background task `sla-sweep`; 43 routes.

## Stores

`OpsStoreFamily` gives each family its own URL property and its own DuckDB file (a file DuckDB holds a
single-writer lock); credentials are shared (`objects.db.user` / `.password`).

| Family | URL property |
|---|---|
| `OBJECTS` | `objects.db.url` |
| `LINKS` | `objects.links.db.url` |
| `NOTES` | `objects.notes.db.url` |
| `TAGS` | `objects.tags.db.url` |

`DbObjectStore` is plain JDBC (DuckDB by default, PostgreSQL by URL), table `inspecto_ops_objects`, with a real
`UPDATE` per transition. In `OpsEngineProvider` a DB that will not open degrades to in-memory rather than failing
the boot; `-Dobjects.backend=postgres` instead refuses the boot unless every family resolves to a
`jdbc:postgresql:` URL ([admin-guide](admin-guide.md)).

## Config loading

- `OpsEngineProvider` runs the ops config loaders: at most one escalation policy (first valid wins); a
  `*_workflow` override is last file wins per object type; an unreadable document is warned and skipped.
  `*_rca` stays in core (`RcaTemplate`).
- `GovernanceRegistry` re-reads `registry/workflows/`, `registry/sla-policies/` and `registry/escalation-rules/`
  per file by modified time and size, so a saved, approved or restored Workflow applies on the next transition.
  A file that fails re-validation keeps its last valid version in force; Escalation Rules are capped at `MAX_RULES`.
- `ObjectEngineExtension` lets another module load its own kinds into the engine (`inspecto-case-management`
  reads `*_caserule.toon`); implementations must be fail-soft per file.

## Gates and errors

`ObjectService` maps an illegal move to `IllegalStateException` (422) and an unknown id to
`NoSuchElementException` (404). Concurrent writes surface as `ObjectVersionConflictException`. Capabilities per
route are in [api-routes](api-routes.md) (`canManageIncidents`, `canWorkIncidents`, `canAdminister`,
`canAuthorWorkbench`).

## Jobs and maintenance

- `objects.analytics` writes rollups as Parquet under `<dataDir>/ops_analytics/` and result-stamps the
  `ops_analytics` Dataset; it reaches the objects in-process because the JDBC store allows no second connection.
- `incident_purge` deletes Archived Incidents past `closedAt + retention_days` with their notes, attachments,
  links and tag edges; a legal hold is fail-safe. Both arrive through `ServiceLoader` loops that run after the
  built-ins, so a contributed name can never shadow a built-in.

Tests: [tests-inventory](tests-inventory.md).
