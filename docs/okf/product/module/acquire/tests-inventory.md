---
type: TestsInventory
title: Acquisition and collectors — tests inventory
item: module.acquire
description: Generated list of the test classes for module.acquire, with how to run them.
tags: [tests, module]
---

# Acquisition and collectors — tests inventory (`module.acquire`)

> **Generated** by `tools/render-tests-inventory.mjs` from the git-tracked test sources under `platform/inspecto-acquire/src/test/` — never edit this table; add or
> rename tests and re-run the tool. Hub: [index](index.md) · Product Map Item
> [`module.acquire`](../../../../../product-map/module/acquire.toon).

26 test class(es): 26 unit, 0 real-HTTP control-plane, 0 IT.
Kind is inferred: `*IT` → IT; a `ControlApi*` class, or one using both `HttpClient` and `ControlApi` → real-HTTP
control-plane test; else unit.

Run the whole module, or chosen classes (commas between classes, never `+`):

```bash
mvn -o test -pl platform/inspecto-acquire
mvn -o test -pl platform/inspecto-acquire -Dtest=AcquisitionLedgerPruneTest,AcquisitionLedgersTest
```

| test class | package | kind | file | summary |
|---|---|---|---|---|
| `AcquisitionLedgerPruneTest` | `com.gamma.acquire` | unit | `platform/inspecto-acquire/src/test/java/com/gamma/acquire/AcquisitionLedgerPruneTest.java` | Retention pruning must never delete a source's <b>resume position</b> <!-- vocab-allow: quoted Javadoc --> |
| `AcquisitionLedgersTest` | `com.gamma.acquire` | unit | `platform/inspecto-acquire/src/test/java/com/gamma/acquire/AcquisitionLedgersTest.java` | The process-wide accessor resolves one {@link AcquisitionLedger} per space ({@link EventLog#currentSpaceId()}). |
| `AcquisitionLedgerTest` | `com.gamma.acquire` | unit | `platform/inspecto-acquire/src/test/java/com/gamma/acquire/AcquisitionLedgerTest.java` | The fingerprint ledger contract — find / record / upsert — for both the in-memory and DuckDB backends. |
| `AcquisitionLedgerWatermarkTest` | `com.gamma.acquire` | unit | `platform/inspecto-acquire/src/test/java/com/gamma/acquire/AcquisitionLedgerWatermarkTest.java` | The incremental high-watermark (Data Acquisition roadmap Phase C4) is <em>derived</em> from the fingerprint |
| `ChecksumsTest` | `com.gamma.acquire` | unit | `platform/inspecto-acquire/src/test/java/com/gamma/acquire/ChecksumsTest.java` | Content checksums via the JDK only — known vectors for "hello" across the supported algorithms. |
| `CircuitBreakerTest` | `com.gamma.acquire` | unit | `platform/inspecto-acquire/src/test/java/com/gamma/acquire/CircuitBreakerTest.java` | Phase F — {@link CircuitBreaker} trip / cooldown / half-open behaviour with an injected clock. |
| `ConnectionProfileTest` | `com.gamma.acquire` | unit | `platform/inspecto-acquire/src/test/java/com/gamma/acquire/ConnectionProfileTest.java` | Connection profile parse from {@code *_connection.toon}, secret masking, and the test endpoint. |
| `ConnectionRegistryTest` | `com.gamma.acquire` | unit | `platform/inspecto-acquire/src/test/java/com/gamma/acquire/ConnectionRegistryTest.java` | Phase-E: the process-wide bridge the static poll path uses to resolve a source.connection binding. <!-- vocab-allow: quoted Javadoc --> |
| `ConnectionTesterTest` | `com.gamma.acquire` | unit | `platform/inspecto-acquire/src/test/java/com/gamma/acquire/ConnectionTesterTest.java` | The reachability test: a real TCP connect (zero-dep) + secret-resolution reporting, no external network. |
| `DayManifestTest` | `com.gamma.acquire` | unit | `platform/inspecto-acquire/src/test/java/com/gamma/acquire/DayManifestTest.java` | LA-DAILY-INGEST-1 T8 (operator 2026-10-06): the per-day delivery manifest and what a recorded part reveals. |
| `DuplicatePolicyTest` | `com.gamma.acquire` | unit | `platform/inspecto-acquire/src/test/java/com/gamma/acquire/DuplicatePolicyTest.java` | The pure dup/change decision logic + the config-string enum mapping. |
| `FileSequenceGapsBucketStartGoldenTest` | `com.gamma.acquire` | unit | `platform/inspecto-acquire/src/test/java/com/gamma/acquire/FileSequenceGapsBucketStartGoldenTest.java` | Golden figures for where {@link FileSequenceGaps#analyze} starts enumerating its window (ANALYTICS-CORE-1 M5): the |
| `FileSequenceGapsTest` | `com.gamma.acquire` | unit | `platform/inspecto-acquire/src/test/java/com/gamma/acquire/FileSequenceGapsTest.java` | {@link FileSequenceGaps} — "how many files are missing for this pipeline yesterday?" over |
| `GapDetectorTest` | `com.gamma.acquire` | unit | `platform/inspecto-acquire/src/test/java/com/gamma/acquire/GapDetectorTest.java` | Phase-D sequence-gap detection — pure logic over a template + observed file names. |
| `GapTrackerTest` | `com.gamma.acquire` | unit | `platform/inspecto-acquire/src/test/java/com/gamma/acquire/GapTrackerTest.java` | Phase-D per-process gap dedup: a persistent gap fires once; a filled-then-reopened gap fires again. |
| `IntakeGovernorTest` | `com.gamma.acquire` | unit | `platform/inspecto-acquire/src/test/java/com/gamma/acquire/IntakeGovernorTest.java` | T15 — {@link IntakeGovernor} admission cap + cycle-overrun controller (pipeline-graph §3.5). |
| `IntegrityCheckerTest` | `com.gamma.acquire` | unit | `platform/inspecto-acquire/src/test/java/com/gamma/acquire/IntegrityCheckerTest.java` | Phase-E: size + etag-checksum verification of a fetched file against its listing metadata. |
| `LocalConnectionWorkbenchTest` | `com.gamma.acquire` | unit | `platform/inspecto-acquire/src/test/java/com/gamma/acquire/LocalConnectionWorkbenchTest.java` | The built-in local {@link ConnectionWorkbench}: graded probe checks, jailed explore, DuckDB-backed sample. |
| `LocalFileSystemConnectorTest` | `com.gamma.acquire` | unit | `platform/inspecto-acquire/src/test/java/com/gamma/acquire/LocalFileSystemConnectorTest.java` | Phase-A parity + behaviour of the built-in local connector. |
| `PostActionTest` | `com.gamma.acquire` | unit | `platform/inspecto-acquire/src/test/java/com/gamma/acquire/PostActionTest.java` | {@link PostAction#resolveTemplate} — the {@code archive_path} date-template resolver. Relocated |
| `RateLimiterTest` | `com.gamma.acquire` | unit | `platform/inspecto-acquire/src/test/java/com/gamma/acquire/RateLimiterTest.java` | Phase F — {@link RateLimiter} token-bucket throttling with an injected nano clock + sleeper. |
| `RetrievalPlannerTest` | `com.gamma.acquire` | unit | `platform/inspecto-acquire/src/test/java/com/gamma/acquire/RetrievalPlannerTest.java` | The I/O-minimisation policy: write bytes at most once — stream when nothing is kept, else fetch |
| `StabilityGateTest` | `com.gamma.acquire` | unit | `platform/inspecto-acquire/src/test/java/com/gamma/acquire/StabilityGateTest.java` | Phase-B readiness gate: stabilization is wall-clock driven and connector readiness short-circuits. |
| `StreamLaneTest` | `com.gamma.acquire` | unit | `platform/inspecto-acquire/src/test/java/com/gamma/acquire/StreamLaneTest.java` | The continuous lane's decision loop in isolation: drain on N, drain on T, reset on empty, reopen on failure. |
| `RetryPolicyTest` | `com.gamma.acquire.retry` | unit | `platform/inspecto-acquire/src/test/java/com/gamma/acquire/retry/RetryPolicyTest.java` | Phase F — {@link RetryPolicy} backoff schedule + retry/succeed/exhaust behaviour, with an injected sleeper. |
| `ConnectorFactoryTckSelfTest` | `com.gamma.acquire.testkit` | unit | `platform/inspecto-acquire/src/test/java/com/gamma/acquire/testkit/ConnectorFactoryTckSelfTest.java` | Proves the connector-factory TCKs can fail (MODULE-REORG-1 P5b): each assertion is run against a deliberately broken |
