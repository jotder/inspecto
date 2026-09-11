---
type: Capability
area: OPS
title: Observability & maintenance (OPS) — capability spec
description: The requirement-of-record and as-built specification for the signal ledger, metrics, the three-layer audit, provenance & conservation, run reporting, and the maintenance task library with its retention policy — one file, eight sections, machine-verified pointers.
status: current
written: 2026-09-08
supersedes-rows: REQUIREMENTS §3.7 OPS-1..OPS-6 (this file corrects them, see §2)
---

# Observability & maintenance (`OPS`)

> **How to read this file.** §1–§2 are the *requirement of record*: where they disagree with
> `docs/REQUIREMENTS.md` §3.7 or `docs/EDITIONS.md`, **this file wins** and the disagreement is stated
> in place. §3 is the as-built specification, §4 the dated decisions, §5 what is not built (tracked and
> untracked), §6 what was refused or superseded, §7 the pointers into code and docs, §8 how the whole
> thing is verified. Everything named in backticks under §3 and §7 was checked against the tree on
> 2026-09-08 by a capability-pointer check (`tools/check-doc-citations.mjs`, committed 2026-09-09); the deliberate exceptions are called out where they occur.
>
> ⛔ **`OPS` is a capability; "Ops" is a Lens.** `docs/GLOSSARY.md` §14 fixes the name *Observability &
> maintenance* (it *was* "Observability & operations"). The Ops **Lens** is a UI surface owned by the
> `UI` area; its screens appear here only as *consumers* of this capability's contracts (§3.8).

## 1. Purpose & scope

`OPS` is the platform's ability to **see what it did, prove who did it, bound what it keeps, and keep
itself healthy** — without shell scripts, OS cron, or an external monitoring stack being mandatory.

**In scope**

* **The signal ledger** — one append-only `EventStore` fed by a synchronous `EventLog`; the canonical
  `Signal` envelope riding on it as `EventType.SIGNAL`; the `/events*` read surface (search, live tail,
  saved views, CSV export) and the `/signals*` surface (query, causation tree, SSE stream).
* **Metrics** — the core `MetricRegistry` and the two exposure surfaces: the Prometheus text scrape
  (`/metrics`, an optional module) and the JSON acquisition snapshot (`/metrics/acquisition`, core).
* **The three-layer audit** — (1) per-run status/batch-audit ledgers, (2) per-run data-plane provenance
  rows, (3) the who-did-what `AUDIT`/`ACCESS_DENIED` trail captured by one dispatch interceptor, read
  through core `/audit/*` in every edition.
* **Provenance & conservation** — per-(node, relationship) record counts per run, the conservation
  invariant over them, the editor's edge-weight overlay, and `GET /lineage`.
* **Run reporting** — `/status`, `/report`, `/runs/{name}/report` (p50/p95/p99 over a date window), the
  live inbox/step gauges, `/health/details`, and the optional job-run reporting projection.
* **Maintenance & retention** — the `maintenance` Job Type's task library (core + the `inspecto-backup`
  and `inspecto-ops` contributions through `MaintenanceTaskProvider`), backup/restore, and the retention
  policy "nothing forgets by default".
* **Scheduler caps, health and the ops-plane knobs** — the D11 concurrency cap, the Consignment broker's
  weighted shares, `GET|PUT /system/scheduler`, `/system/operational-db`, `-Dops.timezone`, and the
  launch flags this layer reads.

**Out of scope (owned elsewhere)**

* Alert Rules, Notifications (channels, digests, receipts, suppression) and Incidents → `INC`
  ([`incidents/incidents.md`](../incidents/incidents.md)). The ledger *carries* their events; the rules
  are theirs.
* The Job framework's authoring model — triggers, `when:` guards, the parameter contract, `$` tokens,
  Job Packs, `consignment.process` → `PIP` (execution) via
  [`okf/backend/control-plane/jobs.md`](../../backend/control-plane/jobs.md). `OPS` owns only the
  *maintenance* tasks and the scheduler's *caps*.
* The agent-facing projections of the Signal Backbone (AG-UI, A2UI, context fabric, gated agentic write,
  S3–S7) → `AGT`. §3.1 stops at the envelope and the HTTP surface.
* Edition packaging (jlink runtime, `package.ps1`, the ServiceLoader mechanism itself) → `PKG`; the
  timezone *rules* for data → `DAT`; the operational stores' engines and roster → `DAT`
  ([`data-plane/data-plane.md`](../data-plane/data-plane.md) §3).
* The Ops Lens as a surface (navigation, lens homes, read-only semantics) → `UI`.

## 2. Requirements of record

The board rows were `docs/REQUIREMENTS.md` §3.7 (that section was stripped to an index on 2026-09-09 — this file is their only home now) `OPS-1`…`OPS-6` plus the `EDITIONS.md` rows `OPS-01`…`OPS-08`,
`JOB-03`, `CP-13`, `SEC-09`. Below, each row is restated as the requirement actually holds, with the
correction the board needs. **Where a cell below says CORRECTION, the board was wrong on 2026-09-08 and
has been amended in the same commit as this file.**

