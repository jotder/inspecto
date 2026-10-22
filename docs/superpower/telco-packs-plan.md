# Telco solution packs: open work (bypass, partner settlement, re-rating)

> 🟡 **IN FLIGHT, consolidated 2026-10-22.** The one plan for the three telecom solution packs still being built. It holds **only
> open work**: slices not built, decisions not taken, the contract between partner settlement and re-rating, the RFP lines that still
> matter, and the gates. What is built, why, what was measured and what was found lives in each pack's hub (`plan-as-built.md`
> beside the architecture page and guides); the original plans are archived for provenance:
> [bypass](../archived-documents/plans-archive/bypass-simbox-detection-plan.md),
> [partner settlement](../archived-documents/plans-archive/partner-settlement-wholesale-plan.md),
> [re-rating](../archived-documents/plans-archive/rerating-tariff-validation-plan.md).
> Every number below is a synthetic measurement or an illustrative default, never a vendor or operator fact.

| Pack (board row) | Space Template | Built | Open slices | Open decisions | Hub |
|---|---|---|---|---|---|
| Bypass / SIM-box (`TELCO-BYPASS-SIMBOX-1`, matrix gap E) | `telco-bypass` | B0 measured; B1 to B3 (signals, Risk Score, lists, SIM Alert, cluster stage, box Incident and Case) | B4 to B7 (B5 demand-gated) | D-BP10 | [telco-bypass](../okf/product/solution-pack/telco-bypass/index.md) |
| Partner settlement (`PACK-PARTNER-SETTLEMENT-1`, gap Q) | `partner-settlement` | S0 to S4 (References, ingestion, compare, netting, roll-forward) | S5 to S8; W1 to W3 demand-gated | PS-O1 to PS-O7 (§2.3), the §4 contract | [partner-settlement](../okf/product/solution-pack/partner-settlement/index.md) |
| Re-rating (`PACK-RERATING-1`, gap C) | `telco-rerating` | R0 measured; R1 to R3 (hygiene, ambiguity, causes, root causes, plan inference) | R4, R6; R5 demand-gated; R7 only if G-K trips | D-RR10, the §4 contract | [telco-rerating](../okf/product/solution-pack/telco-rerating/index.md) |

All three are configuration-only content packs (`minEdition: standard`, listed in `offerings/standard.toon` `contentPacks`, Product
Map Items with `billable: false`, `status: preview`): no new Step Processor, Job Type or route is planned unless a gate (§5) trips.
None edits `telco-fraud` or `telco-ra`.

## 1. Bypass / SIM-box (`telco-bypass`)

CDR-based only by design: signalling (matrix row F), test calls (row G) and inline blocking stay out of scope, and a CDR pattern is
evidence of consistency with a box, never proof. As built: [architecture](../okf/product/solution-pack/telco-bypass/architecture.md),
[decisions and as-built record](../okf/product/solution-pack/telco-bypass/plan-as-built.md).

### 1.1 Open slices

**Before B4:** split `BypassCorpus` per archetype into disjoint files, so B4 and later lanes do not collide on one generator. B5 and
B6 are independent of each other.

