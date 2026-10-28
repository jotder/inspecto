---
type: Feature
title: "Entity store — admin guide"
description: "Files on disk, configuration, keys and failure modes of the entity store: the identity fact log, Entity List sidecars, link-analysis.toon, la-limits.toon and Industry packs."
tags: [entity-store, admin-guide]
timestamp: 2026-10-19T00:00:00Z
---

# Entity store — admin guide

The entity store is a base platform module in every edition; there is nothing to install. Administrators
deal with its files, its settings documents and its keys. Design detail: [spec](spec.md).

## Files on disk

| Path | Content | Notes |
|---|---|---|
| `<Space config root>/audit/entity-facts/NNNNNNNNNNNN.json` | identity fact log | append-only and hash-chained; never edit, rename or delete a file |
| `<data root>/entity_list_<id>/entries.parquet` | Entity List sidecar | derived; rewritten on every change; safe to lose |
| `<Space config root>/link-analysis.toon` | per-Space Link Analysis settings and `SPACE` limits | crash-safe TOON; missing or unreadable reads as defaults |
| `la-limits.toon` in the server-wide document home | `INSTALL` limits | located by `-Dsystem.config.dir`, then the spaces container root, then the sole Space's write root |
| Space secrets directory, `.risk-score-mask.key` | Space identity key for `masked:` tokens | owner-only; outside every exported, imported and backed-up tree |
| `mask.key` in an Investigation or fact-log directory | per-directory masking key | created on first use; never served |

## Configure

- **Entity Types, masking, caps:** `link-analysis.toon` keys (`entity_types`, `maskingMode`, four-eyes thresholds, caps). An absent key inherits the shipped default; an empty `entity_types` list is refused. Prefer the Link Analysis settings screen ([la-core user guide](../la-core/user-guide-link-analysis.md)) over hand edits.
- **Limits:** every Link Analysis limit is one item with a range. A write outside the range is refused 422; a hand-edited out-of-range value silently reads as the default. Reference: [la-core architecture](../la-core/architecture.md) §9-§10.
- **Industry pack:** a Space selects one as its Domain Profile (`domain_profile` in `link-analysis.toon`). Packs ship inside jars; selecting a refused or absent pack is refused 422.

## Back up and restore

- Back up `audit/entity-facts/` as a whole; a partial copy that drops a middle file breaks the chain.
- The sidecar Parquet is regenerated from the log, so it need not be backed up.
- The Space identity key is deliberately excluded from backups (the `backup` task skips `.secrets` directories, see [backup spec](../backup/spec.md)). Restoring a Space without it mints a new key, so `masked:` tokens change; keep a separate, secured copy if tokens must stay stable.

## Common failures

| Symptom | Cause | Fix |
|---|---|---|
| Entity List or Link Analysis read fails with a broken chain | a fact file was edited, removed or renamed (`BrokenChainException`) | restore `audit/entity-facts/` from a verified backup; never repair by hand |
| Route answers `sidecar: "failed"` | the Parquet projection could not be written; the fact is kept | check the data root is writable; the next change rewrites it |
| Sidecar never written for a list | the target directory exists without the list's `.entity-list-output` marker | the writer refuses to overwrite a Dataset it did not create; remove or rename the foreign directory |
| A limit change has no effect | stored value is outside the item's range, so the default applies | write it through the settings route, which reports the range |
| An Industry pack is missing from the picker | refused at load (missing file, parse error, unknown concept or duplicate id) | see the refused list in the pack catalogue; fix the pack jar |
| `masked:` tokens differ after a move | the Space identity key was not carried over | restore the key from its secured copy |
