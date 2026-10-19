---
type: Feature
title: "Link Analysis daily feed — features"
description: "A ready-made, monitored daily feed for the Link Analysis index."
tags: [la-daily-feed, features, presales]
timestamp: 2026-10-19T00:00:00Z
---

# Link Analysis daily feed — features

**Product Map Item:** [`solution-pack.la-daily-feed`](../../../../../product-map/solution-pack/la-daily-feed.toon) · **Status:** ga · **Availability:** billable, order code `INS-SP-LA-DAILY-FEED`; content pack listed in `offerings/basic.toon`.

## What it is

The reusable daily-feed shape for Link Analysis: one CSV per UTC day in, an up-to-date link index out, with monitors for late, short and unindexed days.

## Who benefits

Teams feeding a daily telecom (or similar) file drop or nightly database export into Link Analysis.

## Key capabilities

- Pipeline with typed schema, partitioned by event date; poll Collector, one file per day, gap detection.
- Duplicate markers and quarantine for refused files.
- `la_index_build` Job appends each landed day to the Link Analysis index.
- Freshness and index-lag Alert Rules; a daily row-count Expectation run after every File.
- Two sample days in `data/samples`.

## Related hubs

[architecture](architecture.md) · [user guide](user-guide.md) · [admin guide](admin-guide.md) · [Link Analysis add-on](../../addon/linkAnalysis/features.md)