| Slice | Delivers | Done when (verifiable) | Tests (unit level) |
|---|---|---|---|
| **B4 anomaly layer** | Anomaly Model `tb_sim_behaviour` (entity `subscriber`, `bucket: day`, `window: 28`, `seasonality: weekday`); Features are day Measures over `tb_features` (`sum(calls_mo)`, `sum(distinct_b)`, `sum(night_calls)`, …; ratios stay in the Job because a Measure is an `{agg, field}` pair); `peers.by` = the plan column of `subscriber_profile` (never a classified column: the anomaly gate refuses an MSISDN / IMSI cohort); `exclusionList: tb_allow`; the `anomaly.score` Job; the Risk Score factor `max(score)` over `anomaly_scores_tb_sim_behaviour_latest` (illustrative weight 8). | A planted **novel** archetype (P6: no fixed threshold covers it) is flagged by the anomaly factor alone and stays **below** `high`; a heavy-user plan is not flagged against its own cohort; the anomaly factor never alerts alone (D-BP4). | `BypassAnomalyTest` (golden extension); `AnomalyKindValidator` gate on the model (peers column not classified). |
| **B5 interconnect side** (demand-gated, D-BP7) | `ic_cdr` feed (`trunk_id, presented_cli, network_cli, b_number, duration_s, start_ts`); trunk and CLI signals (national CLI on an international trunk, CLI cardinality per trunk, sequential CLIs, CLI equal to B-number); Alert Rule `by: trunk_id`; the inbound-international versus own-network-terminated Measure, the join point with partner settlement (§4.3). | Planted trunk archetypes recovered; the boundary with the IRSF / Wangiri plan recorded first: no CLI column, signal or Alert Rule defined in both. | golden extension; `TrunkCliTest`. |
| **B6 feedback and KPIs** | Typed verdict on close: *confirmed box*, *false positive: verified business line*, *false positive: other*, *needs verification* (a customer or dealer call: only a call separates a dialler from a box). Precision, false-positive share and per-signal contribution Measures; a quarterly tuning report Job; tuning suggestions as a diff for a human, **never auto-applied**. On close a confirmed SIM goes to `tb_block`, a verified false positive to `tb_allow` with expiry (both through the held Entity List approval). | A replayed verdict history on the corpus produces the planted precision; a tuning suggestion is a diff, not a write. | `BypassKpiTest`; mutation pass. |
| **B7 package** | Space dashboard (suspected boxes by cell, score distribution, precision), a Widget per signal family, the SIM runbook beside the shipped box runbook and `runbook` links on the SIM Alert Rules; sample CSVs for the template gallery; the matrix row E; hub docs refreshed; verify how Advanced inherits `contentPacks` (only `basic.toon` and `standard.toon` list them) before claiming it. | The gallery marks `creatable` per edition; the guards pass **locally** (vocabulary, doc-links, doc-counts, openapi if a route changed, product-map) before any push. | golden; the guard runs. |

### 1.2 Open items carried from B1 to B3

Each is either folded into a slice above or needs a slice of its own when demand appears:

- **Barring Action Request** (D-BP6): suspend or bar the SIM, bar the IMEI, held for four-eyes, sent once with bounded retries; a manual
  step on the Case until the operator exposes a provisioning API.
- **`tb_block` and `tb_ranges` consumers**: `tb_block` feeds the barring request, the partner list push (§1.5, `RT-26`) and Link
  Analysis `seedBy`; `tb_ranges` feeds S11 and the cluster stage with own-network and suspect ranges.
- **Watch list lowering `min_events`** for a watched SIM (only the persistence floor is lowered today).
- **Box persistence** (a box Incident opens on its first night) and an **Incident text that refreshes** on a later night (today a
  persisting box opens one Incident and then nothing; `days_seen` and `last_day` move).
- **Device signals**, blocked on the operator's real CDR contract (D-BP2's actual columns): S13's 7-day IMSI churn, S14 TAC class (an
  optional, never-bundled Reference, D-BP9), S15's cell concentration and peak concurrent calls per IMEI, and the device-cluster
  Risk Score factor (illustrative weight 12, `max(cluster_candidate_sims_excess)`). S16 in money needs the D-BP8 rate parameter.
- **Hourly window** (D-BP5): a later slice, measured against `WS-21`. Persistence latency (`persist_days`, the illustrative 2 of 3 days) is still unmeasured.
- **Geo Map** of cell co-location in the triage view.
- **Unmeasured:** peak memory of the B2 stages, more than 405,000 SIMs a day, a real operator's volume and precision.

### 1.3 Open decision

| ID | Decision | State |
|---|---|---|
| **D-BP10** | Commercial and matrix: `billable` and `orderCode` for the pack; matrix cell E (**P** to **Y**, noted "CDR-based")? | **Open, the operator's call.** Until then the Product Map Item stays `billable: false`, `status: preview`, no `orderCode`; the matrix changes only when a slice ships. |

### 1.4 RFP traceability (MVNO RFP working set, cited as identifiers only)

