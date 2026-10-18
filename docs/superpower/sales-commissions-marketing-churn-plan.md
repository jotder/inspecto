# Sales, commissions, marketing and churn: a plan (`PACK-SALES-MARKETING-1`)

> 🟡 **PLAN 2026-10-18 (`PACK-SALES-MARKETING-1`, competitive gap V): design only, nothing built.** Gap **V** (sales,
> commissions, marketing, churn) is **N** in [`COMPETITIVE_CAPABILITY_MATRIX.md`](../okf/product/layer/product/brochure-competitive-matrix.md):
> LATRO and Mobileum sell it, Subex partly, Neural, TEOCO and Araxxe do not. The row `PACK-SALES-MARKETING-1` said "no design
> yet" and "depends on `PREDICTIVE-ANALYTICS-1`". **This plan finds that dependency is only half true:** three of the four
> lanes, and the deterministic half of the fourth, run on engines that already ship. Only a *probability* of churn, a
> fitted model and a forecast surfaced as a first-class **Forecast** need the not-yet-built prediction layer (§4).
> The one lane that traces to the MVNO RFP is **commissions**, and the RFP bid already scores it **Compliant** while its
> content is "to be built" (`RA-24`), so the plan builds it first (§9). Decisions **D-SM1…D-SM12 are open** (§11). Every
> claim about volume is a hypothesis until slice SM0 measures it.
>
> **Method.** Read on 2026-10-18: matrix rows V, P, J, L; the `PACK-SALES-MARKETING-1`, `PREDICTIVE-ANALYTICS-1` and
> `ANALYTICS-CORE-1` rows; [`predictive-analytics-design.md`](predictive-analytics-design.md) (decisions §9a);
> [`analytics-core-plan.md`](analytics-core-plan.md) S5; the `business-assurance` and `mobile-money` templates (the
> `mm_commission` Job, Alert Rule, KPI and Risk Score model); the Risk Score, Anomaly Score and Entity List concepts;
> `docs/GLOSSARY.md`; the two sibling plans [`rerating-tariff-validation-plan.md`](rerating-tariff-validation-plan.md) and
> [`partner-settlement-wholesale-plan.md`](partner-settlement-wholesale-plan.md) (house style); and the git-excluded MVNO RFP
> working set under `docs/superpower/rfp-mvno-assurance/` (`compliance-matrix.xlsx`, `gap-register.md`, `build-plan.md`,
> `assurance-content-catalog.xlsx`, `generator/rfp_rows.py`). ⚠ That working set is a **draft dated 2026-09-23/24**; the
> workstream statuses it carries were not all re-checked (§8 marks which were).

## 1. What exists today, and the gap

### 1.1 What the platform already does that this plan builds on

| Piece | What it really is | Evidence read |
|---|---|---|
| `mobile-money` Job `mm_commission` | **A flat-rate recomputation, per agent per day.** `sum(round(amount × rate, 2))` for `CASH_IN` and `CASH_OUT` against `sum(commission)` paid; two rates are Job parameters (`0.005`, `0.01`); `abs(paid − expected)` plus a `direction` of `OVERPAID` / `UNDERPAID` / `EXACT`. Alert Rule `mm_commission` `max(commission_variance) > 5` `by: agent_id`; KPI `mm_commission_variance`; the variance is a **factor of the `mm_agent` Risk Score** (weight 1, cap 30). | `spaces/_templates/mobile-money/config/jobs/mm_commission_job.toon`, `registry/alert-rules/mm_commission.toon`, `registry/risk-scores/mm_agent.toon` |
| `business-assurance` template | Holt-Winters additive forecast with a band in **DuckDB SQL** (`ba_revenue_forecast`), and margin erosion per product × channel × partner (`ba_margin_erosion`); five Alert Rules, three KPIs, one dashboard. Listed in `offerings/basic.toon` `contentPacks`, so in every edition. | `docs/okf/product/solution-pack/business-assurance/architecture.md` |
| Reconciliation add-on | Two or three Datasets compared at a key grain with `exact` / `absolute` / `percent` tolerance; Break lifecycle, dedupe, Incident promotion. | [`okf/product/module/reconciliation/architecture-overview.md`](../okf/product/module/reconciliation/architecture-overview.md) |
| Scoring & Lists add-on | **Risk Score** (weighted factors, each a Measure over a Dataset with a cap, 0–100, per-factor explanation, recomputable) and **Entity Lists** (`allow` / `block` / `watch` / `exclusion`, expiring entries, Parquet sidecar, `WatchListFeed.feedScored` from a score band). | [`okf/product/module/scoring/spec-risk-scores.md`](../okf/product/module/scoring/spec-risk-scores.md), [`entity-lists.md`](../okf/product/module/entity-list/spec.md) |
| Anomaly Detection add-on | Per-entity robust **self and Peer Group** baselines with seasonality, 0–100 score with a per-Feature explanation; statistical, not ML. | [`okf/product/module/anomaly/spec-anomaly-scores.md`](../okf/product/module/anomaly/spec-anomaly-scores.md) |
| Analytics Kernel (`ANALYTICS-CORE-1`, M1–M10 and Should batches built 2026-10-17) | Canonical statistics, time-series primitives and Steps (`resample`, `shift`, `forecast`), accuracy measures and a rolling-origin backtest, seasonality detection, a seeded holdout split, `transform.analytics.rank`, `transform.data.binning`, `transform.stats.correlation`, `transform.entity.link`. | [`okf/product/module/analytics/spec.md`](../okf/product/module/analytics/spec.md) |
| Predictive Analytics (`PREDICTIVE-ANALYTICS-1`) | **Design only.** One optional module `inspecto-predictive`, three component kinds (Segmentation Model, Churn Model, Forecast Model), declared-coefficient first slices, **no training in v1**. Open: OD-10 churn label, OD-11 forecast grain, OD-12, OD-13, OD-15, OD-16. | [`predictive-analytics-design.md`](predictive-analytics-design.md) §9a |
| The RFP catalogue | `RA-C20` *Dealer commission recomputation* (monthly, absolute 1 tolerance, "recomputed vs paid; commission on churned lines") and `FM-R09` *Dealer unauthorised activation / commission churn* (activation-to-churn < 30 days above a peer baseline) are **specified, not built**. | `assurance-content-catalog.xlsx` sheet `Controls` |

### 1.2 What is missing (the gap)

| # | Limit | Consequence |
|---|---|---|
| G1 | **No scheme.** `mm_commission` has two global rates and no effective dating, no tier, no accelerator, no cap, no qualification test. | A real dealer scheme (tiers by period volume, a minimum lifetime before a SIM qualifies, a clawback window) cannot be recomputed. `RA-24` says "commission recalculation Pipelines against scheme References": there is no scheme Reference. |
| G2 | **No clawback and no churn linkage.** | "Commission on churned lines" (`RA-C20`) and "churn after commission" (`T-16`) need the churn event of lane 4 or a deactivation feed. |
| G3 | **Variance is signed into one absolute number.** `abs(paid − expected)` plus a `direction` label; the Alert Rule watches `max(commission_variance)`. | Over-payment (a loss) and under-payment (a dealer dispute) are the same alert, and the two **net inside one agent-day** (`sum(commission_paid)` against `sum(commission_expected)`), so offsetting errors cancel. |
| G4 | **No sales facts.** No Dataset, KPI or Widget for funnel, activations, targets, ARPU or mix. | Lane 1 is empty; `business-assurance` sees revenue and margin, not who sold it. |
| G5 | **Measures are `{agg, field}` pairs** (parked-rows D10) with no window and no ratio. | ARPU, conversion, attainment %, churn rate and every "trailing 30 days as of a date" input must be computed **in Job SQL** and the Measure is a `sum` or `max` over the result. This is the same constraint the re-rating plan hit. |
| G6 | **No cohort, survival or churn definition anywhere.** A grep of `docs/okf`, `docs/stakeholders`, `docs/superpower` and the platform sources for survival, cohort retention and churn found only the predictive design. | Churn needs a declared definition (OD-10) before any number means anything. |
| G7 | **No campaign data model.** No touch, exposure, redemption or control-group Dataset. | Attribution, uptake and cannibalisation have nothing to read. Promotion **abuse** is a fraud typology (`T-20`, `FM-14`) planned in the FM pack, not here. |
| G8 | **No windowed entity Measure.** `PREDICTIVE-WINDOWED-MEASURES-1` is design only. | Churn inputs are written by a Job as Datasets (the same pattern Anomaly uses for Features) until it ships. |

