---
type: Feature
title: "Telecom re-rating (tariff validation) — admin guide"
description: "Install, size and verify the Telecom re-rating pack."
tags: [telco-rerating, admin-guide]
timestamp: 2026-10-21T00:00:00Z
---

# Telecom re-rating (tariff validation) — admin guide

## Prerequisites

- Edition: Standard and up (`minEdition: standard`).
- Product Map Item `solution-pack.telco-rerating` (`product-map/solution-pack/telco-rerating.toon`); commercial fields are not set (decision D-RR10 is the operator's). The pack is an unbilled preview.

## Install

1. Confirm the Space Template is offered: `GET /api/v1/spaces/templates` lists `telco-rerating`.
2. Create a Space from it (needs `canAdminister`):

   ```
   POST /api/v1/spaces
   {"id": "<space-id>", "template": "telco-rerating"}
   ```

   The first refusal creates no Space (`docs/api/openapi-v1.json`, `postSpaces`).
3. Point the operator's rating output at `data/inbox/rated_usage` (one file per day, `RATED_USAGE_<yyyyMMdd>.csv`), deliver the whole tariff table, one row per version, to `data/inbox/tariff_version` whenever it changes, and the whole subscription history to `data/inbox/subscription_history`. No sample files ship: use the contracts in `config/rated_usage/rated_usage_schema.toon`, `config/tariff_version/tariff_version_schema.toon` and `config/subscription_history/subscription_history_schema.toon`.

## The feed contracts

`rated_usage`: `XDR_ID, EVENT_TS, EVENT_DATE, SUBSCRIBER_ID, PLAN, SERVICE, USAGE_UNITS, CHARGE`. `EVENT_TS` is the call start, naive, in one agreed zone, and must be the zone of the tariff's effective dates. `tariff_version`: `TARIFF_VERSION_ID, PLAN, SERVICE, RATE_PER_UNIT, EFFECTIVE_FROM, EFFECTIVE_TO` (blank = open) and the optional `PRIORITY` (the higher number wins; a blank counts as lowest); leave `PRIORITY` blank, or end the file after `EFFECTIVE_TO`. The tariff Pipeline sets `csv_settings.null_padding: true` for that. `subscription_history`: `SUBSCRIBER_ID, PLAN, FROM_TS` (inclusive), `TO_TS` (exclusive, blank = open): one row per period, the whole history in every file. It is an ordinary feed and not an `scd2` Reference, because the periods are in the past (D-RR8 revised, [as-built record](plan-as-built.md)). `SUBSCRIBER_ID` is `INTERNAL`: deliver masked or synthetic ids. The tariff is **one row per version**, not a current snapshot: past versions must stay in the file so a late-arriving call is rated at the rate in force when it started.

## Sizing

Measured on one synthetic day on a 12-core developer box, DuckDB 1.5.6 with the engine's session settings (4 threads, the 3,246 MiB default memory cap); see the archived [plan](../../../../archived-documents/plans-archive/rerating-tariff-validation-plan.md), section 12, for the harness and every figure.

Peak DuckDB memory of the re-rating statement by rated-usage rows in one day, 4 threads, default cap (3,246 MiB): 0.38 GiB at 1 million rows (4.7 s), **1.78 GiB at 5 million** (25 s, no spill), 3.17 GiB at 10 million (97 % of the cap, 53 s, spilling). At 5 million rows a 1 GiB cap still completes (28 s, spilling). The real Job through `JobService` (feed Parquet, `IncrementalSink`, the Parquet write) took **16.9 s at 5 million rows with a process commit peak of 2.72 GiB** (JVM plus DuckDB). **Above roughly 8 million rows a day on the default cap, raise `processing.duckdb.memory_limit` to at least 4 GiB** (advice interpolated between the measured 5 and 10 million points). `rr_tariff_hygiene` reads only the tariff table (hundreds of rows) and runs in well under a second. These are one synthetic day (300,000 subscribers, about 165 tariff rows), not an operator's volume; the budget slice R0 set is a 5 million row day in 60 s on 4 threads, no spill, inside the default cap.

## Verify

- `GET /api/v1/jobs` lists `rr_tariff_hygiene`, `rr_rerate`, `rr_explain` and `rr_root_cause`.
- After the first run, `rerating_tariff_findings` has one row per tariff defect, `rerated_usage` one row per call of the day, `rerating_findings` one row per finding and `rerating_root_causes` one row per root cause; every call with no usable tariff row says `no_tariff`.
- The golden test is `TelcoRerateGoldenTest` in module `inspecto`.

## Explanation and root causes

`rr_root_cause` reads the whole retained `rerating_findings` (31 days). Since slice R3 `rr_explain` also reads the day's calls of the subscribers that have a finding (plan inference) and the subscription history, so its cost follows the calls of those subscribers. Measured over in-memory tables (not through the Job and Parquet): 0.28 s before and 0.47 s after at 5 million calls with 3,800 findings on 300 subscribers, 0.60 s and 0.9 s at 10 million, about 15 MiB more peak memory (archived [plan](../../../../archived-documents/plans-archive/rerating-tariff-validation-plan.md), section 15). Not measured: findings spread over many thousands of subscribers, the Parquet scan, a real history of millions of rows. The sizing figures above are for `rr_rerate`. Keep `tolerance` equal in `rr_rerate` and `rr_explain`. A root cause keeps its id from day to day, so an Alert Rule by `ROOT_CAUSE_ID` (a later slice) opens one Incident per cause; size `stormCap` for the number of distinct causes (R0 answer 2, [as-built record](plan-as-built.md)).

## Safety

`SUBSCRIBER_ID` is `INTERNAL` and synthetic in the template's corpus; the pack holds no name, number or invoice detail. The pack publishes nothing, never writes to a billing system and never acts on a call.
