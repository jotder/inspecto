---
type: Capability
title: Observability & maintenance (OPS) — capability spec — Specification
description: Part 2 of 4 of Observability & maintenance (OPS) — capability spec: Specification
status: current
---

# Observability & maintenance (OPS) — capability spec — Specification

Part 2 of 4 of [Observability & maintenance (OPS) — capability spec](plan-observability.md) · previous: [Purpose, scope and requirements of record](plan-observability-scope.md) · next: [Specification — Ops Lens screens; decisions and not built](plan-observability-ops-lens-decisions.md).

## 3. Specification

### 3.1 The signal ledger

**Bus and store.** `EventLog` (`platform/inspecto-event/src/main/java/com/gamma/event/EventLog.java`) is the
event bus: `global()` plus one instance per space, `current()` routing by the calling thread's `space` MDC
and falling back to global. **Emission is synchronous on the publishing thread**; `emit()` runs every
subscriber inline, uses no SLF4J (re-entrant capture), and swallows subscriber errors. **This is the
hand-off seam**: the publisher holds that Pipeline's `PipelineRunGuard` claim (before 2026-08-01 the
global `ingestLock`), so any subscriber that starts synchronous work must hand off to a bounded queue on
its own virtual-thread executor — `FailureReactor`'s and `SignalIngress`'s pattern. Never "simplify" a
subscriber to run inline. `emit()` is also the **single secret-scrub seam**: `SecretScrubber.scrub(event)`
runs before anything is persisted.

`EventStore` (`spi/inspecto-audit-spi/src/main/java/com/gamma/audit/EventStore.java`) is the append-only store
contract — `append`, `query`, `recent`, keyset `page(limit, afterTs, afterId)` ordered `ts DESC, eventId
DESC`, `count()`, and `prune(LocalDate before, dryRun)` which deletes **whole UTC day-partitions** and
returns `-1` when the backend keeps nothing durable. **Three** backends, chosen by `-Devents.backend`
(`ServiceStores.java`): **`memory`** (default) — `InMemoryEventStore`, a bounded ring of 8,192 that drops
oldest and forgets on restart; **`parquet`** — `ParquetEventStore`, rolling Hive-partitioned Parquet under
`-Devents.dir` (`level/year/month/day`) read through DuckDB `read_parquet`. A startup store-swap drains the
in-memory store oldest-first so nothing emitted before the configured backend attached is lost.

🔴 **The default is a per-EDITION choice made in the launcher, not in the engine** (`EVENTS-DURABLE-1`,
2026-09-11). The engine default stays `memory`, because Basic's promise is zero files and zero
configuration — but a ring that empties on restart cannot carry the **tamper-evident, append-only** audit
trail Standard+ sells, and `AuditTrail` emits `EventType.AUDIT` straight into `EventLog`. So the
Standard/Advanced launchers emitted by `inspecto/package.ps1` now pass **`-Devents.backend=parquet`**,
selected off the same `inspecto-oidc.jar` presence check that turns on OIDC. `-Devents.dir` is
deliberately left unset: it falls back to `SpaceRoot.eventsDir()`, so discover mode keeps **one trail per
space** rather than pooling every space's audit into one directory. Both halves are pinned by
`EventStoreDurabilityTest` — including the memory drop, so the default is a choice and not an accident.
🔴 **Basic too, since 2026-11-06 (`BASIC-EVENTS-DURABLE-1`, operator decision).** Basic's zero-file promise
cost it the audit anchors: the memory chain restarts at seq 1 on every boot, so the anchor written before a
restart names a record the next boot no longer holds and the daily roll answered 409 in every Space. Every
edition's `serve.*` now passes `-Devents.backend=parquet`; the engine default stays `memory`.
✅ **CLOSED 2026-09-11 by scale-out phase A.** This row used to read *"still open, and deliberately not
built here… hangs on `-Dinspecto.topology=partitioned`, a switch that does not exist in the tree."* The
switch now exists (`com.gamma.util.Topology`, D12), so the residual is discharged: a `parquet` backend that
**cannot open** still degrades to memory with a WARN under `single` — unchanged, and deliberate, because
observability must never block a single node's service — but under `partitioned` it **fails the boot**.
⚠ The enforcement is not in this opener. Every store opener records its outcome in
`com.gamma.util.StoreHealth`, and the topology check lives in that one recording seam rather than in
thirteen catch blocks, so a future store cannot forget it. → `okf/product/module/engine/spec-db-layer.md` §5.2

### Stale dashboard tiles (`SIGNAL-STALE-TILES-1`, 2026-09-11)

The positioning's central seam — *a gap at acquisition reaches the dashboard that went stale* — is now
true end to end. A dashboard tile carries a **Stale** badge while its data source has an unresolved
disruption, with the reason as its tooltip.

