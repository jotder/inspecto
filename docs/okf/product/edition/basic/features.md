---
type: Feature
title: "Basic Edition — features"
description: "Basic is the entry Edition: core connectors, Entity Lists, the Link Analysis studio and three content packs."
tags: [basic, edition, features, presales]
timestamp: 2026-10-19T00:00:00Z
---

# Basic Edition — features

**Product Map Item:** [`edition.basic`](../../../../../product-map/edition/basic.toon) · **Status:** ga · **Availability:** Billable Edition `INS-ED-BASIC`, per-node billing, standard support, enforced at build (`offerings/basic.toon`).

## What it is

Basic is the entry Edition: core connectors, Entity Lists, the Link Analysis studio and three content packs. For the sales story see [brochure](brochure.md).

## Includes

- Base Edition; includes no lower Edition.

## Platform posture

- Sign-in mode: `none`
- Events backend: `default`
- Objects backend: `default`
- PostgreSQL sidecar: `absent`

## Modules added

- [connectors](../../module/connectors/index.md)
- [entity-list](../../module/entity-list/index.md)
- [la-storage](../../module/la-storage/index.md)
- [la-core](../../module/la-core/index.md)
- [la-api](../../module/la-api/index.md)
- [geo-link](../../module/geo-link/index.md)

## Add-ons available

| Add-on | Order code | Stages this Edition contributes |
|---|---|---|
| [linkAnalysis](../../addon/linkAnalysis/index.md) | `INS-AO-LINK-ANALYSIS` | la-storage, la-core, la-api, geo-link |

## Content packs added

- [business-assurance](../../solution-pack/business-assurance/index.md)
- [la-daily-feed](../../solution-pack/la-daily-feed/index.md)
- [orders-starter](../../solution-pack/orders-starter/index.md)

## Limits

- No Edition-specific limits are declared in the offering file (`defaultSpaceSettings` is empty or absent).

## Related hubs

[Basic](../basic/index.md) · [Standard](../standard/index.md) · [Advanced](../advanced/index.md) · [Preview](../preview/index.md)
