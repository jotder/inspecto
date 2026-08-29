---
type: Concept
title: DuckDB Integration
description: Appender-based bulk ingest (~75× vs JDBC), thread auto-derivation, and reserved-word quoting.
resource: inspecto-engine/src/main/java/com/gamma/inspector/DuckDbRecordSink.java
tags: [engine, duckdb, performance, appender, threads]
timestamp: 2026-06-28T00:00:00Z
---

# DuckDB Integration

The engine embeds DuckDB natively (requires the `--enable-native-access=ALL-UNNAMED` JVM flag — see
[build & run](../build-run/build-test.md)).

* **Appender, not JDBC batch.** `DuckDbRecordSink` and `TypedRecordIngester` bulk-load via the DuckDB
  `DuckDBAppender` API (heap buffer `APPEND_BATCH = 10,000` rows). Benchmarked on 1M rows: JDBC
  `PreparedStatement.executeBatch` ≈ 6.9K rows/s vs the Appender ≈ 520–530K rows/s — **~75× faster**, at
  parity with the native CSV reader.
* **Thread auto-derivation.** `DuckDbUtil.effectiveWorkerThreads` (`inspecto-util/src/main/java/com/gamma/util/DuckDbUtil.java`)
  derives per-batch `duckdb_threads`: `0` (default) with batch concurrency > 1 → `max(1, cores/concurrency)`;
  explicit `N` honored verbatim; `-1` → DuckDB's per-core default. Avoids the threads×cores oversubscription
  stall; `ConfigValidator` warns when explicit `threads × duckdb_threads` exceeds the core count.
* **Memory / spill caps (opt-in; one knob for every scratch connection).** `DuckDbUtil.applyDuckDbSettings`
  sets `memory_limit` / `temp_directory` (spill) / `max_temp_directory_size` when a value is configured;
  unset ⇒ DuckDB's own default (≈ 80% RAM **per instance** — the aggregate-overcommit hazard under
  concurrency). The batch-ingest path caps its connections via `BatchIngestStrategy.configure`
  (per-pipeline `processing.duckdb.*`). The **flow-job** (`PipelineJobRunner`) and **enrichment**
  (`EnrichmentEngine`) run scratch connections have no per-config `processing.duckdb` section, so they call
  `DuckDbUtil.applyGlobalDuckDbSettings`, which reads the global JVM fallbacks
  `-Dprocessing.duckdb.memory_limit` / `.temp_directory` / `.max_temp_directory_size` / `.threads`; the
  batch path honors the same globals as a fallback (`DuckDbUtil.globalOr`), so a single
  `-Dprocessing.duckdb.memory_limit` caps every DuckDB scratch connection uniformly. **All opt-in** — with
  no config or `-D` value set nothing is issued and behavior is unchanged. Set these on high-concurrency /
  multi-tenant boxes to prevent overcommit, and pair with `temp_directory` so an over-limit query spills to
  disk instead of OOM-ing. (Preview / dry-run connections — `ComponentPreview`, `PipelineDryRun`, enrichment
  `preview` — run over bounded samples and are deliberately left uncapped.)
