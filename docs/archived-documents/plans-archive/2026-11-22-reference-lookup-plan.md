# Lookup — exact and best-match key resolution over a Reference Dataset

**State:** ✅ **DONE 2026-11-25 — P1–P4 built and archived; truth in [`okf/product/module/elt/user-guide-elt-record-transformer.md`](../../okf/product/module/elt/user-guide-elt-record-transformer.md) §8.7.**
Board row `LOOKUP-1` [items: module.elt, module.engine]. Hubs on ship: `okf/product/module/elt/` (authoring,
Step) and `okf/product/module/engine/` (execution, point engine).

## 1. Why

Transform functions need to resolve a key against reference data and get back the matched record or value —
both **exact match** and **best match** (longest prefix, range, as-of, fuzzy) — with the cache refresh,
versioning and observability that ETL tools give their lookup caches. Callers are both batch Pipelines and
row-at-a-time consumers (streaming, agents, API).

## 2. What already exists (reuse, do not rebuild)

| Piece | Where | What it gives |
|---|---|---|
| **Reference Dataset** (`GLOSSARY.md` §6-B) | `produces: reference`, `reference.load: replace\|upsert\|scd2` | the store, dedup, SCD2 history, delete tombstones |
| `ReferenceReader.sqlFor` / `versionedView` | `platform/inspecto-engine/src/main/java/com/gamma/enrich/ReferenceReader.java` | current view and as-of view; the one shared read rule |
| `transform.join` | `RowShaper.join` via `ReferenceResolver` (`platform/inspecto-engine/.../pipeline/exec/RowShaper.java`) | exact match only (`LEFT JOIN … USING`) |
| `transform.lookup` | `RowShaper.lookup`, `RecipeCompiler.lookup` | inline `key=value` `CASE` map; exact; small |
| `RecordTransform` catalog | `platform/inspecto-etl/src/main/java/com/gamma/etl/RecordTransform.java` | per-field functions a Fields row applies |
| `SnapshotSwapLock` | used by `ReferenceReader.sqlFor` | readers never see a half-compacted store |

**The gap** is (a) match modes beyond exact, (b) a lookup *function* a transform can call per field, (c) a
row-at-a-time engine, and (d) refresh, version and observability controls on the cached side. Batch
execution is set-based DuckDB, so on the batch side the "cache" is a materialized, indexed lookup table per
run — not a per-row call.

## 3. Operator decisions (2026-11-22)

- **D-LK1 Name:** **Lookup** (a Type) defined over a **Reference Dataset**; added to `GLOSSARY.md`. Never "cache"
  as the entity name — the cache is how a Lookup is held, not what it is.
- **D-LK2 Match modes in scope:** exact, longest prefix, range, as-of / nearest, fuzzy.
- **D-LK3 Sizes:** both small code tables and 1M–100M-row master data.
- **D-LK4 Callers:** batch Pipelines **and** row-at-a-time → two engines behind one Lookup definition, held
  equal by one parity suite.

## 4. Features

### 4.1 Definition
- Binds a Reference Dataset (`reference/<pipeline>`) or a file; declares key column(s), return column(s),
  match mode, not-found value, multi-match policy.
- Validated at save: key and return columns exist, the match mode fits the key type, refusals named.

