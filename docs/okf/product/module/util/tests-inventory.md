---
type: TestsInventory
title: Utilities, DuckDB access, TOON helpers, module manifests — tests inventory
item: module.util
description: Generated list of the test classes for module.util, with how to run them.
tags: [tests, module]
---

# Utilities, DuckDB access, TOON helpers, module manifests — tests inventory (`module.util`)

> **Generated** by `tools/render-tests-inventory.mjs` from the git-tracked test sources under `platform/inspecto-util/src/test/` — never edit this table; add or
> rename tests and re-run the tool. Hub: [index](index.md) · Product Map Item
> [`module.util`](../../../../../product-map/module/util.toon).

36 test class(es): 36 unit, 0 real-HTTP control-plane, 0 IT.
Kind is inferred: `*IT` → IT; a `ControlApi*` class, or one using both `HttpClient` and `ControlApi` → real-HTTP
control-plane test; else unit.

Run the whole module, or chosen classes (commas between classes, never `+`):

```bash
mvn -o test -pl platform/inspecto-util
mvn -o test -pl platform/inspecto-util -Dtest=ModuleBuildIdTest,ModuleManifestsTest
```

| test class | package | kind | file | summary |
|---|---|---|---|---|
| `ModuleBuildIdTest` | `com.gamma.module` | unit | `platform/inspecto-util/src/test/java/com/gamma/module/ModuleBuildIdTest.java` | MODULE-REORG-1 P3a: the build-id gate, over real (synthetic) jars. An absent or {@code dev} stamp is never a mismatch. |
| `ModuleManifestsTest` | `com.gamma.module` | unit | `platform/inspecto-util/src/test/java/com/gamma/module/ModuleManifestsTest.java` |  |
| `BoundedHistoryTest` | `com.gamma.util` | unit | `platform/inspecto-util/src/test/java/com/gamma/util/BoundedHistoryTest.java` |  |
| `CanonicalHashTest` | `com.gamma.util` | unit | `platform/inspecto-util/src/test/java/com/gamma/util/CanonicalHashTest.java` | {@link CanonicalHash} — the §3.4.3 schema-fingerprint hash. The contract that matters: key order must |
| `ConditionsTest` | `com.gamma.util` | unit | `platform/inspecto-util/src/test/java/com/gamma/util/ConditionsTest.java` | The A2 condition grammar (rbac-abac-plan §4): parse-once predicates over nested attribute maps. |
| `ConnectionSourceTest` | `com.gamma.util` | unit | `platform/inspecto-util/src/test/java/com/gamma/util/ConnectionSourceTest.java` | The {@link ConnectionSource} seam — scale-out phase A's connection pool ({@code OPS-03}). |
| `CronExpressionTest` | `com.gamma.util` | unit | `platform/inspecto-util/src/test/java/com/gamma/util/CronExpressionTest.java` | Tests for the dependency-free {@link CronExpression} parser + next-fire calculator. |
| `CsvLedgerTest` | `com.gamma.util` | unit | `platform/inspecto-util/src/test/java/com/gamma/util/CsvLedgerTest.java` |  |
| `CsvTest` | `com.gamma.util` | unit | `platform/inspecto-util/src/test/java/com/gamma/util/CsvTest.java` |  |
| `DottedPathTest` | `com.gamma.util` | unit | `platform/inspecto-util/src/test/java/com/gamma/util/DottedPathTest.java` | Unit tests for the shared dotted-path walker (event-signal-backbone-plan §4.4) — the one resolver |
| `DuckDbInMemorySealTest` | `com.gamma.util` | unit | `platform/inspecto-util/src/test/java/com/gamma/util/DuckDbInMemorySealTest.java` | {@code ENGINE-INMEMORY-UNSEALED-1}: {@link DuckDbUtil#openInMemory} is sealed by default — no extension |
| `DuckDbPendingQueryPreambleTest` | `com.gamma.util` | unit | `platform/inspecto-util/src/test/java/com/gamma/util/DuckDbPendingQueryPreambleTest.java` | {@link DuckDbUtil#withoutPendingQueryPreamble} against the REAL driver, so a DuckDB upgrade that rewords |
| `DuckDbSettingsTest` | `com.gamma.util` | unit | `platform/inspecto-util/src/test/java/com/gamma/util/DuckDbSettingsTest.java` | Covers the 3.10.0 scratch-relocation primitives: the temp DB is created in the requested |
| `FileBackupTest` | `com.gamma.util` | unit | `platform/inspecto-util/src/test/java/com/gamma/util/FileBackupTest.java` |  |
| `FileMoverByDateTest` | `com.gamma.util` | unit | `platform/inspecto-util/src/test/java/com/gamma/util/FileMoverByDateTest.java` | {@link FileMoverByDate}: date-pattern moves, dry-run, duplicate skip, source validation. <!-- vocab-allow: quoted Javadoc --> |
| `FileWalkerTest` | `com.gamma.util` | unit | `platform/inspecto-util/src/test/java/com/gamma/util/FileWalkerTest.java` |  |
| `IngestActivityTest` | `com.gamma.util` | unit | `platform/inspecto-util/src/test/java/com/gamma/util/IngestActivityTest.java` | CL-12's ingest signal: a scope counts while open, a second close does not count twice. |
| `IntegratedProcessorTest` | `com.gamma.util` | unit | `platform/inspecto-util/src/test/java/com/gamma/util/IntegratedProcessorTest.java` | {@link IntegratedProcessor}: extract 'unknown' archives, move dated members, sentinel idempotency. |
| `LakehouseCatalogTest` | `com.gamma.util` | unit | `platform/inspecto-util/src/test/java/com/gamma/util/LakehouseCatalogTest.java` | The shared-catalog config surface (scale-out phase C, §5.4 bullet 5). |
| `LockingRunnerTest` | `com.gamma.util` | unit | `platform/inspecto-util/src/test/java/com/gamma/util/LockingRunnerTest.java` |  |
| `MappingCsvTest` | `com.gamma.util` | unit | `platform/inspecto-util/src/test/java/com/gamma/util/MappingCsvTest.java` | {@link MappingCsv} — the shared Mapping CSV shape (ELT amendment §3.2, Phase 1). |
| `NoRawInMemoryDuckDbOpenContractTest` | `com.gamma.util` | unit | `platform/inspecto-util/src/test/java/com/gamma/util/NoRawInMemoryDuckDbOpenContractTest.java` | {@code DUCKDB-INMEMORY-SCRATCH-UNCAPPED-1} — no production code opens an in-memory DuckDB except through |
| `OperationsZoneTest` | `com.gamma.util` | unit | `platform/inspecto-util/src/test/java/com/gamma/util/OperationsZoneTest.java` | The operations zone — the one the scheduler fires on and {@code $today} resolves in. |
| `SchedulerTest` | `com.gamma.util` | unit | `platform/inspecto-util/src/test/java/com/gamma/util/SchedulerTest.java` |  |
| `SchemaExtractorFieldListTest` | `com.gamma.util` | unit | `platform/inspecto-util/src/test/java/com/gamma/util/SchemaExtractorFieldListTest.java` | MAPPING-GEN-1 (decided 2026-09-10): {@code create-schema} — the only generator that writes to disk — must |
| `SchemaExtractorMergeTest` | `com.gamma.util` | unit | `platform/inspecto-util/src/test/java/com/gamma/util/SchemaExtractorMergeTest.java` | Tests for {@link SchemaExtractor}'s description merge/preserve (P1): regeneration must never |
| `SharedPoolConnectionSourceTest` | `com.gamma.util` | unit | `platform/inspecto-util/src/test/java/com/gamma/util/SharedPoolConnectionSourceTest.java` | The ONE-shared-pool-per-process connection budget (operator decision 2026-10-06), driven OFFLINE over in-memory |
| `SqlIdentTest` | `com.gamma.util` | unit | `platform/inspecto-util/src/test/java/com/gamma/util/SqlIdentTest.java` | Pins the exact semantics of the one SQL-quoting primitive (SQLIDENT-NINE-COPIES-1). |
| `StoreHealthTest` | `com.gamma.util` | unit | `platform/inspecto-util/src/test/java/com/gamma/util/StoreHealthTest.java` | {@link StoreHealth} — the per-space record of what each operational store family actually resolved to. |
| `StructureCsvTest` | `com.gamma.util` | unit | `platform/inspecto-util/src/test/java/com/gamma/util/StructureCsvTest.java` | {@link StructureCsv} — the Schema structure CSV shape (ELT amendment §3.2 first table, STRUCTURE-CSV-1). |
| `TarExtractorTest` | `com.gamma.util` | unit | `platform/inspecto-util/src/test/java/com/gamma/util/TarExtractorTest.java` | {@link TarExtractor}: extract 'unknown' archives, CSV report, sentinel rerun, base validation. |
| `TarUtilTest` | `com.gamma.util` | unit | `platform/inspecto-util/src/test/java/com/gamma/util/TarUtilTest.java` | {@link TarUtil#extractTar}'s containment — the archive-extraction family PATH-2 left unpinned. |
| `ToonHelperTest` | `com.gamma.util` | unit | `platform/inspecto-util/src/test/java/com/gamma/util/ToonHelperTest.java` | {@link ToonHelper#load} / {@link ToonHelper#decode} are the one TOON decode seam |
| `TopologyTest` | `com.gamma.util` | unit | `platform/inspecto-util/src/test/java/com/gamma/util/TopologyTest.java` | {@link Topology} — {@code -Dinspecto.topology}, and its refusal to guess. |
| `ValuesTest` | `com.gamma.util` | unit | `platform/inspecto-util/src/test/java/com/gamma/util/ValuesTest.java` | Pins the exact semantics of each consolidated helper — the historical per-file copies differed |
| `EgressPolicyTest` | `com.gamma.util.egress` | unit | `platform/inspecto-util/src/test/java/com/gamma/util/egress/EgressPolicyTest.java` | {@link EgressPolicy}: host syntax, the deny-by-default address classes, and what an allowlist entry lifts. |
