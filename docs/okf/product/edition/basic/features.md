---
type: Feature
title: "Basic Edition — features"
description: "Basic is the entry Edition: core connectors, Entity Lists, the Link Analysis studio and two content packs."
tags: [basic, edition, features, presales]
timestamp: 2026-10-19T00:00:00Z
---

# Basic Edition — features

**Product Map Item:** [`edition.basic`](../../../../../product-map/edition/basic.toon) · **Status:** ga · **Availability:** Billable Edition `INS-ED-BASIC`, per-node billing, standard support, enforced at build (`offerings/basic.toon`).

## What it is

Basic is the entry Edition: core connectors, Entity Lists, the Link Analysis studio and two content packs. For the sales story see [brochure](brochure.md).

## Includes

- Base Edition; includes no lower Edition.

## Platform posture

- Sign-in mode: `none`
- Events backend: `parquet` — the audit trail and its hash chain survive a restart, so the daily anchors keep
  rolling (`BASIC-EVENTS-DURABLE-1`, 2026-11-06; Basic ran the in-memory store before, which restarted the chain
  at seq 1 on every boot)
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

- [la-daily-feed](../../solution-pack/la-daily-feed/index.md)
- [orders-starter](../../solution-pack/orders-starter/index.md)

## Data layer (DATA-TIERS-1, as built 2026-11-06)

- **Add data, three ways in:** upload a file (CSV / TSV, Parquet, JSON / NDJSON, Excel when the bundle carries the DuckDB `excel` extension; a large file may be read in place), an import inbox (*Import waiting files*, or *Watch this folder*: a fixed 30 s poll, off by default), and *Choose a table*: a read-only Postgres snapshot refreshed with *Refresh now* (never polled).
- **One default Pipeline, no transformation:** the column review (names, types, column roles, Entity Types), a no-code row filter with *Edit filter* later, Expectations, quarantine and dedupe in the import's own run, the arrivals list and compaction; Inspecto LA adds its link index as the import's follow-on.
- **Locked with a reason in Basic's screens:** *Keep this up to date* on a schedule, another database or a remote system in Add data, enrichment from a Reference on the Dataset page. The retention form is not shown: every file stays, compacted.
- **Basic runs any configuration it is given.** The edition line is what the screens offer, not a refusal on load; the console's Pipelines, Collectors and Jobs panes are not edition-locked yet. Full table: [EDITIONS.md](../../../../EDITIONS.md) *Data layer by edition*.

## Limits

- No Edition-specific limits are declared in the offering file (`defaultSpaceSettings` is empty or absent).

## Related hubs

[Basic](../basic/index.md) · [Standard](../standard/index.md) · [Advanced](../advanced/index.md) · [Preview](../preview/index.md)
