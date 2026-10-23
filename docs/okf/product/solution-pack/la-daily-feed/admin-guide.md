---
type: Feature
title: "Link Analysis daily feed — admin guide"
description: "Install, adapt and verify the la-daily-feed Space Template."
tags: [la-daily-feed, admin-guide]
timestamp: 2026-10-23T00:00:00Z
---

# Link Analysis daily feed — admin guide

## Prerequisites

- An Edition with the Link Analysis add-on (`linkAnalysis` in `offerings/basic.toon` and up); the pack is listed in Basic's `contentPacks`.
- The `admin` principal: `la_index_build` runs as `owner: admin`, which must be able to view the Dataset. On a server with sign-in, creating the Space re-stamps the owner to the user who applies the template, unless that user holds `canConfigureAccess` (then `admin` stands).

## Install

1. Create the Space: `POST /spaces` with body `{"id":"<space>","template":"la-daily-feed"}` (needs `canAdminister`; `docs/api/openapi-v1.json`).
2. Adapt per feed: rename the feed, columns and `include` file pattern; keep `partitionKey` on the event-date column. For a database export, follow the contract in the [ETL ingestion plan](../../module/etl/plan-ingestion.md).

## Verify

1. Copy `data/samples/daily_xdr/XDR_20260901.csv` and `XDR_20260902.csv` into `data/inbox/daily_xdr`.
2. Each file commits to `daily_xdr_dataset` and triggers `la_index_build` and `la_rows_band_evaluate`.
3. A corrected day: re-send it under the same name with changed content; it replaces that day's partition and the next index build picks it up. An identical re-send is skipped.
4. `daily_xdr_index_lag` stays clear once the index has caught up; `la_daily_rows_band` does not judge a day until 3 prior days exist.
