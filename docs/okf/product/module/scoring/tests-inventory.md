---
type: TestsInventory
title: Scoring — tests inventory
item: module.scoring
description: Generated list of the test classes for module.scoring, with how to run them.
tags: [tests, module]
---

# Scoring — tests inventory (`module.scoring`)

> **Generated** by `tools/render-tests-inventory.mjs` from the git-tracked test sources under `features/inspecto-scoring/src/test/` — never edit this table; add or
> rename tests and re-run the tool. Hub: [index](index.md) · Product Map Item
> [`module.scoring`](../../../../../product-map/module/scoring.toon).

30 test class(es): 27 unit, 3 real-HTTP control-plane, 0 IT.
Kind is inferred: `*IT` → IT; a `ControlApi*` class, or one using both `HttpClient` and `ControlApi` → real-HTTP
control-plane test; else unit.

Run the whole module, or chosen classes (commas between classes, never `+`):

```bash
mvn -o test -pl features/inspecto-scoring
mvn -o test -pl features/inspecto-scoring -Dtest=RiskScoreAlertTest,BypassAllowListTest
```

| test class | package | kind | file | summary |
|---|---|---|---|---|
| `RiskScoreAlertTest` | `com.gamma.alert` | unit | `features/inspecto-scoring/src/test/java/com/gamma/alert/RiskScoreAlertTest.java` | ASSURE-RISK-SCORE-1 step 3: Incident priority through the EXISTING Alert Rule machinery — a per-entity rule |
| `BypassAllowListTest` | `com.gamma.control` | unit | `features/inspecto-scoring/src/test/java/com/gamma/control/BypassAllowListTest.java` | {@code TELCO-BYPASS-SIMBOX-1} slice B2: the {@code tb_findings} stage in isolation (gate G-SB(c): a stage's SQL can be |
| `BypassClusterTest` | `com.gamma.control` | unit | `features/inspecto-scoring/src/test/java/com/gamma/control/BypassClusterTest.java` | {@code TELCO-BYPASS-SIMBOX-1} slice B3: the two cluster stages in isolation (gate G-SB(c): a stage's SQL can be |
| `BypassScaleMeasureTest` | `com.gamma.control` | unit | `features/inspecto-scoring/src/test/java/com/gamma/control/BypassScaleMeasureTest.java` | Measurement, not a gate ({@code TELCO-BYPASS-SIMBOX-1} slice B2). Runs only with {@code TELCO_BYPASS_PERF=1} and prints: |
| `BypassScoreTest` | `com.gamma.control` | unit | `features/inspecto-scoring/src/test/java/com/gamma/control/BypassScoreTest.java` | {@code TELCO-BYPASS-SIMBOX-1} slice B2: the shipped {@code tb_sim} Risk Score model, factor by factor. Pure arithmetic |
| `ControlApiRiskScorePreviewTest` | `com.gamma.control` | real-HTTP control-plane test | `features/inspecto-scoring/src/test/java/com/gamma/control/ControlApiRiskScorePreviewTest.java` | ASSURE-RISK-SCORE-RESIDUALS-1 S3 over real HTTP with an ARMED Subject: {@code POST /risk-scores/preview} scores ONE |
| `ControlApiRiskScoreTest` | `com.gamma.control` | real-HTTP control-plane test | `features/inspecto-scoring/src/test/java/com/gamma/control/ControlApiRiskScoreTest.java` | ASSURE-RISK-SCORE-1 over real HTTP with an ARMED Subject: {@code GET /risk-scores/{model}/{entityKey}} returns |
| `PaymentFraudDashboardTest` | `com.gamma.control` | real-HTTP control-plane test | `features/inspecto-scoring/src/test/java/com/gamma/control/PaymentFraudDashboardTest.java` | ASSURE-PACK-PAYMENT-FRAUD-1 (WS-44 dashboards + KPIs): the shipped {@code payment-fraud} Space Template is created |
| `PendingAlertRulesTest` | `com.gamma.control` | unit | `features/inspecto-scoring/src/test/java/com/gamma/control/PendingAlertRulesTest.java` | TEMPLATE-RISK-SCORE-ALERT-RULE-1 (operator 2026-10-06, deferred seed): a pending Alert Rule over a Risk Score's |
| `ScoringAbsentSurfaceParityTest` | `com.gamma.control` | unit | `features/inspecto-scoring/src/test/java/com/gamma/control/ScoringAbsentSurfaceParityTest.java` | The module's {@code module.toon} {@code provides.routes} must list EXACTLY the routes its {@code RouteModule}s |
| `TelcoBypassClusterGoldenTest` | `com.gamma.control` | unit | `features/inspecto-scoring/src/test/java/com/gamma/control/TelcoBypassClusterGoldenTest.java` | {@code TELCO-BYPASS-SIMBOX-1} slice B3 golden test. The shipped {@code telco-bypass} Space Template, copied verbatim, |
| `TelcoBypassGoldenTest` | `com.gamma.control` | unit | `features/inspecto-scoring/src/test/java/com/gamma/control/TelcoBypassGoldenTest.java` | {@code TELCO-BYPASS-SIMBOX-1} slice B2 golden test. The shipped {@code telco-bypass} Space Template, copied verbatim, |
| `AmlPackGoldenTest` | `com.gamma.pack` | unit | `features/inspecto-scoring/src/test/java/com/gamma/pack/AmlPackGoldenTest.java` | {@code PACK-AML-1}: the {@code aml} Space Template RUN end to end from a verbatim copy. The two Pipelines ingest the |
| `MobileMoneyRiskScoreGoldenTest` | `com.gamma.pack` | unit | `features/inspecto-scoring/src/test/java/com/gamma/pack/MobileMoneyRiskScoreGoldenTest.java` | {@code PACK-MOBILE-MONEY-1}: the {@code mobile-money} template's per-agent Risk Score ({@code mm_agent}), run over |
| `PaymentFraudTemplateGoldenTest` | `com.gamma.pack` | unit | `features/inspecto-scoring/src/test/java/com/gamma/pack/PaymentFraudTemplateGoldenTest.java` | ASSURE-PACK-PAYMENT-FRAUD-1 slice 1: the {@code payment-fraud} Space Template, RUN end to end from a verbatim |
| `RiskScoreCalculatedColumnWorkaroundTest` | `com.gamma.risk` | unit | `features/inspecto-scoring/src/test/java/com/gamma/risk/RiskScoreCalculatedColumnWorkaroundTest.java` | ASSURE-RISK-SCORE-RESIDUALS-1 item (7): a Risk Score factor has no arithmetic, so the supported workaround is to |
| `RiskScoreEvaluatorTest` | `com.gamma.risk` | unit | `features/inspecto-scoring/src/test/java/com/gamma/risk/RiskScoreEvaluatorTest.java` | The evaluator over real DuckDB: an author's filter value is a typed literal, quote included. |
| `RiskScoreJobTest` | `com.gamma.risk` | unit | `features/inspecto-scoring/src/test/java/com/gamma/risk/RiskScoreJobTest.java` | ASSURE-RISK-SCORE-1: the {@code risk.score} Job evaluates a saved model over a real DuckDB/Parquet corpus |
| `RiskScoreJobTypeManifestTest` | `com.gamma.risk` | unit | `features/inspecto-scoring/src/test/java/com/gamma/risk/RiskScoreJobTypeManifestTest.java` | {@code provides.jobTypes} of this module's manifest equals the Job Types its providers register (P4e). |
| `RiskScoreJobTypeTckTest` | `com.gamma.risk` | unit | `features/inspecto-scoring/src/test/java/com/gamma/risk/RiskScoreJobTypeTckTest.java` | {@link RiskScoreJobType} against the platform's JobTypeProvider TCK (MODULE-REORG-1 P5b). |
| `RiskScoreModelTest` | `com.gamma.risk` | unit | `features/inspecto-scoring/src/test/java/com/gamma/risk/RiskScoreModelTest.java` | Save-time refusals of a {@code risk-score} model — every one fails closed, naming the field. |
| `RiskScoreOutputGoldenTest` | `com.gamma.risk` | unit | `features/inspecto-scoring/src/test/java/com/gamma/risk/RiskScoreOutputGoldenTest.java` | Golden pins of everything a {@code risk.score} run writes or hands on (ANALYTICS-CORE-1 M7): the history and |
| `RiskScoreOutputOwnershipTest` | `com.gamma.risk` | unit | `features/inspecto-scoring/src/test/java/com/gamma/risk/RiskScoreOutputOwnershipTest.java` | The scores output can never destroy data it does not own: the name is derived (never authored), and the |
| `RiskScoreRetentionTest` | `com.gamma.risk` | unit | `features/inspecto-scoring/src/test/java/com/gamma/risk/RiskScoreRetentionTest.java` | Opt-in history retention: off by default, whole run files only, never {@code _latest} or the new run. |
| `RiskScoreRoutesContractTest` | `com.gamma.risk` | unit | `features/inspecto-scoring/src/test/java/com/gamma/risk/RiskScoreRoutesContractTest.java` | {@link RiskScoreRoutes} against the platform test kit's RouteModule TCK (MODULE-REORG-1 P5a): no host, no processor boot. |
| `RiskScorerTest` | `com.gamma.risk` | unit | `features/inspecto-scoring/src/test/java/com/gamma/risk/RiskScorerTest.java` | The Risk Score arithmetic: weights, caps, missing indicators, the 0–100 clamp and reproducibility. |
| `RiskScoreWatchListFeedTest` | `com.gamma.risk` | unit | `features/inspecto-scoring/src/test/java/com/gamma/risk/RiskScoreWatchListFeedTest.java` | ASSURE-ENTITY-LISTS-1 x ASSURE-RISK-SCORE-1: the {@code risk.score} Job's watch-list feed, with Entity Lists |
| `RiskScoreWhenParityTest` | `com.gamma.risk` | unit | `features/inspecto-scoring/src/test/java/com/gamma/risk/RiskScoreWhenParityTest.java` | STEP 5 parity corpus (MODULE-REORG-P7-KERNEL): a Risk factor's flat {@code filters} and the same predicate as a |
| `ScoringSignalsTest` | `com.gamma.risk` | unit | `features/inspecto-scoring/src/test/java/com/gamma/risk/ScoringSignalsTest.java` | Pins every emitted Signal type byte-for-byte: they are persisted and matched, so a rename is a data break. |
| `AmlScreeningGoldenTest` | `com.gamma.screening` | unit | `features/inspecto-scoring/src/test/java/com/gamma/screening/AmlScreeningGoldenTest.java` | {@code PACK-AML-1}: the {@code aml} template's {@code screening.run} Job and Report Template, consumed through their |
