# Bypass / SIM-box detection from CDRs: a plan

> **ARCHIVED 2026-10-22** — open work moved to docs/superpower/telco-packs-plan.md; as-built facts in the telco-bypass hub
> ([`okf/product/solution-pack/telco-bypass/`](../../okf/product/solution-pack/telco-bypass/index.md), `plan-as-built.md`).

> 🟡 **PLAN 2026-10-18 (`TELCO-BYPASS-SIMBOX-1`): B0 measured, B1 and B2 built 2026-10-20 (§16, §17, §18); B3 to B7 not built.** Competitive gap **E** (bypass /
> SIM-box detection) is **Partial** in [`COMPETITIVE_CAPABILITY_MATRIX.md`](../../okf/product/layer/product/brochure-competitive-matrix.md):
> LATRO, Subex, Mobileum, Neural and Araxxe sell it, and the matrix records Inspecto as "CDR-based only". The same
> matrix places signalling probes (row F) and test-call generation (row G) **out of scope**, so this plan is
> **CDR-based only by design** and says in §11 which RFP lines it therefore cannot close. Interconnect-side fraud that is
> not bypass (IRSF, Wangiri, flash calls; row H) is planned by a sibling document and is **not** touched here.
> Decisions **D-BP1…D-BP9 were answered 2026-10-20; D-BP10 stays open** (§14). Every number in this plan (volumes, thresholds, weights) is a hypothesis
> until slice B0 measures it or an operator calibrates it; none is a vendor or industry fact.
>
> **Method.** Read the matrix rows E, F, G and H, the `telco-fraud` Space Template (its `fraud_simbox` Job, Alert Rule,
> Dataset, runbook, anomaly model, Case Rule and golden corpus), the Anomaly Scores, Risk Scores and Entity Lists concept
> pages, `docs/GLOSSARY.md`, the two sibling plans ([re-rating](rerating-tariff-validation-plan.md),
> [partner settlement](partner-settlement-wholesale-plan.md)) and the MVNO RFP working set (compliance matrix, gap
> register, build plan, content catalogue). Every statement about "what exists" cites a file read on 2026-10-18.

## 1. What exists today, and the gap

### 1.1 What `telco-fraud` already does for SIM-box

One `sql.template` Job, `fraud_simbox` (`spaces/_templates/telco-fraud/config/jobs/fraud_simbox_job.toon`), runs at 02:00
over yesterday's `cdr` rows:

1. For every originating number (`direction = 'MO'`) it counts **distinct voice targets**, SMS sent and **distinct cells**; for
   every terminating number it counts calls received.
2. A line is a **candidate** at 10 or more distinct voice targets (`min_candidate_targets`), and its `simbox_targets` is the
   target count **only if** it received no call, sent no SMS, used at most one cell (`max_cells`) and is not in the static
   `exempt_msisdns` parameter; otherwise 0.
3. The Alert Rule `fraud_simbox` is `by: msisdn`, `measure: max(simbox_targets)`, `gt 50`, CRITICAL, `stormCap 500`, with a
   runbook component; the golden corpus (`TelcoFraudCorpus`, `TelcoFraudTemplateGoldenTest`) plants 4 offenders (3 at 80 targets,
   one **just above** at 51) against 5 look-alikes (busy but receives calls, moves across four cells, **at** the threshold of 50, and a
   registered PBX line on the exempt list).
4. A separate **Anomaly Model** `tf_subscriber_usage` scores each calling number's daily outgoing voice seconds, SMS and
   data seconds against its own 28-day history; its Alert Rule is a deferred seed (`pending/alert-rules`) created after the
   model's first run.

The feed that carries all of this is the 10-column `cdr` schema (`config/cdr/cdr_schema.toon`): `record_id, start_ts,
direction, service, a_number, b_number, duration_s, charge, roaming, cell_id`. **There is no IMEI, IMSI, call disposition,
release cause, trunk, called-number type or presented CLI in it.**

### 1.2 What it cannot do (the gap)

| # | Limit, read from the Job, the Alert Rule and the schema | Consequence |
|---|---|---|
| E1 | One signal chain: distinct targets AND no inbound AND no SMS AND ≤ 1 cell. All four must hold or the score is **0**. | A box that rotates SIMs, keeps a few inbound calls alive, or sits on two cells scores 0; there is no partial credit and no "nearly". |
| E2 | No call-shape signals: duration, answer rate, short-call share, cadence, sequential dialling, hour-of-day profile. | The classic short, uniform, round-the-clock call pattern is never looked at. |
| E3 | No data or SMS **volume** signal (only `sms_sent = 0`), no tenure, no dealer or activation-batch link. | A SIM that also browses a little, or a fresh batch activated together, is invisible. |
| E4 | No device or SIM identity: no IMEI, IMSI, SIM-per-device or IMSI-churn signal; no cluster. | The unit of fraud is a **box** holding tens of SIMs on one site; the pack alerts per SIM and cannot say "these 60 lines are one box". |
| E5 | The cell is a string; there is no cell location. | No co-location, no site concentration and no "impossible movement" (cloned SIM). |
| E6 | The exemption is a static, hand-edited parameter: `exempt_msisdns`. | No owner, no expiry, no approval, no review; the pack's own runbook states that an unregistered PBX looks exactly like a SIM-box and offers "no defence here". The recorded trust note ("lists must come from a verified register") is unmet. |
| E7 | The threshold is one number, `gt 50`, on one signal; no score, no explanation of **why** a line alerted. | The analyst re-derives the reasons by hand; a tuning change moves one cliff edge. |
| E8 | No watch list, block list or allow list; no feedback from a closed Incident. | A confirmed box is not remembered and a false positive is not suppressed next night. |
| E9 | One Incident per offending SIM at most; the Case Rule groups on `msisdn` only. | Sixty SIM Incidents for one box are sixty work items. |
| E10 | Daily 02:00 window only; no peer baseline (compared with itself only, never with a cohort). | A new heavy line has no history, and a cohort-unusual line is not flagged. |

## 2. What CDRs can and cannot show (scope)

**In scope (v1, CDR-based):** originating-side evidence of **local SIMs used to terminate bypassed international traffic**
(the SIM-box case), observed in the operator's own voice CDRs (plus optional SMS and data session records for the
"does it behave like a person" test), and, as a **demand-gated** later slice, interconnect-side CDRs for CLI anomalies per
trunk (§10, B5).

**Out of scope, stated up front:**

- Any signal that needs a probe or a signalling feed (SS7, MAP, Diameter, SIP): matrix row **F**, RFP rows `RT-21` … `RT-24`.
- Test-call generation and call verification (matrix row **G**). Nothing in this plan confirms a bypass by calling it; a
  CDR pattern is **evidence of consistency with** a box, never proof.
- Inline blocking or per-call validation (the RTSC partner, §11). This plan publishes **lists and scores**, and raises
  **Action Requests** for approval; it never decides a live call.
- Bypass that leaves no local-SIM trace in the CDRs: OTT or VoIP bypass, a box on a foreign network, SMS A2P grey routes (the
  last is `FM-R13` / `T-07`, a different typology).
- A trained model. Smart here means **deterministic, explainable, recomputable**, the standard Risk Scores and Anomaly Scores already hold
  ([`okf/product/module/scoring/spec-risk-scores.md`](../../okf/product/module/scoring/spec-risk-scores.md),
  [`anomaly-scores.md`](../../okf/product/module/anomaly/spec-anomaly-scores.md)); ML scoring stays the roadmap gap `G-21` (`FM-18`).

### 2.1 The CDR contract this plan needs (decision D-BP2)

Today's feed has 10 columns. A real MSC or mediation CDR often carries more, but **whether this operator's feed (and, for
an MVNO, the MVNE's export) does is the largest unknown in the plan** (the gap register records the same dependency for
IMEI, cell and SIM-change data, `G-38` and `G-26`). The plan therefore defines one extended feed, `voice_cdr`, with columns marked
by how much each signal needs them, and **degrades per signal instead of failing**: a signal whose column is absent is omitted
from the score and flagged `missing`, exactly the Risk Score behaviour for a missing indicator.

| Column | Needed by | If absent |
|---|---|---|
| `start_ts`, `direction`, `service`, `a_number`, `b_number`, `duration_s`, `roaming`, `cell_id` (**today's columns**) | every signal in §3 except those below | the pack cannot run |
| `answered` or `release_cause` (attempt records, not only answered calls) | answer rate (ASR), failed-attempt share | ASR omitted; duration shape still works |
| `imei`, `imsi` | device and SIM-churn signals, device cluster | cluster falls back to cell and activation batch (§4.3) |
| `called_number_type` (own network, other national, international) or a `number_plan` Reference | on-net target share | derived from the Reference by prefix |
| `trunk_id`, `presented_cli`, `network_cli` | interconnect slice B5 only | B5 does not run |

Reference data it needs: `cell_site` (cell to latitude, longitude, site), `subscriber_profile` (plan, dealer, activation date,
corporate flag), `number_plan` (own number ranges by prefix), and **optionally** `tac_class` (a device-type table the operator
licenses; D-BP9).

## 3. The CDR-derivable signals

A **signal** is one numeric column per entity per day, computed in Job SQL (§4.1) and then used three ways: as a **Risk Score
indicator** (a Measure over the signal Dataset, §5), as an **Alert Rule Measure**, and as a **Feature** of the Anomaly Model
(only the day-grain, per-entity Measures qualify; hour-grain buckets are refused by the Anomaly Model today). Thresholds and
weights are configuration, with defaults chosen in B1 and **flagged illustrative**.

| # | Signal (column) | What it is | Why it points at a box | Entity | Needs | Where it is computed |
|---|---|---|---|---|---|---|
| S1 | `b_diversity` | distinct B-numbers ÷ MO voice calls | a person redials; a box dials a new number almost every call | SIM | today's columns | Job SQL |
| S2 | `short_call_ratio`, `acd_s`, `acd_cv` | share of answered calls at or below `short_s`; mean answered duration; its coefficient of variation | call length is capped or uniform on a box, spread out for a person | SIM | `duration_s` | Job SQL |
| S3 | `asr`, `failed_share` | answered ÷ attempted | a box dials numbers it cannot match to subscribers | SIM | `answered` / `release_cause` | Job SQL |
| S4 | `inbound_ratio`, `calls_mt` | MT calls ÷ (MT + MO) | a box SIM is almost never called back | SIM | today's columns | Job SQL (extends today's `mt` CTE) |
| S5 | `voice_only_share`, `sms_mo`, `data_s` | share of the SIM's events that are voice; SMS sent; data seconds | a box SIM has no SMS and no data | SIM | today's columns | Job SQL |
| S6 | `distinct_cells`, `top_cell_share`, `max_implied_kmh` | cells used; share on the top cell; fastest implied speed between consecutive CDRs on different cells | static is expected of a box; **impossible speed** means a cloned or relocated SIM | SIM | `cell_site` Reference for speed | Job SQL |
| S7 | `seq_dial_share` | share of consecutive MO calls whose B-numbers differ by at most `seq_step` and share a prefix | number walking: a box dials a campaign list in order | SIM | `b_number` order by `start_ts` | Job SQL (window `lag`) |
| S8 | `active_hours`, `night_share`, `hour_entropy` | hours with a call; share between local midnight and 05:00; spread across the day | a box runs round the clock; a person sleeps | SIM | `start_ts` and an explicit zone | Job SQL |
| S9 | `max_calls_1h`, `gap_cv` | peak calls in an hour; coefficient of variation of the gap between calls | burst and machine-regular cadence | SIM | `start_ts` | Job SQL |
| S10 | `overlap_calls` | MO calls from one SIM whose `[start, start + duration)` intervals overlap | one SIM cannot hold two calls; a cloned or mis-attributed SIM can | SIM | `duration_s` | Job SQL |
| S11 | `onnet_target_share` | share of B-numbers inside the operator's own `number_plan` | terminated bypass lands on **own** subscribers | SIM | `number_plan` Reference | Job SQL (range predicate in SQL: an equal-key join cannot do prefixes) |
| S12 | `tenure_days`, `new_batch_share` | days since activation; share of the SIM's activation batch (same dealer, same day, consecutive MSISDN) also candidates | boxes are fed with freshly activated SIM batches | SIM | `subscriber_profile` | Job SQL |
| S13 | `imei_count`, `sims_on_imei`, `imsi_churn_7d` | distinct IMEIs the SIM used; SIMs seen on one IMEI today; distinct IMSIs seen on one IMEI over 7 days | many SIMs on one device, or SIMs rotated through one device | SIM and device | `imei`, `imsi` | Job SQL (cluster stage) |
| S14 | `tac_class_gateway` | IMEI's TAC maps to a GSM gateway or modem class | a box's modules are not phones | device | `tac_class` Reference (**optional**) | Job SQL |
| S15 | `candidate_sims_on_cell`, `cell_concentration`, `max_concurrent_on_imei` | SIM candidates per cell; share of a cell's MO voice from candidate SIMs; peak simultaneous calls on one IMEI | the box as a unit: many lines, one site, a port-count ceiling on concurrency | cell, device | S1 to S12, `cell_site` | Job SQL (cluster stage) |
| S16 | `est_bypass_minutes` | answered MO minutes of a candidate SIM to own-network targets | the exposure, in minutes (money is D-BP8) | SIM | S1 to S12 | Job SQL |

