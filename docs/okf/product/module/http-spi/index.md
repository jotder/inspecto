---
type: ProductItem
title: Route module contract
item: module.http-spi
description: Documentation hub for module.http-spi — every document about it, by slot.
tags: [product-item, module]
---

# Route module contract (`module.http-spi`)

> **Documentation hub** (`PRODUCT-DOCS-HUB-1`) for Product Map Item
> [`module.http-spi`](../../../../../product-map/module/http-spi.toon). Editions, add-ons, order codes and dependencies are read
> from the Product Map — never restated here. **Generated** by `tools/render-product-docs.mjs` from the files in
> this folder — add or rename a file (`<slot>.md` / `<slot>-<topic>.md`), never edit this table.

| Slot | Status | Document |
|---|---|---|
| plan | n/a | a contract module carved out by MODULE-REORG-1 and opened by EDG-01; its requirement of record is decision D-MR1 in layer/platform spec-module-reorg-decisions.md |
| spec | n/a | the contract's invariants are stated per interface in api.md and the boot and discovery design in architecture.md; the host implementation is ControlApi in the core |
| architecture | current | [Route module contract — architecture](architecture.md) |
| features | current | [Route module contract — features](features.md) |
| feature-db | n/a | no feature rows derive to this item (`tools/render-feature-db.mjs`) |
| user-guide | n/a | a pure Java contract with no screens; each route module's screens are documented in its own hub |
| admin-guide | n/a | nothing to install or configure in this jar; which route modules a bundle carries is an edition choice documented in layer/platform architecture-editions-model.md |
| api | current | [Route module contract — Java API](api.md) |
| tests | n/a | the module has no test classes of its own; its test tree is the platform test kit (FakeApiContext, RouteModuleContract, ModuleRoutesParity) shipped as a test-jar and exercised by every feature module's RouteModuleContract subclass |
| brochure | n/a | per edition / solution pack |

Status: `current` (in this folder) · `missing` (a real gap) · `n/a` (decided: the reason says why; per-hub decisions in `slots-na.toon`).

## Open work

Generated from the rows of [`docs/BACKLOG.md`](../../../../BACKLOG.md) tagged `module.http-spi` — the board is the only list; change the row there.

_No open rows._
