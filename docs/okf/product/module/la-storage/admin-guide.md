---
type: Guide
title: Link analysis storage - admin guide
description: Configure, size, operate and troubleshoot the Link Analysis link index.
resource: la/inspecto-la-storage/
tags: [module, la-storage, link-analysis, index, admin-guide]
timestamp: 2026-10-19T00:00:00Z
---

# Link analysis storage - admin guide

The link index needs no install step of its own: the jar ships in every edition with la-core and la-api. Design:
[architecture](architecture.md).

## Where it lives on disk

`<writeRoot>/la-index/<dataset>/<mappingHash>/` per Space: version directories `vNNNNNN/`, a `CURRENT` pointer and
`pins.json`. Crashed builds leave `vNNNNNN.tmp` stages that `gc` removes. Back it up with the Space write root, or treat
it as derived data: any index can be rebuilt with a `full` build from the Dataset.

⚠ Do not hand-edit `CURRENT` or `pins.json`: an unreadable `pins.json` makes `gc` delete nothing (fail closed).

## Configuration

All keys are Link Analysis limits (catalogue `platform/inspecto-entity-store/src/main/java/com/gamma/entitystore/LaLimit.java`),
edited through `PUT /settings/link-analysis/limits` (SPACE) or `PUT /system/link-analysis/limits` (INSTALL).

| Key | Layer | Default | Effect |
|---|---|---|---|
| `index.enabled` | SPACE (`link-analysis.toon`) | on | only an explicit `false` disables index reads |
| `index.max_disk_bytes` | SPACE | 0 (no limit) | builds whose estimate (`rows × 34 B`) exceeds it are refused 422 |
| `index.keep_versions` | SPACE | 2 | versions `gc` keeps beside the live one |
| `index.max_age_hours` | SPACE | 24 | a Dataset without file fingerprints is served from the index only while the build is younger |
| `index.traversal_depth` / `index.frontier_cap` | SPACE | 2 / 20 | beyond these, reads fall back to the flat path |
| `index.max_deltas` | SPACE | 8 | then `append` is refused and `compact` advised |
| `index.threads` / `index.queue` | INSTALL | 1 / 4 | build workers and waiting line; a full queue is 503 |
| `index.build_memory_pct` | INSTALL | 80 | DuckDB memory limit of a build, as a share of physical RAM |
| `index.edges_per_bucket`, `index.min_buckets`, `index.max_buckets` | INSTALL | 4,000,000 / 16 / 1,024 | bucket sizing; a change forces the next build to be `full` |

Memory, bucket sizing and pool sizes apply to the next build (`retune`); running builds finish under the old values.

## Operate

- **Build:** `POST /inv/index/builds` (capability `canBuildLinkIndex`), or schedule the `la.index.build` Job (needs the
  `link-index` Platform Service from geo-link; `full` builds only when the Job allows them).
- **Watch:** `GET /inv/index` shows versions, sizes, row counts, dropped NULL endpoints, staleness and the advised mode;
  `GET /inv/index/builds/{id}` shows progress, including `waiting_for_ingest`.
- **Cancel:** `POST /inv/index/builds/{id}/cancel`; the stage is deleted and `CURRENT` is untouched.
- A build waits while ingest runs and fails with `IngestStillActiveException` after 6 hours of waiting.

## Troubleshoot

| Symptom | Cause / fix |
|---|---|
| 409 on build submit | a build of the same (Dataset, mapping) is already queued or running; or `append` refused (bucket sizing changed, deltas at cap) |
| 422 on build submit | disk estimate above `index.max_disk_bytes`, or the estimate timed out |
| 503 on build submit | queue full: raise `index.queue` / `index.threads` |
| Build `FAILED` with only a class name | by design (a message could quote data); check the server log for the stack |
| Reads report `stale_unknown` | view-backed Dataset whose index is older than `index.max_age_hours`: rebuild |
