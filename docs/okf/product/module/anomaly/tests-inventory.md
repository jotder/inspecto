---
type: TestsInventory
title: Anomaly Detection — tests inventory
item: module.anomaly
description: Generated list of the test classes for module.anomaly, with how to run them.
tags: [tests, module]
---

# Anomaly Detection — tests inventory (`module.anomaly`)

> **Generated** by `tools/render-tests-inventory.mjs` from the git-tracked test sources under `features/inspecto-anomaly/src/test/` — never edit this table; add or
> rename tests and re-run the tool. Hub: [index](index.md) · Product Map Item
> [`module.anomaly`](../../../../../product-map/module/anomaly.toon).

23 test class(es): 21 unit, 2 real-HTTP control-plane, 0 IT.
Kind is inferred: `*IT` → IT; a `ControlApi*` class, or one using both `HttpClient` and `ControlApi` → real-HTTP
control-plane test; else unit.

Run the whole module, or chosen classes (commas between classes, never `+`):

```bash
mvn -o test -pl features/inspecto-anomaly
mvn -o test -pl features/inspecto-anomaly -Dtest=AnomalyAbsentSurfaceParityTest,AnomalyEvidenceTest
```

| test class | package | kind | file | summary |
|---|---|---|---|---|
| `AnomalyAbsentSurfaceParityTest` | `com.gamma.anomaly` | unit | `features/inspecto-anomaly/src/test/java/com/gamma/anomaly/AnomalyAbsentSurfaceParityTest.java` | The module's {@code module.toon} {@code provides.routes} must list EXACTLY the routes its {@code RouteModule} |
| `AnomalyEvidenceTest` | `com.gamma.anomaly` | unit | `features/inspecto-anomaly/src/test/java/com/gamma/anomaly/AnomalyEvidenceTest.java` | Evidence rows (ANOMALY-DETECTION-RESIDUALS-1 (c), design section 5): up to 3 origin rows of the scored day for an |
| `AnomalyForestScorerTest` | `com.gamma.anomaly` | unit | `features/inspecto-anomaly/src/test/java/com/gamma/anomaly/AnomalyForestScorerTest.java` | The isolation-forest option of an Anomaly Model (ANALYTICS-CORE-1 C4) over the golden corpus ({@link AnomalyCorpus}): |
| `AnomalyGoldenCorpusTest` | `com.gamma.anomaly` | unit | `features/inspecto-anomaly/src/test/java/com/gamma/anomaly/AnomalyGoldenCorpusTest.java` | The golden corpus ({@link AnomalyCorpus}) over real DuckDB/Parquet: every planted anomaly is {@code high} and in |
| `AnomalyKindValidatorTest` | `com.gamma.anomaly` | unit | `features/inspecto-anomaly/src/test/java/com/gamma/anomaly/AnomalyKindValidatorTest.java` | The {@code anomaly-model} save gates that need the Space, and the reserved {@code anomaly_scores_} prefix. |
| `AnomalyListsFeedTest` | `com.gamma.anomaly` | unit | `features/inspecto-anomaly/src/test/java/com/gamma/anomaly/AnomalyListsFeedTest.java` | Design §9 with Entity Lists installed (the inspecto-entity-list module provides the {@link WatchListFeed}): a model's |
| `AnomalyModelTest` | `com.gamma.anomaly` | unit | `features/inspecto-anomaly/src/test/java/com/gamma/anomaly/AnomalyModelTest.java` | {@link AnomalyModel#fromMap}: the defaults, and every refusal fail-closed with the field named. |
| `AnomalyPeerGoldenCorpusTest` | `com.gamma.anomaly` | unit | `features/inspecto-anomaly/src/test/java/com/gamma/anomaly/AnomalyPeerGoldenCorpusTest.java` | The peer golden corpus ({@link AnomalyPeerCorpus}) over real DuckDB/Parquet: U2 (SIM-box), U5 (peer outlier) and a |
| `AnomalyScoreAlertTest` | `com.gamma.anomaly` | unit | `features/inspecto-anomaly/src/test/java/com/gamma/anomaly/AnomalyScoreAlertTest.java` | Design §9: Incidents through the EXISTING Alert Rule machinery — a per-entity rule over |
| `AnomalyScoreEvaluatorTest` | `com.gamma.anomaly` | unit | `features/inspecto-anomaly/src/test/java/com/gamma/anomaly/AnomalyScoreEvaluatorTest.java` | The {@code peers.by} cohort key (ANOMALY-DETECTION-RESIDUALS-1 (g), operator decision 2026-10-11): NULL is a cohort |
| `AnomalyScoreJobTest` | `com.gamma.anomaly` | unit | `features/inspecto-anomaly/src/test/java/com/gamma/anomaly/AnomalyScoreJobTest.java` | The {@code anomaly.score} Job through a real {@link JobService} over the golden corpus: the history and |
| `AnomalyScoreJobTypeManifestTest` | `com.gamma.anomaly` | unit | `features/inspecto-anomaly/src/test/java/com/gamma/anomaly/AnomalyScoreJobTypeManifestTest.java` | {@code provides.jobTypes} of this module's manifest equals the Job Types its providers register. |
| `AnomalyScoreJobTypeTckTest` | `com.gamma.anomaly` | unit | `features/inspecto-anomaly/src/test/java/com/gamma/anomaly/AnomalyScoreJobTypeTckTest.java` | {@link AnomalyScoreJobType} against the platform's JobTypeProvider TCK. |
| `AnomalyScoreOutputGoldenTest` | `com.gamma.anomaly` | unit | `features/inspecto-anomaly/src/test/java/com/gamma/anomaly/AnomalyScoreOutputGoldenTest.java` | Golden pins of everything an {@code anomaly.score} run writes or hands on (ANALYTICS-CORE-1 M7), over the golden |
| `AnomalyScoreRoutesContractTest` | `com.gamma.anomaly` | unit | `features/inspecto-anomaly/src/test/java/com/gamma/anomaly/AnomalyScoreRoutesContractTest.java` | {@link AnomalyScoreRoutes} against the platform test kit's RouteModule TCK: no host, no processor boot. |
| `AnomalyScorerTest` | `com.gamma.anomaly` | unit | `features/inspecto-anomaly/src/test/java/com/gamma/anomaly/AnomalyScorerTest.java` | {@link AnomalyScorer}: pure, on plain numbers. |
| `AnomalySignalsTest` | `com.gamma.anomaly` | unit | `features/inspecto-anomaly/src/test/java/com/gamma/anomaly/AnomalySignalsTest.java` | Pins every emitted Signal type byte-for-byte: they are persisted and matched, so a rename is a data break. |
| `AnomalyTemplateModelsTest` | `com.gamma.anomaly` | unit | `features/inspecto-anomaly/src/test/java/com/gamma/anomaly/AnomalyTemplateModelsTest.java` | ANOMALY-DETECTION-1 S5: the example Anomaly Model each fraud / AML pack ships passes the {@code anomaly-model} save |
| `ControlApiAnomalyScoreTest` | `com.gamma.anomaly` | real-HTTP control-plane test | `features/inspecto-anomaly/src/test/java/com/gamma/anomaly/ControlApiAnomalyScoreTest.java` | ANOMALY-DETECTION-1 S4 over real HTTP with an ARMED Subject (a test with no Subject would make |
| `ControlApiAnomalyTemplateApplyTest` | `com.gamma.anomaly` | real-HTTP control-plane test | `features/inspecto-anomaly/src/test/java/com/gamma/anomaly/ControlApiAnomalyTemplateApplyTest.java` | Live-verification finding: a Space Template whose Anomaly Model declares {@code peers} (mobile-money |
| `PackFraudAmlAnomalyPropertyTest` | `com.gamma.anomaly` | unit | `features/inspecto-anomaly/src/test/java/com/gamma/anomaly/PackFraudAmlAnomalyPropertyTest.java` | ANOMALY-DETECTION-RESIDUALS-1 (b), decision D8(b), for the mobile-money and AML packs (sibling of |
| `PeerSideEquivalenceTest` | `com.gamma.anomaly` | unit | `features/inspecto-anomaly/src/test/java/com/gamma/anomaly/PeerSideEquivalenceTest.java` | ANOMALY-PEER-GROUPING-1: the grouped {@code AnomalyScoreEvaluator.PeerSide} must be behaviour-identical to the |
| `TelcoFraudPackAnomalyPropertyTest` | `com.gamma.anomaly` | unit | `features/inspecto-anomaly/src/test/java/com/gamma/anomaly/TelcoFraudPackAnomalyPropertyTest.java` | ANOMALY-DETECTION-RESIDUALS-1 (b), decision D8(b): the SHIPPED telco-fraud pack model {@code tf_subscriber_usage} |
