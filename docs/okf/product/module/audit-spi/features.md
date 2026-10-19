---
type: Feature
title: "Audit contract — features"
description: "The audit contract is the service interface every module uses to record who did what."
tags: [audit-spi, features, presales]
timestamp: 2026-10-19T00:00:00Z
---

# Audit contract — features

**Product Map Item:** [`module.audit-spi`](../../../../../product-map/module/audit-spi.toon) · **Status:** ga · **Availability:** Platform base — present in every Edition (Basic, Standard, Advanced, Preview).

## What it is

The audit contract is the service interface every module uses to record who did what. Keeping it a small contract lets audit storage be supplied separately from the features that write to it.

## What this enables

- Every feature writes audit events the same way, so compliance reviewers get one consistent trail regardless of which modules a bundle carries — see [Identity and Access](../access/features-access.md)

## Related hubs

[spi](../../layer/spi/index.md) · [access](../../module/access/index.md)