⚠ **Signals are not independent** (S1, S2, S7 and S9 all rise with a dialler). The score therefore uses **agreement** (§5.2), not a
plain sum, and the golden corpus plants a benign look-alike for each signal on its own (§12).

## 4. Detection architecture

### 4.1 The staged chain (SQL Jobs over Datasets, no new Step Processor and no new Job Type)

```
voice_cdr (feed) ─► tb_features (SIM-day signals) ─► tb_clusters (device / cell / batch roll-up)
                         │                                  │
                         ├─► anomaly.score (tb_sim_behaviour)│
                         └─► risk.score (tb_sim) ◄───────────┘ (cluster signals are indicators too)
                                   │ watchList feed (≤ 24 h)            │
                                   ▼                                    ▼
                           Entity List tb-watch            Alert Rules: per SIM, per cluster ─► Incident ─► Case
```

Each stage is a `sql.template` Job writing one Dataset, chained with `on_signal: job.run.completed` and the guard
`when: "$signal.job == <previous> && $signal.outcome == SUCCESS"`, the pattern `telco-ra` and the re-rating plan use. Incremental
by partition (`window_date`), `lookback` of one day, `retain` 31. The Job-written Dataset layering is the recorded design
([`okf/product/module/config/plan-spaces.md`](../../okf/product/module/config/plan-spaces.md) §3.5): it avoids cycles and hidden cost. Why SQL and no new
Step: the Step Processor hold was lifted 2026-10-06, but windowed detection already runs as `sql.template` throughout
`telco-fraud`, and the bid plan records velocity as hand-written SQL until it is productised (`G-07`, `WS-14`).

### 4.2 The SIM-day stage `tb_features`

One row per SIM per `window_date` with every S1…S12 column, the `requires` flags (which optional columns were present), the
`event_count` (a floor: a SIM with fewer MO voice calls than `min_events` is **not scored**, the same candidate gate as today's
`min_candidate_targets`) and the example `record_id`s that drive the strongest signals (evidence, up to 3, as Risk Score
factors already carry). ⚠ **DuckDB's session `TimeZone` is the host's, not UTC** (recorded gotcha): `night_share`, `active_hours` and
every day boundary take an explicit zone parameter, and a test runs the corpus under two host zones.

### 4.3 The cluster stage `tb_clusters`

A box is many SIMs on one site and few devices, so the **work item** is the box, not the SIM. Cluster keys, each its own row
type in one Dataset (`cluster_kind`, `cluster_key`):

- `imei`: SIMs on one IMEI, with the 7-day IMSI churn (S13). Reliable only if the box does not spoof IMEIs, which the plan
  does **not** assume; hence the second key.
- `cell`: candidate SIMs per cell and the share of the cell's voice they carry (S15). Needs no device data, so it is the
  **fallback** when `imei` is absent.
- `batch`: candidates that share a dealer and an activation day, or hold consecutive MSISDNs (S12). Links a box to its channel
  without naming a person.

A cluster row carries `candidate_sims`, `scored_sims`, `max_score`, `est_bypass_minutes`, first and last day and up to 5 example
MSISDNs (masked in the UI as the existing classification dictates).

### 4.4 The Anomaly Model as the adaptive layer

`tb_sim_behaviour` (entity `subscriber`, `bucket: day`, `window: 28`, `seasonality: weekday`) takes as Features the day Measures
over the `tb_features` Dataset (`sum(calls_mo)`, `sum(distinct_b)`, `sum(night_calls)` and so on; Measures are `{agg, field}` pairs,
so **ratios live in the Job**, not in the Feature). Its `peers.by` is the plan column of `subscriber_profile` (never a
classified column: the anomaly gate refuses an MSISDN / IMSI cohort). Its job is the **unknown unknown**: a SIM whose shape
changed against itself or its plan cohort. It is **one weighted factor** of the Risk Score (`max(score)` over
`anomaly_scores_tb_sim_behaviour_latest`, a use the Anomaly Scores page already allows), never a standalone alert in v1 (D-BP4).
`exclusionList: tb-allow` removes verified heavy lines before the cohort statistics.

## 5. Scoring and watch-list design

### 5.1 Risk Score `tb_sim` (explainable, 0 to 100)

A `risk-score` component over `tb_features` and `tb_clusters`: `factors[]` each with `dataset`, `key`, `measure`, `weight`, `cap`
and evidence. Illustrative shape (weights are configuration; the convention is: **weights of the positive factors sum to about 100,
protective factors are negative**):

| Factor | Measure | Sign | Illustrative weight |
|---|---|---|---|
| b_diversity above floor | `max(b_diversity_excess)` | + | 14 |
| short, uniform calls | `max(short_uniform_index)` | + | 12 |
| no inbound | `max(no_inbound_flag)` | + | 14 |
| voice only (no SMS or data) | `max(voice_only_flag)` | + | 10 |
| static cell | `max(static_cell_flag)` | + | 8 |
| sequential dialling | `max(seq_dial_share)` | + | 8 |
| round-the-clock | `max(night_share)` | + | 8 |
| own-network targets | `max(onnet_target_share)` | + | 6 |
| device cluster | `max(cluster_candidate_sims_excess)` | + | 12 |
| anomaly vs own history / cohort | `max(score)` of `tb_sim_behaviour_latest` | + | 8 |
| **protective:** receives calls from many distinct callers | `max(inbound_distinct_excess)` | − | up to −20 |
| **protective:** uses data or SMS normally | `max(normal_service_mix)` | − | up to −15 |
| **protective:** moves across cells | `max(mobility_flag)` | − | up to −12 |

The *excess* and *flag* columns are Job-computed (a threshold crossing is a function of configuration), because a Measure cannot
express "above the floor". The clamp, the `high` threshold and the per-factor explanation (`value`, `weight`, `contribution`,
`capped`, `evidence`) come from the shared score output contract, so **every score is recomputable by `RiskScorer.recompute`**
and a reviewer sees which signals fired. Implemented as a deterministic sum, so a change in one weight is a visible,
testable change (gap E7 in §1.2).

### 5.2 Agreement, not just a sum

An Alert is raised only when **both** hold: `score ≥ high` **and** at least `min_signals` of the **primary** signals (S1, S2, S4,
S5, S6 static, S8) are individually positive. The second condition is a column the Job computes (`primary_signals`) and the Alert
Rule's second Measure reads; it is how the pack avoids one inflated factor producing a CRITICAL. (Whether one Alert Rule can
express both conditions or a derived `alert_score` column is needed is a B0 question.)

### 5.3 Entity Lists (the watch-list design)

All four are **Entity Lists** (`allow · block · watch · exclusion`) in the `inspecto-entity-list` module, keyed by `msisdn`, `imsi`,
`imei` or number prefix. They replace the static `exempt_msisdns`.

| List | Purpose | Written by | Expiry and approval | Read by |
|---|---|---|---|---|
| `tb-watch` | `watch` | the Risk Score `watchList` feed after each run: every **high** entity | expires `ttlHours` ≤ 24 after the run; a 24-hour-or-less add applies without a second approver (D-P5) | the analyst's board; the next night's Job (a watched SIM lowers `min_events` and the persistence floor, §7) |
| `tb-block` | `block` | an analyst, on a **confirmed** Incident, through the held `entity-list` approval | permanent until retired; **four-eyes** (`approval: {entity-list: {required: true}}`) | Action Request for barring (§6), the partner list push (§11), Link Analysis `seedBy` |
| `tb-allow` | `allow` | the Fraud Lead, for a verified PBX, dialler or call-centre line, `reason` = the registry id | **expires in ≤ 365 days** so a verification is renewed; four-eyes; owner recorded | the Job anti-join (the Entity List sidecar Dataset `entity_list_tb_allow`, with `expires_at` filtered), and the Anomaly Model as `exclusionList` |
| `tb-ranges` | `watch` | the Fraud Lead, prefix and range entries | four-eyes | S11 and the cluster stage: own-network ranges, and **suspect** ranges (a batch of consecutive MSISDNs flagged together) |

As built (§18): the list ids are `tb_watch`, `tb_allow`, `tb_block`, `tb_ranges` (underscores, so the sidecar store `entity_list_<id>` needs no quoting); only `tb_allow` and `tb_watch` are read by a Job yet.

⚠ **The allow list is the trust boundary.** Its entries come from a person with the authority and a registry id, not from a field the
dealer or subscriber controls (the existing note on `exempt_doc_prefixes`). An allowed SIM is **not unmonitored**: a Job keeps its
signals and raises a distinct, lower-severity "allowed line changed shape" Alert when a mobility, inbound or data signal flips,
so an allow-list entry cannot become a permanent hiding place.

