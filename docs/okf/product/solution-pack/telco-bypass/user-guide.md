---
type: Feature
title: "Telecom bypass (SIM-box) — user guide"
description: "How an analyst reads the daily SIM-box signals, scores and findings in the Telecom bypass pack."
tags: [telco-bypass, user-guide]
timestamp: 2026-10-20T00:00:00Z
---

# Telecom bypass (SIM-box) — user guide

What a Space created from the `telco-bypass` template gives an analyst in slices B1 and B2. Design detail is in [architecture](architecture.md); the reading guide is `config/runbooks/telco-bypass-runbooks.md` in the Space.

## Data arriving

Copy the day's voice CDR file (`VOICE_CDR_<yyyyMMdd>.csv`) into `data/inbox/voice_cdr`. Load the three Reference feeds (`cell_site`, `number_plan`, `subscriber_profile`) the same way; each is optional. Gap detection watches the daily file sequence.

## The daily cycle

| When | What runs | Output |
|---|---|---|
| 03:00 | `tb_features` | one row per SIM with enough calls for the previous day, in the Dataset `tb_features` (31 days kept) |
| 03:10 | `tb_factors` | the score inputs of those SIMs for that day: the Dataset `tb_factors` |
| 03:20 | `tb_risk_score` | a score 0 to 100 and its factors for each SIM: `risk_scores_tb_sim_latest` |
| 03:30 | `tb_findings` | a finding for every SIM at or above 60 and every allowed SIM: the Dataset `tb_findings` (31 days kept) |

Run one for another day with `POST /api/v1/jobs/<name>/trigger`, passing `window_start`, `window_end` and `day`; run the four in order.

## What raises an Alert

`tb_sim_high` (CRITICAL, one Incident per SIM) is breached by a `tb_findings` row whose `status` is `alert`: the score is high, at least five of the six primary signals hold, the SIM was high on two of the last three nights (one night for a SIM on the watch list), it is not on the allow list and shadow mode is off. Every other high SIM still has a finding, and `status` says which condition held it back: `allowed`, `agreement`, `persistence` or `shadow`.

`tb_allowed_line_changed` (INFO, one Alert for all such lines) is breached when a SIM on the allow list changes shape from one night to the next (it starts to receive calls, to use other services or to move). Read `tb_findings` for `shape_changed = 1` to see which.

## Reading a `tb_findings` row

Start from `score` and `top_factors`: the three signals that contributed most. `factors` holds all eleven, each with its value, weight, contribution and the evidence it was read from, and the score is exactly their sum (clamped to 0 to 100), so a reviewer sees which signals fired and a changed weight is a visible change. Three factors subtract: a SIM that receives calls, uses SMS or data, or moves across cells at a plausible speed scores lower. `primary_signals` is how many of the six primary signals hold. `est_bypass_minutes` is an estimate of the minutes the SIM carried. `evidence_ids` names three answered calls to look at first.

The underlying signals are in `tb_features`; `box_like` is true when all five strict clauses hold. `max_implied_kmh` and `overlap_calls` point at a cloned SIM, which is not a box; `missing` lists what could not be computed.

## What it will not tell you

A line that is flagged is consistent with a box. An unregistered PBX or outbound-dialler SIM looks the same, so the next step is a verification (a call to the customer or dealer, or a test call), not a bigger threshold. A verified line goes on the `tb_allow` list with an expiry; do not leave it without one. Roaming calls are left out; a SIM below the call floor has no row. Nothing here bars a SIM.

## Verify

- `GET /api/v1/jobs` lists the four Jobs; `POST /api/v1/jobs/tb_features/trigger` runs the first now.
- Query the Dataset `tb_findings` for `status = 'alert'` after a run; `status` and `top_factors` say why each high SIM did or did not alert.

## Tuning

Every threshold is a Job parameter and the weights and the high threshold are in the Risk Score `tb_sim`; the shipped values are illustrative until calibrated on your traffic. Run in shadow mode first (admin guide). The box-level Incident and the cluster signals follow in later slices.
