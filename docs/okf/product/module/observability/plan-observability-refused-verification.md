---
type: Capability
title: Observability & maintenance (OPS) — capability spec — Refused, as-built pointers and verification
description: Part 4 of 4 of Observability & maintenance (OPS) — capability spec: Refused & superseded; As-built pointers; Verification
status: current
item: module.observability
audience: internal
verified: 2026-11-19 last-edit
code: features/inspecto-observability
---

# Observability & maintenance (OPS) — capability spec — Refused, as-built pointers and verification

Part 4 of 4 of [Observability & maintenance (OPS) — capability spec](plan-observability.md) · previous: [Specification — Ops Lens screens; decisions and not built](plan-observability-ops-lens-decisions.md).

## 6. Refused & superseded

| Item | Verdict | Why / source |
|---|---|---|
| Per-step lifecycle Signals | **Refused** 2026-08-12 | a Signal is a durable write; steps × batches multiply it, and mid-batch Signals invite `on_signal` work while the claim is held — "no consumer needs a durable copy of a gauge" |
| A message broker (Kafka/Redis/RabbitMQ) for events or notifications | **Not used, by decision** 2026-06-29 | in-process virtual threads + append-only store |
| A defaulted `retention_days` on any deleting task | **Refused** | "forgetting is a policy decision and a wrong default is silent data loss" — `cleanup`'s 7 is the sole exception |
| A shipped or scheduled `incident_purge` | **Refused** | indefensible default; standing it up is an operator act |
| Archive-is-terminal for Incidents | **Superseded** by D5 (2026-07-25) | it would make "archived" mean "kept forever" |
| Four per-store `JobService` hooks for the purge cascade, "fail-closed on partial attachment" | **Premise wrong** | `ObjectService` already holds all four stores; per-store nullable hooks would *reintroduce* the half-cascade hazard — ⛔ do not add them |
| A computed / semaphore-derived DuckDB cap; a `max_temp_directory_size` default; capping preview connections | **Refused** by D11, still standing | `d11-resource-caps-plan.md` (archive); BACKLOG §6 |
| `-D` capability switches for edition gating | **Refused** 2026-09-07 | a switch leaves the code in the Basic bundle — ServiceLoader modules instead |
| Gating the audit CSV alone (an `if` inside core `/events/export`) | **Refused** 2026-09-08 | §Assembly bans conditionals inside core routes; the feed moved whole and a core `AuditLogRoutes` was added |
| `scheduler` / `trigger` as Component kinds | **Deliberately not kinds** | cron and event are mutually exclusive job fields with no second consumer |
| A full `ConsignmentEventBus` → Signal migration | **Not attempted** | `ConsignmentAuditWriter` emits additively; the run-claim seam makes a bus migration hazardous |
| Growing the `EventType` enum for business signals | **⛔ Standing** | they ride `Signal` on one `SIGNAL` Event |
| Conscripting `ConservationCheck` into the completeness KPI | **⛔ Refused** | node-level conservation is a different invariant |
| Inventing a **declared expected row count** to measure record deviation against | **⛔ Refused** 2026-08-30 | none exists; a fabricated target makes every deviation an artefact of the fabrication. Record deviation is statistical, off a rolling prior-period baseline — §3.9 |
| Reporting **0** where a sink's `bounds` is null, or where the registry is off | **⛔ Refused** | UNKNOWN and REFUSE respectively; a quiet zero manufactures confidence in the very number the KPI exists to check — §3.9, `KPI-UNKNOWN-1` |
| Consignment **sealing** (`OPEN→SEALED→REOPENED`, seal signals) | **Dropped, not deferred** 2026-08-30 | replaced by the completeness KPI; K5's archive banner enumerates the superseded sites |
| Record-level lineage & replay (OPS-6) | **Won't (now)** | per-batch ancestry is the grain |
| Postgres multi-user (`OPS-03`) | **Parked** 2026-09-06 | until a multi-operator install exists |
| Wholesale AG-UI adoption; wholesale A2UI Assist-panel replacement | **Refused / superseded** | thin adapter; lossy kind allowlist — `AGT`'s |

## 7. As-built pointers

