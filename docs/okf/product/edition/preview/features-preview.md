---
type: Feature
title: "Preview Edition — features"
description: "Preview includes Advanced; its offering adds no further modules, add-ons or content packs."
tags: [preview, edition, features, presales]
timestamp: 2026-10-19T00:00:00Z
item: edition.preview
audience: internal
status: current
verified: 2026-11-18 last-edit
code: offerings/preview.toon
---

# Preview Edition — features

**Product Map Item:** [`edition.preview`](../../../../../product-map/edition/preview.toon) · **Status:** ga · **Availability:** Billable Edition `INS-ED-PREVIEW`, per-node billing, standard support, enforced at build (`offerings/preview.toon`).

## What it is

Preview includes Advanced; its offering adds no further modules, add-ons or content packs. For the sales story see [brochure](brochure-preview.md).

## Includes

- Everything in the [Advanced Edition](../advanced/features-advanced.md) (`includes: advanced`).

## Platform posture

- Sign-in mode: `oidc`
- Events backend: `parquet`
- Objects backend: `default`
- PostgreSQL sidecar: `bundled`

## Modules added over Advanced

- None — `offerings/preview.toon` lists no `modules`.

## Add-ons available

| Add-on | Order code | Stages this Edition contributes |
|---|---|---|
| — | — | none listed in `offerings/preview.toon`; add-ons of lower Editions carry over |

## Content packs added over Advanced

- None added; packs of lower Editions carry over.

## Limits

- No Edition-specific limits are declared in the offering file (`defaultSpaceSettings` is empty or absent).

## Related hubs

[Basic](../basic/index.md) · [Standard](../standard/index.md) · [Advanced](../advanced/index.md) · [Preview](index.md)