* **D11 SHIPPED 2026-08-26 — the pair is `memory_limit=2GB` + `maxConcurrentRuns=4`, both on by default and
  both owned by the server configuration.** They are surfaced in the UI at **Settings ▸ Scheduler ▸ Resource
  caps**, persisted in `scheduler.toon`, and served with provenance (`file` | `property` | `default`) by
  `GET/PUT /system/scheduler`. Ownership moved deliberately: `DuckDbUtil.memoryLimit(configured)` is now the
  single use-time resolver — per-pipeline value > installed server value > `-Dprocessing.duckdb.memory_limit`
  (a bootstrap default only) > DuckDB's own default — because ⛔ a key served by the settings tier must not
  also be read from `-D` at use time (`SchedulerRoutes`, the 2026-08-15 operational-db decision).
  `JobService.setMaxConcurrentRuns` is the matching seam. ⚠ Preview / dry-run connections remain
  **uncapped** and must stay so: only `EnrichmentEngine`, `PipelineJobRunner` (via
  `applyGlobalDuckDbSettings`) and `BatchIngestStrategy` (per-config path) resolve a limit.
  `max_temp_directory_size` still gets **no** default — none is defensible without the volume size.
  **Same-day review hardening + follow-ups, all shipped 2026-08-26:** the Run bound counts in-flight Runs
  even while unbounded (🔴 a `Semaphore` subclass that re-derived "did this Run take a permit?" from
  `cap()` at release ended at **7 permits under a cap of 4, permanently**), the installed bound is
  process-global so a space created after a PUT starts on it, and clearing it REVERTS rather than freezes.
  The **memory-limit grammar is served, not mirrored**: `SchedulerRoutes.MEMORY_LIMIT_PATTERN` is published
  as `duckdbMemoryLimitPattern` and the UI holds no regex of its own (it had two hand-maintained copies —
  identical when measured, unpinned). ⚠ One string can only serve both sides if it is **portable**: no
  inline `(?i)` (the browser compiles it with `new RegExp(p, 'i')`, and JS has no such construct) and
  **explicitly anchored** (`String.matches` anchors implicitly, `RegExp.test` does not — unanchored, the
  form accepts `2GB of RAM`). **DuckDB's proportional form is accepted** (`80%`), bounded `1..100`
  *inside* the pattern, because a bound that does not travel with the served grammar lets the form accept
  a `500%` the PUT refuses. The value is a pass-through to `SET memory_limit='…'` — nothing parses it into
  bytes, so a non-byte value breaks no arithmetic. 🔴 The offline mock had **no resource-pair gate at all**
  (it accepted `"lots"` where the server 422s and served neither value); it now mirrors both gates.
* **Defaults DECIDED 2026-07-25 (BACKLOG D11 + D12).** D12 shipped that day; **D11 was declined that day**
  and stayed unimplemented until 2026-08-26 (history below).
  * **D12 — chunking is ON by default** (SHIPPED): `processing.chunking.max_file_bytes` now defaults to
    **8 GiB** (`8589934592`) instead of `0`. The threshold exists for *pathological* single files, so it was
    set far above any routine input — normal workloads never change shape, and only a genuinely outsized file
    chunks. Setting the key to `0` still disables chunking.
  * **D11 — NOT implemented.** There is still **no default `processing.duckdb.memory_limit`**: an uncapped run
    gets DuckDB's own ≈80%-of-RAM-*per-instance* default, and concurrent runs can still overcommit the box.
    The decision, if it is ever revisited, was for a conservative fixed per-instance cap + spill, **not** a cap
    computed from the concurrency semaphores — a computed cap (RAM ÷ `jobs.maxConcurrentRuns`) was rejected
    because `maxConcurrentRuns` defaults to `0` (unbounded), so the divisor is routinely unknown, and the
    batch-ingest path has its own semaphore; two independent limiters mean any "compute it" formula is wrong in
    exactly the overcommit case it was meant to prevent. Until then, set `-Dprocessing.duckdb.memory_limit`
    explicitly on high-concurrency boxes. `processing.duckdb.max_temp_directory_size` likewise has no default
    (DuckDB uses ≈90% of the disk), though spill already lands on the data volume via the batch scratch dir
    (`BatchIngestStrategy.scratchDir` → `dirs.temp`). Read-path connection reuse is explicitly **not** the
    lever here (see BACKLOG §6 C6).
  * **D11 — MEASURED 2026-07-27. The number is `2GB`, and two long-standing beliefs here are wrong.**
    Measured on a 32 GiB host, DuckDB 1.5.2.1, over a CDR-shaped 12-column CSV. What the numbers say:
    - ⚠ **Peak memory does NOT scale with input size on the ingest path.** `read_csv_auto` →
      `COPY … TO parquet` streams: a **1.0 GiB** input peaked at **1081 MiB**, a **3.1 GiB** input at
      **981 MiB** — flat. So a giant file is *not* what exhausts memory, and **chunking (D12) was never
      really the memory bound** it is described as above; it bounds the unit of work and scratch, which is
      still worth having, but it is not standing in for D11.
    - ⚠ **What a cap actually governs is the blocking operators, and they hard-fail instead of spilling.**
      A 9M-group `GROUP BY` peaked at 937 MiB, a wide `DISTINCT` at 895 MiB, and **at `512MB` both died
      with `Out of Memory Error`** having spilled only ~192 MiB. Graceful degradation is not the failure
      mode. **This is the trap: an aggressive cap turns working jobs into failing ones.** (`ORDER BY`
      ~200 MiB and a self-`JOIN` ~385 MiB are cheap and never the constraint.)
    - **`2GB` is the defensible value**: ~2.2× the highest peak observed anywhere (1081 MiB), clear of the
      OOM cliff, and free — capped runs measured at or slightly *faster* than uncapped (ingest 3741 ms
      @ `2GB` vs 4928 ms uncapped; `GROUP BY` 1711 vs 2123 ms). `1GB` passed everything too but sits only
      ~1.3× over peak, uncomfortably near the cliff.
    - ⚠ **A `memory_limit` default alone does not close D11.** Total exposure = `memory_limit` ×
      concurrent runs, and `-Djobs.maxConcurrentRuns` still defaults to `0` = unbounded — so at enough
      concurrency *any* fixed per-instance cap overcommits. The pair is the fix: `memory_limit=2GB` +
      `maxConcurrentRuns=4` ⇒ ≤8 GiB worst case (~25% of a 32 GiB box) vs ~25 GiB *per run* today. Note
      this does **not** resurrect the rejected *computed* cap (RAM ÷ semaphore) — it is two independent
      fixed knobs, which is exactly what the D11 decision asked for.
    - **Method, to reproduce:** open a plain JDBC DuckDB connection, `SET temp_directory` (as
      `DuckDbUtil.applyDuckDbSettings` does) and optionally `SET memory_limit`; poll
      `SELECT sum(memory_usage_bytes), sum(temporary_storage_bytes) FROM duckdb_memory()` on a duplicated
      connection every 15 ms while the statement runs, and take the max. Sweep the cap over
      `{default, 8GB, 4GB, 2GB, 1GB, 512MB, 256MB, 128MB}` for the ingest shape and
      `{default, 4GB, 2GB, 1GB, 512MB}` for the blocking-operator shapes.
    - **Not measured, so not claimed:** scaling with thread/core count (DuckDB sizes per-thread buffers, so
      a much larger box may need more than 2 GiB), non-CSV frontends, the `materialize` task's real query
      shapes, and any RAM-relative or per-edition default.
    - **Still an operator call** — the measurement removes the blocker (BACKLOG §6); it does not ship a
      default.
