---
type: Feature
title: "Graph algorithms — spec"
description: "Developer reference for platform/inspecto-graph: package, types, entry points, run control and test idioms."
tags: [graph, spec]
timestamp: 2026-10-19T00:00:00Z
---

# Graph algorithms — spec

Module `platform/inspecto-graph`, package `com.gamma.graph`. Plain JDK plus `jackson-databind`; no
dependency on any other Inspecto module. `module.toon`: `buildRole: platform`, `offeringRole: base`,
`bindingTime: build` — in every edition. Design and the caller table: [architecture](architecture-overview.md).

## Types

Declared in `GraphAlgorithms`: `Node(id, label)`, `Edge(id, source, target)`, `Graph(nodes, edges)`,
`Selection(nodeIds, edgeIds)` and `Direction { OUT, IN, BOTH }`. All ids are strings; edge weights are
passed separately as `Map<String, Double>` keyed by edge id (Link Analysis parses them).
`TemporalMotifMatcher` has its own timed `Edge(id, source, target, t)`.

## Entry points

All algorithms are `public static` methods on final utility classes:

| Class | Notes |
|---|---|
| `GraphAlgorithms` | degree, connected components, triangles, neighbourhood; owns the shared records |
| `GraphPaths` | `shortestPath`, `weightedShortestPath`, `allPaths(g, from, to, limit, maxHops, …)`, `maxFlow`, `egoNetwork`, `maximumSpanningForest` |
| `GraphCentrality` | betweenness (Brandes), closeness, Jaccard, link prediction |
| `GraphIterative` | PageRank, eigenvector, Katz, HITS, `detectCommunities`, Louvain |
| `GraphStructure` | k-core, bridges, articulation points, cliques, cycles |
| `GraphPropagation` | `propagatedRisk(g, own, seeds, weights, …)`; at most `FACTOR_LIMIT` (5) explanation factors per node |
| `GraphSuspicion` | `suspicionScore` blend |
| `TemporalMotifMatcher` | `match(stages, legsByStage, limit)`; `WORK_BUDGET` = 500,000 leg examinations, then `truncated` |
| `UnionFind` | deterministic disjoint sets, used by `EntityRegistry` in `platform/inspecto-entity-store` |

## Run control seam

Long algorithms take an optional `RunControl`; the overloads without one pass `RunControl.NONE` (a
no-op). Factories: `create()` (cancel and observe only), `withBudget(workCeiling)`,
`withTimeout(millis)`, `of(millis, workCeiling)`. The algorithm calls `checkpoint()` once per bounded
unit of work; the clock is read every 256 checkpoints. A stop throws `GraphAborted` with
`Reason { CANCELLED, DEADLINE, BUDGET }`, the work reached and the ceiling — never a partial result.
Another thread may `cancel()` and read `work()` / `fraction()`.

## Adding an algorithm

1. Put it in the class of its family as a static method over `Graph`; take a `RunControl` if its cost
   is more than linear.
2. If it has a browser twin, add a hand-derived fixture shared with the TypeScript test and a
   `*ParityTest`; if server-only, hand-computed unit tests (as `GraphPropagationTest`).
3. Register it in the Link Analysis catalogue (`Algorithm`, `InMemoryGraphEngine`) — that is where cost
   classes and the API live, not here.

Test inventory: [tests](tests-inventory.md).
