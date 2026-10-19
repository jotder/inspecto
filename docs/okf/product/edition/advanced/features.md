---
type: Feature
title: "Advanced Edition — features"
description: "Advanced includes Standard and adds policy, a PostgreSQL investigation store and intelligence modules."
tags: [advanced, edition, features, presales]
timestamp: 2026-10-19T00:00:00Z
---

# Advanced Edition — features

**Product Map Item:** [`edition.advanced`](../../../../../product-map/edition/advanced.toon) · **Status:** ga · **Availability:** Billable Edition `INS-ED-ADVANCED`, per-node billing, standard support, enforced at build (`offerings/advanced.toon`).

## What it is

Advanced includes Standard and adds policy, a PostgreSQL investigation store and intelligence modules. For the sales story see [brochure](brochure.md).

## Includes

- Everything in the [Standard Edition](../standard/features.md) (`includes: standard`).

## Platform posture

- Sign-in mode: `oidc`
- Events backend: `parquet`
- Objects backend: `postgres`
- PostgreSQL sidecar: `bundled`

## Modules added over Standard

- [policy](../../module/policy/index.md)
- [la-store-pg](../../module/la-store-pg/index.md)
- [intelligence](../../module/intelligence/index.md)

## Add-ons available

| Add-on | Order code | Stages this Edition contributes |
|---|---|---|
| [linkAnalysis](../../addon/linkAnalysis/index.md) | `INS-AO-LINK-ANALYSIS` | la-store-pg |
| [aiAssist](../../addon/aiAssist/index.md) | `INS-AO-AI-ASSIST` | intelligence |

## Content packs added over Standard

- None added; packs of lower Editions carry over.

## Limits

- No Edition-specific limits are declared in the offering file (`defaultSpaceSettings` is empty or absent).

## Related hubs

[Basic](../basic/index.md) · [Standard](../standard/index.md) · [Advanced](../advanced/index.md) · [Preview](../preview/index.md)
