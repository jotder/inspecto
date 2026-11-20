---
type: Concept
title: Parsing — frontends, the parsing block, parser plugins, XML and ASN.1, Decode Profiles
description: Three parse mechanisms over one DuckDB backend, the read_csv reference, the other DuckDB-native readers, the parsing: block and per-format profiles, the decision matrix, the ParserPlugin framework, XML and ASN.1 ingest, drop-in parser jars, a pointer to Decode Profiles (spec-parsing-decode-profile.md), deferred items and BER hostile-input handling.
resource: platform/inspecto-etl/src/main/java/com/gamma/etl/StreamingFileIngester.java, platform/inspecto-engine/src/main/java/com/gamma/inspector/ConsignmentIngestor.java, platform/inspecto-etl/src/main/java/com/gamma/etl/PipelineConfig.java, platform/inspecto-engine/src/main/java/com/gamma/consignment, platform/inspecto-engine/src/main/java/com/gamma/inspector/ConcurrencyBroker.java, platform/inspecto-etl/src/main/java/com/gamma/etl/DuckDbCsvIngester.java, platform/inspecto-engine/src/main/java/com/gamma/parse/ParserPlugin.java
tags: [elt, engine, ingestion, spi, streaming, consignment, ingester, duckdb, seam, partitions, contract-test, plugins, segments, quarantine, route, branch, arming, fail-closed, lanes, addressing, revisions, watermark, selector, event-time, concurrency, scheduler, priority, backpressure, operations, parsing, grammar, csv, plugin, read-csv, frontend, options, asn1, xml, decode-profile]
timestamp: 2026-11-18T00:00:00Z
item: module.elt
audience: internal
status: current
verified: 2026-11-20 code-reviewed
code: platform/inspecto-etl
---

# Parsing — frontends, the parsing block, parser plugins, XML and ASN.1, Decode Profiles

> One of the ELT spec files, split out on 2026-11-18 (DOCS-HUB-NAMES-1); section numbers are unchanged. [spec-ingest.md](spec-ingest.md) · [spec-consignments.md](spec-consignments.md) · **spec-parsing.md**

**Contents**

