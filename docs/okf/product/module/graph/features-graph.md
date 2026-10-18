---
type: Feature
title: "Graph library — features"
description: "The graph library is the module platform/inspecto-graph (package com.gamma.graph): server-side graph algorithms over plain node and edge ids — paths, flow, centrality, PageRank, communities, k-core, cliques, link prediction, propagated risk, a suspicion blend and a temporal motif matcher."
tags: [graph, graph, features, presales]
timestamp: 2026-10-19T00:00:00Z
---

# Graph library — features

**Availability:** Offering: all tiers (a core thin jar in every edition).

## What it does

The graph library is the module `platform/inspecto-graph` (package `com.gamma.graph`): server-side
graph algorithms over plain node and edge ids — paths, flow, centrality, PageRank, communities,
k-core, cliques, link prediction, propagated risk, a suspicion blend and a temporal motif matcher.
It is plain JDK with no dependency on any other Inspecto module.

It began as a port of the Link Analysis SPA's algorithms and was moved into the platform by the
analytics core plan (decision D-AN2) so Fraud, AML and Case Management can use it without depending
on Link Analysis. The backend description of Link Analysis, which is its main caller, is
[link-analysis (backend)](../la-core/architecture.md).

## Where it fits

It is a leaf library. Link Analysis keeps the algorithm catalogue (`Algorithm`), dispatch
(`InMemoryGraphEngine`), the cost ceilings and budget (`GraphBudget`) and edge-weight parsing; this
module keeps only the algorithms. Callers found in the code:

| Caller | Use |
|---|---|
| `la/inspecto-la-core` (`GraphEngine`, `InMemoryGraphEngine`, `GraphRunService`, `PatternQueryCompiler`) | run algorithms and motif search for an Investigation |
| `la/inspecto-la-storage` (`SqlGraphEngine`, `RoutingGraphEngine`) | the SQL-backed graph engine |
| `la/inspecto-la-api` (`PatternRoutes`, `GraphResultJson`) | route and JSON shape |
| `platform/inspecto-entity-store` (`EntityRegistry`) | `UnionFind` for entity merging |

More: [screens](user-guide-graph.md) · [API](api-graph.md) · [architecture](architecture-overview.md).