## 6. Incidents and the Case flow

1. **Alert Rules** (all per-entity: `by` a key column, so each offender or box gets its own Alert and Incident, deduplicated per
   Alert Rule and key, as the pack's other 14 do):
   - `tb_sim_high` over `risk_scores_tb_sim_latest`, `by: entity_key`, severity from score band (CRITICAL at or above a second
     threshold);
   - `tb_box_suspected` over `tb_clusters`, `by: cluster_key`, `measure: max(candidate_sims)`, CRITICAL: **the box-level Incident
     is the work item**; the SIM Alerts are its evidence;
   - `tb_allowed_line_changed` (low severity, §5.3).
2. **Incident** opens with owner role `fraud-analyst`, an SLA by severity (the recorded fallback applies when no priority is
   set: `SlaDecisions.deadlineStamp` uses the severity), the runbook, the top three score factors in the description and the
   evidence rows.
3. **Case Rule.** The existing rule groups CRITICAL Incidents by the `key.msisdn` attribute the Alert Rule stamps. A box needs the
   Case to carry **the cluster key** and the member SIM Incidents. Whether one Alert Rule can stamp two keys (`by[2]`), or the
   SIM Incident can carry `cluster_key` as an attribute, or the box Incident alone is the Case anchor, is a B0 question (D-BP3).
4. **Triage evidence** (what the analyst is shown, all from CDRs): the factor table, the SIM's day in call-detail form,
   the cell and device roll-up, the activation batch, a Link Analysis projection of SIM, IMEI, cell and dealer as one Working Set
   (`FM-07`, already shipped), and the Geo Map for cell co-location.
5. **Verdict, typed:** *confirmed box*, *false positive: verified business line*, *false positive: other*, or *needs verification*
   (a customer or dealer call, **a process step**, because only a call or a test call can separate a dialler from a box, §11).
6. **Action (approval, never automatic):** an **Action Request** to the operator's provisioning API (suspend or bar the SIM, bar
   the IMEI), held for four-eyes and sent once with bounded retries ([`okf/product/module/action-requests/spec.md`](../../okf/product/module/action-requests/spec.md)); the
   target API is the customer's, so until one exists the step is a manual task on the Case.
7. **Close** with the typed outcome and the financial-impact fields (`WS-10`; impact stays **manual**, the stance `telco-ra` and
   the re-rating plan already take). A confirmed SIM is added to `tb-block`; a verified false positive to `tb-allow` with expiry.

## 7. False-positive control

False positives are the cost that decides whether a fraud analyst keeps using the pack, and the pack's own runbook concedes
the one it cannot remove. Layers, cheapest first (each is configuration and each gets a look-alike in the corpus):

1. **Volume floor:** `min_events` MO voice calls per day before a SIM is scored at all.
2. **Agreement** (§5.2): `min_signals` primary signals, so no single inflated signal alerts.
3. **Protective factors** (§5.1): inbound from many distinct callers, normal data and SMS use, mobility.
4. **Persistence:** an Incident opens only after `persist_days` of the last `persist_window` days at or above `high`; a watched
   SIM (`tb-watch`) needs one day fewer. ⚠ This trades latency for precision; the number is D-BP5 and a B0 measurement.
5. **Verified allow list with expiry and owner** (§5.3), replacing the static parameter.
6. **Peer baseline:** the cohort view stops a plan whose normal usage is heavy from flagging the whole plan.
7. **Shadow mode:** a new pack version runs for `shadow_days` with the Alert Rules **disabled**, writing scores only, so
   precision is measured before anyone is paged (the shadow idea in `RT-29`).
8. **Measured precision:** typed verdicts (§6.5) feed precision, false-positive share and per-signal contribution Measures
   (`FM-30`, `FM-33`, KPIs `FM-K05`, `FM-K06`, `FM-K13`); a quarterly tuning report is a Job, not a meeting.
9. **The residual, stated:** a dialler or PBX SIM that is **not** on the allow list, with ordinary-looking mobility and some
   inbound, is indistinguishable from a quiet box **from CDRs alone**. The pack's answer is *needs verification*, not a smarter
   threshold.

## 8. Data model

### 8.1 Streams and References in

| Name | Kind | Grain and key columns | New or changed |
|---|---|---|---|
| `voice_cdr` | Stream | the `cdr` feed plus optional `imei, imsi, answered, release_cause, called_number_type` (§2.1) | new feed schema in the new template; `telco-fraud`'s `cdr` is untouched |
| `sms_cdr`, `data_session` | Stream | optional: MO SMS and data session records per SIM | new, optional; today SMS and data ride in `cdr` as `service` |
| `subscriber_profile` | Reference | `msisdn` key; `plan, dealer_id, activated_on, corporate_flag` | new (the `telco-fraud` `subscriber_events` feed is events, not a profile) |
| `cell_site` | Reference | `cell_id` key; `site_id, lat, lon` | new; `cell_id` is classified CONFIDENTIAL today |
| `number_plan` | Reference | `prefix` to `operator`, `own` flag | new; also seeds `tb-ranges` |
| `tac_class` | Reference | `tac` to `device_class` | **optional** (D-BP9) |
| `ic_cdr` | Stream | interconnect gateway CDRs: `trunk_id, presented_cli, network_cli, b_number, duration_s, start_ts` | B5 only |

### 8.2 Datasets out (Job-written)

| Dataset | Written by | Content |
|---|---|---|
| `tb_features` | `tb_features` | SIM-day signals (§4.2), `requires` flags, evidence ids |
| `tb_clusters` | `tb_clusters` | cluster rows (§4.3) |
| `risk_scores_tb_sim`, `risk_scores_tb_sim_latest` | `risk.score` (derived names, never authored) | score, `high`, factors JSON |
| `anomaly_scores_tb_sim_behaviour`, `…_latest` | `anomaly.score` | per-entity 0 to 100 with per-Feature explanation |
| `tb_findings` | `tb_findings` | one row per Incident-worthy SIM or box per day: `ENTITY`, `KIND`, `SCORE`, `PRIMARY_SIGNALS`, `EST_BYPASS_MINUTES`, `CLUSTER_KEY`; **the Dataset the Alert Rules read**, so their Measures stay `max(col)` |
| `entity_list_tb_*` | the Entity List sidecar | read-only projection of the lists (§5.3) |

Zero-row `schema-seed.parquet` seeds are needed for every Dataset an Alert Rule, Risk Score or Anomaly Model reads, because the
save gate reads the Schema and every pack Dataset is empty at apply (the `telco-fraud` and `telco-ra` pattern). The reserved
prefixes `risk_scores_` and `anomaly_scores_` mean the score Datasets cannot be authored directly; the Alert Rule reads
`…_latest` through the documented identity Dataset.

## 9. Delivery, packaging and edition placement

- **A new Space Template `telco-bypass`** (`spaces/_templates/telco-bypass/`), not an edit of `telco-fraud` (D-BP1). Reasons:
  `telco-fraud`'s counts are pinned (14 Jobs, 14 Alert Rules, 58 golden Alerts, mutation-checked); the sibling IRSF / Wangiri,
  5G / IoT and sales / churn lanes are planned in parallel and several may want that template; two lanes in one template and one
  golden is the recorded collision failure. Cost: the CDR feed schema is copied and extended. `fraud_simbox` **stays** as the
  lightweight starter; nothing is retired.
- **Kind.** A telecom-flavoured **content pack** (`product-map/solution-pack/…`, `kind: solution-pack`), listed in
  `offerings/standard.toon` `contentPacks` beside `telco-fraud`. It needs the **Scoring & Lists** (`entity-list`, `scoring`) and
  **Anomaly Detection** (`anomaly`) add-ons, both listed in `standard.toon`, so `minEdition: standard`. The Preview edition
  carries everything. ⚠ Only `basic.toon` and `standard.toon` list `contentPacks`; verify at B7 how Advanced inherits before
  claiming it. Edition vocabulary is Basic / Standard / Advanced / Preview; do not copy the older Professional / Enterprise
  names still found in some pages.
- **No new Step Processor, no new Job Type, no new component kind.** Everything is TOON config, SQL and the three shipped
  add-ons. A new route is not needed, so the openapi contract is untouched unless B0 finds one is.
- **Dashboard and runbooks** as components: a Space dashboard (suspected boxes by cell, score distribution, precision), a Widget
  per signal family, one runbook for SIM and one for box.
- **Cost of a miss:** if gate **G-SB** trips (§10, B0) the staged SQL is wrong and a feature module with a Job type is a
  separate design, **not** part of these slices.

## 10. Phased slices

Per `CLAUDE.md`: **unit tests per slice** (`-pl <module> -Dtest=A,B`, commas, never `+`); the full reactor gate only at handoff or
before a push touching shared seams. `-pl` without `-am` tests the stale sibling jar, so rebuild what the slice touched. Each
slice ends with a **mutation pass**: every clause of the new SQL gets one mutant that must turn a named assertion red, and the
probe is built so it would otherwise **succeed** (recorded traps: a one-clause mutant red for the wrong reason; a negative test
whose probe cannot succeed). Tests sit with `TelcoFraudTemplateGoldenTest` (module `inspecto`, `com.gamma.control`); confirm the
module at B1 before writing a `-pl` command.

**Gate G-SB (measured in B0, re-checked after B3; any one trips it):** (a) the SIM-day stage with window functions (`lag`, overlap,
implied speed) cannot meet the throughput budget set in B0 on the synthetic daily volume; (b) the Alert Rule `by` cardinality, `stormCap` or
Case grouping cannot express a **box** work item (§6.3); (c) a stage's SQL cannot be unit-tested in isolation; (d) a signal the closed
list of S1 to S16 cannot express is named by a prospect.

