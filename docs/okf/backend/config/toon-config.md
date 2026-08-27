---
type: Concept
title: TOON Configuration
description: ConfigCodec (JToon), the three config file types, PipelineConfigParser, and the tabular-array serialization gotcha.
resource: inspecto-config/src/main/java/com/gamma/config/io/ConfigCodec.java
tags: [config, toon, jtoon, parser, gotcha]
timestamp: 2026-06-28T00:00:00Z
---

# TOON Configuration

All configuration is **TOON** (`.toon`), parsed via JToon. Authoritative key reference: [`configuration.md`](configuration.md).

* **`ConfigCodec`** (`inspecto-config/src/main/java/com/gamma/config/io/ConfigCodec.java`) — thin JToon wrapper:
  `toMap` (lenient, tolerates `#` comments), `toMapStrict` (canonical assertion), `toToon` (canonical encode).
  **Gotcha**: `toToon` does **not** emit tabular-array format — a Java-constructed schema whose `fields`/
  `rules` are `List<Map>` round-trips as nested maps and the parser then throws *"Array length mismatch:
  declared N, found 0"*. Write test schemas as inline TOON strings, not via `toToon(schemaMap)`; round-trip is
  only safe when the map was originally `JToon.decode`-d. See [gotchas](../gotchas/cross-cutting.md).
* **`PipelineConfigParser`** (`inspecto-etl/src/main/java/com/gamma/etl/PipelineConfigParser.java`,
  package-private) — parses a decoded map into an immutable `PipelineConfig` (entry points
  `PipelineConfig.load(path)` / `fromMap(map)`). Pure parse, no filesystem side-effects (`prepare()` does
  those).
  **Navigating it:** `parse()` is a sequence of `// ── section ──` blocks, one per config block, in the
  order the file declares them. The seven largest are named methods — `parseTransformBlocks`,
  `parseParsing`, `parseOutputAndSinks`, `parseSteps`, `parsePlugin`, `parseSchemas`, `parseCollector` —
  leaving `parse()` itself at ~280 lines (was 801). ⚠ The split is by **state**, not size: a section became
  a method only when it shares no locals with what follows. The head (identity/gates) and the
  `processing`/`dirs` sections deliberately stay inline because their locals (`proc`, `dirs`) are read
  throughout. `parseCollector` returns a `Collector`; `parseParsing` returns a private `Grammar` record
  because five of its locals are read by `parsePlugin` further down.
  **Schema-reference resolution (W1b, 2026-07-31): config-relative first, JVM CWD second.** A relative
  `schema_file` / `schemas[].schema_file` / `parsing.plugin.segments` value is resolved against **the config
  file's own directory** if it exists and stays inside it, otherwise against the **JVM CWD** — so a bare
  `orders_schema.toon` beside its pipeline is portable (the space tree can be moved, renamed, or imported
  under a new name with no edits), while every legacy `spaces/<id>/config/...` value keeps loading unchanged.
  `fromMap(map)` has no directory, so it takes the CWD branch only.
  ⚠ `grammar` and `dirs.*` are **still CWD-only** — they were not part of W1b.
  ⚠ The config-relative branch is contained (a `../` escape is skipped, not resolved); the CWD branch is
  **not** jailed and is explicitly not a security boundary (see [gotchas](../gotchas/cross-cutting.md) and
  `BACKLOG.md` §6).

## The three config file types per source

| File | Key groups |
|---|---|
| `<src>_gen.toon` | `csv_settings` (delimiter, engine, skip_* lines), `type_patterns` (dates/timestamps) |
| `<src>_schema.toon` | `raw.fields[]` (name/selector/type), `mapping.rules[]` (targetColumn/sourceExpression/transformType), `partitions[]` |
| `<src>_pipeline.toon` | `name`, `active`, `dirs.*`, `output.format/compression`, `processing.*` (threads, batch, csv_settings, schema_file, streaming, ingester/segments), `source:` acquisition block |

No `#` comments are allowed in files the strict parser handles. Writes go through
[`ConfigSafetyValidator`](config-safety.md).
