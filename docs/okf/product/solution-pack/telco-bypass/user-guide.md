---
type: Feature
title: "Telecom bypass (SIM-box) — user guide"
description: "How an analyst reads the daily SIM-box signals in the Telecom bypass pack."
tags: [telco-bypass, user-guide]
timestamp: 2026-10-20T00:00:00Z
---

# Telecom bypass (SIM-box) — user guide

What a Space created from the `telco-bypass` template gives an analyst in slice B1. Design detail is in [architecture](architecture.md); the per-signal reading guide is `config/runbooks/telco-bypass-runbooks.md` in the Space.

## Data arriving

Copy the day's voice CDR file (`VOICE_CDR_<yyyyMMdd>.csv`) into `data/inbox/voice_cdr`. Load the three Reference feeds (`cell_site`, `number_plan`, `subscriber_profile`) the same way; each is optional. Gap detection watches the daily file sequence.

## The daily cycle

| When | What runs | Output |
|---|---|---|
| 03:00 | `tb_features` | one row per SIM with enough calls for the previous day, in the Dataset `tb_features` (31 days kept) |

Run it for another day with `POST /api/v1/jobs/tb_features/trigger`, passing `window_start`, `window_end` and `day`.

## Reading a row

Start from `box_like`: true means all five clauses hold (little inbound, a different number almost every call, short answered calls, voice only, one cell). Then read the signals behind it: `b_diversity`, `short_call_ratio`, `inbound_ratio`, `voice_only_share`, `top_cell_share`, and the supporting ones: `seq_dial_share` (number walking), `night_share` and `active_hours` (round the clock), `gap_cv` (machine-regular cadence), `onnet_target_share`, `new_batch_share` (a freshly activated batch). `max_implied_kmh` and `overlap_calls` point at a cloned SIM, which is not a box. `evidence_ids` names three answered calls to look at first. `missing` lists what could not be computed.

## What it will not tell you

A line that is flagged is consistent with a box. An unregistered PBX or outbound-dialler SIM looks the same, so the next step is a verification (a call to the customer or dealer), not a bigger threshold. Roaming calls are left out; a SIM below the call floor has no row.

## Verify

- `GET /api/v1/jobs` lists `tb_features`; `POST /api/v1/jobs/tb_features/trigger` runs it now.
- Query the Dataset `tb_features` for `box_like = true` after a run; `missing` says which optional inputs were absent.

## Tuning

Every threshold is a parameter of the Job in `config/jobs/tb_features_job.toon`; the shipped values are illustrative until calibrated on your traffic. Slices that follow add the score, the Entity Lists and the Alert Rules.
