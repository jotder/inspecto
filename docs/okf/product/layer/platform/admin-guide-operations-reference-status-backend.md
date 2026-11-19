---
type: Reference
title: Operations reference — utilities, batching, output, deployment — Batch processing — the status backend onward
description: Part 3 of 4 of Operations reference — utilities, batching, output, deployment: 
tags: [operations, utilities, deployment, batching, output, onboarding]
timestamp: 2026-07-16T00:00:00Z
item: layer.platform
audience: internal
status: current
verified: 2026-11-20 last-edit
---

# Operations reference — utilities, batching, output, deployment — Batch processing — the status backend onward

Part 3 of 4 of [Operations reference — utilities, batching, output, deployment](admin-guide-operations-reference.md) · previous: [Batch processing](admin-guide-operations-reference-batch-processing.md) · next: [Remote deployment and onboarding a Collector](admin-guide-operations-reference-deployment.md).

## Batch Processing (continued)

### Status backend — database (default) or file (`FileStatusStore`)

The audit queries above (`commits`/`batches`/`files`/`lineage`/`quarantine`) and the
observability gauges read through a pluggable **`StatusStore`**. ⚠ **The default is `db`, not
`file`** — it flipped on 2026-08-31 (`OperationalDb.java` declares `STATUS("Status",
"status.backend", "db", …)`); this section previously said the opposite. The service projects the
audit into a database and serves queries from it — durable and SQL-queryable — while ingest keeps
writing the file audit unchanged (it stays the write-time source of truth and survives a DB
outage). Set `-Dstatus.backend=file` to read the on-disk audit artifacts directly
(`FileStatusStore`) instead.

🔴 **Where the projection refresh is hooked matters, and two obvious homes are wrong on purpose.** It runs
in `CollectorService.runPipeline` — **once per run, after the run, outside the run guard** — and is pinned by
`StatusProjectionFreshnessTest` (three cases; commenting out the one call fails two of them). ⛔ **Not on the
batch event bus**, which delivers *synchronously on the ingest thread* once per batch and would put repeated
database work on the ingest path. ⛔ **Not inside the scheduler's single-run entry point**, which batches the
cycle's refresh deliberately and does not route through `runPipeline`, so the two never double up. ⚠ The
first diagnosis of this — *"it projects exactly once, at boot"* — was **false**, which is why the behaviour is
written down rather than re-derived. *(Distilled 2026-09-10 (Sprint 7.6) from the three archived plans; this was their only home.)*

The DB engine is **DuckDB by default** — already bundled for ingest/enrichment, so the DB
backend adds **no new dependency** and the same engine serves tests and production. With no
URL given it opens a local file `inspecto-status.db`:

```bash
# DuckDB (default DB backend — embedded, single-process, zero extra deps)
java -cp inspecto.jar com.gamma.control.ControlApi \
     -Dstatus.backend=db \
     -Dstatus.db.url="jdbc:duckdb:/var/lib/inspecto/status.db" \
     config/
```

The store is engine-neutral JDBC over portable SQL. It creates its schema on first connect
(`inspecto_status_{commits,batches,files,lineage,quarantine}`; pre-rebrand `ucc_status_*`
tables are renamed in place on first connect, and a legacy default `ucc-status.db` file is
still picked up when no `inspecto-status.db` exists) and **syncs at startup and after
every poll cycle**, so the DB reflects the latest committed state (up to one cycle of
staleness) and the API/observability transparently read from it — no endpoint changes. A sync
is a transactional DELETE-then-INSERT per pipeline, so it is idempotent and doubles as the
migrate/backfill of existing file audit into the database.

🔴 **Only a run or a boot makes a ledger observable — there are exactly TWO sync points, and
nothing polls the filesystem.** `CollectorService.start()` projects synchronously as its last boot step,
and a poll cycle projects when its last run finishes (`PipelineScheduler.runOne`, the `pending`
decrement). So a status artifact that appears on disk **without a run** — restored from backup, copied in
by an operator, or hand-written by a test — is invisible to every query surface until the next cycle,
i.e. up to one full `service.poll.seconds` (**default 60**). The file audit is intact the whole time; only
the projection is behind. `-Dstatus.backend=file` reads the artifacts directly and has no such window.

