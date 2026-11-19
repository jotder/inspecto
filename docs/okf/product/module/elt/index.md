---
type: ProductItem
title: ELT — ingestion, Pipelines and step registry
item: module.elt
description: Documentation hub for module.elt — every document about it, by slot.
tags: [product-item, module]
---

# ELT — ingestion, Pipelines and step registry (`module.elt`)

> **Documentation hub** (`PRODUCT-DOCS-HUB-1`) for Product Map Item
> [`module.elt`](../../../../../product-map/module/elt.toon). Editions, add-ons, order codes and dependencies are read
> from the Product Map — never restated here. **Generated** by `tools/render-product-docs.mjs` from the files in
> this folder — add or rename a file (`<slot>-<hub>.md` / `<slot>-<topic>.md`), never edit this table.

| Slot | Status | Document |
|---|---|---|
| plan | current | [module.elt - board row detail](plan-board-detail.md) · [Acquisition, ingestion and Pipelines (ACQ, ING, PIP) — plan](plan-elt.md) |
| spec | current | [The Unpack stage — specification](spec-acquisition-unpack.md) · [Acquisition — specification](spec-acquisition.md) · [Consignments — addressing and concurrency](spec-consignments.md) · [Ingest — the ingestion SPI, ingesters, Consignment coordination and branch-aware ingest](spec-ingest.md) · [Decode Profile — specification](spec-parsing-decode-profile.md) · [Sample profiler — one sniff and one type answer](spec-parsing-sample-profiler.md) · [Parsing — frontends, the parsing block, parser plugins, XML and ASN.1, Decode Profiles](spec-parsing.md) · [Pipeline execution — run steps](spec-pipeline-execution-run-steps.md) · [Pipeline execution — engines, lanes, test run, dry run, park and drain, post-sync chains](spec-pipeline-execution.md) · [Pipeline model — node types, the Step catalog, transforms and the editable round-trip](spec-pipeline-model.md) · [Sinks — writers, store layout, destinations and object-storage export](spec-sinks.md) · [Step catalog — every Step kind, delivered and pending](spec-step-catalog.md) |
| architecture | current | [ELT — architecture](architecture-elt.md) |
| features | current | [Collectors, parsing, ingest and Pipelines — features](features-elt.md) |
| feature-db | n/a | no feature rows derive to this item (`tools/render-feature-db.mjs`) |
| user-guide | current | [Connection and Collector](user-guide-concept-connection-collector.md) · [Expectation](user-guide-concept-expectation.md) · [Pipeline and Steps](user-guide-concept-pipeline.md) · [ELT — user guide](user-guide-elt.md) · [Tutorial 2 — Make it a Pipeline with Expectations](user-guide-tutorial-pipeline-expectations.md) |
| admin-guide | current | [ELT — admin guide](admin-guide-elt.md) |
| api | current | [Collectors, parsing, ingest and Pipelines — API](api-elt.md) |
| tests | current | [ELT — ingestion, Pipelines and step registry — tests inventory](tests-inventory.md) |
| brochure | n/a | per edition / solution pack |

Status: `current` (in this folder) · `missing` (a real gap) · `n/a` (decided: the reason says why; per-hub decisions in `slots-na.toon`).

## Open work

Generated from the rows of [`docs/BACKLOG.md`](../../../../BACKLOG.md) tagged `module.elt` — the board is the only list; change the row there.

| Row | Priority | Kind | Title | Hold |
|---|---|---|---|---|
| `AUTHORING-REDESIGN-1` | P3 | feature | Only letter (d) of the authoring redesign is left; no CREATE MACRO exists in any main source. | demand |
| — | P3 | feature | Step Processor catalog | — |
| `PARSING-DERIVE-EDITOR-1` | P3 | feature | slice 4 of Parsing D9: an optional profile editor for ASN.1 derive / children. | operator |
| `BASIC-ELT-PROFILE-1` | P2 | feature | Make Basic onboarding a locked profile of ELT, retiring the parallel Basic backend and LA UI layers that duplicate ELT parts. | — |
| `BASIC-WATCH-ARRIVALS-1` | P3 | defect | A watched inbox's scheduled run leaves no arrivals sidecar line and no watchError for a quarantined file (filed 2026-11-19, BASIC-ELT-PRO… | — |
| — | P3 | feature | D-11 hand-authored relations component | demand |
| `DUCKLAKE-REAL-DRIVER-FAILURE-PROOF-1` | P3 | chore | no test proves that a real DuckLake driver's registration failure is non-fatal (filed 2026-11-16; untracked since SPEC-NOPROOF-1 closed o… | external |