| Concern | Code | Docs |
|---|---|---|
| Bus, store, types, scrubbing | `platform/inspecto-event/src/main/java/com/gamma/event/EventLog.java`, `InMemoryEventStore.java`, `SecretScrubber.java`, `CurrentEventSink.java`; contract `spi/inspecto-audit-spi/src/main/java/com/gamma/audit/EventStore.java`, `EventSink.java`, `EventType.java`; `platform/inspecto-event/src/main/java/com/gamma/event/ParquetEventStore.java`, `SavedViewStore.java` | [`events-metrics.md`](../event/spec-events-metrics.md) |
| Signal envelope & read side | `platform/inspecto-engine/src/main/java/com/gamma/signal/Signal.java`, `Signals.java`, `PipelineConsignmentSignal.java` | [`signal-backbone.md`](../event/spec-signal-backbone.md) §S0–S2 |
| Feed routes (optional) · fallbacks · audit read | `features/inspecto-observability/src/main/java/com/gamma/eventsapi/EventRoutes.java`; `inspecto/src/main/java/com/gamma/control/AbsentModuleRoutes.java`, `AbsentModuleRoutes.java`, `AuditLogRoutes.java`, `SignalRoutes.java` | `EDITIONS.md` CP-13, §Audit; [`events.md`](../event/user-guide-events.md) |
| Metrics | `platform/inspecto-event/src/main/java/com/gamma/metrics/MetricRegistry.java`; `features/inspecto-observability/src/main/java/com/gamma/metricsapi/MetricsRoutes.java`; `inspecto/src/main/java/com/gamma/control/AcquisitionRoutes.java`; `platform/inspecto-engine/src/main/java/com/gamma/inspector/AcquisitionTelemetry.java` | `ADVANCED_GUIDE.md` §7 (`/metrics` catalog) |
| Audit trail | `platform/inspecto-access/src/main/java/com/gamma/access/AuditTrail.java` (called from `ControlApi.dispatch`) | `compliance/controls-matrix.md` AU-9, ISO 8.15–8.17 |
| Provenance & conservation | `platform/inspecto-engine/src/main/java/com/gamma/pipeline/exec/DbProvenanceStore.java`, `ProvenanceStores.java`, `ConservationCheck.java`; `platform/inspecto-engine/src/main/java/com/gamma/job/PipelineJobRunner.java` (`reportConservation`); `inspecto/src/main/java/com/gamma/control/LineageRoutes.java`, `JobRoutes.java` (`/provenance*`); `platform/inspecto-engine/src/main/java/com/gamma/notify/NotificationRules.java` | `docs/okf/product/layer/platform/tests-provenance-conservation.md` |
| Reporting, status, health | `inspecto/src/main/java/com/gamma/report/ReportService.java`; `inspecto/src/main/java/com/gamma/control/RunRoutes.java`, `HealthDetails.java`, `SystemRoutes.java`; `inspecto/src/main/java/com/gamma/service/InboxStatus.java`, `ServiceStores.java`, `OperationalDb.java`; `platform/inspecto-engine/src/main/java/com/gamma/job/DbJobRunStore.java` | [`operations-reference.md`](../../layer/platform/admin-guide-operations-reference.md) §Reports, §Status backend |
| Maintenance & retention | `platform/inspecto-engine/src/main/java/com/gamma/job/MaintenanceJob.java`, `MaintenanceTaskProvider.java`, `EventPruneTask.java`; `features/inspecto-backup/src/main/java/com/gamma/backup/BackupTask.java`, `BackupTaskProvider.java`; `features/inspecto-ops/src/main/java/com/gamma/opsjob/OpsMaintenanceTasks.java`; `spaces/demo/config/jobs/*.toon` | [`jobs.md`](../processor/spec-jobs.md) §Maintenance; `operations-reference.md` §Retention & purging; `docs/okf/product/module/backup/admin-guide-backup-restore.md` |
| Caps & scheduler | `platform/inspecto-engine/src/main/java/com/gamma/job/JobService.java`; `inspecto/src/main/java/com/gamma/control/SchedulerRoutes.java`; `platform/inspecto-engine/src/main/java/com/gamma/inspector/ConcurrencyBroker.java`; `platform/inspecto-util/src/main/java/com/gamma/util/OperationsZone.java` | `jobs.md` §Total-concurrency; [`duckdb.md`](../engine/spec-duckdb.md) (memory half) |
| Baseline Expectations | `inspecto/src/main/java/com/gamma/expectation/BaselineEvaluator.java`, `BaselineProfileStore.java`, `Expectation.java` (`Baseline`); `inspecto/src/main/java/com/gamma/control/ExpectationRoutes.java`; tests `BaselineEvaluatorTest`, `BaselineProfileStoreTest`, `ControlApiExpectationBaselineTest` | §3.10; [`step-catalog.md`](../elt/spec-step-catalog.md) § Expectations |
| Launch flags | `inspecto/src/main/java/com/gamma/control/ControlApi.java` (`PUBLIC_PATHS`, `isInfraRoute`, `-Dcontrol.bind`) | [`operations.md`](../../layer/platform/admin-guide-launch-flags.md) |
| UI consumers | `inspecto-ui/src/app/modules/admin/{events,audit-logs,dashboard,runs,run-detail,jobs,processing-status,settings}/`, `inspecto-ui/src/app/app.routes.ts` | [`events.md`](../event/user-guide-events.md), [`runs.md`](../elt/user-guide-elt.md), [`run-detail.md`](../elt/user-guide-elt.md), [`dashboard.md`](../../layer/product/user-guide-dashboard.md), [`jobs.md`](../processor/user-guide-jobs.md) |
| North star | — | [`living-operational-system.md`](../../layer/platform/architecture-platform.md) (shape only, no state column) |

