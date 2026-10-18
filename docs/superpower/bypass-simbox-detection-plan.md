# Bypass / SIM-box detection from CDRs: a plan

> 🟡 **PLAN 2026-10-18 (`TELCO-BYPASS-SIMBOX-1`): design only, nothing built.** Competitive gap **E** (bypass /
> SIM-box detection) is **Partial** in [`COMPETITIVE_CAPABILITY_MATRIX.md`](../stakeholders/COMPETITIVE_CAPABILITY_MATRIX.md):
> LATRO, Subex, Mobileum, Neural and Araxxe sell it, and the matrix records Inspecto as "CDR-based only". The same
> matrix places signalling probes (row F) and test-call generation (row G) **out of scope**, so this plan is
> **CDR-based only by design** and says in §11 which RFP lines it therefore cannot close. Interconnect-side fraud that is
> not bypass (IRSF, Wangiri, flash calls; row H) is planned by a sibling document and is **not** touched here.
> Decisions **D-BP1…D-BP10 are open** (§14). Every number in this plan (volumes, thresholds, weights) is a hypothesis
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
  ([`okf/backend/control-plane/risk-scores.md`](../okf/backend/control-plane/risk-scores.md),
  [`anomaly-scores.md`](../okf/backend/control-plane/anomaly-scores.md)); ML scoring stays the roadmap gap `G-21` (`FM-18`).

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
([`okf/capabilities/spaces/spaces.md`](../okf/capabilities/spaces/spaces.md) §3.5): it avoids cycles and hidden cost. Why SQL and no new
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
   the IMEI), held for four-eyes and sent once with bounded retries ([`okf/backend/control-plane/action-requests.md`](../okf/backend/control-plane/action-requests.md)); the
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
| **B1 signals + corpus** | Template skeleton `telco-bypass`; `voice_cdr` schema and Pipeline; Reference seeds; `tb_features` with S1 to S12; the corpus generator `BypassCorpus` (fixed seed) planting every archetype and look-alike of §12; zero-row seeds. | Golden asserts **exactly** the planted signal values per archetype and each look-alike's expected zero or sub-threshold column; the two host-zone run gives identical rows; missing optional columns flag, they do not fail. | `BypassCorpusTest` (deterministic; planted and benign sets disjoint), `BypassFeatureGoldenTest` (Jobs run, Datasets equal the planted set), a clause-by-clause mutation pass, a time-zone test. |
| **B2 score + lists + alert** | `tb_sim` Risk Score; `tb-watch`, `tb-allow`, `tb-block`, `tb-ranges`; `tb_findings` with `primary_signals` and persistence; Alert Rule `tb_sim_high`; the anti-join with `tb-allow`; the shadow switch. | Every planted SIM alerts exactly once with its planted top factors; every look-alike is silent **for the planted reason** (the factor that suppresses it is asserted); an allow entry suppresses and its **expiry** un-suppresses; a changed allowed line raises the lower Alert; the score recomputes from stored factors; `-shadow` writes scores and raises nothing. | `BypassScoreTest` (each factor, protective sign, cap), `TelcoBypassGoldenTest` (Alerts equal planted set), `BypassAllowListTest` (expiry, four-eyes hold), mutation pass. |
| **B3 cluster + Incident + Case** | `tb_clusters` (imei, cell, batch); Alert Rule `tb_box_suspected`; Case grouping per B0 answer; runbooks; Action Request template for barring; Link Analysis projection config. | The corpus's three boxes (one spoofing its IMEI, one on one IMEI, one slow-roll across many SIMs) each yield **one** box Incident and its member SIM Alerts; the IMEI-spoofing box is still caught by the `cell` key; a second night **updates** the Incident; a benign dense building (many SIMs, one cell, normal mix) is silent. | extend the golden; `BypassClusterTest` (each key, fallback when `imei` absent); a two-run stability test. |
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

## 14. Open decisions for the operator

