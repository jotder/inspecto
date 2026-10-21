---
type: Reference
title: Link analysis storage - spec
description: Classes, on-disk layout, build modes, invariants and failure handling of the link index.
resource: la/inspecto-la-storage/
tags: [module, la-storage, link-analysis, index, spec]
timestamp: 2026-10-19T00:00:00Z
---

# Link analysis storage - spec

Package `com.gamma.la.storage` in `la/inspecto-la-storage/`. Manifest
`la/inspecto-la-storage/src/main/resources/META-INF/inspecto/module.toon`: `buildRole: implementation`,
`offeringRole: optional`, requires `la-core`. Depends on DuckDB JDBC. No routes and no ServiceLoader registrations: it
is a library that [la-api](../la-api/spec.md) calls.

## Classes

| Class | Role |
|---|---|
| `IndexMapping` | edge mapping: source, destination, kind, time (+ IANA zone), weight, carried attributes; its hash names the index directory |
| `IndexStore` | versioned directories for one (Dataset, mapping): `stage()`, `publish()`, `current()`, `gc()` |
| `IndexBuilder` | full / append / compact build into a stage, verification, manifest |
| `IndexManifest` | `manifest.json` of one version; ignores unknown fields, refuses a higher `formatVersion` |
| `IndexPlan` | pure classification: what a version needs to cover the Dataset now (`full` / `append` / `compact`) |
| `IndexBuildService` | lifecycle: `QUEUED → RUNNING → COMPLETED / CANCELLED / FAILED`, bounded executor, disk budget, `retune` |
| `IndexReader` | one sealed `SqlSandbox` connection per request over one version |
| `IndexPins` | durable Draft pins in `pins.json`; `gc` never deletes a pinned version |
| `BucketFunction` | `bucket(id) = md5_number_lower(id) % N`, in SQL and in Java |
| `IndexedTraversal` | the recursive-paths walk answered from the index |
| `SqlGraphEngine` | index-backed `GraphEngine`: neighbourhood, ego network, seeds-only degree |
| `RoutingGraphEngine` | `GraphInput.Materialised` → in-memory engine, `GraphInput.IndexRef` → `SqlGraphEngine` |

## Layout

`<writeRoot>/la-index/<dataset>/<mappingHash>/vNNNNNN/` plus a `CURRENT` pointer and `pins.json`. A version holds
`manifest.json`, `out/bucket=B/*.parquet` (edges keyed by source), `in/...` (keyed by target) and `nodes/...`; zstd, row
group 100,000, sorted by (bucket, key, ts); columns `src, dst, kind, ts, w, a0..ak`. Deltas are `dNNN` files in the same
bucket directories; version N+1 hard-links its parent.

## Invariants

- **Publish order:** stage `vNNNNNN.tmp` → rename to `vNNNNNN` → atomic-move a new `CURRENT`. Any failure or cancel
  deletes the stage and leaves `CURRENT` untouched.
- **Trusted SQL:** the relation SQL comes from `DatasetProvider.relationSql` after the caller's view gate; the builder's
  own in-memory DuckDB is deliberately not sandboxed (`COPY ... TO` is forbidden in the sandbox). Column names, zone,
  memory limit and paths are validated or escaped.
- **Time:** `ts` is a naive UTC timestamp; a naive source column is read in the mapping's zone (UTC when none); the
  session time zone is pinned to UTC.
- **Bucket literal:** the reader computes the bucket in Java and writes it as a literal; the key is a bound parameter.
  The Java and SQL renderings are golden-tested to agree.
- **Append is sound only** when the manifest's input files are an unchanged subset of today's, the relation SQL, bucket
  function, bucket sizing and DuckDB version are unchanged, and at least one file is new. Deltas cap at
  `index.max_deltas` (8).
- **One live build per (Dataset, mapping)**; a duplicate is refused. A failed build stores the exception class only.
- **Disk budget:** with `maxDiskBytes > 0`, rows are counted and an estimate (`rows × 34 B`) above it is refused before
  any write; the count is time-bounded and a timeout refuses.
- **Build memory is bounded per bucket (`LA-INDEX-SCALE-MEASURE-1`, 2026-10-21):** the `nodes` table is folded ONE BUCKET AT A TIME
  from the just-written `out` and `in` (`IndexBuilder.copyNodes`, one `COPY` per non-empty bucket), so that phase's working set is
  a bucket (about `index.edges_per_bucket` edges) and not the whole relation; the link statistics' per-key distinct-neighbour count is
  `DISTINCT (key, neighbour)` then `count(*)`, not a grouped `count(DISTINCT)`. The files are the same as before (same rows, one
  `data_0.parquet` per non-empty bucket, same row groups and codec), pinned by `IndexNodesFoldTest` and `IndexLinkStatsParityTest`, which
  keep the replaced statements as references; the bound by the gated `IndexNodesMemBench`. Still global and unmeasured above 10^8
  edges: the `out` / `in` sorts and the statistics' distinct-links table and node-sized aggregates (degree table, quantiles).
- **Caps refuse, never reroute:** `SqlGraphEngine` throws `IndexCapExceeded`, `IndexedTraversal` throws on an
  over-cap frontier; the la-api caller then answers flat.
- **Parity:** `SqlGraphEngine` returns the same answer as the in-memory engine over the same graph (canonical-v1 order).

## Tests

`la/inspecto-la-storage/src/test/`; inventory in [tests-inventory](tests-inventory.md); the gated scale bench
`la/inspecto-la-storage/src/test/java/com/gamma/la/storage/IndexScaleBench.java` and the gated memory-bound bench `IndexNodesMemBench.java`.