### 4.2 Match modes
| Mode | Use | Batch engine | Point engine |
|---|---|---|---|
| `exact` | codes, IDs | hash join (today's `transform.join`) | hash map |
| normalized exact | trim, case-fold, strip leading zeros or `+` | normalize both sides with a declared normalizer | same normalizer, then hash map |
| `longest_prefix` | MSISDN → operator or country, IP CIDR, BIN → issuer | join on the prefix lengths present, keep the longest (`QUALIFY row_number()`) | prefix trie |
| `range` | number, date or IP bands `[from, to]` | range join; overlaps refused at load or ranked | interval tree / sorted array |
| `asof` / nearest | rate or price valid at time ≤ t | DuckDB `ASOF JOIN` | sorted-by-time per key |
| `pattern` | wildcards (`44*`, `^07\d+`) | ranked pattern list, most specific first | compiled pattern list |
| `fuzzy` | names, addresses | `jaro_winkler` / `levenshtein` with a blocking key (no N×M), threshold, top-k | n-gram or BK-tree candidate index |

- **Multi-match policy:** `first` (by a priority column) · `most_specific` · `all` (explicit fan-out) · `fail`.
- **Tiered keys:** an ordered fallback chain, e.g. `(country, prefix)` → `(prefix)` → not-found value.
- **Match metadata** on request: `__match_type`, `__matched_key`, `__match_score`.

### 4.3 Versioning and time travel
- Reuse SCD2: as-of run time, **as-of each row's event time** (point-in-time correct), or a pinned version
  (Consignment).
- Record the Reference version used in run lineage so a rerun reproduces the result.
- Business-effective rows (`valid_from` / `valid_to`) kept distinct from load versions.

### 4.4 Cache lifecycle
- **Load strategy:** `per_run` (default) · `shared` (kept warm across runs) · `on_demand` (lazy, LRU, for very
  large sets).
- **Invalidation:** on Reference Consignment commit (`ConsignmentEventBus`), TTL, manual refresh route.
- **Atomic swap:** build the new snapshot, then swap; readers never see a partial one (`SnapshotSwapLock`).
- **Incremental refresh** from upsert / scd2 deltas rather than a full rebuild.
- **Memory bounds:** a size cap, spill to an on-disk DuckDB table, refuse or warn past the cap.
- **Warm-up** of `shared` Lookups at service start, with readiness reported.

### 4.5 Quality and safety
- Load-time checks: duplicate keys (fail / keep latest / keep first), overlapping ranges, null keys, prefix
  conflicts.
- Not-found handling: default value, null, quarantine, or an Expectation on miss rate that raises an Incident.
- A test console: type a key, see which row matched and why.
- Scoped to its Space; files go through the existing reference allowlist and path jail.

### 4.6 Observability
- Per Lookup per run: hit, miss and multi-match counts, hit rate, cache size, load time, version used,
  staleness age.
- A sample of unmatched keys written beside the output for triage.

### 4.7 Authoring surface
- A `transform.match` Step (or `lookup:` extended) and a `lookup()` scalar in the `RecordTransform` catalog so a
  Fields row can call it.
- UI in the existing enrichment / Pipeline editor; Control API: list Lookups, stats, refresh, test a key.

## 5. Phases

1. **P1 — batch, all non-fuzzy modes:** Lookup definition and save validation; `transform.match` Step and
   `lookup()` scalar; exact, normalized, longest prefix, range, as-of; per-run materialization; multi-match
   policy; not-found value; hit / miss counts.
2. **P2 — versioning and lifecycle:** as-of event time, version used in lineage, duplicate / overlap /
   prefix-conflict checks at load, `shared` warm cache invalidated on Reference commit with atomic swap,
   incremental refresh.
3. **P3 — point engine:** JVM indexes per mode, memory cap and spill, warm-up and readiness, Control API
   test-a-key / stats / refresh, parity suite against the batch engine.
4. **P4 — fuzzy:** batch (blocking key, score, top-k), then point (candidate index); test console UI.

**P1d built 2026-11-23 (operator: "normalized exact"):** `normalize:` on the keyed join (`match: exact | longest_prefix`) —
an ordered list of `trim`, `lower`, `upper`, `strip_leading_zeros`, `strip_plus` applied to BOTH the Reference key and
every looked-up value; two Reference rows that collapse to one key are refused by the duplicates rule; refused on
`range`. `RowShaperTest`, `PipelineValidatorTest`. Still open in P1: as-of, the `lookup()` scalar, hit / miss counts.
**P1d — the join output model (proposed 2026-11-22, operator; built 2026-11-23, see below):** each looked-up number fetches its OWN set of columns
under its OWN names, and the user sees what a join adds before running it.
- **Per-input mappings with rename** — one Step, one pass over the Reference, a mapping per `on` column, each with its
  own column list and output names (`Reference column: output name`), so the output matches a legacy layout exactly:
  ```toon
  lookups[3]:
    - on: SERVED_MSISDN
      columns: {dial_digit_key: SERVED_MSISDN_DIAL_DIGIT_KEY, provider_desc: SERVED_MSISDN_OPER}
      default: {SERVED_MSISDN_DIAL_DIGIT_KEY: -99}
    - on: OTHER_MSISDN
      columns: {dial_digit_key: OTHER_MSISDN_DIAL_DIGIT_KEY, event_category_key: EVENT_CATEGORY_KEY}
    - …
  ```
  Today's `on` / `columns` / `as` stay valid as the shorthand for one mapping per `on` column.
- **"Columns this Step adds"** — a read-only table under the join in the Step form (output column · type · from ·
  looked up by), computed from the Reference's real columns; a clash or a missing column shows before saving. The same
  list feeds the next Step's pickers and records each added column's origin in the output Dataset's metadata (shared
  with the Job graph plan, JG2).