The working set is a git-excluded local folder; no client material is in this document. The RFP's position columns are the
operator's to change; nothing here edits them.

| Slice | Compliance-matrix lines | Gap IDs | Workstreams |
|---|---|---|---|
| B1 to B3 (built) | `FM-02` (SIM-box part, `T-05`, `FM-R03`), `FM-04` (abnormal location, impossible speed), `FM-16`, `INT-01`, `FM-09`, `FM-15`, `FM-22`, `FM-20`, `RT-06` (lists, partial), `FM-08`, `FM-07`, `FM-19`, `FM-23`, `FM-24`, `FM-21`, `RC-04`, `RT-26` | `G-07`, `G-08`, `G-26`, `G-05`, `G-13`, `G-42`, `G-15`, `G-14` | `WS-14`, `WS-15`, `WS-03`, `WS-12`, `WS-22`, `WS-18`, `WS-13`, `WS-24`, `WS-23` |
| B4 | `FM-16`, `FM-15` (peer groups, behavioural models: statistical, not ML) | `G-07` | `WS-14` |
| B5 | `FM-02` (abnormal international traffic, the `T-03` / `T-06` boundary); row H is the IRSF / Wangiri lane's | `G-08` | `WS-15` |
| B6 | `FM-27`, `FM-30`, `FM-31` (`FM-K05`, `FM-K06`, `FM-K11`), `FM-33` (`FM-K13`, `FM-K14`), `FM-29` (`FM-K02`, once the ledger exists) | `G-03`, `G-11` | `WS-10`, `WS-20` |
| B7 | `FM-28`, `FM-34` (Customized classification, Configurable thresholds), `INT-09`, `INT-11` | `G-11` | `WS-20`, `WS-15` |

⚠ `FM-02` stays **Partially compliant** after this pack: SIM-box is one of nine typologies in that line.

**Lines CDRs cannot close** (state them in every bid):

| Line | Why not from CDRs | What closes it |
|---|---|---|
| `RT-03`, `RT-04`, `RT-05`, `RT-07` (real-time validation and enforcement) | a CDR exists after the call; the pack is post-event | the RTSC partner (`G-01`, `WS-30`, bid item `B-01`); the pack supplies lists, scores and the Action Request |
| `FM-17`, `FM-01` (real-time part) | mediation latency plus the poll cycle set a floor of minutes | `WS-21`; inline by the partner |
| `RT-21` … `RT-24`, `T-08`, `FM-03` (spoofing) | needs signalling or firewall logs (matrix row F) | partner or probe; out of scope |
| none (matrix row G): test calls | a call must be placed and observed; no compliance line asks for it (the nearest, `RA-11`, is not about bypass) | a test-call service; out of scope |
| `FM-18` (ML scoring) | statistical by decision (D-BP4) | roadmap `G-21`, `WS-31` |
| `FM-04` (device part) | only when the feed carries IMEI and IMSI; an MVNO on an MVNE may not get them (`G-38`) | the MVNE exposing the fields; until then the `cell` and `batch` keys |
| `FM-29`, `FM-K01` … `FM-K04` (loss figures) | a CDR gives minutes; money needs a rate and a confirmed verdict | `WS-10` ledger and D-BP8; impact stays manual |

### 1.5 Risks still open

The CDR contract is unknown (the largest risk; the pack degrades per signal). The irreducible false positive: an unregistered quiet
dialler or PBX is identical to a quiet box from CDRs alone, so the answer is *needs verification*, not a smarter threshold. Synthetic
corpus only (boxes adapt; the signal list is closed, a missing signal is gate G-SB(d)). Storm and cardinality (`stormCap` 1000 for SIMs,
500 for boxes a day). Privacy (MSISDN and IMSI classified; the cohort column must not be; cell location CONFIDENTIAL).

## 2. Partner settlement (`partner-settlement`)

As built: [architecture](../okf/product/solution-pack/partner-settlement/architecture.md),
[decisions and as-built record](../okf/product/solution-pack/partner-settlement/plan-as-built.md) (PS-D1 to PS-D10, answered, with how
to reverse each).