| Slice | Delivers | Done when (verifiable) | Tests (unit level) |
|---|---|---|---|
| **B0 spike** | Facts, no shipped config: (1) throughput and memory of the SIM-day stage on a synthetic 5M-row day with the `lag` / overlap / speed windows; (2) can one Alert Rule `by` take two key columns, and what does `stormCap` do at a box-per-site cardinality; (3) can a Case Rule group on a cluster key that SIM Incidents carry; (4) can one Alert Rule express score **and** `primary_signals`; (5) does an `allow` Entity List sidecar filter on `expires_at` inside a `sql.template` Job read; (6) the module that owns `TelcoFraudTemplateGoldenTest`. | A measured table in this plan (§16) with the budget that gates **G-SB(a)**; the six answers recorded; **no repository change except this document**. | Throwaway harness, not committed; numbers only. |
| **B1 signals + corpus** (**BUILT 2026-10-20, §17**) | Template skeleton `telco-bypass`; `voice_cdr` schema and Pipeline; Reference seeds; `tb_features` with S1 to S12; the corpus generator `BypassCorpus` (fixed seed) planting every archetype and look-alike of §12; zero-row seeds. | Golden asserts **exactly** the planted signal values per archetype and each look-alike's expected zero or sub-threshold column; the two host-zone run gives identical rows; missing optional columns flag, they do not fail. | `BypassCorpusTest` (deterministic; planted and benign sets disjoint), `BypassFeatureGoldenTest` (Jobs run, Datasets equal the planted set), a clause-by-clause mutation pass, a time-zone test. |
| **B2 score + lists + alert** (**BUILT 2026-10-20, §18**) | `tb_sim` Risk Score; `tb-watch`, `tb-allow`, `tb-block`, `tb-ranges`; `tb_findings` with `primary_signals` and persistence; Alert Rule `tb_sim_high`; the anti-join with `tb-allow`; the shadow switch. | Every planted SIM alerts exactly once with its planted top factors; every look-alike is silent **for the planted reason** (the factor that suppresses it is asserted); an allow entry suppresses and its **expiry** un-suppresses; a changed allowed line raises the lower Alert; the score recomputes from stored factors; `-shadow` writes scores and raises nothing. | `BypassScoreTest` (each factor, protective sign, cap), `TelcoBypassGoldenTest` (Alerts equal planted set), `BypassAllowListTest` (expiry, four-eyes hold), mutation pass. |
| **B3 cluster + Incident + Case** (**BUILT 2026-10-20, §19**) | `tb_clusters` (imei, cell, batch); Alert Rule `tb_box_suspected`; Case grouping per B0 answer; runbooks; Action Request template for barring; Link Analysis projection config. | The corpus's three boxes (one spoofing its IMEI, one on one IMEI, one slow-roll across many SIMs) each yield **one** box Incident and its member SIM Alerts; the IMEI-spoofing box is still caught by the `cell` key; a second night **updates** the Incident; a benign dense building (many SIMs, one cell, normal mix) is silent. | extend the golden; `BypassClusterTest` (each key, fallback when `imei` absent); a two-run stability test. |
| **B4 anomaly layer** | `tb_sim_behaviour` Anomaly Model with plan cohort, the `anomaly.score` Job, the Risk Score factor from its `_latest`; `exclusionList`. | A planted **novel** archetype (a pattern no fixed signal threshold covers) is flagged by the anomaly factor alone and **below** `high` in the score; a heavy-user plan is not flagged against its own cohort; the anomaly factor never alerts alone (D-BP4). | `BypassAnomalyTest` (golden extension), `AnomalyKindValidator` gate on the model (peers column not classified). |
| **B5 interconnect-side (demand-gated, D-BP7)** | `ic_cdr` feed; trunk and CLI signals (national CLI on an international trunk, CLI cardinality per trunk, sequential CLIs, CLI equal to B-number); Alert Rule `by: trunk_id`; inbound-international versus own-network-terminated Measure, the join point with the partner-settlement lane. | Planted trunk archetypes recovered; the boundary with the IRSF / Wangiri sibling plan recorded (no CLI column, signal or Alert Rule defined in both). | golden extension; `TrunkCliTest`. |
| **B6 feedback + KPIs** | Typed verdict to precision, false-positive share and per-signal contribution Measures; the quarterly tuning report Job; tuning candidates (suggested threshold changes; **never auto-applied**). | A replayed verdict history on the corpus produces the planted precision; a tuning suggestion is a diff for a human, not a write. | `BypassKpiTest`; mutation pass. |
| **B7 package** | Dashboard and Widgets; template and offering entries; the `product-map` item; docs distilled into OKF and the matrix; the CI guards. | The template gallery marks `creatable` per edition; the guards pass **locally** (vocabulary, doc-links, doc-counts, openapi if a route changed, product-map check) before any push, because a green reactor is not green CI. | golden; the guard runs. |

Slices B1 → B2 → B3 are sequential (each extends the corpus and the golden). B4 can start beside B3 once `BypassCorpus` is split
per archetype (disjoint files). B5 and B6 are independent of each other after B3. Nothing edits `telco-fraud`, so the lane cannot collide with a sibling.

## 11. Traceability: RFP lines, gaps and workstreams

IDs are those of the MVNO RFP working set (compliance matrix, gap register, build plan, content catalogue), a git-excluded
local folder: they are cited here as identifiers only. **No client material or real data is in this document.**

### 11.1 Per slice

| Slice | Compliance-matrix lines it satisfies or moves | Gap IDs | Workstream IDs |
|---|---|---|---|
| B1 | `FM-02` (SIM-box and bypass part of traffic fraud, typology `T-05`, catalogue `FM-R03`), `FM-04` (device anomalies, abnormal location change, impossible speed, via S6 and S13), `FM-16` (batch scoring), `INT-01` (CDR feeds; the extended `voice_cdr` contract) | `G-07` (velocity and baseline in SQL), `G-08` (typology content), `G-26` (CDR format validation, depends on samples) | `WS-14` (SQL form), `WS-15`, `WS-03` |
| B2 | `FM-09` (risk scoring at SIM level), `FM-15` (thresholds, profiles, watch, allow and block lists, peer groups), `FM-22` (explainable scores), `FM-20` (prioritisation by risk and, with `est_bypass_minutes`, exposure), `RT-06` (lists, partial: mastered here, enforced by a partner) | `G-05`, `G-13`, `G-42` | `WS-12`, `WS-22`, `WS-18`, `WS-13` |
| B3 | `FM-08` (organised networks: concentration, shared identities, repeated device use), `FM-07` (relationships: already compliant, used), `FM-19` (correlation), `FM-23` (alert handling), `FM-24` (case management, already compliant, used), `FM-21` (control requests, approval-gated), `RC-04` (RTSC control coverage row, "detection only plus list push"), `RT-26` (scores and lists published to the partner) | `G-42`, `G-15`, `G-14` | `WS-18`, `WS-24`, `WS-23`, `WS-12` |
| B4 | `FM-16`, `FM-15` (peer groups, behavioural models: statistical, not ML) | `G-07` | `WS-14` |
| B5 | `FM-02` (abnormal international traffic, `T-03` / `T-06` boundary), `H` is a sibling lane's | `G-08` | `WS-15` |
| B6 | `FM-27` (typed false-positive classification, outcome workflow), `FM-30` and `FM-31` (precision, false positives, coverage, `FM-K05`, `FM-K06`, `FM-K11`), `FM-33` (detector and data performance, `FM-K13`, `FM-K14`), `FM-29` (suspected exposure, `FM-K02`, once the ledger exists) | `G-03`, `G-11` | `WS-10`, `WS-20` |
| B7 | `FM-28` (dashboards with drill-down from exposure to entity and event), `FM-34` (disclosure: the pack is **Customized** classification, **Configurable** thresholds), `INT-09` and `INT-11` (data quality and feed health on `voice_cdr`) | `G-11` | `WS-20`, `WS-15` |

⚠ Matrix positions after this plan: `FM-02` stays **Partially compliant** (it names IRSF, Wangiri, callback, premium-rate and the
rest as well; SIM-box and bypass is one of nine typologies in that line, and the other lanes own the rest). The RFP working set's
`after_build` and position columns are the operator's to change; nothing here edits them.

### 11.2 RFP lines this plan can **not** satisfy from CDRs alone

| Line | What it asks | Why CDRs cannot close it | What closes it |
|---|---|---|---|
| `RT-03`, `RT-04`, `RT-05`, `RT-07` | real-time validation and **enforcement** of calls for SIM-box and bypass; allow, reject, release, redirect | a CDR exists **after** the call; the pack is post-event (daily, hourly at best) and never decides a live call | the RTSC partner (`G-01`, `WS-30`, bid item `B-01`); this plan supplies the list, the score and the Action Request |
| `FM-17`, `FM-01` (real-time part) | real-time scoring and detection | CDR mediation latency plus the poll cycle set a floor of minutes | `WS-21` (seconds, not milliseconds); inline by the partner |
| `RT-21` … `RT-24`, `T-08`, `FM-03` (spoofing) | signalling security, spoofing detection | needs signalling (SS7, MAP, Diameter, SIP) or firewall logs; matrix row **F** | partner or probe; out of scope |
| none (matrix row **G**) | test-call generation to prove a bypass | a call must be placed and observed; no CDR signal replaces it | a test-call service or hardware; out of scope. **No compliance-matrix line asks for test calls for bypass**; the nearest, `RA-11` (launch validation, `G-34`), is not about bypass |
| `FM-18` | machine-learning scoring | the anomaly layer is statistical (median and MAD), by decision; no trained model | roadmap `G-21`, `WS-31` |
| `FM-04` (device-anomaly part) | device anomalies | only when the feed carries IMEI and IMSI; an MVNO on an MVNE may not receive them (`G-38`) | the MVNE exposing the fields; until then the `cell` and `batch` keys (§4.3) |
| `FM-29`, `FM-K01` … `FM-K04` (confirmed loss, prevented loss, recovered) | loss figures | a CDR gives **minutes**; a monetary loss needs a rate and a confirmed verdict | `WS-10` ledger and D-BP8; impact stays manual |

## 12. Synthetic fixture approach

`BypassCorpus` in the style of `TelcoFraudCorpus` and the re-rating plan's `RerateCorpus`: **fixed seed, synthetic ids only**,
fictional number ranges (the corpus never uses a real prefix, IMEI, IMSI or cell), planted and benign sets **disjoint**, and a
generator split by archetype into disjoint files so lanes B3 and B4 do not collide. No client material, no operator file and no
real call is ever read or copied; a real CDR sample, if one arrives, is calibration input kept outside the repository.

| Archetype | Planted as | Expected |
|---|---|---|
| **P1** classic box | 60 SIMs, one cell, one IMEI, 80+ distinct targets/day, short uniform calls, no inbound, no SMS or data, round-the-clock | every SIM alerts; one box Incident |
| **P2** slow-roll box | 200 SIMs at 12 calls/day each, rotated through one IMEI (`imsi_churn_7d` high) | no SIM alerts alone; **box** Incident via `imei` and `cell` |
| **P3** IMEI-spoofing box | as P1, every call under a different IMEI | caught by the `cell` key (§4.3) |
| **P4** sequential dialler | one SIM walking a number list in order, moderate volume | S7 plus agreement, below the CRITICAL band |
| **P5** cloned SIM | one SIM on two cells 800 km apart within the hour, overlapping calls | S6 and S10 flagged; **not** a box (kind recorded) |
| **P6** novel shape | a pattern no S1 to S12 threshold covers | anomaly factor only, below `high`, never alone |
| **L1** call centre | high outbound, **inbound present**, many callers | silent (protective factor asserted) |
| **L2** PBX / dialler, registered | box-like, on `tb-allow` | silent; **entry expiry** un-suppresses |
| **L3** PBX / dialler, unregistered | box-like, with ordinary mobility | **alerts**: the documented irreducible case, asserted as *needs verification*, not hidden |
| **L4** alarm or M2M line | few B-numbers, one cell, no inbound | silent (`b_diversity` floor) |
| **L5** courier | many targets, moves across cells | silent |
| **L6** new heavy user | high volume, tenure under `tenure_days` | silent (insufficient history, no anomaly claim) |
| **L7** dense building | 80 SIMs, one cell, normal service mix | silent (protective mix) |
| **L8** visiting roamer | `roaming = Y` | excluded from candidates |
| Each primary signal | one SIM **at** the threshold and one **just above** | below / above, never both |