⚠ **For tests this is a race, not merely a delay, because `start()` schedules the first cycle with
initial delay 0** — that cycle's sync runs concurrently with the test body. Seed the ledger *after* boot
and it is observable only if the sync happens to land after your write; lose that coin-flip and the route
correctly returns 0 rows, with the next sync 60s away, so **retry-until-it-appears cannot recover it**.
⇒ **A test must seed every ledger / quarantine fixture BEFORE booting the space**, making the boot sync the
single deterministic projection point. Pinned by `ControlApiProblemFilesTest` (see its `open()` javadoc).
*(Established 2026-09-17: this is what made that class's `limitBounds…` case intermittent — 1 of 3
full-module runs red at an unchanged tree, green 8/8 alone. Reproduced deterministically by inserting a
2s sleep after boot, which forces the race to lose.)*

> **Future / distributed:** the same code path runs on **PostgreSQL** for a multi-writer or
> multi-node deployment — point the URL at `jdbc:postgresql://host:5432/inspecto` (with
> `-Dstatus.db.user`/`.password`) and put the PostgreSQL JDBC driver on the classpath. The
> driver is *not* bundled (bring-your-own) to keep the default fat-JAR lean; DuckDB's
> single-process file lock is fine for the current single-JVM service, and Postgres is what
> you switch to when you split into separate processes/nodes.

### Object backend (Alert Center) — database (default), PostgreSQL (Advanced) or in-memory (`DbObjectStore`)

The object store records **operational objects** — managed, *mutable* things with a lifecycle
(`INCIDENT`, `CASE` and `TASK`), the counterpart to the immutable event log. Alerts are not objects:
since MODULE-REORG-P7 they live in their own `AlertStore` (table `inspecto_alerts`, every edition), walk
`OPEN → ACKNOWLEDGED → RESOLVED`, and an `ALERT` row left in an old object table loads inert. Because they mutate they live in a table store, not Parquet. Since 2026-09-25
(`OBJECTS-BACKEND-DEFAULT-MEMORY-1`) the default is durable, `-Dobjects.backend=db` — the same
engine-neutral JDBC-over-DuckDB pattern as the status backend (no extra dependency), in the Space's
`duckdb/` (legacy single-tenant file `inspecto-ops.db`). The Advanced `serve.*` passes
`-Dobjects.backend=postgres`, which **refuses the boot** unless every object family resolves to a
`jdbc:postgresql:` URL (`INSPECTO_DB_URL`, or `-Dobjects*.db.url`) and never degrades to memory.
`-Dobjects.backend=memory` survives only as an explicit, non-durable opt-in; any other value fails the boot:

```bash
java -cp inspecto.jar com.gamma.control.ControlApi \
     -Dobjects.backend=db \
     -Dobjects.db.url="jdbc:duckdb:/var/lib/inspecto/ops.db" \
     config/
```

It creates table `inspecto_ops_objects` on first connect and does a real `UPDATE` on each
transition (unlike the status store's DELETE-then-INSERT projection — these objects are the source
of truth). Every transition emits an `OBJECT_OPENED`/`OBJECT_ACTIVITY` event so the change shows in
the event log. A fired Alert is a row in the `AlertStore`, operated over its own routes:

```bash
curl -s -H "Authorization: Bearer $TOKEN" "localhost:8080/api/v1/alerts?limit=50"
curl -s -H "Authorization: Bearer $TOKEN" -X POST localhost:8080/api/v1/alerts/<id>/ack
curl -s -H "Authorization: Bearer $TOKEN" -X POST localhost:8080/api/v1/alerts/<id>/resolve
```

