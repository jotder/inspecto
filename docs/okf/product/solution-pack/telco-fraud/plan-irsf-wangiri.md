# Interconnect / wholesale fraud from CDRs (IRSF, Wangiri, flash-call / CLI): plan

> ⏸ **PARKED 2026-10-22 — design only, demand-gated; not in flight.** To resume: move it back to docs/superpower/ and rank its BACKLOG row.
>
> *Status before parking:* 🟡 **PLAN 2026-10-18 (`PACK-IRSF-WANGIRI-1`): design only, nothing built.** Competitive gap **H** (interconnect / wholesale fraud:
> Wangiri, IRSF, flash calls, CLI) is **Partial** in [`COMPETITIVE_CAPABILITY_MATRIX.md`](../../layer/product/brochure-competitive-matrix.md):
> two per-subscriber detection windows exist in the `telco-fraud` Space Template and nothing at the **trunk, partner or
> destination** level, where wholesale fraud is actually priced. This plan is **CDR-based only**: no signalling, no test calls,
> no inline blocking (those are matrix rows F, G and I). It is written to be **RFP-compatible** (§12): every slice maps to the
> MVNO RFP compliance-matrix lines, gap IDs and workstreams it satisfies or moves, and the RFP lines CDRs cannot satisfy are named
> with the reason. Decisions **D-IW1…D-IW10 answered 2026-10-20** (§14). Every volume or latency claim is a hypothesis until slice IW0 measures it.
>
> **Method.** Read the matrix rows D, E, H, I; the `telco-fraud` template (Jobs, Alert Rules, KPI, runbook, Anomaly Model, Schemas, sample
> CDR); `okf/product/module/config/plan-spaces.md` §3.5; the Anomaly Scores and Entity Lists concepts; `docs/GLOSSARY.md`; the two sibling plans
> (re-rating, partner settlement); and the RFP working set (`compliance-matrix.xlsx`, `gap-register.md`, `build-plan.md`,
> `assurance-content-catalog.xlsx`). Every "exists" statement cites a file read on 2026-10-18.

## 1. What exists today, and the gap

### 1.1 What `telco-fraud` already detects

Two `sql.template` Jobs, each writing one row per candidate entity per `window_date` (`incremental: by day, lookback 1, retain 31`, cron `0 2 * * *`):

| Piece | What it really does | Evidence |
|---|---|---|
| `fraud_irsf` | Normalises `b_number` to E.164 digits, keeps **originated (`MO`) voice** whose number starts with **any** entry of the Job parameter `irsf_prefixes` (default `882,883,8818`), and sums calls and seconds **per `a_number`** for the day. | `spaces/_templates/telco-fraud/config/jobs/fraud_irsf_job.toon` |
| `fraud_irsf` Alert Rule | `max(irsf_seconds) gt 3600`, `by msisdn`, `stormCap 500`, CRITICAL, runbook `fraud_irsf`; KPI `irsf_exposure_seconds` = `sum(irsf_seconds)`. | `registry/alert-rules/fraud_irsf.toon`, `registry/kpis/irsf_exposure_seconds.toon` |
| `fraud_premium_rate` | Same shape over `premium_prefixes` (default `999900,999909`), but sums the CDR `charge` per caller; `gt 100`, WARNING. | `jobs/fraud_premium_rate_job.toon`, `alert-rules/fraud_premium_rate.toon` |
| `fraud_wangiri` | Counts **ring targets** (MT calls of at most `max_ring_seconds` = 3) and the subset that **called the ringing number back** within `callback_hours` = 24, per **ringing (A) number**. The Alert Rule is on `max(callback_targets) gt 3`, so a pure ring burst with no callback never alerts, and a flash-call OTP sender stays silent by design. | `jobs/fraud_wangiri_job.toon`, `alert-rules/fraud_wangiri.toon`; behaviour note in `okf/product/module/config/plan-spaces.md` §3.5 |
| `tf_subscriber_usage` Anomaly Model | Daily outgoing voice seconds, SMS and data per `a_number` against the line's own last 28 days, `seasonality: weekday`, `minBaselinePoints 3`; its Alert Rule is created after the first run (pending). | `registry/anomaly-models/tf_subscriber_usage.toon` |
| Golden test | `TelcoFraudTemplateGoldenTest` with fixed-seed `TelcoFraudCorpus` (seed `20260930`, fictional country code `999`, foreign callers `287` / `289`): planted offenders one above each threshold, look-alikes at each threshold, a flash-call OTP sender and a registered PBX line silent. | `okf/product/module/config/plan-spaces.md` §3.5 |
| Runbooks and Incident flow | Runbook components linked from each Alert Rule; known false positives named as "no defence here" (a legitimate satellite-phone user). Per-offender Alert Rules (one Incident per offender) are built; Cases are made by hand or by a Case Rule. | `registry/runbooks/fraud_irsf.toon` |

### 1.2 The gap

| # | Limit, read from the Jobs and the CDR Schema | Consequence |
|---|---|---|
| G1 | The destination list is **a typed Job parameter**, matched with "any prefix" (`starts_with`). No origin, tier, expiry, owner or review date; no longest-prefix override. | Nobody can say where a prefix came from or whether it is stale; a risky country prefix cannot carry a safe sub-range; no update cadence exists. |
| G2 | The CDR Schema has **ten columns** (`record_id, start_ts, direction, service, a_number, b_number, duration_s, charge, roaming, cell_id`): **no trunk, partner, route, release cause, ring time or presented CLI**. (`telco-ra`'s `switch_xdr` carries a `PARTNER` column; `telco-fraud`'s `cdr` does not.) | Detection is per subscriber only. The interconnect level (per trunk, partner, destination range) and the wholesale cost exposure cannot be computed. |
| G3 | The unit is **seconds per day**, one fixed threshold (3600 s), no cost, no time-of-day, no baseline. | A heavy legitimate caller and a fraud ramp look the same; a burst that runs for two hours at night is diluted into a day. The KPI is seconds, not money. |
| G4 | No **new-destination** feature, no **spend velocity**, no **revenue-share indicator**. | The tell-tale IRSF shape (a line that never called this range before, now calling it for hours) is invisible. |
| G5 | Wangiri alerts only on **callback victims** per ringing number; no ring-burst rate, no inbound-from-international filter, no **many-to-one** call-back view, no **number-range** roll-up. | A campaign spread over many A-numbers from one range, or one B-number collecting call-backs, is missed. |
| G6 | The Anomaly Model is **day-bucket, subscriber-keyed**; `hour` is refused (`okf/product/module/anomaly/spec-anomaly-scores.md`: *bucket: day, 'hour' refused: not built*). It baselines the line, not the destination, trunk or partner. | No seasonality for a destination prefix or a partner; no intraday spike. |
| G7 | Alerts and exemptions are **per subscriber**; exemptions are static parameters trusted as given (`exempt_msisdns`; backlog `ASSURE-PACK-TELCO-FRAUD-RESIDUALS-1` (h)). | No watch / allow / block Entity List fed by detection; false-positive control is a parameter edit. |
| G8 | The "act" step of the runbook is **prose** ("bar international calls", "ask the partner to withhold settlement"). No recommended action travels with the Incident; no Impact is stamped. | Triage is manual, exposure is not on the Incident, FP / TP is not typed. |

