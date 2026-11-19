---
type: Concept
title: Ingest — the ingestion SPI, ingesters, Consignment coordination and branch-aware ingest
description: How files are ingested into the DuckDB core: the one emit-based ingestion SPI and its one sink, union/generation execution modes, segment schemas and the two partition-declaration contracts, output layout, the shipped ingesters and the plugin author workflow, Consignment coordination, failover and graceful stop, write-path identity and output_per_member, branch-aware route: ingest, and the contract tests.
resource: platform/inspecto-etl/src/main/java/com/gamma/etl/StreamingFileIngester.java, platform/inspecto-engine/src/main/java/com/gamma/inspector/ConsignmentIngestor.java, platform/inspecto-etl/src/main/java/com/gamma/etl/PipelineConfig.java, platform/inspecto-engine/src/main/java/com/gamma/consignment, platform/inspecto-engine/src/main/java/com/gamma/inspector/ConcurrencyBroker.java, platform/inspecto-etl/src/main/java/com/gamma/etl/DuckDbCsvIngester.java, platform/inspecto-engine/src/main/java/com/gamma/parse/ParserPlugin.java
tags: [elt, engine, ingestion, spi, streaming, consignment, ingester, duckdb, seam, partitions, contract-test, plugins, segments, quarantine, route, branch, arming, fail-closed, lanes, addressing, revisions, watermark, selector, event-time, concurrency, scheduler, priority, backpressure, operations, parsing, grammar, csv, plugin, read-csv, frontend, options, asn1, xml, decode-profile]
timestamp: 2026-11-18T00:00:00Z
item: module.elt
audience: internal
status: current
verified: 2026-11-20 last-edit
code: platform/inspecto-etl
---

# Ingest — the ingestion SPI, ingesters, Consignment coordination and branch-aware ingest

> One of the ELT spec files, split out on 2026-11-18 (DOCS-HUB-NAMES-1); section numbers are unchanged. **spec-ingest.md** · [spec-consignments.md](spec-consignments.md) · [spec-parsing.md](spec-parsing.md)

**Contents**

- [Plugin ingester](spec-ingest-plugin-ingester.md#plugin-ingester)
- [Execution modes — the framework picks by file size](spec-ingest-plugin-ingester.md#execution-modes--the-framework-picks-by-file-size)
- [3. Segment schemas and the partition declaration](spec-ingest-plugin-ingester.md#3-segment-schemas-and-the-partition-declaration)
- [Pipeline config (plugin)](spec-ingest-plugin-ingester.md#pipeline-config-plugin)
- [5. Output layout](spec-ingest-plugin-ingester.md#5-output-layout)
- [6. Shipped ingesters](spec-ingest-plugin-ingester.md#6-shipped-ingesters)
- [7. Plugin author workflow](spec-ingest-plugin-ingester.md#7-plugin-author-workflow)
- [8. Consignment coordination](spec-ingest-coordination-failover.md#8-consignment-coordination)
- [9. Failover and graceful stop](spec-ingest-coordination-failover.md#9-failover-and-graceful-stop)
- [10. Write-path identity and output naming](spec-ingest-coordination-failover.md#10-write-path-identity-and-output-naming)
- [11. Branch-aware ingest — `route:` on the poll-driven path](spec-ingest-branch-aware.md#11-branch-aware-ingest--route-on-the-poll-driven-path)
- [12. Contract tests](spec-ingest-branch-aware.md#12-contract-tests)

**What this covers.** Ingest is the step where a landed file is read, shaped and written as partitioned
Table files. DuckDB does the loading and writing for every format; formats DuckDB cannot read are decoded
in Java by a plugin that feeds records in. This part explains that plugin contract, how big and small
files are handled, how files are grouped into Consignments and committed (including what happens when a
node dies or a Space stops mid-run), how output files are named so a retry does not duplicate rows, and
how a Pipeline with `route:` branches writes each branch on the ordinary ingest path.

> Vocabulary: a Consignment is the unit a Step's token references (data resolved by reference; edges
> carry no records) — the full runtime edge model converges at Phase 7
> ([spec-pipeline-model.md](spec-pipeline-model-node-types.md#the-token-model--the-design-of-record) § *The token model*). The parse mechanisms and the
> `parsing:` block are [Part III](spec-parsing.md) below; acquisition (getting files to the inbox) is
> [spec-acquisition.md](spec-acquisition.md) and [architecture-elt.md](architecture-elt.md).

## Parts

This page is split by topic into 3 parts; each part lists the sections it holds.

1. **[The plugin ingester, modes, schemas and shipped ingesters](spec-ingest-plugin-ingester.md)** — Plugin ingester · Execution modes — the framework picks by file size · 3. Segment schemas and the partition declaration · Pipeline config (plugin) · 5. Output layout · 6. Shipped ingesters · 7. Plugin author workflow
2. **[Consignment coordination, failover and write-path identity](spec-ingest-coordination-failover.md)** — 8. Consignment coordination · 9. Failover and graceful stop · 10. Write-path identity and output naming
3. **[Branch-aware ingest and contract tests](spec-ingest-branch-aware.md)** — 11. Branch-aware ingest — `route:` on the poll-driven path · 12. Contract tests · References