> **Future / distributed:** like the status backend, point `-Dobjects.db.url` at
> `jdbc:postgresql://host:5432/inspecto` (with `-Dobjects.db.user`/`.password` and the PostgreSQL
> JDBC driver on the classpath) for a multi-writer deployment. The default lifecycle for any
> `ObjectType` can be overridden by dropping a `*_workflow.toon` (a `workflow { object_type, initial,
> terminal[], transitions[]{from,to,action} }` block) into the space's config dir: `ServiceBootstrap`
> scans them at boot and installs each via `ObjectService.registerWorkflow`, so `GET /workflows/{type}`
> then serves the authored machine (last file wins per type; a malformed one is warned and skipped).

### Incident Tracker (Phase 3) — operator-created Incidents + SLA tracking

Incidents live in the `inspecto_ops_objects` table (`object_type=INCIDENT`), so there is no extra storage
or backend to configure. An Incident is **operator-created** with `POST /objects` and walks `IDENTIFIED → DIAGNOSING → RESOLVED → ARCHIVED` (actions `accept`/`resolve`/`archive`, plus
`reopen` from `RESOLVED|ARCHIVED` back to `DIAGNOSING`). Drive the moves through the generic
`/objects/{id}/transition` route:

```bash
# create an Incident with a 2-hour SLA (dueInMinutes; or pass an absolute dueAt in epoch millis).
# Standard/Advanced only — the object routes are the inspecto-ops module and 503 on Basic.
# title + at least one link are mandatory (create contract, 2026-07-22).
curl -s -X POST localhost:8080/api/v1/objects \
  -d '{"type":"INCIDENT","title":"reconcile mismatch on pipeX","priority":"MAJOR","assignee":"alice","dueInMinutes":120,"links":[{"to":"<alert-or-run-id>","relationship":"RELATED_TO"}]}'
# walk the lifecycle: IDENTIFIED → DIAGNOSING → RESOLVED → ARCHIVED (assign never moves status)
curl -s -X POST localhost:8080/api/v1/objects/<id>/transition -d '{"action":"accept"}'
curl -s -X POST localhost:8080/api/v1/objects/<id>/transition -d '{"action":"resolve"}'   # 422 until the postmortem has timeline + cause analysis + corrective action and dueAt (I1)
curl -s "localhost:8080/api/v1/objects?type=INCIDENT&status=DIAGNOSING"
```

*(Rewritten 2026-09-08: the block used the retired words `issue`/`ISSUE`, the retired `assign` → `start`
transitions, and `Authorization: Bearer $TOKEN` — the token plane was removed 2026-06-16; under Standard the
header is a real OIDC bearer.)*

**SLA tracking** is opt-in per Incident: set `dueAt` (epoch millis) or `dueInMinutes` at creation. A
scheduled sweep then breaches any Incident that passes its deadline while still being worked (i.e. not
yet `RESOLVED`/`ARCHIVED`); each breach stamps a `slaBreachedAt` marker on the object (so it fires once)
and emits an **`OBJECT_SLA_BREACH`** event into the event log, where it surfaces in `/events`
alongside the Incident's activity. The old `*_escalation.toon` policy is retired (`RETIRE-HALVES-1`,
2026-09-14); its replacement is authored **Escalation Rules** (`registry/escalation-rules/<id>.toon`,
`ASSURE-WORKFLOW-SLA-1`): on a breach or at an age a matching rule marks the Incident `escalated`, can raise
its priority and reassign it, and emits `OBJECT_ESCALATED`. The cadence is `-Dobjects.sla.sweep.seconds` (default `60`; set `0`
to disable):

```bash
java -cp inspecto.jar com.gamma.control.ControlApi -Dobjects.sla.sweep.seconds=30 config/
# find breached incidents via the event feed (Standard and above: optional inspecto-observability module)
curl -s "localhost:8080/api/v1/events/search?type=OBJECT_SLA_BREACH"
```

### Case Management (Phase 4) — correlation links & graph

A `CASE` (`object_type=CASE`, lifecycle `OPEN → INVESTIGATING → ESCALATED → RESOLVED → CLOSED`) groups
the Incidents of one investigation. Phase 4 makes **correlation first-class**: an append-only
`OBJECT_LINK` graph records directed edges between objects — `Case CONTAINS Incident`, `Incident
ESCALATED_FROM Alert`, `Alert CAUSED_BY Event` — so you can pivot from any object to everything related
to it. Links follow the same `-Dobjects.backend` toggle (durable in their own DuckDB file
`inspecto-ops-links.db`, or one Postgres alongside the objects).

