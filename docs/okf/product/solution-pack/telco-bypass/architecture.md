# Telecom bypass (SIM-box)

> **Level 2 solution page.** Content pack: Space Template `telco-bypass`
> (`spaces/_templates/telco-bypass/template.toon`). **Slice B1 of `TELCO-BYPASS-SIMBOX-1` is built: the signals only.**
> Scoring, Entity Lists, Alert Rules and the box-level Incident are later slices
> ([plan](../../../../superpower/bypass-simbox-detection-plan.md)). Checked against the template on 2026-10-20.

## Business problem

A SIM-box (SIM bank) terminates bypassed international calls as local calls through many local SIMs on one site. Seen from the
operator's own voice CDRs, those SIMs make a lot of short, evenly spaced calls to a different number almost every time, are
almost never called back, send no SMS, use no data, sit on one cell and run round the clock. The existing `telco-fraud` pack's
`fraud_simbox` Job looks for one chain of four conditions and scores everything else zero; this pack computes the individual
signals so a later score can say which fired and why.

A CDR pattern is evidence of consistency with a box, never proof. Probes, signalling and test calls are out of scope
(matrix rows F and G), and a quiet PBX or dialler SIM is identical to a quiet box from CDRs alone.

## Data in

Four Pipelines with Schemas under `spaces/_templates/telco-bypass/config/`:

| Feed | Contents | Needed by |
|---|---|---|
| `voice_cdr` | one row per call, SMS or data session: `record_id, start_ts, direction, service, a_number, b_number, duration_s, roaming, cell_id` plus the optional `answered, release_cause, imei, imsi` | every signal |
| `cell_site` | `cell_id, site_id, lat, lon` | implied speed (S6) |
| `number_plan` | `prefix, operator, own` | own-network targets (S11) |
| `subscriber_profile` | `msisdn, plan, dealer_id, activated_on, corporate_flag` | tenure and activation batch (S12) |

The `voice_cdr` Pipeline sets `csv_settings.null_padding: true`, so a file that stops after `cell_id` ingests with the optional
columns NULL; without it the whole file would be quarantined as a field mismatch. Timestamps are naive: a feed arrives in one
agreed zone, named by the Job parameter `feed_zone`. Every Dataset ships a zero-row schema seed under
`spaces/_templates/telco-bypass/data/`, so the Job runs on a fresh Space and an absent Reference reads as empty.

## What the pack sets up

One Job, `tb_features` (`sql.template`, 03:00 daily, window `$yesterday`, `incremental` by `window_date` with `lookback: 1` and
`retain: 31`), writes the Dataset `tb_features`: one row per SIM per day with at least `min_events` (default 20) non-roaming MO
voice calls.

| Signal | Columns |
|---|---|
| S1 B-number diversity | `b_diversity`, `distinct_b` |
| S2 call shape | `short_call_ratio`, `acd_s`, `acd_cv` |
| S3 answer rate | `asr`, `failed_share` |
| S4 inbound share | `inbound_ratio`, `calls_mt` |
| S5 service mix | `voice_only_share`, `sms_mo`, `data_s` |
| S6 mobility | `distinct_cells`, `top_cell_share`, `max_implied_kmh` |
| S7 sequential dialling | `seq_dial_share` |
| S8 hour profile | `active_hours`, `night_share`, `hour_entropy` |
| S9 cadence | `max_calls_1h`, `max_distinct_b_1h`, `gap_cv` |
| S10 overlapping calls | `overlap_calls` |
| S11 own-network targets | `onnet_target_share` |
| S12 tenure and batch | `tenure_days`, `dealer_id`, `new_batch_share` |

Plus `box_like` (five strict clauses, all Job parameters), `missing` (the signals omitted for that SIM), `has_imei`,
`has_imsi` and `evidence_ids` (the three shortest answered calls).

## Decisions and seams worth knowing

- **Per-signal degradation (D-BP2).** `answered` / `release_cause`, `imei`, `imsi` and each Reference are optional. A signal
  whose input is absent is NULL and named in `missing` (`S3`, `S6`, `S11`, `S12`); nothing fails. Release cause `16` counts as
  answered only when `answered` is blank.
- **Device signals deferred.** S13 and S14 (SIMs per IMEI, IMSI churn, TAC class) and the cluster stage are not built; they wait
  for the cluster slice, and the `imei` cluster key for the CDR contract answer. `has_imei` / `has_imsi` record presence only.
- **Batch, as built.** `new_batch_share` is the share of the SIMs activated by the same dealer on the same day that are `box_like`
  today. The plan's "consecutive MSISDN" refinement belongs to the cluster stage's `batch` key.
- **Roaming.** MO calls with `roaming = Y` are left out of every call-shape signal (a roamer with only such calls has no row);
  they still count in the SIM's service mix and inbound share.
- **Window and zone.** The window is `[window_start, window_end)`. Hour signals are judged in `local_zone`, converted from
  `feed_zone` with `timezone()`, so the result does not depend on the host's session zone; the golden runs the statement under
  two zones.
- **Cost.** ⚠ `WITH w AS NOT MATERIALIZED (...)` and `arg_min(record_id, (duration_s, record_id), 3)` are load-bearing: without
  the first DuckDB materialises every column of the day's rows once per use (peak memory about 2.8 GiB instead of 1.8 GiB at 5 million
  rows), and a per-SIM `list(... ORDER BY ...)` for the evidence ids made the statement four times slower.
- **Thresholds** are Job parameters and **illustrative**; none is calibrated on a real operator's traffic.

## Verification

`BypassFeatureGoldenTest` (module `inspecto`, beside `TelcoFraudTemplateGoldenTest`) boots the template through `POST /spaces`,
ingests the seeded synthetic `BypassCorpus` through the template's own Pipelines, runs the Job as authored and holds every
signal of every scored SIM to an independent plain-Java oracle; the `box_like` set to the exact planted set (a 60-SIM box and a
20-SIM IMEI-spoofing box, plus one SIM just inside each clause) with every look-alike silent (call centre, alarm line, couriers,
new heavy user, dense building, visiting roamer, sequential dialler, cloned SIM, and one SIM exactly at each threshold); the
degradation cases; and zone independence.

## Related

[user guide](user-guide.md) · [admin guide](admin-guide.md) · [features](features.md) · [plan](../../../../superpower/bypass-simbox-detection-plan.md) ·
the starter typology it complements: [Telecom fraud](../telco-fraud/architecture.md).
