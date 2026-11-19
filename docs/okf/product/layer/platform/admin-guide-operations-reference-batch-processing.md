---
type: Reference
title: Operations reference — utilities, batching, output, deployment — Batch processing
description: Part 2 of 4 of Operations reference — utilities, batching, output, deployment: Batch Processing
tags: [operations, utilities, deployment, batching, output, onboarding]
timestamp: 2026-07-16T00:00:00Z
item: layer.platform
audience: internal
status: current
verified: 2026-11-20 last-edit
---

# Operations reference — utilities, batching, output, deployment — Batch processing

Part 2 of 4 of [Operations reference — utilities, batching, output, deployment](admin-guide-operations-reference.md) · previous: [Output structure and the status log](admin-guide-operations-reference-output-audit.md) · next: [Batch processing — the status backend onward](admin-guide-operations-reference-status-backend.md).

## Batch Processing

By default the pipeline processes one file per batch (`max_files = 1`, `max_bytes = Long.MAX_VALUE`), which is identical to the previous single-file behaviour. Consolidation activates only when you explicitly set either limit.

### Configuration

Add a `batch:` sub-section inside the `processing:` block of the pipeline toon:

```yaml
processing:
  threads: 4              # max batches processed concurrently; DEFAULT = logical cores since 2026-09-14 (see Concurrency below)
  duckdb_threads: 0       # PRAGMA threads per batch connection (0 = auto: cores ÷ threads)
  file_pattern: "glob:**/*.{csv,csv.gz}"
  batch:
    max_files: 500
    max_bytes: 268435456
```

| Key | Default | Description |
|---|---|---|
| `collector.consignment.max_files` | `1` | Maximum number of input files consolidated into one Consignment (legacy spelling `processing.batch.max_files`, dual-read, healed on save) |
| `collector.consignment.max_bytes` | `Long.MAX_VALUE` | Maximum total uncompressed size of files in one Consignment (bytes) |
| `collector.consignment.order` | `mtime` | `mtime` (arrival) or `name` — how inbox files are ordered before packing |

Files are grouped greedily in poll order; a new batch starts whenever either limit would be exceeded by the next candidate.

### Concurrency (M..N parallelism)

Within a single source run, batches execute on a **virtual-thread executor bounded by a semaphore**. Every batch is submitted up front; at most `processing.threads` of them do heavy work at once, and a batch blocked on file I/O or DuckDB parks its (virtual) carrier cheaply instead of pinning a platform thread. So `threads` is a *concurrency cap*, not a fixed pool size — raise it to overlap more I/O-bound batches without paying for idle platform threads.

Two knobs control CPU/I/O pressure, and they multiply:

| Key | Default | Controls |
|---|---|---|
| `processing.threads` | `4` | How many batches run concurrently (semaphore permits). |
| `processing.duckdb.memory_limit` | 40 % of RAM ÷ 4, ≥ 1 GiB | DuckDB `memory_limit` on each batch, Pipeline-job and enrichment scratch connection (GAP-4, 2026-09-26). Overridable per Pipeline, in `scheduler.toon` (Settings ▸ Scheduler) or by `-Dprocessing.duckdb.memory_limit`; `80%` restores DuckDB's own per-instance default. ⚠ The divisor is the Run bound's default of 4 — raise `jobs.maxConcurrentRuns` or `processing.threads` and lower this with it. |
| `processing.duckdb.async_threads` (JVM `-D` only) | `1` | DuckDB `async_threads` (its async I/O pool) on EVERY DuckDB database instance the server opens: scratch, Pipeline, job and the long-lived per-Space stores (`LA-PERF-N10-1`, 2026-11-12). DuckDB 2.0's own default is 4 × cores (48 on 12 cores) per instance, idle threads included: a demo server with 89 open instances held 5,251 DuckDB threads; with `1` it held about 1,070. Negative = DuckDB's default. |
| `processing.duckdb_threads` | `0` | `PRAGMA threads=N` on each batch's DuckDB connection. `0` = **auto** (`max(1, cores ÷ threads)`); `-1` = DuckDB's per-core default; positive `N` = exactly `N`. |

