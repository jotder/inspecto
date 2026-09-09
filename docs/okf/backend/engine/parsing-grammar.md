---
type: Concept
title: Parsing & Grammar
description: Three parse mechanisms over one DuckDB backend; CSV knobs, delimited grammar files, plugin ingesters.
resource: inspecto-etl/src/main/java/com/gamma/etl/DuckDbCsvIngester.java
tags: [engine, parsing, grammar, csv, plugin]
timestamp: 2026-06-28T00:00:00Z
---

# Parsing & Grammar

**Three frontend mechanisms, one backend.** (⚠ The config's `frontend:` *token* set is finer — one per
format; see [ingestion.md](../../capabilities/ingestion/ingestion.md) §3.3.) All formats converge on the same DuckDB backend (mapping + transform +
partition + lineage). The frontends:

1. DuckDB-native `read_csv` — delimited files, SQL*Plus dumps.
2. `read_json`/`read_text` + SQL regex — JSON, fixed-width, LDIF, key-value, flat XML.
3. The [`StreamingFileIngester`](ingestion.md) plugin — binary, ASN.1/BER-TLV, proprietary CDRs, complex XML.

**Live CSV knobs** (via `DuckDbCsvIngester.buildReadSpec`): `delimiter`, `has_header` + `skip_header_lines`,
and an `engine` selector (`auto`/`duckdb`/`java`). Files are read all-VARCHAR; typing happens at transform
time via `TRY_STRPTIME`. The `auto` engine uses native `read_csv` unless `skip_junk_lines`/`skip_tail_lines`/
`skip_tail_columns` force the Java `CsvIngester` path (messy files). Native is ~4–5× faster.

> ⚠ **The paragraph below is the 4.1 account and is superseded** (flagged 2026-09-08): since 2026-07-07 a Grammar lives
> **inline** on its parse Step in the unified `parsing:` block; `processing.csv_settings` is a read alias `parsing:` wins
> over; a reusable Grammar is a **Grammar Template** in the `grammar` component kind — a copy source, never a binding
> (2026-08-15). Requirement of record: [ING capability spec](../../capabilities/ingestion/ingestion.md) §3.3, §3.9.

**Delimited grammar** (v4.1) — a reusable `*.grammar.toon` externalises per-data-file-type parse settings
(`delimiter`, `quote`, `escape`, `encoding`, `compression`, `strict_mode`, `null_strings`, boundary pre-scan)
from the pipeline `.toon`. One grammar serves one data-file type; many event-type schemas can reference it.
Backward-compatible with inline `processing.csv_settings:`. Implemented in `PipelineConfig.resolveGrammar`,
`CsvSettings`, `DuckDbCsvIngester.readOptions`/`filterWhere`, and `BoundaryScanner`.

A grammar can also carry **row-level filters** injected post-parse as WHERE predicates —
`include_prefixes` (anchored prefix allow-list), `include_regex` (allow), `exclude_regex` (deny). ⚠ Author
scalar lists in TOON with the tabular form `key[N]: v1, v2` — JToon reads a YAML-style `["a","b"]` as one
literal string; omit the key entirely for an empty list.

**Plugin ingesters** are declared in the pipeline `.toon` (`processing.ingester:` + `processing.segments:`);
the class must be on the fat-JAR classpath with a public no-arg constructor, and segment keys must match the
schema. Grammars/schemas are also editable as reusable [components](../components/component-registry.md).
Authoritative docs: [`parsing-options-reference.md`](../config/parsing-options-reference.md),
[`plugins.md`](plugins.md); the delimited-grammar design of record (shipped 4.1) is archived at
[`delimited-grammar-design.md`](../../../archived-documents/plans-archive/delimited-grammar-design.md).