* **Reserved-word quoting.** `day` is a DuckDB keyword — alias it (`run_day`) in SQL; quote `"trigger"` too.
  Watch this whenever generating SQL with date/trigger columns. See [gotchas](../gotchas/cross-cutting.md).
* ⚠ **The session `TimeZone` is the HOST zone, not UTC** — probed 2026-08-15 against the bundled
  `org.duckdb:duckdb_jdbc:1.5.2.1` on a plain `jdbc:duckdb:` connection:
  `current_setting('TimeZone')` returned the host zone (`Asia/Calcutta` on this box) and
  `duckdb_extensions()` reported **icu installed: true, loaded: true**. The widespread "DuckDB defaults to
  UTC" belief holds only for an **ICU-less** build, and this is not one. Consequently a SQL-side
  `now()::TIMESTAMP` writer and a Java-side `LocalDateTime.ofInstant(…, ZoneId.systemDefault())` reader are
  **the same wall clock** and agree — e.g. `BatchIngestStrategy:215`'s `__valid_from` and
  `ReferenceCompactor:142`'s retention cutoff are a genuine matched pair, *not* the off-by-the-UTC-offset
  bug they resemble. ⛔ "Fixing" that reader to UTC would **create** the skew and drop rows outside `keep`.
  ⚠ Note the asymmetry: DuckDB follows `systemDefault()` but is **blind to `-Dops.timezone`** — nothing in
  the repo issues `SET TimeZone` and `DuckDbUtil` has no setter — so moving such a pair onto
  [`OperationsZone`](../control-plane/jobs.md) means changing the connection's zone as a **third** moving
  part, not just the two Java halves.

## The source time zone for temporal data

**SHIPPED 2026-08-29** (engine + config `44ecef76`, surfaces `dd02d377`). Plan + the full live-probe
transcript: [`archived-documents/plans-archive/source-timezone-plan.md`](../../../archived-documents/plans-archive/source-timezone-plan.md).

