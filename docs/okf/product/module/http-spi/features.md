---
type: Feature
title: "Route module contract — features"
description: "The route module contract lets an optional module contribute its own Control API routes."
tags: [http-spi, features, presales]
timestamp: 2026-10-19T00:00:00Z
---

# Route module contract — features

**Product Map Item:** [`module.http-spi`](../../../../../product-map/module/http-spi.toon) · **Status:** ga · **Availability:** Platform base — present in every Edition (Basic, Standard, Advanced, Preview).

## What it is

The route module contract lets an optional module contribute its own Control API routes. A module that is not installed simply contributes none.

## What this enables

- Add-ons arrive with their own HTTP surface and leave none behind when absent, so each bundle exposes only what the customer bought — [architecture-module-taxonomy](../../layer/platform/architecture-module-taxonomy.md)

## Related hubs

[spi](../../layer/spi/index.md) · [processor](../../module/processor/index.md)
