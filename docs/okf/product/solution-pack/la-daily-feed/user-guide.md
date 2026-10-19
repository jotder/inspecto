---
type: Feature
title: "Link Analysis daily feed — user guide"
description: "How an investigator works with a daily-fed Link Analysis index."
tags: [la-daily-feed, user-guide]
timestamp: 2026-10-19T00:00:00Z
---

# Link Analysis daily feed — user guide

The feed keeps the Link Analysis index current; investigation itself uses the standard studio ([end user guide](../../module/la-core/user-guide-end-user.md)).

## Day to day

1. Each day's `XDR_yyyyMMdd.csv` lands and is indexed automatically; numbers (`MSISDN`, `OTHER_PARTY`) are the graph nodes, `REC_TYPE` the link kind.
2. Before trusting a graph, check the monitors: `daily_xdr_freshness` (day late), `daily_xdr_index_lag` (index behind the files, so a graph read now may lag the data), `la_daily_rows_band` (day half-delivered or doubled).
3. A row-count breach opens an Incident; work it before relying on that day.
4. Pivot from a number into an Investigation and onward to Entity profiles and Cases as in the [Investigation pivot guide](../../module/la-core/user-guide-investigation-pivot.md).
