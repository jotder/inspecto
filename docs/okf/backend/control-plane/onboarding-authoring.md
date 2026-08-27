---
type: Seam
title: Onboarding authoring seams (draft lifecycle, previews, register pair)
description: The control-plane routes the guided Stream/Reference onboarding authors against — a draft is an inactive pipeline; enrichments need explicit hot-registration.
resource: inspecto/src/main/java/com/gamma/control/ConfigWriteRoutes.java
tags: [control-plane, onboarding, config, enrichment, pipeline, reference]
timestamp: 2026-07-16T00:00:00Z
---

# Onboarding authoring seams

The guided onboarding (frontend: [onboarding](../../frontend/features/onboarding.md)) authors real
Stage-1 configs through these seams — **a draft is just a pipeline with `active: false`** (parsed,
indexed, catalog-visible, never executed; D3 of the design). Shipped P0–P3, 2026-07-16.

## Draft lifecycle (`ConfigWriteRoutes` write/patch, `ConfigReadRoutes` read/delete, `ConfigPreviewRoutes` previews)

- `POST /config/write {type, config, overwrite?, subdir?}` — spec + `ConfigSafetyValidator` gate
  (ERRORs → 422), filename from the config's own identity field, **scan-suffix convention
  enforced**: pipelines → `<name>_pipeline.toon` (`MultiCollectorProcessor.resolveConfigs`),
  enrichments → `<name>_enrich.toon` (`ServiceBootstrap.resolveBySuffix`) — a bare name silently
  drops out of the registry on the next restart (found live, P2/P3).
- `GET|DELETE /config/{type}/{name}` — suffix-first resolution with bare-name fallback; DELETE
  refuses an `active: true` pipeline (409).
- **Write alone does not index a NEW file** — the register pair below completes a create; later
  overwrites hot-reload pipelines by mtime (enrichments do NOT — see below).

## Stateless sample previews

- `POST /config/preview/parsing {config, sample_text}` → `ComponentPreview.parsing` — the draft is
  interpreted by the real config parser, read with the engine's own DuckDB idioms per frontend;
  `all_varchar=true` for delimited/NDJSON (raw ingest is 100% VARCHAR; also keeps `java.time` out of
  the JSON). **`format: array|auto` (2026-07-19 fix)**: `read_json` has no `all_varchar` option, so
  `jsonSelect` instead casts every column with `SELECT COLUMNS(*)::VARCHAR FROM read_json(...)` —
  before this fix, an auto-detected timestamp came back as a DuckDB `TIMESTAMP` (a non-serializable
  `java.time` value), inconsistent with every other format's raw-string preview.
- `POST /config/preview/schema {config:{raw:{fields}}, sampleRows}` → `ComponentPreview.schema`
  TRY_CAST split → `{columns, okCount, rejectedCount, rejectedRows}`.
- `POST /enrichment/preview {config:{…enrichment draft…}, sampleRows}` → `EnrichmentEngine.preview` — seeds
  the `input` view from the sample (all VARCHAR), registers the real reference views (`ref:`-by-name resolve
  against the loaded pipelines, `path:` reads the file), runs the draft's `transform`, returns
  `{columns, rows, truncated}`. Persists nothing (throwaway DuckDB, `output.database` untouched); the enrichment
  stage's "Validated" state. 400 for a missing config/sample; 422 when the draft doesn't parse or its transform
  fails on the sample (surfaces exactly the error a run would hit).
  **UI (2026-07-19):** the enrichment pane's **Preview** button (`enrichment-pane.component`) samples the
  stream's Stage-1 output via `GET /db/table?name=<normalizedName>&limit=200` (the decision-rule Simulate
  idiom) and posts it as `sampleRows` (`ConfigService.previewEnrichment`); results render in a shared
  `<inspecto-query-panel>`, a 422 surfaces as an inline alert, and a stream with no ingested data yet warns
  (the `/db/table` 404/empty path) instead of calling the endpoint. Read-only — available in every lens.

## The register pair

- **Pipelines:** `POST /runs {configPath}` → `CollectorService.registerPipeline` (in-memory; the
  file under a scanned config dir is what survives restart).
- **Enrichments (v5.1.0):** `POST /enrichment {configPath}` → `CollectorService.registerEnrichment`
  → `EnrichmentService.register` — **upsert by `name`** over a live job list. Needed because
  enrichments have NO mtime hot-reload and had no register route (a new `*_enrich.toon` used to
  require a restart; the `POST /jobs type=enrich` workaround does full recomputes and breaks
  by-name refs). Event triggers apply from the next committed batch; schedule timers resolve their
  config **by name at fire time**. `EnrichmentService` is **always constructed** (empty-list tolerant)
  so a fresh space can register its first job; `GET /enrichment` with no jobs is 200-`[]` (was 404).
  Gates mirror `POST /runs`: 503 → 400 → 403 → 404 → 422; replace is the documented upsert (no 409).

