# Telecom bypass (SIM-box) runbooks

> **Skeleton (slice B1).** This Space Template computes the daily per-SIM signals only. It scores nothing, keeps no
> Entity List and raises no Alert yet, so there is no Incident to work from this file: the per-signal reading guide
> below is for the analyst who opens the `tb_features` Dataset. The SIM and box runbooks are added with the
> Alert Rules in the next slices (`docs/superpower/bypass-simbox-detection-plan.md`).

**A CDR pattern is evidence of consistency with a SIM-box, never proof.** Only a verification step (a call to the
customer or the dealer, a test call) separates a quiet box from a quiet dialler or PBX, and that step is a process, not
something this pack does.

## What runs

One Job, `tb_features` (`sql.template`, daily at 03:00), reads the day before from the `voice_cdr` Dataset and the three
Reference Datasets (`cell_site`, `number_plan`, `subscriber_profile`) and writes one row per SIM per day to the
`tb_features` Dataset (31 days kept). A SIM with fewer than `min_events` (default 20) non-roaming MO voice calls that day
gets no row. Run it for another day by passing `window_start`, `window_end` and `day` on the run; without `day` the rows
fall outside the run's day and are dropped.

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

## What looks like a box and is not (from CDRs alone)

- **Silent by design:** a call centre (many inbound callers), an alarm or M2M line (few numbers), a courier (moves across
  cells), a dense building (ordinary service mix), a visiting roamer (`roaming = Y` calls are left out), a new heavy user.
- **Not silent, and cannot be:** an unregistered PBX or outbound-dialler SIM that never receives calls, sends SMS or moves is
  identical to a quiet SIM-box. `box_like` is true for it. The answer is *needs verification*, not a tighter threshold. A
  verified line goes on an allow list with an owner and an expiry; that list arrives with slice B2, and until then there is no
  exemption parameter in this pack.

## Thresholds are configuration

Every threshold is a Job parameter in `config/jobs/tb_features_job.toon` (`min_events`, `short_s`, `seq_step`, `seq_prefix`,
`night_end_hour`, `feed_zone`, `local_zone` and the five `cand_*` clauses). The shipped values are **illustrative**: no number
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
