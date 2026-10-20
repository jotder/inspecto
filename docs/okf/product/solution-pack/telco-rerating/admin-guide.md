---
type: Feature
title: "Telecom re-rating (tariff validation) — admin guide"
description: "Install, size and verify the Telecom re-rating pack."
tags: [telco-rerating, admin-guide]
timestamp: 2026-10-20T00:00:00Z
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
3. Point the operator's rating output at `data/inbox/rated_usage` (one file per day, `RATED_USAGE_<yyyyMMdd>.csv`) and deliver the whole tariff table, one row per version, to `data/inbox/tariff_version` whenever it changes. No sample files ship: use the contracts in `config/rated_usage/rated_usage_schema.toon` and `config/tariff_version/tariff_version_schema.toon`.

## The feed contracts

`rated_usage`: `XDR_ID, EVENT_TS, EVENT_DATE, SUBSCRIBER_ID, PLAN, SERVICE, USAGE_UNITS, CHARGE`. `EVENT_TS` is the call start, naive, in one agreed zone, and must be the zone of the tariff's effective dates. `tariff_version`: `TARIFF_VERSION_ID, PLAN, SERVICE, RATE_PER_UNIT, EFFECTIVE_FROM, EFFECTIVE_TO` (blank = open) and the optional `PRIORITY` (the higher number wins; a blank counts as lowest); leave `PRIORITY` blank, or end the file after `EFFECTIVE_TO`. The tariff Pipeline sets `csv_settings.null_padding: true` for that. The tariff is **one row per version**, not a current snapshot: past versions must stay in the file so a late-arriving call is rated at the rate in force when it started.

## Sizing

Measured on one synthetic day on a 12-core developer box, DuckDB 1.5.6 with the engine's session settings (4 threads, the 3,246 MiB default memory cap); see the plan section 12 for the harness and every figure.

Peak DuckDB memory of the re-rating statement by rated-usage rows in one day, 4 threads, default cap (3,246 MiB): 0.38 GiB at 1 million rows (4.7 s), **1.78 GiB at 5 million** (25 s, no spill), 3.17 GiB at 10 million (97 % of the cap, 53 s, spilling). At 5 million rows a 1 GiB cap still completes (28 s, spilling). The real Job through `JobService` (feed Parquet, `IncrementalSink`, the Parquet write) took **16.9 s at 5 million rows with a process commit peak of 2.72 GiB** (JVM plus DuckDB). **Above roughly 8 million rows a day on the default cap, raise `processing.duckdb.memory_limit` to at least 4 GiB** (advice interpolated between the measured 5 and 10 million points). `rr_tariff_hygiene` reads only the tariff table (hundreds of rows) and runs in well under a second. These are one synthetic day (300,000 subscribers, about 165 tariff rows), not an operator's volume; the budget slice R0 set is a 5 million row day in 60 s on 4 threads, no spill, inside the default cap.

## Verify

- `GET /api/v1/jobs` lists `rr_tariff_hygiene` and `rr_rerate`.
- After the first run, `rerating_tariff_findings` has one row per tariff defect and `rerated_usage` one row per call of the day; every call with no usable tariff row says `no_tariff`.
- The golden test is `TelcoRerateGoldenTest` in module `inspecto`.

## Safety

`SUBSCRIBER_ID` is `INTERNAL` and synthetic in the template's corpus; the pack holds no name, number or invoice detail. The pack publishes nothing, never writes to a billing system and never acts on a call.
