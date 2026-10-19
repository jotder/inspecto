---
type: TestsInventory
title: Analytics Kernel (canonical statistics and baselines) — tests inventory
item: module.analytics
description: Generated list of the test classes for module.analytics, with how to run them.
tags: [tests, module]
---

# Analytics Kernel (canonical statistics and baselines) — tests inventory (`module.analytics`)

> **Generated** by `tools/render-tests-inventory.mjs` from the git-tracked test sources under `platform/inspecto-analytics/src/test/` — never edit this table; add or
> rename tests and re-run the tool. Hub: [index](index.md) · Product Map Item
> [`module.analytics`](../../../../../product-map/module/analytics.toon).

41 test class(es): 41 unit, 0 real-HTTP control-plane, 0 IT.
Kind is inferred: `*IT` → IT; a `ControlApi*` class, or one using both `HttpClient` and `ControlApi` → real-HTTP
control-plane test; else unit.

Run the whole module, or chosen classes (commas between classes, never `+`):

```bash
mvn -o test -pl platform/inspecto-analytics
mvn -o test -pl platform/inspecto-analytics -Dtest=ColumnProfilerTest,Md5BucketTest
```

| test class | package | kind | file | summary |
|---|---|---|---|---|
| `ColumnProfilerTest` | `com.gamma.analytics` | unit | `platform/inspecto-analytics/src/test/java/com/gamma/analytics/ColumnProfilerTest.java` | The column profiler (ANALYTICS-CORE-1 M4) executed against DuckDB on fixtures: numbers, text, dates, NULLs, an |
| `Md5BucketTest` | `com.gamma.analytics` | unit | `platform/inspecto-analytics/src/test/java/com/gamma/analytics/Md5BucketTest.java` | {@link Md5Bucket} against figures computed outside Java (Python hashlib: lower 64 bits of the MD5, little-endian, |
| `StatsDuckDbParityTest` | `com.gamma.analytics` | unit | `platform/inspecto-analytics/src/test/java/com/gamma/analytics/StatsDuckDbParityTest.java` | JVM ↔ DuckDB parity (ANALYTICS-CORE-1 M1): the same inputs through {@link Stats} and through DuckDB's built-in |
| `StatsTest` | `com.gamma.analytics` | unit | `platform/inspecto-analytics/src/test/java/com/gamma/analytics/StatsTest.java` | Golden-value conformance tests: one hand-computed figure per definition in docs/okf/product/module/analytics/spec.md. |
| `AccuracyDuckDbParityTest` | `com.gamma.analytics.accuracy` | unit | `platform/inspecto-analytics/src/test/java/com/gamma/analytics/accuracy/AccuracyDuckDbParityTest.java` | JVM ↔ DuckDB parity for the accuracy measures: {@link AccuracySql} against {@link Accuracy} on the same pairs. |
| `AccuracyTest` | `com.gamma.analytics.accuracy` | unit | `platform/inspecto-analytics/src/test/java/com/gamma/analytics/accuracy/AccuracyTest.java` | Hand-computed figures for every {@link Accuracy} definition and its input rules. |
| `BacktestTest` | `com.gamma.analytics.accuracy` | unit | `platform/inspecto-analytics/src/test/java/com/gamma/analytics/accuracy/BacktestTest.java` | The rolling-origin backtest driver: origins, expanding window, per-step aggregates, caps and forecast checks. |
| `BaselineStatisticsTest` | `com.gamma.analytics.baseline` | unit | `platform/inspecto-analytics/src/test/java/com/gamma/analytics/baseline/BaselineStatisticsTest.java` | Planted-pattern tests for the S2 baseline statistics. Each mutation check names the mutant it kills. |
| `RobustStatsTest` | `com.gamma.analytics.baseline` | unit | `platform/inspecto-analytics/src/test/java/com/gamma/analytics/baseline/RobustStatsTest.java` | ANALYTICS-CORE-1 M6: RobustStats now delegates to the kernel's Stats. These pin that every figure is BIT-identical to |
| `AnalyticsCatalogueTest` | `com.gamma.analytics.catalogue` | unit | `platform/inspecto-analytics/src/test/java/com/gamma/analytics/catalogue/AnalyticsCatalogueTest.java` |  |
| `CohortDuckDbParityTest` | `com.gamma.analytics.cohort` | unit | `platform/inspecto-analytics/src/test/java/com/gamma/analytics/cohort/CohortDuckDbParityTest.java` | JVM ↔ DuckDB parity for {@link Retention} and {@link Funnel} over seeded random events, across zones and options. |
| `CohortTest` | `com.gamma.analytics.cohort` | unit | `platform/inspecto-analytics/src/test/java/com/gamma/analytics/cohort/CohortTest.java` | Hand-computed retention and funnel figures (the rules are in the two classes' notes). |
| `BinningTest` | `com.gamma.analytics.distribution` | unit | `platform/inspecto-analytics/src/test/java/com/gamma/analytics/distribution/BinningTest.java` | {@link Binning}: hand figures for the JVM form, then the DuckDB form against it row by row (equal width over the |
| `CorrelationDuckDbParityTest` | `com.gamma.analytics.distribution` | unit | `platform/inspecto-analytics/src/test/java/com/gamma/analytics/distribution/CorrelationDuckDbParityTest.java` | {@link CorrelationSql} (DuckDB {@code corr}, {@code regr_*}) against {@link Stats#correlation} and |
| `OutlierDuckDbParityTest` | `com.gamma.analytics.distribution` | unit | `platform/inspecto-analytics/src/test/java/com/gamma/analytics/distribution/OutlierDuckDbParityTest.java` | {@link OutlierSql} (DuckDB) against the JVM definitions it is built on ({@link Stats#tukeyFences(double[], double, |
| `EntityGroupingParityTest` | `com.gamma.analytics.entity` | unit | `platform/inspecto-analytics/src/test/java/com/gamma/analytics/entity/EntityGroupingParityTest.java` | JVM ↔ DuckDB parity for entity grouping (ANALYTICS-CORE-1 S2): {@link EntityGrouping#group} and |
| `EntityGroupingTest` | `com.gamma.analytics.entity` | unit | `platform/inspecto-analytics/src/test/java/com/gamma/analytics/entity/EntityGroupingTest.java` | The JVM form of entity grouping (ANALYTICS-CORE-1 S2): hand cases, the caps, determinism and D-M1. |
| `ForecasterTest` | `com.gamma.analytics.forecast` | unit | `platform/inspecto-analytics/src/test/java/com/gamma/analytics/forecast/ForecasterTest.java` | {@link Forecaster} and {@link PredictionInterval}: hand-computed figures and the input rules (ANALYTICS-CORE-1 S5). |
| `ForecastSqlParityTest` | `com.gamma.analytics.forecast` | unit | `platform/inspecto-analytics/src/test/java/com/gamma/analytics/forecast/ForecastSqlParityTest.java` | {@link ForecastSql} against {@link Forecaster}: the same series through DuckDB and the JVM give the same rows — |
| `ForecastTemplateGoldenTest` | `com.gamma.analytics.forecast` | unit | `platform/inspecto-analytics/src/test/java/com/gamma/analytics/forecast/ForecastTemplateGoldenTest.java` | The golden test of the forecast kernel: the {@code business-assurance} Space Template's Holt-Winters SQL is the |
| `IsolationForestTest` | `com.gamma.analytics.model` | unit | `platform/inspecto-analytics/src/test/java/com/gamma/analytics/model/IsolationForestTest.java` |  |
| `KMeansDuckDbParityTest` | `com.gamma.analytics.model` | unit | `platform/inspecto-analytics/src/test/java/com/gamma/analytics/model/KMeansDuckDbParityTest.java` | {@link KMeansSql} (nearest stored centroid in DuckDB) against {@link KMeans.Model#assign}: every row, incl. NULL / NaN. |
| `KMeansTest` | `com.gamma.analytics.model` | unit | `platform/inspecto-analytics/src/test/java/com/gamma/analytics/model/KMeansTest.java` |  |
| `LogisticDuckDbParityTest` | `com.gamma.analytics.model` | unit | `platform/inspecto-analytics/src/test/java/com/gamma/analytics/model/LogisticDuckDbParityTest.java` | {@link LogisticSql} against {@link LogisticRegression.Model#probability}: every row, incl. NULL / NaN inputs. |
| `LogisticRegressionTest` | `com.gamma.analytics.model` | unit | `platform/inspecto-analytics/src/test/java/com/gamma/analytics/model/LogisticRegressionTest.java` |  |
| `SamplerDuckDbParityTest` | `com.gamma.analytics.sample` | unit | `platform/inspecto-analytics/src/test/java/com/gamma/analytics/sample/SamplerDuckDbParityTest.java` | JVM ↔ DuckDB parity for the sampler: {@link Sampler#sql} keeps exactly the rows {@link Sampler#select} keeps, for fixed |
| `SamplerTest` | `com.gamma.analytics.sample` | unit | `platform/inspecto-analytics/src/test/java/com/gamma/analytics/sample/SamplerTest.java` | The sampler's definitions: key text, the hash (golden figures computed outside Java), quotas, order and stability. |
| `ScoreContractTest` | `com.gamma.analytics.score` | unit | `platform/inspecto-analytics/src/test/java/com/gamma/analytics/score/ScoreContractTest.java` | The score output contract's pure parts: names, scale and bands, the entity cap, columns, run files and retention. |
| `SimilarityDuckDbParityTest` | `com.gamma.analytics.similarity` | unit | `platform/inspecto-analytics/src/test/java/com/gamma/analytics/similarity/SimilarityDuckDbParityTest.java` | JVM ↔ DuckDB 1.5.6 parity for the similarity kit (ANALYTICS-CORE-1 S1). What DuckDB's built-ins compute, as found: |
| `SimilarityTest` | `com.gamma.analytics.similarity` | unit | `platform/inspecto-analytics/src/test/java/com/gamma/analytics/similarity/SimilarityTest.java` | Hand-computed figures for every similarity definition (ANALYTICS-CORE-1 S1). |
| `HoldoutSplitDuckDbParityTest` | `com.gamma.analytics.split` | unit | `platform/inspecto-analytics/src/test/java/com/gamma/analytics/split/HoldoutSplitDuckDbParityTest.java` | JVM ↔ DuckDB parity for the holdout split: the SQL roles and predicates against the JVM functions. |
| `HoldoutSplitTest` | `com.gamma.analytics.split` | unit | `platform/inspecto-analytics/src/test/java/com/gamma/analytics/split/HoldoutSplitTest.java` | Golden figures for the time split and the entity-hash split. |
| `ChangePointDuckDbParityTest` | `com.gamma.analytics.time` | unit | `platform/inspecto-analytics/src/test/java/com/gamma/analytics/time/ChangePointDuckDbParityTest.java` | JVM ↔ DuckDB parity for {@link ChangePoint}, and the golden test against the origin of the rule: the |
| `ChangePointTest` | `com.gamma.analytics.time` | unit | `platform/inspecto-analytics/src/test/java/com/gamma/analytics/time/ChangePointTest.java` |  |
| `HourOfWeekDuckDbParityTest` | `com.gamma.analytics.time` | unit | `platform/inspecto-analytics/src/test/java/com/gamma/analytics/time/HourOfWeekDuckDbParityTest.java` | JVM ↔ DuckDB parity for {@link HourOfWeek} slots and {@link SeasonalProfile}, under two session zones. |
| `HourOfWeekTest` | `com.gamma.analytics.time` | unit | `platform/inspecto-analytics/src/test/java/com/gamma/analytics/time/HourOfWeekTest.java` |  |
| `SeasonalityDetectorTest` | `com.gamma.analytics.time` | unit | `platform/inspecto-analytics/src/test/java/com/gamma/analytics/time/SeasonalityDetectorTest.java` | Hand ACF figures and the detection rule: significance, minimum cycles, peak, ties, NONE. |
| `SeasonalityDuckDbParityTest` | `com.gamma.analytics.time` | unit | `platform/inspecto-analytics/src/test/java/com/gamma/analytics/time/SeasonalityDuckDbParityTest.java` | JVM ↔ DuckDB parity for the ACF: {@link SeasonalityDetector#acfSql} against {@link SeasonalityDetector#acf} per key, |
| `TimeGrainDuckDbParityTest` | `com.gamma.analytics.time` | unit | `platform/inspecto-analytics/src/test/java/com/gamma/analytics/time/TimeGrainDuckDbParityTest.java` | JVM ↔ DuckDB parity for {@link TimeGrain} (ANALYTICS-CORE-1 M5): every grain, every form, over instants packed around |
| `TimeGrainTest` | `com.gamma.analytics.time` | unit | `platform/inspecto-analytics/src/test/java/com/gamma/analytics/time/TimeGrainTest.java` | Hand-computed figures for {@link TimeGrain}'s JVM forms (ANALYTICS-CORE-1 M5). DuckDB agreement: the parity test. |
| `TimeSeriesSqlTest` | `com.gamma.analytics.time` | unit | `platform/inspecto-analytics/src/test/java/com/gamma/analytics/time/TimeSeriesSqlTest.java` | {@link TimeSeriesSql}'s resample and shift, executed on DuckDB (ANALYTICS-CORE-1 M5). |
