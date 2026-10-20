---
type: TestsInventory
title: Reconciliation — tests inventory
item: module.reconciliation
description: Generated list of the test classes for module.reconciliation, with how to run them.
tags: [tests, module]
---

# Reconciliation — tests inventory (`module.reconciliation`)

> **Generated** by `tools/render-tests-inventory.mjs` from the git-tracked test sources under `features/inspecto-reconciliation/src/test/` — never edit this table; add or
> rename tests and re-run the tool. Hub: [index](index.md) · Product Map Item
> [`module.reconciliation`](../../../../../product-map/module/reconciliation.toon).

25 test class(es): 20 unit, 5 real-HTTP control-plane, 0 IT.
Kind is inferred: `*IT` → IT; a `ControlApi*` class, or one using both `HttpClient` and `ControlApi` → real-HTTP
control-plane test; else unit.

Run the whole module, or chosen classes (commas between classes, never `+`):

```bash
mvn -o test -pl features/inspecto-reconciliation
mvn -o test -pl features/inspecto-reconciliation -Dtest=ControlApiReconDayTest,ControlApiReconPromoteTest
```

| test class | package | kind | file | summary |
|---|---|---|---|---|
| `ControlApiReconDayTest` | `com.gamma.control` | real-HTTP control-plane test | `features/inspecto-reconciliation/src/test/java/com/gamma/control/ControlApiReconDayTest.java` | Real-HTTP tests for the per-DAY Reconciliation (RECON-PERF-1, operator 2026-10-09): {@code day} scoping on |
| `ControlApiReconPromoteTest` | `com.gamma.control` | real-HTTP control-plane test | `features/inspecto-reconciliation/src/test/java/com/gamma/control/ControlApiReconPromoteTest.java` | {@code BREAK-INCIDENT-1}: {@code POST /recon/promote} hands one reconciliation Break to Ops as an |
| `ControlApiReconStateTest` | `com.gamma.control` | real-HTTP control-plane test | `features/inspecto-reconciliation/src/test/java/com/gamma/control/ControlApiReconStateTest.java` | R2-03 (operator 2026-09-26, reversing C9) — real-HTTP + real-DuckDB tests for a Reconciliation's |
| `ControlApiReconTest` | `com.gamma.control` | real-HTTP control-plane test | `features/inspecto-reconciliation/src/test/java/com/gamma/control/ControlApiReconTest.java` | Real-HTTP + real-DuckDB tests for the reconciliation routes (DAT-7, design |
| `ReconAbsentSurfaceParityTest` | `com.gamma.control` | unit | `features/inspecto-reconciliation/src/test/java/com/gamma/control/ReconAbsentSurfaceParityTest.java` | The module's {@code module.toon} {@code provides.routes} must list EXACTLY the routes its {@code RouteModule}s |
| `MobileMoneyPackGoldenTest` | `com.gamma.job` | real-HTTP control-plane test | `features/inspecto-reconciliation/src/test/java/com/gamma/job/MobileMoneyPackGoldenTest.java` | {@code PACK-MOBILE-MONEY-1} golden test. Boots the shipped {@code spaces/_templates/mobile-money} Space Template |
| `PartnerSettlementCorpusTest` | `com.gamma.job` | unit | `features/inspecto-reconciliation/src/test/java/com/gamma/job/PartnerSettlementCorpusTest.java` | {@link PartnerSettlementCorpus}: deterministic, planted and benign sets disjoint, synthetic names and identifiers only. |
| `PartnerSettlementPackGoldenTest` | `com.gamma.job` | unit | `features/inspecto-reconciliation/src/test/java/com/gamma/job/PartnerSettlementPackGoldenTest.java` | {@code PACK-PARTNER-SETTLEMENT-1} slices S2 and S3 golden test. The shipped {@code partner-settlement} Space Template is created |
| `PartnerSettlementReferenceTest` | `com.gamma.job` | unit | `features/inspecto-reconciliation/src/test/java/com/gamma/job/PartnerSettlementReferenceTest.java` | {@code PACK-PARTNER-SETTLEMENT-1} slice S1: the four References and the settlement-cycle calendar. The shipped |
| `PartnerSettlementSeedTest` | `com.gamma.job` | unit | `features/inspecto-reconciliation/src/test/java/com/gamma/job/PartnerSettlementSeedTest.java` | The zero-row schema seeds of the {@code partner-settlement} Space Template. Every Dataset a Job reads or writes ships a seed |
| `TelcoRaGoldenTest` | `com.gamma.job` | unit | `features/inspecto-reconciliation/src/test/java/com/gamma/job/TelcoRaGoldenTest.java` | Golden test of the {@code telco-ra} Space Template ({@code ASSURE-PACK-TELCO-RA-1}): the template's OWN |
| `ReconBreaksParityTest` | `com.gamma.recon` | unit | `features/inspecto-reconciliation/src/test/java/com/gamma/recon/ReconBreaksParityTest.java` | RECON-UPLIFT-1 B1 parity: {@link ReconBreaks#compute} now reads every Break set off ONE shared {@code __grain}; it |
| `ReconBreaksTest` | `com.gamma.recon` | unit | `features/inspecto-reconciliation/src/test/java/com/gamma/recon/ReconBreaksTest.java` | {@link ReconBreaks} — the server-side port of the SPA's Break lifecycle (R2-03): the {@code mergeBreaks} |
| `ReconConfigLoaderTest` | `com.gamma.recon` | unit | `features/inspecto-reconciliation/src/test/java/com/gamma/recon/ReconConfigLoaderTest.java` | {@link ReconConfigLoader#buildSpec} — the shared {@code reconciliation} config → {@link ReconService.Spec} |
| `ReconDayCacheTest` | `com.gamma.recon` | unit | `features/inspecto-reconciliation/src/test/java/com/gamma/recon/ReconDayCacheTest.java` | The result tier of {@link ReconDay.Cache} is bounded by estimated bytes as well as by count (RECON-UPLIFT-1 P4). |
| `ReconDayPruneTest` | `com.gamma.recon` | unit | `features/inspecto-reconciliation/src/test/java/com/gamma/recon/ReconDayPruneTest.java` | RECON-PERF-RESIDUALS-1 (1): a day reads only the files whose Parquet statistics can hold it |
| `ReconDayWeighMeasureTest` | `com.gamma.recon` | unit | `features/inspecto-reconciliation/src/test/java/com/gamma/recon/ReconDayWeighMeasureTest.java` | Measurement, not a gate (RECON-UPLIFT-RESIDUALS-1 5a): {@link ReconDay.Cache#weigh} against the real retained heap of |
| `ReconJobTypeManifestTest` | `com.gamma.recon` | unit | `features/inspecto-reconciliation/src/test/java/com/gamma/recon/ReconJobTypeManifestTest.java` | {@code provides.jobTypes} of this module's manifest equals the Job Types its providers register (P4e). |
| `ReconPerfTest` | `com.gamma.recon` | unit | `features/inspecto-reconciliation/src/test/java/com/gamma/recon/ReconPerfTest.java` | Timing harness, not a gate (RECON-PERF-1, operator 2026-10-09): 30 days x 4,000 msisdns x 3 sides (HLR / CRM / |
| `ReconRoutesContractTest` | `com.gamma.recon` | unit | `features/inspecto-reconciliation/src/test/java/com/gamma/recon/ReconRoutesContractTest.java` | {@link ReconRoutes} against the platform test kit's RouteModule TCK (MODULE-REORG-1 P5a): no host, no processor boot. |
| `ReconRunJobTest` | `com.gamma.recon` | unit | `features/inspecto-reconciliation/src/test/java/com/gamma/recon/ReconRunJobTest.java` | The {@code recon.run} Job Type: runs a saved {@code reconciliation} over its Datasets and emits a |
| `ReconRunJobTypeTckTest` | `com.gamma.recon` | unit | `features/inspecto-reconciliation/src/test/java/com/gamma/recon/ReconRunJobTypeTckTest.java` | {@link ReconRunJobType} against the platform's JobTypeProvider TCK (MODULE-REORG-1 P5b). |
| `ReconServiceTest` | `com.gamma.recon` | unit | `features/inspecto-reconciliation/src/test/java/com/gamma/recon/ReconServiceTest.java` | Unit tests for {@link ReconService} — spec validation (the route's 422 surface), the server-built |
| `ReconSignalsTest` | `com.gamma.recon` | unit | `features/inspecto-reconciliation/src/test/java/com/gamma/recon/ReconSignalsTest.java` | Pins every emitted Signal type byte-for-byte: they are persisted and matched, so a rename is a data break. |
| `ReconStateStoreTest` | `com.gamma.recon` | unit | `features/inspecto-reconciliation/src/test/java/com/gamma/recon/ReconStateStoreTest.java` | {@link ReconStateStore} — a Reconciliation's recorded run + Break lifecycle (R2-03): one JSON document per |
