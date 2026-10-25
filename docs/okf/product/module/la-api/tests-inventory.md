---
type: TestsInventory
title: Link analysis and geo routes — tests inventory
item: module.la-api
description: Generated list of the test classes for module.la-api, with how to run them.
tags: [tests, module]
---

# Link analysis and geo routes — tests inventory (`module.la-api`)

> **Generated** by `tools/render-tests-inventory.mjs` from the git-tracked test sources under `la/inspecto-la-api/src/test/` — never edit this table; add or
> rename tests and re-run the tool. Hub: [index](index.md) · Product Map Item
> [`module.la-api`](../../../../../product-map/module/la-api.toon).

43 test class(es): 43 unit, 0 real-HTTP control-plane, 0 IT.
Kind is inferred: `*IT` → IT; a `ControlApi*` class, or one using both `HttpClient` and `ControlApi` → real-HTTP
control-plane test; else unit.

Run the whole module, or chosen classes (commas between classes, never `+`):

```bash
mvn -o test -pl la/inspecto-la-api
mvn -o test -pl la/inspecto-la-api -Dtest=CaseHistoryRoutesContractTest,ColumnProfileGoldenTest
```

| test class | package | kind | file | summary |
|---|---|---|---|---|
| `CaseHistoryRoutesContractTest` | `com.gamma.la.api` | unit | `la/inspecto-la-api/src/test/java/com/gamma/la/api/CaseHistoryRoutesContractTest.java` | {@link CaseHistoryRoutes} against the platform test kit's RouteModule TCK (MODULE-REORG-1 P5a): no host, no processor boot. |
| `ColumnProfileGoldenTest` | `com.gamma.la.api` | unit | `la/inspecto-la-api/src/test/java/com/gamma/la/api/ColumnProfileGoldenTest.java` | Golden figures of Link Analysis' column-profile SQL (ANALYTICS-CORE-1 M4): {@link ColumnProfile#stats} over numbers, text, |
| `ConnectSeedsTest` | `com.gamma.la.api` | unit | `la/inspecto-la-api/src/test/java/com/gamma/la/api/ConnectSeedsTest.java` | LA-SEED-CONNECT-1: the pair table on a planted graph - the search itself, with no HTTP. |
| `DatasetFilesRoutesTest` | `com.gamma.la.api` | unit | `la/inspecto-la-api/src/test/java/com/gamma/la/api/DatasetFilesRoutesTest.java` | LA-DATA-TRUST-1 S1: how a lineage ledger's written path is matched to an index manifest's data-root-relative input path. |
| `DossierBundleRoutesContractTest` | `com.gamma.la.api` | unit | `la/inspecto-la-api/src/test/java/com/gamma/la/api/DossierBundleRoutesContractTest.java` | {@link DossierBundleRoutes} against the platform test kit's RouteModule TCK (MODULE-REORG-P5-TCKS): no host, no processor boot. |
| `DossierRoutesContractTest` | `com.gamma.la.api` | unit | `la/inspecto-la-api/src/test/java/com/gamma/la/api/DossierRoutesContractTest.java` | {@link DossierRoutes} against the platform test kit's RouteModule TCK (MODULE-REORG-P5-TCKS): no host, no processor boot. |
| `DraftAdmissionHeavyLimitTest` | `com.gamma.la.api` | unit | `la/inspecto-la-api/src/test/java/com/gamma/la/api/DraftAdmissionHeavyLimitTest.java` | {@code LA-CONFIG-LIMITS-1}: the heavy Draft job cap is the install-wide {@code drafts.heavy_jobs} when the server-wide |
| `DraftPromoteCostTest` | `com.gamma.la.api` | unit | `la/inspecto-la-api/src/test/java/com/gamma/la/api/DraftPromoteCostTest.java` | LA-DRAFT-PROMOTE-COST-1: promote reuses the Draft's own sealed per-step hash and set file instead of re-serialising the whole |
| `DraftRebaseCostTest` | `com.gamma.la.api` | unit | `la/inspecto-la-api/src/test/java/com/gamma/la/api/DraftRebaseCostTest.java` | LA-DRAFT-REBASE-COST-1: the rebase plan serialises each rebased state ONCE (hash and set file from the same canonical bytes) instead of |
| `DraftRoutesContractTest` | `com.gamma.la.api` | unit | `la/inspecto-la-api/src/test/java/com/gamma/la/api/DraftRoutesContractTest.java` | {@link DraftRoutes} against the platform test kit's RouteModule TCK (MODULE-REORG-P5-TCKS): no host, no processor boot. |
| `EmbedExpirySweepTest` | `com.gamma.la.api` | unit | `la/inspecto-la-api/src/test/java/com/gamma/la/api/EmbedExpirySweepTest.java` | LA-EMBED-EXPIRY-SWEEP-1: the sweep drops an expired grant's hash with NO mint and NO list call. |
| `EmbedGrantsExpiryClockTest` | `com.gamma.la.api` | unit | `la/inspecto-la-api/src/test/java/com/gamma/la/api/EmbedGrantsExpiryClockTest.java` | LA-EMBED-VIEW-1 remainder (d): real expiry, driven by an INJECTED clock (every grant call takes {@code now}), including a lifetime |
| `EntityIdentityRoutesContractTest` | `com.gamma.la.api` | unit | `la/inspecto-la-api/src/test/java/com/gamma/la/api/EntityIdentityRoutesContractTest.java` | {@link EntityIdentityRoutes} against the platform test kit's RouteModule TCK (MODULE-REORG-P5-TCKS): no host, no processor boot. |
| `EntityKeySqlTest` | `com.gamma.la.api` | unit | `la/inspecto-la-api/src/test/java/com/gamma/la/api/EntityKeySqlTest.java` | The Entity profile counts Entities by the SQL mirror of {@link EntityTypes#normalise} (D-EP5). It runs the shared |
| `EntityProfileRoutesContractTest` | `com.gamma.la.api` | unit | `la/inspecto-la-api/src/test/java/com/gamma/la/api/EntityProfileRoutesContractTest.java` | {@link EntityProfileRoutes} against the platform test kit's RouteModule TCK: no host, no processor boot. |
| `GeoRoutesContractTest` | `com.gamma.la.api` | unit | `la/inspecto-la-api/src/test/java/com/gamma/la/api/GeoRoutesContractTest.java` | {@link GeoRoutes} against the platform test kit's RouteModule TCK (MODULE-REORG-1 P5a): no host, no processor boot. |
| `GraphRunRoutesContractTest` | `com.gamma.la.api` | unit | `la/inspecto-la-api/src/test/java/com/gamma/la/api/GraphRunRoutesContractTest.java` | {@link GraphRunRoutes} against the platform test kit's RouteModule TCK (MODULE-REORG-P5-TCKS): no host, no processor boot. |
| `IndexedExpandTest` | `com.gamma.la.api` | unit | `la/inspecto-la-api/src/test/java/com/gamma/la/api/IndexedExpandTest.java` | {@link IndexedExpand#notIndexable}: every rung the index must NOT answer, each against a simple twin (D-3 step 6). |
| `IndexRoutesContractTest` | `com.gamma.la.api` | unit | `la/inspecto-la-api/src/test/java/com/gamma/la/api/IndexRoutesContractTest.java` | {@link IndexRoutes} against the platform test kit's RouteModule TCK (MODULE-REORG-P5-TCKS): no host, no processor boot. |
| `IndexStalenessTest` | `com.gamma.la.api` | unit | `la/inspecto-la-api/src/test/java/com/gamma/la/api/IndexStalenessTest.java` | {@link IndexStaleness}: every reason, their precedence, removedInput and the 'cannot tell' cases (D-3 design 5.3a). |
| `InputFingerprintCacheTest` | `com.gamma.la.api` | unit | `la/inspecto-la-api/src/test/java/com/gamma/la/api/InputFingerprintCacheTest.java` | A timed-out listing is cached for the TTL like any answer, so a pathological Dataset is not re-walked on every request. |
| `InvestigationCaseRoutesContractTest` | `com.gamma.la.api` | unit | `la/inspecto-la-api/src/test/java/com/gamma/la/api/InvestigationCaseRoutesContractTest.java` | {@link InvestigationCaseRoutes} against the platform test kit's RouteModule TCK (MODULE-REORG-P5-TCKS): no host, no processor boot. |
| `InvestigationComparisonRoutesContractTest` | `com.gamma.la.api` | unit | `la/inspecto-la-api/src/test/java/com/gamma/la/api/InvestigationComparisonRoutesContractTest.java` | {@link InvestigationComparisonRoutes} against the platform test kit's RouteModule TCK (MODULE-REORG-P5-TCKS): no host, no processor boot. |
| `InvestigationCoverageRoutesContractTest` | `com.gamma.la.api` | unit | `la/inspecto-la-api/src/test/java/com/gamma/la/api/InvestigationCoverageRoutesContractTest.java` | {@link InvestigationCoverageRoutes} against the platform test kit's RouteModule TCK (MODULE-REORG-P5-TCKS): no host, no processor boot. |
| `InvestigationInboundRoutesContractTest` | `com.gamma.la.api` | unit | `la/inspecto-la-api/src/test/java/com/gamma/la/api/InvestigationInboundRoutesContractTest.java` | {@link InvestigationInboundRoutes} against the platform test kit's RouteModule TCK (MODULE-REORG-P5-TCKS): no host, no processor boot. |
| `InvestigationMemberRoutesContractTest` | `com.gamma.la.api` | unit | `la/inspecto-la-api/src/test/java/com/gamma/la/api/InvestigationMemberRoutesContractTest.java` | {@link InvestigationMemberRoutes} against the platform test kit's RouteModule TCK (MODULE-REORG-P5-TCKS): no host, no processor boot. |
| `InvestigationReferenceRoutesContractTest` | `com.gamma.la.api` | unit | `la/inspecto-la-api/src/test/java/com/gamma/la/api/InvestigationReferenceRoutesContractTest.java` | {@link InvestigationReferenceRoutes} against the platform test kit's RouteModule TCK (MODULE-REORG-P5-TCKS): no host, no processor boot. |
| `InvestigationRoutesContractTest` | `com.gamma.la.api` | unit | `la/inspecto-la-api/src/test/java/com/gamma/la/api/InvestigationRoutesContractTest.java` | {@link InvestigationRoutes} against the platform test kit's RouteModule TCK (MODULE-REORG-P5-TCKS): no host, no processor boot. |
| `InvestigationTemplateRoutesContractTest` | `com.gamma.la.api` | unit | `la/inspecto-la-api/src/test/java/com/gamma/la/api/InvestigationTemplateRoutesContractTest.java` | {@link InvestigationTemplateRoutes} against the platform test kit's RouteModule TCK (MODULE-REORG-P5-TCKS): no host, no processor boot. |
| `InvRoutesContractTest` | `com.gamma.la.api` | unit | `la/inspecto-la-api/src/test/java/com/gamma/la/api/InvRoutesContractTest.java` | {@link InvRoutes} against the platform test kit's RouteModule TCK (MODULE-REORG-1 P5a): no host, no processor boot. |
| `InvRoutesTraversalPolicyTest` | `com.gamma.la.api` | unit | `la/inspecto-la-api/src/test/java/com/gamma/la/api/InvRoutesTraversalPolicyTest.java` | The recursive-paths walk runs under {@code assist.sql.traversal_threads} (default 4); every other sandboxed |
| `LaApiPortsTest` | `com.gamma.la.api` | unit | `la/inspecto-la-api/src/test/java/com/gamma/la/api/LaApiPortsTest.java` | LA separation D-1 step 5b/6 — Link Analysis routes run on the PORTS ALONE. This module's test classpath has no engine, no |
| `LaApiRoutesManifestParityTest` | `com.gamma.la.api` | unit | `la/inspecto-la-api/src/test/java/com/gamma/la/api/LaApiRoutesManifestParityTest.java` | The module's {@code module.toon} {@code provides.routes} must list EXACTLY the routes its {@code RouteModule}s |
| `LaLimitCatalogueParityTest` | `com.gamma.la.api` | unit | `la/inspecto-la-api/src/test/java/com/gamma/la/api/LaLimitCatalogueParityTest.java` | {@code LA-CONFIG-LIMITS-1}: an item whose code site still holds a constant has its catalogue default pinned to that |
| `PatternRoutesContractTest` | `com.gamma.la.api` | unit | `la/inspecto-la-api/src/test/java/com/gamma/la/api/PatternRoutesContractTest.java` | {@link PatternRoutes} against the platform test kit's RouteModule TCK (MODULE-REORG-P5-TCKS): no host, no processor boot. |
| `ReferenceRowsKeyPredicateTest` | `com.gamma.la.api` | unit | `la/inspecto-la-api/src/test/java/com/gamma/la/api/ReferenceRowsKeyPredicateTest.java` | {@link ReferenceRows#keyPredicate}: the prunable forms and the CAST fallback. |
| `ScheduledIndexBuildTest` | `com.gamma.la.api` | unit | `la/inspecto-la-api/src/test/java/com/gamma/la/api/ScheduledIndexBuildTest.java` | The pure authority re-decision of the scheduled index build principal (D-ING2, the standing-detection model). |
| `StandingDetectionTest` | `com.gamma.la.api` | unit | `la/inspecto-la-api/src/test/java/com/gamma/la/api/StandingDetectionTest.java` | LD-2/LD-3: the pure re-decision of a standing-detection sweep's authority (no PDP registered: ABSTAIN). |
| `ValueMeasureRoutesContractTest` | `com.gamma.la.api` | unit | `la/inspecto-la-api/src/test/java/com/gamma/la/api/ValueMeasureRoutesContractTest.java` | {@link ValueMeasureRoutes} against the platform test kit's RouteModule TCK (MODULE-REORG-P5-TCKS): no host, no processor boot. |
| `ValueMeasuresPackKeysTest` | `com.gamma.la.api` | unit | `la/inspecto-la-api/src/test/java/com/gamma/la/api/ValueMeasuresPackKeysTest.java` | An Industry pack's {@code measure.<id>} terms may name exactly the Value Measures (the core's list is a mirror). |
| `WorkingSetConditionalReadTest` | `com.gamma.la.api` | unit | `la/inspecto-la-api/src/test/java/com/gamma/la/api/WorkingSetConditionalReadTest.java` | PERF-P4-3 review hardening of the Working Set conditional read: (1) the change token is re-checked AFTER the log read, so a |
| `WorkingSetEtagTest` | `com.gamma.la.api` | unit | `la/inspecto-la-api/src/test/java/com/gamma/la/api/WorkingSetEtagTest.java` | PERF-P4-3: every ingredient of the Working Set tag moves the tag ALONE. The store is a stub whose cache key and tokens are |
| `WorkingSetRoutesContractTest` | `com.gamma.la.api` | unit | `la/inspecto-la-api/src/test/java/com/gamma/la/api/WorkingSetRoutesContractTest.java` | {@link WorkingSetRoutes} against the platform test kit's RouteModule TCK (MODULE-REORG-P5-TCKS): no host, no processor boot. |