## 2. What this plan delivers

A **CDR-only interconnect-fraud layer** built from configuration over engines that exist: staged `sql.template` Jobs, Reference
Datasets, Anomaly Models, Entity Lists, Alert Rules, Case Rules, runbooks, Impact. **No new Step Processor** (hold on new Steps is
in force for velocity / baseline Steps, build-plan `D-16`; SQL windows are the recorded form) and **no new Job Type** in v1.
Eight capabilities, each a Dataset or a column, deterministic and recomputable (the standard of
[`anomaly-scores.md`](../../module/anomaly/spec-anomaly-scores.md): every verdict stores the numbers it came from):

| # | Capability | What it is | Closes |
|---|---|---|---|
| C1 | **Destination risk Reference** | One effective-dated Reference, longest-prefix matched, with class, tier, cost, provenance and a review date. | G1 |
| C2 | **Interconnect-aware CDR** | A Schema v2 adds optional trunk, partner, release-cause, ring-time and presented-CLI columns; absent columns degrade the feature, never fail the run. | G2 |
| C3 | **IRSF subscriber features** | Spike, off-hours, new destination, short-call burst, spend velocity, revenue-share indicators, as a per-line daily feature Dataset with a transparent score. | G3, G4 |
| C4 | **Interconnect roll-up and cost exposure** | Per trunk, partner and destination range; `exposure_cost` as the money Measure. | G2, G3 |
| C5 | **Wangiri campaign features** | Ring bursts, callback pattern, many-to-one call-back, A-number range roll-up. | G5 |
| C6 | **Seasonal baselines** | Anomaly Models over destination and partner series (weekday seasonality); an hour-of-day profile in Job SQL. | G6 |
| C7 | **Lists and false-positive control** | Watch / allow / block Entity Lists, exemption by Reference, confidence tiers, tier-gated severity. | G7 |
| C8 | **Incident to Case flow with a recommended action** | One Incident per offender, a Case per campaign, suspected Impact, a CDR-only **recommend-block Consequence**; never autonomous. | G8 |

What it does **not** mean: no real-time or pre-call blocking (`REALTIME-DECISIONING-1`, matrix row I); no signalling analysis, test
calls or probes (rows F, G); no trained model (no ML runtime; RFP `FM-18` stays roadmap); no authoritative claim of **spoofing**
(§9: CDRs show suspicious *patterns*, never proof of a forged CLI).

## 3. Scope boundary

- **In:** voice (and, where the CDR carries it, SMS) records; outbound (`MO`) IRSF and premium-rate, inbound (`MT`) Wangiri and
  flash-call patterns, interconnect traffic labelled by trunk or partner **when the feed carries it**.
- **Out, v1:** signalling and SS7 / Diameter evidence, CLI **verification**, OTT and A2P grey routes (`FM-R13`), SIM-box and bypass
  (sibling lane `TELCO-BYPASS-SIMBOX-1`; shared features are reused, not rebuilt), roaming (`telco-fraud` `fraud_roaming`), payment
  and recharge fraud, any write to a switch, HLR or billing system.
- **Interface with the sibling plans.** The wholesale **cost per minute** comes from the partner / interconnect rate Reference of
  [`partner-settlement-wholesale-plan.md`](../../../../archived-documents/plans-archive/partner-settlement-wholesale-plan.md) (`ic_rates` today: `PARTNER`, `RATE_PER_MIN`, not effective-dated)
  and, when it ships, its effective-dated agreement; this plan **reads** it and never edits it. The retail `rerated_usage` contract of
  [`rerating-tariff-validation-plan.md`](../../../../archived-documents/plans-archive/rerating-tariff-validation-plan.md) is the retail revenue side; exposure here is the **wholesale payable**. Cases and
  disputes with a partner belong to the settlement plan; this plan raises the **fraud** Incident and hands the evidence over.

## 4. High-risk destination and premium-range reference data

### 4.1 One Reference, longest-prefix matched

`destination_risk` (a **Reference**, effective-dated, replacing the typed `irsf_prefixes` / `premium_prefixes` Job parameters):

