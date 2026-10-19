---
type: Feature
title: "Entity lists — features"
description: "Entity Lists hold named sets of entities — numbers, accounts, ranges, CIDR blocks — that Pipelines and analysts match against."
tags: [entity-list, features, presales]
timestamp: 2026-10-19T00:00:00Z
---

# Entity lists — features

**Product Map Item:** [`module.entity-list`](../../../../../product-map/module/entity-list.toon) · **Status:** ga · **Availability:** Optional module, staged from Basic and every higher Edition (`offerings/basic.toon`). Add-on: [scoringLists](../../addon/scoringLists/index.md).

## What it is

Entity Lists hold named sets of entities — numbers, accounts, ranges, CIDR blocks — that Pipelines and analysts match against. Entries can expire, and changes can be held for maker-checker approval.

## Who benefits

Fraud and assurance analysts maintaining watch lists, and reviewers approving list changes.

## Key capabilities

- Range and CIDR entries, expiring entries and a Parquet sidecar Dataset — [spec](spec.md)
- Match route and member maintenance (7 routes) — [feature-db](feature-db.md)
- Maker-checker hold on list changes — [spec](spec.md)

## Related hubs

[scoringLists](../../addon/scoringLists/index.md) · [scoring](../../module/scoring/index.md) · [screening](../../module/screening/index.md) · [entity-store](../../module/entity-store/index.md)

More in this hub: [feature-db](feature-db.md) · [spec](spec.md).
