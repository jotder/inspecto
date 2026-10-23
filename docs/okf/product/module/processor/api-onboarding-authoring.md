---
type: Seam
title: Onboarding authoring seams (draft lifecycle, previews, register pair)
description: The control-plane routes the guided Stream/Reference onboarding authors against — a draft is an inactive pipeline; enrichments need explicit hot-registration.
resource: inspecto/src/main/java/com/gamma/control/ConfigWriteRoutes.java
tags: [control-plane, onboarding, config, enrichment, pipeline, reference]
timestamp: 2026-07-16T00:00:00Z
---

# Onboarding authoring seams

The guided onboarding (frontend: [onboarding](../../layer/product/user-guide-onboarding.md)) authors real
Stage-1 configs through these seams — **a draft is just a pipeline with `active: false`** (parsed,
indexed, catalog-visible, never executed; D3 of the design). Shipped P0–P3, 2026-07-16.

## Draft lifecycle (`ConfigWriteRoutes` write/patch, `ConfigReadRoutes` read/delete, `ConfigPreviewRoutes` previews)

- `POST /config/write {type, config, overwrite?, subdir?}` — spec + `ConfigSafetyValidator` gate
  (ERRORs → 422), filename from the config's own identity field, **scan-suffix convention
  enforced**: pipelines → `<name>_pipeline.toon` (`MultiCollectorProcessor.resolveConfigs`),
  enrichments → `<name>_enrich.toon` (`ServiceBootstrap.resolveBySuffix`) — a bare name silently
  drops out of the registry on the next restart (found live, P2/P3).