### 2.1 Open slices

The golden test for S5 onward stays in `features/inspecto-reconciliation` if its test classpath carries Workflow & SLA and Regulatory
Reporting; otherwise it goes where `AmlPackGoldenTest` went (check at S5).

| Slice | Content | Done when (verifiable) | Tests |
|---|---|---|---|
| **S5 disputes** | A Case Rule grouping Incidents per `(partner, cycle)` into one Case: the Incident is the line, the Case is the dispute. The authored CASE workflow `OPEN → INVESTIGATING → DISPUTED (sent) → AGREED \| REJECTED → CREDIT_PENDING → CLOSED`, a superset of the built-in states (PS-D5; ⚠ one Workflow per object type per Space, so it replaces the default for every Case; **blocked on PS-O5**). An SLA Policy for `CASE` as the internal response target; the contractual window as data: `dispute_window_end` per agreement and cycle in `settlement_calendar`, with an Alert Rule N days before it for any Case still before `DISPUTED`; an Escalation Rule by age. Impact mapped per PS-D6, an analyst action (PS-D9). Disposition uses the built-in ladder, extended in the pack's glossary entry only if it cannot say "credit note agreed". The disputed amount as its own column of `settlement_position`, never netted away. Calendar dates (statement due, dispute window, payment due) as Expectations and Alert Rules. | A planted mismatch becomes an Incident, groups into one Case per `(partner, cycle)`, moves `DISPUTED → AGREED → CREDIT_PENDING`; a Case near its window end alerts; the Impact reads as mapped. | golden through the Control API (real HTTP, as `TelcoRaGoldenTest`); a workflow `problems()` unit test on the authored file. |
| **S6 aging and accruals** | `settlement_aging`: open balance and open dispute amount by age bucket (0–30, 31–60, 61–90, 90+ days, boundaries configurable), aged from the statement date for balances and from the Case's `DISPUTED` timestamp for disputes (the Break's `ageDays` is read-time and not reused). `settlement_accrual` per `(agreement, period)`: `accrued = expected − invoiced` for a period with no statement yet, plus a provision on disputed amounts (PS-D7: configurable, default 100 %, a finance policy). KPIs (open disputed amount, aged > 60 days, accrued unbilled), the `settlement_overview` dashboard, the runbook. The pack reports accruals, never posts them. | KPI tiles render on the corpus; a late statement is an accrual before `statement_due` and a finding after. | golden: every dashboard tile renders (the `mobile-money` assertion). |
| **S7 evidence pack** | Report Template `settlement-dispute-pack` (Regulatory Reporting, CSV and JSON, four-eyes, file-drop; PS-D8), rendered from the Case: statement header and disputed lines, our re-rated lines with `rate_id` and `rating_run_id`, the variance and its reason, the agreement version and tolerance, the FX row, the Incident timeline. A PDF dispute letter is demand-gated. | The CSV / JSON names the rate and run id of every disputed line; a line with no re-rating run is refused (422), not exported blank. | template-render unit test; four-eyes path asserted. |
| **S8 docs and registration** | The glossary entries of §2.4; the matrix rows C and Q corrected (`ra_settlement` is a SQL control with a flat rate, not a Reconciliation, and the old board row bundled gaps Q and C); hub docs refreshed; `offerings/*.toon` and `tools/check-offerings.mjs`. | All guards green: vocabulary, doc links, offerings, product map. | the guards named. |
| **W1…W3 wholesale, MVNO, roaming** | Demand-gated; W1 first (PS-D10). See §2.2. | A named prospect or the operator's call. | per slice at that time. |

### 2.2 Wholesale, MVNO and roaming (W1 to W3)

