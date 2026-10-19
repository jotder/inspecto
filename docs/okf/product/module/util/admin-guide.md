---
type: Feature
title: "Utilities, DuckDB access, TOON helpers, module manifests — admin guide"
description: "The deployment-wide JVM flags this module reads, the files it writes and the boot failures it raises."
tags: [util, admin-guide]
timestamp: 2026-10-19T00:00:00Z
---

# Utilities, DuckDB access, TOON helpers, module manifests — admin guide

Full flag list for the product: [admin-guide-launch-flags](../../layer/platform/admin-guide-launch-flags.md)
and [ADVANCED_GUIDE](../../../../ADVANCED_GUIDE.md). The flags below are read by this module.

## Flags

| Flag | Default | Effect |
|---|---|---|
| `-Dprocessing.duckdb.memory_limit` | unset | Bootstrap default for connections whose config and the installed server value set no limit; with none of the three, a derived cap applies (`max(1 GiB, 0.40*RAM/4)`) |
| `-Dprocessing.duckdb.threads` | unset | Same, for DuckDB `threads` |
| `-Dprocessing.duckdb.temp_directory` | unset (no SET issued; the in-memory scratch default is `<java.io.tmpdir>/.duckdb_tmp`) | Spill directory |
| `-Dprocessing.duckdb.max_temp_directory_size` | unset | Spill size cap |
| `-Ddb.pool.process.size` | `20` | Connections in the shared PostgreSQL pool per process per server |
| `-Ddb.pool.size` | `10` | Per-store-family cap on concurrent borrows |
| `-Ddb.pool.timeoutMs` | built-in (min 250) | Borrow wait; a borrow that cannot be served fails instead of hanging |
| `-Dinspecto.topology` | `single` | `partitioned` when several processes share state: a store that cannot open its backend then fails the boot |
| `-Dinspecto.ducklake.catalog` / `-Dinspecto.ducklake.data` | unset | Shared lakehouse catalog URL and its data path; set both or neither |
| `-Dops.timezone` | JVM default | Operations zone for cron firing and the `$today` / `$yesterday` macros (not the data's zone) |

A non-numeric pool flag logs a WARN and uses the default.

## Files on disk

- DuckDB spill files under the spill directory above.
- A Space's secret keys in `<config root>.secrets/` (default Space: `<assist.write.root>.secrets/`). This
  directory is outside exported and shared trees; back it up separately, or encrypted secrets cannot be read
  after a restore.

## Boot failures

| Message names | Cause | Fix |
|---|---|---|
| `-Dinspecto.topology=… is not a topology` | Value other than `single` / `partitioned` | Use one of the two |
| `inspecto.ducklake.*` | Only one of the pair set, or a blank value | Set both, or remove both |
| `-Dops.timezone=… is not a zone this JVM can resolve` | Not an IANA id | e.g. `UTC`, `Asia/Kolkata` |

To see what each store family resolved to (database or an in-memory fallback), read `GET /health/details`.
