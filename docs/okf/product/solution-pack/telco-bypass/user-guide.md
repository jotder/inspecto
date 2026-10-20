---
type: Feature
title: "Telecom bypass (SIM-box) — user guide"
description: "How an analyst reads the daily SIM-box signals, scores and findings in the Telecom bypass pack."
tags: [telco-bypass, user-guide]
timestamp: 2026-10-20T00:00:00Z
---

# Telecom bypass (SIM-box) — user guide

What a Space created from the `telco-bypass` template gives an analyst in slices B1 to B3. Design detail is in [architecture](architecture.md); the reading guide is `config/runbooks/telco-bypass-runbooks.md` in the Space.

## Data arriving

Copy the day's voice CDR file (`VOICE_CDR_<yyyyMMdd>.csv`) into `data/inbox/voice_cdr`. Load the three Reference feeds (`cell_site`, `number_plan`, `subscriber_profile`) the same way; each is optional. Gap detection watches the daily file sequence.

## The daily cycle

| When | What runs | Output |
|---|---|---|
| 03:00 | `tb_features` | one row per SIM with enough calls for the previous day, in the Dataset `tb_features` (31 days kept) |
| 03:10 | `tb_factors` | the score inputs of those SIMs for that day: the Dataset `tb_factors` |
| 03:20 | `tb_risk_score` | a score 0 to 100 and its factors for each SIM: `risk_scores_tb_sim_latest` |
| 03:30 | `tb_findings` | a finding for every SIM at or above 60 and every allowed SIM: the Dataset `tb_findings` (31 days kept) |
| 03:40 | `tb_cluster_members` | every candidate SIM that shares a device, a cell or an activation batch with at least four others: the Dataset `tb_cluster_members` |
| 03:50 | `tb_clusters` | one row per suspected box: the Dataset `tb_clusters` (31 days kept) |

Run one for another day with `POST /api/v1/jobs/<name>/trigger`, passing `window_start`, `window_end` and `day`; run the six in order.

## What raises an Alert

`tb_sim_high` (CRITICAL, one Incident per SIM) is breached by a `tb_findings` row whose `status` is `alert`: the score is high, at least five of the six primary signals hold, the SIM was high on two of the last three nights (one night for a SIM on the watch list), it is not on the allow list and shadow mode is off. Every other high SIM still has a finding, and `status` says which condition held it back: `allowed`, `agreement`, `persistence` or `shadow`.

`tb_box_suspected` (CRITICAL, one Incident per box) is breached by a `tb_clusters` row whose `alert_value` is not zero: at least five candidate SIMs (each dials a new number almost every call, in short calls, never receives calls, uses no SMS or data and stays on one cell) share one device (`imei:`), failing that one cell (`cell:`), failing that one activation batch (`batch:`). It does not wait for persistence, and it fires even when none of the SIMs scores high on its own (a slow-roll box). The Incident is the work item and the Case anchor: the Case Rule `tb_box_case` opens one Case per box. The SIMs of the box that also alert on their own are the evidence (`alerting_sims`), and the Link Analysis view `tb_box_members` shows them as one graph.

`tb_allowed_line_changed` (INFO, one Alert for all such lines) is breached when a SIM on the allow list changes shape from one night to the next (it starts to receive calls, to use other services or to move). Read `tb_findings` for `shape_changed = 1` to see which.

## Reading a `tb_clusters` row

`cluster_kind` and `cluster_key` say which key made the box. `candidate_sims` is the size; `scored_sims` how many of them have a SIM-day row (none for a slow-roll box); `alerting_sims` and `max_score` how many alert on their own and how high the best scores. `cells`, `imeis` and `batches` show what else the members share: one cell and one device is a classic box, many cells on one device is a rotated box, and `spoofed_sims` counts members that used three or more IMEIs today (swapping, which is why the cell key exists). `days_seen`, `first_day` and `last_day` tell a new box from one that has run for weeks; the Incident is not refreshed, the row is. `est_bypass_minutes` is the members' talk minutes. `tb_cluster_members` lists the SIMs of a box with the key, cell and batch each one has.

## Reading a `tb_findings` row

Start from `score` and `top_factors`: the three signals that contributed most. `factors` holds all eleven, each with its value, weight, contribution and the evidence it was read from, and the score is exactly their sum (clamped to 0 to 100), so a reviewer sees which signals fired and a changed weight is a visible change. Three factors subtract: a SIM that receives calls, uses SMS or data, or moves across cells at a plausible speed scores lower. `primary_signals` is how many of the six primary signals hold. `est_bypass_minutes` is an estimate of the minutes the SIM carried. `evidence_ids` names three answered calls to look at first.

The underlying signals are in `tb_features`; `box_like` is true when all five strict clauses hold. `max_implied_kmh` and `overlap_calls` point at a cloned SIM, which is not a box; `missing` lists what could not be computed.

## What it will not tell you

A line that is flagged is consistent with a box. An unregistered PBX or outbound-dialler SIM looks the same, so the next step is a verification (a call to the customer or dealer, or a test call), not a bigger threshold. A verified line goes on the `tb_allow` list with an expiry; do not leave it without one. Roaming calls are left out; a SIM below the call floor has no row. A box that shares no device, cell or batch between its SIMs is invisible to the cluster stage. Nothing here bars a SIM or a device: the runbook step is approval-gated and manual until the operator has a provisioning API.

## Verify

- `GET /api/v1/jobs` lists the four Jobs; `POST /api/v1/jobs/tb_features/trigger` runs the first now.
- Query the Dataset `tb_findings` for `status = 'alert'` after a run; `status` and `top_factors` say why each high SIM did or did not alert.

## Tuning

Every threshold is a Job parameter and the weights and the high threshold are in the Risk Score `tb_sim`; the shipped values are illustrative until calibrated on your traffic. Run in shadow mode first (admin guide). The cluster thresholds (`cand_min_events`, `min_box_sims`, `spoof_imei_count`) are Job parameters too.