Each concurrent batch opens its own DuckDB connection, and DuckDB defaults to one thread per core — so an *unmanaged* per-core default gives an effective worker count of roughly `threads × cores`. With `threads=4` on a 16-core box that is 4 × 16 = 64 DuckDB workers fighting over 16 CPUs (≈100%-sys oversubscription stall). **Since v3.12.0 the default `duckdb_threads=0` auto-derives `max(1, cores ÷ threads)`**, so the concurrent batches divide the cores (e.g. `threads=4` on 16 cores → 4 threads each; `threads=16` on 56 cores → 3 each); a single batch keeps all cores. Set a positive value to tune manually, or `-1` to deliberately let one batch use the whole machine. The config validator warns at startup when an *explicit* `sources.max × threads × duckdb_threads` exceeds the available cores (it factors in `-Dsources.max` when set).

### Multiple sources in one process

`MultiCollectorProcessor` runs several sources (each its own `pipeline.toon`) concurrently in a single JVM — the outer layer of the M..N model, composing the per-source batch runner above.

```bash
# one or more pipeline toons and/or directories (searched for *_pipeline.toon)
java -cp inspecto.jar com.gamma.inspector.MultiCollectorProcessor \
     -Dsources.max=4 \
     spaces/default/config/subscriber/subscriber_pipeline.toon \
     spaces/ucc/config/voucher/voucher_pipeline.toon
# or point it at a directory tree of configs:
java -cp inspecto.jar com.gamma.inspector.MultiCollectorProcessor spaces/ucc/config
```

Collectors run on a virtual-thread executor bounded by `-Dsources.max` (default: all resolved collectors in parallel). Each collector is isolated — one collector failing (bad config or batch failures) is logged and counted but never aborts the others; the process exits non-zero if any collector failed. A failed collector does not stop the rest.

> ⚠ **The rest of this section describes the CLI orchestrator (`MultiCollectorProcessor`).** In
> **server mode** the fleet factor is not `-Dsources.max` but the broker's system Consignment cap —
> see [Consignment concurrency](../../module/elt/spec-consignments.md) for the four-layer model
> (per-Pipeline / per-space / per-server + priority), all of it hot-tunable from Settings ▸ Scheduler
> since 2026-08-25. The oversubscription warning reads the installed fleet cap there; the arithmetic
> becomes `cap × duckdb_threads ≈ cores`.

**Three multiplying caps.** Total worker pressure ≈ `sources.max × processing.threads × processing.duckdb_threads`. The auto-derive for `duckdb_threads=0` only divides cores among one source's `threads` — it does **not** know about `sources.max`. When running many sources in one JVM, set `duckdb_threads` explicitly (or lower `sources.max`/`threads`) so the three-way product ≈ cores — e.g. on 16 cores: `sources.max=4`, `threads=2`, `duckdb_threads=2`. When `-Dsources.max > 1` is set, the config validator surfaces this at startup: it factors `sources.max` into the explicit-oversubscription check, and for the auto default (`duckdb_threads=0`) it warns that the auto cap ignores `sources.max` and suggests a concrete value (`cores ÷ (sources.max × threads)`).

### Service mode — always-on host (`CollectorService`)

For continuous operation, `CollectorService` keeps the JVM up and runs the registry on a poll schedule instead of one-shot. It also emits a **batch-commit event** on every `SUCCESS` flush (carrying the partitions that batch wrote, from lineage) — the trigger backbone the Stage-2 enrichment and Control API build on.

```bash
# Scan paths for *_pipeline.toon (sources), *_enrich.toon (Stage-2 jobs)
# and *_job.toon (config-driven cron/event jobs, v2.8.0)
java -cp inspecto.jar com.gamma.service.CollectorService \
     -Dservice.poll.seconds=60 -Dservice.max.runs=4 config/
```

Each poll cycle reloads configs (so edits are picked up without a restart) and runs the registry via the same bounded virtual-thread executor as `MultiCollectorProcessor`. On startup it reports each pipeline's previously committed batches (from the commit log) for recovery visibility; batch atomicity + marker dedup already make an interrupted batch safe to reprocess next cycle.

### Stage-2 enrichment (`EnrichmentProcessor`)

The multiplexer (Stage 1) deliberately does no joins/aggregation. The separate **enrichment engine** turns its partitioned output into reports/KPIs, reading the Hive-partitioned tree with DuckDB, applying a configured columnar transform (reference joins + aggregation), and writing a new partitioned dataset **idempotently** (`OVERWRITE_OR_IGNORE`). Config is an `*_enrich.toon` (see `spaces/default/config/events/events_daily_kpi.toon`).

```bash
# Full recompute over all input partitions
java -cp inspecto.jar com.gamma.enrich.EnrichmentProcessor spaces/default/config/events/events_daily_kpi.toon
# Incremental — recompute only the given partitions (semicolon-separated)
java -cp inspecto.jar com.gamma.enrich.EnrichmentProcessor spaces/default/config/events/events_daily_kpi.toon \
     --partitions "event_type=CALL/year=2020/month=04/day=03"
```