```bash
# create a case, then link the Incident it contains
curl -s -H "Authorization: Bearer $TOKEN" -X POST localhost:8080/api/v1/objects \
  -d '{"type":"CASE","title":"Q2 reconciliation incident","severity":"HIGH"}'
curl -s -H "Authorization: Bearer $TOKEN" -X POST localhost:8080/api/v1/objects/<caseId>/links \
  -d '{"to":"<incidentId>","relationship":"contains","actor":"alice"}'
# the case's neighbourhood, and a 2-hop correlation subgraph (nodes + edges)
curl -s -H "Authorization: Bearer $TOKEN" "localhost:8080/api/v1/objects/<caseId>/links"
curl -s -H "Authorization: Bearer $TOKEN" "localhost:8080/api/v1/objects/<caseId>/graph?depth=2"
# every correlation also lands in the event feed (Standard and above: optional inspecto-observability module)
curl -s -H "Authorization: Bearer $TOKEN" "localhost:8080/api/v1/events/search?type=OBJECT_LINKED"
```

> Links are immutable facts (append-only — no edit/delete), like events.

**Evidence — comments, attachments, RCA.** An object also carries an append-only **note** thread:
free-text *comments* and *attachment* references (file/URL metadata — the bytes stay out of the lean
core). An **RCA template** seeds one comment per section, giving an investigator a structured skeleton to
fill in — supplied inline (`{sections[]}`) or authored as a `*_rca.toon` (loaded at startup from the same
config paths, listed at `GET /rca/templates`, applied by `{template:"<name>"}`). Notes follow the same
`-Dobjects.backend` toggle (their own DuckDB file `inspecto-ops-notes.db` when durable).

```bash
curl -s -H "Authorization: Bearer $TOKEN" -X POST localhost:8080/api/v1/objects/<id>/comments \
  -d '{"author":"alice","body":"reproduced on pipeX; investigating the reconciler"}'
curl -s -H "Authorization: Bearer $TOKEN" -X POST localhost:8080/api/v1/objects/<id>/attachments \
  -d '{"name":"trace.log","uri":"s3://evidence/trace.log","contentType":"text/plain","author":"alice"}'
# seed an RCA skeleton (one comment per section), then read the thread
curl -s -H "Authorization: Bearer $TOKEN" -X POST localhost:8080/api/v1/objects/<id>/rca \
  -d '{"sections":["Summary","Timeline","Root cause","Impact","Remediation"],"actor":"alice"}'
curl -s -H "Authorization: Bearer $TOKEN" "localhost:8080/api/v1/objects/<id>/comments"
curl -s -H "Authorization: Bearer $TOKEN" "localhost:8080/api/v1/objects/<id>/attachments"
```

### Observability — metrics & structured events

`GET /metrics` serves **Prometheus text format** from a zero-dependency in-process registry — no extra agent or sidecar process. ⚠ **Since EDG-01 cell 5 the exposition lives in the optional `inspecto-observability` module** (root `pom.xml` `edition-standard` / `edition-advanced` profiles, Standard+Advanced only): on **Basic the path answers `503`** naming the module, with no `# HELP` in the body (`AbsentModuleRoutes.java`). Instrumentation still runs everywhere — `MetricRegistry` stays in core; only the HTTP read-out is edition-gated. The path stays in `PUBLIC_PATHS` either way (a scraper carries no token).

```bash
curl -s localhost:8080/metrics
```

