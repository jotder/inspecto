# Telecom bypass (SIM-box)

> **Level 2 solution page.** Content pack: Space Template `telco-bypass`
> (`spaces/_templates/telco-bypass/template.toon`). **Slices B1 to B3 of `TELCO-BYPASS-SIMBOX-1` are built: the signals, the
> Risk Score, the allow and watch lists, persistence, agreement, shadow mode, the SIM Alert, the cluster stage and the box Incident
> and Case.** The Anomaly Model factor and the 7-day device signals are later slices
> ([open work](../../../../superpower/telco-packs-plan.md); decisions, measurements and traps of B0 to B3 in the
> [as-built record](plan-as-built.md)). Checked against the template on 2026-10-20.

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
| 03:30 | `tb_findings` | `sql.template`, `incremental`, `retain: 31` | `tb_factors`, `risk_scores_tb_sim_latest`, its own history, `entity_list_tb_allow`, `entity_list_tb_watch` | `tb_findings`: the Dataset both SIM Alert Rules read |
| 03:40 | `tb_cluster_members` | `sql.template`, `incremental`, `retain: 31` | `voice_cdr`, `subscriber_profile`, `entity_list_tb_allow` | `tb_cluster_members`: one row per candidate SIM that shares a box (below) |
| 03:50 | `tb_clusters` | `sql.template`, `incremental`, `retain: 31` | `tb_cluster_members`, `tb_features`, `tb_findings`, its own history | `tb_clusters`: one row per suspected box per day, the Dataset the box Alert Rule reads |

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

## The cluster stage and the box

A box is many SIMs on one site and few devices, so the work item is the box, not the SIM (D-BP3). Two Jobs find it.

**`tb_cluster_members`** reads the day's `voice_cdr` itself (not `tb_features`, because a slow-roll box keeps every SIM under the SIM-day
volume floor) and keeps the SIMs that are **candidates**: at least `cand_min_events` (default 10, against 20 for `tb_features`) non-roaming MO
voice calls and all five `box_like` clauses (inbound under 5 %, B-number diversity over 0.9, short-call share over 0.9, voice-only share over
0.99, top-cell share over 0.9; every clause strict, the same parameter values as `tb_features`, pinned equal in a test), and which are not
on a live `tb_allow` entry. Each candidate has three possible keys: its dominant **IMEI** (`imei`), its dominant **cell** (`cell`) and its
**activation batch** (`dealer/activation day`, `batch`). A key is a box when at least `min_box_sims` (default 5) candidates share it, and a
SIM joins the **first** key that is a box, in that order, so one box is one `cluster_key` (`imei:<imei>`, `cell:<cell>`, `batch:<dealer>/<day>`).
Two devices at one cell are two boxes; a box that swaps IMEIs on every call has no shared device and is caught by its cell; with no `imei`
column at all the cell key finds every box whose SIMs share a cell. Only SIMs in a box are written, so no row has a NULL key (the Case Rule trap of
B0 answer 3, [as-built record](plan-as-built.md)). `imei_count` (IMEIs a SIM used today) is the evidence of swapping.

**`tb_clusters`** rolls the members up to one row per box per day: `candidate_sims`, `scored_sims` (members that have a `tb_features` row; a slow-roll
box has none), `alerting_sims` (members whose `tb_findings.status` is `alert`), `max_score`, `est_bypass_minutes` (the members' talk minutes),
the distinct `cells`, `imeis` and `batches` (what else the members share), `spoofed_sims` (members using at least `spoof_imei_count` (3) IMEIs),
`first_day`, `last_day`, `days_seen` (from the Job's own history; a re-run does not count its own night twice and a gap does not reset the
first day), up to five `example_msisdns`, and `alert_value`: `candidate_sims`, or 0 when the `shadow` parameter is `true`.

## The Alert Rules

| Rule | Reads | Severity | Raises |
|---|---|---|---|
| `tb_box_suspected` | `max(alert_value)` of `tb_clusters`, by `cluster_key`, `gte 1`, `stormCap 500`, runbook `tb_box_suspected` | CRITICAL | one Alert and **one Incident per box**: the work item and the Case anchor |
| `tb_sim_high` | `max(alert_score)` of `tb_findings`, by `entity_key`, `gte 1`, `stormCap 1000` | CRITICAL | one Alert and one Incident per SIM: the evidence beneath the box |
| `tb_allowed_line_changed` | `max(shape_changed)` of `tb_findings`, by `entity_key`, `gte 1` | INFO | one Alert for all such lines; no per-SIM Incident (only CRITICAL or error opens one) |

The Case Rule `tb_box_case` (`config/ops/tb_box_caserule.toon`: CRITICAL Incidents, `groupBy: cluster_key`, threshold 1) opens one Case per box
Incident. A per-SIM Incident carries `key.entity_key`, not `key.cluster_key`, so it never joins a box Case. The `tb_box_members` Link Analysis view
projects `tb_cluster_members` (SIM to box, link kind = the key that decided it) and the registry runbook `tb_box_suspected` tells the analyst what to read.

⚠ Every rule reads the whole retained Dataset (31 days), so a SIM or a box that alerted stays breached until its day partition is pruned: an Incident
opens once and the rule does not heal early, and a second night opens no second Incident for the same box (the box row's `days_seen` and `last_day`
move; the Incident's text is not refreshed). The SIM rule's `stormCap` is 1000, the ceiling, because the probe counts distinct SIMs over the retention, and
above it the rule opens ONE storm Incident and no per-SIM Incident: 1,200 SIMs of 20 boxes are expressed by the 20 box Incidents (the G-SB(b) re-check, [as-built record](plan-as-built.md)).

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
- **Device signals.** The `imei` cluster key (SIMs per IMEI today) and the IMEI-swap count are built. S13's 7-day IMSI churn, S14 (TAC class) and S15's
  cell concentration and concurrent calls per IMEI are not.
- **A box nothing links is invisible.** SIMs on different cells, different devices and different activation batches share no key; with no device column, a
  box of that shape cannot be found from CDRs by this stage.
- **No box persistence.** A box Incident opens on the first night `min_box_sims` candidates share a key (the SIM Alerts wait for persistence); raise
  `min_box_sims` or run in shadow first if that is too eager for your traffic.
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

`BypassClusterTest` runs the two cluster stages in isolation over hand-made inputs (each key, the fallback without a device column, the
precedence between keys, the box size and every candidate clause AT and just INSIDE its threshold, the volume floor, the window edges, roaming, the allow
list, the IMEI-swap count and the roll-up). `TelcoBypassClusterGoldenTest` runs the full chain over the B3 corpus and holds every member of every box to a
plain-Java oracle written from the raw call rows, the six planted boxes by name (one device, a slow-roll box under the SIM-day floor, an IMEI-swapping box caught
by its cell, a two-device farm, the smallest box of five), every look-alike silent (a dense building, a block of SIMs that receive calls, four candidates and a
fifth under the floor, the PBX pair), one Incident per box that a second night and a re-run do not repeat, the same boxes through the cell key without a device column, and
shadow mode. `BypassBoxCaseRuleTest` (module `inspecto-case-management`) runs the shipped Alert Rules and Case Rule against the real object engine.

## Related

[user guide](user-guide.md) · [admin guide](admin-guide.md) · [features](features.md) · [as-built record](plan-as-built.md) ·
[open work](../../../../superpower/telco-packs-plan.md) ·
the starter typology it complements: [Telecom fraud](../telco-fraud/architecture.md).
