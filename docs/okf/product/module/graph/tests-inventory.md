---
type: TestsInventory
title: Graph algorithms — tests inventory
item: module.graph
description: Generated list of the test classes for module.graph, with how to run them.
tags: [tests, module]
---

# Graph algorithms — tests inventory (`module.graph`)

> **Generated** by `tools/render-tests-inventory.mjs` from the git-tracked test sources under `platform/inspecto-graph/src/test/` — never edit this table; add or
> rename tests and re-run the tool. Hub: [index](index.md) · Product Map Item
> [`module.graph`](../../../../../product-map/module/graph.toon).

15 test class(es): 15 unit, 0 real-HTTP control-plane, 0 IT.
Kind is inferred: `*IT` → IT; a `ControlApi*` class, or one using both `HttpClient` and `ControlApi` → real-HTTP
control-plane test; else unit.

Run the whole module, or chosen classes (commas between classes, never `+`):

```bash
mvn -o test -pl platform/inspecto-graph
mvn -o test -pl platform/inspecto-graph -Dtest=GraphAlgorithmsParityTest,GraphCentralityParityTest
```

| test class | package | kind | file | summary |
|---|---|---|---|---|
| `GraphAlgorithmsParityTest` | `com.gamma.graph` | unit | `platform/inspecto-graph/src/test/java/com/gamma/graph/GraphAlgorithmsParityTest.java` | D-S4 parity — the server half, tranche A slice 1. Runs {@code graph-algorithms-parity.fixture.json}, the SAME file |
| `GraphCentralityParityTest` | `com.gamma.graph` | unit | `platform/inspecto-graph/src/test/java/com/gamma/graph/GraphCentralityParityTest.java` | D-S4 parity — the server half, single-pass float centrality lane. Runs {@code graph-centrality-parity.fixture.json}, |
| `GraphComplexityEquivalenceTest` | `com.gamma.graph` | unit | `platform/inspecto-graph/src/test/java/com/gamma/graph/GraphComplexityEquivalenceTest.java` | LA-GRAPH-QUADRATIC-1 - {@code kCore}, {@code weightedShortestPath} and {@code linkPrediction} now run on a bucket |
| `GraphIterativeParityTest` | `com.gamma.graph` | unit | `platform/inspecto-graph/src/test/java/com/gamma/graph/GraphIterativeParityTest.java` | D-S4 parity — the server half, tranche A, iterative + communities slice. Runs |
| `GraphPathsParityTest` | `com.gamma.graph` | unit | `platform/inspecto-graph/src/test/java/com/gamma/graph/GraphPathsParityTest.java` | D-S4 parity — the server half, paths-and-flow lane. Runs {@code graph-paths-parity.fixture.json}, the SAME file <!-- vocab-allow: quoted Javadoc --> |
| `GraphPropagationTest` | `com.gamma.graph` | unit | `platform/inspecto-graph/src/test/java/com/gamma/graph/GraphPropagationTest.java` | {@link GraphPropagation#propagatedRisk}: hand-computed graphs (no TypeScript twin, so no parity fixture). |
| `GraphStructureParityTest` | `com.gamma.graph` | unit | `platform/inspecto-graph/src/test/java/com/gamma/graph/GraphStructureParityTest.java` | D-S4 parity — the server half, structure lane. Runs {@code graph-structure-parity.fixture.json}, the SAME file |
| `GraphSuspicionParityTest` | `com.gamma.graph` | unit | `platform/inspecto-graph/src/test/java/com/gamma/graph/GraphSuspicionParityTest.java` | D-S4 parity — the server half, suspicion score. Runs {@code graph-suspicion-parity.fixture.json}, the SAME file |
| `LaAuditGoldenTest` | `com.gamma.graph` | unit | `platform/inspecto-graph/src/test/java/com/gamma/graph/LaAuditGoldenTest.java` | The {@code la-audit} Space Template's seven planted stories (plan section 2.B), over the SHIPPED corpus |
| `LaClaimsGoldenTest` | `com.gamma.graph` | unit | `platform/inspecto-graph/src/test/java/com/gamma/graph/LaClaimsGoldenTest.java` | The {@code la-claims} Space Template's seven planted stories (docs/superpower/la-multi-vertical-showcase-plan.md, 2.C), over the SHIPPED |
| `LaMobileMoneyGoldenTest` | `com.gamma.graph` | unit | `platform/inspecto-graph/src/test/java/com/gamma/graph/LaMobileMoneyGoldenTest.java` | The {@code la-mobile-money} Space Template's seven planted stories (docs/superpower/la-multi-vertical-showcase-plan.md, |
| `LaShowcaseGoldenTest` | `com.gamma.graph` | unit | `platform/inspecto-graph/src/test/java/com/gamma/graph/LaShowcaseGoldenTest.java` | DR-S10 - the {@code la-showcase} Space Template's demo promises, over the SHIPPED corpus |
| `RunControlTest` | `com.gamma.graph` | unit | `platform/inspecto-graph/src/test/java/com/gamma/graph/RunControlTest.java` | D-4 step 2 — every job-class algorithm honours {@link RunControl}: cancel mid-run, an expired deadline, a work |
| `TemporalMotifMatcherGoldenTest` | `com.gamma.graph` | unit | `platform/inspecto-graph/src/test/java/com/gamma/graph/TemporalMotifMatcherGoldenTest.java` | Golden tests of the motif search alone (no SQL): exact node order, edge order, layers and the truncated flag, |
| `UnionFindTest` | `com.gamma.graph` | unit | `platform/inspecto-graph/src/test/java/com/gamma/graph/UnionFindTest.java` |  |