🔴 **Four things the backlog row assumed that are not true**, all grounded before building:
* **`SEQUENCE_GAP` is a raw `Event`, not a `Signal`** (`AcquisitionTelemetry`), and it carries the
  **pipeline** plus the missing sequence key — no store, table or Dataset id.
* **Quarantine emits no signal at all** — a quarantined file is a `FILE_QUARANTINED` event; rejected rows
  are batch metrics.
* **Widgets and Dashboards are not Catalog nodes.** A control grep over the `catalog` package returns zero
  hits for either while the same grep returns dozens elsewhere ⇒ `/catalog/graph` **cannot** answer "which
  widgets depend on Dataset X", so the traversal the row named does not exist.
* Consequently the row's own test — *"a seeded gap Signal **on a Dataset**"* — describes something nothing
  emits.

**What is built instead** anchors on the identity that IS carried, the **pipeline**, chosen by the operator
on 2026-09-11 over two larger alternatives (give the emitters a Dataset identity; or add Widget/Dashboard
nodes to the Catalog graph — both still open, see `BACKLOG.md`). The chain is
`pipeline → the store it produces (GET /pipelines `produces`) → Datasets on that store (`sourceName`) →
widgets bound to them (`datasetId`) → tiles (`dashboard.tiles[].widgetId`)`, every hop over a field that
already exists and that `ComponentIntegrity` already validates. It lives in
`inspecto-ui/src/app/inspecto/signal/stale-tiles.ts`, framework-free and structurally typed.