When hosted by `CollectorService`, an enrichment's optional `triggers` section drives it automatically:

```
triggers:
  on_pipeline: events        # freshness: recompute the committed partitions when that
                             # Stage-1 pipeline (or an upstream enrichment, by name) commits
  schedule_seconds: 3600     # completeness: full recompute on this interval (reconciles late data)
```

Chains form naturally — set `on_pipeline` to an upstream enrichment's `name` and it fires on that enrichment's own commit. Recomputes for one job are serialised (a per-job lock), and idempotent writes make event + schedule overlap converge.

**Run-level audit & lineage.** Every recompute — event, scheduled, or CLI — is recorded under a `_audit` sibling of the output root (`<output.database>_audit/`), so it never collides with the partitioned output tree. Three append-only artifacts per job, all keyed by a correlating `run_id` (which is also the chain `ConsignmentEvent` id, linking the audit to `/metrics` and the `inspecto.events` log):

| File | Rows |
|---|---|
| `<job>_enrich_runs.csv` | one per recompute (SUCCESS **and** FAILED): trigger (`event`/`schedule`/`cli`), reason, input scope, output partition/file counts, total rows, bytes, duration, error |
| `<job>_enrich_lineage.csv` | one per written output partition file (run_id, partition, file, bytes) |
| `<job>_enrich_commits.log` | durable, fsync'd `CommitLog` of successful runs (the "did this recompute finish" ledger) |

### Control API — REST control plane (`ControlApi`)

`ControlApi` runs the service **with** an embedded REST surface (JDK `HttpServer`, no extra deps), so every CLI operation is reachable over HTTP — for operators now, UI/agent later.

```bash
java -cp inspecto.jar com.gamma.control.ControlApi \
     -Dcontrol.port=8080 \
     -Dservice.poll.seconds=60 config/
```

🔴 **Listen address — `-Dcontrol.bind` (shipped 2026-08-29, GAP-1).** Unset means **every interface**,
in every edition. `-Dcontrol.bind=127.0.0.1` restricts it to loopback; an unresolvable value **fails the
boot** rather than falling back to the wider address, so an operator who asked for loopback can never
silently get more. Both the HTTP and HTTPS paths honour it.

⚠ **Combine that default with the auth model below before exposing a Basic install.** Basic ships
no `Authenticator` at all, so left on the default it serves an unauthenticated control plane — config-write
routes included — to every host that can reach the port. The `-Dassist.write.root` gate is the only thing
in front of those routes, and an operator may well have set it. **Set `-Dcontrol.bind=127.0.0.1`, or
firewall the port, for any single-user install.**

The wildcard default is deliberate, not an oversight: narrowing it would make every already-deployed
Standard/Advanced install unreachable on upgrade. `ControlApiBindTest` pins it so a later "hardening"
fails a test instead of surprising an operator. ⛔ `EDITIONS.md` asserted "bind localhost only" for years
without the code enforcing it — the claim was the defect, and it now states the exposure instead.

🔴 **Client IP behind a proxy — `-Dcontrol.trustedProxies` (SEC review F3, 2026-09-24).** A comma-separated
list of IP literals and CIDR ranges (`10.0.0.0/8, 192.168.1.5, fd00::/8`). **Unset ⇒ `X-Forwarded-For` is
ignored** and the client IP — the audit trail's `ip`, and the per-IP rate-limit key for unauthenticated
callers — is the socket peer. When the direct peer is listed, the header is read right to left and the
first hop NOT in the list is the client; hops are parsed as literals only (never resolved). An unparseable
entry **fails the boot**, like `-Dcontrol.bind`. ⚠ Behind a reverse proxy or gateway (WSO2, nginx, an LB)
list its address, or every request audits and throttles as the proxy. ⛔ Until this flag the FIRST XFF
entry was believed from any caller, so the audit IP and the throttle key were caller-chosen.
Pinned by `TrustedProxiesTest` + `ControlApiClientIpTest`.

**Auth model (editions realignment, 2026-06-16).** The common core is **auth-free**: on the Basic
edition every route is open — no token, guard, or login (the old `CONTROL`/`assist.*` token scopes were
removed). The **Standard** edition re-adds authentication out-of-band via the `Authenticator` / `Subject` /
`TokenRelay` SPIs (`com.gamma.control`), implemented by the `inspecto-oidc` module (OIDC resource
server, HTTPS, BFF `/auth/*` routes) — see [`spec-editions.md`](spec-editions.md). Separate from auth and always on:
**write routes are fail-closed** behind the `-Dassist.write.root` gate (`503` when unset).

