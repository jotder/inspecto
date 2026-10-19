---
type: Feature
title: "Entity store — spec"
description: "Implementation detail of platform/inspecto-entity-store: the identity fact log format, the Entity List fold, Entity Types, masking, Industry packs and the Link Analysis settings and limits."
tags: [entity-store, spec]
timestamp: 2026-10-19T00:00:00Z
---

# Entity store — spec

Module: `platform/inspecto-entity-store`, package `com.gamma.entitystore`, `buildRole: platform`,
`offeringRole: base`, `bindingTime: build` ([module.toon](../../../../../platform/inspecto-entity-store/src/main/resources/META-INF/inspecto/module.toon)).
It holds no HTTP routes: the Entity List routes live in [entity-list](../entity-list/api-routes.md) and Link
Analysis in [la-core](../la-core/api-link-analysis.md); both read this module.

## Classes

| Class | Role |
|---|---|
| `EntityFactLog` | Per-Space append-only identity fact log at `<Space config root>/audit/entity-facts/`. |
| `EntityRegistry` | Pure fold of the log into Entity Lists as of one sequence number. Not cached. |
| `EntityListFacts` | Host-free read and append helpers shared by the Entity List routes and Link Analysis. |
| `EntityListEntries` | Range entries stored as one canonical string: `prefix:<key>`, `range:<lo>..<hi>` (same-length keys), `cidr:<network>/<len>` (IPv4 or IPv6, no host bits). |
| `EntityListSidecar` | Derived Parquet copy of a list at `<dataRoot>/entity_list_<id>/entries.parquet`, read by a Dataset with `physicalRef: entity_list_<id>`. |
| `EntityTypes` | Per-Space Entity Types: key normaliser (closed set), masking, Dataset classifications. Stored as `entity_types` in `link-analysis.toon`; `null` inherits the defaults, a stated list replaces them, an empty list is refused. |
| `MaskTokens` | `masked:<16 hex>` = HMAC-SHA256 of the id under a random key. |
| `LinkAnalysisSettings` | Per-Space `link-analysis.toon`: node caps, `maskingMode` (default `typed`), four-eyes thresholds, Entity Types, distinct caps, hub threshold, graph-run knobs. |
| `LaLimit`, `LaLimits`, `LaLimitValues` | The one catalogue of Link Analysis limits, with range checks and the per-Space resolution. |
| `IndustryPack`, `IndustryPacks` | Classpath-discovered vertical vocabularies; refused whole on any rule break. |

## Fact log format

- One immutable JSON file per fact, named by a 12-digit zero-padded sequence (`000000000001.json`).
- Fields in order: `seq`, `at`, `actor`, `reason`, `kind`, `listId`, the kind's payload, `prevHash` (SHA-256 of the previous file's bytes; `""` for seq 1).
- Write: staged temp file, forced, then published by a hard link (`publishNew`), so an existing seq is never overwritten, even across processes. In-JVM writers serialise on `lock()`.
- Read verifies the whole chain each time; a gap, a seq mismatch or a wrong `prevHash` throws `BrokenChainException`. Tail truncation is caught only by a cited head hash.
- Fact kinds folded by `EntityRegistry`: `list.created`, `list.member.added` (optional `expiresAt`), `list.member.removed`, `list.range.added`, `list.range.removed`, `list.retired`. The normaliser is sealed at `list.created`.

## Sidecar

Columns: `list_id, purpose, entity_type, match, entry, lo, hi, expires_at, added_at, added_by, reason`.
Expired entries are kept with `expires_at`; a consumer filters on it. The writer only touches a directory
carrying its own `.entity-list-output` marker. A failed sidecar write never undoes the fact.

## Masking keys

- `MaskTokens.key(dir)`: a `mask.key` per directory, created on first use and published by hard link.
- `MaskTokens.spaceKey(writeRoot)`: the Space identity key `.risk-score-mask.key` in the Space secrets directory, shared by Link Analysis, Entity Lists, Screening, BI and Risk and Anomaly Scores.

## Limits

`LaLimit` items are `SPACE` layer (in `link-analysis.toon`) or `INSTALL` layer (in the server-wide
`la-limits.toon`, located by `-Dsystem.config.dir`, then the spaces container root, then the sole Space's
write root). A write outside an item's range is refused 422; a stored value outside it reads as the default.
Writes are read-modify-write over the raw TOON map under one lock, so unknown keys survive.

## Industry packs

Index `META-INF/inspecto/industry-packs.toon`; each pack is `META-INF/inspecto/industry-packs/<id>/` with
`industry-pack.toon`, `industry-pack-terms.toon`, `industry-pack-entity-types.toon`,
`industry-pack-link-kinds.toon`. Bundled: `telecom`, `mobile-money`. Term keys are `concept.`, `entity_type.`,
`link_kind.`, `measure.`, `pattern.` or `term.`; a pack never adds a concept.

Tests: [tests-inventory](tests-inventory.md).
