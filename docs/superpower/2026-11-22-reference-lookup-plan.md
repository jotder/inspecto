# Lookup — exact and best-match key resolution over a Reference Dataset

**State:** 🟡 **PROPOSED 2026-11-22 — feature set and phasing approved by the operator; nothing built; P1 startable.**
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

## 6. Open before P1 starts
- **D-LK5** Step shape: a new `transform.match` node, or extend `transform.lookup` to bind a Reference Dataset.
- **D-LK6** Where a Lookup definition lives: inline in the Step, or its own config file reusable across
  Pipelines.

## 7. Verification
Golden cases per match mode (prefix ties, overlapping ranges, the as-of boundary, not-found, multi-match), as
`RowShaperTest`-style node tests; a refresh-during-read concurrency test after
`ReferenceScd2CharacterizationTest`; from P3 the same golden cases through both engines.
