---
type: Index
title: Product documentation hubs
description: One hub per Product Map Item, each with the same ten document slots — the docs tree mirrors product-map/.
tags: [index, product-item]
timestamp: 2026-10-19T00:00:00Z
---

# Product documentation hubs

One hub per Product Map Item at `product/<kind>/<name>/index.md`, mirroring `product-map/<kind>/<name>.toon`.
Every hub lists the same ten slots — plan, spec, architecture, features, feature-db, user-guide,
admin-guide, api, tests, brochure. Design: [`product-docs-hub-plan.md`](../../superpower/product-docs-hub-plan.md).
New hub: copy [`_template.md`](_template.md). Check: `node tools/check-product-docs.mjs`.

# Hubs

* [Acquisition and collectors](module/acquire/index.md) - `module.acquire`
* [Incidents](addon/incidents/index.md) - `addon.incidents`