| ID | Requirement (as it holds) | MoSCoW | Status of record | Edition of record |
|---|---|---|---|---|
| **OPS-1** | One **Signal** ledger; Events, Alerts and Notifications are *views* over it; live tail, saved views, CSV export | Must | ✅ SHIPPED (R4 unification 2026-07; Signal-primary flip 2026-07-18). **CORRECTION:** "live tail" is **client polling** (2–60 s, paused while the tab is hidden), not a push stream; the server's SSE `GET /signals/stream` exists and **has no client consumer**; there is no `/events/stream` | **Recording: All. Reading: S/E.** The feed routes (`/events*`) are the optional `inspecto-events` module (EDG-01 cell 6, 2026-09-08); Personal answers `503` naming the module and the SPA shows an explained state. The board's bare `All` is half true |
| **OPS-2** | **Metrics**, Prometheus-compatible — throughput, error rate, lag, run durations | Must | ✅ SHIPPED. **CORRECTION:** the cell said the exposition is "core and ungated in every bundle; the gating is EDG-01 debt". **False since 2026-09-07** — EDG-01 cell 5 moved `GET /metrics` into `inspecto-metrics`; on Personal the path answers `503` with no `# HELP` leak. `MetricRegistry` stays core. "Deliberately unauthenticated" is *true* (`ControlApi.PUBLIC_PATHS`) and is precisely **why** the ungated Personal exposure was the P1 that started EDG-01 | **S/E** for the scrape. `GET /metrics/acquisition` (JSON, core, **authenticated** — not in `PUBLIC_PATHS`) is **All**. ⚠ `BACKLOG.md` §5 said this row "says `All`" — it says `S/E`; struck 2026-09-08 |
| **OPS-3** | Three-layer audit: file/batch audit ledgers, provenance rows, an **append-only who-did-what Audit Log** | Must | ✅ SHIPPED. **CORRECTIONS (three):** (a) the log is **append-only by construction of the write path, NOT tamper-evident** — no hash, chain or signature exists (`compliance/controls-matrix.md` AU-9); `EDITIONS.md` SEC-09 said "tamper-evident" and `USER_GUIDE.md`/`GLOSSARY.md` said "immutable" — amended. (b) **Sign-ins are not audited**: `AuditTrail` records mutating `POST/PUT/DELETE`, export `GET`s and access-denied attempts; authentication events are out of scope (BACKLOG §6 standing entry). `USER_GUIDE.md` §3 and the GLOSSARY entry listed "sign-ins/logins" — amended. (c) The audit CSV export is **core** `GET /audit/export?format=csv` since 2026-09-08 (`AuditLogRoutes`), no longer a branch of `/events/export` | **All** (recording *and* reading — the one observability read that stays on Personal; EDITIONS §Audit) |
| **OPS-4** | Durable **Run reporting** — success rate, p50/p95 | Should | ✅ SHIPPED. **CORRECTION:** "off by default" is true of **one of three** ledgers only. The **batch-audit report** (`GET /status`, `/report`, `/runs/{name}/report`, p50/p95/**p99**, `?from&to`) reads the status store, whose backend default is **`db` since 2026-08-31** — always on. The **file run log** (`jobs_audit/jobs_runs.csv` + JSONL) is always on. Only the **job-run DB projection** (`/jobs/metrics|runs|failures`, T27) is off (`-Djobs.backend` default `none`, `404` until set) | All |
| **OPS-5** | Per-edge **Provenance** + conservation invariant → Alerts + an edge-weight overlay | Should | 🟡 PARTIAL — built, tested, **default off** (`-Dprovenance.backend` default `none`; `/provenance*` answers `404` until `duckdb`); verified against synthetic data only; the live soak is BACKLOG §2. **CORRECTION:** there is **no Sankey component** — the pipeline editor paints per-edge record counts as weight labels on the canvas edges (T17). The wording "Sankey overlay" is the design's name for that; the row keeps it, this file names what shipped | All |
| **OPS-6** | Record-level lineage & replay | **Won't (now)** | — per-batch ancestry is the accepted grain (`/runs/{name}/lineage`, `/lineage?store=`) | — |

**Edition-board corrections carried in this commit**

* `EDITIONS.md` **`JOB-03`** promised the whole task library on Personal (✅). Three tasks are *not* on
  Personal by design — `backup`/`backup_verify`/`restore` (`inspecto-backup`, cell 2) and `incident_purge`
  (`inspecto-ops`, cell 7) are **unknown tasks, refused loudly** there. The P cell is now 🟡.
* `EDITIONS.md` **`SEC-09`** "tamper-evident" → "append-only (not tamper-evident)". The `SEC` spec §5 had
  already recorded the mismatch and assigned it to `OPS`.
* `REQUIREMENTS.md` §3.7's heading "Observability & operations … UI Ops" carried the retired name; renamed
  to the §14 term.

**Non-functional requirements this capability carries** (from `REQUIREMENTS.md` §4, restated as they
hold): NFR-7's audit-retention window is **one year** (operator, 2026-08-30), applied by `event_prune`
**only when an operator schedules it** — "stated policy the code does not apply" until then (G5); a purged
Incident's history is exempt from that window by decision (MNT-14 G3, §4); no store is pruned by default.

## 3. Specification

### 3.1 The signal ledger

**Bus and store.** `EventLog` (`inspecto-event/src/main/java/com/gamma/event/EventLog.java`) is the
event bus: `global()` plus one instance per space, `current()` routing by the calling thread's `space` MDC
and falling back to global. **Emission is synchronous on the publishing thread**; `emit()` runs every
subscriber inline, uses no SLF4J (re-entrant capture), and swallows subscriber errors. **This is the
hand-off seam**: the publisher holds that Pipeline's `PipelineRunGuard` claim (before 2026-08-01 the
global `ingestLock`), so any subscriber that starts synchronous work must hand off to a bounded queue on
its own virtual-thread executor — `FailureReactor`'s and `SignalIngress`'s pattern. Never "simplify" a
subscriber to run inline. `emit()` is also the **single secret-scrub seam**: `SecretScrubber.scrub(event)`
runs before anything is persisted.

`EventStore` (`inspecto-event/src/main/java/com/gamma/event/EventStore.java`) is the append-only store
contract — `append`, `query`, `recent`, keyset `page(limit, afterTs, afterId)` ordered `ts DESC, eventId
DESC`, `count()`, and `prune(LocalDate before, dryRun)` which deletes **whole UTC day-partitions** and
returns `-1` when the backend keeps nothing durable. Two backends, chosen by `-Devents.backend`
(`ServiceStores.java`): **`memory`** (default) — `InMemoryEventStore`, a bounded ring of 8,192 that drops
oldest and forgets on restart; **`parquet`** — `ParquetEventStore`, rolling Hive-partitioned Parquet under
`-Devents.dir` (`level/year/month/day`) read through DuckDB `read_parquet`. A startup store-swap drains the
in-memory store oldest-first so nothing emitted before the configured backend attached is lost.

🔴 **The default is a per-EDITION choice made in the launcher, not in the engine** (`EVENTS-DURABLE-1`,
2026-09-11). The engine default stays `memory`, because Personal's promise is zero files and zero
configuration — but a ring that empties on restart cannot carry the **tamper-evident, append-only** audit
trail Standard+ sells, and `AuditTrail` emits `EventType.AUDIT` straight into `EventLog`. So the
Standard/Enterprise launchers emitted by `inspecto/package.ps1` now pass **`-Devents.backend=parquet`**,
selected off the same `inspecto-security.jar` presence check that turns on OIDC. `-Devents.dir` is
deliberately left unset: it falls back to `SpaceRoot.eventsDir()`, so discover mode keeps **one trail per
space** rather than pooling every space's audit into one directory. Both halves are pinned by
`EventStoreDurabilityTest` — including the memory drop, so the default is a choice and not an accident.
✅ **CLOSED 2026-09-11 by scale-out phase A.** This row used to read *"still open, and deliberately not
built here… hangs on `-Dinspecto.topology=partitioned`, a switch that does not exist in the tree."* The
switch now exists (`com.gamma.util.Topology`, D12), so the residual is discharged: a `parquet` backend that
**cannot open** still degrades to memory with a WARN under `single` — unchanged, and deliberate, because
observability must never block a single node's service — but under `partitioned` it **fails the boot**.
⚠ The enforcement is not in this opener. Every store opener records its outcome in
`com.gamma.util.StoreHealth`, and the topology check lives in that one recording seam rather than in
thirteen catch blocks, so a future store cannot forget it. → `okf/backend/engine/db-layer.md` §5.2

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

⚠ **The granularity that follows, stated rather than hidden:** a disruption marks **every** Dataset fed by
that pipeline, not only the rows or column that gapped. A deliberate over-approximation — a tile wrongly
marked stale costs a second look, a tile wrongly left clean is a number someone acts on.

**Clearing is derived, not stored.** A pipeline is stale while its most recent disruption is *newer* than
its most recent `BATCH_COMMITTED`. So "clear on the next successful run" is not a second mechanism that
could be forgotten or leak — it falls out of the comparison, and there is **no stale flag anywhere**.
⛔ Do not "improve" this by persisting one. ⚠ A tie counts as cleared, or a badge could survive every
later run. ⚠ The badge is **advisory**: when any of its three feeds fails the dashboard renders nothing
stale rather than painting every tile suspect.

**The type catalog is the `EventType` enum** (`inspecto-event/src/main/java/com/gamma/event/EventType.java`)
— quote the enum, never a page. Families: `LOG`, `AUDIT`, `ACCESS_DENIED`, service/pipeline lifecycle,
`BATCH_*`, `FILE_*` (acquisition), `JOB_*`, `SIGNAL`, `PIPELINE_CONSERVATION_IMBALANCE` (legacy alias
`FLOW_CONSERVATION_IMBALANCE`), `EXCHANGE_*`, `ALERT_FIRED`, `REPORT_READY`, `EXPECTATION_FAILED`, `OBJECT_*`.
⛔ **Do not grow the enum for new business signals** — they ride `Signal` on one `SIGNAL` Event
(completeness-KPI plan §5, standing).

**The Signal envelope.** `Signal` (`inspecto-engine/src/main/java/com/gamma/signal/Signal.java`) is a
**13-field record**: `signalId, type (dotted), at, severity, source:Ref, subject:Ref, correlationId,
causationId, space, actor:Ref, message, payload:Map, schemaVersion`. `toEvent()`/`fromEvent()` are a
lossless round-trip onto `EventType.SIGNAL` — the payload rides as a structured map, never JSON in a string.
`Ref` is `{kind, id, rel, via}`; `Severity` has six levels (`TRACE…CRITICAL`, wire-serialised lowercase),
mapping onto the five-level `EventLevel` with `CRITICAL→ERROR` the one lossy direction, by design.
⚠ `GLOSSARY.md` §8 described a seven-field envelope as "binding" — corrected 2026-09-08 to point here.
`Signals` (`inspecto-engine/src/main/java/com/gamma/signal/Signals.java`) is the stateless read side:
`query(store, type|prefix.*, since, until, minSeverity, correlationId, limit)`, the shared `matches()`
predicate, and `assembleTree()`. **Producers do thread `causationId`**: `JobService.emitSignal` carries the
id of the Signal that triggered a Run (a mirror's root is deliberately `null`) — `signal-backbone.md`'s
"no producer threads `causationId` yet" was stale and is corrected in this commit.

**HTTP surfaces.**

| Route | Home | Edition | Notes |
|---|---|---|---|
| `GET /events[?limit]`, `/events/search[?level&type&pipeline&correlationId&q&from&to&limit&offset]`, `/events/{id}`, `/events/export[?format=csv\|json&…]`, `GET\|POST /events/views`, `POST /events/views/{id}/delete` | `inspecto-events/src/main/java/com/gamma/eventsapi/EventRoutes.java` | S/E | Absent the module, `AbsentEventsRoutes` (core) answers `503` naming it on every path. **Recording is not gated.** `/bootstrap.features.events` tells the SPA |
| `GET /signals`, `/signals/tree?correlationId=&limit=`, `/signals/stream` (SSE with heartbeat comments) | `inspecto/src/main/java/com/gamma/control/SignalRoutes.java` | All | `correlationId` is **required** on the tree (400 otherwise — "a tree without an anchor is an unbounded forest"); `/signals/stream` has **no SPA consumer** (§5) |
| `GET /audit/search`, `/audit/export?format=csv` | `inspecto/src/main/java/com/gamma/control/AuditLogRoutes.java` | All | Fail-closed to `type=AUDIT\|ACCESS_DENIED`; the auditor's evidence CSV (§3.3) |

Saved views persist through `SavedViewStore` (`inspecto-event`). The SPA's `EventsService` and
`AuditService` are the two clients — deliberately separate, because they gate differently (§3.8).

### 3.2 Metrics

`MetricRegistry` (`inspecto-event/src/main/java/com/gamma/metrics/MetricRegistry.java`) holds counters,
gauges and histograms keyed by name + sorted labels; `scrape()` runs registered collectors and renders
Prometheus text. It is **core and stays core** — it has dependants across the engine; what EDG-01 gated is
the HTTP exposition only. The `space` label is supplied by callers; the registry has no space awareness.

Two exposure surfaces:

* **`GET /metrics`** — `inspecto-metrics/src/main/java/com/gamma/metricsapi/MetricsRoutes.java`, S/E.
  Unversioned and in `PUBLIC_PATHS` (**unauthenticated by design**: a scraper carries no token). Absent the
  module, core `AbsentMetricsRoutes` answers `503`. Pinned by `MetricsExpositionTest`
  (`inspecto-metrics/src/test/java/com/gamma/control/MetricsExpositionTest.java`).
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
[`operations-reference.md`](../../backend/build-run/operations-reference.md) §*Status Log & Auditing*.

**Layer 2 — provenance rows** (§3.4).

**Layer 3 — the who-did-what trail.** `AuditTrail` (`inspecto/src/main/java/com/gamma/control/AuditTrail.java`)
is **one central interceptor called from `ControlApi.dispatch`** — not per handler. It records every
successful state-changing request (`POST`/`PUT`/`DELETE`), export `GET`s, and non-GET attempts on
forbidden routes, classified fail-closed to `AUDIT` / `ACCESS_DENIED`, with actor, action, target, IP and
user agent as attributes. Actor comes from `ApiContext.actor`; on Personal (no authenticator) it is the
default `appUser`. **Scope boundary, stated, do not overclaim:** authentication events (sign-in/out,
MFA, true 401/403 at the authenticator) are **not** in it; *authorization* decisions are
(`access.denied`/`access.granted`, ABAC A5). Secrets are scrubbed at `EventLog.emit`, not here.

**Durability claims, exactly.** The trail is **append-only by construction** — one write seam, no update
or delete route, 405 inherent to dispatch — and that is the whole claim. It is **not tamper-evident** (no
hash chain, no signature), not permission-hardened (no `PosixFilePermission` call exists repo-wide), and
the in-memory backend **drops** on restart; the Parquet backend's buffer drops after 50k events on sustained
flush failure (`controls-matrix.md` AU-9). Anyone who needs evidential integrity must add it; see §5.

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

During an at-rest Pipeline run, `PipelineJobRunner` (`inspecto-engine/src/main/java/com/gamma/job/PipelineJobRunner.java`)
collects one `ProvenanceRow(pipelineId, batchId, nodeId, rel, rowCount, runTs)` per (node, relationship)
through `PipelineExecutor.ProvenanceCollector` **while the scratch relations are live**, persists them via
`DbProvenanceStore` (`inspecto-engine/src/main/java/com/gamma/pipeline/exec/DbProvenanceStore.java`, per
space through `ProvenanceStores`), then runs `ConservationCheck.imbalances(graph, counts)`
(`inspecto-engine/src/main/java/com/gamma/pipeline/exec/ConservationCheck.java`) and emits one
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

**Verification protocol** — `docs/ops/provenance-conservation-verification.md` (signed 2026-07-08):
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
| `GET /jobs/metrics`, `/jobs/runs`, `/jobs/failures` | the T27 job-execution projection over `DbJobRunStore` (`inspecto-engine/src/main/java/com/gamma/job/DbJobRunStore.java`); **`404` unless `-Djobs.backend=duckdb\|postgres`**; the Jobs pane's Reporting mode degrades to an explained "reporting disabled" |
| `POST /jobs/runs/{runId}/replay` | 🔴 **NOT gated by `-Djobs.backend` — this row grouped it with the projection routes until 2026-09-09 and that is the wrong cause.** Replay resolves the original run through `JobService.runById` → `liveRuns`, an in-memory map capped at `LIVE_RUN_CAP` and lost on restart. The two failure modes are **opposite**: replay WORKS with the projection off for a run still in memory, and `404`s with the projection ON for one that has been evicted. Owner: [`okf/capabilities/pipeline-execution/pipeline-execution.md`](../pipeline-execution/pipeline-execution.md) §2.3 |
| `GET /system/operational-db`, `POST /system/operational-db/test` | what this deployment actually uses per store family, and a real `SELECT 1` against a proposed JDBC URL; `canConfigureAccess`; **no PUT exists** (the pane's spec asserts it) |

**Live gauges are in-memory and poll-read.** `InboxStatus` (`inspecto/src/main/java/com/gamma/service/InboxStatus.java`)
carries `pending`, `running`, `current` (file *i* of *n*, `IngestProgress.Snapshot`) and `step` (chain step
*i* of *n*, `StepProgress.Snapshot`). **Per-step Signals were refused** (2026-08-12, §6): a Signal is a
durable ledger write and would fire `on_signal` work while the batch claim is held.

**The file run log is always on**: every Job run appends to `jobs_audit/jobs_runs.csv` and its JSONL
artifacts (`-Djobs.runlog.maxEntries`, default 10,000); `runlog_prune` is its retention task.

### 3.6 Maintenance & retention

**System maintenance is tasks on the `maintenance` Job Type — never shell scripts or OS cron** (2026-07-12).
`MaintenanceJob` (`inspecto-engine/src/main/java/com/gamma/job/MaintenanceJob.java`) dispatches on the
`task` parameter (default `cleanup`); each task is its own `*Task` class in `com.gamma.job`.

| Task | Deletes / does | Home | Notes |
|---|---|---|---|
| `cleanup` | files under `dir` breaching age/count/size (`max_count`, `max_size`, `archive_dir`, `min_keep`) | core | the **only** task with a `retention_days` default (7); the newest N are never retired |
| `ledger_prune` | acquisition-ledger fingerprints | core | ⚠ a pruned file still at the source **re-ingests as new** |
| `dedup_prune` | windowed `transform.dedup` keys | core | |
| `runlog_prune` | Run history JSONL + artifacts + the `inspecto_job_runs` projection | core | |
| `notification_prune`, `receipt_prune` | in-app feed entries (any read state); delivery receipts | core | the receipt store's 5,000 cap is a **backstop, not retention** |
| `event_prune` | the Parquet event store's day-partitions older than the window | core | COMPLY-3; one-year window is policy; `-1` on the memory backend |
| `partition_prune` | a sink store's `year=/month=/day=` directories older than the window (path-jailed) | core | 2026-09-06; whole days only, catalog rows stay |
| `retire_superseded` | the **bytes** of output revisions the outputs catalog marks `SUPERSEDED` | core | ⚠ **the one retention task a correctness fix depends on** — without it every full recompute leaves a complete extra copy forever; since 2026-08-29 the runner **WARNs** after a recompute when no enabled job exists |
| `storage_report`, `storage_trend` | per-axis usage (+ a queryable `maintenance_storage` sample on a real run); two-point growth slope, breach ETA, archive candidates | core | trend emits `maintenance.storage.trend` (WARN) inside `warn_days` |
| `db_maintenance`, `scheduler_audit`, `metadata_validate`, `file_repository_audit`, `materialize` | CHECKPOINT/VACUUM via host seams; cron sanity; broken refs/duplicates/missing data; repository consistency; view materialisation | core | |
| `backup`, `backup_verify`, `restore` | timestamped zip + SHA-256 sidecar manifest via `Checksums`; hash-first verify (fail-closed); manifest-validated restore with **zip-slip jail** and conflict preview — archive-based, the whole config tree, *not* bundle import | **`inspecto-backup`** (`BackupTaskProvider`) | Standard+/Enterprise (`OPS-06`, cell 2, 2026-09-07). On Personal: *unknown maintenance task*, refused loudly. Pinned by `NoBackupTaskShipsInThePersonalBuildTest` |
| `incident_purge` | Archived Incidents with notes, links, tag edges — **the only task that deletes operator business records** | **`inspecto-ops`** (`OpsMaintenanceTasks`) | MNT-14; `retention_days` required, `max_count` default 1000; legal hold fail-safe and re-checked inside `purge()`; G3 — the audit history survives. **Nothing schedules it, and nothing should** |

The **`MaintenanceTaskProvider`** ServiceLoader seam (`inspecto-engine/src/main/java/com/gamma/job/MaintenanceTaskProvider.java`):
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
`runlog_retention_job`, `scheduler_audit_job`. ⚠ On a **Personal** install the demo chain **stops at
`config_backup` with a FAILED run** — the edition boundary working, not a defect (`OPS-06`, runbook).
Runbook: `docs/ops/backup-restore-runbook.md`.

### 3.7 Scheduler caps, health and the ops-plane knobs

**Concurrency (D11, 2026-08-26).** `JobService.DEFAULT_MAX_CONCURRENT_RUNS = 4` is **on by default**;
`0` = unbounded; hot-resizable, a shrink **drains**. It is owned by the settings tier — `scheduler.toon`
→ `GET|PUT /system/scheduler` (`SchedulerRoutes.java`, `canOperateRuns`) — and `-Djobs.maxConcurrentRuns`
is a *bootstrap default* consulted only when nothing is stored (⛔ a key served by the settings tier must
not also be read from `-D` at use time). The same document carries the Consignment broker's globals:
`ConcurrencyBroker` (`inspecto-engine/src/main/java/com/gamma/inspector/ConcurrencyBroker.java`) grants
**weighted shares, never precedence** — FIFO per Pipeline, `stride = 6 / priority` (1–3), so a priority-1
Pipeline provably keeps a non-zero share; `IntakeGovernor` holds the intake caps (`intakeMaxFilesPerCycle`,
per-space `maxConcurrentConsignments`). The Scheduler-settings pane shows each value's provenance chip
(`file`/`property`/`default`). ⚠ **The DuckDB `memory_limit` half has NO default** (GAP-4, verified again
2026-09-08: `DuckDbUtil.memoryLimit(null)` is `null`, no `scheduler.toon` ships, the committed corpus sets
`memory_limit: ""`). `jobs.md` §Total-concurrency described the cap as "the other half of the
`memory_limit=2GB` default" — corrected in this commit; the DAT spec §2 carries the same finding.

**Run budget.** `-Dservice.max.runs=M` (`ControlApi.main`, `CollectorService`) bounds a service
process's ingest runs — a CLI/service knob distinct from job concurrency.

**Health.** `/health` and `/ready` are the connectivity probes (public, unversioned; `/ready` reports the
registered pipeline count); `/health/details` is the operator's per-subsystem view (§3.5).
`-Dcontrol.bind` unset = **every interface, in every edition** — a deliberate call, documented in
[`operations.md`](../../backend/build-run/operations.md), and the reason EDG-01 chose modules over `-D`
switches (§4).

**The operations zone.** `-Dops.timezone` (`OperationsZone`, `inspecto-util`) governs cron firing and
`$today`; it is *not* the data zone (`parsing.source_timezone`) and DuckDB's session `TimeZone` stays the
host's — the rules are `DAT`'s.

**Flags this layer reads** (readers verified 2026-09-08):

| Flag | Default | Reader |
|---|---|---|
| `-Devents.backend` / `-Devents.dir` | `memory` (⚠ launcher sets `parquet` on Standard+) / `SpaceRoot.eventsDir()` — `<space>/data/events`, and only `inspecto-events` in the CWD under the legacy root | `ServiceStores.java`, `inspecto/package.ps1` |
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

### 3.8 The Ops Lens screens as consumers

| Screen (route, component) | Contracts consumed | Edition behaviour |
|---|---|---|
| **Events** (`/events`, `events.component.ts`) | `/events/search` (level/type/pipeline/q/correlationId, offset paging), live tail by `visibleInterval` polling (2/5/10/30/60 s), `/events/export` → client Blob, `/events/views` CRUD, `EventDetailDialog` re-filters by correlation/type, "What led to this" → `AiStatusDialog` | `features.events=false` → explained state, no call; Ops lens home falls back to `/pipelines` (`OPS_HOME_WITHOUT_EVENTS`, `app.routes.ts`) |
| **Audit log** (`audit-logs.component.ts`) | core `/audit/search` for `AUDIT` + `ACCESS_DENIED` via `forkJoin`; columns Time, Actor, Action, Category, Target, IP, Message | **not** gated on `features.events` — reads core routes on every edition |
| **Overview** (`/overview`, `dashboard.component.ts`) | `/ready`, `/status`, `/report` (p50/p95/p99, outcomes), `/metrics/acquisition` KPIs, raw `/metrics` text behind a `showMetrics` toggle, recent events | each fetch degrades independently; the events tile is skipped when `eventsEnabled` is false; the raw-metrics toggle meets a `503` on Personal (§5) |
| **Runs / Run detail** (`/runs`, `/runs/:name`) | `RunRoutes` list/trigger/pause/resume/reprocess; tabs batches/files/lineage/quarantine/commits/report; `InboxStatus` current/step gauges; "Failed consignments retry automatically" notice; "View the rejected rows" | Business lens read-only (2026-07-03) |
| **Jobs** (`/jobs`, `jobs.component.ts`) | schedules mode (list/trigger/enable/reschedule/edit/delete) and **reporting** mode (`/jobs/metrics|runs|failures`, 5 s live tail); the authoring form takes its **task picker from `GET /jobs/types`** — which maintenance tasks appear is entirely what the backend registered, so backup/restore vanish on Personal with no UI change | reporting 404 → `reportDisabled` explained |
| **Processing status** (`processing-status.component.ts`) | `/status`, `/status/problem-files`; row action opens Run detail | |
| **Scheduler settings** (`settings/scheduler.component.ts`) | `GET|PUT /system/scheduler`; `memoryLimit` validated by the server-served DuckDB grammar; provenance chips | saves gated on `canOperateRuns` |
| **Operational DB** (`settings/operational-db.component.ts`) | `GET /system/operational-db`, `POST …/test`; no PUT | `canConfigureAccess` |

There is no metrics-browsing UI and no backup/restore screen beyond the Jobs form — both by design.

### 3.9 The completeness KPI — design of record (not built)

⚠ Distilled 2026-09-09 from `completeness-kpi-plan.md`, archived the same day. K1, K2's analysis half,
K3 and K5 shipped; **K2's wiring and K4 are ON HOLD by operator decision (2026-08-30)**. The hold is on the
**work**, not the document, and it has no dated lift condition. ⚠ The shipped half is dormant —
`VolumeBaseline` and `FileSequenceGaps` have **no production caller**, because K4 was to be the caller.

**The two deviation bases (operator, 2026-08-30 — ⛔ do not re-ask):**

- **File deviation is EXACT, from the sequence template** — the template already implies how many files a
  period should hold.
- **Record deviation is STATISTICAL, from a rolling prior-period baseline.** ⛔ There is no declared
  expected row count and **inventing one was refused**: a fabricated target would make every deviation an
  artefact of the fabrication rather than a fact about the data.

**Two ways this can silently report nothing. Both must fail loudly** — a completeness KPI that quietly
reports zero is worse than one that is absent, because it manufactures false confidence in exactly the
number it exists to check.

1. `-Dconsignment.outputs.backend=none` makes recording a no-op (fail-open by design). The job must
   **refuse and say so**, never emit zeros.
2. ⚠ **`bounds` is nullable** for sinks with no partition or event time — dataset and enrichment sinks.
   Those pipelines' daily counts are **UNKNOWN, not zero**, and the KPI must carry that distinction **end to
   end**. 🔴 Only the first trap ever reached the board; this one is now `KPI-UNKNOWN-1`.

**Why `consignment_outputs` is the substrate, and what was rejected** (the correction is load-bearing — the
operator's own first choice could not answer the question):

- ⛔ **`CommitLog`** was that first choice. One row per batch, **no partition or day column**: a batch spans
  record-days and a record-day receives rows from many batches, so per-day counts are **not derivable**.
  Durable and default-on — simply the wrong shape.
- ⛔ **The `_lineage_<runTimestamp>.csv` ledger** *does* carry `partition,row_count` per record-day, but it
  is **buffered rather than fsync'd** and written **one file per run**, so reading it means globbing many
  files and it can lose a tail on a crash. Not a ledger to build a KPI on.
- ✅ **`consignment_outputs`** — durable, a per-output-file `rows` count, `record_day`/`bounds`, written from
  the ordinary ingest path, on by default since 2026-08-10. See `okf/backend/engine/db-layer.md` §3.9.

⚠ **Naming tension, stated rather than hidden.** The requirement is "a KPI *not related with* Consignment"
and the chosen store is *named* `consignment_outputs`. The coupling is nominal — it is the ordinary
per-output-file registry on the normal write path, not a Consignment-only structure. **If the name later
matters, that is a rename, not a redesign.**

**Verify gates.** These are the acceptance criteria; K1's and K3's had no home anywhere before this.

| Slice | Gate |
|---|---|
| **K1** | a day receiving rows from several batches sums correctly · a disabled registry **refuses** rather than returning zeros · a null-`bounds` sink reads UNKNOWN |
| **K2** | a window-edge silent hour is found · an interior hole is exact · the undetectable tail and the uncountable empty bucket are both pinned |
| **K3** | steady reports no deviation · a halved day breaches · an empty history reads `NO_BASELINE` · the unknown-day bucket neither raises the baseline nor stands in for a missing target day |
| **K4** | real cron arming · a breach opens **exactly one** Incident across repeated runs · a pipeline whose registry is off **fails the run visibly** |

🔴 **K1 is the whole risk.** K2–K4 are assembly over existing parts; K1 is the only slice that has to be
right about what the data actually says.

`VolumeBaseline`'s contract is worth keeping here because it is counter-intuitive: a day **absent** from
K1's series is **not a zero** (absence covers both "received nothing" and "was not expected to run"), so
absent days never enter the baseline and an absent target day is `NO_OBSERVATION`; the baseline is the
**lower median, not a mean**, so one recompute spike neither manufactures nor masks the next day's breach;
and a **zero baseline yields a null deviation** — undefined, not −100 %.

## 4. Decisions (dated one-liners)

| Date | Decision | Who / where |
|---|---|---|
| 2026-06-29 | Notifications + audit trail shipped (`ddfa288`): **no message broker** — in-process virtual threads over an append-only `EventStore` is the idiom; email is an edition seam | `events-metrics.md` §Notifications; archived notification plan |
| 2026-06-29 | `AuditTrail` is **one interceptor in `ControlApi.dispatch`**, not per handler; authentication events out of scope (deferred, later made a standing entry) | same |
| 2026-07-06 | Signal network: **full unification** — Event/Alert/Notification become views over one ledger, not additive-converge | product owner, `signal-network-plan.md` (archive) |
| 2026-07-08 | OPS-5's discharge criterion is the signed verification protocol; cannot close offline | `docs/ops/provenance-conservation-verification.md` |
| 2026-07-12 | Maintenance = tasks on the `maintenance` Job Type, never shell/OS cron; dry run is fail-closed (no preview ⇒ no-op); `/health/details` auth-gated, `/health` stays public; authoring stays in Workbench → Jobs | MNT plan (archive), `jobs.md` §Maintenance |
| 2026-07-18 | **Signal-primary flip** (D1a): implement the `openapi-v1.json` `Signal`; `Event` becomes the projection | operator, `event-signal-backbone-plan.md` (archive) |
| 2026-07-19 | No `@PublicApi` bump for the envelope change — 4.x unreleased (D2); AG-UI is a thin edge adapter, "AG-UI-shaped, domain-named" (D3) | same |
| 2026-07-22 | `/signals/tree` requires `correlationId`; tree and `signal_timeline` share `Signals.assembleTree` | `signal-backbone.md` |
| 2026-07-23 | Conservation imbalance gets a built-in notification rule (`ops`, WARN) so both LOSS and AMPLIFICATION notify | `events-metrics.md`; verification protocol |
| 2026-07-23 | `storage_trend`: `created_ms` is the sort key (ISO strings are not reliably chronological); predictive maintenance deferred to AGT-5 | `jobs.md` |
| 2026-07-25 | Incident retention is a **retention tier, not archive-is-terminal** (D5) | `jobs.md` §incident_purge |
| 2026-07-25 | `inspecto_legacy_api_requests_total` removed; `/health`, `/ready`, `/metrics`, `/metrics/acquisition` are the four routes that stay unversioned | `events-metrics.md`, `ControlApi.isInfraRoute` |
| 2026-07-27 | `incident_purge` (MNT-14): `retention_days` required, no default; derived from `closedAt`; legal hold fail-safe and re-checked in `purge()`; **G3 — a purge is not "all trace removed"**; **nothing schedules it and nothing should** | `jobs.md`, `operations-reference.md` §Retention |
| 2026-08-01 | The run-claim hand-off seam moved from the global `ingestLock` to `PipelineRunGuard`; every `EventLog` subscriber must hand off | `events-metrics.md`, `signal-backbone.md` §Gotchas |
| 2026-08-12 | **Per-step Signals refused** — in-memory step progress, poll-read (S7/G6) | consignment-chain plan (archive) |
| 2026-08-26 | D11: `maxConcurrentRuns=4` on by default, owned by `scheduler.toon`/`/system/scheduler`; `-D` is bootstrap only; **no computed cap**, preview connections uncapped, `max_temp_directory_size` no default | `jobs.md`, `d11-resource-caps-plan.md` (archive) |
| 2026-08-29 | A recompute that superseded revisions WARNs when no enabled `retire_superseded` job exists — the silent disk cost made loud | `operations-reference.md` |
| 2026-08-30 | The audit-retention window is **one year**; applied by a partition delete, never a SQL `DELETE` | operator; `controls-matrix.md` G5 |
| 2026-08-31 | `-Dstatus.backend` default flipped to **`db`** | `OperationalDb.java`; `operations-reference.md` §Status backend |
| 2026-09-02 | `event_prune` shipped (COMPLY-3); `partition_prune` followed 2026-09-06 | `jobs.md`, `EDITIONS.md` JOB-03 |
| 2026-09-07 | EDG-01 cell 2: backup tasks moved to `inspecto-backup` via `MaintenanceTaskProvider`; duplicate claims refused fail-closed; Personal refuses loudly, never a silent SKIPPED | operator; `EDITIONS.md` OPS-06 |
| 2026-09-07 | EDG-01 cell 5: only the `/metrics` **exposition** gated (`inspecto-metrics`); `MetricRegistry` stays core; path stays public — "a scraper carries no token" | `EDITIONS.md` CP-13 |
| 2026-09-07 | EDG-01 mechanism: **ServiceLoader modules, ⛔ not `-D` capability switches** — a switch leaves the code in a bundle that ships no authenticator and binds every interface | operator; `EDITIONS.md` §EDG-01 |
| 2026-09-07 | `living-operational-system.md` distilled to the OKF north star with **no as-built column** ("a state column rots") | `BACKLOG.md` §5 |
| 2026-09-08 | EDG-01 cell 6: the events feed + audit CSV export moved **whole** to `inspecto-events` (gating the CSV alone needed an `if` inside a core route, banned by §Assembly); recording not gated; new core `AuditLogRoutes` keeps `/audit/*` on every edition | operator; `EDITIONS.md` CP-13, §Audit |
| 2026-09-08 | This spec: OPS-2's "ungated" cell, OPS-3's "tamper-evident"/"sign-ins", OPS-4's "off by default" and the 7-field GLOSSARY envelope corrected; `OPS`/Ops-Lens naming applied to REQUIREMENTS §3.7 | this file §2 |

## 5. Not built

### 5.1 Tracked (a `docs/BACKLOG.md` row exists)

| Item | Row |
|---|---|
| OPS-5 provenance conservation **live soak** — no code left; outcome log empty | §2 *OPS-5 provenance conservation* |
| Deployment-topology live validation (closes OPS-5's "needs a live deploy" too) | §2 *Deployment topology live validation* |
| Compliance program NFR-7 external sub-items; G6 restore drill record + RTO/RPO targets | §2 *Compliance program (NFR-7)*; §5 *Compliance repo-side artifacts* |
| GAP-4 DuckDB `memory_limit` default (the missing half of D11) | §3 P2 *Deployment topology gaps* |
| Completeness KPI — K2 wiring, K4 `kpi.completeness` job type (signal + deduped Incident) — **on hold by operator, 2026-08-30**; the shipped half (`VolumeBaseline`, `FileSequenceGaps`) has no production caller. Design of record is now §3.9 of this spec, not the plan | §2 *Completeness KPI hold*; §3 P2; `KPI-UNKNOWN-1`; archived plan `docs/archived-documents/plans-archive/completeness-kpi-plan.md` |
| Signal/Decision follow-ons: optional S8 (connector-direct emission, cross-space controller); a general event-triggered consequence policy gate (today `/apply`-only); RFC 6902 AG-UI deltas | §3 P3 *Signal / Decision networks* |
| Maintenance COULD tier — **the full list, landed 2026-09-10 (Sprint 7.2) from the archived maintenance plan, where it was its only home**: space-to-space comparison · predictive maintenance (AGT-5) · AI recommendations (AGT-5 P1+) · self-healing · backup **encryption** and compression tuning (the backup writer is a plain zip: no cipher, no compression-level knob) · **incremental/differential** backup (the writer walks the whole tree every run — no diff, no watermark) · backup **deduplication** · a **health score** (the health route answers UP/DOWN/NOT_CONFIGURED only; a numeric composite was deferred in the plan itself) · **agent-session retention** — ⚠ **blocker re-verified 2026-09-10 and still live**: agent sessions are a `ConcurrentHashMap` cleared on shutdown, and none of the module's four durable rings backs one, so there is nothing to retain yet. ⛔ Two of the plan's COULDs must NOT be carried forward: **growth-trend analysis + archive recommendations SHIPPED** as `storage_trend` (§3 above dates it), and **Dev/Prod maintenance "profile presets"** is refuted as framed — job templates exist, but no Dev/Prod tiering concept exists anywhere in `spaces/`. Its open question *"does archived material need its own retention tier"* is answered by the code: **archive is terminal** — `incident_purge` hard-deletes, and `archive_instead_of_delete` moves files once and never re-walks them | §3 P3 *Job framework* |
| D8 residuals (soft-bounce retry, SES/SNS, GeoIP, per-user prefs) — `INC`'s, listed for the ledger's sake | §3 P2 *Notifications*, `D8-SUPPRESS-1` |
| Standing LEAVEs recorded as working-as-designed: MNT-14 has no UI and no shipped instance; "nothing prunes by default"; `-Djobs.maxConcurrentRuns` is the only bound; `AuditTrail` has no authentication events; D11's uncapped preview connections; `BackupTask.restore`'s jail (PATH-2); ARCH-OPS-SCC (85-file ripple) | §6 |

### 5.2 UNTRACKED — found 2026-09-08, no board row yet

> ✅ **Filed 2026-09-09 (Sprint 2).** These findings are no longer untracked. The **cross-cutting** ones
> — those no single area owned, which is why they sat here — are filed as cross-cutting
> `docs/BACKLOG.md` rows. ⚠ The list below is matched **by family, not per item**, so treat it as a
> starting point and read the row before acting on it:
> `CONSUMER-PAIRS-1`, `SPEC-STALEREF-1`, `SPEC-GLOSSARY-1`, `SPEC-ORPHANPAGE-1`.
> ✅ **`CONSUMER-PAIRS-1` decided 2026-09-10 (operator, per row):** `GET /signals/stream` **ADOPT** — the Events pane subscribes, polling stays as the fallback → `BACKLOG.md` §3 `CLIENT-HALVES-1`.
>
> ⚠ **The remainder stay here deliberately, and that is their correct home.** A finding that is
> area-specific, is *design* rather than a defect, and is recorded in the owning spec's §5 is already filed —
> copying it onto the board would give it two homes and one of them would go stale. The board holds what
> **crosses** areas; a spec holds what belongs to **one**. See
> [`archived-documents/plans-archive/post-consolidation-sprints.md`](../../../archived-documents/plans-archive/post-consolidation-sprints.md) §Sprint 2.

1. **`GET /signals/stream` (SSE) has no client.** The Events pane polls; nothing in `inspecto-ui/src/app`
   opens an `EventSource`. The sixth instance of *server half shipped, no consumer* — not a Must this time
   (OPS-1's "live tail" is met by polling), but it belongs in the same product conversation as the five
   Musts (`If-Match`, cursor pagination, `permissions[]`, `POST /queries/{id}/run`, Expectations).
2. **No `event_prune` Job instance ships anywhere** (`spaces/`, `docs/ops`), so the one-year audit window
   is stated policy on every deployment that does not author it. Either ship a disabled template in
   `spaces/demo` (like `retention_job_template`) or record the omission as a deliberate operator act the
   way `incident_purge` is.
3. **Tamper-evidence is a promise with no owner.** `SEC-09`'s title, `USER_GUIDE.md` §3 and the GLOSSARY
   said it; AU-9 says explicitly not. Corrected to "append-only" here; whether evidential integrity (hash
   chain / signed partitions) is ever wanted needs a product decision, not a doc edit.
4. **The Overview's raw-metrics toggle on Personal** calls `/metrics` and receives `503`; the tile has a
   per-call `catchError` but whether it renders the *explained* state (module absent) rather than a
   generic failure was not verified.
5. **Three Ops Lens screens have no OKF Feature page**: Audit log, Processing status, and the Scheduler /
   Operational-DB settings panes (`okf/frontend/features/index.md` already says "not yet documented").
6. **`operations-reference.md`'s route table** carries no `/audit/*` and no `/health/details` row — the
   core audit read surface and MNT-15 were absent from the long-form operations manual. Two rows were added
   in this commit; the rest of that 1,100-line Reference still carries its own "not the authority" banner.
7. **`docs/ops/backup-restore-runbook.md`** cited its plan of record at a `docs/superpower/` path that
   no longer exists (archived) — fixed in this commit; the runbook also predates cell 2 and still reads as
   if backup were core (the edition caveat is one line).
8. **`ChannelConfig` cannot override a rule-level `template`** — the Signal-Backbone S2 slice archived
   unbuilt; recorded in the `INC` spec §5, repeated here because the field lives on the ledger side.
9. **`SignalIngress` is tested but deliberately unwired** (its ERROR+ floor differs from the
   `ContextBroker`'s WARN+); `AgentAskResult.artifact` has no live producer — `AGT`'s to decide, listed
   because both ride this ledger.

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
| `-D` capability switches for edition gating | **Refused** 2026-09-07 | a switch leaves the code in the Personal bundle — ServiceLoader modules instead |
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
| Bus, store, types, scrubbing | `inspecto-event/src/main/java/com/gamma/event/EventLog.java`, `EventStore.java`, `InMemoryEventStore.java`, `ParquetEventStore.java`, `EventType.java`, `SecretScrubber.java`, `SavedViewStore.java` | [`events-metrics.md`](../../backend/control-plane/events-metrics.md) |
| Signal envelope & read side | `inspecto-engine/src/main/java/com/gamma/signal/Signal.java`, `Signals.java`, `PipelineConsignmentSignal.java` | [`signal-backbone.md`](../../backend/control-plane/signal-backbone.md) §S0–S2 |
| Feed routes (optional) · fallbacks · audit read | `inspecto-events/src/main/java/com/gamma/eventsapi/EventRoutes.java`; `inspecto/src/main/java/com/gamma/control/AbsentEventsRoutes.java`, `AbsentMetricsRoutes.java`, `AuditLogRoutes.java`, `SignalRoutes.java` | `EDITIONS.md` CP-13, §Audit; [`events.md`](../../frontend/features/events.md) |
| Metrics | `inspecto-event/src/main/java/com/gamma/metrics/MetricRegistry.java`; `inspecto-metrics/src/main/java/com/gamma/metricsapi/MetricsRoutes.java`; `inspecto/src/main/java/com/gamma/control/AcquisitionRoutes.java`; `inspecto-engine/src/main/java/com/gamma/inspector/AcquisitionTelemetry.java` | `ADVANCED_GUIDE.md` §7 (`/metrics` catalog) |
| Audit trail | `inspecto/src/main/java/com/gamma/control/AuditTrail.java` (called from `ControlApi.dispatch`) | `compliance/controls-matrix.md` AU-9, ISO 8.15–8.17 |
| Provenance & conservation | `inspecto-engine/src/main/java/com/gamma/pipeline/exec/DbProvenanceStore.java`, `ProvenanceStores.java`, `ConservationCheck.java`; `inspecto-engine/src/main/java/com/gamma/job/PipelineJobRunner.java` (`reportConservation`); `inspecto/src/main/java/com/gamma/control/LineageRoutes.java`, `JobRoutes.java` (`/provenance*`); `inspecto-engine/src/main/java/com/gamma/notify/NotificationRules.java` | `docs/ops/provenance-conservation-verification.md` |
| Reporting, status, health | `inspecto/src/main/java/com/gamma/report/ReportService.java`; `inspecto/src/main/java/com/gamma/control/RunRoutes.java`, `HealthDetails.java`, `SystemRoutes.java`; `inspecto/src/main/java/com/gamma/service/InboxStatus.java`, `ServiceStores.java`, `OperationalDb.java`; `inspecto-engine/src/main/java/com/gamma/job/DbJobRunStore.java` | [`operations-reference.md`](../../backend/build-run/operations-reference.md) §Reports, §Status backend |
| Maintenance & retention | `inspecto-engine/src/main/java/com/gamma/job/MaintenanceJob.java`, `MaintenanceTaskProvider.java`, `EventPruneTask.java`; `inspecto-backup/src/main/java/com/gamma/backup/BackupTask.java`, `BackupTaskProvider.java`; `inspecto-ops/src/main/java/com/gamma/opsjob/OpsMaintenanceTasks.java`; `spaces/demo/config/jobs/*.toon` | [`jobs.md`](../../backend/control-plane/jobs.md) §Maintenance; `operations-reference.md` §Retention & purging; `docs/ops/backup-restore-runbook.md` |
| Caps & scheduler | `inspecto-engine/src/main/java/com/gamma/job/JobService.java`; `inspecto/src/main/java/com/gamma/control/SchedulerRoutes.java`; `inspecto-engine/src/main/java/com/gamma/inspector/ConcurrencyBroker.java`; `inspecto-util/src/main/java/com/gamma/util/OperationsZone.java` | `jobs.md` §Total-concurrency; [`duckdb.md`](../../backend/engine/duckdb.md) (memory half) |
| Launch flags | `inspecto/src/main/java/com/gamma/control/ControlApi.java` (`PUBLIC_PATHS`, `isInfraRoute`, `-Dcontrol.bind`) | [`operations.md`](../../backend/build-run/operations.md) |
| UI consumers | `inspecto-ui/src/app/modules/admin/{events,audit-logs,dashboard,runs,run-detail,jobs,processing-status,settings}/`, `inspecto-ui/src/app/app.routes.ts` | [`events.md`](../../frontend/features/events.md), [`runs.md`](../../frontend/features/runs.md), [`run-detail.md`](../../frontend/features/run-detail.md), [`dashboard.md`](../../frontend/features/dashboard.md), [`jobs.md`](../../frontend/features/jobs.md) |
| North star | — | [`living-operational-system.md`](../../living-operational-system.md) (shape only, no state column) |

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
  `inspecto-events` — `ControlApiEventsTest`, `ControlApiEventsPageTest`; `inspecto-metrics` —
  `MetricsExpositionTest`; `inspecto-backup` — `BackupTaskTest`, `BackupPathContainmentTest`;
  `inspecto-ops` — `IncidentPurgeTaskTest`, `EventObjectBridgeTest`; `inspecto-engine` —
  `MaintenanceLibraryTest`, `NoBackupTaskShipsInThePersonalBuildTest`, `JobServiceTest`,
  `JobServiceOrphanAuditTest`, `PipelineJobRunnerTest`, `ConservationCheckTest`, `ConsignmentProvenanceTest`,
  `DbJobRunStoreTest`, `ConcurrencyBrokerTest`, `EnrichmentAuditReaderTest`. UI: 21 vitest specs under
  the eight `modules/admin` directories in §7 (Processing status and `rejected-rows.dialog` have none).
* **Contract** — `ApiContractTest` holds `openapi-v1.json` to the served routes; the four infra routes are
  the documented exemptions from `/api/v1`.
* **Guards** — `node tools/check-vocabulary.mjs`, `node tools/check-doc-links.mjs`,
  `node tools/check-gate-tally.mjs` from the repo root; the pre-push hook re-runs the vocabulary guard.
* **Falsify, don't read** — the two claims most worth re-probing on a running server: `curl -i
  localhost:8080/metrics` on a Personal bundle must be `503`, never `# HELP`; `curl -i
  localhost:8080/api/v1/health/details` without a token on Standard must be `401`, while `/health` is `200`.