| ID | Decision | Recommendation |
|---|---|---|
| **D-BP1** | Home: new template `telco-bypass`, or extend `telco-fraud`? | **New template**; keep `fraud_simbox` as the starter. |
| **D-BP2** | The CDR contract: which extra columns can the operator (or the MVNE) supply: IMEI, IMSI, attempts or release cause, cell site? | Ship with **optional** columns and per-signal degradation; ask the operator at B0. |
| **D-BP3** | Work item: per SIM, per box, or both? | **Both**: the box Incident is the work item, SIM Alerts are evidence; confirm the Case grouping in B0. |
| **D-BP4** | Scoring: Risk Score primary with the Anomaly Model as one factor, or the anomaly layer as its own alert? | **Risk Score primary**; anomaly never alerts alone in v1. No ML (`G-21`). |
| **D-BP5** | Latency versus precision: persistence `persist_days` and run frequency (daily only, or an hourly window)? | **Daily with persistence** first; hourly is a later slice measured against `WS-21`. |
| **D-BP6** | Action: automatic barring, an approval-gated Action Request, or a manual task? | **Approval-gated Action Request** (or manual until a provisioning API exists); never automatic; only `watch` entries (≤ 24 h) auto-apply. |
| **D-BP7** | Build the interconnect-side slice B5 now, or only when a prospect supplies gateway CDRs? | **Demand-gated**; boundary with the IRSF / Wangiri lane agreed first. |
| **D-BP8** | Exposure: minutes only, or a monetary estimate from a termination-rate Reference? | **Minutes**, with an optional rate parameter; impact stays manual. |
| **D-BP9** | A TAC (device class) Reference: the operator licenses one, or the pack ships without it? | **Optional**; absent means S14 is omitted. |
| **D-BP10** | Commercial and matrix: `billable`, `orderCode` for the new pack; matrix cell E after B3 (**P** to **Y**, with the note "CDR-based")? | The operator's call; the matrix is updated only when a slice ships. |

## 15. Backlog and docs touched

- `BACKLOG.md`: the row **`TELCO-BYPASS-SIMBOX-1`** keeps its place and priority and its **text** now points at this plan (CDR-based
  slices B0 to B7; non-CDR evidence stays out of scope). No row is added or removed, so no count marker changes.
- `INDEX.md`: this plan's row in the in-flight table.
- On ship (B7): distil as-built facts into [`okf/capabilities/spaces/spaces.md`](../okf/capabilities/spaces/spaces.md) (a subsection beside §3.5),
  add a solution page under `stakeholders/solutions/`, update the matrix row E, then `git mv` this plan to
  `archived-documents/plans-archive/`.

## References

- Matrix: [`stakeholders/COMPETITIVE_CAPABILITY_MATRIX.md`](../stakeholders/COMPETITIVE_CAPABILITY_MATRIX.md) rows E, F, G, H.
- Existing pack: `spaces/_templates/telco-fraud/` (`fraud_simbox` Job, Alert Rule, runbook, `tf_subscriber_usage` Anomaly Model,
  Case Rule); [`stakeholders/solutions/telecom-fraud.md`](../stakeholders/solutions/telecom-fraud.md).
- Add-ons: [`okf/backend/control-plane/anomaly-scores.md`](../okf/backend/control-plane/anomaly-scores.md),
  [`risk-scores.md`](../okf/backend/control-plane/risk-scores.md), [`entity-lists.md`](../okf/backend/control-plane/entity-lists.md),
  [`action-requests.md`](../okf/backend/control-plane/action-requests.md).
- Sibling plans (house style): [`rerating-tariff-validation-plan.md`](rerating-tariff-validation-plan.md),
  [`partner-settlement-wholesale-plan.md`](partner-settlement-wholesale-plan.md).
- Canonical vocabulary: [`GLOSSARY.md`](../GLOSSARY.md) (the single source of truth).

## 16. Measurements (filled by slice B0)

_Empty until B0 runs: SIM-day throughput and memory, Alert Rule `by` with two keys and `stormCap` at box cardinality, Case grouping on a
cluster key, a two-condition Alert Rule, the `allow` sidecar expiry read, and the test's owning module._