| Metric | Type | Labels | Meaning |
|---|---|---|---|
| `inspecto_batches_total` | counter | `pipeline`, `status` | terminal batches (SUCCESS + FAILED) |
| `inspecto_output_rows_total` | counter | `pipeline` | rows written by committed batches |
| `inspecto_rejected_files_total` | counter | `pipeline` | quarantined member files |
| `inspecto_partitions_written_total` | counter | `pipeline` | output partitions written |
| `inspecto_batch_duration_seconds` | histogram | `pipeline` | batch wall-clock latency |
| `inspecto_enrichment_recomputes_total` | counter | `job`, `trigger` | Stage-2 recomputes (event vs schedule) |
| `inspecto_enrichment_duration_seconds` | histogram | `job` | enrichment recompute latency |
| `inspecto_poll_cycles_total` · `inspecto_source_run_failures_total` | counter | — | poll cycles run / source-run failures |
| `inspecto_active_runs` | gauge | — | source runs currently executing |
| `inspecto_committed_batches` · `inspecto_quarantine_files` | gauge | `pipeline` | durable commit count / quarantine depth |
| `inspecto_inbox_oldest_seconds` | gauge | `pipeline` | **lag** — age of the oldest unprocessed inbox file |
| `inspecto_paused` | gauge | `pipeline` | 1 if paused |
| `inspecto_files_waiting_stability` | gauge | `pipeline` | discovered files held back by the readiness gate |
| `inspecto_duplicates_skipped_total` | counter | `pipeline` | files skipped by content-based dedup |
| `inspecto_watermark_skipped_total` | counter | `pipeline` | files skipped by the incremental high-watermark (modified before the last recorded slice) |
| `inspecto_sequence_gaps_total` | counter | `pipeline` | missing files detected in a configured sequence |
| `inspecto_files_discovered_total` · `inspecto_files_downloaded_total` | counter | `pipeline` | remote files listed / fetched+validated |
| `inspecto_downloads_failed_total` · `inspecto_post_actions_failed_total` | counter | `pipeline` | fetch/integrity failures / failed source post-actions |
| `inspecto_bytes_transferred_total` | counter | `pipeline` | bytes retrieved from source connectors |
| `inspecto_fetch_seconds` | histogram | `pipeline` | time to fetch one remote file |
| `inspecto_active_connections` | gauge | `pipeline` | open source-connector sessions |
| `inspecto_stream_lag_records` | gauge | `connection`, `topic`, `partition` | **lag** — Kafka records past the committed frontier |
| `inspecto_stream_slices_drained_total` | counter | `connection`, `topic` | Kafka partition slices drained |
| `inspecto_slice_frontiers_committed_total` | counter | `pipeline` | Kafka / DB-export slices whose frontier advanced at commit |

Eager metrics are recorded off the batch-commit event; the point-in-time gauges (lag, quarantine depth, commit count) are computed lazily when `/metrics` is scraped, so they reflect current state without a polling loop.

**Structured event log.** Alongside the human logs, the `inspecto.events` logger emits one JSON line per batch — correlatable by `batch_id`:

```json
{"event":"batch","pipeline":"adjustment_etl","batch_id":"20260530_000652_default_0001","status":"SUCCESS","rows":3,"partitions":2,"rejected":0,"duration_ms":650}
```

Route that logger to a file or shipper to stream batch events into a log pipeline.

**Acquisition lifecycle events.** When a `source:` block is configured, the `EventLog` emits structured facts
for the file-acquisition lifecycle (queryable via `GET /events`): `FILE_DISCOVERED` → `FILE_STABLE` →
`FILE_FETCHED` → `FILE_VALIDATED` → (`FILE_CHANGED`) → `FILE_ARCHIVED`, plus the failure/operational facts
`FILE_FETCH_FAILED`, `SEQUENCE_GAP`, and `SOURCE_CIRCUIT_OPEN`. A `SEQUENCE_GAP` becomes a stored Alert
(`GET /alerts`) on every edition.

### Audit files written to `dirs.status_dir`

Each ETL run produces three timestamped CSVs alongside the existing status file. All three use the same `<yyyyMMdd_HHmmss>` timestamp suffix so runs never overwrite each other.