**Versioned contract.** Every business route below is served **only** under the versioned **`/api/v1`**
prefix (since 2026-07-25) with a response envelope
(`{data, metadata, links, permissions, diagnostics}`), an error-code catalog
(`{error:{errorCode, message, recoverable, correlationId, details?}}`), ETag/`If-Match` concurrency on
components, `GET /bootstrap`, `POST /queries/{id}/run`, and **async** job/pipeline triggers (`202` +
`runId` + poll, `Idempotency-Key` replay). A bare unversioned business path is no longer served, and
`/api/<anything-not-v1>` returns a JSON `404`. Only the four infra probes stay unversioned:
`GET /health`, `GET /ready`, `GET /metrics`, `GET /metrics/acquisition`. The
authoritative surface is [`ADVANCED_GUIDE.md`](../../../../ADVANCED_GUIDE.md) §Control API and the OpenAPI contract
[`api/openapi-v1.json`](../../../../api/openapi-v1.json). With multi-space enabled, the space segment sits
**after** the version: `/api/v1/spaces/{id}/…`.

Paths in the table below are written **relative to `/api/v1`** — e.g. `GET /pipelines` is requested as
`GET /api/v1/pipelines`. The two probe rows (`/health`, `/ready`) are the exception: they are absolute
and unversioned.

| Method & path | Purpose |
|---|---|
| `GET /health`, `GET /ready` | liveness / readiness (open); `/ready` stays 200 when the shared run lease's database is unreachable, by decision (operator 2026-10-03) — `okf/product/layer/platform/admin-guide-launch-flags.md` |
| `GET /health/details` | per-subsystem UP/DOWN/NOT_CONFIGURED (MNT-15) — **auth-gated**, not a public path (row added 2026-09-08); includes `live.runLease.<scope>` (down since + last error) when a shared run lease is configured (2026-10-03) |
| `GET /audit/search`, `GET /audit/export?format=csv` | the who-did-what trail, core in every edition since EDG-01 cell 6 (row added 2026-09-08; `okf/product/module/observability/plan-observability.md` §3.3) |
| `GET /pipelines` | list pipelines (summaries) |
| `GET /runs` | list running pipelines + paused state + commit count |
| `POST /runs` | body `{"configPath":"…"}` — register a new pipeline live from a config under `-Dassist.write.root`, no restart (v4.1) |
| `POST /runs/{runId}/trigger` | run one pipeline once |
| `POST /runs/{runId}/pause` · `/resume` | pause (poll cycle skips it) / resume |
| `GET /runs/{runId}/commits` | committed batch ids |
| `GET /runs/{runId}/batches` · `/files` · `/lineage[?batchId=]` | audit queries (via `StatusStore`) |
| `GET /runs/{runId}/quarantine` | quarantined inputs + reason |
| `GET /runs/{runId}/pending` | inbox scan — files awaiting processing (`pending`), an under-processing flag (`running`), and live per-file progress (`current`: batch id, file, index/total, started-at; `null` when not mid-file); read-only, no audit side effects (v4.1) |
| `POST /runs/{runId}/reprocess` | body `{"batchId":"…"}` — replay a batch |
| `POST /trigger` | run all pipelines once |
| `POST /validate` | body `{"configPath":"…"}` (saved file) or `{"type":…,"config":{…}}` (unsaved draft — the full save gate, `SaveGate`) — structured findings |
| `GET /status` | live status snapshot — all pipelines + rollup (v2.8.0) |
| `GET /report[?from=&to=]` | service-wide batch-audit report; optional date range (v2.8.0; range v2.10.0) |
| `GET /runs/{runId}/report[?from=&to=]` | batch-audit report for one pipeline; optional date range (v2.8.0; range v2.10.0) |
| `GET /jobs` | list config-driven jobs + last outcome + next fire (v2.8.0) |
| `GET /jobs/{name}/runs` | recent run history for a job (v2.8.0) |
| `POST /jobs/{name}/trigger` | run a job once now (v2.8.0) |
| `GET /enrichment` | list Stage-2 enrichment jobs + trigger config + last run (v2.9.0) |
| `GET /enrichment/{job}/runs` | enrichment run-audit rows (v2.9.0) |
| `GET /enrichment/{job}/lineage[?runId=]` | enrichment output lineage rows (v2.9.0) |
| `GET /enrichment/{job}/report[?from=&to=]` | run-audit rollup for one enrichment job; optional date range (v2.9.0; range v2.10.0) |
| `GET /catalog` · `/catalog/tables/{id}` · `/catalog/kpis` · `/catalog/graph` | metadata graph (`assist.read` scope; v3.2.0) |
| `GET /config/spec/{type}` | declarative config spec for UI form rendering (`assist.read`; v3.2.0) |
| `GET /assist/diagnoses` | recent event-driven failure diagnoses (`assist.read`; v3.7.0) |
| `POST /assist/{intent}` | run an assist skill; `503` when the agent module is absent (`assist.read`; v3.3.0) |
| `POST /config/write` | body `{type, config, subdir?, overwrite?}` — persist a validated draft as `.toon` under `-Dassist.write.root` (`assist.write` scope; v4.1) |