## 2. Scope, and the vocabulary this plan holds to

`docs/GLOSSARY.md` is binding. This plan uses **Dataset** (never the banned synonym for a relation), **Measure** (never metric), **Incident**,
**Collector**, **Reference**, **Stream**, **Expectation** / **Alert Rule** / **Decision Rule** (never a bare *rule*),
**Pipeline**, **Space Template**, **Risk Score**, **Anomaly Score**, **Peer Group**, **Entity List**, **Prediction**,
**Customer Segment**, **Churn Model**, **Churn Score**, **Forecast Model**, **Forecast**. Three traps this plan keeps clear:

- A **Churn Score** is the Instance of a **Churn Model** (the Predictive add-on). The early-warning number lane 4 ships
  *without* the module is a **Risk Score** (model id `churn_early_warning`), and says so on screen. It is never
  called a Churn Score, and it is not a probability.
- A commission **scheme** is not a *rule*. The authored artefact is a **Reference** (effective-dated rows).
- **Segment** alone is banned for a customer group: always **Customer Segment**. An acquisition **cohort** is not yet a
  glossary term (the glossary uses *cohort* informally under **Peer Group**); slice SM0 adds an entry (D-SM9).

**Scope boundary.** In: assessing and explaining sales, commissions, marketing and churn from Datasets. **Out, always:**
executing campaigns or sending offers, paying commissions or holding a payout in the payer's system (an **Action Request**
to an external system is possible, its target API is a customer dependency), real-time next-best-action (the
`REALTIME-DECISIONING-1` row), CRM write-back, customer lifetime value extrapolation, uplift modelling (S14 of the
predictive design is a Won't). Out of v1 for lane 4: any model the platform trains (OD-6).

## 3. The four lanes

### 3.1 Lane 1: sales performance

**Data.** `sales_activation` (Stream; one row per activation or order line: `ACTIVATION_ID`, `ICCID`, `MSISDN`, `DEALER_ID`,
`AGENT_ID`, `CHANNEL`, `REGION`, `PRODUCT`, `ACTIVATED_AT`), optional `sales_lead` (Stream; funnel stages with timestamps),
`sales_target` (**Reference**: period, `CHANNEL`, `REGION`, `AGENT_ID`, `TARGET_UNITS`, `TARGET_REVENUE`, effective-dated),
`dealer_hierarchy` (Reference: dealer, parent, tier, region), `product` (Reference), `subscriber_revenue_monthly` (Stream or
Job-written Dataset: per subscriber per month `REVENUE`, for ARPU).

**What it computes (Job SQL, because of G5).**

| Output Dataset | Content |
|---|---|
| `sales_funnel_daily` | per `CHANNEL`, `REGION`, `AGENT_ID`, day: leads, orders, provisioned, activated, first-recharge; step conversion; median days between stages. Funnel stages that the customer does not feed are **absent**, not zero (a missing stage never becomes 0 % conversion). |
| `sales_attainment` | per period and dimension: actual, target, `ATTAINMENT_PCT`, `PACE_PCT` (attainment divided by the elapsed share of the period) and a forecast-to-period-end with a band (the kernel forecast, seasonal-naive first) |
| `sales_mix_arpu` | per period, `PRODUCT`, `CHANNEL`: gross adds, ARPU (revenue ÷ active subscribers, the denominator declared once), mix share, mix and rate effect against the previous period (a deterministic decomposition: volume, mix, rate sum to the change) |
| `agent_productivity` | per `AGENT_ID` per week: activations, share of the Peer Group (same region and tier) median; feeds an **Anomaly Model** with `peerBy: REGION`, so an underperforming or a suddenly collapsing agent is scored with a per-Feature explanation |

**Measures and KPIs.** `gross_adds` (`sum(activations)`), `sales_revenue`, `attainment_pct` (`max` over the Job output),
`pace_pct`, `arpu`. KPIs `sales_attainment`, `sales_pace`, `arpu`. ⚠ A KPI's target and band shape was **not** re-read for
this plan; SM0 confirms what a KPI definition can carry today against `WS-20` (the RFP's "KPI catalogue with target,
bands, variance"), before any slice promises a banded tile.

**Alert Rules.** `sales_pace_below` (`min(pace_pct)` below a configured floor, `by: REGION` or `CHANNEL`), `sales_funnel_stall`
(a stage conversion below its own trailing baseline, an Anomaly Score on the funnel grain rather than a fixed threshold).

### 3.2 Lane 2: commissions (the revenue-assurance lane)

The RFP framing is **independent re-computation versus payout** (`RA-24`: "commission recalculation Pipelines against scheme
References, compared with paid amounts"). Independence is the point: the expected amount comes from the scheme and the
activation facts, never from the payer's own figure.

**Data.**

| Name | Kind | Grain and key columns |
|---|---|---|
| `commission_scheme` | **Reference**, effective-dated (`load: scd2` or one row per version, as in the re-rating plan R0) | `SCHEME_ID`, `SCHEME_VERSION_ID`, `DEALER_TIER`, `CHANNEL`, `PRODUCT`, `MIN_LIFETIME_DAYS`, `MIN_FIRST_RECHARGE`, `BASE_AMOUNT` or `BASE_RATE`, `TIER_FROM_UNITS`, `ACCELERATOR_RATE`, `ACCELERATOR_MODE` (`marginal` / `retroactive`), `CAP_PER_PERIOD`, `CLAWBACK_WINDOW_DAYS`, `CLAWBACK_PCT`, `EFFECTIVE_FROM`, `EFFECTIVE_TO`, `PRIORITY` |
| `sales_activation` | Stream (shared with lane 1) | as §3.1, plus `KYC_OK`, `FIRST_RECHARGE_AMOUNT`, `FIRST_RECHARGE_AT`, `ACTIVE_DAYS_FIRST_N` |
| `line_deactivation` | Stream (or lane 4's `churn_event`) | `ICCID`, `DEACTIVATED_AT`, `REASON` |
| `commission_payout` | Stream (the payer's `F-11` dealer-platform extract) | `PAYOUT_ID`, `DEALER_ID`, `PERIOD`, `LINE_TYPE` (`base` / `accelerator` / `clawback` / `adjustment`), `AMOUNT`, `ICCID` where per line |
| `dealer_hierarchy` | Reference | as §3.1 |

**Stages (a chain of `sql.template` Jobs, as the re-rating plan chains them).**

```
cm_qualify ─► cm_compute ─► cm_explain ─► cm_root_cause ─► Alert Rule by ROOT_CAUSE_ID ─► Incident ─► Case (dispute)
                  └──────► cm_compute_period ─► cm_vs_paid (recon.run) ─► Break lifecycle
cm_gaming_signals ─► dealer Risk Score ─► Entity List (watch) ─► Alert Rule by DEALER_ID
```

1. **`cm_qualify`**: each activation joined to the scheme version **in force at `ACTIVATED_AT`** (half-open interval); it
   qualifies when lifetime, first recharge and KYC conditions hold. Output `qualified`, `not_qualified_reason`, the
   `SCHEME_VERSION_ID` used and `CANDIDATES` (ambiguity handled as in re-rating plan §6.2: all candidates equal means
   `agree`; different means an expected **range**, and the claim is made only outside it).
2. **`cm_compute`**: expected commission per dealer and period: base, **accelerator** (tier decided by the period's
   qualifying volume per dealer; `marginal` pays the higher rate only above the threshold, `retroactive` re-prices the whole
   period, and the choice is an explicit scheme column, never inferred), **cap**, and **clawbacks**: a qualified line
   deactivated inside `CLAWBACK_WINDOW_DAYS` produces a negative line in the period when the deactivation is **observed**.
3. **`cm_vs_paid`**: a `reconciliation` component, left `commission_expected_period`, right `commission_payout` summed, key
   `DEALER_ID, PERIOD`, `includeRecordCount: false` (many lines face one payout row, the reason `ra_rated_vs_billed` needs it),
   absolute and percent tolerance as configuration; gives the Break lifecycle and Incident promotion for free.
4. **`cm_explain`**: for each break a **counterfactual**: recompute the expected amount with one parameter changed and keep
   the hypotheses that reproduce the paid figure within tolerance: `stale_scheme_version`, `accelerator_ignored`,
   `accelerator_mode_swapped`, `cap_ignored`, `clawback_not_applied`, `clawback_outside_window`, `paid_not_qualified`,
   `qualified_not_paid`, `duplicate_payout`, `rounding`, `unexplained`. Output `CAUSE`, `CAUSE_CONFIDENCE` (exactness ×
   support, the re-rating plan §6.3 formulation, all constants configuration), `CAUSE_ALTERNATIVE`. Wording is
   **"consistent with"**: a counterfactual shows a cause is sufficient, not unique.
5. **`cm_root_cause`**: group by `(CAUSE, SCHEME_VERSION_ID, DEALER_TIER, CHANNEL)` into a stable `ROOT_CAUSE_ID` with
   dealer count, first and last period and amount; an Alert Rule `by: ROOT_CAUSE_ID` (one Incident per cause, updated, not
   duplicated; confirm the high-cardinality `by` and `stormCap` in SM0 exactly as re-rating plan §6.5 does).

> ⚠ **Sign convention, do not port from re-rating.** In re-rating, *under*-billing is the leakage. Here **over-payment is
> the loss** (money out) and **under-payment is a dealer-dispute and relationship exposure**. Both are measured and
> never netted: `OVERPAID_AMOUNT` and `UNDERPAID_AMOUNT` are separate columns and Measures (`mm_commission` nets them
> inside a dealer-day today, G3), and only over-payment feeds a "money at risk" figure (D-SM4).

**Controls: SIM-activation fraud and dealer gaming (deterministic first).** All read Datasets this lane already holds; each is
a column or a Dataset, none is a model.

| Control | Test | RFP link |
|---|---|---|
| `commission_churn_burst` | share of a dealer's qualified activations that deactivate inside N days, against the dealer's **Peer Group** (tier and region) median; scored by an Anomaly Model with per-Feature explanation | `FM-R09`, `T-16`, `FM-10` |
| `recycled_sim` | the same `ICCID` or `IMEI` qualifying more than once, or across dealers, inside a window | `T-16`, `FM-10` |
| `zero_usage_activation` | activations with no first recharge and no billed usage in the first N days that were still commissioned | `T-12`, `FM-08` |
| `activation_burst` | activations per dealer per hour or day against its own seasonal baseline and Peer Group (velocity in SQL, the route `WS-14` productises) | `T-12`, `FM-08` |
| `tier_hugging` | a dealer's period volume clusters just above an accelerator threshold, found by `transform.data.binning` around each `TIER_FROM_UNITS` against the population | `FM-10` |
| `self_activation` | the customer is the dealer, the agent or a linked identity (entity grouping, `transform.entity.link`, then Link Analysis for the cluster) | `FM-07`, `FM-08`, `FM-25` |
| `activation_without_stock` | an activation whose `ICCID` has no stock movement to that dealer | `RA-C16`, `RA-17`, `RA-18` |

The signals are factors of a **dealer Risk Score** (the `mm_agent` model is the pattern: factor, Dataset, key, Measure,
weight, cap). A dealer above `highThreshold` goes to an **Entity List** (`watch`) through `WatchListFeed.feedScored`, and the
list drives an Alert Rule `by: DEALER_ID`. The plan makes **no** claim that this *prevents* a payout; holding it is an Action
Request to the dealer platform (§2, §10 risk 6).

**Dispute flow.** A dealer disputes a statement: the Break (open, assigned, resolved, `auto_closed`) is promoted to an
**Incident** (`POST /recon/promote`), grouped into a **Case** by a Case Rule on `DEALER_ID, PERIOD`, run under a Workflow and
SLA Policy (priority from amount and dealer tier). The closing **Disposition** classes (`scheme error`, `payer error`,
`dealer error`, `fraud`) are carried in the Incident; **no write to an impact ledger**: the financial-impact ledger of the RFP
(`G-03`, `WS-10`) is not what this plan's slices depend on, so the amount lives in the Incident description, as the
re-rating plan decided.

### 3.3 Lane 3: marketing

**Data.** `campaign` (Reference: `CAMPAIGN_ID`, `OFFER_ID`, `CHANNEL`, `START`, `END`, `COST`, `ELIGIBILITY` text or key),
`campaign_touch` (Stream: `CAMPAIGN_ID`, `SUBSCRIBER_ID`, `SENT_AT`, `CHANNEL`, `HOLDOUT` flag), `offer_redemption`
(Stream: `OFFER_ID`, `SUBSCRIBER_ID`, `REDEEMED_AT`, `PRICE`, `PREVIOUS_PLAN`), `subscriber_revenue_monthly` (as lane 1),
`cost_allocation` (Reference: acquisition, subsidy, commission; reuses lane 2's paid commission).

| Capability | What it is (deterministic) | What it is not |
|---|---|---|
| **Attribution** | Declared model per campaign: `last_touch` or `first_touch` within a window of N days, or `linear`. One SQL per model, the model and window stored with each row. | **Not causal.** Attribution assigns credit; it does not show the offer changed behaviour. The UI says "attributed", never "caused". |
| **Incremental lift** | Only where a `HOLDOUT` group exists: `rate(treated) − rate(holdout)` with a normal-approximation interval, `insufficient` below a minimum group size. | No lift without a holdout. The pack does **not** assign holdouts (it does not run campaigns); the seeded holdout split of the kernel can produce an **exclusion Entity List** for the operator's campaign tool (Could, D-SM10). |
| **Offer uptake** | Redemptions ÷ touched, by Customer Segment (declared RFM bins when the Predictive add-on exists, else a declared tenure or plan band). | |
| **Cannibalisation** | A difference-in-differences: ARPU change of offer takers over the pre/post window minus the ARPU change of a matched non-taker comparison (same plan and tenure band). The cannibalised amount is the share of the takers' ARPU drop not seen in the comparison, in money, per offer. | Not a counterfactual model; a stated estimate with its comparison group and sizes shown. |
| **Promotion abuse** | **Owned by the fraud pack.** Eligibility breaches and multi-account redemption are `FM-R12` / `T-20` / `FM-14` in `WS-15`. Lane 3 adds only the marketing-economics cut: **redeem-then-churn** (redeemers who deactivate inside N days after the benefit ended) and cost of benefits given to ineligible redeemers, read from the fraud pack's output (D-SM8). | Does not duplicate the typology detector. |
| **Cohort ROI** | Acquisition cohort (month, campaign or channel): cumulative revenue − (campaign cost + commission + subsidy) per cohort over tenure; **payback month** = first tenure month the cumulative margin is ≥ 0; ROI = cumulative margin ÷ cost. Cohort machinery is lane 4's `cohort_lifetime` (§3.4), built once. | Margin follows the `business-assurance` margin definition (`BA-K01`); a customer-specific cost allocation is configuration. |

### 3.4 Lane 4: churn

**Churn definition (the real work, OD-10).** A **declared** `churn_definition` (configuration, versioned): a churn is of a
type, either `explicit` (a termination or port-out event) or `inactivity` (no chargeable activity for N days; prepaid
default 90, a Job parameter). Two facts the SQL must respect and the golden pins: (a) an inactivity churn is **known only N
days after** the last activity, so every score or backtest as of date *t* uses only churn events *known* by *t* (the leakage
guard of the predictive design §4.3); (b) a reactivation inside the window **cancels** the churn. Output Dataset
`churn_event` (`SUBSCRIBER_ID`, `CHURN_DATE`, `KNOWN_ON`, `TYPE`: `voluntary` / `involuntary` / `port_out`, `DEFINITION_ID`).

**Cohorts and survival (deterministic SQL, no prediction).** `cohort_lifetime`: per acquisition cohort and tenure bucket
(week or month) `AT_RISK`, `CHURNED`, `CENSORED`, hazard = churned ÷ at-risk, **survival** = the running product of
(1 − hazard) (the life-table form of Kaplan-Meier, computed as `exp(sum(ln(1 − hazard)))` over a window), right-censored
subscribers leave the at-risk count without counting as churn. This is **descriptive statistics on core DuckDB**, it is not
a Prediction, and it needs nothing the platform lacks. Extrapolating a curve past the observed tenure (expected remaining
lifetime, lifetime value) **is** prediction and is out of v1.

**Early-warning scoring, explainable per input (two honest tiers).**

| Tier | What it is | Ships when |
|---|---|---|
| **Tier A: early-warning Risk Score** `churn_early_warning` | A Job writes per-subscriber input Datasets as of a date (G8): usage change against the subscriber's own trailing baseline, days since last recharge or top-up, balance trend, tenure band, contract-end proximity, port-out request flag, complaint count, bill-shock flag. A **Risk Score** model takes each as a factor `{Dataset, key, Measure, weight, cap}`; the explanation is the per-factor contribution, recomputable. A usage-collapse input may itself be an **Anomaly Score** `_latest` (`measure: max(score)`), the documented Risk Score factor source. | Slice CH2, on engines that ship. **A 0–100 number, not a probability**; weights are authored. |
| **Tier B: Churn Score** | The **Churn Model** kind of the Predictive add-on: authored coefficients, `100 × sigmoid(b0 + Σ bᵢxᵢ)`, contributions `bᵢxᵢ`, `insufficient` under minimum history, stored backtest (AUC and lift in SQL), drift. | Needs `PREDICTIVE-CHURN-DECLARED-1`, `PREDICTIVE-WINDOWED-MEASURES-1`, and a validation slice (`PREDICTIVE-VALIDATE-DRIFT-1`); gated (§9). |

Tier A is validated without the module by a **score-outcome table** (`churn_score_outcomes`): the High-band subscribers as
of a past date joined to the churn events known 30, 60 and 90 days later, giving the band's observed churn rate, precision
and the share of all churn it caught. That is a hit-rate table, not AUC; AUC and a lift curve stay with the predictive
validation slice rather than being rebuilt here (S6 of the design).

**Win-back lists via Entity Lists.** A Job selects churned subscribers by `TYPE = voluntary`, value above a threshold,
inside the win-back window, **minus** the do-not-contact list. The list is an **Entity List** with an **expiry** per
entry (the win-back window), the do-not-contact list is an `exclusion` list used by `excludeBy`. Keys are masked on read
like scores. ⚠ **Open:** Entity List purposes are `allow / block / watch / exclusion` (fraud-flavoured). `watch` for a
win-back target is a stretch of the word, and writing members from a Job is a **new writer**, which needs an
operator-approved write-funnel inventory row (the same constraint as the `afterScore` writer in the predictive design).
SM0 decides whether the existing `WatchListFeed.feedScored` path (score band to list) is enough (D-SM7). Delivery to a
campaign tool is Integration & Delivery or an Action Request, never this pack.

**Value at risk.** Two Measures, kept apart because one needs a probability and one does not:
`churn_revenue_exposed` = `sum(trailing_arpu × horizon_months)` over the High band (pure exposure, no probability), and
`churn_value_at_risk_expected` = the same, multiplied by the band's **empirical** observed churn rate from
`churn_score_outcomes` (declared, never a model output). Arithmetic is in the Job; the Measure is a `sum` (G5). ⚠ Vocabulary:
the re-rating plan already uses "revenue-at-risk" for *leakage*; this plan names the churn figure **Churn Value at Risk** and
adds a glossary entry so the two never share a word (D-SM9).

### 3.5 What the shipped engines express, and what needs the prediction layer

| Need | Deterministic on shipped engines? | Evidence and limit |
|---|---|---|
| Funnel, attainment, mix and ARPU decomposition | **Yes**, Job SQL | Ratios live in the Job (G5). |
| Forecast to period end with a band | **Yes, with limits** | Kernel forecast and the `business-assurance` SQL exist; as a first-class **Forecast** with stored backtest it is `PREDICTIVE-FORECAST-1` (design only). |
| Commission recomputation, accelerators, caps, clawbacks | **Yes** | Window functions and effective-dated joins, the pattern of `ra_rerating`. Throughput of the stateful accelerator stage is unmeasured (SM0). |
| Break lifecycle, dispute Case, SLA | **Yes** | Reconciliation, Incidents, Case Management, Workflow & SLA. |
| Peer-baseline dealer and agent anomalies | **Yes** | Anomaly Detection, statistical. |
| Churn definition, cohorts, survival | **Yes** | Core DuckDB; descriptive. |
| Early-warning ranking with per-input explanation | **Yes (Tier A)** | Risk Score, authored weights, **not a probability**. |
| Churn **probability**, calibration, backtest AUC and lift | **No** | `inspecto-predictive` (unbuilt): declared-coefficient logistic, then S6 validation. |
| Fitted coefficients, k-means Customer Segments | **No** | The platform does not train in v1 (OD-6); a Could slice, separately decided. |
| Attribution (credit) | **Yes** | Declared window, deterministic. |
| Incremental lift, cannibalisation | **Yes, as stated estimates** | Needs a holdout or a comparison group; the pack states group sizes. |
| Uplift modelling, next-best-action, LTV extrapolation | **No, and out** | Won't, per the predictive design. |

## 4. Data model, Measures, Alert Rules and delivery

### 4.1 Datasets, References, Streams

Streams in: `sales_activation`, `sales_lead`, `line_deactivation`, `commission_payout`, `campaign_touch`, `offer_redemption`,
`subscriber_revenue_monthly`, `churn_signals` (per-subscriber daily usage and recharge, the largest input). References:
`commission_scheme`, `sales_target`, `dealer_hierarchy`, `product`, `campaign`, `cost_allocation`, `churn_definition`.
Job-written Datasets: `sales_funnel_daily`, `sales_attainment`, `sales_mix_arpu`, `agent_productivity`, `cm_qualified`,
`cm_expected`, `cm_findings`, `cm_root_causes`, `cm_vs_paid` (Reconciliation), `dealer_signals`, `campaign_attribution`,
`campaign_lift`, `offer_cannibalisation`, `cohort_lifetime`, `cohort_roi`, `churn_event`, `churn_inputs_<date>`,
`churn_score_outcomes`, `winback_candidates`. Zero-row `schema-seed.parquet` seeds for every Dataset an Alert Rule or KPI
reads (the `telco-ra` pattern). `SUBSCRIBER_ID`, `MSISDN`, `ICCID`, `DEALER_ID`: synthetic and classified `INTERNAL` in the
corpus; a classified column may not become a Customer Segment or cohort label (the `requireUnclassifiedCohort` gate).

### 4.2 Measures and KPIs

`gross_adds`, `attainment_pct`, `pace_pct`, `arpu`, `commission_expected`, `commission_paid`, `overpaid_amount`,
`underpaid_amount`, `commission_variance_pct`, `dealer_churn_share`, `campaign_roi`, `payback_month`, `survival_90d`,
`churn_rate_30d`, `high_risk_subscribers`, `churn_revenue_exposed`, `churn_value_at_risk_expected`. KPIs (names): `cm_overpaid`,
`cm_underpaid`, `sales_attainment`, `arpu`, `survival_90d`, `churn_value_at_risk`. Every ratio is a Job column first.

### 4.3 Expectations and Alert Rules

Expectations (per record, on the Streams): `commission_payout.AMOUNT` non-null, `LINE_TYPE` in the closed list,
`sales_activation.ICCID` unique per `ACTIVATED_AT`, `commission_scheme` rows with `EFFECTIVE_FROM < EFFECTIVE_TO`, non-negative
rates. **Overlap or gap between scheme rows is a relation, not a record property, so it stays in SQL** (the re-rating plan §3
finding). Alert Rules: `cm_overpaid` (CRITICAL, `by: ROOT_CAUSE_ID`), `cm_underpaid` (WARNING, `by: DEALER_ID`),
`cm_dealer_watch` (`by: DEALER_ID`), `sales_pace_below`, `sales_funnel_stall`, `cohort_survival_drop`, `churn_high_band_growth`
(`min` growth against own baseline), `campaign_negative_roi`.

### 4.4 Delivery: Space Templates, packs and edition placement

| Template | Lane | Kind | Edition |
|---|---|---|---|
| `telco-commission` | 2 | content pack, **telecom** side (`kind: solution-pack`, `enforcement: build`, like `telco-ra`), because scheme vocabulary is telecom | `minEdition: standard` (Reconciliation, Alert Rules, scoring), `contentPacks` beside `telco-ra` |
| `sales-performance` | 1 | **function pack**, domain-neutral columns, with a telecom overlay for ARPU | `standard`; the funnel and attainment parts could ride `basic` beside `business-assurance` (D-SM11) |
| `marketing-economics` | 3 | function pack | `standard` |
| `churn-retention` | 4 | function pack; Tier A needs `scoring` and `entity-list` (both in Standard) | `standard`; Tier B adds the `predictiveAnalytics` add-on |

**Config only for lanes 1, 2, 3 and Tier A: no new Step Processor, no new Job Type, no new module.** Tier B is the Predictive
add-on's own slices; this plan adds none of its code. Edition vocabulary is Basic / Standard / Advanced / Preview. ⚠ The
predictive design says the add-on is "Professional and up"; read that as **Standard and up** (the old names are retired) and
confirm at scheduling. Only `basic.toon` and `standard.toon` list `contentPacks`: verify at CM5 how Advanced inherits
before claiming it (the same caveat as the re-rating plan §7). A compliance sign-off (OD-14, consent and automated-decision
obligations of the PDPL class) gates any **customer-facing** template of lanes 3 and 4, not the engine.

## 5. Should `PACK-SALES-MARKETING-1` split? Yes, into three

One row with four lanes cannot be built, scheduled or sold as one thing: the lanes have different buyers (revenue-assurance
analyst, sales operations, marketing, retention), different RFP standing (only lane 2 has RFP lines), and different
dependencies (lane 2 and 3 need no Predictive module; lane 4's Tier B does). **Recommended rows (filed only when the operator
signs D-SM1):**

1. **`PACK-COMMISSION-ASSURANCE-1`**: lane 2 (`telco-commission`), the RFP-facing row and the first built.
2. **`PACK-SALES-MARKETING-1`** (kept, narrowed): lanes 1 and 3 (`sales-performance`, `marketing-economics`), demand-gated.
3. **`PACK-CHURN-1`**: lane 4 (`churn-retention`), Tier A first, Tier B behind `PREDICTIVE-*`.

The cohort kernel (`cohort_lifetime`) is written once in the churn row and read by marketing; to avoid two lanes forking it
the plan **publishes one contract**: the Dataset `cohort_lifetime` with a stable column list (§3.4).

## 6. Phased slices

Per `CLAUDE.md`: **unit tests per slice** (`-pl <module> -Dtest=A,B`, commas, never `+`); the full reactor gate only at handoff
or before a push touching shared seams; `-pl` without `-am` tests the stale sibling jar, so rebuild what the slice touched.
Each slice ends with a **mutation pass** (one mutant per new SQL clause that must turn a named assertion red, with a probe
that would otherwise **succeed**). The golden tests sit beside `MobileMoneyPackGoldenTest` in `features/inspecto-reconciliation`
and the scoring golden beside `MobileMoneyRiskScoreGoldenTest` in `features/inspecto-scoring`; ⚠ the reconciliation module
holding `TelcoRaGoldenTest` was a documentation discrepancy in the re-rating plan, so SM0 confirms the owning module before a
`-pl` command is written. **Synthetic fixtures only, fixed seed, ids like `DLR-001`, `SUB-000001`, `ICC-…`: no client material,
no real data** (`no-data-or-client-files-in-commits`).

| Slice | Delivers | Done when (verifiable) | Tests (unit level) |
|---|---|---|---|
| **SM0 spike and definitions** | Facts, no shipped config, plus glossary entries (**Cohort**, **Churn Value at Risk**, **Win-back List** if kept, **Churn Definition**). Five measurements: (1) a KPI definition's target and band shape against `WS-20`; (2) Alert Rule `by` on a high-cardinality column and `stormCap`; (3) staged-SQL throughput: the accelerator window over 2M qualified activations, and per-subscriber windowed inputs over 5M subscribers × 90 days, with a memory ceiling; (4) a Risk Score model of 10 factors over per-subscriber Datasets: save gate, run time, the `maxEntities` cap; (5) whether a Job may write Entity List members (the new-writer inventory row) or `WatchListFeed.feedScored` suffices. | A measured table in §13 with budgets; each answer recorded; glossary entries landed and the vocabulary guard green; no repository change except this plan and the glossary. | Throwaway harness, numbers only. |
| **CM1 scheme and qualify** | Template skeleton `telco-commission`; `commission_scheme` Reference; Expectations; `cm_qualify`; `CommissionCorpus` (fixed seed) planting: a stale scheme version, an overlap with the **same** terms and one with **different** terms, a gap, a SIM below `MIN_LIFETIME_DAYS`, no first recharge, a `KYC_OK` false, a scheme change **between** activation and period end. | Golden asserts **exactly** the planted set per stage: the same-terms overlap yields one scheme finding and **0** per-line findings; the different-terms overlap an expected range and a data-quality row, not an over-payment; the boundary look-alikes (a SIM exactly at `MIN_LIFETIME_DAYS`, a one-cent rounding) stay silent. | `TelcoCommissionGoldenTest`, `CommissionCorpusTest` (deterministic; planted and benign sets disjoint), SQL-clause mutation pass. |
| **CM2 compute and reconcile** | `cm_compute` (base, accelerator `marginal` and `retroactive`, cap), `cm_compute_period`, `cm_vs_paid` Reconciliation, `OVERPAID_AMOUNT` / `UNDERPAID_AMOUNT` separate. | Planted over-paid, under-paid, unpaid-qualified, paid-not-qualified and duplicate-payout dealers each land in the right class; an over-payment on dealer A and an under-payment on dealer B **do not net**, **nor within one dealer**; a dealer exactly on a tier boundary is priced by the declared mode; `includeRecordCount: false` verified; the same corpus under **two host time zones** gives identical results (DuckDB's session `TimeZone` is the host's). | golden extension; `AcceleratorModeTest`; a two-zone test. |
| **CM3 explain, root cause, clawback** | `cm_explain`, `cm_root_cause`, clawbacks and `clawback_outside_window`; Alert Rule by `ROOT_CAUSE_ID`; KPIs. | Every planted break gets its planted `CAUSE` at or above the confidence floor; an unplanted break is `unexplained` with confidence 0; two hypotheses that both fit are both kept; three dealers hit by one stale scheme form **one** root cause with a stable id across two runs; a clawback exactly on the window end is handled by the declared (inclusive or exclusive) rule and a look-alike one day later is silent. | `CommissionExplainTest`, two-run stability test, golden extension. |
| **CM4 gaming controls** | The seven controls of §3.2, the dealer Risk Score model, an Anomaly Model with `peerBy` tier-and-region, the Entity List feed. | Planted gaming dealers (churn burst, recycled SIM, tier-hugging, self-activation, no-stock activation) are scored over `highThreshold` with the planted per-factor contributions and **recompute equals stored**; the benign look-alikes stay silent: a legitimately large dealer in a large region (the Peer Group normalises it), a seasonal campaign-period spike; one watch-list entry per dealer. | `DealerRiskScoreGoldenTest`, `DealerAnomalyGoldenTest`, `recompute` equality test. |
| **CM5 dispute, package, RFP evidence** | Break promote to Incident, Case Rule on `DEALER_ID, PERIOD`, Workflow and SLA entries, dashboards and Widgets, runbook, template and offering entries, `product-map` item, RFP evidence note (§8). | One Incident per root cause on the golden and a **second period's** run updates it; the gallery marks `creatable` per edition; vocabulary, doc-link, openapi (if a route changed) and product-map guards pass **locally** before any push (a green reactor is not green CI). | golden; `CommissionAlertTest`; the CI guards run locally. |
| **SP1 sales facts, funnel, attainment** | `sales-performance` skeleton, `sales_funnel_daily`, `sales_attainment`, Measures, KPIs, Alert Rule `sales_pace_below`. | A planted channel misses its pace and is the only one flagged; a missing funnel stage is absent, not 0 %; a target changed mid-period uses the version in force. | golden, `SalesCorpus`. |
| **SP2 mix, ARPU, variance, forecast to target** | `sales_mix_arpu` with the volume / mix / rate decomposition, forecast-to-period-end with a band. | The three effects sum to the total change exactly; the forecast band covers the planted holdout at the declared level; a series under two seasons is `insufficient`. | `MixDecompositionTest`; backtest assertion using the kernel accuracy measures. |
| **SP3 agent productivity** | `agent_productivity`, an Anomaly Model on it, package. | A planted collapsing agent scores high with the per-Feature explanation; a new agent (short history) is `insufficient`, not 0. | golden. |
| **CH1 churn definition, event, cohorts, survival** | `churn_definition` Reference, `churn_event`, `cohort_lifetime` (the published contract), `survival_90d`. | Cohorts with a **known planted hazard** reproduce the closed-form survival to the stated tolerance; right-censoring is not counted as churn; a reactivation inside the window cancels; a prepaid line inactive 89 days is silent and at 91 days is churn dated with `KNOWN_ON`; no score input ever reads an event with `KNOWN_ON` after its as-of date. | `ChurnCorpus`, `SurvivalGoldenTest`, `LeakageGuardTest`. |
| **CH2 early-warning Risk Score and outcomes** | `churn_inputs_<date>` Job, the `churn_early_warning` Risk Score, `churn_score_outcomes`, alert `churn_high_band_growth`. | Planted risk profiles score in their planted order with per-factor contributions; recompute equals stored; a subscriber with too little history is `insufficient`; the outcomes table gives the planted band hit rates at 30, 60 and 90 days; the screen labels it "not a probability". | golden; `recompute` test; outcomes test. |
| **CH3 win-back list and value at risk** | `winback_candidates`, the Entity List and exclusion list (or the feed, per SM0), `churn_revenue_exposed`, `churn_value_at_risk_expected`. | A do-not-contact subscriber never reaches the list; entries expire at the window end; exposure equals the planted sum and the expected figure equals exposure × the band's empirical rate; keys are masked on read. | golden; list-expiry test. |
| **MK1 cohort ROI** | `cohort_roi` over `cohort_lifetime` and `cost_allocation`. | Planted cohorts give the planted payback month; a cohort that never pays back has no payback month (empty, not 0); commission cost matches lane 2's paid total. | golden. |
| **MK2 attribution, lift, uptake** | `campaign_attribution`, `campaign_lift`, uptake by band. | The three declared models credit the planted touches; a campaign with no holdout has **no** lift row; a holdout below the minimum size is `insufficient`; `last_touch` outside the window earns no credit. | golden, `LiftIntervalTest`. |
| **MK3 cannibalisation** | `offer_cannibalisation` (difference-in-differences). | The planted cannibalised amount is recovered within tolerance; an offer taken only by new subscribers (no pre period) is `not_assessable`; comparison group size shown. | golden. |
| **MK4 promotion economics** | redeem-then-churn and ineligible-benefit cost, **read from the fraud pack**; no detector copy. | Counts equal the planted set; if the fraud pack is absent the slice is skipped, not faked (D-SM8). | golden. |
| **CH4 Churn Score (gated)** | Not a slice of this plan's code: the pack consumes `PREDICTIVE-CHURN-DECLARED-1` once built and re-points the early-warning model. | Predictive slices S1, S3, S6 shipped; OD-10 answered. | n/a here. |

Order and parallelism: SM0 first; **CM1 → CM2 → CM3 → CM4 → CM5 sequential** (each extends the corpus and the golden); lane 1 and
lane 4 share `sales_activation` and `cohort_lifetime` and are independent of lane 2's files, so **SP1 and CH1 can run beside
CM3** if the corpus generators are split per lane (disjoint files); MK1 waits on CH1; MK2 and MK3 are independent of lane 4.
Nothing here edits `mobile-money`, `telco-ra`, `telco-fraud` or `business-assurance`, so it cannot collide with the
re-rating, partner-settlement, SIM-box, IRSF or 5G/IoT plans.

## 7. Existing assets this plan deliberately leaves alone

`mm_commission` stays as the lightweight mobile-money starter and a factor of the `mm_agent` Risk Score; `telco-commission`
is a new template (D-SM2), for the reason the re-rating plan gave for `telco-rerating`: counts and goldens are pinned in
other tests and docs, and two lanes in one template and one golden is the failure the shared-tree memories record.
`business-assurance` is read, not edited, and its margin definition is reused. Promotion abuse detection stays in `telco-fraud`.

## 8. Traceability to the MVNO RFP working set

References are IDs only (the working set is git-excluded client material, never linked or quoted; IDs are matrix lines `XX-nn`,
gaps `G-nn`, workstreams `WS-nn`, catalogue controls `RA-Cnn` / `FM-Rnn`, typologies `T-nn`, KPI groups `K-xx-n`, feeds `F-nn`).
Workstream status as checked 2026-10-18: `WS-12` (Entity Lists) and `WS-22` (Risk Score) are **shipped** per their OKF
concepts; the status of `WS-10`, `WS-14`, `WS-15`, `WS-16`, `WS-18`, `WS-20`, `WS-28` was **not** re-checked here (the build-plan is
a 2026-09-24 draft). The matrix `Today` column for `FM-09` ("Unsupported") predates the Risk Score; correct it when the bid
is revisited.

### 8.1 Slice to RFP lines

| Slice | Matrix lines | Gap IDs | Workstreams | Effect |
|---|---|---|---|---|
| CM1, CM2, CM3 | `RA-24` (commission, incentive and dealer-payment validation); `RA-14` (dealer-wallet balances, commissions and adjustments); catalogue `RA-C20` and `RA-C14` | `G-09` | `WS-16` | **Satisfies `RA-24`'s "content to be built"**: the catalogue control `RA-C20` becomes the deep version below; `RA-14` gains its commission leg |
| CM2, CM5 | `RA-33` (KPI baseline: dealer-wallet, channel); KPI group `K-RA-3` ("dealer-wallet variance"); reports `RPT-07` (commission findings) | `G-09`, `G-11` | `WS-16`, `WS-20` | **Moves**: supplies the commission variance KPI and the `RPT-07` findings; the KPI band shape depends on `WS-20` |
| CM4 | `FM-10` (dealer / agent / channel fraud incl. unauthorized activations and commission / incentive abuse); `FM-08` (suspicious activations); `FM-09` (risk scoring at dealer and channel level); `FM-07` and `FM-25` (relationship analysis and link analysis, `self_activation`); typologies `T-15` (dealer collusion), `T-16` (commission / incentive abuse), `T-12` (activation bursts); catalogue `FM-R09`, `FM-R08` | `G-08`, `G-13`, `G-07` | `WS-15`, `WS-22`, `WS-14` | **Moves**: the commission-abuse typology `T-16` / `FM-R09` is built here in SQL (velocity as SQL, per the Step Processor hold); `FM-09` at dealer level is met by the shipped Risk Score |
| CM4 | `RA-18` and `RA-17` (SIM stock), catalogue `RA-C16` (`activation_without_stock`) | `G-09`, `G-27` | `WS-16` | **Moves**: one more consumer of the SIM-stock roll-forward; does not build it |
| CM5 | `FM-23` and `FM-24` (alert handling, case management) for the dispute path; `FM-20` (prioritise by financial exposure) | `G-14`, `G-03`, `G-13` | `WS-23`, `WS-10`, `WS-22` | **Moves** `FM-20` only as far as the Incident carries the amount; full exposure ranking needs the ledger (`WS-10`) |
| SP1, SP2 | `BA-04` (executive KPIs, trends, variance, forecasting, drill-down); `BA-01` (margin and product / bundle performance across channels, dealers and partners) | `G-11`, `G-19` | `WS-20`, `WS-28` | **Moves**: channel / dealer attainment and ARPU mix are inputs to `BA-01`; forecast-to-target feeds `BA-04`'s forecasting; neither reaches Full without `WS-20` / `WS-28` |
| SP3, CH2 | `AI-01` (anomaly detection, risk scoring), `FM-22` (explainable scores) | `G-13` | `WS-22` | **Moves**: per-Feature and per-factor explanations on sales and churn |
| MK1 | `BA-01`; KPI `BA-K01` (gross margin); report `RPT-18` (product and channel profitability) | `G-19` | `WS-28` | **Moves**: acquisition-cohort margin and payback; not a revenue-recognition model |
| MK2, MK3, MK4 | `FM-14` (promotion abuse), `FM-12` (promotional-credit fraud), typology `T-20`, catalogue `FM-R12`; `RA-11` (validate promotions before and after) | `G-08`, `G-34` | `WS-15`, `WS-16` | **Moves `FM-14` only** through the redeem-then-churn economics (the detector is `WS-15`); `RA-11` is **not** moved: pre/post-launch validation is a catalogue-snapshot procedure |
| CH1, CH2, CH3 | **None.** No RFP line is about churn, sales performance, marketing attribution or win-back (the 189 lines, the KPI baseline and typology sheets were searched for churn, sales, marketing, campaign, ARPU, retention and win-back; churn appears only as an **input** to `T-16`). | none | none | Value is `BA` depth and a secondary input to `T-16`; **not an RFP deliverable** |
| Feeds | `F-07` (provisioning: orders and activations), `F-11` (dealer platform: wallets, commissions), `F-06` (CRM), `F-08` (product catalogue) | none | none | The inventory has **no commission-scheme feed**: the scheme is authored by the customer as a Reference. Recommend adding it to the inventory |

### 8.2 RFP lines this plan cannot satisfy, and why

| Line | Why not |
|---|---|
| `RT-20` (block unauthorized activation, duplicate renewal) | Needs enforcement in the VAS or provisioning platform (`G-01`, partner `WS-30`); this plan **detects**. A payout hold is an Action Request whose target API is a customer dependency (`G-15`, `WS-24`). |
| `RT-17` (balance-affecting transactions, real time) | Post-event only (`G-01`). |
| `FM-18`, `AI-05`, `GEN-09`, `FM-33` (ML scoring, predictive and ML capability, model drift KPIs) | `G-21` / `WS-31` (roadmap). **Declared-coefficient scoring is not ML training**; the early-warning Risk Score and the Tier B Churn Score must be described that way. Drift KPIs wait for the Predictive drift slice. |
| `FM-09` at **transaction** level | The Risk Score is per entity, not per transaction. |
| `RA-11` | Procedure-driven pre-launch harness (`G-34`); promotions are not test-called. |
| `BA-02` (consolidated leakage and recovery exposure) | Needs the financial-impact ledger (`G-03`, `WS-10`); commission over-payment is carried in the Incident only. |
| `BA-03` (IFRS 15 analysis) | Out of scope. |
| Lines for churn, sales performance, marketing | **No line exists**; the plan cannot "satisfy" what the RFP does not ask, and must not be presented as RFP coverage. |

## 9. Build order and the demand gate

**Recommended order:** SM0 → lane 2 (CM1…CM5) → lane 4 deterministic half (CH1…CH3) with lane 1 beside it (SP1…SP3) → lane 3
(MK1…MK4) → the prediction-dependent parts (CH4, Forecast Model surfacing, Customer Segment bands) only on gate G-P.

Why commissions first, although the backlog row is P3: it is the **only** lane with an RFP line that is already scored
**Compliant** on a "content to be built" basis (`RA-24`, `RA-14`, `T-16`), and the RFP catalogue budgets commission recomputation
inside `WS-16` Controls wave 2 as **one** control among twelve in about 3 to 5 engineer-weeks. This plan is a much deeper control
(accelerators, clawbacks, fraud, disputes); ⚠ a rough, **unmeasured** size is 5 to 7 engineer-weeks for CM1…CM5 beyond that stub.
If the bid is lost or deferred, none of the build is wasted: the lane stands as a product pack.

**Demand gates (a pack with no prospect is a demo).**

- **G-R (lane 2):** the RFP bid proceeds (the working set is "not on the board until the bid decision", operator 2026-09-23), **or** a prospect
  names commission validation, **or** the operator schedules it. Until then the plan is ready, nothing is built.
- **G-D (lanes 1, 3, 4 deterministic):** a prospect confirms the feed (activations, dealer hierarchy, targets; for lane 4 a
  subscriber status or usage history long enough for a cohort: at least two tenure windows), **or** the operator wants the
  synthetic demo. Without either, build SM0 and stop.
- **G-P (prediction-dependent):** `PREDICTIVE-ANALYTICS-1` scheduled, OD-10 (churn label) and OD-14 (compliance sign-off for
  customer-facing use) answered. Until then Tier A is the honest product and is labelled "not a probability".

## 10. Risks

1. **Churn definition differs per customer** and is the real work; the plan makes it configuration with a leakage guard rather
   than guessing (OD-10).
2. **Overclaiming:** attribution is not causality; a counterfactual explains sufficiency, not cause; a Risk Score is not a
   probability. Mitigation: "attributed", "consistent with", "not a probability", and group sizes on every estimate.
3. **Scheme is a document, not a feed.** The dominant error source in commission validation is the scheme itself; CM1 starts
   with scheme hygiene (overlap, gap, bad rate), as re-rating starts with tariff hygiene.
4. **Over/under-payment sign and netting** (§3.2): porting the re-rating sign would invert the loss.
5. **Privacy and profiling:** segmenting and scoring people carries consent and automated-decision obligations (OD-14); win-back
   targeting is marketing use of personal data. Compliance sign-off gates lane 3 and lane 4 customer-facing templates.
6. **"Prevents payout" overclaim:** the pack detects and raises Incidents; it never holds a payment.
7. **Volume unknown.** Nothing is measured above golden-corpus size; per-subscriber windowed inputs are the expensive
   statement (SM0 measures before any promise).
8. **New writers:** a Job writing Entity List members, and the `afterScore` pending Alert Rule writer, each need an
   operator-approved write-funnel inventory row; plan around the existing feed if declined.
9. **Time zone and cycle boundaries:** DuckDB's session `TimeZone` is the host's, not UTC; every period and tenure boundary uses
   an explicit zone and the golden runs under two zones.
10. **Two lanes colliding:** sibling plans meet this one only at `cohort_lifetime` (published here) and the fraud pack's
    promotion outputs (read-only); any other shared file is a collision.
11. **Vocabulary:** Churn Score vs early-warning Risk Score; Churn Value at Risk vs re-rating's revenue at risk; Customer Segment
    never *Segment*; Measure never *metric*; Collector never *source*.
12. **Matrix honesty:** V moves from **N** to **P** only when a lane ships and **Y** is not claimed until Tier B and the churn
    backtest ship; the matrix is edited only then.

## 11. Open decisions for the operator

| ID | Decision | Recommendation |
|---|---|---|
| **D-SM1** | Split `PACK-SALES-MARKETING-1` into three rows (§5)? | **Yes**: commission assurance, sales and marketing, churn. |
| **D-SM2** | Home of commissions: new `telco-commission` template, or extend `mobile-money`'s `mm_commission`? | **New template**; keep `mm_commission` as the starter. |
| **D-SM3** | Priority: raise the commission row above P3 because the bid scores `RA-24` Compliant? | **Yes if the bid proceeds** (gate G-R); otherwise stay P3. |
| **D-SM4** | Over-payment (money out) and under-payment (dealer dispute) as separate classes, never netted; only over-payment feeds a "money at risk" figure. | **Yes.** |
| **D-SM5** | Churn definition: inactivity N days (prepaid default 90), explicit termination, both (OD-10). | **Both**, inactivity as the zero-configuration default, configuration versioned. |
| **D-SM6** | Tier A early-warning Risk Score now, or wait for the Churn Model? | **Tier A now**, labelled "not a probability", Tier B when `PREDICTIVE-*` is scheduled. |
| **D-SM7** | Win-back list: reuse `watch` and `WatchListFeed.feedScored`, add an Entity List purpose, or approve a Job-to-list writer? | **Reuse the feed**; ask for a writer only if SM0 shows it is not enough. |
| **D-SM8** | Promotion abuse: owned by the fraud pack (`WS-15`), marketing reads its output only. | **Confirm.** |
| **D-SM9** | Glossary: add **Cohort**, **Churn Definition**, **Churn Value at Risk**; keep it apart from re-rating's revenue at risk. | **Yes**, in SM0. |
| **D-SM10** | Holdout assignment from the kernel's seeded split as an exclusion list for the operator's campaign tool. | **Could**, not v1; the pack assesses, it does not run campaigns. |
| **D-SM11** | Edition: `standard` for all four; may the funnel and attainment part ride `basic` beside `business-assurance`? | **Standard**; revisit `basic` after SP1. |
| **D-SM12** | Add a commission-scheme feed to the RFP interface inventory and re-score the matrix `Today` for `FM-09`. | **Yes**, a bid-document edit, outside this plan's files. |

## 12. Backlog and docs touched

- `BACKLOG.md`: the `PACK-SALES-MARKETING-1` row text is updated to say the plan exists, the dependency is half true, the
  split is recommended (D-SM1) and the demand gates. **No new rows** until D-SM1 is signed; the count markers are unchanged.
- `INDEX.md`: this plan's row in the in-flight table.
- On ship (per lane): distil as-built facts into the matching OKF concept (a new subsection beside `spaces.md` §3.5 for each
  pack), add a solution page under `stakeholders/solutions/`, update matrix row V (**N** → **P** at the first lane), then
  `git mv` this plan to `archived-documents/plans-archive/` when the last lane ships.

## References

- Matrix: [`okf/product/layer/product/brochure-competitive-matrix.md`](../okf/product/layer/product/brochure-competitive-matrix.md) rows P, J, L, V.
- Predictive design: [`predictive-analytics-design.md`](predictive-analytics-design.md); Analytics Kernel:
  [`okf/product/module/analytics/spec.md`](../okf/product/module/analytics/spec.md), [`analytics-core-plan.md`](analytics-core-plan.md).
- Sibling plans (house style, contracts): [`rerating-tariff-validation-plan.md`](rerating-tariff-validation-plan.md),
  [`partner-settlement-wholesale-plan.md`](partner-settlement-wholesale-plan.md).
- Business assurance: [`okf/product/solution-pack/business-assurance/architecture.md`](../okf/product/solution-pack/business-assurance/architecture.md).
- Scoring and lists: [`okf/product/module/scoring/spec-risk-scores.md`](../okf/product/module/scoring/spec-risk-scores.md),
  [`anomaly-scores.md`](../okf/product/module/anomaly/spec-anomaly-scores.md), [`entity-lists.md`](../okf/product/module/entity-list/spec.md).
- Reconciliation module: [`okf/product/module/reconciliation/architecture-overview.md`](../okf/product/module/reconciliation/architecture-overview.md).
- Canonical vocabulary: [`GLOSSARY.md`](../GLOSSARY.md) (the single source of truth).

## 13. Measurements (filled by slice SM0)

_Empty until SM0 runs: KPI target and band shape, Alert Rule `by` cardinality and `stormCap`, accelerator and per-subscriber
windowed-input throughput and memory, Risk Score factor limits, the Entity List writer decision, the owning test module._