## 13. Risks

1. **CDR contract unknown** (§2.1). The largest risk; the answer decides whether device signals exist at all. The plan degrades
   per signal and always has the `cell` and `batch` keys.
2. **The irreducible false positive** (§7.9). A CDR-only detector cannot tell a quiet dialler from a quiet box. The pack's honest
   output is *consistent with*, plus a verification step, and it says so on the Alert.
3. **Synthetic corpus only.** A real operator's patterns are richer and boxes adapt (slower dialling, human-like gaps). The signal list
   is closed (S1 to S16); a missing one is gate **G-SB(d)**, and the pack says so rather than guessing.
4. **Volume unknown.** Nothing is measured above the small golden corpus; B0 measures before any promise (the reconciliation lane's
   rule: a 10M-row claim is a hypothesis until measured).
5. **Overlapping signals** inflate a sum; mitigated by agreement (§5.2) and protective factors, and pinned by the corpus.
6. **Storm and cardinality.** One Alert per SIM and per box; `stormCap` must stay above `retain` × daily offenders (the recorded
   scaling note); a large box (hundreds of SIMs) is the stress case B0 measures.
7. **Time zones and day boundaries** (§4.2).
8. **Privacy.** MSISDN and IMSI are classified columns; the cohort column of the Anomaly Model must not be one (the gate refuses);
   evidence rows are masked per the existing classification; cell location is CONFIDENTIAL.
9. **Lane collision.** The sibling plans meet this one only at `ic_cdr` (B5) and at no shared file; any other overlap is a collision.
   Edits are confined to this plan, one `INDEX.md` row and one `BACKLOG.md` row.
10. **Vocabulary.** Use *Pipeline*, *Dataset*, *Incident*, *Collector*, *Expectation* / *Alert Rule* / *Decision Rule*, *Measure*,
    *Reference* / *Stream*, *Entity List*; the bid plan calls the lists by another name that the glossary bans, so the word
    **Entity List** is used throughout (`docs/GLOSSARY.md` §0).

## 14. Decisions for the operator

| ID | Decision | Recommendation |
|---|---|---|
| **D-BP1** | Home: new template `telco-bypass`, or extend `telco-fraud`? | **ANSWERED 2026-10-20 (operator: go with the recommendations): new template `telco-bypass`;** `fraud_simbox` stays in `telco-fraud` as the starter and `telco-fraud`'s pinned counts stay untouched. Built in B1. |
| **D-BP2** | The CDR contract: which extra columns can the operator (or the MVNE) supply: IMEI, IMSI, attempts or release cause, cell site? | **ANSWERED 2026-10-20: accept optional columns with per-signal degradation.** IMEI, IMSI, release cause or attempts and the cell site are optional; a missing optional column is flagged per signal (`missing`), it does not fail. S13 / S14 and the cluster `imei` key are deferred until after B3. Built in B1 for S1 to S12. |
| **D-BP3** | Work item: per SIM, per box, or both? | **ANSWERED 2026-10-20: the box Incident (`by: cluster_key`) is the Case anchor;** SIM rows are evidence in `tb_findings` (§16.4 answers 2 and 3). Decided now, built in B2 / B3. |
| **D-BP4** | Scoring: Risk Score primary with the Anomaly Model as one factor, or the anomaly layer as its own alert? | **ANSWERED 2026-10-20: Risk Score primary**; the anomaly layer never alerts alone in v1; no ML (`G-21`). |
| **D-BP5** | Latency versus precision: persistence `persist_days` and run frequency (daily only, or an hourly window)? | **ANSWERED 2026-10-20: daily with persistence** first; hourly is a later slice measured against `WS-21`. |
| **D-BP6** | Action: automatic barring, an approval-gated Action Request, or a manual task? | **ANSWERED 2026-10-20: approval-gated Action Request** (or manual until a provisioning API exists); never automatic; only `watch` entries (≤ 24 h) auto-apply. |
| **D-BP7** | Build the interconnect-side slice B5 now, or only when a prospect supplies gateway CDRs? | **ANSWERED 2026-10-20: B5 demand-gated**; boundary with the IRSF / Wangiri lane agreed first. |
| **D-BP8** | Exposure: minutes only, or a monetary estimate from a termination-rate Reference? | **ANSWERED 2026-10-20: minutes**, with an optional rate parameter; impact stays manual. |
| **D-BP9** | A TAC (device class) Reference: the operator licenses one, or the pack ships without it? | **ANSWERED 2026-10-20: optional, never bundled**; absent means S14 is omitted. |
| **D-BP10** | Commercial and matrix: `billable`, `orderCode` for the new pack; matrix cell E after B3 (**P** to **Y**, with the note "CDR-based")? | **OPEN, deferred: the operator's call.** Until then B1 lists the template in `offerings/standard.toon` `contentPacks` and as Product Map Item `solution-pack.telco-bypass` with `billable: false`, `status: preview` and no `orderCode`; the matrix is updated only when a slice ships. |

## 15. Backlog and docs touched

- `BACKLOG.md`: the row **`TELCO-BYPASS-SIMBOX-1`** keeps its place and priority and its **text** now points at this plan (CDR-based
  slices B0 to B7; non-CDR evidence stays out of scope). No row is added or removed, so no count marker changes.
- `INDEX.md`: this plan's row in the in-flight table.
- On ship (B7): distil as-built facts into [`okf/product/module/config/plan-spaces.md`](../../okf/product/module/config/plan-spaces.md) (a subsection beside §3.5),
  add a solution page under `stakeholders/solutions/`, update the matrix row E, then `git mv` this plan to
  `archived-documents/plans-archive/`.

## References

- Matrix: [`okf/product/layer/product/brochure-competitive-matrix.md`](../../okf/product/layer/product/brochure-competitive-matrix.md) rows E, F, G, H.
- Existing pack: `spaces/_templates/telco-fraud/` (`fraud_simbox` Job, Alert Rule, runbook, `tf_subscriber_usage` Anomaly Model,
  Case Rule); [`okf/product/solution-pack/telco-fraud/architecture.md`](../../okf/product/solution-pack/telco-fraud/architecture.md).
- Add-ons: [`okf/product/module/anomaly/spec-anomaly-scores.md`](../../okf/product/module/anomaly/spec-anomaly-scores.md),
  [`risk-scores.md`](../../okf/product/module/scoring/spec-risk-scores.md), [`entity-lists.md`](../../okf/product/module/entity-list/spec.md),
  [`action-requests.md`](../../okf/product/module/action-requests/spec.md).
- Sibling plans (house style): [`rerating-tariff-validation-plan.md`](rerating-tariff-validation-plan.md),
  [`partner-settlement-wholesale-plan.md`](partner-settlement-wholesale-plan.md).
- Canonical vocabulary: [`GLOSSARY.md`](../../GLOSSARY.md) (the single source of truth).

## 16. Measurements (slice B0 results, 2026-10-20)

B0 was a throwaway spike: a harness and a synthetic CDR generator kept **outside the repository**; the only repository change is this
section. Nothing below is a vendor or industry fact; every figure is one synthetic day on one developer box.

**Box.** Intel64 Family 6 Model 158 (12 logical cores), 31.7 GiB RAM, Windows 11, JDK 27, local SSD.
**DuckDB.** `duckdb_jdbc` 1.5.6.0 (the repo pin, `pom.xml` `duckdb.version`), reported `v1.5.6`.
**Engine settings mirrored.** An in-memory database, Parquet views over the day, `SET allowed_directories` to the data root,
`enable_external_access=false` and `lock_configuration=true` (what `SqlTemplateJob` does through `DuckDbUtil.openInMemory`), the
statement run as `CREATE TABLE … AS <sql>` then `COPY … TO parquet`. `memory_limit` = the engine default
`max(1 GiB, 40 % of RAM ÷ 4)` = **3246 MiB** on this host (`DuckDbUtil.defaultMemoryLimit`); `threads` = the DuckDB default
(12), because `SqlTemplateJob` sets none.
**⚠ Which path was measured: standalone DuckDB with the engine's settings, NOT a `sql.template` Job run through a throwaway Space and
backend.** Standing up that Space was not cheap, so it was not attempted. What was run through the real code: the statement text passes
`SqlGuard.check` (the guard `SqlTemplateJob` applies) with **0 findings**. Not measured: `IncrementalSink.replace` (the partition
write the Job uses with `lookback`), Decision Rule application, Job queueing, concurrent Jobs.

### 16.1 Synthetic day and SQL

A generator (DuckDB SQL, deterministic hashes) writes a `voice_cdr` day of N rows: ~400 000 ordinary SIMs (50 % MO, 60 % voice,
25 % SMS, 15 % data, 2 % roaming, three cells each) plus planted populations scaled with N (at 5M: **20 boxes × 60 SIMs = 1 200 box
SIMs** with 250 short (20 to 60 s) MO calls each, round the clock, one cell, one IMEI, B-numbers walking +1 with 10 % jumps,
on-net targets, no inbound; **1 000 call-centre SIMs** with 50 % inbound; **500 couriers** moving across 40 cells). The SIM-day
statement (`tb_features`-style) computes S1, S2, S4, S5, S6 (haversine implied speed with a `cell_site` join), S7 (`lag` plus
prefix-and-step test), S8, S9 (hourly peak, distinct B per hour, gap CV), S10 (running-max overlap window), S11 (range join to
`number_plan`, an inequality join), S12 (`tenure_days`) and 3 evidence ids per SIM. It uses four window functions over
`PARTITION BY a_number ORDER BY start_ts`, one inequality join and six aggregation CTEs. The 5M day: 5 000 000 rows, 402 698 distinct SIMs, 1 793 270 MO voice calls, 258 150 output rows
(SIMs with at least 3 MO calls).

**Sanity (the signals separate the planted from the look-alikes).** A plain predicate (≥ 200 calls, inbound ratio < 0.05, B diversity
> 0.9, short-call share > 0.9, voice-only share > 0.99) flagged **1 200 of 1 200** box SIMs, **0 of 1 000** call-centre SIMs and **0 of 500**
couriers. Box SIMs: `seq_dial_share` 0.79, `max_calls_1h` 11, night share 0.21, on-net share 1.0.
⚠ The synthetic cell coordinates are random, so S6 `max_implied_kmh` values (e.g. 118 682 for couriers) are meaningless; B0 measures the
cost of the speed window, not its signal quality.

### 16.2 Results (SIM-day stage, one run each unless stated)