```bash
curl -s localhost:8080/api/v1/pipelines
curl -s -X POST localhost:8080/api/v1/runs/adjustment_etl/trigger   # 202 + {runId}
```

**Authoring → save → register (v4.1).** With `-Dassist.write.root=<dir>` set, a validated config
draft can be persisted (`POST /config/write`) and then registered as a live
pipeline (`POST /pipelines`) without a restart — the running service picks it up
on the next poll cycle. Both routes are fail-closed: unset write root ⇒ `503`; paths are jailed
under the root; drafts with ERROR-level findings (spec or hard-fail safety validator) ⇒ `422`;
an existing file ⇒ `409` unless `overwrite:true`. Registration is in-memory — keep the write root
inside the config tree the service was launched with so the pipeline also survives a restart.

**Serving the operator web console (Inspector).** The same `ControlApi` host can serve a built
Angular SPA as static files, so one process hosts both the API and the UI (v4.1):

- `-Dui.dir=<path>` — serve a built SPA bundle (the folder containing `index.html`). Unknown
  **GET** paths with no file extension fall back to `index.html` (SPA deep links — this is why a bare
  `GET /objects` still serves the shell), while anything under `/api/…` never falls back: an unmatched
  or non-`v1` API path returns JSON `404`. Routes always win over the static fallback. Static assets
  are served without restriction so the shell loads before the operator connects. A path-traversal guard
  confines reads under the root.
- `-Dcontrol.cors=<origin>` (e.g. `http://localhost:4204`, or `*`) — enable CORS headers + `OPTIONS`
  preflight, for a separately-hosted dev SPA. Omit for prod (same-origin needs no CORS).

Both flags are **off by default**: unset, the control plane behaves exactly as a headless API. The
deploy bundle's `bin/serve.sh` / `bin\serve.bat` wire these up automatically (`-Dui.dir=./ui` when a `ui/`
folder is present; `CORS_ORIGIN` env → `-Dcontrol.cors`). See the
[User Guide](../../../../USER_GUIDE.md) for the full UI walkthrough.

```bash
# bundle root — serve API + UI on :8080
bash bin/serve.sh    # Linux/Mac
bin\serve.bat        # Windows
# then open http://localhost:8080/
```

### Reports — status snapshot & batch-audit rollup (`ReportService`)

The audit queries above return raw rows; the **report** endpoints return the aggregated
view operators and dashboards actually want — computed on demand through the same
`StatusStore`, so they work identically over the file or DB backend.

- `GET /status` — a live snapshot: per-pipeline paused state, committed-batch count,
  quarantined-file count, and last-batch id/status/time, plus a service rollup.
- `GET /report` (service-wide) and `GET /runs/{runId}/report` (one pipeline) — a
  historical batch-audit rollup: total/success/failed batch counts, **error rate**, input
  & output rows, rejected files, output file count and bytes, average / max duration, and
  duration **percentiles** (`p50`/`p95`/`p99`) so a tail-latency spike isn't hidden by the mean.

**Date ranges (v2.10.0).** The `/report`, `/runs/{runId}/report` and
`/enrichment/{job}/report` endpoints accept an optional inclusive `?from=&to=` window — a
date (`2026-05-01`) or datetime (`2026-05-01 09:00:00`). A date-only `to` covers the whole
day. The rollup (counts, percentiles, first/last time) is computed over just the rows whose
`start_time` falls in the range; the applied bounds are echoed back as `windowFrom`/`windowTo`
(blank = unbounded). Filtering is a lexicographic compare on the audit timestamp, so it works
identically over the file and DB backends.

