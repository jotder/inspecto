---
type: ProductItem
title: Acquisition and collectors
item: module.acquire
description: Documentation hub for module.acquire — every document about it, by slot.
tags: [product-item, module]
---

# Acquisition and collectors (`module.acquire`)

> **Documentation hub** (`PRODUCT-DOCS-HUB-1`) for Product Map Item
> [`module.acquire`](../../../../../product-map/module/acquire.toon). Editions, add-ons, order codes and dependencies are read
> from the Product Map — never restated here. **Generated** by `tools/render-product-docs.mjs` from the files in
> this folder — add or rename a file (`<slot>.md` / `<slot>-<topic>.md`), never edit this table.

| Slot | Status | Document |
|---|---|---|
| plan | current | [Acquisition & connectivity (ACQ)](plan-acquisition.md) |
| spec | current | [Data acquisition — the original requirement](spec-framework-design.md) · [Unpack stage — decompression at the Collector](spec-unpack-stage.md) |
| architecture | current | [Collectors](architecture-collectors.md) · [Data Acquisition Framework](architecture-framework.md) · [Multi-location ingest — the composition pattern](architecture-multi-location-ingest.md) |
| features | current | [Collectors — features](features-collectors.md) |
| feature-db | current | [Acquisition and collectors — feature database](feature-db.md) |
| user-guide | current | [Collector configuration (the one surface)](user-guide-collector-config.md) · [Collectors](user-guide-collectors.md) |
| admin-guide | current | [Acquisition and collectors — admin guide](admin-guide.md) |
| api | current | [Collectors — API](api-collectors.md) |
| tests | current | [Acquisition and collectors — tests inventory](tests-inventory.md) |
| brochure | n/a | per edition / solution pack |

Status: `current` (in this folder) · `missing` (a real gap) · `n/a` (decided: the reason says why; per-hub decisions in `slots-na.toon`).

## Open work

Generated from the rows of [`docs/BACKLOG.md`](../../../../BACKLOG.md) tagged `module.acquire` — the board is the only list; change the row there.

| Row | Priority | Kind | Title | Hold |
|---|---|---|---|---|
| — | P3 | feature | Unpack (11) absent codecs | demand |
| `DATA-TIERS-1` | P2 | feature | the data layer by edition: Basic gets a one-off "Add data" import (CSV / Parquet upload, Postgres snapshot, no-code shaping) and no sched… | operator |
| `SCALEOUT-U1-REMOTE-ACQUIRE-1` | P3 | feature | Remote acquisition (U1) is not distributed: two pods would both fetch the same remote file. | — |