| Case | Rows in | Output rows | Wall (CTAS + COPY) | Peak DuckDB memory | Peak process commit | Spill |
|---|---|---|---|---|---|---|
| 1M, engine default (3246 MiB, 12 threads) | 1 000 000 | 12 152 | 1.8 s | 593 MiB | 840 MiB | 0 |
| **5M, engine default**, three runs | 5 000 000 | 258 150 | **9.8 s, 12.6 s, 14.6 s** (median 12.6 s) | 1 810 to 1 830 MiB | 2 129 to 2 159 MiB | 0 |
| 5M, 4 threads | 5 000 000 | 258 150 | 9.1 s | 1 821 MiB | 2 059 MiB | 0 |
| 5M, 1 thread | 5 000 000 | 258 150 | 24.3 s | 674 MiB | 917 MiB | 0 |
| 5M, `memory_limit` 1 GiB | 5 000 000 | 258 150 | 23.7 s | 1 024 MiB (at cap) | 1 356 MiB | 370 MiB |
| 5M, `memory_limit` 512 MiB | 5 000 000 | none | **fails: Out of Memory Error** (511.6 of 512 MiB) | at cap | n/a | n/a |
| **10M, engine default** | 10 000 000 | 389 205 | 22.3 s | **3 145 MiB (97 % of the 3 246 MiB cap)** | 3 448 MiB | 0 |

Derived: about 380 bytes of peak DuckDB memory per input row at 5M (1 810 MiB) and about 330 at 10M; throughput 0.4 to 0.5 M rows/s on
12 threads, 0.2 M rows/s on one. Time is steady across run repetition within ±20 %; 4 threads was as fast as 12 (cause not investigated). Peak memory is polled every 50 ms from a second connection
(`duckdb_memory()`), so it is a floor on the true peak; process commit is the JVM's committed virtual memory.

### 16.3 G-SB(a) budget recommendation

**Not tripped: the staged SQL with `lag`, overlap and implied-speed windows is fast enough on a 5M-row day.** Recommended budget for
the gate (to be re-checked after B3, when the cluster stage is added):

- **Time:** the SIM-day stage completes a **5M-row day in ≤ 60 s** on a 4-core allowance at the default memory cap (measured median
  12.6 s at 12 cores and 9.1 s at 4 threads, so the budget carries about 5× headroom for a slower host, concurrent Jobs and the
  two-day `lookback` read); ≤ 120 s fails the gate.
- **Memory is the binding limit, not time.** Peak must stay **≤ 2.5 GiB at 5M rows with zero spill**, which the default cap
  (3 246 MiB on a 32 GiB host) meets (1.8 GiB). At **10M rows the stage sits at 97 % of the default cap**, one step from the 1 GiB
  behaviour (2× slower, spills) and the 512 MiB cliff (fails), so the pack must document: above about **7 to 8M rows a day on the
  default cap** (a linear extrapolation from two points, not a measurement), raise `processing.duckdb.memory_limit` to at least 4 GiB;
  on an 8 GiB host the default is the 1 GiB floor and a 5M day then costs about 24 s with spill.
- Volume unknown stays unknown: B0 fixes the cost per row, not the real operator's volume (risk 13.4).

### 16.4 The six answers

1. **Throughput and memory:** §16.2 and §16.3. Gate G-SB(a) holds. Unmeasured: the cluster stage (`tb_clusters`: S13, S14, S15, the
   `new_batch_share` part of S12), S3 (no `answered` column was used), the Anomaly Model and Risk Score Jobs, hour-grain buckets,
   a real operator's SIM cardinality above 405 000 SIMs a day (group-by memory grows with it), and the two-host-zone test (the
   statement uses naive `hour(start_ts)`, so zone sensitivity is not exercised; the host zone here was Asia/Calcutta).

2. **Alert Rule `by` with two keys: yes; `stormCap` at box cardinality: it caps individual Incidents, so size it.**
   - `AlertRule.by` is a list (a comma-separated scalar is accepted), any length, each a plain column present in the Dataset
     Schema, and `stormCap` defaults to 100. Two keys are already shipped: `by: [model, entity_key]` in the Risk Score and Anomaly Score
     Alert Rules (`RiskScoreAlertTest`, `AnomalyScoreAlertTest`). Proven by running `PerEntityAlertTest` (module
     `platform/inspecto-engine`, 15 tests, 0 failures, 8.1 s), which exercises `by [msisdn, region]` with 40 and 64 keys.
   - ⚠ **Plan §6.1 is stale:** since ALERT-PER-RULE-1 (2026-10-14) there is **one Alert per Alert Rule** (a count of breached keys) and
     **one Incident per key** (at CRITICAL or ERROR; dedupe `<rule>|<keyId>`). §6.1's "each offender or box gets its own Alert" must read
     "own Incident".
   - `stormCap` mechanics: the probe reads at most `stormCap` breaching keys and a total (`COUNT(*) OVER ()`). When the total exceeds the
     cap the rule keeps its one Alert (marked storm) and opens **one storm Incident** (`<rule>|*`); **no per-key Incident opens and none
     heals** until the count is back under the cap (`PerEntityAlertTest` case at line 225). Persisted open-key digests are truncated at
     `MAX_DIGESTS` = 1 000.
   - At box cardinality: the box-level Alert Rule `by: cluster_key` stays well under the default (20 boxes a day in the synthetic day);
     the **SIM-level** Alert Rule would breach it (1 200 box SIMs against the default 100 and the pack's 500), producing one storm Incident
     and no SIM Incidents. Either raise `stormCap` above the expected daily offenders (and above `retain` × them, §13.6, because the probe
     groups the **whole Dataset**, every retained day, unless the Alert Rule reads a `…_latest` identity Dataset), keeping it ≤ 1 000, or
     let the box Incident be the only Incident and keep SIM rows as evidence in `tb_findings` (recommended; see answer 3).
   - Also: one Measure (`count` or `agg(field)`), one comparator and one threshold per Alert Rule.

3. **Case Rule grouping on a cluster key: yes, through the Alert Rule's `by`, not through a free attribute.** `CaseRule.groupBy` is one plain
   column name and reads the Incident attribute `key.<groupBy>`; an Alert Rule stamps `key.<col>` for **every** `by` column
   (`AlertService.openKeyIncident`). So a SIM Alert Rule with `by: [entity_key, cluster_key]` and a Case Rule with `groupBy: cluster_key` yield one
   Case per box with the member SIM Incidents attached (a Case holds only a digest of the key). Determined by code read
   (`CaseRule`, `CaseOperations.evaluateCaseRule`) and the single-key `CaseRuleGroupByTest`; **not run end to end**. Traps for B3:
   (i) a NULL `cluster_key` is stamped as the **string `NULL`**, which is non-blank, so every unclustered SIM would join one Case: the
   Alert Dataset must carry a non-null cluster key or exclude those rows; (ii) the Incident dedupe is the whole key tuple, so a SIM that
   changes box tomorrow opens a new Incident; (iii) above `stormCap` there are no SIM Incidents to group; (iv) the Case Rule `threshold`
   and `windowMinutes` apply per group. The alternative that needs no Case Rule: the box Incident (`by: cluster_key`) is the Case anchor
   and the SIM rows are evidence. **G-SB(b) is not tripped**, provided `stormCap` is sized as in answer 2.

4. **Score and `primary_signals` in one Alert Rule: no.** An Alert Rule watches one Measure with one comparator and one threshold, and
   `alert.when` is refused on a Dataset Measure Alert Rule (`AlertRule` constructor). Two options: (a) the Job writes only rows that pass
   both conditions into `tb_findings` (as §8.2 already says) and the Alert Rule is `max(score) gt 0` / `max(alert_score) gt <high>`;
   (b) a Dataset `calculated: [{name: alert_score, expr: CASE WHEN score >= … AND primary_signals >= … THEN score ELSE 0 END}]`
   (row-wise, re-evaluated per read; `DatasetRelation`, DAT-5), which keeps the thresholds visible in config but duplicates the `high`
   constant. **Recommend (a):** the thresholds are Job parameters the golden test pins. (b) was read in code, not run.

5. **`allow` Entity List sidecar filtering on `expires_at` inside a Job read: yes.** The sidecar column is `expires_at TIMESTAMP`
   (`EntityListSidecar` DDL) and keeps expired rows. A tiny proof, standalone DuckDB sealed like the Job (view over the
   `entity_list_<id>/**/*.parquet` glob, `enable_external_access=false`, locked), with three `allow` entries (permanent, future expiry,
   expired) and four SIMs: the anti-join `LEFT JOIN (SELECT entry FROM entity_list_… WHERE purpose='allow' AND match='key' AND
   (expires_at IS NULL OR expires_at > <ts>)) … WHERE entry IS NULL` returned the **expired** SIM and the unlisted one (the permanent and
   future-dated entries suppressed), with a substituted timestamp and with `now()`; both statements pass `SqlGuard.check` with 0
   findings. **Not proven through a real Job run:** the Job's store list reads `<dataDir>/<store>/**/*.parquet`, which is where the sidecar
   lives by code read, but no precedent in any template uses it and the Consignment resolution on a non-Consignment folder was not
   exercised. Gotchas for B2: the store name is `entity_list_<id>`, so a list id `tb-allow` needs the quoted view name
   `"entity_list_tb-allow"` (plan §5.3 spells it `entity_list_tb_allow`; prefer underscore ids, charset to be checked at B2); the
   sidecar exists only after the first write; expiry has no write, so the filter is the only enforcement; prefer a run-day parameter to
   `now()` so a backfill is reproducible.

6. **Owner of `TelcoFraudTemplateGoldenTest`:** module **`inspecto`**, file
   `inspecto/src/test/java/com/gamma/control/TelcoFraudTemplateGoldenTest.java` (package `com.gamma.control`). B1's golden test lands there:
   `-pl inspecto -Dtest=BypassFeatureGoldenTest`.

### 16.5 Plan numbers: confirmed, contradicted, unmeasured

| Plan statement | Status |
|---|---|
| S1 to S12 are expressible in one `sql.template` statement with `lag`, overlap, speed and prefix-range joins (§3, §4.2) | **Confirmed** (ran; `SqlGuard` 0 findings) except S3 (no `answered` column used) and the `new_batch_share` half of S12 |
| A 5M-row synthetic daily volume is feasible (§10 G-SB(a), "budget set in B0") | **Confirmed**: median 12.6 s, 1.8 GiB (§16.2); budget in §16.3 |
| Daily volume itself (§13.4) | **Unmeasured** (no operator volume; 10M sits at 97 % of the default memory cap) |
| `by` can carry the keys the stage needs (§6.1, §6.3, D-BP3) | **Confirmed** for two keys; **contradicted** wording: "own Alert" (it is one Alert per Alert Rule, an Incident per key) |
| `stormCap` must stay above `retain` × daily offenders (§13.6) | **Confirmed**, and sharpened: the probe groups the whole Dataset; digests persist at most 1 000 |
| One Alert Rule can express score and `primary_signals` (§5.2) | **Contradicted**: needs a Job-written or calculated column |
| Case grouping on a cluster key (§6.3) | **Confirmed** via `by: [entity_key, cluster_key]` + `groupBy: cluster_key`; the NULL-string trap is new |
| The allow sidecar filters on expiry in a Job read (§5.3) | **Confirmed** (standalone proof); not via a real Job run |
| Test module for `TelcoFraudTemplateGoldenTest` (§10) | **Confirmed**: `inspecto` |
| Cluster stage cost (S13, S14, S15), Anomaly Model, Risk Score Jobs, `persist_days` (D-BP5), hour-grain Anomaly refusal, two-host-zone behaviour, 58 / 14 / 14 pack counts | **Unmeasured** in B0 |

### 16.6 Sequencing recommendation

No G-SB clause trips, so **B1 can start now**. Size `stormCap` and the SIM versus box Incident choice at B2 and B3 using answers 2 and 3
(box Incident as the work item is the safer default). D-BP2 (the CDR contract: IMEI, IMSI, attempts, cell site) does **not** block B1,
because B1 degrades per signal; it blocks the device signals (S13, S14) and the cluster stage's `imei` key, so ask it in parallel and
re-measure the cluster stage after B3. Record the memory note of §16.3 in the pack admin guide at B7.

## 17. B1 as built (2026-10-20)

Commits on `lane/bypass-b1`: `cf5dda252` (template skeleton, feeds, seeds, runbook skeleton, offering and Product Map Item),
`add360e45` (the `tb_features` Job and the golden). Hub: [`okf/product/solution-pack/telco-bypass/`](../../okf/product/solution-pack/telco-bypass/index.md).

**Delivered.** The `telco-bypass` Space Template: four Pipelines (`voice_cdr` with the optional `answered`, `release_cause`, `imei`,
`imsi`; the Reference feeds `cell_site`, `number_plan`, `subscriber_profile`), their Datasets and zero-row seeds, one daily
`sql.template` Job `tb_features` (S1 to S12, `box_like`, `missing`, `has_imei`, `has_imsi`, `evidence_ids`; every threshold a Job
parameter), a runbook skeleton and the inert SLA examples every template carries. Test side, in module `inspecto` beside
`TelcoFraudTemplateGoldenTest`: `BypassCorpus` (seed 20261020, 18,614 CDR rows, 613 SIMs), `BypassCorpusTest` (4 tests) and
`BypassFeatureGoldenTest` (8 tests).

**What the golden pins.** (1) Every signal of every one of the 211 scored SIMs equals an independent plain-Java oracle to 1e-9.
(2) `box_like` is exactly the planted set: a 60-SIM box (P1), a 20-SIM IMEI-spoofing box (P3) and one SIM just inside each of the
five clauses plus the volume floor (86 SIMs), and the pinned ambiguous pair (L2 registered PBX, L3 unregistered dialler: 4 SIMs,
identical to a quiet box from CDRs alone, §7.9); every other SIM is silent, including the slow-roll box under the floor (P2), the
sequential dialler (P4, P4b), the cloned SIM (P5, speed above 1,000 km/h, 2 overlapping calls), the novel shape (P6), the call
centre (L1), the alarm line (L4), couriers (L5), the new heavy user (L6), the dense building (L7), the roamer (L8, no row) and the
SIM exactly AT each clause's threshold. (3) A feed with blank, absent or release-cause-only optional columns, and a Space with no
Reference loaded, still runs and names the omitted signals. (4) The statement gives the same rows under two host session zones, and
`local_zone` moves the hour signals. (5) A same-window re-run duplicates nothing and the next window keeps the earlier one.

**Deviations from the plan, and traps found.**

- `new_batch_share` is the share of the dealer-and-activation-day batch that is `box_like` today; "consecutive MSISDN" waits for the
  cluster stage's `batch` key. S13 and S14 are not computed (D-BP2); `has_imei` / `has_imsi` record presence only.
- ⚠ **Shared CTE materialisation.** A CTE read three times is materialised with every column. `WITH w AS NOT MATERIALIZED (...)` took
  the 5M-row peak from about 2.8 to 1.8 GiB; a per-SIM `list(record_id ORDER BY ...)` for the evidence ids cost four times the run
  time and is now `arg_min(record_id, (duration_s, record_id), 3)`. The B0 harness's 12.6 s and 1.8 GiB were measured without the MT
  scan and the evidence ids; with them and without these two changes the statement took 18 to 23 s at 2.9 GiB.
- ⚠ `avg(CASE WHEN x THEN 1.0 ELSE 0.0 END)` reads a NULL `x` as 0, so the answer rate and the own-network share would be 0, not
  missing, when their input is absent; both are written `CASE WHEN x IS NULL THEN NULL ...`. The golden's blank-column case found it.
- A CSV with fewer fields than the schema is quarantined as `field_mismatch` unless the Pipeline sets `csv_settings.null_padding:
  true`; `voice_cdr` does, so "optional" can mean absent from the file.
- Float aggregates (the entropy sum, the gap deviation) differ in the last digit with thread scheduling, whatever the zone: the golden
  compares to 1e-9, not bit for bit. Window ties are broken by `record_id` so the order of equal timestamps is stable.
- The B1 predicate is `box_like`, five strict clauses (the B0 sanity predicate plus a static-cell clause); the plan's `primary_signals`
  agreement, protective factors and persistence are B2.
- Plan §6.1 still says each offender gets its own Alert; since ALERT-PER-RULE-1 it is one Alert per Alert Rule and an Incident per
  key (§16.4 answer 2).

**Measurements (synthetic day, 12-core box, default 3,246 MiB cap, `duckdb_jdbc` 1.5.6.0).** Standalone with the engine's settings:
1M rows 0.68 GiB; 5M rows 4.6 s, **1.78 GiB**, no spill; 7.5M rows 7.9 s, 2.47 GiB; 10M rows 9.9 s, **3.14 GiB (97 % of the cap)**;
5M rows under a 1 GiB cap 12.1 s with 394 MiB spilled; 4 threads at 5M 6.4 s, 1.90 GiB. The **real Job through `JobService`** at 5M
rows (with `IncrementalSink`, the Decision Rule pass and the Parquet write; 400,000 SIMs, 2,700 scored, 1,200 `box_like`): 6.8 s,
process commit 2.37 GiB (JVM plus DuckDB). That closes the B0 caveat for this stage. **Gate G-SB(a) holds**: a 5M-row day in 60 s on
4 cores, peak 2.5 GiB, no spill. The 4 GiB advice above about 8M rows a day is now interpolated between measured points
(7.5M, 10M) and is in the runbook and the admin guide. Not measured: the cluster stage, the Risk Score and Anomaly Model Jobs, more
than 405,000 SIMs a day, a real operator's volume or signal quality (the synthetic cell coordinates are a grid, the call shapes are
planted).