> **Where `$TOKEN` comes from, once:** an access token issued by your IdP. `Authorization` is enforced by
> the **Standard/Advanced security module** (`Authenticators.active()`, a `ServiceLoader` slot) — configured through `AUTH_OIDC_*` — so on
> **Basic** there is nothing to send and the header is simply ignored. ⛔ It is *not* produced by
> `CONTROL_TOKEN` or `-Dcontrol.token`: those have no readers and never authenticated anything. Every curl
> below uses the same `$TOKEN`.

```bash
curl -s -H "Authorization: Bearer $TOKEN" localhost:8080/api/v1/status
curl -s -H "Authorization: Bearer $TOKEN" localhost:8080/api/v1/runs/adjustment_etl/report
# just last month, with p50/p95/p99 over that window:
curl -s -H "Authorization: Bearer $TOKEN" \
  "localhost:8080/api/v1/runs/adjustment_etl/report?from=2026-04-01&to=2026-04-30"
```

### Enrichment run audit over the API (`EnrichmentAuditReader`)

Stage-2 recomputes persist a durable run-level ledger (`<output>_audit/<job>_enrich_runs.csv`
+ `_enrich_lineage.csv`, written by `EnrichmentAuditWriter`). From v2.9.0 that ledger is
readable over the Control API — the Stage-2 counterpart to the `/runs/{runId}/batches`
+ `/lineage` + `/report` surface — so you can answer "did the nightly KPI run, and what did
it write?" without shelling onto the box.

- `GET /enrichment` — list each hosted enrichment job with its trigger config
  (`onPipeline`, `scheduleSeconds`, `eventTriggered`, `scheduled`) and last-run summary
  (`runCount`, `lastStatus`, `lastRunTime`).
- `GET /enrichment/{job}/runs` — the raw run-audit rows (one per recompute, SUCCESS and
  FAILED): trigger, reason, scope, input/output partition & file counts, rows, bytes,
  duration, error.
- `GET /enrichment/{job}/lineage[?runId=]` — one row per written output partition file;
  `?runId=` narrows to a single recompute.
- `GET /enrichment/{job}/report` — the aggregated rollup (mirrors the Stage-1 batch report):
  total/success/failed run counts, **error rate**, output rows/files/bytes, avg/max duration,
  first/last run time.

All four require auth and return `404` when no enrichment is registered, or the named job
is unknown.

```bash
curl -s -H "Authorization: Bearer $TOKEN" localhost:8080/api/v1/enrichment
curl -s -H "Authorization: Bearer $TOKEN" localhost:8080/api/v1/enrichment/EVENTS_DAILY_KPI/report
```

### Config-driven jobs — cron / event / manual (`JobService`)

Beyond the fixed poll cycle, define arbitrary **jobs** in `*_job.toon` files (scanned from
the same paths as pipelines). One uniform scheduler runs every Job Type — the built-in `enrich`
(a Stage-2 job), `report` (emit a report snapshot to the `inspecto.events` log), `maintenance`
(built-in housekeeping) and `pipeline` (an authored Pipeline over data at rest), plus the types modules
register through `JobTypeProvider` — each triggered by a cron expression, an upstream batch-commit
event, and/or a manual `POST`. There is no `ingest` Job: ingestion belongs to the Pipeline's poll loop.

```toon
# nightly_clean_job.toon  — prune audit CSVs older than 30 days at 02:00 daily
job:
  name: nightly-clean
  type: maintenance
  cron: "0 2 * * *"          # 5 fields (min hour dom mon dow) or 6 (with leading seconds)
  task: cleanup
  dir: /var/lib/inspecto/test-status
  retention_days: 30
  glob: "*.csv"
```

```toon
# kpi_refresh_job.toon  — recompute a KPI hourly AND whenever EVENTS commits a batch
job:
  name: kpi-refresh
  type: enrich
  cron: "0 0 * * * *"        # top of every hour
  on_pipeline: EVENTS         # also fire on the upstream's batch-commit event
  config: spaces/default/config/events/events_daily_kpi_enrich.toon
```

The cron parser is a small, dependency-free quartz-like engine: `*`, ranges (`9-17`),
steps (`*/15`, `9-17/2`), lists (`MON,WED,FRI`) and month/day names. When both
day-of-month and day-of-week are restricted, a time matches if **either** does (Vixie-cron
semantics). Every run is recorded to `jobs_audit/jobs_runs.csv` (override with
`-Djobs.audit.dir`) and a short in-memory history the API serves.