✅ **Store granularity where the inputs allow it** (`STALE-TILES-PRECISION-1`, 2026-09-15). The resolver
(`staleStoresOf`) now projects every disruption and every commit onto STORES first and compares per store: a
`SEQUENCE_GAP` carries `attributes.stores` (the sinks its pipeline produces, comma-joined — `AcquisitionTelemetry`),
and a `dataset.write` Signal is read as a STORE-level commit through `GET /signals?type=dataset.write`
(`EventsService.signals`, projecting the Signal's `subject` — which `signalToEvent` used to drop). So a pipeline
that produces two stores and refreshed one no longer clears both. An input with no store falls back to
`pipeline → produces[]`, the original over-approximation — a tile wrongly marked stale costs a second look, a
tile wrongly left clean is a number someone acts on. 🔴 Two claims corrected: the imprecision lived in this
resolver's inputs (raw `BATCH_COMMITTED` Events, not Signals) as much as in the emitters; and `FILE_QUARANTINED`
has **no emitter anywhere** — it stays a declared trigger nothing produces.

**Clearing is derived, not stored.** A pipeline is stale while its most recent disruption is *newer* than
its most recent `BATCH_COMMITTED`. So "clear on the next successful run" is not a second mechanism that
could be forgotten or leak — it falls out of the comparison, and there is **no stale flag anywhere**.
⛔ Do not "improve" this by persisting one. ⚠ A tie counts as cleared, or a badge could survive every
later run. ⚠ The badge is **advisory**: when any of its three feeds fails the dashboard renders nothing
stale rather than painting every tile suspect.

**The type catalog is the `EventType` enum** (`spi/inspecto-audit-spi/src/main/java/com/gamma/audit/EventType.java`)
— quote the enum, never a page. Families: `LOG`, `AUDIT`, `ACCESS_DENIED`, service/pipeline lifecycle,
`BATCH_*`, `FILE_*` (acquisition), `JOB_*`, `SIGNAL`, `PIPELINE_CONSERVATION_IMBALANCE` (legacy alias
`FLOW_CONSERVATION_IMBALANCE`), `EXCHANGE_*`, `ALERT_FIRED`, `REPORT_READY`, `EXPECTATION_FAILED`, `OBJECT_*`.
⛔ **Do not grow the enum for new business signals** — they ride `Signal` on one `SIGNAL` Event
(completeness-KPI plan §5, standing).

**The Signal envelope.** `Signal` (`platform/inspecto-engine/src/main/java/com/gamma/signal/Signal.java`) is a
**13-field record**: `signalId, type (dotted), at, severity, source:Ref, subject:Ref, correlationId,
causationId, space, actor:Ref, message, payload:Map, schemaVersion`. `toEvent()`/`fromEvent()` are a
lossless round-trip onto `EventType.SIGNAL` — the payload rides as a structured map, never JSON in a string.
`Ref` is `{kind, id, rel, via}`; `Severity` has six levels (`TRACE…CRITICAL`, wire-serialised lowercase),
mapping onto the five-level `EventLevel` with `CRITICAL→ERROR` the one lossy direction, by design.
`Signals` (`platform/inspecto-engine/src/main/java/com/gamma/signal/Signals.java`) is the stateless read side:
`query(store, type|prefix.*, since, until, minSeverity, correlationId, limit)`, the shared `matches()`
predicate, and `assembleTree()`. **Producers do thread `causationId`**: `JobService.emitSignal` carries the
id of the Signal that triggered a Run (a mirror's root is deliberately `null`) — `signal-backbone.md`'s
"no producer threads `causationId` yet" was stale and is corrected in this commit.

**HTTP surfaces.**

| Route | Home | Edition | Notes |
|---|---|---|---|
| `GET /events[?limit]`, `/events/search[?level&type&pipeline&correlationId&q&from&to&limit&offset]`, `/events/{id}`, `/events/export[?format=csv\|json&…]`, `GET\|POST /events/views`, `POST /events/views/{id}/delete` | `features/inspecto-observability/src/main/java/com/gamma/eventsapi/EventRoutes.java` | S/E | Absent the module, `AbsentModuleRoutes` (core) answers `503` naming it on every path. **Recording is not gated.** `/bootstrap.features.events` tells the SPA |
| `GET /signals`, `/signals/tree?correlationId=&limit=`, `/signals/stream` (SSE with heartbeat comments) | `inspecto/src/main/java/com/gamma/control/SignalRoutes.java` | All | `correlationId` is **required** on the tree (400 otherwise — "a tree without an anchor is an unbounded forest"); `/signals/stream` is consumed by the Events pane (`EventsService.stream()`) |
| `GET /audit/search`, `/audit/export?format=csv` | `inspecto/src/main/java/com/gamma/control/AuditLogRoutes.java` | All | Fail-closed to `type=AUDIT\|ACCESS_DENIED`; the auditor's evidence CSV (§3.3) |

Saved views persist through `SavedViewStore` (`inspecto-event`). The SPA's `EventsService` and
`AuditService` are the two clients — deliberately separate, because they gate differently (§3.8).

### 3.2 Metrics

`MetricRegistry` (`platform/inspecto-event/src/main/java/com/gamma/metrics/MetricRegistry.java`) holds counters,
gauges and histograms keyed by name + sorted labels; `scrape()` runs registered collectors and renders
Prometheus text. It is **core and stays core** — it has dependants across the engine; what EDG-01 gated is
the HTTP exposition only. The `space` label is supplied by callers; the registry has no space awareness.

Two exposure surfaces:

* **`GET /metrics`** — `features/inspecto-observability/src/main/java/com/gamma/metricsapi/MetricsRoutes.java`, S/E.
  Unversioned and in `PUBLIC_PATHS` (**unauthenticated by design**: a scraper carries no token). Absent the
  module, core `AbsentModuleRoutes` answers `503`. Pinned by `MetricsExpositionTest`
  (`features/inspecto-observability/src/test/java/com/gamma/control/MetricsExpositionTest.java`).
* **`GET /metrics/acquisition`** — `inspecto/src/main/java/com/gamma/control/AcquisitionRoutes.java`,
  All. The acquisition family as JSON for the Overview tiles (`ACQ_METRICS`: files discovered / downloaded
  / failed, post-action failures, watermark skips, bytes, fetch seconds, active connections, files
  waiting stability). Unversioned (`isInfraRoute`) but **not** public — the bearer/OIDC rule applies.

**What is measured.** 47 distinct `inspecto_*` series names are registered across `inspecto`,
`inspecto-engine` and `inspecto-acquire` (grep the string literals; the count is a 2026-09-08 measurement).
OPS-2's four quantities map to: throughput — `inspecto_batches_total`, `inspecto_output_rows_total`,
`inspecto_partitions_written_total`; error rate — the status-labelled batch/job counters and
`inspecto_source_run_failures_total`; lag — `inspecto_inbox_oldest_seconds`, `inspecto_files_waiting_stability`;
run durations — `inspecto_batch_duration_seconds`, `inspecto_job_duration_seconds`,
`inspecto_enrichment_duration_seconds` (histograms). Duration **percentiles** are served by the report
endpoints (§3.5), not by `/metrics`. The sunset counter `inspecto_legacy_api_requests_total` was removed
2026-07-25 with the unversioned business surface.

### 3.3 The three-layer audit

**Layer 1 — run and batch ledgers.** Every ETL run writes a timestamped status CSV
(`<pipeline>_status_<yyyyMMdd_HHmmss>.csv`, one row per file: status, parsed/error rows, outputs, sizes,
duration, error) plus the error CSV and the quarantine layout; the Consignment-level batch audit and the
commit ledger are projected by the `StatusStore` — **`-Dstatus.backend` default `db`** (flipped from
`file` 2026-08-31, `OperationalDb.java`), `file` reads the on-disk CSVs directly. The column contract and
the quarantine reasons are in
[`operations-reference.md`](../../layer/platform/admin-guide-operations-reference.md) §*Status Log & Auditing*.

**Layer 2 — provenance rows** (§3.4).

**Layer 3 — the who-did-what trail.** `AuditTrail` (`platform/inspecto-access/src/main/java/com/gamma/access/AuditTrail.java`)
is **one central interceptor called from `ControlApi.dispatch`** — not per handler. It records every
successful state-changing request (`POST`/`PUT`/`PATCH`/`DELETE` — `PATCH` only since 2026-09-26: before, the
classifier admitted three methods, so the admin-only `PATCH /objects/{id}` priority / severity / assignee edit
left no row), export `GET`s, and non-GET attempts on
forbidden routes, classified fail-closed to `AUDIT` / `ACCESS_DENIED`, with actor, action, target, IP and
user agent as attributes. Actor comes from `ApiContext.actor`; on Basic (no authenticator) it is the
default `appUser`. **Scope boundary, stated, do not overclaim:** the credential check itself (MFA,
password, lockout) belongs to the IdP and is **not** in it. ✅ Since 2026-09-17 the *session lifecycle* IS:
`auth.exchange` / `auth.refresh` / `auth.logout` (`AuditTrail.authentication`, category `authentication`),
granted as `AUDIT` and refused as `ACCESS_DENIED`, with no code or token on the row. The generic
interceptor **skips `/auth/*`** so these rows are not doubled — until 2026-09-17 it also classified the
same requests as `auth.created` (`data_mutation`), so every sign-in wrote two rows
(`AUDIT-AUTH-DUPLICATE-ROW-1`; an over-count in auditor-facing data). *Authorization*
decisions are in it too (`access.denied`/`access.granted`, ABAC A5). Secrets are scrubbed at `EventLog.emit`, not here.

**Read-shaped POSTs are not audited (operator, 2026-09-26, demo finding R2-12).** Read-shaped POSTs, as
declared in `CapabilityManifest.EXEMPTIONS`, are not audited — **the manifest is the single list.**
`AuditTrail.classify` treats a (method, path) that fully matches a `read-shaped` entry's route pattern
exactly like a GET. The pattern is compiled once and matched against the prefix-stripped path the route
table uses, so `/spaces/{id}/bi/query` counts too. It must be a FULL match, so a persisting sibling
(`/recon/promote` beside `/recon/breaks`) is still audited. Before this, every dashboard tile's
`POST /bi/query` was written as `bi.created · data_mutation`, ~120 rows in a short demo session, and
the real acts were lost among them. The trail's own skip list had also drifted from the manifest
(`/bi/query`, `/db/query`, `/recon/*`, `/geo/*`, `/inv/*` and `/config/preview/*` were all missing
from it; the last was even filed under `configuration`). ⚠ Being like a GET cuts both ways:
`POST /bundle/export` (read-shaped) is still audited, now as `bundle.exported · export`, the way a
GET export is, rather than `bundle.created · data_mutation`. The literal skips (`…/test`,
`…/preview`, `…/dry-run`, `/assist*`, `/auth/*`, `/notifications*`) stay, because each of them covers
routes the manifest does not list as read-shaped. Only `/validate` was fully covered, so its literal
went. The Link Analysis and Geo reads (`/inv/*`, `/geo/*`) still say who looked at what, through their
own typed events (`LINK_PROJECTED`, `GEO_PROJECTED`, …). What went away is the generic duplicate
`inv.created` row. Pinned by `AuditTrailTest` and
`ControlApiAuditTest.readShapedPostLeavesNoAuditRowButAMutationDoes`, and both were proven red by
deleting the clause.

**An idempotency replay is audited like the original (2026-09-26).** A request carrying an
`Idempotency-Key` whose response is cached is answered by the `idempotency` stage, which runs BEFORE
`bindSpace` strips `/spaces/{id}`. The replay used to classify that raw path, so a replayed mutation
filed as `space.*` under the prefixed path (and outside the Space's own event store, because the MDC was
not bound yet), and a replayed read-shaped POST was audited as a mutation. `ControlApi.auditReplay` now
strips the prefix and binds the Space for the one record call, exactly as `bindSpace` does for a live
request. The SPA never sends the header, so no demo showed it. ⚠ **Superseded 2026-09-29
(`SEC-IDEMPOTENCY-REPLAY-1`):** the replay now answers inside `routeDispatch`, after `bindSpace` and every
gate, so it records through the ordinary `AuditTrail.record` on the already-stripped path and
`auditReplay` is gone; the test below still pins the outcome. Pinned by
`ControlApiAuditTest.idempotencyReplayOfASpacePrefixedRequestIsAuditedLikeTheOriginal`, red before the fix
(`space.paused /spaces/default/runs/…`, `space.created /spaces/default/bi/query`).

**A request that fails is recorded as an attempted action (session decision, 2026-09-28,
`AUDIT-ERRORED-REQUEST-UNRECORDED-1`).** A mutating request whose handler throws (an `Exception` or an
`Error`, `OutOfMemoryError` included) and is answered with a 5xx writes **one** `AUDIT` row with that status.
Its message ends "(failed, HTTP 500)", never "(refused, …)": a 5xx is a server fault, and the write may have
got part of the way. Before this, `ControlApi.routeDispatch` recorded only after the handler returned, so an
errored mutation left no trace at all. The rules:
- **Same classification as a success.** A read-shaped POST that fails writes nothing, like one that succeeds.
- **Exactly one row.** The failure is recorded in `routeDispatch`'s catch around the handler and then rethrown to
  `errorBoundary`, which shapes the answer. The throw skips the success record, so there is never a second
  row. The catch sits there, not in `errorBoundary`, because only there is the route known to have matched
  and the Space MDC still bound. The status mirrors the boundary's answer (`failureStatus`): the
  `ApiException`'s own 5xx, 503 for no Space hosted, 500 otherwise. A client disconnect writes no row,
  because the boundary answers none.
- **An `OutOfMemoryError` is recorded before `errorBoundary` rethrows it.**
- **Every `AUDIT` row now carries the request's Correlation-ID** (`correlationId`), so a failed row joins its
  ERROR log line and `/audit/search?correlationId=` finds it.
⚠ An `ApiException` with a 4xx other than 401/403 (409, 422, …) is still not recorded; that was never in
scope. A handler that answers a 5xx itself and returns is recorded by the success path, now worded "failed".
Pinned by `ControlApiErrorBoundaryTest` (a throwing mutation → one `AUDIT:500` row with the cid; a failing
read-shaped `/inv/projection` → none, against its recorded mutating sibling `/inv/investigations`; a
success → one row; the OOM rethrow → one row). The suite goes red with the record removed and again with it
doubled.

**Durability claims, exactly.** The trail is **append-only by construction** — one write seam, no update
or delete route, 405 inherent to dispatch — plus, since 2026-09-27 (`ASSURE-AUDIT-CHAIN-1`), **tamper-evident**:
a per-Space hash chain with MAC-signed daily anchors that `GET /audit/verify` recomputes, naming the first bad
record. It is still not tamper-proof (a chain rewritten end to end after the last anchor needs an anchor kept
off the box), not permission-hardened (no `PosixFilePermission` call exists repo-wide), and
the in-memory backend **drops** on restart; the Parquet backend's buffer drops after 50k events on sustained
flush failure (`controls-matrix.md` AU-9).

**Crash durability (operator, 2026-09-26).** On the Parquet backend **an audit row is on disk once its request
is answered**: every buffered event is also written to a write-ahead journal (`<eventsDir>/pending.jsonl`, one
JSON line each, forced to disk for `AUDIT` and `ACCESS_DENIED`), a successful flush truncates it, and opening the
store replays a leftover journal into Parquet. Before this, a hard kill (no clean close) silently lost the whole
unflushed buffer — up to 10 s or 1 000 events, audit rows included: a demo build stopped by kill came back with
none of its audit trail. The rule was chosen over a synchronous flush per audit event (one Parquet file per
request, with no compaction) and a timer flush (still loses a window). ⚠ Scope, exactly: a *process* kill loses
nothing; after an OS crash or power loss the non-audit lines may be lost (only audit lines are forced), and a
kill mid-write tears at most the last line, which replay skips. Pinned by
`ParquetEventStoreTest.bufferedEventsSurviveAHardKillThroughTheJournal` and `…aTornLastJournalLineIsSkipped`,
both red with the journal write removed.

**Reading and exporting.** Core `GET /audit/search` (offset-paged, `type=AUDIT|ACCESS_DENIED`) and
`GET /audit/export?format=csv` in every edition; the SPA's Audit log pane (`audit-logs.component.ts`)
reads them through `AuditService` and **does not** gate on `features.events` — by design (cell 6).

**Retention of the trail.** `event_prune` (COMPLY-3, 2026-09-02) deletes whole day-partitions older than
`retention_days` on the Parquet store (`-1` on memory). The operator-decided window is **one year**
(2026-08-30): schedule `retention_days: 365`. ⚠ **Nothing ships scheduled** — no `event_prune` Job
instance exists under `spaces/` (§5). MNT-14 **G3** stands alongside: a purged Incident's `OBJECT_ACTIVITY`
history, its own purge record included, is *not* removed by `incident_purge`; the one-year window is a
store-level upper bound, G3 a record-level exemption within it. Both are true at once.

### 3.4 Provenance & conservation

During an at-rest Pipeline run, `PipelineJobRunner` (`platform/inspecto-engine/src/main/java/com/gamma/job/PipelineJobRunner.java`)
collects one `ProvenanceRow(pipelineId, consignmentId, nodeId, rel, rowCount, runTs)` per (node, relationship)
through `PipelineExecutor.ProvenanceCollector` **while the scratch relations are live**, persists them via
`DbProvenanceStore` (`platform/inspecto-engine/src/main/java/com/gamma/pipeline/exec/DbProvenanceStore.java`, per
space through `ProvenanceStores`), then runs `ConservationCheck.imbalances(graph, counts)`
(`platform/inspecto-engine/src/main/java/com/gamma/pipeline/exec/ConservationCheck.java`) and emits one
`PIPELINE_CONSERVATION_IMBALANCE` Event per non-amplifying node whose records in ≠ records out
(`kind` = LOSS/ERROR or AMPLIFICATION/WARN). The built-in Notification Rule
`builtin-conservation-imbalance` (`NotificationRules.java`, `ops` category, `minLevel=WARN`) surfaces
both kinds; the Alert object itself is `INC`'s.

**Default off, and what that means concretely.** `-Dprovenance.backend` defaults to `none`
(`ServiceStores.java`): the collector is `NONE`, nothing is recorded, the conservation check never runs,
and `GET /provenance?batch=` / `GET /provenance/batches` (`JobRoutes.java`) answer **`404`** with the
message "provenance DB not enabled (set -Dprovenance.backend=duckdb)". The pipeline editor's **T17
last-run overlay** (`pipeline-editor.component.ts`, `pipeline-graph.ts`) fetches those routes and paints
each edge's record count as a weight label — this is the "Sankey overlay" of OPS-5; there is no Sankey
chart component. `GET /lineage?store=` (`LineageRoutes.java`) is **independent of the flag**: its upstream
half reads the ingest audit CSVs, its downstream half the Consignment outputs registry.

**Verification protocol** — `docs/okf/product/layer/platform/tests-provenance-conservation.md` (signed 2026-07-08):
enable on a real feed, soak through natural variation, cross-check `recordsIn`/`recordsOut` against
ground truth, log the outcome. Its outcome log is still `_(empty)_`; this cannot close offline.

### 3.5 Run reporting & health

`ReportService` (`inspecto/src/main/java/com/gamma/report/ReportService.java`) computes on demand over the
same `StatusStore` (file or db, identically):

| Route | Returns |
|---|---|
| `GET /status` | live snapshot per Pipeline — paused, committed-batch count, quarantined files, last batch id/status/time — plus a service rollup; `GET /status/problem-files` feeds the Processing-status pane |
| `GET /report`, `GET /runs/{name}/report` (`?from=&to=` inclusive, date or datetime) | batch-audit rollup: total/success/failed, **error rate**, rows in/out, rejected files, output files/bytes, avg/max duration, **`p50`/`p95`/`p99`**; the window echoes back as `windowFrom`/`windowTo` |
| `GET /runs/{name}/{batches\|files\|lineage\|quarantine\|commits\|outputs\|errors\|pending}` | the Run-detail tabs (`RunRoutes.java`); quarantine rows are **synthesised** from the `<reason>/<filename>` layout and carry no batch id or timestamp |
| `GET /health`, `GET /ready` | liveness / readiness, public, unversioned |
| `GET /health/details` | per-subsystem `UP`/`DOWN`/`NOT_CONFIGURED` (`HealthDetails.java`: configStore, dataStore, pipelines, scheduler, jobRunsProjection); DOWN iff any subsystem DOWN; **auth-gated, deliberately not public** (MNT-15) |
| `GET /jobs/metrics`, `/jobs/runs`, `/jobs/failures` | the T27 job-execution projection over `DbJobRunStore` (`platform/inspecto-engine/src/main/java/com/gamma/job/DbJobRunStore.java`); **`404` unless `-Djobs.backend=duckdb\|postgres`**; the Jobs pane's Reporting mode degrades to an explained "reporting disabled" |
| `POST /jobs/runs/{runId}/replay` | 🔴 **NOT gated by `-Djobs.backend` — this row grouped it with the projection routes until 2026-09-09 and that is the wrong cause.** Replay resolves the original run through `JobService.runById` → `liveRuns`, an in-memory map capped at `LIVE_RUN_CAP` and lost on restart. The two failure modes are **opposite**: replay WORKS with the projection off for a run still in memory, and `404`s with the projection ON for one that has been evicted. Owner: [`okf/product/module/elt/plan-elt.md`](../elt/plan-elt.md) §EXE.2.3 |
| `GET /system/operational-db`, `POST /system/operational-db/test` | what this deployment actually uses per store family, and a real `SELECT 1` against a proposed JDBC URL; `canConfigureAccess`; **no PUT exists** (the pane's spec asserts it) |

**Live gauges are in-memory and poll-read.** `InboxStatus` (`inspecto/src/main/java/com/gamma/service/InboxStatus.java`)
carries `pending`, `running`, `current` (file *i* of *n*, `IngestProgress.Snapshot`) and `step` (chain step
*i* of *n*, `StepProgress.Snapshot`). **Per-step Signals were refused** (2026-08-12, §6): a Signal is a
durable ledger write and would fire `on_signal` work while the batch claim is held.

**The file run log is always on**: every Job run appends to `jobs_audit/jobs_runs.csv` and its JSONL
artifacts (`-Djobs.runlog.maxEntries`, default 10,000); `runlog_prune` is its retention task.

### 3.6 Maintenance & retention

**System maintenance is tasks on the `maintenance` Job Type — never shell scripts or OS cron** (2026-07-12).
`MaintenanceJob` (`platform/inspecto-engine/src/main/java/com/gamma/job/MaintenanceJob.java`) dispatches on the
`task` parameter (default `cleanup`); each task is its own `*Task` class in `com.gamma.job`.

| Task | Deletes / does | Home | Notes |
|---|---|---|---|
| `cleanup` | files under `dir` breaching age/count/size (`max_count`, `max_size`, `archive_dir`, `min_keep`) | core | the **only** task with a `retention_days` default (7); the newest N are never retired |
| `ledger_prune` | acquisition-ledger fingerprints | core | ⚠ a pruned file still at the source **re-ingests as new** |
| `dedup_prune` | windowed `transform.dedup` keys | core | |
| `runlog_prune` | Run history JSONL + artifacts + the `inspecto_job_runs` projection | core | |
| `status_prune` | the run-timestamped `_status_`/`_batches_`/`_lineage_`/`_unpack_` CSVs under each Pipeline's `status/` | core | STATUS-CSV-RETENTION-1 — the commit log and `manifests/` are excluded by a positive filter |
| `notification_prune`, `receipt_prune` | in-app feed entries (any read state); delivery receipts | core | the receipt store's 5,000 cap is a **backstop, not retention** |
| `event_prune` | the Parquet event store's day-partitions older than the window | core | COMPLY-3; one-year window is policy; `-1` on the memory backend. Dataset freshness does not depend on the pruned rows — its last-publication floor lives in `<audit dir>/dataset-publications.tsv` (studio §3.4) |
| `partition_prune` | a sink store's `year=/month=/day=` directories older than the window (path-jailed) | core | 2026-09-06; whole days only, catalog rows stay |
| `retire_superseded` | the **bytes** of output revisions the outputs catalog marks `SUPERSEDED` | core | ⚠ **the one retention task a correctness fix depends on** — without it every full recompute leaves a complete extra copy forever; since 2026-08-29 the runner **WARNs** after a recompute when no enabled job exists |
| `storage_report`, `storage_trend` | per-axis usage (+ a queryable `maintenance_storage` sample on a real run); two-point growth slope, breach ETA, archive candidates | core | trend emits `maintenance.storage.trend` (WARN) inside `warn_days` |
| `db_maintenance`, `scheduler_audit`, `metadata_validate`, `file_repository_audit`, `materialize` | CHECKPOINT/VACUUM via host seams; cron sanity; broken refs/duplicates/missing data; repository consistency; view materialisation | core | |
| `backup`, `backup_verify`, `restore` | timestamped zip + SHA-256 sidecar manifest via `Checksums`; hash-first verify (fail-closed); manifest-validated restore with **zip-slip jail** and conflict preview — archive-based, the whole config tree, *not* bundle import | **`inspecto-backup`** (`BackupTaskProvider`) | Standard+/Advanced (`OPS-06`, cell 2, 2026-09-07). On Basic: *unknown maintenance task*, refused loudly. Pinned by `NoBackupTaskShipsInThePersonalBuildTest` |
| `incident_purge` | Archived Incidents with notes, links, tag edges — **the only task that deletes operator business records** | **`inspecto-ops`** (`OpsMaintenanceTasks`) | MNT-14; `retention_days` required, `max_count` default 1000; legal hold fail-safe and re-checked inside `purge()`; G3 — the audit history survives. **Nothing schedules it, and nothing should** |

The **`MaintenanceTaskProvider`** ServiceLoader seam (`platform/inspecto-engine/src/main/java/com/gamma/job/MaintenanceTaskProvider.java`):
the built-in `switch` always wins first; its `default` arm consults discovered providers before throwing
*unknown maintenance task*; **a task claimed by two providers is refused fail-closed**, never resolved by
classpath order. Findings emit `maintenance.*` Signals for Alert Rules.

**Retention policy, stated once.** *Retention is never on by default.* Every deleting task is inert until
an operator defines a Job for it, and each requires an explicit `retention_days` (except `cleanup`) —
forgetting is a policy decision and a wrong default is silent data loss. Consequences: the one-year audit
window (§3.3) and the recompute disk cost (`retire_superseded`) are both *stated policy* on a deployment
that has not authored the Jobs. **Dry run** (`POST /jobs/{name}/trigger?dryRun=true`) is the first step
for every write task; tasks with no preview do nothing on a dry run.

**Shipped instances.** `spaces/demo/config/jobs/` carries eight `type: maintenance` configs —
`backup_verify_job`, `chained_backup_job_template` (the MNT-13 nightly chain: each link
`on_signal: job.run.completed` + `when: "$signal.job == <prev> && $signal.outcome == SUCCESS"`),
`db_maintenance_job`, `orders_summary_followup_job`, `orders_weekly_compact_job`, `retention_job_template`,
`runlog_retention_job`, `scheduler_audit_job`. ⚠ On a **Basic** install the demo chain **stops at
`config_backup` with a FAILED run** — the edition boundary working, not a defect (`OPS-06`, runbook).
Runbook: `docs/okf/product/module/backup/admin-guide-backup-restore.md`.

### 3.7 Scheduler caps, health and the ops-plane knobs

**Concurrency (D11, 2026-08-26).** `JobService.DEFAULT_MAX_CONCURRENT_RUNS = 4` is **on by default**;
`0` = unbounded; hot-resizable, a shrink **drains**. It is owned by the settings tier — `scheduler.toon`
→ `GET|PUT /system/scheduler` (`SchedulerRoutes.java`, `canOperateRuns`) — and `-Djobs.maxConcurrentRuns`
is a *bootstrap default* consulted only when nothing is stored (⛔ a key served by the settings tier must
not also be read from `-D` at use time). The same document carries the Consignment broker's globals:
`ConcurrencyBroker` (`platform/inspecto-engine/src/main/java/com/gamma/inspector/ConcurrencyBroker.java`) grants
**weighted shares, never precedence** — FIFO per Pipeline, `stride = 6 / priority` (1–3), so a priority-1
Pipeline provably keeps a non-zero share; `IntakeGovernor` holds the intake caps (`intakeMaxFilesPerCycle`,
per-space `maxConcurrentConsignments`). The Scheduler-settings pane shows each value's provenance chip
(`file`/`property`/`default`). ⚠ **The DuckDB `memory_limit` half has NO default** (GAP-4, verified again
2026-09-08: `DuckDbUtil.memoryLimit(null)` is `null`, no `scheduler.toon` ships, the committed corpus sets
`memory_limit: ""`). `jobs.md` §Total-concurrency described the cap as "the other half of the
`memory_limit=2GB` default" — corrected in this commit; the DAT spec §2 carries the same finding.
✅ **GAP-4 closed 2026-09-26:** the resolver now ends at a code default (40 % RAM ÷ 4, ≥ 1 GiB), served
with provenance `default`.

**Run budget.** `-Dservice.max.runs=M` (`ControlApi.main`, `CollectorService`) bounds a service
process's ingest runs — a CLI/service knob distinct from job concurrency.

**Health.** `/health` and `/ready` are the connectivity probes (public, unversioned; `/ready` reports the
registered pipeline count); `/health/details` is the operator's per-subsystem view (§3.5).
`-Dcontrol.bind` unset = **every interface, in every edition** — a deliberate call, documented in
[`operations.md`](../../layer/platform/admin-guide-launch-flags.md), and the reason EDG-01 chose modules over `-D`
switches (§4).

**The operations zone.** `-Dops.timezone` (`OperationsZone`, `inspecto-util`) governs cron firing and
`$today`; it is *not* the data zone (`parsing.source_timezone`) and DuckDB's session `TimeZone` stays the
host's — the rules are `DAT`'s.

**Flags this layer reads** (readers verified 2026-09-08):

| Flag | Default | Reader |
|---|---|---|
| `-Devents.backend` / `-Devents.dir` | `memory` (⚠ every edition's launcher sets `parquet` — Basic since 2026-11-06; `db`/`postgres`/`jdbc:` since 2026-09-12) / `SpaceRoot.eventsDir()` — `<space>/data/events`, and only `inspecto-events` in the CWD under the legacy root | `ServiceStores.java`, `inspecto/package.ps1` |
| `-Devents.db.url` / `.user` / `.password` | `SpaceRoot.eventsDbUrl()` — `<space>/duckdb/inspecto-events.db` | `OperationalDb.Family.EVENTS`, `DbEventStore` |
| `-Dstatus.backend` | `db` | `OperationalDb.java`, `ServiceStores.java` |
| `-Djobs.backend` | `none` | `ServiceStores.java`, `DbJobRunStore.java`, `HealthDetails.java` |
| `-Dprovenance.backend` | `none` | `ServiceStores.java`, `DbProvenanceStore.java` |
| `-Djobs.maxConcurrentRuns` | 4 (bootstrap only) | `JobService.java` |
| `-Djobs.runlog.maxEntries` · `-Djobs.signal.maxChainDepth` · `-Djobs.orphan.audit` | 10,000 · 8 · on | `JobService.java` |
| `-Dservice.max.runs` | unbounded | `ControlApi.java`, `CollectorService.java` |
| `-Dobjects.sla.sweep.seconds` | 60 (≤0 disables) | `CollectorService.java` |
| `-Dingest.retry.max` | 5 | `CommitRetry.java` |
| `-Dops.timezone` | host | `OperationsZone.java`, `JobService.java` |
| `-Dcontrol.bind` / `-Dcontrol.port` | every interface / 8080 | `ControlApi.java` |

⛔ `-Dcontrol.token` is a **dead flag** — zero Java readers (`operations-reference.md` §authority note).

## Changes

- 2026-09-08: `GLOSSARY.md` §8 described a seven-field envelope as "binding"; it now points here.
- 2026-11-01: the audit durability note corrected — it said there was no hash chain.
