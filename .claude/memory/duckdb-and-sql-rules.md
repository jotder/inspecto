---
name: duckdb-and-sql-rules
description: "DuckDB session TimeZone follows the HOST (ICU bundled), not UTC or -Dops.timezone; persisted SQL must store ingredients and render at read time; DuckDB 2.0.1.0 is pinned: lambda x: syntax, window_self_join disabled (wrong results), 2.0 parse-tree shape, similarity built-ins count code points"
metadata:
  type: reference
---

- **DuckDB TimeZone = JVM/host default** (ICU auto-loaded in `duckdb_jdbc`), so SQL `now()::TIMESTAMP` and Java
  `ZoneId.systemDefault()` agree today. It does NOT follow `-Dops.timezone`; moving a Java half to the operations
  zone needs an explicit `SET TimeZone` on the connection too. Probe the real driver rather than trusting
  "DuckDB defaults to UTC".
- **Persisted-then-executed SQL: store the ingredients, render at use.** A baked glob goes silently stale
  (double-counted superseded revisions); a baked file list breaks when retention deletes files. Views carry a
  `{{reader}}` token (`ViewReaderSql.READER_TOKEN`) rendered at read time; make placeholders invalid syntax so a
  forgetful executor fails loudly. Two branches of one method disagreeing about a rule is a defect signature.
- **DuckDB 2.0.1.0 is the pin (2026-11-21; 2.0 since 2026-10-23).** Write lambdas as `lambda x: …` (the `x -> …` arrow is a Binder error).
  A new analytics connection must go through `DuckDbUtil` (`openConnection` / `openInMemory` /
  `applyGlobalDuckDbSettings`) or call `DuckDbUtil.applyCorrectnessSettings`: 2.0.0's and 2.0.1's `window_self_join` makes a
  `PARTITION BY` aggregate over hive-pruned parquet count the pruned days. Code reading `json_serialize_sql` must read
  2.0's shape (`arguments[].expression`, `literal {kind, text}`) and fail CLOSED on a shape it does not know — the
  masking check failed open on exactly that. 2.0.1's `round(DOUBLE, n)` rounds the exact binary value (52.05 → 52.0): round a DOUBLE as
  `CAST(CAST(x AS DECIMAL(38,n)) AS DOUBLE)` (never `round(CAST(x AS DECIMAL(38,10)), n)` — double rounding), guard
  inf/NaN with `isfinite`. 2.0.1's `levenshtein` / `jaro*` / `jaccard` count code points (2.0.0 counted UTF-8 bytes), and its tzdata
  disagrees with the JDK on America/Havana from 2026-11-01. Facts and pins: `okf/product/module/engine/spec-duckdb.md` § *DuckDB 2.0.1.0*.

Long form, with the original incidents (from the retired `PROJECT_NOTES.md`, 2026-11-19): [spec-engine-gotchas.md](../../docs/okf/product/module/engine/spec-engine-gotchas.md).