| File | Description |
|---|---|
| `<pipeline>_status_<ts>.csv` | One row per processed file — same as before, with a `consignment_id` column appended at the end (ledgers written before the rename carry `batch_id`, read as the same column) |
| `<pipeline>_batches_<ts>.csv` | One row per batch: consignment_id, member count, total input bytes, total output bytes, status, duration |
| `<pipeline>_lineage_<ts>.csv` | Input-to-output row-count matrix — one row per (input file, output partition) pair |

### Commit log — the durable "did this batch finish" ledger

In addition to the three per-run CSVs, a single **persistent, append-only** commit log accumulates across every run of the pipeline:

```
<status_dir>/<pipeline>_commits.log
```

Each committed batch appends one line — `committed_at,consignment_id,pipeline,status,member_count,output_count,output_rows,output_bytes` — and the write is **`fsync`'d to disk** before the batch is considered done. Unlike the per-run `_batches_<ts>.csv` (which is buffered and can lose its tail if the process is killed), a line in the commit log durably means the batch's outputs, manifest, backup, and markers are all on disk. It's the file to `grep` when you need to answer "did batch X finish?" definitively:

```bash
grep ',SUCCESS,' status/<adapter>/<pipeline>_commits.log   # every committed batch
```

Disabled (not written) when `dirs.status_dir` is unset. The path is available programmatically as `cfg.dirs().commitLogPath()`, and `CommitLog.committedBatchIds()` reads back the set of SUCCESS batch ids for recovery tooling.

### Per-batch JSON manifest

After each batch completes, a manifest is written to `<status_dir>/manifests/<batch_id>.json`. It records:

- The list of member input files and their paths in `dirs.backup`
- All output file paths and sizes produced by the batch
- The marker paths written for each member
- The batch status and completion timestamp

Manifests are used by the reprocess command to reconstruct exactly what a batch did and reverse it.

### Reprocessing a batch

```bash
curl -X POST http://localhost:8080/runs/<pipeline>/reprocess -H 'Content-Type: application/json' \
     -d '{"batchId":"<batch_id>"}'
```

The route needs the `canOperateRuns` capability. `reprocess` performs the following atomically:

1. Reads the manifest for `<batch_id>`.
2. Deletes all output files and partition directories the batch created.
3. Deletes the `.processed` marker for each member.
4. Restores each member file from `dirs.backup` back into its original inbox path.
5. Supersedes the manifest with a `REPROCESSED` status entry so the audit trail is preserved.
6. On the next poll cycle the files are picked up and processed fresh.

### Backward compatibility

When a batch contains exactly one member file, the output retains the legacy `<basename>_out.<ext>` naming convention (e.g. `adj_DATE_20200403_out.parquet`). Warehouse views built against single-file outputs are therefore unaffected when `max_files` remains at its default of `1`.

### Crash semantics — batch commit ordering

`ConsignmentIngestor.commit` writes durable state in a specific order so a process crash mid-commit is idempotent on rerun:

1. **DuckLake register** (optional, non-fatal — log and continue if catalog is unreachable).
2. **Manifest write** — required for reprocess; this is what `POST /runs/{pipeline}/reprocess` reads.
3. **Backup originals** — moves files out of the inbox into `dirs.backup`.
4. **Marker files** — last. A marker means "this file is fully durable, skip on next poll."

A crash at any point before step 4 leaves the input file in the inbox without a marker, so the next poll cycle re-processes it. Output writes use `OVERWRITE_OR_IGNORE`, so re-running the same data is safe. The only way a file is "stranded" (in backup, never marked) is if backup completes and the marker write fails — `MarkerManager` logs to SLF4J in that case, and a rerun will not re-process the file (no markers) but the file is also no longer in the inbox.

### Exit codes

`CollectorProcessor.main` returns:

| Exit code | Meaning |
|---|---|
| `0` | All planned batches succeeded; no files quarantined. |
| `1` | Invalid invocation (missing `pipeline.toon` argument). |
| `2` | At least one batch threw an exception. Per-batch stack traces are in the log; aggregate `ConsignmentProcessingException` message is on stderr. |

Wrapper scripts (`run.sh`, `run.bat`, cron jobs) should treat non-zero as failure and alert.

---