**Gap rows** (a pointer that *should* exist and does not): a Feature page for Audit log / Processing
status / settings panes (§5.2 #5); a `PipelineScheduler` test class (none exists — `jobs.md` says so);
an as-built page for the seven-networks thesis (deliberately none — §4 2026-09-07).

**Archive cited as authority** (history tier, never maintained): `plans-archive/event-signal-backbone-plan.md`
(S0–S7 shipped record; the unbuilt S2 templating slice), `signal-network-plan.md` (R4 plan of record),
`living-operational-system.md` (R1–R6 roadmap), `d11-resource-caps-plan.md` (provenance only — durable
facts live in `duckdb.md`), `system-maintenance-plan.md` (MNT plan of record), `notification-system-and-audit-trail-plan.md`,
`consignment-chain-plan.md` (the per-step-Signal refusal). Cite them for *why*; this file and the concept
pages for *what is*.

## 8. Verification

* **Pointer check** — a capability-pointer check over this file (`tools/check-doc-citations.mjs` — **committed 2026-09-09**, wired into `ci.yml` and `.githooks/pre-push`) — (tree index of
  every backticked repo path, Java class and test name; control probe must pass first). Deliberate
  historical names it reports as MISSING: `ingestLock`, `FLOW_CONSERVATION_IMBALANCE` is a live alias,
  the `docs/archived-documents/plans-archive/system-maintenance-plan.md` path is quoted only as the dead pointer it was.
* **Tests that pin this capability** (≈41 classes across seven modules, 2026-09-08 count by name
  pattern): core `inspecto` — `AuditLogRoutesTest`, `AuditTrailTest`, `ControlApiAuditTest`,
  `ControlApiHealthDetailsTest`, `ControlApiProvenanceTest`, `ControlApiSchedulerSettingsTest`,
  `ControlApiJobRunsPageTest`, `ControlApiJobRunReplayTest`, `ReportServiceTest` …; `inspecto-event` —
  `EventCoreTest`, `EventLogAndAppenderTest`, `ParquetEventStoreTest`, `MetricRegistryTest`, `AuditAttrsTest`;
  `inspecto-observability` — `ControlApiEventsTest`, `ControlApiEventsPageTest`; `inspecto-observability` —
  `MetricsExpositionTest`; `inspecto-backup` — `BackupTaskTest`, `BackupPathContainmentTest`;
  `inspecto-ops` — `IncidentPurgeTaskTest`; `inspecto-engine` — `EventAlertBridgeTest`,
  `MaintenanceLibraryTest`, `NoBackupTaskShipsInThePersonalBuildTest`, `JobServiceTest`,
  `JobServiceOrphanAuditTest`, `PipelineJobRunnerTest`, `ConservationCheckTest`, `ConsignmentProvenanceTest`,
  `DbJobRunStoreTest`, `ConcurrencyBrokerTest`, `EnrichmentAuditReaderTest`. UI: 21 vitest specs under
  the eight `modules/admin` directories in §7 (Processing status and `rejected-rows.dialog` have none).
* **Contract** — `ApiContractTest` holds `openapi-v1.json` to the served routes; the four infra routes are
  the documented exemptions from `/api/v1`.
* **Guards** — `node tools/check-vocabulary.mjs`, `node tools/check-doc-links.mjs`,
  `node tools/check-gate-tally.mjs` from the repo root; the pre-push hook re-runs the vocabulary guard.
* **Falsify, don't read** — the two claims most worth re-probing on a running server: `curl -i
  localhost:8080/metrics` on a Basic bundle must be `503`, never `# HELP`; `curl -i
  localhost:8080/api/v1/health/details` without a token on Standard must be `401`, while `/health` is `200`.