A parsed wall-clock timestamp used to mean whatever the *host* running the batch thought it meant.
Temporal columns now carry a declared **source zone** and are normalised to **naive UTC** at parse
time, so a value stops depending on which box processed it.

* **The policy is three tiers, precedence `raw.fields[].timezone_column` > `raw.fields[].timezone` >
  `parsing.source_timezone` > none.** `SourceZones` (`inspecto-etl`) is the one home for the concept —
  `validate` / `resolve` / `wrap`. `none` is the default and is **byte-identical to the old behaviour**,
  pinned by a test.
* **The compile shape is `timezone('UTC', timezone(Z, <naive-parse>))`** — measured, not reasoned. It
  yields a naive-UTC `TIMESTAMP`, which is what keeps everything downstream (partitions, BI grains,
  dedup) naive and consistent.
* **Four temporal sites, not the "one choke point" the board row assumed.** Applied at
  `SchemaFieldTypes.castSql` (the mapped column), `TransformCompiler.dateExpr` (`DATE_*` partitions and
  `__event_time`) and `TransformCompiler.concatDt`. ⛔ **`FILENAME_DATE` and `DATE` are zone-exempt by
  design** — a date has no instant to shift, and shifting one moves a file dated `20260829` into the
  previous day for any negative offset. Documented at the method, not silently skipped. The `EXPR` rule
  is out of scope by the same rule that leaves it un-sandboxed.
* 🔴 **The `TIMESTAMPTZ` host-zone trap is CLOSED by refusal, not by a default.** A naive value cast
  `::TIMESTAMPTZ` is interpreted in the **session** zone (i.e. the host — see the bullet above), so a
  `TIMESTAMPTZ` field with **no** zone source is now refused at config load
  (`PipelineConfigParser.requireZoneForTimestampTz`, called at all three schema-resolution points:
  single, multi, segment). No shipped config declared one, so nothing broke.
* 🔴 **`TRY()` does NOT catch DuckDB's *Unknown TimeZone*.** *Not implemented* errors are outside what
  `TRY` intercepts, so one bad cell in a `timezone_column` would kill the whole batch with no soft
  failure available. A per-row zone therefore resolves through a **`pg_timezone_names()` lookup**, which
  yields NULL for an unknown or NULL zone — the same "bad value becomes NULL" contract every other
  coercion has, already counted by the cast-failure audit. ~2µs/row, paid only when a `timezone_column`
  is configured; the fixed-zone form compiles to a literal.
* 🔴 **The zone-column reference needs `CAST(… AS VARCHAR)`** — `lower()` binds only to VARCHAR, so a
  typed zone column was a binder error that killed the batch, precisely the failure the lookup exists to
  prevent. Found by a test, not by inspection.
* ⚠ **The gate is membership in `ZoneId.getAvailableZoneIds()`, and `ZoneId.of` alone is NOT a valid
  gate.** Offset forms (`+05:30`, `Z`) are **rejected by DuckDB** though `ZoneId.of` accepts them; the
  two engines disagree in both directions (DuckDB takes `utc`, Java does not; Java knows `UT`, DuckDB
  does not). Measured containment: all 604 Java zone ids appear in DuckDB's 638-row
  `pg_timezone_names()`, so the Java set is a proven-safe subset — pinned against the live engine by
  `everyZoneTheGateAdmitsIsOneDuckDbAccepts` rather than trusted.
* **ICU needed no work.** It is statically bundled, `loaded=true` on a bare `jdbc:duckdb:` connection,
  and named-zone arithmetic **survives the `SqlSandbox` seal** (`autoload_known_extensions=false` +
  `enable_external_access=false` + `lock_configuration=true`). This was the build's biggest assumed risk
  and it was not one.
* **DST-ambiguous and non-existent local times raise nothing** — Berlin `02:30` on both switch days
  resolves silently to `01:30` UTC. Nothing to guard; worth knowing.
* ⚠ **A blank cell in TOON's tabular field form is ABSENT, not empty.** `fields[N]{…}` declares one
  header per column, so a schema giving *any* field a zone writes `""` for all the others — null-only
  checks would refuse every such schema at load.