- **Optional match details** per mapping: `matched_key` (the key or prefix that matched) and `match_kind`
  (`exact` / `prefix` / `range` / `none`).
- **Several joins** read as such on the canvas: each join labelled *Reference · match*; *Add another lookup* inserts
  the next join (Job graph plan, JG2).
- **Hit / miss counts** per mapping per run (already in P1), with a sample of unmatched values.

**P1d built 2026-11-23 (backend + Step form):** `lookups:` (per-input mappings, rename maps, output-keyed `default`,
per-entry `as`) on every keyed `match`, one pass over the Reference; `details:` (`true` → `<prefix>matched_key` /
`<prefix>match_kind`, or a map naming them; a range's matched key is its period `from..to`); the shared planner
`RowShaper.joinMappings` behind both the run and `RowShaper.joinAddedColumns`, served by
`POST /components/transform/describe` with a `config` body (Reference columns from its Pipeline's declared schema, or
`referenceColumns`) and drawn as the read-only *Columns this Step adds* table in the join pane. Save refuses `lookups:`
mixed with the shorthand, an entry without `on` or with an unknown key, and two renamed outputs sharing a name
(`JOIN_KEYED_INVALID`); `lookups:` stands in for `on:` (validator and recipe). **Deliberately deferred:** the form has
no structured editor for `lookups:` (no list-of-maps control — it is kept as an extra setting and written in the
file); the table reflects the APPLIED settings, not unsaved edits; feeding the next Step's pickers and recording each
column's origin in the output Dataset's metadata stay with JG2; the per-mapping hit / miss counts with P1's counts.

**Known limits of P1c (open, from its review):** a `TIMESTAMPTZ` value against a `DATE` bound is turned into a date in
the server's session time zone, which the user guide does not say yet; a `DATE` value against text bounds stops with a
raw DuckDB error that does not name the Step (refuse it by name, or parse the bounds).

**Moved out (2026-11-22):** "as-of event time" and "version used in lineage" (P2) are now the core of
[`2026-11-22-dated-reference-runs-plan.md`](2026-11-22-dated-reference-runs-plan.md); the Job's own graph is
[`2026-11-22-job-graph-plan.md`](2026-11-22-job-graph-plan.md).

**P1e built 2026-11-23 (operator: "normalized exact"):** `normalize:` on the keyed join (`match: exact | longest_prefix`) —
an ordered list of `trim`, `lower`, `upper`, `strip_leading_zeros`, `strip_plus` applied to BOTH the Reference key and
every looked-up value; two Reference rows that collapse to one key are refused by the duplicates rule; refused on
`range`; the returned key column keeps the Reference's own text, while `matched_key` shows the NORMALIZED key. `RowShaperTest`, `PipelineValidatorTest`.
Still open in P1: the `lookup()` scalar, hit / miss counts.

**P1f built 2026-11-23 (operator: "as-of with a key"):** `match: asof` with `key:` (Reference equality column), `from:`
(its effective-from column; empty = since forever) and `at:` (the rows' time column) — each row takes the latest
Reference row of its key effective at or before its time; resolved once per distinct (key, time) pair. Decision: a
second input column rides in `at:` (one time for all `on` columns) rather than a pair per mapping; per-mapping `at:`
inside `lookups:` is not built. Single-table as-of with no key was already `range` with only `from:`. As built:
`user-guide-elt-record-transformer.md` §8.7. `RowShaperTest`, `PipelineValidatorTest`.
Still open in P1: nothing (P1 complete after P1h).

**P1g built 2026-11-23 (operator: "lookup() scalar and per-mapping hit and miss counts" — counts half):** a keyed join
counts, per mapping, rows that **hit** (found a Reference row) and **miss** (had a value, found none; a NULL value is
neither). Channel: Step Receipt `diag.counts` as `lookup.<on column>.hit|miss` — no new receipt field. The join writes
`<node>__lookup_stats`; the walk takes it (`RowShaper.takeLookupStats`) when the Step ends OK; the per-row hit
columns the join adds are dropped from the output. Not built: counts for the unkeyed (`USING`) join and the
`longest_prefix`-only legacy path; a sample of unmatched keys (§4.6); the UI reading `counts`.

**P2a built 2026-11-23 (operator: "P2 lifecycle", slice "Miss-rate Expectation"):** `max_miss_rate:` (0 to 1) on a keyed join stops the Step when, for any mapping, more than that share of looked-up values (hit + miss; NULLs excluded) find no Reference row — the stale-Reference detector, built on the P1g counts. The failed Step is what raises the Incident (the existing batch-failed path), with the counts in the message and no values. Decision: node-level, one threshold for every mapping, fail-only (no warn mode); a miss-rate Expectation proper (evaluated outside a run) is not built. Decision (assistant, 2026-11-23): the rest of P2 — shared warm cache, atomic swap, incremental refresh — waits for the P3 point engine, the only consumer (D-LK8); as-of event time and the version in lineage are in `2026-11-22-dated-reference-runs-plan.md`. `RowShaperTest`, `PipelineValidatorTest`, `NodeAttributesContractTest`.

**Form editor for `lookups:` built 2026-11-23 (operator: "form editor for lookups"):** `inspecto-join-lookups-editor` on the join Step's pane (`pipelines/join-lookups-editor.component.ts`, pure rows ↔ value in `join-lookups.ts`), replacing the JSON row in Additional config; same host pattern as the summarize editor (the pane owns Apply, dirty, and the merge into the extras). Stored shape unchanged; a default list `name=value` is written back as a map; an unreadable `lookups:` stays an extra row. Not driven against a live backend yet (specs only). Reference-column and input-column pickers are plain text (input columns suggested when a sample is parsed).

**P2b built 2026-11-23 (operator: "load-time quality report"):** the Reference's own quality as counts on the same `diag.counts` — `reference.rows`, `reference.null_keys`, and for `longest_prefix` `reference.nested_prefixes` (a key with a shorter key as a prefix; one hash probe per distinct key length, no N×M). Keyed `exact | longest_prefix | asof`; not `range`. Decisions (assistant): informational only, no fail threshold; "prefix conflict" read as nesting, since a repeated key is already refused by `duplicates`; counts only, NO sample — a sample of keys would be stored in the receipt, so it waits with the unmatched-key sample (§4.6) for a place that is access-controlled. Still open in P2: nothing in batch; the cache lifecycle waits for P3. `RowShaperTest`.

**Lookups screen built 2026-11-23 (operator: "Lookups screen", slice "Inventory + last-run counts"):** `GET /lookups`
(`LookupRoutes`, an open read) lifts each loaded Pipeline and lists every keyed `transform.join` — Pipeline, Step,
Reference, match, `lookedUpBy` — with `lastRun` from the newest non-simulated Step Receipt of that Pipeline and Step
(`DbConsignmentOutputStore.latestStep`): outcome, start, rows in, `diag.counts`, `truncated`. ⚠ The joins are read
from `PipelineLift.stageTwo(cfg)` — a keyed join runs only at rest, and the job runner's receipts carry that graph's
name `<pipeline>_stage2`, not the Pipeline's own (caught by the verification review; the test names its receipts that
way). A Pipeline with no at-rest chain (no `output_store:`) lists nothing, and canvas-store Pipelines run by a
`pipeline: <id>` Job are not listed. Drawn as *Workbench ▸
Lookups* (`modules/admin/lookups/`, read-only grid). Decisions (assistant): plain joins are not listed (no counts); first
500 + `total`; with receipts off `lastRun` is absent and `known.lastRun=false`; a truncated receipt shows no Found / Not
found sums rather than an understated one. Gap found: `StepReceipt.MAX_COUNTS = 8` cuts a join's counts past 3 mappings
(`reference.*` first) — not changed here. Not built: test-a-key, refresh, cache size, version used (P3). Not driven in
the preview. `ControlApiLookupsTest`, `lookups.component.spec.ts`.

**Unmatched-key sample built 2026-11-24 (operator: "unmatched-key sample and refresh"; slice "Fingerprint + shape",
refresh "Defer to P3"):** §4.6's sample, in the only form stored or served — never the value. The keyed join leaves
its top 20 distinct unmatched values per mapping in a session table (`<node>__lookup_misses`,
`RowShaper.takeLookupMisses`); the walk hands them to `WalkObserver.lookupMisses`; `StepReceiptRecorder` keeps them;
`PipelineJobRunner.flushLookupMisses` turns each into `LookupMissSample.Entry` (Space-salted
`FailureText.fingerprint` — the rejected rows' own — length, prefix-only shape, count) and replaces the Step's row set
in `lookup_misses` (`DbConsignmentOutputStore`, spec-db-layer §3.9-b). `GET /lookups` serves it as `unmatched`; the
Lookups screen shows it under a clicked row. Decisions (assistant): last run only (a clean run clears it); not for a
dry run; none for a canvas-store graph (no Pipeline config to salt from); shape keeps `min(4, length / 3)` characters.
Refresh stays with P3's shared cache (D-LK8: batch keeps no cache to refresh). `RowShaperTest`, `LookupMissSampleTest`,
`PipelineJobRunnerLookupMissesTest`, `PipelineJobRunnerTest.aKeyedJoinRunStoresItsUnmatchedSampleFingerprinted`,
`ControlApiLookupsTest`, `lookups.component.spec.ts`. Not driven in the preview.

**P3a — point engine and test-a-key built 2026-11-24 (operator: "P3", caller "Test-a-key API"):** P3 had no
row-at-a-time caller (D-LK8; the ingest lane refuses lookups), so its first caller is the test box. `PointLookup`
(`com.gamma.pipeline.exec`) builds from the keyed join's node config, reusing `RowShaper`'s own parsing
(`joinMappings`, `joinNormalizers`, `normalized`, widened to package scope) and the batch join's Reference SQL verbatim
(key normalized in SQL, `filter`, `duplicates` + `order_by`, `exact_when`), read once over a connection sealed by
`PipelineDryRun.seal`; a tested value is normalized by DuckDB through the same `normalized` SQL (a Java copy
disagreed on Unicode — no-break spaces, dotted I, final sigma, ß — found by verification), then probed in Java (hash map; for `longest_prefix` the key lengths present, longest
first, code points as DuckDB's `length`). Every returned value is held as text. `POST /lookups/test`
`{pipeline, step, value, refresh?}` (read-shaped exemption, value in the body) answers through it; `LookupRoutes`
keeps at most 8 indexes warm (LRU), rebuilt when the Step config changes or `refresh: true`. Decisions (assistant):
`exact` and `longest_prefix` first — `range` and `asof` refused by name; a memory cap by rows
(`-Dlookup.point.max_rows`, default 1,000,000) that refuses the build, no spill; no invalidation on Reference commit
— the answer carries `index.loadedAt` and the screen offers *Reload Reference and test*. Parity: `PointLookupParityTest`
(longest prefix + `exact_when` + default + details, exact, normalized exact and prefix with `filter`, duplicate pick)
asserts every added column equal to the batch join's for each value. Still open in P3: `range` / `asof` indexes, the
invalidation on Reference commit and atomic swap, a size-aware cap, readiness / warm-up at start, stats beyond the
answer's `index`. `PointLookupParityTest`, `ControlApiLookupsTest`, `lookups.component.spec.ts`. Not driven in the
preview.

**P3b — `range` and `asof` in the point engine built 2026-11-24 (operator: "Range + asof"):** the batch join compares
a value in its own type, casting only to DATE for a DATE range bound and to DATE or TIMESTAMP for such an as-of
from-column; the point engine casts in DuckDB there, and also to a TIMESTAMP range bound (DuckDB widens the
batch's typed DATE to midnight, which text comparison misses), and otherwise compares the text as it is — a number by its
exact value (`BigDecimal`, with `-inf` < finite < `inf` < `nan`), everything else by code point. (A first cut cast to
every bound's type, which rounded 9.5 into an INTEGER band and nulled 1e19; found by verification.) `range`: every row kept with its pick order (`row_number()` over the batch's own
`order_by` tie-break), the value checked against each row — a linear scan, fine for a test, not for a hot path;
`duplicates: refuse` reuses the batch overlap check, now `RowShaper.refuseRangeOverlap`. `asof`: the rows of each
(normalized) key; the latest from-date at or before the time wins, an undated row last; a missing time matches only
undated rows, as `from <= NULL` does in SQL. `POST /lookups/test` takes `at`; the screen shows a **Time** box for an
`asof` lookup. Found on the way: a CSV `path:` Reference is read as text, so numeric range bounds compare as text in
the batch join too (`10` < `9`) — recorded in the guide, not changed. Still open in P3: invalidation on Reference
commit with atomic swap, a size-aware cap, readiness / warm-up. `PointLookupParityTest` (numeric, from-only, overlap
pick and refusal, date ranges; asof with an undated row, a NULL time, normalize, repeated key-and-date refused and
picked), `ControlApiLookupsTest`, `lookups.component.spec.ts`. Not driven in the preview.

**P3c — the kept index follows its Reference, built 2026-11-24 (operator: "Auto-refresh"):** `PointLookup.referenceStamp`
stamps the files a Reference reads as its resolver declares them (`readDirs` / `readFiles`: the producing Pipeline's
database dir for `reference/<name>`, the one file for a path) — file count, bytes, newest modification time, no row
read. `POST /lookups/test` stamps before each answer and rebuilds when the stamp moved (stamped BEFORE the build, so a
commit landing mid-build is seen next time); the new index replaces the old in one map write, so a reader never sees a
half-built one. The answer's `index.reloaded` says why it built (`first use | reload asked | step changed | reference
changed`, null when kept) and `index.watchesReference=false` when no files are declared — then only a reload sees a
change. Decisions (assistant): a filesystem stamp, not a version id — it works for both reference kinds and needs no
registry read; a walk per test is cheap at a Reference's file count. Known limits (verification): a file replaced by one of the same size AND the same modification time (a copy that keeps the source time) is not seen — engine commits write new files, so a Reference Dataset commit is; an append-only store grows the walk until compaction. Still open in P3: a size-aware cap, readiness /
warm-up at start. `PointLookupParityTest.theReferenceStampChangesWithItsFilesAndIsNullWhenNoneAreDeclared`,
`ControlApiLookupsTest`, `lookups.component.spec.ts`. Not driven in the preview.

**P3d — memory cap and preload built 2026-11-24 (operator: "a memory cap based on size instead of row count, and
loading indexes before first use"); P3 is complete.** The row cap is gone: `PointLookup` estimates the heap each row
holds as it loads (64 bytes a row, 8 a value slot, a String's 40 plus two bytes a character — UTF-16, the worst case, so
the estimate errs high) and refuses past `-Dlookup.point.max_bytes` (default 256 MiB); the answer and the list carry
`bytes`. `-Dlookup.point.preload=true` starts a daemon thread at route registration that builds every keyed lookup's
index in Pipeline order, up to the 8 kept; a refusal is logged and kept, and the next lookup goes on. `GET /lookups`
gives each row `index {state: ready | building | failed | cold, rows, bytes, loadedAt, error}` — the build path shared
with `POST /lookups/test` (`LookupRoutes.warmUp`) maintains it — and the screen shows **Test index** and **Index
memory** columns. Decisions (assistant): the cap is per index (8 kept × 256 MiB is the worst case; a global budget was
not asked for); preload off by default, since it spends memory at start for lookups nobody may test; a Pipeline loaded
after start is not preloaded. `PointLookupParityTest`, `ControlApiLookupsTest` (cold → ready, preload ready, over-cap
failed), `lookups.component.spec.ts`. Not driven in the preview.

**P1h built 2026-11-23 (operator: "Yes, exact only"):** the `lookup()` scalar as the Record Transformer function
`lookup.reference` (category Lookup; params `reference`, `key`, `returns`, `normalize`), exact match only. It renders
the marker call `inspecto_lookup(...)`; `RowShaper.project` expands it into a scalar subquery over the resolved
Reference, the row's column qualified with the input table, `normalize` applied to both sides. A repeated key makes
DuckDB refuse the subquery (as `duplicates: refuse`). Decision: only on a pipeline `transform.sql` projection, which
has the Reference resolver; the ingest lane (`processing.map`) has none, so a lookup field there fails as an unknown
function `inspecto_lookup`, and a `sink.view`'s derived SQL skips a node carrying one. The UI's client-side SQL preview
cannot run it either. Not built: prefix/range/as-of on the scalar (use the join), a not-found default (wrap in
`logic.default_if_empty`). **Built 2026-11-23 (operator: "take on LOOKUP-1 items"):** a save refuses a lookup field
on the ingest lane — `ERR_LOOKUP_ON_INGEST` on `processing.map.fields[i].fn`, regardless of `active`, on every save
path (`SaveGate.ingestLookupFindings`, `ControlApiSaveGateParityTest`); a schema file's own `mapping.fields[]` saved
on its own is not judged. `RowShaperTest`,
`RecordTransformContractTest`, the TS contract (`sql-functions.contract.json`) regenerated; the TS spec was not run.

**P1c built 2026-11-22 (operator: "start range"):** `match: range` on the keyed join — `from`/`to` Reference
columns, inclusive bounds, an empty bound open-ended, compared in the value's own type; `filter` (a condition on
Reference rows, every keyed mode); overlapping periods refused by name unless `duplicates: first|last` + `order_by`.
Proven on the client's service-range table (SDR rate by call date): every November 2026 call gets the legacy
`getSdrValue` rate, and the default refusal surfaced the table's overlaps and an impossible date (31/06/26).

**P1b built 2026-11-22 (operator: "go and create a demo" on a client dialling-code table):** the keyed form —
`key:` names the Reference's key column and every `on` column is an independent input; `columns`, `as`,
`default`, `exact_when`, `duplicates` + `order_by`. Each distinct value is resolved once (5M rows × 3 numbers:
2.3 s). Proven on a local demo against the client's legacy `getDialDigit`: 6,000 of 6,000 numbers identical. A keyed
join saves as `steps:` only. **Decision D-LK8 (2026-11-22, operator asked "no in-memory cache?"):** batch Pipelines
keep no cache — the join's own per-run hash table is the cache and each run sees the current Reference; an in-memory
engine waits for a row-at-a-time caller (P3). As built: `user-guide-elt-record-transformer.md` §8.7.
**Fixed on the way (2026-11-22):** a Job's join could not bind `reference/<pipeline>` BY NAME in a running server —
`CollectorService` never called `JobService.pipelineConfigs(..)`, and `JobService` handed each runner the field's
value at registration (before the host sets it). Both fixed; `JobByNameReferenceJoinTest` boots the service and runs
the Job. The demo then read the client table as a versioned `scd2` Reference by name, 6,000 of 6,000 again.

**P1a built 2026-11-22 (operator: "longest_prefix, exact first"):** `transform.join` gained `match: exact |
longest_prefix` — exact is the unchanged default; longest prefix on the last `on` column, earlier columns equal;
duplicate Reference keys refused; carried by every save path (steps, `processing.join`, recipe, editor lower);
`JOIN_MATCH_UNKNOWN` at save. As built: `okf/product/module/elt/user-guide-elt-record-transformer.md` §8.7.
Still open in P1: normalized exact, range, as-of, the `lookup()` scalar, multi-match policy, not-found value,
hit / miss counts.

## 6. Decisions
- **D-LK5 (decided 2026-11-22, assistant under the operator's "go"):** extend **`transform.join`** with `match:`
  rather than add a `transform.match` node or widen `transform.lookup`. The join already binds a Reference
  Dataset through `ReferenceReader` (current and as-of views, one read rule) and takes the key columns; a new node
  type would touch six registries for the same inputs. `transform.lookup` stays the inline code map.
- **D-LK6 (decided 2026-11-22, same):** the definition is inline in the Step for now; a reusable Lookup file waits
  for a second consumer (the point engine, P3).

## 7. Verification
Golden cases per match mode (prefix ties, overlapping ranges, the as-of boundary, not-found, multi-match), as
`RowShaperTest`-style node tests; a refresh-during-read concurrency test after
`ReferenceScd2CharacterizationTest`; from P3 the same golden cases through both engines.
