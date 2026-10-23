---
type: Feature
title: "Link Analysis daily feed — architecture"
description: "How the daily feed pack moves one file per day into the Link Analysis index, with monitors."
tags: [la-daily-feed, architecture]
timestamp: 2026-10-23T00:00:00Z
---

# Link Analysis daily feed — architecture

Template: `spaces/_templates/la-daily-feed/` (LA-DAILY-INGEST-1 T2).

## Data flow

1. **Collector** (poll, local connector) picks up `XDR_*.csv` from `data/inbox/daily_xdr`, one file per UTC day; gap detection on (`config/daily_xdr/daily_xdr_pipeline.toon`; `file_template: "XDR_{yyyyMMdd}*"`, so a missing day is a gap and a renamed correction of a day is seen). A corrected day re-sent under the SAME name takes effect: `collector.duplicate` is `checksum` + `on_change: reprocess`, so changed bytes re-ingest the file and replace its partition (one `REDELIVERED` signal), while an unchanged re-delivery is skipped.
2. **Pipeline** `daily_xdr` parses the typed schema (`daily_xdr_schema.toon`) and writes Parquet partitioned by `EVENT_DATE` (the UTC date of `START_AT`); duplicate markers (`.processed`, 30 days) and quarantine for refused files.
3. **Dataset** `daily_xdr_dataset` (`config/registry/datasets/`).
4. On every successful `pipeline.commit`:
   - Job `la_index_build` (`la.index.build`) appends the day to the Link Analysis index: `MSISDN` to `OTHER_PARTY`, kind `REC_TYPE`, time `START_AT`, attributes `IMSI,IMEI,DIRECTION`; the first run is a full build (`allow_full`). It runs as `owner` (`admin` in the template; re-stamped to whoever applies the template on a server with sign-in, unless that user holds `canConfigureAccess`), with `timeout_seconds: 600` and `wait_seconds: 300` (how long a run waits behind another build of the same index before it gives up as `BUILD_IN_PROGRESS`).
   - Job `la_rows_band_evaluate` (`expectation.evaluate`) runs the row-count Expectation, so an odd day opens an Incident.

## Monitors

| Monitor | Kind | Fires when |
|---|---|---|
| `daily_xdr_freshness` | Alert Rule, WARNING | no new File for 2x the poll interval (`maximumAge: auto`) |
| `daily_xdr_index_lag` | Alert Rule, WARNING | landed Files not covered by the index for more than 2h |
| `la_daily_rows_band` | Expectation, MAJOR | a day below 50% or above 200% of the 7-day median (needs 3 prior days) |

## Modules used

[Link Analysis](../../module/la-core/architecture.md) · [ETL ingestion plan](../../module/etl/plan-ingestion.md) (export contract for a database origin).