* **Surfaces** (`inspecto-ui`): a `source_timezone` select on the Grammar editor's **Types** tab across
  all four frontends — parsing-level (no `delimited__` prefix, matching `encoding`/`compression`) and
  with **no default** — plus a **Source zone** column in the columns table, rendered only on rows whose <!-- vocab-allow: "Source zone" is the shipped UI column label for a temporal ORIGIN zone, not the acquisition entity -->
  type carries an instant. One shared vocabulary in `inspecto/schema/time-zones.ts`; the offline mock
  mirrors the server's refusals on both the schema and the pipeline write.
  * ⚠ **`ConfigSpecs` needed nothing.** Parsing-block keys are frontend `AttributeSpec`s, and no backend
    allow-list gates an unknown config key (`ConfigSafetyValidator` is path-jail + output formats only),
    so the key saves through the control plane untouched. `date_formats` / `timestamp_formats` are not
    in `ConfigSpecs` either.
  * ⚠ **Placement deviates from the board row deliberately.** The row asked for the metadata grid; that
    grid is documented as Catalog-facing and *never read by the ETL*, and a source zone IS ETL-read. It
    went beside the **type it qualifies** in the columns table, on the DECIMAL-parameters precedent —
    self-limiting, so no timestamps means no column.
  * ⛔ **`timezone_column` has no editor, by decision.** Offering a per-row column beside ~418 zone names
    in one cell invites exactly the ambiguity the engine's mutual-exclusion rule prevents. A
    hand-authored one is **carried through a save** and shown read-only on its row, so the fixed-zone box
    cannot silently contradict it.
* 🔴 **Config-level key homes.** `parsing.source_timezone` is a **sibling of `delimited:`**, because it
  is format-agnostic — which means it had to be added to `PipelineConfigParser.mergeParsing`'s explicit
  **scalar allow-list**, the only path that carries a parsing-level scalar through. `timezone` /
  `timezone_column` are validated fail-closed in `Identifiers.validateSchema`; a `timezone_column`
  naming a field that does not exist is a load error. **`meta.domain.timezone` stays display-only** —
  activating it here would reverse the 2026-08-15 decision recorded in
  [`OperationsZone`](../control-plane/jobs.md).
* 🔴 **A pre-existing UI defect this work exposed, fixed with it.** A `type: 'select'` asks in a MatDialog
  that the CDK attaches to `document.body`, so choosing an option never bubbles a click through the pane;
  all three `pipelines/*-definition` panes derived dirtiness from `@HostListener('click')`, so **Apply
  stayed greyed out over a choice just made** — for every select on those drawers, not just this one. A
  2816-test suite missed it and the preview caught it. Fixed with `@HostListener('document:click')` and
  pinned by a regression spec.
