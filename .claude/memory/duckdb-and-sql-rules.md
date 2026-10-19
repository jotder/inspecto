---
name: duckdb-and-sql-rules
description: "DuckDB session TimeZone follows the HOST (ICU bundled), not UTC or -Dops.timezone; persisted SQL must store ingredients and render at read time; DuckDB 2.0.x adoption decided but unmerged"
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
- **DuckDB 2.0.x:** operator decided 2026-10-05 to adopt; branches written but unmerged and the alpha benchmarked
  slower on ingest. Read `docs/superpower/duckdb-2-adoption-plan.md` before touching `duckdb.version`.
