# Telecom bypass (SIM-box) runbooks

> **Slice B3.** This Space Template computes the daily per-SIM signals, scores each SIM with the `tb_sim` Risk Score, applies
> agreement, persistence, the `tb_allow` and `tb_watch` Entity Lists and a shadow switch, raises the Alert Rules
> `tb_sim_high` and `tb_allowed_line_changed`, and finds the BOX: the SIMs that share a device, a cell or an activation batch,
> one Incident (`tb_box_suspected`) and one Case (`tb_box_case`) per box. The Anomaly Model factor and the 7-day device signals
> are the next slices (`docs/superpower/telco-packs-plan.md`).

**A CDR pattern is evidence of consistency with a SIM-box, never proof.** Only a verification step (a call to the
customer or the dealer, a test call) separates a quiet box from a quiet dialler or PBX, and that step is a process, not
something this pack does.

## What runs

Six Jobs, in this order (the clock gives each ten minutes; a Job that fails leaves the later ones reading the earlier night):

| Time | Job | Writes |
|---|---|---|
| 03:00 | `tb_features` (`sql.template`) | one row per SIM per day with at least `min_events` (default 20) non-roaming MO voice calls: the Dataset `tb_features` (31 days kept) |
| 03:10 | `tb_factors` (`sql.template`) | the factor columns of the Risk Score for the latest window only: the Dataset `tb_factors` |
| 03:20 | `tb_risk_score` (`risk.score`) | the score of each SIM and the factors behind it: the Datasets `risk_scores_tb_sim` (history) and `risk_scores_tb_sim_latest` |
| 03:30 | `tb_findings` (`sql.template`) | one row per SIM at or above the high threshold (and per allowed SIM): the Dataset `tb_findings` (31 days kept), the Dataset the two SIM Alert Rules read |
| 03:40 | `tb_cluster_members` (`sql.template`) | one row per candidate SIM that shares a box (see below): the Dataset `tb_cluster_members` (31 days kept) |
| 03:50 | `tb_clusters` (`sql.template`) | one row per suspected box per day: the Dataset `tb_clusters` (31 days kept), the only Dataset the box Alert Rule reads |

Run one for another day by passing `window_start`, `window_end` and `day` on the run; without `day` the rows fall outside the
run's day and are dropped. Run the six in order for the same day.

## Reading a row

| Column | Signal | Reads as |
|---|---|---|
| `b_diversity`, `distinct_b`, `max_distinct_b_1h` | S1 | A person redials; a box dials a new number almost every call. Near 1.0 is box-like. |
| `short_call_ratio`, `acd_s`, `acd_cv` | S2 | Share of answered calls at or under `short_s` (60 s), mean answered duration, its coefficient of variation. A box caps or evens out call length. |
| `asr`, `failed_share` | S3 | Answered share of attempts. Needs `answered` or `release_cause`. |
| `inbound_ratio`, `calls_mt` | S4 | MT voice calls over MT plus MO. A box SIM is almost never called back. |
| `voice_only_share`, `sms_mo`, `data_s` | S5 | A box SIM has no SMS and no data. |
| `distinct_cells`, `top_cell_share`, `max_implied_kmh` | S6 | A box is static. An impossible speed between consecutive calls means a cloned or relocated SIM, not a box. Speed needs `cell_site`. |
| `seq_dial_share` | S7 | Share of consecutive calls to numbers within `seq_step` (5) of the previous one: number walking. |
| `active_hours`, `night_share`, `hour_entropy` | S8 | A box runs round the clock. Judged in `local_zone`. |
| `max_calls_1h`, `gap_cv` | S9 | Burst and machine-regular cadence. |
| `overlap_calls` | S10 | One SIM cannot hold two calls at once. |
| `onnet_target_share` | S11 | Terminated bypass lands on the operator's own subscribers. Needs `number_plan`. |
| `tenure_days`, `dealer_id`, `new_batch_share` | S12 | Boxes are fed with freshly activated SIM batches. Needs `subscriber_profile`. |
| `box_like` | none | All five clauses hold: `inbound_ratio < cand_max_inbound`, `b_diversity > cand_min_b_div`, `short_call_ratio > cand_min_short`, `voice_only_share > cand_min_voice_only`, `top_cell_share > cand_min_top_cell`. Every clause is strict, so a SIM exactly at a threshold is silent. |

