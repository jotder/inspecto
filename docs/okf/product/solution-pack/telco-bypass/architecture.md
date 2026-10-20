# Telecom bypass (SIM-box)

> **Level 2 solution page.** Content pack: Space Template `telco-bypass`
> (`spaces/_templates/telco-bypass/template.toon`). **Slices B1 and B2 of `TELCO-BYPASS-SIMBOX-1` are built: the signals, the
> Risk Score, the allow and watch lists, persistence, agreement, shadow mode and the SIM Alert.** The cluster stage, the box-level
> Incident and Case, the Anomaly Model factor and the device signals are later slices
> ([plan](../../../../superpower/bypass-simbox-detection-plan.md)). Checked against the template on 2026-10-20.

## Business problem

A SIM-box (SIM bank) terminates bypassed international calls as local calls through many local SIMs on one site. Seen from the
operator's own voice CDRs, those SIMs make a lot of short, evenly spaced calls to a different number almost every time, are
almost never called back, send no SMS, use no data, sit on one cell and run round the clock. The existing `telco-fraud` pack's
`fraud_simbox` Job looks for one chain of four conditions and scores everything else zero; this pack computes the individual
signals, weighs them in an explainable score, and says why a SIM did or did not alert.

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
`spaces/_templates/telco-bypass/data/`, so the Jobs run on a fresh Space and an absent Reference reads as empty.

## The nightly chain

| Time | Job | Type | Reads | Writes |
|---|---|---|---|---|
| 03:00 | `tb_features` | `sql.template`, `incremental` by `window_date`, `retain: 31` | the four feeds | `tb_features`: one row per SIM per day with at least `min_events` (default 20) non-roaming MO voice calls |
| 03:10 | `tb_factors` | `sql.template`, a snapshot of the latest window | `tb_features` | `tb_factors`: the Risk Score's factor columns, the count of primary signals, `est_bypass_minutes` |
| 03:20 | `tb_risk_score` | `risk.score` | `tb_factors` | `risk_scores_tb_sim` (history) and `risk_scores_tb_sim_latest` |
| 03:30 | `tb_findings` | `sql.template`, `incremental`, `retain: 31` | `tb_factors`, `risk_scores_tb_sim_latest`, its own history, `entity_list_tb_allow`, `entity_list_tb_watch` | `tb_findings`: the Dataset both Alert Rules read |

The twelve signals of `tb_features`:

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

## The Risk Score

`tb_sim` (`config/registry/risk-scores/tb_sim.toon`, entity type `sim`, high at 60): eleven factors, each `weight x max(column)` over
`tb_factors`, held to its own weight as the cap, summed and clamped to 0 to 100. The positive weights are 14 (S1), 12 (S2), 14 (S4), 10 (S5),
8 (static cell), 8 (number walking), 8 (night share), 6 (own-network targets): 80 in all, the plan's illustrative values; the device-cluster
(12) and anomaly (8) factors of the plan arrive with later slices. Three protective factors subtract: receives calls (up to -20), uses SMS
or data (up to -15), moves across cells at a plausible speed (-12). Each stored score carries its factors (value, weight, contribution,
capped, evidence), so `RiskScorer.recompute` reproduces it from the row.

The factor columns are computed by the Job, because a threshold crossing is a function of configuration and a Measure cannot express
"above the floor". The five signals of `box_like` are 0-or-1 flags using the same strict comparisons and the same parameter values (one
set of numbers, pinned equal in a test); the shares (number walking, night, own-network) are used as they are; the two protective
shares are graded between a floor and a full value (`inbound_full`, `mix_full`) and mobility is a flag (top cell under
`mobility_top_cell` and no implied speed above `mobility_max_kmh`, so a cloned SIM never counts as mobile).

`primary_signals` counts S1, S2, S4, S5, static cell and round the clock (`active_hours >= rtc_hours`, default 18).

## What `tb_findings` decides

One row per SIM at or above the high threshold, and one per allowed SIM whatever its score (that is how an allowed line is followed).
`status` is decided in this order:

1. `allowed`: a live entry in `entity_list_tb_allow` (key, prefix or same-length range; `expires_at` null or after the window's end, strictly).
2. `agreement`: fewer than `min_signals` (default 5) primary signals.
3. `persistence`: fewer than `persist_days` (default 2) high nights in the last `persist_window` (default 3) nights counting tonight; a SIM with a live entry in `entity_list_tb_watch` needs one fewer. Only nights at or above high count (an allowed night counts if it was high).
4. `shadow`: everything above passed but the `shadow` parameter is `true`.
5. `alert`: `alert_score` is the score; every other status writes 0.

`shape_sig` is the allowed line's signature (no inbound, receives calls, mobile, uses other services); `shape_changed` is 1 when an allowed line's
signature differs from its previous finding and shadow mode is off. `top_factors` lists the three largest positive contributions
(ties by factor id), `est_bypass_minutes` is calls x answer rate x mean answered duration / 60 (an estimate, D-BP8; the optional rate parameter is not built).

## The Alert Rules

| Rule | Reads | Severity | Raises |
|---|---|---|---|
| `tb_sim_high` | `max(alert_score)` of `tb_findings`, by `entity_key`, `gte 1`, `stormCap 1000` | CRITICAL | one Alert and one Incident per SIM |
| `tb_allowed_line_changed` | `max(shape_changed)` of `tb_findings`, by `entity_key`, `gte 1` | INFO | one Alert for all such lines; no per-SIM Incident (only CRITICAL or error opens one) |

⚠ Both rules read the whole retained Dataset (31 days), so a SIM that alerted stays breached until its day partition is pruned: an Incident
opens once and the rule does not heal early. The `stormCap` is 1000, the ceiling, because the probe counts distinct SIMs over the retention.
D-BP3 makes the box Incident the Case anchor; that is slice B3, and this per-SIM rule is the evidence beneath it.

## Entity Lists

A Space Template cannot ship an Entity List, so the pack ships a zero-row copy of the sidecar of `tb_allow` and `tb_watch` (with the list's
marker) and the Jobs read those until the lists are created (admin guide). `tb_block` and `tb_ranges` are created by hand and not yet
read by anything. List ids use underscores because the store name is `entity_list_<id>`. The Risk Score model ships without `watchList`;
the operator adds it after creating `tb_watch`.

## Decisions and seams worth knowing

- **Per-signal degradation (D-BP2).** `answered` / `release_cause`, `imei`, `imsi` and each Reference are optional. A signal
  whose input is absent is NULL and named in `missing` (`S3`, `S6`, `S11`, `S12`); nothing fails. In the score a NULL factor input is
  `missing`, counts as 0 and is flagged in the stored factors, so a Space without `number_plan` scores lower than one with it for the same traffic.
  Release cause `16` counts as answered only when `answered` is blank.
- **Device signals deferred.** S13 and S14 (SIMs per IMEI, IMSI churn, TAC class) and the cluster stage are not built.
- **Batch, as built.** `new_batch_share` is the share of the SIMs activated by the same dealer on the same day that are `box_like` today.
- **Roaming.** MO calls with `roaming = Y` are left out of every call-shape signal; they still count in the SIM's service mix and inbound share.
- **Window and zone.** The window is `[window_start, window_end)`. Hour signals are judged in `local_zone`, converted from
  `feed_zone` with `timezone()`, so the result does not depend on the host's session zone. The allow and watch lists compare `expires_at` with the
  window's end as stored, both naive timestamps: the sidecar writer stores an entry's expiry in the HOST's zone (measured: 12:00Z is stored as 17:30 on a
  host in Asia/Kolkata), while the window follows `feed_zone`, so keep the two the same (normal when the host runs in the operator's zone) or an entry lapses a zone offset early or late.
- **Staleness.** `tb_findings` joins `tb_factors` (only rows of its own window) to the latest scores; a scores run older than the window is rejected
  rather than joined (`risk_scores_tb_sim_latest.scored_at` must be at or after the window's end).
- **Cost.** ⚠ `WITH w AS NOT MATERIALIZED (...)` and `arg_min(record_id, (duration_s, record_id), 3)` in `tb_features` are load-bearing: without
  the first DuckDB materialises every column of the day's rows once per use (peak memory about 2.8 GiB instead of 1.8 GiB at 5 million
  rows), and a per-SIM `list(... ORDER BY ...)` for the evidence ids made the statement four times slower.
- **SQL traps found in B2.** `asof` is a DuckDB keyword (a CTE of that name does not parse); a `window_date` read from a store view needs an explicit `CAST(... AS DATE)`
  before it is compared with a date; an Alert Rule `threshold` must be positive (`gt 0` is refused, use `gte 1`); a TOON list is `evidence[2]: a, b`, not `evidence: [a, b]`.
- **Thresholds and weights** are configuration and **illustrative**; none is calibrated on a real operator's traffic.

## Verification

`BypassFeatureGoldenTest` (module `inspecto`, beside `TelcoFraudTemplateGoldenTest`) boots the template through `POST /spaces`,
ingests the seeded synthetic `BypassCorpus` through the template's own Pipelines, runs `tb_features` as authored and holds every
signal of every scored SIM to an independent plain-Java oracle, the `box_like` set to the exact planted set and every look-alike silent.

`TelcoBypassGoldenTest`, `BypassScoreTest` and `BypassAllowListTest` (module `inspecto-scoring`, which reads `BypassCorpus` through the core's
test-jar) hold the rest: the factor columns, primary signals and score of every scored SIM to the same kind of oracle; the Alerts to the planted set
(every planted SIM once, with its planted top factors) and every look-alike silent for the factor asserted to suppress it; an allow entry that
suppresses and one whose expiry un-suppresses; a changed allowed line; persistence, agreement and the watch list; shadow mode; and the seeds to the Jobs' SQL.

## Related

[user guide](user-guide.md) · [admin guide](admin-guide.md) · [features](features.md) · [plan](../../../../superpower/bypass-simbox-detection-plan.md) ·
the starter typology it complements: [Telecom fraud](../telco-fraud/architecture.md).