* 🔴 **The `%z` defect — GROUNDED and CLOSED FAIL-CLOSED 2026-08-29. The board row was wrong in
  three ways, and there is a fourth directive it never mentioned.** It is **not** the plain branch, it does **not** need a mixed list, and it is worse than
  "session-interpreted".
  * ✅ **Confirmed:** a format list mixing a `%z` pattern with a plain one unifies the `COALESCE` to
    `TIMESTAMP WITH TIME ZONE` (a plain-only list stays `TIMESTAMP`).
  * 🔴 **REFUTED — the plain branch is NOT corrupted by the mixing.** `SqlBuilder.appendCoalesce`
    appends a trailing `::TIMESTAMP`, which converts *back* through the same session zone, so the
    promotion round-trips to an identity. Measured: `2026-03-01 10:00:00` returns `10:00:00` under
    `Asia/Calcutta`, `Europe/Berlin`, `UTC` and `America/New_York` alike, plain-only list and mixed
    list, bare and wrapped in `SourceZones.toNaiveUtc`. The one exception is narrow and is its own
    bullet below.
  * 🔴 **The real defect is the `%z` branch, and a `%z`-ONLY list has it too — mixing is irrelevant.**
    `TRY_STRPTIME` parses the offset correctly, and then the trailing `::TIMESTAMP` **throws the
    instant away by rendering it in the HOST's wall clock**. The same input
    `2026-03-01 10:00:00+00:00` yields `15:30` · `11:00` · `10:00` · `05:00` under those four host
    zones. The correct naive-UTC value is `10:00`, reachable as `timezone('UTC', <the TIMESTAMPTZ>)`.
    This is exactly the host-dependence the rest of this section exists to remove.
  * 🔴 **With a declared source zone it is doubly wrong and still host-dependent.** A `%z` value under
    `toNaiveUtc(zone='Asia/Tokyo')` lands on `2026-03-01 06:30` on an `Asia/Calcutta` host and
    `2026-02-28 20:00` on an `America/New_York` one — **a different calendar day**. The host render
    happens first, so the declared zone is then applied to an already-corrupted wall clock.
  * 🔴 **A `DATE` column is affected too, and it feeds partitions.** `date_formats` containing `%z`
    puts `2026-03-01 23:00:00+00:00` on `2026-03-02` (Calcutta host) vs `2026-03-01` (New York host)
    — so `DATE_*` partition keys become host-dependent.
  * ⚠ **The one thing mixing does add** is a DST-gap shift on the plain branch: a local time that does
    not exist in the **host's** zone is silently moved forward (Berlin `2026-03-29 02:30:00` → `03:30`
    on a mixed list, `02:30` on a plain-only one). The ambiguous fall-back case (`2026-10-25 02:30:00`)
    is an identity.
  * ⚠ **Nothing validates a format string.** `ConfigValidator:84` only *warns* on an empty
    `timestamp_formats`; `%z` is unvalidated free text, offered by no UI list and refused by nothing.
  * **Still latent:** no `.toon` under `spaces/` uses `%z` (the only grep hits are binary parquet).
  * 🔴 **There are exactly TWO offending directives, not one: `%z` (numeric offset) AND `%Z` (zone
    name).** `%Z` behaves identically (`10:00:00 UTC` → `15:30` on a Calcutta host, `05:00` on a New
    York one) and is named nowhere in the row, the plan or the original probe. It was found by
    **sweeping every ASCII letter directive against the live engine** and reading the return type — not
    by reading the strptime documentation. Every other accepted directive returns a naive `TIMESTAMP`
    (`%n` returns `TIMESTAMP_NS`, also naive).
  * **✅ DECIDED (operator, 2026-08-29) and SHIPPED: fail closed.** `SourceZones.assertNoZoneDirective`
    refuses a `%z`/`%Z` format in `date_formats` or `timestamp_formats` at config load, called from
    `PipelineConfigParser`'s single parse site for those two keys — the same posture and the same
    neighbourhood as the zone-less `TIMESTAMPTZ` refusal above. The message names the key, the format,
    the directive and the fix (declare a zone instead). No shipped config was affected.
  * ⚠ **`%%` is an escaped literal percent**, so `'%%z'` is the two characters `%z` in the input text
    and parses naive — measured, and deliberately admitted. The scan consumes `%%` as a pair, so
    `'%%%z'` (a literal percent then a real directive) is still refused.
  * 🔴 **The guard's SCOPE is pinned by re-running the sweep in a test, and was falsified in BOTH
    directions before being trusted** — dropping `%Z` from the gate fails it with *"these lose their
    offset to the host zone but the gate admits them: [%Z]"*, and adding a harmless `%y` fails it with
    the converse. A DuckDB upgrade that introduces a third zone directive therefore breaks the build
    instead of shipping the corruption.
  * **The feature is NOT built, deliberately.** The original row wanted "an offset in the data should
    **WIN** over any configured zone"; that remains a separate, deliberate build (emit
    `timezone('UTC', …)` and make the data's offset a fifth precedence tier). ⛔ Do not reach it by
    relaxing this gate.
  * ⚠ **The offline mock's pipeline-write zone gate is a DIVERGENCE, not parity** (found while scoping
    this). `onboarding.handler.ts` 422s on a bad `parsing.source_timezone`, but **no Java route
    validates it on write** — `ConfigSafetyValidator` is path-jail + output formats only and never runs
    the parser, so the real server accepts the write and refuses at load. S2's "mock parity mirrors the
    server's refusals" is accurate for the schema write and wrong for the pipeline write. Left as
    found; deliberately not extended to the format lists.
* **Dead code flagged, not touched:** `SqlBuilder.buildPartitionExpr` has no production caller (only
  `buildCastExpr` at `TransformCompiler:209` does).

Output is written via DuckDB `COPY` — see [output & sinks](output-sinks.md).