| Construct | Plan | Phase |
|---|---|---|
| MVNO per-unit and retail-minus | Same `settlement_agreement` kinds; the rate card carries the construct (re-rating side). Attribution by IMSI range, MSISDN block or APN in an effective-dated `hosting_agreement` Reference (the attribution of §4.1 R3). Research controls C8 (invoice verification), C9 (usage-file completeness), C18 (dispute register) map to S3, S2 and S5. | W1 |
| Capacity pipe, bulk volume, minimum commitment | A commitment true-up control: purchased versus measured peak or volume, shortfall invoice check, drawdown and expiry. Needs measured busy-hour throughput as a feed. | W2 |
| Pass-through (TAP charges, termination, VAS, MNP fees) | Control C5: carrier charges reconciled against amounts re-billed to the MVNO. | W2 |
| Roaming (TAP / RAP, IOT, SDR-denominated) | Inbound and outbound TAP, late TAP, SDR conversion, rejected records. **A TAP decoder is not verified to exist** (ASN.1 decoding unchecked): the slice begins by checking. | W3 |
| Clearing house | A Collector and a mapping; no connection without a named customer. | W3 |

The research note behind C5, C8, C9, C18 is in a git-excluded working set (`group-regulator-assurance/research/R7-…`), not linked.

### 2.3 Open decisions

| ID | Question | Notes |
|---|---|---|
| **PS-O1** | Sign the §4 contract, and **who writes the wholesale re-rating adapter** (per-call retail `rerated_usage` plus an attribution Reference to the §4.1 aggregate) | §4.2; R6 (the completeness Signal) is not implemented, so a half-written period is not protected |
| **PS-O2** | Cross-currency netting: a position currency per netting group and a reverse-rate convention, or keep one currency per position | today one currency per position; inventing `1 / rate` would be a convention |
| **PS-O3** | An opening-balance feed, or a first-month convention | today a series opens at zero; an older balance is an adjustment note |
| **PS-O4** | A payment-integrity finding (duplicate, orphan, wrong partner) | today an orphan is visible in `settlement_movement`, not raised |
| **PS-O5** | How the S5 dispute workflow, SLA Policy and Escalation Rule reach a Space: an administrator apply step, or a governance seed | **blocks S5**: a Space Template carries no governance objects |
| **PS-O6** | The real statement layout (flat or header plus lines) | decided once a real file is seen; today flat, a header is flattened in the Pipeline mapping |
| **PS-O7** | The class of `restated_statement` | today `info`, neither variance nor data quality |

### 2.4 Vocabulary to add to `docs/GLOSSARY.md` at S8

