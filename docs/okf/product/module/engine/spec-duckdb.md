---
type: Concept
title: DuckDB Integration
description: Appender-based bulk ingest (~75× vs JDBC), thread auto-derivation, and reserved-word quoting.
resource: platform/inspecto-engine/src/main/java/com/gamma/inspector/DuckDbRecordSink.java
tags: [engine, duckdb, performance, appender, threads]
timestamp: 2026-06-28T00:00:00Z
---

# DuckDB Integration

The engine embeds DuckDB natively (requires the `--enable-native-access=ALL-UNNAMED` JVM flag — see
[build & run](../../layer/platform/spec-build-test.md)).

## Parts

This page is split by topic into 2 parts; each part lists the sections it holds.

1. **[DuckDB 2.0.0.0, the pinned version](spec-duckdb-version-pin.md)** — DuckDB 2.0.0.0 (pinned 2026-10-23, `DUCKDB-2-MIGRATION-1`)
2. **[Sealed connections, time zones and aggregate state](spec-duckdb-connections-state.md)** — Every ingest connection is sealed (`SEC-INGEST-EXPR-EXTERNAL-ACCESS-1`, 2026-09-30) · Every in-memory scratch connection is sealed by default (`ENGINE-INMEMORY-UNSEALED-1`, 2026-10-03) · The source time zone for temporal data · Aggregate state cannot be persisted and restored (§7.5 probe)