`missing` names the signals left out for that SIM because their input is absent: `S3` (no `answered` or `release_cause`),
`S6` (no cell of the SIM is in `cell_site`), `S11` (`number_plan` is empty), `S12` (the SIM is not in `subscriber_profile`).
A missing optional column or Reference is flagged here; it never fails the Job. `has_imei` and `has_imsi` say whether the
device columns were present; the device and cluster signals (S13 to S15) are not computed in this slice.

## Reading a `tb_findings` row

The Risk Score `tb_sim` is a sum: eight signals add (weights 14, 12, 14, 10, 8, 8, 8, 6 = 80) and three protective factors subtract
(receives calls up to -20, uses SMS or data up to -15, moves across cells at a plausible speed -12), clamped to 0 to 100. High is
60. A score is explained by its stored `factors` (value, weight, contribution, capped, evidence): the analyst sees which signals fired,
and a changed weight is a visible change. The weights are illustrative; the device-cluster and anomaly factors of the plan arrive with
later slices, so a SIM cannot score above 80 yet.

`tb_findings.status` says why a high SIM did or did not alert, decided in this order:

| `status` | Meaning | What to do |
|---|---|---|
| `allowed` | The SIM is on the `tb_allow` list with a live entry. It keeps its score and signals. | Nothing, unless `tb_allowed_line_changed` fires. |
| `agreement` | Score is high but fewer than `min_signals` (5) of the six primary signals hold (S1, S2, S4, S5, static cell, round the clock). | Read `factors`: one inflated factor is not enough to page anyone. |
| `persistence` | Not yet high on `persist_days` (2) of the last `persist_window` (3) nights. A SIM on `tb_watch` needs one night fewer. | Wait for the next night. |
| `shadow` | Would have alerted, but `shadow` is `true`: scores and findings are written and nothing is raised. | Measure precision, then switch off. |
| `alert` | `alert_score` is the score and the Alert Rule `tb_sim_high` (CRITICAL, one Incident per SIM) is breached. | Verify the line (below); open the evidence ids. |

`top_factors` lists the three largest positive contributions, `est_bypass_minutes` is an estimate of the minutes the SIM carried
(calls x answer rate x mean answered duration), `evidence_ids` names three answered calls.

**Verify before acting.** The pack cannot separate a quiet box from a quiet PBX or dialler. A call to the customer or dealer, or a
test call, is a process step. A verified line goes on `tb_allow` (see the admin guide) with a reason carrying the registry id and an
expiry (the plan recommends at most 365 days, so a verification is renewed; nothing enforces it); a confirmed box goes on `tb_block` (a list this slice documents but does
not yet read). **An allowed SIM is not unmonitored:** if its inbound, service-mix or mobility signals change from one night to the
next, the Alert Rule `tb_allowed_line_changed` (INFO, one Alert for all such lines) fires and the entry may no longer describe it.

## Reading a box (`tb_clusters`)

A box is a **candidate SIM** group. A candidate has at least `cand_min_events` (default 10, lower than the SIM-day floor of 20, so a slow-roll box
whose SIMs each make a dozen calls still counts) non-roaming MO voice calls and all five `box_like` clauses, and is not on a live `tb_allow` entry.
Three keys can group candidates: the SIM's dominant **IMEI** (`imei:<imei>`), its dominant **cell** (`cell:<cell>`) and its **activation batch**
(`batch:<dealer>/<day>`). A key is a box when at least `min_box_sims` (default 5) candidates share it; a SIM joins the first key that is a box, in
that order, so one box is one `cluster_key` and one Incident. Two devices at one cell are two boxes (bar each device). A box that puts a different IMEI on
every call has no shared device: its SIMs share a cell, and `spoofed_sims` counts the members that used `spoof_imei_count` (3) or more IMEIs today.