| Column | Meaning |
|---|---|
| `PREFIX` | Digits only, 1 to 15, E.164-normalised (the existing normaliser; an entry that is not 1 to 15 digits **fails the run**, as today). |
| `DEST_CLASS` | Closed vocabulary: `premium_rate`, `shared_intl` (international shared-revenue and satellite networks), `high_risk_country`, `overlay_area_code`, `home_premium` (the home regulator's premium ranges), `normal` (an explicit safe sub-range). |
| `RISK_TIER` | `1` (block-candidate) … `3` (watch). Tier drives severity (§8.3). |
| `COUNTRY` | ISO 3166 alpha-2, for roll-ups. |
| `COST_PER_MIN` | Optional wholesale cost override; else the partner rate is read from the rate Reference. |
| `ORIGIN_KIND` | `own_observed`, `regulator`, `itu`, `commercial_import`, `analyst`. |
| `PROVENANCE` | Free text: document, date, analyst. Mandatory. |
| `VALID_FROM`, `VALID_TO`, `REVIEW_BY` | Effective dating and the date after which an entry is flagged stale. |

Match is **longest prefix wins**, so a risky country entry can carry a more specific `normal` sub-range (today's "any prefix" cannot).
The Reference is **a data origin, not an Entity List**: a prefix is a *range*, and Entity Lists hold typed entity keys (a range block is
supported there as a number block, `okf/product/module/entity-list/spec.md`, but dimension data with class, cost and review dates is a Reference).

### 4.2 Provenance, licensing and the seed that ships

- **Nothing real is committed.** Per the repository rule (no data or client files in commits), the template's seed is **synthetic**:
  the fictional numbering already used by the corpus (home country code `999`, foreign `287` / `289`, premium `999900` / `999909`) plus one
  entry per `DEST_CLASS`, each with `ORIGIN_KIND = analyst` and `PROVENANCE = synthetic fixture`.
- **Licensing-free sources an operator can load themselves** (the pack documents the recipe, ships none of the content): the operator's
  **own observed ranges** (the highest-value origin: derived from its own confirmed Incidents, §8.5); the **home regulator's numbering plan**
  and its published premium-rate designations; **ITU E.164 assigned codes** (country codes, the shared international networks); published
  overlay area-code lists from regulators' consumer alerts. ⚠ Their terms of use must be read by the operator before import; the plan does
  not assert any licence. **Commercial range feeds** (IPRN / high-risk-number lists) are licensed and arrive as `commercial_import` through
  a **Collector** into the same Reference; the pack never bundles them (D-IW3).
- **Update cadence.** Own observed ranges: reviewed at every Incident closure and weekly. Regulator and ITU: on publication, checked
  monthly. Commercial feed: per supplier (daily to weekly). Every entry carries `REVIEW_BY`; an **Expectation** over the Reference fails
  the nightly run's data-quality check for an entry past `REVIEW_BY`, with an overlap, a `VALID_TO` before `VALID_FROM`, or an empty
  `PROVENANCE`.
- **Change control.** Edits to the Reference are ordinary Reference loads; a **permanent** `RISK_TIER 1` entry is the kind of change the
  maker-checker lane (`G-06`, `WS-13`) is meant to hold for a second person (D-IW6).

## 5. Detection features

All windows are `sql.template` Jobs over the `cdr` Dataset and `destination_risk`, staged through Job-written Datasets (the recorded
layering, [`okf/product/module/config/plan-spaces.md`](../../module/config/plan-spaces.md) §3.5), partition-incremental like the existing Jobs. Names below are
proposals. **Every threshold is configuration**, in the Job parameters or the Alert Rule, as today.

### 5.1 IRSF (outbound, per subscriber, per day and per hour window)

Feature Dataset `iw_irsf_line` (one row per `msisdn` and `window_date`), computed over `MO` voice whose normalised number matches a
`destination_risk` entry in a risky class, **billing minutes = CEIL(seconds / 60)** (same convention the settlement control uses):

| Feature | Definition | Why it indicts |
|---|---|---|
| `risky_minutes`, `risky_calls` | Totals to risky classes. | The base volume. |
| `exposure_cost` | Σ billing minutes × wholesale cost per minute for the matched partner or entry (§6.2). | Money, not seconds; drives severity and the KPI. |
| `spike_ratio` | Today's risky minutes over the line's own trailing median for the same weekday (the Anomaly Score machinery when keyed on a Dataset, otherwise a SQL median over the retained days). | A ramp against the line's own history. |
| `off_hours_share` | Share of risky minutes outside the line's local day (configured band, host-independent zone, D-IW9). | IRSF is often run overnight. |
| `new_dest_flag`, `new_dest_minutes` | The line never called this prefix class in the previous `lookback` days. | The classic "first-time destination, then hours of it". |
| `short_burst_calls` | Calls shorter than `short_s` to risky prefixes in a 10-minute window, peak count. | Number-testing before the main run. |
| `max_hour_minutes` | Peak risky minutes in any single hour. | Intraday intensity a daily total hides. |
| `spend_velocity` | Exposure cost per hour over the hours the line was active that day, and the day-over-day change. | Wholesale cost accrues by the hour. |
| Revenue-share indicators | `dur_cv` (coefficient of variation of call durations: IRSF calls to a revenue-share number cluster at long, near-equal durations), `concurrent_peak` (maximum simultaneous risky calls from one line), `few_dest_share` (share of risky minutes to the single top destination number). | **Indicators of a revenue-sharing pattern, never a claim**: CDRs do not show who is paid. Labelled "consistent with" in the explanation. |
| `irsf_score` | A transparent weighted sum of normalised features (weights are configuration); the explanation lists each feature, its value and its contribution. | Explainable, as RFP `FM-22` requires; not a trained model. |

Alert Rule `iw_irsf_line` over `max(irsf_score)` or `max(exposure_cost)` (one threshold each, §8.3), `by msisdn`.

### 5.2 Per-trunk, per-partner and per-destination roll-up

Roll-up Dataset `iw_irsf_interconnect` (one row per `window_date`, `partner_or_trunk`, `dest_prefix`) with minutes, calls, distinct callers,
`exposure_cost`, and the same spike and new-destination features at that grain. Requires the C2 Schema columns (`out_trunk` or `partner`);
**without them** the pack keeps only the destination-prefix roll-up (grain `dest_prefix`) and says so on the dashboard. Alert Rule
`iw_irsf_partner` `by partner_or_trunk` (or `dest_prefix`), so a flood across many lines to one range raises **one** Incident for the
range, not hundreds of per-line Incidents (the `stormCap` problem the existing pack documents).

### 5.3 Wangiri (inbound `MT`, per ringing number and per number range)

Feature Dataset `iw_wangiri_caller` (one row per ringing `a_number` and `window_date`), over inbound voice whose route is international
**when `in_trunk` is present** (else by number format):

| Feature | Definition |
|---|---|
| `ring_calls`, `ring_targets` | Inbound calls with `duration_s` at most `max_ring_seconds` **or** release cause "no answer" **or** ring time at most `max_ring_s`; distinct called numbers. |
| `ring_burst_peak` | Maximum ring calls from one A-number in any 10-minute window. A campaign is a burst, not a day total. |
| `callback_targets`, `callback_calls` | Existing semantics: ring targets that called the A-number back within `callback_hours`. Kept as the **confirmation** feature. |
| `callback_cost` | Cost of the call-backs to that number (the victims' outbound minutes priced at wholesale). |
| `range_id` | The A-number's leading `range_len` digits (configuration), for the range roll-up. |

Range roll-up `iw_wangiri_range` (`window_date`, `range_id`): distinct A-numbers ringing, total ring targets, total call-backs.
**Many-to-one call-back** `iw_callback_funnel` (`window_date`, `dest_number`): distinct subscribers who called the **same** number
within `callback_hours` after a short ring from it, and that number's inbound ring volume. Few victims to one destination is
noise; hundreds is a campaign whose call-backs terminate on a revenue-share line. Alert Rules: `iw_wangiri_range` and
`iw_callback_funnel` (confirmed campaign, WARNING to CRITICAL by victim count and `callback_cost`), `by range_id` or `by dest_number`;
the existing per-ringing-number rule stays as the per-offender view.

### 5.4 Flash call and CLI abuse, **only what CDRs support**

A flash call (a very short call used as an OTP channel) is **legitimate when sent by an OTP provider** and abusive when it is a
campaign (rings at scale, no answer). From CDRs alone:

- **Pattern, not proof.** Feature `flash_pattern` = ring-only calls from one A-number at scale **with no call-back** and a fixed, tiny
  duration: the **inverse of Wangiri** (nobody calls back). It is reported as `suspect_flash_traffic` with INFO or WARNING and the
  explanation "consistent with flash-call OTP traffic (verification cost shifted to the terminating operator)"; it is an
  **interconnect cost** question (who is terminating it and at what rate), not a fraud verdict.
- **CLI refiling / spoof proxies** (needs `in_trunk` and the presented CLI): an **international inbound** call whose presented A-number
  carries the **home country code** (a domestic CLI on an international trunk), or one A-number arriving on **two different partners**
  within minutes. Reported as `cli_inconsistency` with confidence LOW to MEDIUM.
- **Cannot be done from CDRs:** deciding that a CLI was forged, identifying the real origin, verifying STIR / SHAKEN-style attestation,
  or seeing SS7 / SIP signalling. That is RFP `T-08` (spoofing): partner (`WS-30`), stated as a limit in §12.2.

## 6. Interconnect partner roll-up and cost exposure Measure

### 6.1 Measures

Measures stay `{agg, field}` pairs (parked rows, D10 in the re-rating plan), so the arithmetic lives in the Job: Dataset columns
`exposure_cost`, `risky_minutes`, `run_rate_per_hour`, `callback_cost`; then `sum(exposure_cost)` etc. are ordinary **Measures** feeding the KPIs
(`irsf_exposure_cost`, `wangiri_callback_cost`, `suspected_interconnect_exposure`), replacing the seconds-only `irsf_exposure_seconds`
(the old KPI is kept as the volume view, D-IW8).

### 6.2 Where the cost comes from

In priority order: the entry's `COST_PER_MIN`; else the partner rate from the interconnect rate Reference
(`ic_rates`, or the effective-dated agreement once the settlement plan ships); else the CDR `charge` marked **retail, not cost** and
the exposure column flagged `COST_BASIS = retail` (a conservative proxy, never silently mixed). A row with no basis reports minutes only.
Currency is one ISO 4217 code per Space (D-IW7), converted by the same FX Reference the settlement plan introduces, never inline.

### 6.3 Exposure is **suspected**, not loss

The Incident's Impact is stamped **suspected** with the exposure at the time of detection; **confirmed**, **recovered** and **prevented**
are set by the analyst at closure (the Impact model, `WS-10`). The plan never writes confirmed loss automatically
(the same stay-manual stance as `ASSURE-PACK-TELCO-RA-1`).

## 7. Thresholds, seasonality and Anomaly Models

1. **Static floors stay**, as configuration, per class and tier (the Alert Rule `threshold`): a floor protects against a quiet baseline.
2. **Self-baseline per subscriber** reuses the shipped `tf_subscriber_usage` mechanism but over the **risky-class Dataset**: a new Anomaly
   Model `iw_irsf_line_usage`, **feature = `risky_minutes`** from `iw_irsf_line`, `bucket: day`, `window: 28`, `seasonality: weekday`,
   `minBaselinePoints 3`, with a `watchList` and an `exclusionList` naming Entity Lists (C7). Because Features take any Dataset, the model
   runs over the Job-written Dataset and the CDR's prefix match is not needed in a filter (to confirm at IW0, §13).
3. **Destination and partner baselines.** Two further Anomaly Models, keyed on `dest_prefix` and on `partner_or_trunk`, features
   `risky_minutes` and `distinct_callers` from `iw_irsf_interconnect`, weekday seasonality, **peer group** by `DEST_CLASS` (a class
   is not MSISDN / PII, so the peer-column gate passes). They answer "this range is carrying far more than it usually carries on a
   Tuesday", which no per-line threshold can.
4. **Intraday.** `hour` buckets are **refused** in the Anomaly Model today, so the intraday story is **Job SQL**, not a model: an
   hour-of-day profile Dataset `iw_hour_profile` (per `msisdn` or `partner_or_trunk`, hour, trailing median and MAD of the same hour
   over `lookback` days, MAD floor so a flat history cannot divide by zero) gives `off_hours_share` and `max_hour_minutes` a baseline.
   If the operator wants hour-bucket Anomaly Models that is a separate, larger decision (D-IW5), not assumed here.
5. **Season and event calendars.** National holidays and big events change international calling. A `calendar_event` Reference (date,
   country, kind) widens the weekday baseline's tolerance **by configuration** on listed dates; without it the model can be wrong for a
   week and the runbook says so.
6. **Cold start.** An entity with fewer than `minBaselinePoints` buckets is scored from the static floor and the peer / population
   baseline only, flagged `basis = static` in the explanation. A **new SIM** is exactly where IRSF lands, so the new-destination and
   floor features carry it.

## 8. Lists, Incident and Case flow, recommend-block, false-positive control

### 8.1 Entity Lists (the "watch / deny list" of the RFP)

The glossary name is **Entity List** with purposes `allow · block · watch · exclusion`; the assurance bid called it a Control List, and
the glossary settles it as **one concept, `D-P10`**. The plan uses three lists, all Space-scoped and with expiry
(`expiresAt` supported; an expired entry stops matching):

| List | Purpose | Holds | Fed by |
|---|---|---|---|
| `iw-watch` | `watch` | MSISDNs and ringing numbers under observation, **expiring** (default 7 days). | An Alert at `RISK_TIER 3` or a model `watchList`. Analyst can add by hand. |
| `iw-recommended-block` | `block` | Numbers, **and prefix ranges** (a range block is a supported Entity List key kind), the Incident recommends barring. **Proposal only.** | The recommend-block step (§8.4) after approval. Enforcement is the customer's switch or the RTSC partner (`WS-30`), outside this product. |
| `iw-allow` | `allow` / `exclusion` | Verified exemptions: a satellite-phone user, a registered PBX, an OTP provider, a business roamer. | Analyst, with a reason; this **replaces the static `exempt_msisdns` parameters** and answers backlog `ASSURE-PACK-TELCO-FRAUD-RESIDUALS-1` (h) for this pack. ⚠ An exemption is only as trustworthy as its writer (a dealer-entered value is not a register): `iw-allow` entries need a recorded approver (D-IW6). |

The three lists are **read by the detection Jobs through the Entity List match**, not through a hand-typed parameter, so a change is
audited with the log position it took effect at (a fold over the Identity Fact log, readable as of any position).

### 8.2 Incident and Case flow

1. **Alert Rule per offender or campaign** (G-42 / `WS-18` per-entity Alerts already built for `telco-fraud`): `by msisdn`
   (line), `by partner_or_trunk` or `by dest_prefix` (interconnect), `by range_id` or `by dest_number` (Wangiri).
2. **One Incident per key**, deduped; a recurrence updates it. Severity is set from **tier × exposure** (§8.3), not from one number.
3. **Case Rule** groups Incidents of one campaign (same `dest_prefix`, `range_id` or `partner_or_trunk` within a window) into a
   **Case**, so 400 lines calling one range is one Case with 400 member Incidents (the Case Rule that already groups correlated
   CRITICAL Incidents in `telco-fraud`, retargeted by a shared attribute; to verify at IW0 whether the Case Rule can group on an
   entity attribute rather than only on severity).
4. **Runbook** per Alert Rule: *Detects* (one line, not repeated), *Check* (the CDR evidence and the feature table), *Act* (the
   recommendation and who may approve it), *Silent by design*, *Known false positives*.
5. **Outcome typing.** Closure sets `Disposition` (confirmed fraud / false positive / inconclusive) and `Impact` (`WS-10`); a false
   positive on a number optionally feeds `iw-allow` with a reason. This is the loop that lets thresholds be tuned from evidence (§8.5).
6. **Workflow and SLA.** The authored per-type workflow and SLA Policy carry a CRITICAL IRSF Incident to the shorter target
   (`TELCO-FRAUD-INCIDENT-PRIORITY-1` built the severity fallback); RFP `FM-23` is moved, not closed, by `WS-23`.

### 8.3 Severity from tier and exposure

`SEVERITY = f(RISK_TIER, exposure_cost, confidence)`; an illustrative default table (all configuration, calibrated at IW0 on synthetic
volume and later by the operator):

| Tier | Exposure at or above `high_cost` | Between | Below `watch_cost` |
|---|---|---|---|
| 1 | CRITICAL | WARNING | INFO and `iw-watch` |
| 2 | WARNING | WARNING | INFO and `iw-watch` |
| 3 | WARNING | INFO | none (counted, not alerted) |

### 8.4 The CDR-only "recommend block" Consequence

**Principle: a recommendation, never an action.** Detection runs nightly or hourly on CDRs; by the time it fires the calls have
happened. The product's honest role is to put a **specific, evidenced, costed recommendation** in front of a person who can approve
it, and to hand it to the enforcement point after approval. Real-time or autonomous blocking is **out of scope**
(`REALTIME-DECISIONING-1`; RFP `FM-17`, `RT-04`, `RT-07`, `RT-26` stay partner capabilities).

- **Form A, composed from what exists (recommended for v1, D-IW4).** A Decision Rule (`create-incident`) opens the Incident with a
  structured **recommended action** block in its description and evidence: `ACTION = bar_idd | bar_prefix | withhold_settlement | hold_partner_traffic`,
  `TARGET` (number, prefix, partner), `BASIS` (the feature table), `EXPOSURE` (suspected), `EXPIRES` (the recommendation lapses). The same
  Decision Rule's `invoke-api` consequence drafts an **Action Request** to a customer-owned Connection (the RTSC partner or a switch
  management API), which is **held for a four-eyes approval** and sent only after a *different* person approves, with bounded retries
  and one idempotency key (`okf/product/module/action-requests/spec.md`). The block entry in `iw-recommended-block` is written
  only on approval.
- **Form B, a named Consequence (optional, IW7).** A `recommend-block` Consequence registered through the Consequence provider seam
  (a `ConsequenceProvider` contribution), taking `ACTION`, `TARGET`, `BASIS`, `EXPIRES` as typed inputs and producing exactly Form A's
  Incident block plus the held Action Request, so the operator authors one Decision Rule instead of two consequences. It needs a
  module manifest, an edition entry and a parity test; **worth building only if Form A proves clumsy in the demo** (D-IW4).
- **Guards, either form:** needs a role that may work Incidents; refuses an `ACTION` the Connection does not declare; refuses a
  `TARGET` on `iw-allow`; the recommendation carries the Incident id so the audit trail links detection, approval and send; a
  recommendation **expires** if not approved (default 24 hours for a line, 72 for a range), and an expiring `iw-recommended-block`
  entry never silently becomes permanent (the build-plan `D-17` exemption for expiring blocks, to be confirmed by the operator, D-IW6).
- **Never:** an unapproved send, a recommendation without evidence, a block on an exempted number, a recommendation that claims
  "confirmed fraud".

### 8.5 False-positive control

| Source of FP | Control |
|---|---|
| Legitimate heavy caller to a risky range (satellite user, diaspora business, call centre) | `iw-allow` with reason and approver; **peer group** baselining; new-destination and spike features prevent a steady heavy user alerting; named in the runbook. |
| Flash-call / OTP provider (verification traffic) | `flash_pattern` is **INFO** and routed as a cost question; the Wangiri rule needs **call-backs**, so a pure OTP sender stays silent (existing behaviour, pinned). |
| National number starting with a risky prefix | Longest-prefix `normal` entries in the Reference; home numbers normalised to `home_cc` + NSN before matching (existing). |
| Holiday, festival or event spike | `calendar_event` widening (§7.5); weekday seasonality. |
| New SIM with no history | `basis = static` flagged; floors; tier-gated severity. |
| Stale or wrong reference entry | `REVIEW_BY` Expectation; provenance mandatory; an entry's **hit-to-confirmation ratio** shown on the Reference page (from Incident closures), so the noisiest entries get reviewed. |
| Alert storm (one range, thousands of lines) | Alert Rules keyed on the **campaign** (range, partner) plus `stormCap`; the per-line Incidents roll up into one Case. |
| Threshold drift | Closure outcome feeds a **precision KPI** (confirmed over total closed per rule); the threshold is a configuration edit, versioned, never a code change. |

## 9. Honest limits of CDR-only detection

1. **Latency.** The Jobs run on the Collector poll cycle plus the Job cadence. The shipped pack runs nightly (next-morning detection); an
   hourly cadence is configuration, but IRSF can cost a large amount in hours, and **milliseconds is not possible** (`G-02`, `WS-21`). The
   plan reports detection latency as a measured KPI (IW0), never a promise.
2. **No revenue-share proof.** CDRs show who called whom and for how long, not who is paid. Revenue-share is an *indicator* (§5.1).
3. **Spoofing is not determinable.** CLI forgery and refiling need signalling or partner data (RFP `T-08`, `WS-30`); the plan reports
   inconsistency patterns only.
4. **Trunk and partner columns depend on the feed.** If the switch's CDR does not carry them, the interconnect grain degrades to
   destination-prefix (C2).
5. **Intraday models** need a build decision (D-IW5); the plan covers intraday with SQL.

## 10. Data model

| Name | Kind | Grain and key columns | New or changed |
|---|---|---|---|
| `cdr` | Stream (Schema v2) | adds optional `in_trunk`, `out_trunk`, `partner`, `release_cause`, `ring_s`, `presented_cli` | **changed**: nine of the existing ten columns kept, optional columns trailing and blank when absent (the same trailing-column move the payments feed made for `agent_id`) |
| `destination_risk` | **Reference** (effective-dated) | `PREFIX` + `VALID_FROM`; §4.1 columns | new; replaces two Job parameters |
| `partner_rate` | **Reference** | `PARTNER`, `RATE_PER_MIN`, optional `DEST_CLASS`, effective-dated | read from the settlement plan's contract; a local copy in this template until then (D-IW2) |
| `calendar_event` | **Reference** | `EVENT_DATE`, `COUNTRY`, `KIND` | new, optional |
| `iw_irsf_line`, `iw_irsf_interconnect`, `iw_hour_profile` | Datasets (Job-written, day partitions) | §5.1, §5.2, §7.4 | new |
| `iw_wangiri_caller`, `iw_wangiri_range`, `iw_callback_funnel` | Datasets (Job-written) | §5.3 | new |
| `iw_flash_cli` | Dataset (Job-written) | `window_date`, `a_number`, `flash_pattern`, `cli_inconsistency` | new (IW8) |
| `iw-watch`, `iw-recommended-block`, `iw-allow` | **Entity Lists** | §8.1 | new (runtime state, not shipped in a template, see §11) |
| Anomaly Models `iw_irsf_line_usage`, `iw_dest_usage`, `iw_partner_usage` | component kind | §7 | new; their Alert Rules are **pending** (created after the first run) |

**Classification.** `a_number`, `b_number`, `msisdn`, `caller` carry `MSISDN` so a viewer without the reveal capability reads the
`masked:` alias (the behaviour `TELCO-FRAUD-MASKED-DASHBOARD-1` built); `presented_cli` is also `MSISDN`. Prefix, partner, trunk and
range columns are `INTERNAL` (a range is not an individual), and **no peer-group column may be classified MSISDN / IMSI / ACCOUNT / PII**
(the Anomaly Model gate).

## 11. Delivery, packaging and edition placement

- **A new Space Template `telco-interconnect-fraud`** (`spaces/_templates/telco-interconnect-fraud/`), **not** an edit of `telco-fraud`
  (D-IW1). Reasons: `telco-fraud`'s counts are pinned across the golden test, the gallery text and the docs (14 Jobs, 14 Alert Rules,
  18 dashboard tiles, 40 breached keys in the ten-typology corpus); a second lane in one template and one golden is the collision the
  shared-tree memories record; the CDR Schema v2 is a breaking change to a feed other Jobs read. `fraud_irsf` and `fraud_wangiri` **stay**
  in `telco-fraud` as the lightweight starter; nothing is retired. Cost: the `cdr` Schema is copied. (Breaking changes are free on any
  surface, so the alternative, extending `telco-fraud` and re-pinning every count, is viable if the operator prefers one template.)
- **Content pack on the telecom side**, `product-map/solution-pack/telco-interconnect-fraud.toon` (proposed; the file does not exist yet), `enforcement: build`, like `telco-fraud` and `telco-ra`.
- **Edition.** Config only, **no new Step Processor and no new Job Type** in v1. `minEdition: standard` (Alert Rules, Anomaly Models, Entity Lists;
  the existing `telco-fraud` manifest says its Anomaly Model part needs "the Standard, Advanced or Preview edition with Anomaly Detection"), listed beside
  `telco-fraud` in the offering's `contentPacks`; the Preview edition carries everything. ⚠ Verify at IW9 (a) how Advanced inherits `contentPacks`
  (only `basic.toon` and `standard.toon` were found listing them in the sibling plan's reading), and (b) which edition builds the Entity List module.
  Edition vocabulary is Basic / Standard / Advanced / Preview; the older Professional / Enterprise names in the existing spaces section are not to be copied.
- **Runtime state is not shippable in a template.** Entity Lists, Anomaly Model pending Alert Rules, Investigation Templates and the
  value-Measure Alert Rules are runtime state (`PACK-AML-1` recorded the same cut). The template ships the **Jobs, References,
  Datasets, Alert Rules over Job-written Datasets, runbooks, KPIs, Widgets, dashboard and a documented seed script** for the three Entity Lists;
  the pending Alert Rules use the existing `config/pending/alert-rules/` mechanism.
- **If Form B is chosen** (§8.4) the Consequence is a **feature module** declared with `provides.consequences`, an edition entry and
  a module manifest; a separate small design before any code (slice IW7).

## 12. RFP traceability

Identifiers are cited exactly as in the RFP working set (`docs/superpower/rfp-mvno-assurance/`: `compliance-matrix.xlsx`, `gap-register.md`,
`build-plan.md`, `assurance-content-catalog.xlsx`); that folder is git-excluded and exists only in this checkout, so IDs are quoted, never linked.
Gap IDs and workstream IDs are those of the register verified 2026-09-23 / 24; ⚠ the **Entity List** component (`LA-17`, built 2026-09-26 to
2026-09-28) and `Impact` landed after that verification, so the register's `G-05` / `G-03` statuses should be re-read at IW0 before the bid text is
touched (this plan does not edit the working set).

### 12.1 What each slice satisfies or moves

| Slice | Matrix lines (satisfies = ● / moves = ○) | Gaps | Workstreams | Catalog / typology |
|---|---|---|---|---|
| **IW0** spike and facts | ○ `FM-02`, `FM-15` (feasibility only) | `G-07`, `G-08` | `WS-14` (D-16 form), `WS-15` | n/a |
| **IW1** destination risk Reference + Expectations | ● `FM-15` (configurable lists, thresholds) in part; ○ `FM-02`, `RT-06` (destination lists, as data) | `G-05` (data half), `G-08` | `WS-12`, `WS-15` | `FM-R01`, `FM-R04`; `T-01`, `T-04` |
| **IW2** IRSF line features | ● `FM-02` (IRSF, premium-rate abuse) for the subscriber grain; ● `FM-16` (batch scoring by rules and statistics); ○ `FM-22` (explainable score, factor-weighted); ○ `FM-20` | `G-08`, `G-07`, `G-13` (partial) | `WS-15`, `WS-14`-in-SQL, `WS-22` (shape only) | `FM-R01`, `FM-R04`; `T-01`, `T-04`, `T-06` |
| **IW3** interconnect roll-up + exposure Measure | ● `FM-02` (traffic pumping, abnormal international traffic) in part; ○ `FM-28`, `FM-29`, `BA-02` (exposure Measures; suspected only) | `G-08`, `G-03` (suspected exposure) | `WS-15`, `WS-10`, `WS-20` | `T-03`, `T-06` |
| **IW4** Wangiri campaign features | ● `FM-02` (Wangiri, callback); ○ `FM-19` | `G-08`, `G-07` | `WS-15`, `WS-14`-in-SQL | `FM-R02`; `T-02` |
| **IW5** seasonal Anomaly Models | ● `FM-16` (anomaly detection); ● `FM-15` (profiles, peer groups, behavioural models) in part | `G-07` | `WS-14` (the shipped Anomaly Model stands in for the unbuilt Step), `WS-15` | `T-03`, `T-06` |
| **IW6** lists, Incident / Case flow, FP control | ● `FM-15` (watchlists, whitelists, blacklists); ● `FM-24` (Case management); ● `FM-27` (TP / FP classification, impact assessment) in part; ○ `FM-23` (SLA tracking), `FM-26` (audit trail); ○ `RT-06` (country / destination / subscriber lists as **maintained lists**) | `G-05`, `G-14`, `G-03` (typed closure), `G-42` | `WS-12`, `WS-18`, `WS-10`, `WS-23`, `WS-25` | `FM-R01`, `FM-R02` |
| **IW7** recommend-block (Form A, optional Form B) | ○ `FM-21` (analyst-approved control requests); ○ `RT-04`, `RT-07`, `RT-26` (only the **FM-side input**: the list and the score a partner would consume); ○ `FM-20` (prioritise by exposure) | `G-15`, `G-05`, `G-06` | `WS-24`, `WS-13`, `WS-12`, `WS-30` (interface only) | `FM-R01`, `FM-R02` |
| **IW8** flash / CLI patterns | ○ `FM-03` (spoofing, partial: patterns only) | `G-08`, `G-01` (residual) | `WS-15` | `T-08` (partial) |
| **IW9** package, dashboards, KPIs, docs | ● `FM-28` (dashboard drill-down exposure to type, entity, event) in part; ○ `FM-30` (TP / FP KPIs); ○ `FM-29` (suspected exposure KPI) | `G-11`, `G-03` | `WS-20`, `WS-10` | KPI catalogue |

Reading the table: **satisfies** means the CDR-derived part of the line is met by the shipped slice (the line may still carry other
parts, see §12.2); **moves** means the slice delivers a prerequisite or a partial form and the line closes only with the named workstream.

### 12.2 RFP lines this plan **cannot** satisfy from CDRs alone

| Line | Why not | What would |
|---|---|---|
| `RT-04` (enforce controls for Wangiri, IRSF, bypass, premium-rate **in real time**) | Needs an inline enforcement point on the signalling path; CDRs arrive after the call. | RTSC partner (`WS-30`, `B-01`, `G-01`); this plan feeds it lists and recommendations only. |
| `RT-07` (allow / reject / release / redirect / IVR announcement actions) | Call-path actions. | Partner. |
| `RT-26` (real-time use of fraud scores and lists received from FM) | The lists and scores exist after this plan, but their **real-time consumption** is the partner's. | `WS-12` sync + `WS-30`; per-event decision path is `REALTIME-DECISIONING-1` (`G-02`). |
| `FM-17` (real-time scoring) | No per-event path. | `WS-21` (seconds, not milliseconds) + partner. |
| `FM-02` **SIM-box and bypass** limb | Needs cell, IMEI and many-distinct-callee behaviour; sibling lane. | `TELCO-BYPASS-SIMBOX-1`; `fraud_simbox` exists. |
| `FM-02` **traffic pumping / AIT on SMS and A2P** | Needs SMSC records, not voice CDRs. | `FM-R13`, A2P logs. |
| `FM-03` **spoofing** and `T-08` | Needs signalling firewall logs; CDR patterns are not proof. | Partner (`WS-30`). |
| `RT-23` (malformed or spoofed signalling) | Signalling data. | Partner. |
| `FM-18` (ML-based scoring) | No ML runtime; the score here is factor-weighted. | `WS-31` (roadmap, `G-21`). |
| `FM-21` **automated** control requests | Autonomy is excluded by design; only analyst-approved requests. | Policy decision plus `WS-24` / `WS-13`. |
| `FM-29` **confirmed**, **prevented**, **recovered** value | Needs analyst closure and the financial-impact ledger. | `WS-10`. |
| `FM-33` / model-drift KPIs | No model. | `WS-31`. |

⚠ `FM-02` is therefore **partially** met by this plan (IRSF, Wangiri, callback, premium-rate, abnormal international traffic from CDRs), and its
SIM-box and A2P limbs stay with their own lanes. Do not move the matrix cell from *Customized → Configurable* on this plan alone.

### 12.3 Alignment with the bid's own typology and content catalog

`T-01` (IRSF) ⇒ IW1 + IW2 + IW3; `T-02` (Wangiri / callback) ⇒ IW4; `T-03` (traffic pumping / AIT, interconnect CDRs) ⇒ IW3 + IW5, voice only;
`T-04` (premium-rate abuse) ⇒ IW2 over the `premium_rate` class; `T-06` (abnormal international traffic) ⇒ IW3 + IW5; `T-08` ⇒ IW8, partial. The
catalog controls `FM-R01` (IRSF, calls to high-risk number ranges) and `FM-R02` (Wangiri, inbound one-ring bursts) are the two this pack builds as
design-to-working content, with the catalog's reconciliation keys `MSISDN` and `A-number range` respectively (the `range_id` of §5.3).

## 13. Phased slices

Per `CLAUDE.md`: **unit tests per slice** (`-pl <module> -Dtest=A,B`, commas, never `+`); the full reactor gate only at handoff or before
a push touching shared seams. `-pl` without `-am` tests the stale sibling jar: rebuild what the slice touched. Each slice ends with a
mutation pass: every clause of new SQL gets one mutant that must turn a **named** assertion red, with a probe that would otherwise
**succeed** (recorded traps: a one-clause mutant red for the wrong reason; a negative test whose probe cannot succeed). **Synthetic only**: a fixed-seed
`InterconnectFraudCorpus` (fictional numbering as in `TelcoFraudCorpus`: home `999`, foreign `287` / `289`, premium `999900` / `999909`), one
planted offender just above each threshold, one look-alike exactly at it, plus named look-alikes per slice.

| Slice | Delivers | Done when (verifiable) | Tests (unit level) |
|---|---|---|---|
| **IW0 spike** | Facts, no shipped config: (1) can an Anomaly Model feature run over a Job-written Dataset and does a filter op express a prefix match; (2) can a Case Rule group on an entity attribute; (3) can an Entity List write be held for approval and does a Job read a list at a log position; (4) staged-SQL throughput on a synthetic 5M-CDR day (per-hour peak windows) and memory ceiling; (5) hourly cron cadence feasible and its measured detection latency; (6) the module that owns `TelcoFraudTemplateGoldenTest`; (7) re-read `G-05` / `G-03` status. | A measured table in §15 with budgets; each answer recorded; no repository change except this document. | Throwaway harness, not committed. |
| **IW1 reference + hygiene** | Template skeleton; `destination_risk` Reference with longest-prefix match; the shared normaliser; an Expectation set (digits-only prefix, no overlap with equal prefix and overlapping validity, `VALID_TO` after `VALID_FROM`, provenance non-empty, `REVIEW_BY` not past); Schema v2 `cdr`; synthetic seed. | A planted overlap, a stale `REVIEW_BY`, a bad prefix and an empty provenance each fail the named Expectation; a `normal` sub-range overrides a risky parent; a number with a national `0…` or `00` prefix normalises exactly as in `fraud_irsf` (pinned by an equivalence test against the old Job's SQL on the same corpus); a CDR with the optional columns blank loads. | `DestinationRiskTest`; `CdrSchemaV2Test`; golden extension. |
| **IW2 IRSF line features** | `iw_irsf_line` and `irsf_score`; Alert Rules; the explanation columns. | Planted spike, off-hours, new-destination, short-burst and steady-heavy-user look-alike: the first four alert with their planted **top feature**; the heavy user is silent; the same corpus under **two host time zones** gives identical findings; a re-run replaces its day, never duplicates. | golden; `IrsfFeatureTest` (each feature has one boundary case); a time-zone test. |
| **IW3 interconnect roll-up + cost** | `iw_irsf_interconnect`; `exposure_cost` with the three-way cost basis; KPIs `irsf_exposure_cost`, `suspected_interconnect_exposure`; Alert Rules by partner / prefix. | Planted range flood across 40 lines raises **one** range Incident and zero per-line Incidents beyond the planted top offenders; exposure equals CEIL-minutes × rate for the planted partner (hand-computed constant in the test); a row with no rate reports minutes and `COST_BASIS = none`; a row priced from retail `charge` is flagged `retail`. | golden; `ExposureCostTest`. |
| **IW4 Wangiri campaign** | `iw_wangiri_caller`, `iw_wangiri_range`, `iw_callback_funnel`; Alert Rules; the existing per-ringing-number rule kept. | Planted campaign of 30 A-numbers in one range: one range Incident; planted funnel of 200 victims to one number: one funnel Incident; the flash-call OTP sender stays silent on the Wangiri rule (no call-backs) and shows only as INFO `flash_pattern`; call-back **after** `callback_hours` does not count (boundary pair). | golden; `WangiriFunnelTest`. |
| **IW5 seasonal models** | Three Anomaly Models; `iw_hour_profile`; the pending Alert Rules; `calendar_event`. | A planted Tuesday spike against a Tuesday baseline scores `high` while the same volume on a seasonal Friday peak does not; a new entity falls back to `basis = static`; a calendar event widens tolerance exactly on listed dates (golden pins the dates). | `AnomalyModelPackGoldenTest`-style golden; `HourProfileTest` (MAD floor). |
| **IW6 lists, Case flow, FP control** | Seed script for the three Entity Lists; Jobs read them; Case Rule; runbooks; closure outcome KPIs. | An `iw-allow` number never alerts; an expired entry alerts again; a campaign of N Incidents becomes **one** Case; a watched number is read through the list match, not a parameter; precision KPI = confirmed ÷ closed per rule on the golden outcomes. | golden; `EntityListGateTest`; `CaseRuleGroupingTest`. |
| **IW7 recommend-block** | Form A (Decision Rule + held Action Request + expiring `iw-recommended-block` write on approval); Form B only if D-IW4 says so. | A recommendation without evidence is refused; a target on `iw-allow` is refused; a request is **not sent** until a second person approves (real HTTP, per the four-gate house pattern); an unapproved recommendation expires; every send carries the Incident id. | real-HTTP gate class; `RecommendBlockGuardTest`. |
| **IW8 flash / CLI patterns** | `iw_flash_cli`; `cli_inconsistency`; wording. | Domestic CLI on an international trunk is flagged **LOW or MEDIUM**, never CRITICAL; one A-number on two partners within minutes is flagged; the explanation never says "spoofed". | golden; wording assertion. |
| **IW9 package and docs** | Template, offering entry, `product-map` item, dashboard, Widgets, KPIs, solution page, as-built distillation. | Template creates in each edition that carries it and gallery marks `creatable` accordingly; guards pass **locally** (vocabulary, doc-links, doc-counts, openapi if a route changed, product-map). | `ControlApiSpaceTemplateSeedGateTest`-style gate; the CI guards run locally. |

Slices IW1 → IW2 → IW3 are sequential (each extends the corpus and the golden). IW4 and IW5 can proceed beside IW3 once the corpus
generator is split per feature (disjoint files). Nothing here edits `telco-fraud`, `telco-ra` or the sibling plans' files.

## 14. Open decisions for the operator

| ID | Decision | Recommendation |
|---|---|---|
| **D-IW1** | Home: new template `telco-interconnect-fraud`, or extend `telco-fraud` (re-pinning its counts)? | **New template**; keep the two starter Jobs. |
| **D-IW2** | Rate source for cost: a local `partner_rate` Reference now, or wait for the settlement plan's agreement Reference? | **Local copy now**, switch when settlement ships; one contract, never two. |
| **D-IW3** | Commercial or licensed range feeds: never bundle; operator imports through a Collector? | **Yes, never bundle**; the pack ships the recipe and a synthetic seed. |
| **D-IW4** | Recommend-block: Form A (composed) now, Form B (named Consequence) only if the demo shows it clumsy? | **Form A first.** |
| **D-IW5** | Intraday: SQL profile only, or build hour-bucket Anomaly Models (a larger platform change)? | **SQL profile**; defer hour buckets until a customer needs them. |
| **D-IW6** | Four-eyes: permanent `RISK_TIER 1` Reference entries, `iw-allow` entries and permanent block entries need a second approver; expiring blocks (24 h or less) apply and are reviewed afterwards (build-plan `D-17`)? | **Yes**, same as `D-17`. |
| **D-IW7** | One currency per Space with FX from the settlement Reference? | **Yes**, `USD` default as in `telco-ra`. |
| **D-IW8** | Keep `irsf_exposure_seconds` as the volume KPI beside the new cost KPI (breaking changes are free, so it could be retired)? | **Keep**, as volume. |
| **D-IW9** | Local time zone and "off-hours" band: per Space or per partner country? | **Per Space** configuration; per-country later. |
| **D-IW10** | Matrix: after IW3 + IW4 + IW6 mark row H as **Y** for IRSF / Wangiri, **P** for flash / CLI? RFP `FM-02`: leave "Partially compliant" until SIM-box and A2P lanes land? | Operator's call; the matrix is updated only when a slice ships (as the sibling plans decide). |

**ANSWERED 2026-10-20 (operator, accepted the recommended answer): D-IW1…D-IW10, all as recommended** (D-IW10 stays the
operator's own call at ship time: the matrix changes only when a slice ships). **Cross-note:** D-IW2 (local `partner_rate`
Reference now, switch when settlement ships), D-BP7 and D-BP8 of the SIM-box plan (`bypass-simbox-detection-plan.md`) share
one rate / CLI boundary: **no CLI column, signal or Alert Rule defined in both packs.**

## 15. Risks

1. **No real data seen.** Every pattern is from public typology descriptions and synthetic fixtures; thresholds are guesses until an
   operator's traffic calibrates them. The pack says so and ships thresholds as configuration.
2. **Reference data quality dominates.** Stale or wrong prefixes cause both misses and false alarms. Mitigations: provenance, `REVIEW_BY`,
   hit-to-confirmation ratio, longest-prefix `normal` overrides.
3. **CDR lacks trunk / partner** (G2): the interconnect grain degrades; Schema v2 is optional by design, the dashboard states the grain in use.
4. **Latency.** A next-morning detection of an overnight IRSF run is a **loss already incurred**. The plan treats the hourly cadence and
   the partner-enforced list as the real mitigations, and refuses to promise otherwise (§9).
5. **Volume unknown.** Per-hour peak windows over a 5M-CDR day are untested; IW0 measures before any claim (the recorded rule for the Reconciliation lane).
6. **Recommendation fatigue.** A flood of recommendations teaches approvers to click through; tier-gating and campaign Cases mitigate it, and
   the precision KPI exposes it.
7. **Over-reading indicators.** Revenue-share and flash / CLI features are patterns; wording is "consistent with", enforced by a test (IW8).
8. **Runtime state is not template content** (Entity Lists, pending Alert Rules): demos need a seed script; a Space created from the template
   without it has no lists, and the Jobs must treat an absent list as empty, not as failure.
9. **Four lanes, one repository.** Three sibling plans are written in parallel; the only shared artifacts are the rate Reference contract
   (settlement) and the shared feature SQL (`TELCO-BYPASS-SIMBOX-1`). Any other shared file is a collision; edit only your own plan file,
   plus the index and board lines.
10. **Vocabulary.** Use *Pipeline*, *Dataset*, *Incident*, *Collector*, *Stream* / *Reference*, *Expectation* / *Alert Rule* / *Decision Rule*,
    *Measure*, *Entity List* (never "watchlist" as the noun, never "Control List"); see `docs/GLOSSARY.md` §0 and §13.

## 16. Backlog and docs touched

- `BACKLOG.md`: a new P3 row **`PACK-IRSF-WANGIRI-1`** after `PACK-RERATING-1` (competitive gap H); the board's row-count markers move
  with it. Close it when IW9 ships; file residuals as their own rows.
- `INDEX.md`: this plan's row in the in-flight table (superpower tier).
- On ship (IW9): distil as-built facts (decisions, seams, gotchas) into [`okf/product/module/config/plan-spaces.md`](../../module/config/plan-spaces.md) (a new
  subsection beside §3.5), add a solution page under `stakeholders/solutions/`, update the matrix row H, then `git mv` this plan to
  `archived-documents/plans-archive/`.
- **Not touched by this plan:** the RFP working set (read-only here), `GLOSSARY.md` (this plan adds no new term: *recommend-block* is a Decision Rule
  configuration, not a vocabulary entry, unless Form B is built, when `Consequence` §4 gains one line), and every other session's plan file.

## 17. Measurements (filled by slice IW0)

_Empty until IW0 runs: anomaly-model-over-Job-Dataset answer, Case Rule grouping answer, Entity List approval-hold answer, staged-SQL throughput and
memory on a synthetic 5M-CDR day, hourly-cadence detection latency, the module owning `TelcoFraudTemplateGoldenTest`, and the re-read register status._

## References

- Matrix: [`okf/product/layer/product/brochure-competitive-matrix.md`](../../layer/product/brochure-competitive-matrix.md) rows D, E, H, I.
- `telco-fraud` as-built: [`okf/product/module/config/plan-spaces.md`](../../module/config/plan-spaces.md) §3.5.
- Anomaly Models: [`okf/product/module/anomaly/spec-anomaly-scores.md`](../../module/anomaly/spec-anomaly-scores.md). Entity Lists:
  [`okf/product/module/entity-list/spec.md`](../../module/entity-list/spec.md). Action Requests:
  [`okf/product/module/action-requests/spec.md`](../../module/action-requests/spec.md).
- Sibling plans: [`rerating-tariff-validation-plan.md`](../../../../archived-documents/plans-archive/rerating-tariff-validation-plan.md),
  [`partner-settlement-wholesale-plan.md`](../../../../archived-documents/plans-archive/partner-settlement-wholesale-plan.md).
- Canonical vocabulary: [`GLOSSARY.md`](../../../../GLOSSARY.md) (the single source of truth).
