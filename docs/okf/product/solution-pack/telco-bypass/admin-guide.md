---
type: Feature
title: "Telecom bypass (SIM-box) — admin guide"
description: "Install, size and verify the Telecom bypass pack."
tags: [telco-bypass, admin-guide]
timestamp: 2026-10-20T00:00:00Z
---

# Telecom bypass (SIM-box) — admin guide

## Prerequisites

- Edition: Standard and up (`minEdition: standard`).
- Product Map Item `solution-pack.telco-bypass` (`product-map/solution-pack/telco-bypass.toon`); commercial fields are not set (decision D-BP10 is the operator's).

## Install

1. Confirm the Space Template is offered: `GET /api/v1/spaces/templates` lists `telco-bypass`.
2. Create a Space from it (needs `canAdminister`):

   ```
   POST /api/v1/spaces
   {"id": "<space-id>", "template": "telco-bypass"}
   ```

   The first refusal creates no Space (`docs/api/openapi-v1.json`, `postSpaces`).
3. Point the operator's mediation at `data/inbox/voice_cdr` (one file per day, `VOICE_CDR_<yyyyMMdd>.csv`) and, once, load the three Reference feeds into `data/inbox/cell_site`, `data/inbox/number_plan` and `data/inbox/subscriber_profile`. No sample files ship: use the CDR contract in `config/voice_cdr/voice_cdr_schema.toon`.

## The CDR contract

Required columns: `record_id, start_ts, direction, service, a_number, b_number, duration_s, roaming, cell_id`. Optional: `answered, release_cause, imei, imsi`; leave them blank, or end the file after `cell_id`. Numbers should be bare E.164 digits. Timestamps are naive and must all be in the zone named by the Job parameter `feed_zone`; set `local_zone` to the zone whose night the pack should use. Whether your operator, or the MVNE for an MVNO, can supply IMEI, IMSI, answer or release data is the largest unknown (plan section 2.1); the pack runs without them.

## Sizing

Peak DuckDB memory by CDR rows in one day, on the default memory cap (3,246 MiB), measured on one synthetic day: 0.7 GiB at 1 million, 1.8 GiB at 5 million, 2.5 GiB at 7.5 million, 3.1 GiB at 10 million (97 % of the cap). **Above roughly 8 million rows a day, raise `processing.duckdb.memory_limit` to at least 4 GiB.** At 5 million rows the real Job ran in 6.8 s (12 cores). Slice B0 set the budget this must stay inside: a 5 million row day in 60 s on 4 cores, peak 2.5 GiB, no spill.

## Verify

- `GET /api/v1/jobs` lists the Job `tb_features`.
- After the first day's run, the Dataset `tb_features` has one row per SIM with at least `min_events` MO voice calls; `missing` names every omitted signal.
- The golden test is `BypassFeatureGoldenTest` in module `inspecto`.

## Safety

`msisdn` is classified `MSISDN`, `imsi` `IMSI`, `imei` `PII`, and `cell_id` and the cell coordinates `CONFIDENTIAL`; `tb_features` carries `msisdn`, a dealer id and example record ids only. The pack publishes nothing and never acts on a call.
