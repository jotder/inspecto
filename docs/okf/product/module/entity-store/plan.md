---
type: Feature
title: "Entity store — plan"
description: "Requirement of record for the entity-store platform module: the identity fact log, Entity Lists, Entity Types, Industry packs and Link Analysis settings and limits."
tags: [entity-store, plan]
timestamp: 2026-10-19T00:00:00Z
---

# Entity store — plan

The entity store is the host-free model shared by Entity Lists, Link Analysis and Screening. It has no
requirement row of its own: it carries the storage half of the rows below, all recorded in
[BACKLOG](../../../../BACKLOG.md).

## Required and built

| Row | What this module carries |
|---|---|
| `LA-17` (design: [link-analysis-entity-model-design](../../../../archived-documents/plans-archive/link-analysis-entity-model-design.md) §4.1, §4.2) | `EntityFactLog` (append-only, hash-chained identity fact log), `EntityRegistry` (pure fold of Entity Lists as of a sequence), `EntityTypes` (per-Space Entity Types with a closed normaliser set) |
| `LA-19` (decisions D-U6, D-U7) | `MaskTokens` (keyed `masked:` pseudonyms), `maskingMode` and the four-eyes thresholds in `LinkAnalysisSettings` |
| `ASSURE-ENTITY-LISTS-1` (WS-12) | range, prefix and CIDR entries (`EntityListEntries`), expiring entries, the Parquet sidecar (`EntityListSidecar`) |
| `LA-CONFIG-LIMITS-1` ([plan](../../../../archived-documents/plans-archive/la-configurable-limits-plan.md)) | `LaLimit` catalogue, `LaLimits` reader and writer, `LaLimitValues` |
| `LA-EDITIONS-1` §3, `LDP-D7` ([plan](../../../../archived-documents/plans-archive/inspecto-la-editions-plan.md)) | `IndustryPack`, `IndustryPacks` and the bundled `telecom` and `mobile-money` packs |
| `MODULE-REORG-P7-CONTRACTS` | the package moved to `com.gamma.entitystore` so `com.gamma.control` no longer spans modules |

D-M7 (operator 2026-10-13) moved the Space identity key out of the fact log directory into the Space secrets
directory (`MaskTokens.spaceKey`).

## Left

- `LA-CONFIG-LIMITS-RESIDUALS-1` (P3): what the limits work left; as-built in [la-core architecture](../la-core/architecture.md) §9-§10.
- `LA-EDITIONS-RESIDUALS-1` (P3): pack and edition residuals.
- `ASSURE-ENTITY-LISTS-RESIDUALS-1` is closed: its demand-gated rest (range / CIDR entries in `excludeBy` / `seedBy`) was retired to BACKLOG §6 on 2026-10-18.

## Refused

- A pack that adds a concept: a pack only relabels concepts the core has, and a pack breaking any rule is refused whole.
- An open normaliser set: the normalisers stay closed because the browser mints the same keys.
- Caching the Entity List fold: reading the log verifies the whole chain anyway, so a cache would save only the linear fold.
- A silent fold of a broken log: any chain mismatch throws `BrokenChainException`.