- 🔴 **A schema's FILE is not its identity** (`SCHEMA-FILE-NAME-1`, 2026-09-25). A schema self-names via
  `raw.name`, but `raw.name` is the raw/source identity (the pipeline name, or `ORDERS`/`CALL`) while a
  Pipeline references its schema as **`<pipeline>_schema.toon`** — the one convention the scaffold, the
  Parse pane, stream transfer and the delete cascade share (`companionSchemaName(id, 'schema')`). So a
  `type=schema` write may carry **`file`** (a bare safe name, no extension): the file lands at
  `<file>.toon` with `raw.name` untouched, and the response `name` is that file name (what
  `GET /config/schema/{name}` reads it by). Absent ⇒ named by `raw.name`, as before. `file` on any
  other type is **400** (their filename IS their identity); an unsafe one 422s. ⚠ Before this the Parse
  pane wrote `<pipeline>.toon` on every Apply — `SCHEMA-NAME-1` rightly stopped rebuilding `raw.name` from
  the file name, and the server then named the file from `raw.name` — while the node it Applied named
  `<pipeline>_schema.toon`: a pipeline that saved, validated and activated clean and never loaded.
  Pinned by `ControlApiSchemaFileRefTest`.
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
- `POST /enrichment/preview {config:{…enrichment draft…}, sampleRows}` (gated `canAuthorWorkbench` since
  2026-09-30 — it executes the draft's SQL) → `EnrichmentEngine.preview` — seeds
  the `input` view from the sample (all VARCHAR), registers the real reference views (`ref:`-by-name resolve
  against the loaded pipelines, `path:` reads the file), runs the draft's `transform`, returns
  `{columns, rows, truncated}`. Persists nothing (throwaway DuckDB, `output.database` untouched); the enrichment
  stage's "Validated" state. 400 for a missing config/sample; 422 when the draft doesn't parse or its transform
  fails on the sample (surfaces exactly the error a run would hit).
  **UI (2026-07-19):** the enrichment pane's **Preview** button (`enrichment-pane.component`) samples the
  stream's Stage-1 output via `GET /db/table?name=<normalizedName>&limit=200` (the decision-rule Simulate
  idiom) and posts it as `sampleRows` to `POST /enrichment/preview` (⚠ `ConfigService.previewEnrichment` is now called by the Configuration pane's Preview tab — `okf/product/module/config/user-guide-config.md`); results render in a shared
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

- **Write** (`ConsignmentIngestStrategy.stampReferenceVersions`, called from `writeAndTrace` — the single tail
  every ingest strategy routes through, so all paths get it): gated on
  `cfg.producesReference() && cfg.reference().load()==UPSERT`, it materialises `__ref_versioned` from
  the `transformed` table with the §2.1 system columns appended — `__key_hash`
  (`md5(concat_ws(chr(31), COALESCE(CAST(key AS VARCHAR),'')…))`), `__valid_from` (`now()`), `__op`
  (`'upsert'`, or `'delete'` from a `reference.delete` marker — D5-ref, below), `__batch_id` — and folds
  within-batch key dupes via `QUALIFY row_number() OVER (PARTITION BY <hash>) = 1` (tie-break arbitrary
  unless `reference.order_by` is set — D6-ref, below). `__src_id` is
  kept so `PartitionWriter`'s default exclude + `LineageCollector` are unchanged. The write reveals
  under a **batch-unique file stem** (`<base>__v_<batchId>`) so versions accumulate instead of
  overwriting — append via unique filename, not a new writer mode.
- **Read** (`EnrichmentEngine.currentView`, in `referenceReader`'s by-name branch when the bound
  pipeline is `UPSERT`): `SELECT * EXCLUDE(__key_hash,__valid_from,__op,__batch_id) FROM (… QUALIFY
  row_number() OVER (PARTITION BY __key_hash ORDER BY __valid_from DESC)=1) WHERE __op != 'delete'` —
  latest version per key, tombstoned keys dropped, system columns stripped. `path:` refs and `replace`
  stores read verbatim (today's behaviour).
- How a `delete` tombstone *enters* the store was deferred here (D5) and shipped 2026-09-25 as the
  `reference.delete` marker column — see *D5-ref / D6-ref* below. Compaction + the `refresh_seconds`
  timer are P3.
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

### D5-ref / D6-ref — delete-feed marker + `order_by` tie-break (2026-09-25)

Operator decision 2026-09-25: the real delete-feed carries a **marker column** (an op/deleted flag,
e.g. `op='D'`), so a delete enters the store as a row of the ordinary feed — not a reserved system
column and not a Decision Rule consequence. Both keys are optional and live in the `reference:` block;
absent ⇒ byte-identical to P1/P2.

```
reference:
  load: upsert
  key[1]: customer_id
  order_by: updated_at
  delete:
    column: op
    values[1]: D
```

(`load` may be `upsert` or `scd2`; both keys are refused on `replace`. No `#` comments — TOON rejects them.)

- **Write** (`ConsignmentIngestStrategy.stampReferenceVersions`, which now takes the whole
  `PipelineConfig.Reference`): `__op = CASE WHEN CAST(<column> AS VARCHAR) IN (<values>) THEN 'delete'
  ELSE 'upsert' END` — matched on the column's **text form**, exact and case-sensitive, so a BOOLEAN
  `deleted` flag works with `values: [true]`; a NULL marker is an upsert. The marker column is then
  **dropped** (`SELECT * EXCLUDE (<column>)`) and excluded from `__row_hash`: it is feed bookkeeping, so it
  is never stored and a `U`→`D` flip never reads as a payload change.
- **Which deletes append:** a delete writes a tombstone only when its key has a **live** version in the
  store (the same current-view subquery the unchanged-row skip uses); a delete of a never-seen or
  already-tombstoned key, and every delete in the store's first batch, appends nothing — a re-delivered
  delete feed does not grow the history. ⚠ The P2 unchanged-row skip now applies to **upserts only**: a
  delete row's payload is usually identical to the live version, and the skip would otherwise swallow it.
  The read side (`EnrichmentEngine.versionedView`, `ReferenceCompactor`) needed no change — it already
  honoured `__op = 'delete'`.
- **`order_by` (D6-ref):** the within-batch `QUALIFY` window orders by `<order_by> DESC NULLS LAST`, then
  **delete-first**, then `__row_hash` — the greatest value wins per key, a NULL never beats a value, a tie
  goes to the delete, and any remaining tie is broken by payload hash, so the winner never depends on
  input row order. Deletes participate: upsert@1 + delete@2 → tombstone; delete@1 + upsert@2 → the upsert.
  Unlike the marker, the `order_by` column is ordinary payload and **is** persisted. It orders only
  *within* a batch — across batches, `__valid_from` still decides.
- **Validation (both paths, fail-closed):** `PipelineConfigParser` throws, and `ConfigSpecs.pipeline()`
  declares `reference.delete.column` / `reference.delete.values` / `reference.order_by` plus four rules —
  `reference-delete-needs-column-and-values` (ERROR: either alone), `reference-delete-order-by-require-versioned-load`
  (ERROR: on `replace` they would be silently inert), `reference-delete-column-not-a-key` (ERROR: the
  marker is dropped before hashing), `reference-delete-without-order-by` (WARNING: a same-key upsert+delete
  pair in one batch resolves arbitrarily). The parser additionally checks both columns exist in the resolved
  schema (skipped for a draft without one). `POST /pipelines/{name}/settings` answers **422** on the ERROR
  rules (it runs the spec, not the parser). The generated accepted-names table in
  [pipeline-config-keys](../config/spec-pipeline-config-keys.md) gained `reference.delete` and
  `reference.order_by`.
- **UI surface (2026-10-11, `3581de0cf`):** the Pipeline settings dialog
  (`inspecto-ui/src/app/modules/admin/pipelines/pipeline-settings.dialog.ts`) edits `reference.order_by`
  (one column, free text) and `reference.delete` (marker column + comma-separated values). The fields show
  only for `upsert` / `scd2`; a `replace` save drops both keys, matching ERROR
  `reference-delete-order-by-require-versioned-load`. On Save the client mirrors ERROR
  `reference-delete-needs-column-and-values` and `reference-delete-column-not-a-key` as field errors; WARNING
  `reference-delete-without-order-by` shows inline and does not block. Clearing a field removes its key;
  unmodelled `reference` keys pass through, because the route replaces the block wholesale. A non-string
  stored marker value loads and saves as text (the engine compares on the text form anyway). ⚠ The earlier
  note here — that a Settings save **dropped** both keys — was already stale before this: the dialog had
  been passing unmodelled keys through untouched. See [onboarding](../../layer/product/user-guide-onboarding.md).
- Tests: `ReferenceVersionStampTest` (+5 — tombstone despite identical payload + marker not persisted/hashed ·
  delete of a non-live key appends nothing · boolean marker · order_by row-order independence + NULL loses ·
  deletes in the ordering + tie → delete) · `PipelineConfigReferenceTest` (+8) · `ConfigSpecsTest` (+4) ·
  `ControlApiPipelineSettingsTest` (+2, the 422s and a round-trip).

## Engine fixes the live walks surfaced (apply beyond onboarding)

1. `SqlBuilder.appendCoalesce` with empty `date_formats` emitted zero-arg `COALESCE()::DATE` —
   whole batch `QUARANTINED_UNREADABLE`; now falls back to `TRY_CAST` (native ISO parse).
2. The collector-level `duplicate:` block is a **no-op on the legacy local poll path** — real
   dedup there is `processing.duplicate_check` (marker files); without it the same file re-ingests
   every cycle (idempotent output, but a spurious `ConsignmentEvent` per cycle re-fires enrichments).
3. Without `dirs.status_dir` no batch audit lands — `/runs/{name}/batches` stays empty forever.

(2) and (3) are why the guided create derives the full orders-convention dir set +
`duplicate_check` silently.

## An event-triggered enrichment is never silently widened to a full recompute (2026-09-15)

`ENRICH-SILENT-FULL-RECOMPUTE-1`, closed. `EnrichmentService.doRecompute` decides *full vs scoped* from one
predicate — `filter == null || filter.isEmpty()` — and `toFilter` drops every committed partition path whose
keys share nothing with `job.input().partitions()`. Until 2026-09-15 that made two different states read the
same: **"no partitions requested"** (a legitimately unscoped commit → full recompute) and **"the requested
partitions matched nothing"** (the producer partitions by columns this job does not). The second silently ran
a full-window recompute, with nothing logged to say the mode had changed — the exact thing the W4 design
note called out as a rule, while the code was already doing it.

* `onConsignmentEvent` now tells them apart: a non-empty event whose filter comes out empty is **REFUSED** —
  a `FAILED` audit row with scope `refused` and the reason (*"no column in common … refusing rather than
  silently recomputing the full window"*), the failure metric, a log line — and no recompute runs. Same idiom
  as `RowShaper.windowedDedup`, which refuses a windowed dedup with no ledger rather than running unwindowed.
* A genuinely unscoped commit (an empty partition list) still recomputes fully, as before.
* ⛔ Do not "fix" a refusal by falling back to full — silently widening a triggered job's blast radius is the
  defect, not the remedy. The producer's partition columns and the job's `input.partitions` must agree.
* Pinned by `EnrichmentServiceTest.eventWhosePartitionsMatchNoJobColumnIsRefusedNotWidenedToFull`
  (mutation-verified: disabling the guard makes it fail on "a refused recompute announces no commit").

## Add data: `/onboard/files` (file in → Dataset out; `LA-ONBOARD-1` phase 2, 2026-10-22)

One server route family turns a person's file into a Dataset in two calls, on every edition (Basic included), in
the core module (`OnboardRoutes`, `OnboardDraft`, `EntityShapes`, `OnboardImport`; not under `/inv`). It replaces
the nine-call walkthrough (parse preview → suggest → two `/config/write` → `/runs` → copy → trigger →
`/components/dataset` → view). The console's guided Onboard Stream / Reference keeps working beside it.

| Route | Capability | What it does |
|---|---|---|
| `POST /onboard/files?name=&kind=stream\|reference[&delimiter=&header=&quote=]` | `canUploadData` | raw body, streamed to `<data root>/_onboard/<draftId>/`; answers the draft |
| `POST /onboard/files/{draftId}/commit` | `canUploadData` | one confirm: writes, registers, lands, runs once, registers the Dataset; 200 run summary, or 202 above `import.inline_max_bytes` |
| `GET /onboard/runs/{runId}` | `canUploadData` | follows a 202 run (`running` / `succeeded` / `failed`): the caller's own, or any with `canAdminister` |
| `POST /datasets/{id}/files?name=[&mode=replace\|append]` | `canUploadData` | **Add a file** to an imported Dataset and run it (replace is the default; append for a Stream only) |
| `POST /datasets/{id}/run` | `canUploadData` | **Run now**: runs the import Pipeline over what waits in its inbox |
| `PUT /datasets/{id}/columns/{column}` | `canAuthorWorkbench` | one column's Entity Type: `{entityType: id\|null}` or `{classification: X\|null}` |
| `GET` / `PUT /settings/data` | open / `canAdminister` | the Space's `data.self_import`: `{selfImport, selfImportDefault, selfImportInForce}` |

**Gate order** for the Add data routes: capability 401/403 → no write root 503 → `data.self_import` off 403 ("Add
data is turned off in this Space: its data comes from Pipelines - ask an administrator to turn it on") → the route's
own checks. `data.self_import` lives in `data.toon` (a reserved config path: no import may write it); unset = on
with no sign-in, off with sign-in (`Authenticators.active()`); `/bootstrap` carries the value in force as
`selfImport`. Rate class: the standard bucket (`OnboardRoutes.isRateLimited`).

### The draft (the contract shared with `ONBOARDING-FIRST-RUN-1`)

```
{draftId, kind, file:{name, bytes, rows},
 format:{type, delimiter?, header?, quote?},                    // delimiter/header/quote for CSV / TSV only
 columns:[{name, header, type:{value, format, evidence, confidence}, sample:[up to 3]}],
 rows:[up to 20 preview rows],
 entityTypes:{columns:[{column, entityType:{value, classification?, evidence, confidence}}],
              primary, link:{from, to, kind, time, amount, details} (stream) | identifying (reference)},
 limits:{maxBytes, maxRows, maxImports, imports, inlineMaxBytes, excel}}
```

* **Every proposed choice is `{value, evidence, confidence}`**. OF-2: `high` when every sampled value agrees and
  the sample has at least 50 non-blank values (`OnboardDraft.MIN_SAMPLE`), `low` otherwise. A choice the user
  stated (`?delimiter=` on a re-draft) is `high` by definition. The sample is the first 1,000 rows.
* `format` is the extension point of slice C1 (file-name dates, decimal mark, nested-JSON record path): add a key
  and keep the choice shape. A column's date or time format rides its `type` choice (`format`, the strptime
  pattern every sampled value parsed with: DATE through `SchemaSuggest`, TIMESTAMP from a fixed candidate list).
* Reading uses a sealed in-memory DuckDB allowed only the draft directory. Delimited text: `sniff_csv` (it spells
  "no quote found" as `(empty)`, and fails on mixed line endings; then the delimiter is guessed from field counts
  and every format choice is `low`), field-count evidence from a quote-aware split of the first lines, and types by
  `SchemaSuggest` voting. Parquet: the file's declared types ("the file declares …"). JSON (array) and NDJSON:
  `read_json`. Excel (`.xlsx`): only when the DuckDB `excel` extension loads from the bundle (the staged
  `-Dduckdb.extension.dir`, else DuckDB's local cache; this route never runs an `INSTALL`); otherwise 422 "save
  '<file>' as CSV". `.xls`, zip archives and other types are 422 naming the formats that are read.
* **Value shapes** (`EntityShapes`): IMEI (15 digits with a valid Luhn check digit), IBAN (mod-97; maps to the type
  claiming `IBAN`, else `ACCOUNT`), e-mail (no seeded type claims it, so the column stays a detail and the evidence
  says so), `+` and 8 to 15 digits (MSISDN; bare 8 to 15 digits is a weak, `low` guess). Names: a token equal to an
  Entity Type id or classification, plus hint words (account/acct/iban, msisdn/phone/caller/called/a_number, imei,
  imsi, wallet, agent/till, cell). Only VARCHAR and BIGINT columns can hold identifiers. **From/to:** two columns
  of one Entity Type whose names say direction (from/to, payer/payee, sender/receiver, caller/called, debit/credit,
  src/dst) is `high`; the same type without direction words, or two different types, is `low`. Kind: a text column
  named type/kind/channel/… with at most 50 distinct sampled values. Time: the TIMESTAMP (else DATE) column.
  Amount: a numeric column named amount/value/sum/…. Details: the rest. Primary: the Entity Type most columns
  hold (a tie is `low`). Reference: the identifying column is the Entity Type column whose sampled values are all
  distinct (an `id` or `key` name wins).
* Limits come from the configurable-limits catalogue (`LaLimit`, Space layer, `PUT /settings/link-analysis/limits`):
  `import.max_bytes` 500 MiB (streamed; a declared length is refused before reading), `import.max_rows` 10 M
  (counted at draft), `import.max_datasets` 20 (at commit), `import.inline_max_bytes` 64 MiB. Measured 2026-10-22
  on a laptop, 7-column CSV, no index: 65 MB / 1 M rows took 1.5 s to draft and 4.5 s to commit; 264 MB / 4 M rows
  took 3.2 s and 20 s. A 500 MB file would hold a request for about 40 s plus the index build, hence the 202
  threshold.
* Drafts expire after 24 hours (pruned on the next upload); a signed-in caller sees only their own (404 otherwise).

### The commit

Body (every key optional; absent = the draft's proposal): `{name, description, columns:[{name, type?, format?,
entityType? (id|null), rename?, drop?}], primaryEntityType?, link?: {from, to, kind?, time?, amount?, details?} |
null, identifyingColumn?}`. Columns are named by their **draft** name. `format.*` cannot change at commit (422:
re-draft with `?delimiter=…`), because the columns were read with it. Order:

1. Decide everything first: 422 for a choice naming nothing real; 409 for a taken name (Dataset, Pipeline, config
   files or `<data root>/<id>`), an approval policy over Datasets, Pipelines or schemas, an import of that id in
   flight (the id is claimed before these checks), or
   `import.max_datasets`. The Dataset entry passes `ComponentRoutes.validateKind` before anything is written.
2. `imports/<id>_schema.toon` (raw fields = every draft column, **index selectors for delimited files** (OB-4) and
   key names otherwise; a dropped column stays a raw field and leaves the mapping; a rename is the mapping's `name`)
   and `imports/<id>_pipeline.toon`, each through `SaveGate.check` (any ERROR refuses with its findings). The
   Pipeline: `origin: import` (OB-3; a spec key read by no engine code), `trigger: {type: manual}` (it never polls,
   on any edition), the local inbox `<data root>/inbox/<id>`, `accepts_upload: true`, `duplicate_check`, PARQUET
   into `<data root>/<id>/database`, and `produces: reference` for a Reference.
3. `registerPipeline`, then the file is **copied** into the inbox as `add_<letters>_<stem>.<ext>`, `<ext>` being the
   import's own lower-case extension that its `file_pattern` globs (so `.txt`, `.tab`, `.jsonl`, `.xlsm` and `T.CSV`
   land; letters only in the stamp: a long digit run in a file name is fingerprinted `<fp:…>` in the run ledgers) and the Pipeline runs once
   (`runPipeline`, synchronous). The summary counts the ledger batches and quarantine entries the run added:
   `rowsIn` (`total_input_rows`, rejected rows included), `rowsRejected`, `quarantined:[{file, reason}]`,
   `rowsBefore`, `rowsAfter`, `rowsChanged`, `replaced`. A run that quarantined the file or loaded no row **fails**
   (422 "nothing was imported … nothing was kept").
4. The Dataset entry: `{physicalRef: <id>/database, description, origin: import, kind, importPipeline, importFormat,
   eventDate (= the link's time), columns:[{name, type: string|number|date|timestamp|boolean, classification?}],
   primaryEntityType, defaultLinkMapping, identifyingColumn}`; with sign-in `owner` is the importer, as provenance:
   no `shares` key, so the Dataset is shared with its Space like every other Dataset in it (`LA-ONBOARD-DEFECTS-1`,
   2026-10-23; it was `shares: []`, private to the importer, and no screen could share it).
5. Follow-on work **inside the same run** (DT-16) unless `pipeline.automation` is present (a Space whose Jobs do it;
   that feature does not exist yet, so every edition does it in-run today): the link index through the
   `LinkIndexBuilder` SPI (`index:{result: BUILT|UP_TO_DATE|REFUSED|FAILED|SKIPPED|NOT_AVAILABLE, edges, nodes, …}`,
   `edges` and `nodes` = what the index holds after the run, every entity once - after an append too; the owner
   is the importer, a full build is allowed) and every enabled Expectation whose target is the Pipeline
   (`expectations:[{name, status, violations}]`). Neither failing undoes the import; both are reported.

**A failed commit leaves nothing behind**: every write is undone in reverse (Dataset entry, data and inbox
directories, registration, Pipeline file, schema file), and the draft keeps its file, so the same draft commits once
the cause is fixed (`ControlApiOnboardFilesTest.aFailedCommitLeavesNothingBehind`, falsified by removing the
rollback). A 202 run is undone the same way in the background and records `failed` with the reason. The background
run uses the Space roots and service captured on the request thread, never "the current Space".

**Add a file** acts only on the Pipeline Add data wrote for that Dataset (`importPipeline` equal to the id and registered
from `imports/<id>_pipeline.toon`; the registry fields alone are editable, so a forged entry is 409), checks the file
has the import's format and carries the columns the schema reads (column count for delimited text, names otherwise;
an unreadable schema is 409). With `replace` it moves `database/` aside, lands, runs,
and drops the old data on success or restores it on failure; `append` adds rows. **Run now** runs whatever waits in
the inbox. Both answer the run summary with the in-run index and Expectations.

Tests: `inspecto/.../ControlApiOnboardFilesTest` (draft evidence and confidence, value shapes, commit, overrides,
Reference, failed commit, 202 and a failed 202, Add a file and Run now, the column route, limits, the Excel
refusal, NDJSON and Parquet, `data.self_import` without and with sign-in, armed 401/403/200) and
`la/inspecto-geo-link/.../ControlApiOnboardLinkAnalysisTest` (index BUILT in the run, `/inv/projection` with no
saved view, Entity profile links assessed from `defaultLinkMapping` and not without it, falsified by removing the
fallback in `EntityTypeBindings`).

### Onboarding counters (`OnboardingRoutes`; `LA-ONBOARD-1` OB-13, 2026-10-22)

A successful commit (inline or 202) records `first_dataset` once per installation in the onboarding counters, after
the undo stack is cleared, so a rolled-back commit counts nothing. The counters themselves - the file in the
server-wide document home, the closed vocabulary, `GET /onboarding/metrics` (`canAdminister`) and
`POST /onboarding/events` (open, rate-limited) - are described in the la-core
[architecture](../la-core/architecture.md) §6.11. `POST /spaces` records `space_created` the same way.
