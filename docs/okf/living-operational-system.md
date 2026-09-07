---
type: Architecture
title: Inspecto as a Living Operational System
description: The platform-wide north star — seven cooperating networks over one metadata model, and the principles each one is enforced by.
resource: inspecto-ui/src/app/inspecto/component-model
tags: [architecture, north-star, metadata, signal, decision, philosophy]
timestamp: 2026-09-07T00:00:00Z
---

# Inspecto as a Living Operational System

The architecture philosophy agreed with the product owner **2026-07-06**, distilled here **2026-09-07**
when its rework roadmap (R1–R6, all shipped 2026-07-06) was archived with
[`plans-archive/living-operational-system.md`](../archived-documents/plans-archive/living-operational-system.md).

⚠ **This file is the north star, not a state report.** It says what the shape *is* and what each network is
*for*; it deliberately carries no "what exists today" column, because the archived plan's did and it went
stale — by the time it was archived it described gaps its own R4/R5 slices had closed and cited two source
files (`inspecto/mock/signals.ts`, `inspecto/mock/decision.ts`) that had been deleted with the mock backend.
For current state, follow the per-network pointers below. Vocabulary is binding per
[`GLOSSARY.md`](../GLOSSARY.md).

## The thesis

The platform is a **living operational organism**, not an ETL tool with screens: a set of independent,
interconnected **networks** — each with one responsibility — cooperating over **one metadata model**. No
artifact exists in isolation; every record, decision, signal, job, visualization and user action
participates in one or more networks.

The load-bearing consequence: because the *architecture* is the networks and their contracts — not any
particular engine — the platform can evolve from a deterministic rule-based system into an AI-driven one
**without changing its fundamental design**. AI is just another decision engine plugged into the Decision
Network. That is the claim every slice below is meant to keep true.

**Biological reading** — Data Lineage = circulatory (every record has ancestry; data is never lost, it
changes state) · Signals = nervous (lightweight facts that announce, never decide) · Decisions = brain
(interpret signals; deterministic first, AI-augmented later) · Pipelines = metabolism (consume data +
signals + decisions, produce new ones — the tissue connecting every network).

## The seven networks

| Network | Responsibility | Where its as-built truth lives |
|---|---|---|
| **Data** | records, datasets, storage, transformations, indexing, record lineage, replay | [`backend/engine/db-layer.md`](backend/engine/db-layer.md) · [`ingestion.md`](backend/engine/ingestion.md) · [`consignment-addressing.md`](backend/engine/consignment-addressing.md) |
| **Signal** | events, notifications, alerts, triggers, scheduler outputs | [`backend/control-plane/signal-backbone.md`](backend/control-plane/signal-backbone.md) · [`events-metrics.md`](backend/control-plane/events-metrics.md) |
| **Decision** | Expectations, Alert Rules, Decision Rules, AI reasoning, recommendations, approvals | [`backend/control-plane/decision-rules.md`](backend/control-plane/decision-rules.md) · [`frontend/features/inline-ai-authoring.md`](frontend/features/inline-ai-authoring.md) |
| **Execution** | jobs, processors, workflow execution, scheduling, retries | [`backend/control-plane/jobs.md`](backend/control-plane/jobs.md) · [`backend/pipeline-graph/execution-lanes.md`](backend/pipeline-graph/execution-lanes.md) |
| **Metadata** | schemas, datasets, query metadata, lineage, catalog, dimensions, measures — **the spine** | [`backend/components/component-registry.md`](backend/components/component-registry.md) · [`backend/control-plane/metadata-bundle.md`](backend/control-plane/metadata-bundle.md) |
| **Presentation** | dashboards, widgets, maps, graphs, reports, investigation views | [`frontend/features/studio.md`](frontend/features/studio.md) · [`geo-map.md`](frontend/features/geo-map.md) |
| **Security** | users, permissions, audit, ownership, authentication | [`backend/editions/auth-security.md`](backend/editions/auth-security.md) |

**Security is deliberately out of core** — Personal is auth-free; Standard/Enterprise re-add it through the
security module and the `Authenticator` SPI. The forward seam is already shaped: Lens **capability signals**
(`canAuthorWorkbench()` …) that RBAC re-derives. ⛔ A Lens is a UI annotation, never a permission.

**External Integrations** (the "gas line") ride the Execution and Signal networks: connections + collectors
inbound, consequence-invoked APIs outbound.

## Everything is Metadata

A new artifact becomes a **Component kind** in the one registry — a config shape, `deriveRefs`, and (where
composite) wiring — never a bespoke store. First-class kinds today: `grammar` · `schema` · `transform` ·
`sink` · `rule` · `dataset` · `widget` · `dashboard` · `requirement` · `reconciliation` ·
`link-analysis-view` · `geo-map-view` · `job` · `query` · `decision-rule`, with `pipeline` and `connection`
as adjacent stores. Each has identity, lineage (via refs), transportability (Metadata Bundle) and reuse.

Still promised by the philosophy and **not** yet kinds: investigation template, alert/notification
definition, case, report, user action. They need no new machinery — the registry already takes them.
⛔ `scheduler`/`trigger` are deliberately **not** separate kinds: cron and event are mutually exclusive job
fields with no second consumer, so the schedule *wiring* is the trigger made first-class. That is the
standing rule — **no abstraction without a second consumer**.

Versioning is the honest gap: provenance `contentHash`/`originVersion` in bundle v2 is the first step; a
real version history is a backend concern and is not built.

## Guiding principles → what enforces them

| Principle | Enforced by |
|---|---|
| Everything is Metadata | the Component registry; a new artifact is a new kind, never a bespoke store |
| Everything has Lineage | `deriveRefs` + per-run provenance; the bundle carries refs + provenance |
| Everything Emits Signals | the Signal envelope; emitting is part of a kind's execution contract |
| Everything is Composable | parts + wiring; consequences and pipelines compose the rest |
| Everything is Transportable | the Metadata Bundle and its transfer surfaces |
| Everything is Observable | the signal ledger + audit log + per-run provenance |
| AI is a Decision Layer | the decision-engine seam — assist plugs in, architecture untouched |
| One Metadata Model, Many Networks | this file + the component registry + GLOSSARY discipline |

## Vocabulary

**Signal**, **Consequence**, **Decision Engine** and **Result Set** were proposed here and are now **binding**
in [`GLOSSARY.md`](../GLOSSARY.md). *Query* and *Parameter* were never formally adopted — use them as
ordinary words, not as capitalized concepts (`BACKLOG.md` §6).