| Column | Reads as |
|---|---|
| `cluster_kind`, `cluster_key` | which key made the box, and its value (the Incident key) |
| `candidate_sims` | the size of the box today (`alert_value` is this, or 0 in shadow mode) |
| `scored_sims`, `alerting_sims`, `max_score` | how many members have a SIM-day row (none for a slow-roll box), how many alert on their own (`tb_findings.status = alert`) and the highest score |
| `est_bypass_minutes` | the members' talk minutes (answered call seconds / 60), an estimate of the traffic, not money |
| `cells`, `imeis`, `batches` | what else the members share: one cell and one device is a classic box; many cells on one device is a rotated box |
| `days_seen`, `first_day`, `last_day` | how long this key has been a box (the Incident is opened once and not refreshed; this row is) |
| `example_msisdns` | up to five members, in number order; `tb_cluster_members` lists them all with their device, cell and batch |

**What to do with a box Incident.** Open the `tb_box_members` Link Analysis view for the key, read the members, then verify before acting: a call to the
owner of the site or to the dealer of the batch, or a test call. A verified PBX or dialler goes on `tb_allow` (its SIMs then stop counting toward the box);
a confirmed box is barred through the operator's provisioning process (an approval-gated step, never automatic) and its SIMs go on `tb_block`. A box
whose SIMs share no device, no cell and no activation batch cannot be linked from CDRs and will not appear here.

## What looks like a box and is not (from CDRs alone)

- **Silent by design:** a call centre (many inbound callers), an alarm or M2M line (few numbers), a courier (moves across
  cells), a dense building (ordinary service mix), a visiting roamer (`roaming = Y` calls are left out), a new heavy user.
- **Not silent, and cannot be:** an unregistered PBX or outbound-dialler SIM that never receives calls, sends SMS or moves is
  identical to a quiet SIM-box. `box_like` is true for it. The answer is *needs verification*, not a tighter threshold. A
  verified line goes on the `tb_allow` list with an owner and an expiry; there is no exemption parameter in this pack.

## Thresholds are configuration

Every threshold is a Job parameter: `config/jobs/tb_features_job.toon` (`min_events`, `short_s`, `seq_step`, `seq_prefix`,
`night_end_hour`, `feed_zone`, `local_zone` and the five `cand_*` clauses), `tb_factors_job.toon` (the same five `cand_*` values, which must
stay equal to the first Job's, and `inbound_full`, `mix_full`, `mobility_top_cell`, `mobility_max_kmh`, `rtc_hours`) and
`tb_findings_job.toon` (`min_signals`, `persist_days`, `persist_window`, `shadow`), `tb_cluster_members_job.toon` (`cand_min_events`, `min_box_sims`
and the same five `cand_*` clauses and `short_s`, which must stay equal to `tb_features`' values) and `tb_clusters_job.toon` (`spoof_imei_count`, `shadow`: set it with the findings' `shadow` when running in shadow mode). The weights and the high threshold are in
`config/registry/risk-scores/tb_sim.toon`. The shipped values are **illustrative**: no number
here is a vendor or industry fact, and none has been calibrated on a real operator's traffic. Calibrate them in a shadow
period before anyone acts on `box_like`.

## Feed contract

- `voice_cdr`: one row per call, SMS or data session. `a_number`, `b_number` and `start_ts` are required; the sequential-dialling
  and own-network signals expect bare E.164 digits for `b_number` (normalise at mediation). Timestamps are naive: every row must
  be in the one zone named by `feed_zone` (default `UTC`). `answered`, `release_cause`, `imei` and `imsi` are optional; leave the
  columns blank, or omit them from the file, when the feed does not carry them.
- `cell_site`, `number_plan`, `subscriber_profile`: small Reference feeds, loaded the same way (copy the CSV into
  `data/inbox/<feed>`). Each is optional; absent, the signals that need it are omitted and named in `missing`.

## Sizing

The SIM-day stage holds its working set in DuckDB memory. Measured on one synthetic day on a 12-core developer box with the
default memory cap (3,246 MiB): peak DuckDB memory about 0.7 GiB at 1 million CDR rows, **1.8 GiB at 5 million** (the real Job
took 6.8 s, no spill), 2.5 GiB at 7.5 million and **3.1 GiB at 10 million, 97 % of the cap**. A 5 million row day under a 1 GiB
cap spilled 394 MiB and took about 2.6 times as long. **Above roughly 8 million rows a day on the default cap, raise
`processing.duckdb.memory_limit` to at least 4 GiB**; on an 8 GiB host the default cap is the 1 GiB floor, so size it
explicitly. These figures are one synthetic day (the SIM cardinality is about 400,000), not an operator's volume; the
cluster stage added by a later slice is not included.