```bash
curl -s -H "Authorization: Bearer $TOKEN" localhost:8080/api/v1/jobs
# trigger is async: 202 + {runId}; poll the run by id
curl -s -X POST -H "Authorization: Bearer $TOKEN" localhost:8080/api/v1/jobs/nightly-clean/trigger
curl -s -H "Authorization: Bearer $TOKEN" localhost:8080/api/v1/jobs/nightly-clean/runs
```

### Retention & purging — what each task forgets (operator policy)

Retention is **never on by default**. Every task below is inert until you define a job for it, and
each one that deletes requires an explicit `retention_days` — there is no default horizon, because
forgetting is a policy decision and a wrong default is silent data loss.

| Task | Deletes | Notes |
|---|---|---|
| `cleanup` | files under `dir` breaching age / count / size | the only task with a `retention_days` default (7) |
| `ledger_prune` | acquisition-ledger fingerprints | ⚠ a pruned file still at the source **re-ingests as new** — retention must exceed the source's own file lifetime |
| `runlog_prune` | Run history JSONL + artifacts (+ the `inspecto_job_runs` projection) | |
| `status_prune` | the run-timestamped status CSVs in every `<dataDir>/<pipeline>/status/` | ⛔ never the persistent `<pipeline>_commits.log` or `manifests/` — one job covers every Pipeline in the Space |
| `notification_prune` | in-app notifications, whatever their read/archived state | |
| `partition_prune` | a pipeline sink store's `year=/month=/day=` partitions older than the window — `dir` is the store's `database/` directory (path-jailed), whole day directories only, empty parents removed | per-date retention for stream data (2026-09-06). Files go, catalog rows stay — the same rule as `retire_superseded`; `ConsignmentSelector` re-enumerates the live tree. A store partitioned by other columns (`partitions[]`) has no date tree and prunes nothing, by design |
| `event_prune` | the durable event store's `level=/year=/month=/day=` Parquet partitions older than the window | COMPLY-3 — the audit-retention window is **one year** (operator, 2026-08-30): schedule it with `retention_days: 365`, or the window stays stated policy the code does not apply. In-memory backend ⇒ nothing to prune |
| `alert_purge` | `RESOLVED` Alert rows closed more than `retention_days` ago (default **90**; blank/0/negative refused) | base task, every edition; **never** an `OPEN`/`ACKNOWLEDGED` Alert (de-duplication state); nothing ships scheduled — see `incidents.md` §3.7 |
| `receipt_prune` | delivery receipts (D8) | the in-memory store's oldest-first cap at 5000 is a **backstop, not retention** |
| `incident_purge` | **Archived Incidents and their notes, attachments, links and tag edges** | the only task that deletes operator business records — see below |
| `retire_superseded` | the **bytes** of output files the Consignment catalog marks unreadable | ⚠ **the only retention task a correctness fix depends on** — see below |

#### `retire_superseded` — the disk half of a safe recompute (addressing step 6)

A full pipeline recompute used to rewrite its sink files in place. That was atomic per file, so a reader
saw either the old bytes or the new — but it also meant a recompute could replace bytes underneath an
in-flight read. Since 2026-08-10 a recompute writes a **new revision** (its base name carries the batch id)
and marks the earlier revision `SUPERSEDED` in `consignment_outputs`; readers resolve through
`ConsignmentSelector`, which stops naming those files.

Nothing is deleted at that moment. **So without a `retire_superseded` job defined, every full recompute
leaves a complete extra copy of its output on disk, forever.** That is the one case where "retention is
never on by default" has a real cost: define this job, or a recompute-heavy pipeline will grow without
bound. Copy the disabled example `spaces/demo/config/jobs/retire_superseded_job.toon` (weekly, 7 days) and
set `enabled: true`.

⚠ **`retention_days` counts from the file's last write, not from when it was superseded** (the catalog
keeps no supersede time). A revision written a month ago and superseded today goes at the next pass. Set
it to how long you want to keep a superseded revision *after it was written*. An already-open read is not
protected by the window but by the store lock: the delete waits for every reader still holding the store.

⛔ **It stays off by default (decided 2026-11-17, MoSCoW C7).** Arming it in every Space would make it the
only deleting task on by default, and on its first pass it would delete every superseded revision an
install has kept. 3.x installs are not affected by that: 3.x overwrote in place and kept no revisions, so
the disk growth starts at the 4.0.0 upgrade (release note in
[plan-api-stability](../../module/api/plan-api-stability.md)).