**Mutation pass.** Hand-built mutants, each of which turned a named assertion red: every clause's threshold and its `>` to `>=`
(or `<` to `<=`), the volume floor in both directions, `short_s` 60 to 59, the roaming filter, `S1` distinct to plain count, `S2`
answered denominator and `<=`, `S3` release cause 16 and the answered flag, `S4` denominator and MT filter, `S5` voice filter and
SMS direction, `S6` radius, the one-second floor and the different-cell condition, `S7` step inclusive, zero step and prefix
check, `S8` night end and entropy base, `S9`, `S10` overlap `<` and the frame, `S11` own filter and `starts_with`, `S12` tenure
and batch candidates, the window edges and the zone. Real Maven runs of six of them (`cand_max_inbound`, the roaming filter,
`min_events`, the sequential step, the release cause, the `missing` flag) were red for the named reason; the rest were run against
the same corpus in a scratch DuckDB and each changed a column the oracle pins.

**Still open for B2 and later:** the Risk Score `tb_sim`, `tb_findings`, the four Entity Lists, `tb_sim_high`; the cluster stage and
the box Incident (D-BP3); S13 to S16; D-BP10; an operator's real CDR contract (D-BP2's actual columns); sample CSVs for the gallery
(B7); the corpus split per archetype before B4 starts.

## 18. B2 as built (2026-10-20)

Commits on `lane/bypass-b2`: see the BACKLOG row. Hub: [`okf/product/solution-pack/telco-bypass/`](../../okf/product/solution-pack/telco-bypass/index.md).

**Delivered.** Three Jobs and a model beside `tb_features`: `tb_factors` (`sql.template`, a one-window snapshot: the factor columns, `primary_signals`, `est_bypass_minutes`), the Risk Score `tb_sim` (11 factors) run by the `risk.score` Job `tb_risk_score`, and `tb_findings` (`sql.template`, incremental, 31 days) which applies the allow list, agreement, persistence, the watch list and the shadow switch and is the only Dataset the two Alert Rules read: `tb_sim_high` (CRITICAL, `max(alert_score)`, by `entity_key`, `stormCap` 1000) and `tb_allowed_line_changed` (INFO). Seeds for `tb_factors`, `tb_findings` and the sidecars of `tb_allow` and `tb_watch`. Tests: in module `inspecto-scoring`, `BypassScoreTest` (6), `BypassAllowListTest` (10, the `tb_findings` stage in isolation, G-SB(c)), `TelcoBypassGoldenTest` (5), the opt-in `BypassScaleMeasureTest`; in `inspecto`, `BypassCorpus.generateB2()` and `filesOnDay`, two more `BypassCorpusTest` tests, and `BypassFeatureGoldenTest` updated for three `sql.template` Jobs. `inspecto` now publishes a test-jar and `inspecto-scoring` reads it (test scope) so the golden reuses `BypassCorpus` instead of copying it; `tools/affected-modules.mjs` therefore answers FULL for this lane (any module pom edit does).

**What the golden pins.** Corpus: B1's plus X1 (two SIMs, high score, only four primary signals) and X2 (one box-like SIM active in exactly 18 hours), and a day 2 feed in which allowed line L2a starts to move. Night 1: 214 scored SIMs, 93 high; every factor, the primary-signal count and the score of every scored SIM equal an independent Java oracle (the weights are pinned in the test, not read from the model). Night 2: 90 SIMs alert, each exactly once (one Incident each, a further sweep and a full re-run of night 2 add none), every one with top factors `b_diversity,no_inbound,short_calls` and six primary signals: P1 (60) + P3 (20) + one SIM per clause and the volume floor (6) + X2 + the pinned dialler pair L3 (2) + L2b, whose allow entry lapsed. Silent, each for an asserted factor: the five AT-the-threshold SIMs (their one clause factor is 0 and with its weight added they would be high), call centre, alarm line, couriers, new heavy user, dense building, sequential dialler, cloned SIM, novel shape (the named positive factors are 0, the named protective factors are negative); X1 (high, `agreement`); L2a (`allowed`, and `tb_allowed_line_changed` is raised for it alone on night 2); everything on night 1 except the watched SIM (`persistence`). Shadow mode writes the same scores and findings with `alert_score` 0 and raises nothing. Every stored score recomputes with `RiskScorer.recompute` to 1e-9. The four-eyes hold on an allow entry is the Entity List module's own behaviour (`ControlApiEntityListAssuranceTest`), configured by the Space's approval policy; B2 adds nothing to it.

