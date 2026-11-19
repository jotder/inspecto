---
type: Architecture
title: ELT — architecture
description: How the ELT layer is built, in the order data moves — acquisition (the poll cycle and phases A–F, Collectors and Connections, multi-location ingest by composition), Stage-1 parsing and ingest, Pipelines and Steps, and the execution engine internals (code homes since the WS-D split, the M..N multiplexer ingest design and its non-goals, the Pipeline graph backend model — derived graph, token rule, lift, validator, commit, triggers, back-pressure, provenance — the Consignment status flow, graceful Space stop and the Space directory layout).
resource: platform/inspecto-acquire/src/main/java/com/gamma/acquire, platform/inspecto-etl/src/main/java/com/gamma/etl, inspecto/, platform/inspecto-engine/src/main/java/com/gamma/pipeline, platform/inspecto-etl/src/main/java/com/gamma/etl/ConsignmentAuditWriter.java
tags: [elt, acquisition, phases, dedup, watermark, stability, ledger, multi-location, composition, pattern, module, core, composition-root, inspecto-processor, engine, architecture, stage1, multiplexer, ingest, layout, pipeline-graph, lift, node-types, derived, tokens, sinks, consignment, observability, audit, provenance, quarantine, status]
timestamp: 2026-11-18T00:00:00Z  # merged 2026-11-18 from the elt, acquire and engine architecture pages; the multi-location ingest part carried type Practice (2026-08-25)
item: module.elt
audience: internal
status: current
verified: 2026-11-19 last-edit
code: platform/inspecto-etl
---

# ELT — architecture

**What this covers.** How the ELT layer is built, in the order data moves: acquisition (the Collector cycle
and its phases A to F, Collectors and Connections, ingesting one feed from several locations), Stage-1
parsing and ingest (Consignments, the parse frontends, quarantine, commit), Pipelines and Steps (Step kinds,
the graph round-trip, the two execution lanes), and the execution engine internals underneath (code homes,
the M..N multiplexer, the Pipeline graph model, Consignment status, graceful stop, directory layout). Each
part keeps its own status line and the date it was checked against the code.

**How it relates to the platform architecture.** The [platform architecture](../../layer/platform/architecture-platform.md)
is the whole-system view: modules, tech stack, extension points, the five-step data path, storage, events,
security. This page zooms into steps 1–3 of that data path (*Collect*, *Parse and ingest*, *Shape*) and
does not restate module layout or packaging — where the two seem to differ, the platform page is the
reference for those, and this page for how the ELT layer behaves.

**Contents**

- **Acquire**
  - [Data Acquisition Framework](architecture-elt-acquisition-parsing.md#data-acquisition-framework) — the cycle, back-pressure and phases A–F.
  - [Collectors](architecture-elt-acquisition-parsing.md#collectors) — the Collector and Connection module summary: schemes, configuration, limits (§A1–§A9).
  - [Multi-location ingest — the composition pattern](architecture-elt-acquisition-parsing.md#multi-location-ingest--the-composition-pattern) — N
    single-Collector Pipelines and one merge, never a Collector list.
- **Parse and ingest** — [Parsing and ingest](architecture-elt-acquisition-parsing.md#parsing-and-ingest) — Consignments, the parse frontends, quarantine and commit (§1–§9).
- **Pipeline** — [Pipelines and Steps](architecture-elt-acquisition-parsing.md#pipelines-and-steps) — Step kinds, the two lanes, the round-trip, at-rest execution (§1–§9).
- **Engine internals** — [Execution engine internals](architecture-elt-acquisition-parsing.md#execution-engine-internals)
  - [E1. Where the engine's code lives](architecture-elt-engine-design.md#e1-where-the-engines-code-lives)
  - [E2. Design Philosophy & Scope](architecture-elt-engine-design.md#e2-design-philosophy--scope) — the M..N multiplexer ingest engine
  - [E3. How a file becomes Tables](architecture-elt-engine-design.md#e3-how-a-file-becomes-tables) — bootstrap, continuous processing, the at-rest transform chain
  - [E4. The Pipeline graph — the backend model](architecture-elt-graph-consignments.md#e4-the-pipeline-graph--the-backend-model) (E4.1–E4.11)
  - [E5. Consignment status flow (the flowfile question)](architecture-elt-graph-consignments.md#e5-consignment-status-flow-the-flowfile-question)
  - [E6. Stopping a Space (graceful stop)](architecture-elt-graph-consignments.md#e6-stopping-a-space-graceful-stop)
  - [E7. Directory layout](architecture-elt-graph-consignments.md#e7-directory-layout)
- [References](architecture-elt-graph-consignments.md#references)

**Section numbers.** *Parsing and ingest* and *Pipelines and Steps* keep their own §1–§9 (each part is
numbered on its own). The Collectors part's sections are §A1–§A9 and the engine internals §E1–§E7 (with
§E4.1–§E4.11): the letter names the part a number came from (acquire and engine pages, merged here
2026-11-18).

## Parts

This page is split by topic into 3 parts; each part lists the sections it holds.

1. **[Acquisition, Collectors, parsing and Pipelines](architecture-elt-acquisition-parsing.md)** — Data Acquisition Framework · Collectors · Multi-location ingest — the composition pattern · Parsing and ingest · Pipelines and Steps · Execution engine internals
2. **[Engine code, design scope and how a file becomes Tables](architecture-elt-engine-design.md)** — E1. Where the engine's code lives · E2. Design Philosophy & Scope · E3. How a file becomes Tables
3. **[The Pipeline graph, consignment status and directory layout](architecture-elt-graph-consignments.md)** — E4. The Pipeline graph — the backend model · E5. Consignment status flow (the flowfile question) · E6. Stopping a Space (graceful stop) · E7. Directory layout · References