| Term | Meaning | Not |
|---|---|---|
| **Settlement Agreement** | The effective-dated commercial terms with one partner for one service. A **Reference**. | ⛔ *Contract* in UI text; ⛔ *Rate card* (the price list, re-rating's) |
| **Settlement Statement** | A partner's document of what it says is owed for a period; versioned, with a control total. A **Stream**. | ⛔ *Invoice* for an interconnect statement |
| **Settlement Cycle** | One period of an agreement between statement dates; the unit of netting and of a dispute Case. | ⛔ *Billing cycle* |
| **Settlement Position** | Per netting group and cycle: payable, receivable, net, in both currencies. A **Dataset**. | ⛔ *Balance* alone |
| **Dispute** | A **Case** grouping the Incidents sent to a partner for one cycle. | ⛔ a new object type; ⛔ *Claim* (la-claims is another pack) |

### 2.5 Risks still open

The §4 contract (highest: S3 is real only with it; mitigated by the stand-in feed). Break volume: the Reconciliation caps recorded
Breaks at 50,000 per Reconciliation (compare at agreement, period, service, destination class and day, one Reconciliation per service
family; scale above about 400k rows is unmeasured, see the archived
[`reconciliation-uplift-plan.md`](../archived-documents/plans-archive/reconciliation-uplift-plan.md)). One CASE workflow per Space
surprises a Space with other Cases (superset states; say so in the runbook). Format drift per partner (one canonical Schema;
per-partner mapping is a Pipeline mapping). Synthetic data only (tax lines, multi-currency files, mid-period adjustments untested).
Scope creep into re-rating, payments or the ledger (the pack moves no money).

## 3. Re-rating (`telco-rerating`)

As built: [architecture](../okf/product/solution-pack/telco-rerating/architecture.md),
[decisions and as-built record](../okf/product/solution-pack/telco-rerating/plan-as-built.md) (why no kernel, D-RR1 to D-RR9, R0's
answers, known limits).

### 3.1 Open slices

**Before R4:** split `RerateCorpus` per feature into disjoint files. R4 can then run beside R6.

| Slice | Delivers | Done when (verifiable) | Tests (unit level) |
|---|---|---|---|
| **R4 rate structure (stateless)** | The closed vocabulary: billing increment and first block, minimum charge, rounding mode and **scope** (per event, per call, per invoice line), destination zone (longest prefix on `B_NUMBER`, joined from `switch_xdr` on `XDR_ID`), time band, tax class and the inclusive / exclusive flag. `tariff_version` gains `BILLING_INCREMENT`, `FIRST_BLOCK`, `MIN_CHARGE`, `ROUNDING_MODE`, `ROUNDING_SCOPE`, `TAX_CLASS`, `ZONE`, `TIME_BAND`; new References `tax_rate` (`JURISDICTION, TAX_CLASS, RATE, INCLUSIVE`, effective-dated) and `destination_zone` (`PREFIX → ZONE`); `rated_usage` gains the optional rater-reported `TAX_AMOUNT`, `ZONE`, `TIME_BAND`. The hypotheses `fractional_rate` (a clean ratio such as 0.5 across the group), `rounding_mode` / `rounding_scope`, `increment_ignored`, `tax_basis`. | One planted defect per feature recovered with the right `CAUSE`; the same corpus under **two host time zones** gives identical findings (every timestamp and cycle comparison on an explicit zone parameter); no feature changes an R1 to R3 expectation. Re-check gate G-K(c). | per-feature golden fixtures; a time-zone test. |
| **R5 allowance / bundle (stateful)** (demand-gated, D-RR7) | `plan_bundle` Reference (`PLAN, BUNDLE_ID, SERVICE, ALLOWANCE_UNITS, CYCLE, OVERAGE_PLAN`); cumulative free-unit split per subscriber and cycle (window order `EVENT_TS, XDR_ID`; `transform.analytics.running` if a Pipeline form is wanted); the `allowance_not_applied` hypothesis; cycle roll-over, bundle priority, overage plan. | Planted cycle-boundary and order cases recovered; results independent of file arrival order; throughput inside the R0 budget, else G-K(a) trips. | golden extension; an order-permutation test. |
| **R6 Reconciliation, Incident, package** | `billed_invoice` feed (with `TAX_AMOUNT`); `rr_rerate_monthly` writing `rerated_usage_monthly` (re-rated amount per subscriber per month); the Reconciliation `rr_rerated_vs_billed` (`leftDataset: rerated_usage_monthly`, `rightDataset: billed_invoice`, `keyColumns: [SUBSCRIBER_ID]`, `includeRecordCount: false`, absolute and percent tolerances as configuration, `impact.column`); the Alert Rule `by: ROOT_CAUSE_ID` over `rerating_root_causes`, severity from revenue at risk, `stormCap` sized to the distinct causes (never above 1,000); the separate over-billing Incident class with its severity threshold (D-RR4, D-RR5); runbook; dashboard Widgets; sample CSVs for the gallery; matrix row C; hub docs; verify how Advanced inherits `contentPacks`. | One Alert per root cause on the golden; a **second day's run opens no second Incident** for the same cause (R0: an Incident is not updated while it persists); the gallery marks `creatable` per edition; guards pass locally. | golden; `RerateAlertTest`; the CI guards before any push. |
| **R7 kernel** (only if G-K trips) | A design, then a SQL builder with a JVM twin, generated from the tariff Reference; a feature module with a Job Type (for example `rerate.run`) in `tools/bundle-modules.mjs` and the edition profiles. | Design reviewed by the operator before any code. | parity test (JVM equals DuckDB). |

### 3.2 Open items carried from R1 to R3

Orphan-plan detection (needs a plan Reference); a history hygiene Job for `subscription_history` (an inverted period is silently
never in force today); a `plan_label` hypothesis (history says Q, rater labelled P: `unexplained` today); weekly or monthly plan
inference (per day today). **Unmeasured:** `rr_explain` through `JobService` over Parquet (R3 measured in-memory tables), findings
spread over many thousands of subscribers, a real history of millions of rows delivered whole, `rr_rerate` on a 10M+ row day with the
memory cap raised, `rr_rerate` over many retained days of tariff history at volume, a real tariff, plan population or volume.

### 3.3 Open decision

| ID | Decision | State |
|---|---|---|
| **D-RR10** | Commercial: `billable` and `orderCode` for the pack; matrix cell C after R6 (**P** to **Y**?) | **Open, the operator's call.** The pack stays an unbilled preview; the matrix changes only when a slice ships. |

### 3.4 Risks still open

Garbage-in tariff (the dominant error source; hence hygiene first). Synthetic corpus only (anything outside the closed vocabulary is
G-K(b)). Volume unknown above the measured synthetic day. Overclaiming a cause (a counterfactual shows sufficiency, not necessity:
alternatives are kept and the wording is "consistent with"). Time zones and cycle boundaries in R4 and R5.

## 4. Cross-pack contract: partner settlement ↔ re-rating

Settlement compares **their statement** with **our independently priced usage**, never the billing system's rating output (that is
`ra_rated_vs_billed`). D-RR9 put retail re-rating in `telco-rerating` and wholesale in `partner-settlement`, with one `rerated_usage`
contract. **The contract is not signed and the two `rerated_usage` Datasets are not the same contract.**

