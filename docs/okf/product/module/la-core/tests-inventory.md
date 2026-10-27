---
type: TestsInventory
title: Link analysis core and ports — tests inventory
item: module.la-core
description: Generated list of the test classes for module.la-core, with how to run them.
tags: [tests, module]
---

# Link analysis core and ports — tests inventory (`module.la-core`)

> **Generated** by `tools/render-tests-inventory.mjs` from the git-tracked test sources under `la/inspecto-la-core/src/test/` — never edit this table; add or
> rename tests and re-run the tool. Hub: [index](index.md) · Product Map Item
> [`module.la-core`](../../../../../product-map/module/la-core.toon).

24 test class(es): 24 unit, 0 real-HTTP control-plane, 0 IT.
Kind is inferred: `*IT` → IT; a `ControlApi*` class, or one using both `HttpClient` and `ControlApi` → real-HTTP
control-plane test; else unit.

Run the whole module, or chosen classes (commas between classes, never `+`):

```bash
mvn -o test -pl la/inspecto-la-core
mvn -o test -pl la/inspecto-la-core -Dtest=DraftCheckpointsTest,DraftLifecycleTest
```

| test class | package | kind | file | summary |
|---|---|---|---|---|
| `DraftCheckpointsTest` | `com.gamma.la.core` | unit | `la/inspecto-la-core/src/test/java/com/gamma/la/core/DraftCheckpointsTest.java` | D7-4 - the Draft checkpoint: resume == full fold, no whole-log fold per op, and a changed file is never served from cache. |
| `DraftLifecycleTest` | `com.gamma.la.core` | unit | `la/inspecto-la-core/src/test/java/com/gamma/la/core/DraftLifecycleTest.java` | D7-6 - the idle markers and their precedence, the open-Draft count, and the crash sweep of a rebase (fixtures, no HTTP). |
| `DraftStoreTest` | `com.gamma.la.core` | unit | `la/inspecto-la-core/src/test/java/com/gamma/la/core/DraftStoreTest.java` | D7-3 - the Draft store: id safety, one-rename creation, the rollback of a failed append, an idempotent discard. |
| `EdgeWeightParityTest` | `com.gamma.la.core` | unit | `la/inspecto-la-core/src/test/java/com/gamma/la/core/EdgeWeightParityTest.java` | D-S4 parity for the {@code "kind · N"} edge-label weight parse, {@link WorkingSetGraphInput#edgeWeight}: the server half |
| `FsEmbedGrantStoreTest` | `com.gamma.la.core` | unit | `la/inspecto-la-core/src/test/java/com/gamma/la/core/FsEmbedGrantStoreTest.java` | {@link EmbedGrantStoreContract} over the filesystem implementation, plus what only a directory tree can show. |
| `FsInvestigationStoreContractTest` | `com.gamma.la.core` | unit | `la/inspecto-la-core/src/test/java/com/gamma/la/core/FsInvestigationStoreContractTest.java` | {@link InvestigationStoreContract} over the filesystem implementation, plus what only a directory tree can show: that the |
| `FsInvestigationStoreTwoJvmRaceTest` | `com.gamma.la.core` | unit | `la/inspecto-la-core/src/test/java/com/gamma/la/core/FsInvestigationStoreTwoJvmRaceTest.java` | {@link InvestigationStoreTwoJvmRace} over {@link FsInvestigationStore} on one shared directory (the shared-volume pod case). |
| `GraphEngineParityTest` | `com.gamma.la.core` | unit | `la/inspecto-la-core/src/test/java/com/gamma/la/core/GraphEngineParityTest.java` | D-4 step 4, engine == inspecto-graph == the browser. Every one of the 29 algorithms is run THROUGH {@link InMemoryGraphEngine} |
| `GraphEngineRequestTest` | `com.gamma.la.core` | unit | `la/inspecto-la-core/src/test/java/com/gamma/la/core/GraphEngineRequestTest.java` | D-4 step 4: the catalogue, request validation and cancellation of {@link InMemoryGraphEngine}. |
| `GraphRunServiceRetuneTest` | `com.gamma.la.core` | unit | `la/inspecto-la-core/src/test/java/com/gamma/la/core/GraphRunServiceRetuneTest.java` | {@code LA-CONFIG-LIMITS-1} P4: a live service takes new install-wide limits ({@link GraphRunService#retune}) - the ceilings |
| `GraphRunServiceTest` | `com.gamma.la.core` | unit | `la/inspecto-la-core/src/test/java/com/gamma/la/core/GraphRunServiceTest.java` | D-4 step 5: the {@link GraphRunService} lifecycle - budget before work, deadline, cancel latency, the four terminal |
| `InvestigationConnectFoldTest` | `com.gamma.la.core` | unit | `la/inspecto-la-core/src/test/java/com/gamma/la/core/InvestigationConnectFoldTest.java` | LA-SEED-CONNECT-1: folding a sealed {@code connect} - seeds at hop 0, route nodes one hop past a seed, whatever the row order. |
| `InvestigationDeleteTest` | `com.gamma.la.core` | unit | `la/inspecto-la-core/src/test/java/com/gamma/la/core/InvestigationDeleteTest.java` | LA-INVESTIGATION-DELETE-1 at the store: the filesystem one removes everything and sweeps; a backend that does not override the default answers 503. |
| `InvestigationExpandedFoldTest` | `com.gamma.la.core` | unit | `la/inspecto-la-core/src/test/java/com/gamma/la/core/InvestigationExpandedFoldTest.java` | LA-UNEXPANDED-MARKER-1: an entity an effective expand had in its frontier is {@code expanded}; it never enters the hash. |
| `InvestigationHubDegreeFoldTest` | `com.gamma.la.core` | unit | `la/inspecto-la-core/src/test/java/com/gamma/la/core/InvestigationHubDegreeFoldTest.java` | The hub degree ({@code contacts}) an entity carries is the LATEST sealed degree, and it never enters the hash. |
| `InvestigationMembersTest` | `com.gamma.la.core` | unit | `la/inspecto-la-core/src/test/java/com/gamma/la/core/InvestigationMembersTest.java` |  |
| `InvestigationStoresTest` | `com.gamma.la.core` | unit | `la/inspecto-la-core/src/test/java/com/gamma/la/core/InvestigationStoresTest.java` | {@code investigations.backend} selection and its fail-closed refusals (design D-IS8, slice S6). No database needed. |
| `InvestigationTimeRecurringTest` | `com.gamma.la.core` | unit | `la/inspecto-la-core/src/test/java/com/gamma/la/core/InvestigationTimeRecurringTest.java` |  |
| `LinkIdsTest` | `com.gamma.la.core` | unit | `la/inspecto-la-core/src/test/java/com/gamma/la/core/LinkIdsTest.java` | D-U9 remainder: the link wire id is the evaluator's key {@code source␀target␀kind}, reversibly and URL-safely encoded. <!-- vocab-allow: quoted Javadoc --> |
| `LinkTemporalDetectorTest` | `com.gamma.la.core` | unit | `la/inspecto-la-core/src/test/java/com/gamma/la/core/LinkTemporalDetectorTest.java` |  |
| `PatternStageMotifTest` | `com.gamma.la.core` | unit | `la/inspecto-la-core/src/test/java/com/gamma/la/core/PatternStageMotifTest.java` | LA's stage → the platform {@link TemporalMotifMatcher} stage: every field carried, the node kind folded to {@code admitsNodes}. |
| `SnapshotStoreTest` | `com.gamma.la.core` | unit | `la/inspecto-la-core/src/test/java/com/gamma/la/core/SnapshotStoreTest.java` | {@code AUDIT-LOG-UNBOUNDED-READ-1}: {@link SnapshotStore#attachmentsOf} streams the one |
| `WorkingSetGraphInputTest` | `com.gamma.la.core` | unit | `la/inspecto-la-core/src/test/java/com/gamma/la/core/WorkingSetGraphInputTest.java` | D-4 step 4: the Working Set adapter. Rows are built in the exact shape {@code WorkingSetRoutes.tables()} caches |
| `WorkingSetSizeLimitTest` | `com.gamma.la.core` | unit | `la/inspecto-la-core/src/test/java/com/gamma/la/core/WorkingSetSizeLimitTest.java` |  |