⚠ **The cost stopped being silent 2026-08-29.** `PipelineJobRunner.supersedeEarlierRevisions` now asks —
only after a recompute has actually superseded at least one earlier revision, never unconditionally —
whether an enabled `retire_superseded` job exists (`JobService.retireSupersededConfigured`, read fresh off
`configSnapshot()` on every recompute, not cached at build time). If none does, it logs a WARN naming the
affected store(s). The check is wired through a `BooleanSupplier` the runner's constructor accepts;
existing callers that pass none are read as "unknown" and stay silent, matching the pre-fix behaviour.

It deletes files and **keeps their catalog rows**. A row for a deleted file is harmless; dropping the row
of a file still on disk would let it back into every reader's glob. A file locked by an open reader is
skipped with a warning and retried on the next pass.

#### `incident_purge` — the retention tier for Archived Incidents (MNT-14)

An Incident becomes purge-eligible once `closedAt + retention_days` has passed, where `closedAt` is
the timestamp the archive transition already stamps. Retention is therefore **derived, not stamped
per record** — which means shortening `retention_days` later retroactively makes older records
eligible. The sweep does not honour "what was promised when this was archived".

```toon
# incident_purge_job.toon — purge Incidents archived over 2 years ago, 03:00 on Sundays
job:
  name: incident-retention
  type: maintenance
  cron: "0 3 * * SUN"
  task: incident_purge
  retention_days: 730        # required — no default
  max_count: 1000            # optional, bounds one run's blast radius (default 1000)
```

**Run it dry first.** A dry run reports the eligible count and, separately, how many were withheld
by a legal hold — `would purge 12 incident(s) archived more than 730d ago (3 held)`. The two counts
are always reported apart so the arithmetic adds up; a held record is never a silent skip.

**Legal hold.** Set the `legalHold` attribute on an Incident (`PATCH /objects/{id}`) and it is never
purged, however long its window has expired. The check is **fail-safe**: only an explicit
`false`/`0`/`no`/`off` clears the hold, any other non-blank value holds — a typo must keep records,
not delete them. It is re-checked at purge time against the live row, so a hold applied between the
preview and the run still wins.

**What a hold protects (LEGAL-HOLD-SCOPE-1, 2026-11-17).** A hold covers the Incident's **evidence**, not
only its row. An object is held when it carries `legalHold` itself or is one `CONTAINS` hop from one that
does: an Incident in a held Case, or a Case that contains a held Incident. A Case shared with another,
unheld Incident is still held (the safe default); the unheld Incident itself is not. Every path below
refuses while held — the route answers **409** naming the hold, and each refusal is an `AUDIT` event with
action `legal_hold.refused` — and works again the moment the hold is released (`legalHold: false`):

| Evidence | Path that would remove it | Under hold |
|---|---|---|
| Incident row, notes, attachments, links, tag edges | `incident_purge` (cascade) | skipped and counted `(N held)` |
| Alerts escalated into the Incident | `alert_purge` | kept, counted `N held by a legal hold on their Incident` |
| the Incident's audit history | `event_prune` (whole UTC days) | every day from the held evidence's first day (creation, or `occurredAt` if earlier) is kept |
| Case membership and other links | `DELETE /objects/{id}/links` | 409 when either end is held |
| a held Case's members | `POST /objects/{id}/merge` (held source) · `POST /objects/{id}/split` (held Case) | 409 |
| Investigations linked to a held Case (log, Steps, Drafts) | `DELETE /inv/investigations/{id}` · `DELETE …/case` · `PUT …/case` to another Case | 409, closed Case included |
| everything in the Space | `DELETE /spaces/{id}?purge=true` | 409 (deregister without `purge` still allowed) |

Not covered: `partition_prune` drops a Pipeline's data partitions by date and has no link from an
Incident to the rows that raised it, so held source data is **not** protected — keep that window longer
than any hold. Drafts are exploration, not evidence (D20), and expire as before unless their
Investigation is deleted, which the hold refuses. Snapshots and Case notes have no delete path.

> ⚠ **A purge is not "all trace removed" — say this before a legal or DPA reviewer asks it.**
> The event ledger is append-only. Each purged Incident's `OBJECT_ACTIVITY` history — including the
> record of its own purge — **survives the purge**. What retention manages is the operational record;
> the audit log is deliberately not subject to it. This is a stated decision (MNT-14 G3), not a gap.
> If a jurisdiction requires the trail to go too, that is a separate build and it does not exist today.

Nothing schedules `incident_purge` for you. Standing up the job is an operator act, exactly like
`receipt_prune` — shipping a default that deletes business records would be indefensible.