**Deviations from the plan, and traps found.**

- The five clause factors are 0-or-1 flags using `box_like`'s own strict comparisons and parameter values (the plan's `b_diversity_excess` and `short_uniform_index` names are kept but are not graded, and `short_uniform_index` ignores `acd_cv`); only the shares (number walking, night, own-network) and the two protective shares are graded. The protective inbound factor reads the inbound share, not the plan's count of distinct callers (`tb_features` does not hold it). Weights are the plan's; the positives sum to 80 until the device-cluster (12) and anomaly (8) factors exist; high is 60.
- ⚠ A Space Template cannot ship an Entity List. The pack ships zero-row sidecar seeds with the list marker so the Jobs read before the lists exist and the list's first write claims the folder; the model ships without `watchList` (a model naming a missing list is refused at save); the lists are created by hand (admin guide). The watch list's `min_events` lowering (plan §5.3) is not built: only the persistence floor is lowered. `tb_block` and `tb_ranges` are documented, not read.
- ⚠ Only a CRITICAL or error rule opens an Incident. `tb_allowed_line_changed` (INFO) therefore opens one Alert whose value is the number of breached lines; the SIMs are read from `tb_findings.shape_changed`. Both rules read max over the 31 retained days, so an alerting SIM stays breached until its partition is pruned.
- ⚠ B0 answer 2 holds: `stormCap` 1000 is the ceiling and the per-SIM rule storms above it (measured below), so the box Incident of B3 (D-BP3) is the answer for a farm; B2's SIM rule is its evidence.
- Alert Rule `threshold` must be positive (`gt 0` is refused; `gte 1`). `asof` is a DuckDB keyword. A `window_date` read through a store view needs `CAST(... AS DATE)`. A TOON list needs `evidence[2]: a, b`. `risk.score` factors read `max(column)` over the whole Dataset, so `tb_factors` is a one-window snapshot.
- Allow and watch expiry is compared with the window's end as stored: the sidecar writer stores an instant in the HOST's zone (measured: 12:00Z becomes 17:30 on an Asia/Kolkata host), the window follows `feed_zone`; keep them equal.
- `tb_findings` rejects a scores run older than the window (`scored_at` at or after the window's end), so a failed `tb_risk_score` night cannot be joined to the next night's factors.
- `cluster_key` is not in `tb_findings` (B3). `est_bypass_minutes` is calls x answer rate x mean answered duration / 60; the optional rate parameter of D-BP8 is not built.

**Measurements (synthetic, one 12-core box; the inputs are a seeded generator, not an operator's traffic).** Stage time over N scored SIMs: 3,000 SIMs: `tb_factors` 0.18 s, `tb_risk_score` 7.5 s, `tb_findings` 0.21 s; 50,000: 0.39 s, 42.7 s, 0.65 s; 150,000: 0.60 s, 114 s, 1.4 s. The Risk Score Job dominates (11 factor queries and the evidence reads, about 0.75 ms a SIM); a day scores about 0.7 % of the SIMs in the B0 scenario (2,700 of 400,000), and the Job refuses more than 200,000 entities by default. Peak memory of these stages was not measured. `tb_sim_high` at its cap: 900 and 1,000 alerting SIMs open one Incident each; 1,001 and 1,200 open ONE storm Incident and no per-SIM Incident. Gate G-SB(b) is not tripped (the box Incident is the answer), but a 1,200-SIM box farm cannot be expressed per SIM; B3 re-checks.

**Mutation pass.** 65 hand-built mutants, each one clause change of the shipped files, each run against `BypassScoreTest`, `BypassAllowListTest` and `TelcoBypassGoldenTest`: every clause flag comparator and its parameter, the inbound and service-mix formulas, the mobility clauses, the round-the-clock boundary and parameter, the primary-signal terms, the window filter, the minutes formula, every weight, sign, cap and the high threshold, the allow expiry (strict), purpose, prefix, range length and key match, the watch match and expiry, the persistence window, comparison, clause and watch discount, the agreement comparison and order, the shadow clause and order, the findings row filter, the shape signature and its shadow silencing, the history cut-off, the high-night filter, the stale-score and stale-snapshot guards, the top-factor slice, filter and sort, `alert_score`, the shipped parameter values, and the Alert Rule measure, severity, key, threshold and `stormCap`. All 65 ended red. One survived at first (F16, `+ 1` for tonight's high night: an allowed night below high was not asserted on its own row) and was killed by a new assertion; T18's first pattern matched two places and was rewritten; the stale-score guard was added after the first pass and has two mutants of its own (F28, F29). Run through the JUnit launcher over the same compiled classes and the real Jobs, not a scratch DuckDB; the final tree was re-run green afterwards. Seven mutants (T12, F12, F13, F16, F18, F26, A6) went red in a right test whose message differed from the substring first guessed; each failing test was read and is the one that owns the clause. A `greatest(1, ...)` guard on the persistence floor was removed as unreachable (every candidate row has at least one hit).

**Not built, and open.** The cluster stage and the box Incident (B3); the Anomaly Model factor (B4); the device signals; `tb_block` and `tb_ranges` consumers; the watch list's `min_events` lowering; the barring Action Request; the per-Alert runbook components (the runbook file is updated, the Alert Rules carry no `runbook` link yet); the KPIs and tuning report (B6); D-BP10 (billable, `orderCode`, matrix cell E) stays the operator's call and no offering changed. Not measured: peak memory of the B2 stages, precision on real traffic, persistence latency (D-BP5 stays an illustrative 2 of 3).

## 19. B3 as built (2026-10-21)

Commits on `lane/bypass-b3`: see the BACKLOG row. Hub: [`okf/product/solution-pack/telco-bypass/`](../../okf/product/solution-pack/telco-bypass/index.md).

**Delivered.** Two `sql.template` Jobs, two Datasets, one Alert Rule, one Case Rule, one Link Analysis view and one runbook in the `telco-bypass` template. `tb_cluster_members` (03:40) reads the day's `voice_cdr` itself and keeps the candidate SIMs (at least `cand_min_events` 10 non-roaming MO voice calls and all five `box_like` clauses, strict, at the `tb_features` parameter values; not on a live `tb_allow` entry) that share a key with at least `min_box_sims` 5 candidates: `imei`, then `cell`, then `batch` (dealer and activation day); a SIM joins the first key that is a box, so one box is one `cluster_key`. `tb_clusters` (03:50) rolls the members up to one row per box per day (candidates, scored and alerting members, top score, talk minutes, cells, devices, spoofed SIMs, `days_seen`). Alert Rule `tb_box_suspected` (CRITICAL, `max(alert_value)` by `cluster_key`, `stormCap` 500, runbook linked) and Case Rule `tb_box_case` (`groupBy: cluster_key`). Zero-row seeds for both Datasets. D-BP10 is untouched: no offering, `orderCode` or matrix change.

**Tests.** `BypassClusterTest` (10, the two stages in isolation: each key, the fallback without a device column, key precedence, box size, every candidate clause AT and just INSIDE its threshold, the volume floor, window edges, roaming, allow list with expiry, the IMEI-swap count, the roll-up), `TelcoBypassClusterGoldenTest` (3, module `inspecto-scoring`: the full chain over the B3 corpus against a plain-Java oracle written from the raw call rows; six planted boxes by name, every look-alike silent, one Incident per box that a second night and a re-run do not repeat, the cell key without a device column, shadow mode), `BypassBoxCaseRuleTest` (2, module `inspecto-case-management`, shipped rules on the real object engine), `BypassCorpus` extended with the B3 archetypes and `BypassCorpusTest` cases. The opt-in `BypassScaleMeasureTest` (`TELCO_BYPASS_PERF=1`) has two more cases.

**Deviations and traps found.**

- The gate wording "a second night **updates** the Incident" is only half true: the rules read the whole retained Dataset, so a box opens one Incident once and the second night opens none; the Incident text is not refreshed, the box row's `days_seen` and `last_day` move. The golden pins that behaviour.
- Found in review of the resumed work: `tb_cluster_members` counted MO calls of every service, so a SIM's SMS inflated `calls_mo`, the diversity and short-call shares; `tb_features` counts MO voice only. Fixed (`service = 'VOICE'` in the `mo` set); `BypassClusterTest` goes red without it (199 expected, 200 found).
- There is no box persistence: a box opens on the first night its candidates share a key (SIM Alerts wait for persistence). Raise `min_box_sims` or run in shadow first.
- A box nothing links (different cells, devices and batches, no device column) is invisible to this stage.

**Gate G-SB(b), re-checked.** 1,200 alerting SIMs of 20 boxes: the SIM rule `tb_sim_high` (cap 1000) opens ONE storm Incident and no per-SIM Incident; `tb_box_suspected` opens 20 Incidents, no storm. The box rule at 500 boxes opens 500 Incidents, at 501 one storm Incident. G-SB(b) is not tripped: the box Incident expresses a farm the SIM rule cannot; `stormCap` 500 is the ceiling for boxes a day.

**Measurements (synthetic, one 12-core box, a hash-based generator that is NOT an operator's traffic; 20 boxes of 60 SIMs planted).** Member stage standalone in DuckDB with the engine's default memory cap: 1.3 M CDR rows 0.40 s, peak 448 MiB; 5.3 M rows 1.5 s, 1,311 MiB; 10.3 M rows 2.8 s, 2,081 MiB. The real `tb_cluster_members` Job: 0.60 s, 1.7 s, 3.8 s; `tb_clusters` 0.10 to 0.16 s. All inside the G-SB(a) budget (5 M rows in 60 s, 2.5 GiB), and the cluster stage adds less memory than `tb_features` at the same row count. Not measured: a box farm with a million candidate SIMs, real traffic, precision.

**Mutation pass.** Eight hand-built mutants of the shipped files, each run against `BypassClusterTest`, `TelcoBypassClusterGoldenTest` and `BypassBoxCaseRuleTest`; every one red for the named reason: all-services `mo` (199 vs 200 calls), box size `>` for `>=` (the boxes of exactly five vanish), cell before imei (the IMEI boxes become cell boxes), allow anti-join disabled (allowed SIMs counted), window end inclusive (the AT-end variants become boxes), `stormCap` 5 (boxes storm), Alert Rule `by` the wrong column, Case Rule `groupBy: entity_key`. A first allow-list mutant was a syntax error (red for the wrong reason) and was replaced by `AND 1 = 0`.

**Not built, and open.** The barring Action Request (a manual step in the runbook until a provisioning API exists); box persistence; an Incident text that refreshes on a later night; S13 to S16 device signals; B4 to B7; D-BP10 stays the operator's call. Peak memory of the B2 stages and precision on real traffic stay unmeasured.