**2026-07-20 SHIPPED — the unregister counterpart, for both pipelines and enrichments:**
`CollectorService.unregisterPipeline` removes a config path from the active registry and rebuilds the
read surface synchronously; wired from `DELETE /config/pipeline/{name}` (the onboarding draft-discard
path), so a discarded draft drops out of the catalog/`pipelines()` at once instead of ghosting there
until the next poll cycle. `EnrichmentService.unregister` (via `CollectorService.unregisterEnrichment`,
wired from `DELETE /config/enrichment/{name}`) removes a hosted job and cancels its schedule timer
immediately — previously a deleted-on-disk enrichment job's completeness timer kept firing (as a no-op)
until restart. `EnrichmentService.register`'s re-arm also now cancels the prior timer before starting a
new one, so a **changed** `schedule_seconds` on an existing name applies immediately rather than only
at restart (`Scheduler.everySeconds` now returns a cancellable `ScheduledFuture`, mirroring `cron()`'s
`CronHandle`).

## Reference production (`produces: reference`, P0)

A pipeline declaring `produces: reference` registers a standalone `REFERENCE_DATASET` origin
(`ref:<pipeline>`) instead of a Stream; `EnrichmentConfig.Reference{ref}` binds it **by name**,
resolved per recompute against the live registry (`EnrichmentEngine.referenceReader` — a
Hive-partitioned glob over the producer's `dirs.database` in its output format). Origin nodes
carry `attrs.active` (P3) so `/catalog/references` rows expose Draft/Live. The bare `EnrichJob`
path passes no pipeline context — by-name refs there fail with a clear error (deliberate).

### Reference Phase-2 load semantics + Stream grouping (config model, 2026-07-24)

Two additive, backward-compatible pipeline-config keys (Reference Phase-2 plan P0+P4; `load: replace`
default ⇒ every existing pipeline parses/runs identically):

- **`reference:` block** (`PipelineConfig.Reference`, never null → `DEFAULT` when absent) — the load
  semantics of a `produces: reference` dataset: `load: replace|upsert|scd2` (`PipelineConfig.Load`,
  default `REPLACE` = today's full-replace), `key: [cols]` (identity columns), `refresh_seconds` (0 =
  on-collect only; >0 arms a Phase-3 compaction timer). `upsert`/`scd2` **require** a non-empty `key`
  and each key column must exist in the resolved schema — enforced parser-side
  (`PipelineConfigParser`, eager `IllegalArgumentException`; column check skipped for a
  draft-without-schema) **and** mirrored declaratively in `ConfigSpecs.pipeline()` (the
  `reference.load` enum + the `reference-upsert-requires-key` `CrossFieldRule`), the two paths kept in
  sync by convention + tests. The block is inert on a Stream pipeline. **Engine mechanics
  (append + current-view read, SCD-2 history, compaction) are P1–P3 — still backlog; P0 only carries
  and validates the config.**
- **`stream:` key** (`PipelineConfig.stream()`, GLOSSARY §3 membership) — the logical Catalog Stream a
  pipeline belongs to; default = the pipeline's own name (strict 1:1, unvalidated for back-compat), an
  explicit value is normalised (lowercase, spaces→`_`) and validated as a SQL identifier. In
  `MetadataGraphBuilder`, pipelines sharing a `stream:` collapse under **one** `stream:<logical>` node
  (label = the logical name, `members[]` = the member pipelines); each member keeps its own
  `schema:`/`event:`/`col:` nodes (child `source=<pipeline>` attr preserves per-member identity). A 1:1
  Stream is byte-for-byte unchanged (label = pipeline display name, no `members[]`). Applies to STREAM
  origins only — references keep `ref:<pipeline>`. `/catalog/streams` is a separate per-collector
  projection and is unaffected.

### Reference Phase-2 P1 — `upsert` engine (append-only, latest-wins; 2026-07-24)

Design (c) from the plan (§2): a `produces: reference` + `load: upsert` store is **append-only Parquet,
latest-version-wins**, the current view derived at read time. `load: replace` (default) is untouched.

- **Write** (`BatchIngestStrategy.stampReferenceVersions`, called from `writeAndTrace` — the single tail
  every ingest strategy routes through, so all paths get it): gated on
  `cfg.producesReference() && cfg.reference().load()==UPSERT`, it materialises `__ref_versioned` from
  the `transformed` table with the §2.1 system columns appended — `__key_hash`
  (`md5(concat_ws(chr(31), COALESCE(CAST(key AS VARCHAR),'')…))`), `__valid_from` (`now()`), `__op`
  (always `'upsert'` on the ingest path — see below), `__batch_id` — and folds within-batch key dupes
  via `QUALIFY row_number() OVER (PARTITION BY <hash>) = 1` (tie-break arbitrary — D6). `__src_id` is
  kept so `PartitionWriter`'s default exclude + `LineageCollector` are unchanged. The write reveals
  under a **batch-unique file stem** (`<base>__v_<batchId>`) so versions accumulate instead of
  overwriting — append via unique filename, not a new writer mode.
- **Read** (`EnrichmentEngine.currentView`, in `referenceReader`'s by-name branch when the bound
  pipeline is `UPSERT`): `SELECT * EXCLUDE(__key_hash,__valid_from,__op,__batch_id) FROM (… QUALIFY
  row_number() OVER (PARTITION BY __key_hash ORDER BY __valid_from DESC)=1) WHERE __op != 'delete'` —
  latest version per key, tombstoned keys dropped, system columns stripped. `path:` refs and `replace`
  stores read verbatim (today's behaviour).
- **Deferred:** how a `delete` tombstone *enters* the store on the ingest path is **not** built (D5) —
  the ingest path always stamps `'upsert'`; the views merely honour a `delete` version if one exists.
  Compaction + the `refresh_seconds` timer are still P3.
- Tests: `ReferenceVersionStampTest` (stamp + within-batch dedup + unchanged-row skip) ·
  `ReferenceUpsertCurrentViewTest` (two batches — changed + unchanged + new key + delete tombstone →
  current view = expected).

### Reference Phase-2 P2 — `scd2` as-of history + unchanged-row skip (2026-07-25)

P2 makes the versioned store *readable as history* and stops it growing on no-op re-deliveries.

- **`scd2` writes the same store as `upsert`.** The write gate moved from `load()==UPSERT` to
  `load().versionedStore()` (`PipelineConfig.Load`, = non-`REPLACE`) — before this, an `scd2` pipeline
  silently fell through to plain full-replace. The two modes differ only in what is *readable*
  (`scd2` also serves as-of) and, later, in what compaction retains (P3 `history_days`).
- **`__row_hash`** is a fifth §2.1 system column: md5 over the payload columns, with `__src_id`
  **excluded** — it is per-batch lineage bookkeeping and would make every re-delivery look changed.
  The column list is read from the staged table's `ResultSetMetaData`, not a DuckDB `COLUMNS(*)` star
  expression, so the hash expression is explicit and order-stable.
- **Unchanged-row skip:** when the store already has files, `stampReferenceVersions` anti-joins the
  staged batch against the store's own current view on `__key_hash || __row_hash` (both fixed-length
  md5 hex and never null, so a scalar `IN`-list is safe where a row-constructor `IN` is not) — an
  identical re-delivery appends no version; a changed payload, a new key, and a key whose current
  version is a tombstone all still append. First batch (empty store) skips the join: a glob matching
  no file is an error in DuckDB, so `existingStoreReader` returns `null` after a filesystem probe,
  and an unreadable tree degrades to "no skip" rather than failing the write.
- **As-of read:** `EnrichmentEngine.currentView` generalised to `versionedView(reader, asOf)` — with
  `asOf == null` it is P1's latest-wins current view verbatim; with an `asOf` the candidate set is cut
  to `__valid_from <= TIMESTAMP '…'` **before** the `QUALIFY`, so the winner is the version valid then.
  A key created later is absent; a key deleted later is still present.
- **Authoring:** `references.<name>.as_of` on the *enrichment* binding (`EnrichmentConfig.Reference.asOf`),
  not the pipeline — an ISO-8601 date or date-time, canonicalised to `yyyy-MM-dd HH:mm:ss` at parse
  because the value is spliced into SQL; anything else is rejected. No `${}` resolvers (deliberate cut).
  Not in `ConfigSpecs.enrichment()` — the `references` block is a dynamic per-name map, so `path`/`ref`/
  `format` aren't declared there either. **Fail-closed** on `as_of` against a `replace`/`upsert` producer
  (an upsert store's superseded versions are compaction fodder, not a queryable surface) or a plain
  `path:` reference (a file carries no version history).
- Tests: `ReferenceScd2AsOfTest` (6 — as-of state at t · current view unaffected · both fail-closed
  rejections · parse validation incl. bare-date start-of-day).

### Reference Phase-2 P3 — compaction + `refresh_seconds` timer (2026-07-25)

The append-only store reveals one file per batch per partition dir, so a frequently-refreshed Reference
accumulates files forever even though the current view only ever surfaces the latest version per key.
P3 makes that derived view the *physical* truth — compaction output **is** the cache the fast path reads.

- **`reference_compact`** is a new `maintenance` sub-task (`ReferenceCompactor`, `com.gamma.job`), i.e. a
  `case` in `MaintenanceJob`'s `switch` — **not** a new Job Type; that is the house pattern for every
  maintenance task. Params: `dir` (required, the store root) · `history_days`.
- **`history_days` has three modes**, which is what makes one task serve both loads:
  `0` (default) = winning versions only, **tombstoned keys dropped outright** (the current view already
  hides them, and a later re-delivery just upserts again) — right for `upsert`; `> 0` = winners plus
  versions inside the horizon, so `scd2` as-of keeps answering inside it; **negative = keep-forever**,
  merging files while dropping no version — the D4 default for `scd2`. Keep-forever still pays, because
  read amplification is a function of **file count**, not row count.
- **The winner is derived store-wide, then each dir is rewritten to its slice.** A dimension row whose
  *partition* value changed has versions in different partition dirs, so a per-dir "keep the latest here"
  would resurrect a superseded version. A dir left with no retained rows loses its files entirely.
  The retained-set window is character-for-character `EnrichmentEngine.versionedView`'s, so "compacted
  store" and "current view" cannot drift.
- **Safety model is `PartitionCompactor`'s, reused verbatim** (there is no lock between jobs and ingest):
  `*.refcompact.tmp` / `*.parquet.refcompacting` / `.refcompact-journal` all sit outside the readers'
  `*.parquet` glob, the merged file appears in one `ATOMIC_MOVE`, and a crash is repaired from the journal
  on the next run. **Unlike `compact` there is no age cutoff** — it isn't needed: a concurrent batch commit
  only ever creates a *new* uniquely-named file, which is simply not in this run's candidate set.
  ⚠ **Gotcha found in build:** `heal()` must walk **every** directory before the work scan, not just dirs
  that still hold live `*.parquet`. A dir a killed run left mid-swap has all its files hidden, so it looks
  empty — healing only "populated" dirs left those versions both invisible *and* absent from the
  store-wide retained set, silently losing rows (`aCrashedRunIsHealedOnTheNextPass` is the regression).
- **`refresh_seconds` timer:** `CollectorService.armReferenceRefresh(cfg)` arms
  `scheduler.everySeconds("ref-compact-"+id, …)` for a versioned producer with `refresh_seconds > 0` —
  cancel-and-rearm keyed by pipeline id (mirroring `EnrichmentService.armSchedule`), so a changed interval
  applies without a restart and dropping to `0`/`load: replace` disarms it. Called from `start()` (boot)
  and `registerPipeline`; **cancelled in `unregisterPipeline`** beside `pipelineScheduler.forget(i)`.
  The timer **compacts** — it never re-pulls from the origin, which stays the Collector's poll loop.
  It re-reads the config each fire (so a stale timer acts on current settings) and never throws: a failed
  compaction leaves the store readable, because the read-time view does not depend on it.
  `-Dreference.compact.history.days` overrides the `scd2` keep-forever default with a horizon.
- Tests: `ReferenceCompactorTest` (7 — compacted == current view + file-count collapse · keep-forever
  merges without dropping · idempotent second pass · **versions split across partitions** ·
  absent store · crash-heal · reachable as a `maintenance` task + dry-run-safe) ·
  `CollectorServicePipelineForgetTest.unregisterCancelsTheReferenceRefreshTimer`.

## Engine fixes the live walks surfaced (apply beyond onboarding)

1. `SqlBuilder.appendCoalesce` with empty `date_formats` emitted zero-arg `COALESCE()::DATE` —
   whole batch `QUARANTINED_UNREADABLE`; now falls back to `TRY_CAST` (native ISO parse).
2. The collector-level `duplicate:` block is a **no-op on the legacy local poll path** — real
   dedup there is `processing.duplicate_check` (marker files); without it the same file re-ingests
   every cycle (idempotent output, but a spurious `BatchEvent` per cycle re-fires enrichments).
3. Without `dirs.status_dir` no batch audit lands — `/runs/{name}/batches` stays empty forever.

(2) and (3) are why the guided create derives the full orders-convention dir set +
`duplicate_check` silently.