- [P.1 The unifying model: a parser *frontend* feeding the DuckDB *backend*](spec-parsing-model-block.md#p1-the-unifying-model-a-parser-frontend-feeding-the-duckdb-backend)
- [P.2 What the engine exposes **today** vs. raw DuckDB](spec-parsing-model-block.md#p2-what-the-engine-exposes-today-vs-raw-duckdb)
- [P.3 Corrected & extended DuckDB `read_csv` parameter reference](spec-parsing-model-block.md#p3-corrected--extended-duckdb-read_csv-parameter-reference)
- [P.4 Beyond CSV — the other DuckDB-native readers](spec-parsing-model-block.md#p4-beyond-csv--the-other-duckdb-native-readers)
- [P.5 The `parsing:` block `[LIVE]`](spec-parsing-model-block.md#p5-the-parsing-block-live)
- [P.6 Per-format profiles (copy-paste starting points)](spec-parsing-profiles-plugins.md#p6-per-format-profiles-copy-paste-starting-points)
- [P.7 Decision matrix — pick a frontend](spec-parsing-profiles-plugins.md#p7-decision-matrix--pick-a-frontend)
- [P.8 Roadmap status and corrections to the PDF](spec-parsing-profiles-plugins.md#p8-roadmap-status-and-corrections-to-the-pdf)
- [P.9 Parser plugins — the self-describing Parser framework](spec-parsing-profiles-plugins.md#p9-parser-plugins--the-self-describing-parser-framework)
- [P.10 XML and ASN.1 — hierarchical parsers that ingest](spec-parsing-hierarchical-decode.md#p10-xml-and-asn1--hierarchical-parsers-that-ingest)
- [P.11 Drop-in parser jars — trust gate, staging dir, pack parsers, ingest and preview gate SHIPPED (2026-09-25)](spec-parsing-hierarchical-decode.md#p11-drop-in-parser-jars--trust-gate-staging-dir-pack-parsers-ingest-and-preview-gate-shipped-2026-09-25)
- [P.12 Decode Profile — one vendor's decode settings, kept once (2026-09-25, slices C1–C4)](spec-parsing-hierarchical-decode.md#p12-decode-profile--one-vendors-decode-settings-kept-once-2026-09-25-slices-c1c4)
- [P.13 Deferred by decision (the trust design's open items)](spec-parsing-hierarchical-decode.md#p13-deferred-by-decision-the-trust-designs-open-items)
- [P.14 BER hostile-input handling — fixed 2026-09-17, value cap 2026-09-24](spec-parsing-hierarchical-decode.md#p14-ber-hostile-input-handling--fixed-2026-09-17-value-cap-2026-09-24)

**What this covers.** Parsing is how the bytes of a file become rows. Inspecto pushes as many formats as
possible onto DuckDB's own fast readers (CSV, JSON, Excel, Parquet, fixed-width and regex-carved text) and
uses Java decoders only for binary or tree-shaped formats such as ASN.1 call records and XML. Whatever
reads the file, the same backend then types, maps, partitions and audits the rows. This part lists every
parse option the engine reads, gives copy-paste starting points per format, and describes the plugin
framework that lets a new format be deployed and configured with no UI change.

> ⚠ **"Frontend" names three different things.** As a *mechanism* there are three (DuckDB-native read,
> DuckDB-native + SQL regex, Java `StreamingFileIngester`). As a **legal `parsing.frontend` config
> value** there are ten tokens / eight distinct formats (`PipelineConfigParser.FRONTENDS`, §P.5).
> As a **registered `ParserPlugin` id** there are six built-ins (`BuiltinParsers.IDS`) plus any
> `ServiceLoader` providers, plus parsers from allowlisted Job Packs (2026-09-25; §P.11). Read "exactly three" as a mechanism claim, never as a config constraint.
> How the parsed rows are written (the ingest SPI, union/generation modes, segments) is
> [spec-ingest.md](spec-ingest.md). Authoring Grammars in the UI is
> [grammar-config](../config/user-guide-grammar-config.md) and the Parse pane is
> [the ELT user guide](user-guide-elt-parse-schema.md#61-a-parse-step-is-three-things-and-the-drawer-is-its-one-home-decision-2026-09-06) §6.1;
> the requirement of record is ING in [the ELT plan](plan-elt-scope-requirements.md#21-ingestion) §2.1. (The plan's old
> §3.3 *one `parsing:` block* is §P.5 here; its old §3.9 *Parse pane* is that user-guide §6.1.)

**Status legend** used throughout:
`[LIVE]` = exposed by the engine today · `[NATIVE]` = DuckDB can do it, engine doesn't surface it
yet · `[PLUGIN]` = reachable today via a `StreamingFileIngester` · `[PROPOSED]` = config grammar
in this doc, not yet implemented.

## Parts

This page is split by topic into 3 parts; each part lists the sections it holds.

1. **[The parser model, DuckDB readers and the parsing block](spec-parsing-model-block.md)** — P.1 The unifying model: a parser *frontend* feeding the DuckDB *backend* · P.2 What the engine exposes today vs. raw DuckDB · P.3 Corrected & extended DuckDB `read_csv` parameter reference · P.4 Beyond CSV — the other DuckDB-native readers · P.5 The `parsing:` block `LIVE`
2. **[Format profiles, decision matrix and parser plugins](spec-parsing-profiles-plugins.md)** — P.6 Per-format profiles (copy-paste starting points) · P.7 Decision matrix — pick a frontend · P.8 Roadmap status and corrections to the PDF · P.9 Parser plugins — the self-describing Parser framework
3. **[XML, ASN.1, drop-in jars, Decode Profiles and BER](spec-parsing-hierarchical-decode.md)** — P.10 XML and ASN.1 — hierarchical parsers that ingest · P.11 Drop-in parser jars — trust gate, staging dir, pack parsers, ingest and preview gate SHIPPED (2026-09-25) · P.12 Decode Profile — one vendor's decode settings, kept once (2026-09-25, slices C1–C4) · P.13 Deferred by decision (the trust design's open items) · P.14 BER hostile-input handling — fixed 2026-09-17, value cap 2026-09-24 · References — board rows: `PARSING-DERIVE-EDITOR-1`