### 4.1 What settlement needs (R1 to R8)

| Requirement | Detail |
|---|---|
| **R1 Grain** | One row per `(agreement_id, direction, service, destination_class, usage_date, rate_id, rating_run_id)`; per-record rows welcome, the pack consumes the aggregate. |
| **R2 Measures** | `units` (rounded by the agreement's rounding rule), `amount_expected` (`DECIMAL(18,4)`), `currency` (ISO 4217), `record_count`. |
| **R3 Key and attribution** | `agreement_id` is the join key. Settlement owns `partner` and `settlement_agreement` (commercial terms); re-rating owns **price** and **attribution of an event to an agreement** (trunk, route, PLMN, IMSI range). |
| **R4 Effective dating** | The rate in force at `usage_date` in the agreement's cut-off zone; `rate_id` names the version. |
| **R5 Run identity** | Every row carries `rating_run_id` and `rated_at`; a re-run is a new run; settlement compares the latest complete run per period and keeps older ones for the evidence pack. |
| **R6 Completeness** | A Signal (`job.run.completed` guard) naming the period it completed for; the settlement chain starts only on it. |
| **R7 Exceptions as data** | Unpriced events (`no_rate_found`, `ambiguous_rate`, `unknown_agreement`) are rows with `amount_expected` NULL and a `reason`; settlement turns them into `data_quality`, never a partner variance. |
| **R8 Cut-off** | Event time in UTC plus the agreement's `cutoff_zone` and `cutoff_time`; `usage_date` already bucketed; never re-bucketed. |

**Minimum:** R1, R2, R3 and R6; R4, R5, R7, R8 can follow without changing settlement's shape, at the price of weaker evidence. If the
re-rating side cannot deliver, **settlement adapts**, not the re-rater. Today S3 reads a stand-in `rerated_usage` feed that implements
R1 to R8 as written.

### 4.2 What re-rating really ships (compared 2026-10-21)

| Requirement | `telco-rerating`'s `rerated_usage` (21 columns) | Match |
|---|---|---|
| R1 | one row per **call** (`ITEM_KEY`, `EVENT_TS`, `SUBSCRIBER_ID`, `PLAN`, `SERVICE`); no agreement, direction or destination class | **No** (retail per call) |
| R2 | `USAGE_UNITS`; a range `EXPECTED_LOW` / `EXPECTED_HIGH` plus `EXPECTED_CHARGE` when unambiguous; no currency, no count | **Partly** |
| R3 | rate card = the `tariff_version` Reference keyed by `TARIFF_VERSION_ID` (the open question answered as hoped); nothing maps a call to an agreement | **Half** |
| R4 | `TARIFF_VERSION_ID`, `CANDIDATE_VERSIONS`; rated at `EVENT_TS` | **Yes** |
| R5 | none; `rr_rerate` replaces a day's partition on re-run | **No** |
| R6 | `job.run.completed` of `rr_rerate` exists, window `$yesterday`, names no period; S3 does not consume it | **No** |
| R7 | `REASON` / `FINDING` (`no_tariff`, `ambiguous_tariff`, `unratable_call`, `data_quality`) | **In spirit**; names differ, blank amount unchecked |
| R8 | `EVENT_DATE` from the call; no cut-off zone | **No** |

So **no wholesale re-rater exists**. The open decision is PS-O1: who writes the adapter from per-call retail `rerated_usage` plus an
attribution Reference (trunk, route, PLMN, IMSI range → agreement) to the §4.1 aggregate, and whether it lives in either pack.
`rerating_findings` plays no part (a retail finding list). If settlement needs a **different re-rating** of the same xDRs rather than an
adapter, re-rating's gate **G-K(d)** opens (§5). Other readers of the contract: the parked
[5G / IoT assurance plan](../okf/product/solution-pack/industry-telecom/plan-5g-iot-assurance.md).

### 4.3 Other seams between the packs

- **Bypass B5 ↔ settlement:** the inbound-international versus own-network-terminated Measure on `ic_cdr` is the join point; no
  shared file.
- **Bypass B5 ↔ IRSF / Wangiri** ([parked plan](../okf/product/solution-pack/telco-fraud/plan-irsf-wangiri.md)): agree the boundary
  before B5; no CLI column, signal or Alert Rule defined in both. That plan shares D-BP7 and D-BP8.

## 5. Gates and protocol (all three packs)

- **Unit tests per slice:** `-pl <module> -Dtest=A,B` (commas, never `+`); `node tools/affected-modules.mjs` names the commands
  (it answers FULL for the bypass lane, because `inspecto` publishes a test-jar `inspecto-scoring` reads). Never `-pl X -am` alone as proof.
- **Mutation pass per slice:** every clause of new SQL gets a one-clause mutant that must turn a **named** assertion red; the probe is
  built so it would otherwise succeed (traps: a mutant red for the wrong reason; a negative test whose probe cannot succeed).
- **Golden corpus per pack:** fixed seed, fictional ids and ranges only, planted and benign sets disjoint, a look-alike AT every
  threshold, an independent plain-Java oracle. No client material or real call is ever read or copied.
- **Gate G-SB (bypass)**, any clause trips it and a Job Type design is opened instead: (a) the SIM-day stage misses the budget (a 5M-row
  day in 60 s on 4 cores, peak 2.5 GiB, no spill); (b) Alert Rule `by`, `stormCap` or Case grouping cannot express a box; (c) a stage's
  SQL cannot be tested in isolation; (d) a prospect names a signal outside S1 to S16. **Not tripped after B3**; re-check (a) and (c)
  at B4.
- **Gate G-K (re-rating)**, any clause opens R7: (a) the allowance stage misses the R0 budget (a 5M-row day through `rr_rerate` in 60 s
  on 4 threads, no spill, default cap); (b) a prospect names a tariff structure outside R4's closed vocabulary; (c) a stage's SQL cannot
  be pinned per clause; (d) settlement needs the same arithmetic (§4.2). **Not tripped after R3**; re-check after R4.
- **Guards locally before any push** (vocabulary, doc-links, doc-citations, doc-counts, product-map, offerings, openapi if a route
  changed): a green reactor is not green CI.

## 6. On ship

Each slice: add its as-built facts to the pack hub's `plan-as-built.md` and refresh the architecture page and guides, update the board
row, then remove the slice here. When a pack has no open slice left (or the rest is parked as demand-gated), move its remaining
demand-gated items to `docs/BACKLOG.md`, update the matrix row (E at B7 per D-BP10, C at R6 per D-RR10, Q at S8), drop its section.
When all three sections are gone, `git mv` this plan to `archived-documents/plans-archive/` and run `node tools/render-product-docs.mjs`.
