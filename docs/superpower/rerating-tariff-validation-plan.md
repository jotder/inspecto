# Re-rating / tariff validation: a "smart" plan

> 🟡 **PLAN 2026-10-18 (`PACK-RERATING-1`): design only, nothing built.** Competitive gap **C** (re-rating / tariff
> validation) is **Partial** in [`COMPETITIVE_CAPABILITY_MATRIX.md`](../stakeholders/COMPETITIVE_CAPABILITY_MATRIX.md): Inspecto has one SQL
> control in the `telco-ra` Space Template and nothing more, while LATRO, Mobileum, Neural and Araxxe sell re-rating and
> Subex and TEOCO sell it partially. The row `PACK-PARTNER-SETTLEMENT-1` filed both gap C and gap Q together; this plan
> takes the **re-rating half**. Gap Q (partner settlement) is planned in a sibling document and is **not** touched here.
> Decisions **D-RR1…D-RR10 are open** (§9). Every claim about volume is a hypothesis until slice R0 measures it.
>
> **Method.** Read the matrix rows C and Q, the revenue-assurance solution page, the `telco-ra` template and its golden
> corpus, the Reconciliation module page, the Analytics Kernel concept and `docs/GLOSSARY.md`; every statement about "what
> exists" below cites a file that was read on 2026-10-18.

## 1. What exists today, and the gap

### 1.1 What `telco-ra` already does

One `sql.template` Job, `ra_rerating` (`spaces/_templates/telco-ra/config/jobs/ra_rerating_job.toon`), runs at 05:10 and
the findings Jobs chain from it:

1. Joins each `rated_usage` row to `tariff` on `PLAN` and `SERVICE`, choosing the tariff row **in force at the call start**
   (`EFFECTIVE_FROM <= EVENT_TS < EFFECTIVE_TO`, an open `EFFECTIVE_TO` allowed).
2. Counts the matching tariff rows. **0** → `no_tariff`, **more than 1** → `ambiguous_tariff` (both **data quality**),
   **1** → `ROUND(USAGE_UNITS × RATE, 2)` compared with `CHARGE` at an absolute `tolerance` of `0.02` →
   `rate_mismatch` (**leakage**).
3. Emits one row per call (`ITEM_KEY`, `ACTUAL_CHARGE`, `EXPECTED_CHARGE`, `LEAKAGE_AMOUNT = EXPECTED − CHARGE`, `REASON`,
   `FINDING`, `CONTROL`) into the `ra_rerating_findings` Dataset; `ra_leakage` unions the leakage rows with the roll-forward
   and settlement controls; `ra_leakage_found` (CRITICAL) is an Alert Rule `by: CONTROL`.

`TelcoRaGoldenTest` (`features/inspecto-reconciliation/src/test/java/com/gamma/job/TelcoRaGoldenTest.java`, corpus
`TelcoRaCorpus.java`) pins it exactly: **5 rate mismatches + 12 ambiguous tariff + 1 no tariff**, with boundary look-alikes
silent (a one-cent-off charge is inside the tolerance; a call that **starts** before the PLAN_A VOICE rate change and
runs past it is rated at the start rate and is **not** flagged). ⚠ The BACKLOG text says the test "moved to the `inspecto`
module"; the file this plan found lives under `features/inspecto-reconciliation`. Confirm the module at R1 before writing
a `-pl` command.

### 1.2 What it cannot do (the gap)

| # | Limit, read from the SQL and the Schemas | Consequence |
|---|---|---|
| G1 | The tariff row is chosen with `MIN(RATE_PER_UNIT)`, and a duplicate row **only** flags the call. | No resolution: 12 data-quality findings come from **one** duplicate tariff row whose rate is **the same** as the base row (`PLAN_B,DATA,0.0080` twice in `TARIFF_20260701.csv`). The harmless case is indistinguishable from a harmful one, and the analyst sees 12 rows for 1 defect. |
| G2 | `PLAN` is read from the rated record itself. | The control validates the rater's arithmetic and tariff lookup, **not its plan assignment**. A subscriber rated on the wrong plan with a correct lookup is invisible. |
| G3 | Flat `USAGE_UNITS × RATE`, one rounding, one absolute tolerance. | No billing increments or minimum charge (the settlement control already knows `CEIL(seconds / 60)` for interconnect minutes, retail re-rating does not), no rounding scope (per event, per call, per invoice line), no percent tolerance. |
| G4 | No bundles or allowances, no tax, no zone, no time band. | Cannot judge any plan with free units, peak and off-peak, destination prefixes or tax-inclusive prices. `B_NUMBER` exists in `switch_xdr` but is never joined. |
| G5 | A mismatch is `rate_mismatch` and nothing more. | The analyst re-derives **why** by hand. The golden corpus plants two causes (a charge at half the rate; a charge at the pre-change rate) and the control cannot tell them apart. |
| G6 | One finding per call. | Three stale-rate calls are three rows; a recurring cause has no identity across days. |
| G7 | `LEAKAGE_AMOUNT = EXPECTED − CHARGE`, summed by `leakage_found` as `sum(LEAKAGE_AMOUNT)`. ⚠ Read from the SQL, **not run**. | An **over-billed** call has a negative amount and is classified `leakage`, so over-billing **nets against** under-billing in the headline figure. Over-billing is a customer and regulatory exposure, not recovered revenue. |
| G8 | Alert Rule `by: CONTROL`. | One Alert per control, never per cause. The runbook cannot say "this cause, this amount, this tariff version". |
| G9 | The tariff is a plain table with no authoring checks. | Overlaps, gaps and negative rates are caught only indirectly, per call, after a billing run. |

## 2. What "smart" means (and what it does not)

Smart here is **deterministic, explainable and recomputable**, the same standard the platform holds Anomaly Scores and
Risk Scores to ([`okf/backend/control-plane/anomaly-scores.md`](../okf/backend/control-plane/anomaly-scores.md)): every verdict
stores the numbers it came from, so an auditor can recompute it. **No trained model, no external ML runtime** (recorded
constraint of [`analytics-core-plan.md`](analytics-core-plan.md) §1). Eight capabilities, each a column or a Dataset, not a service:

| # | Capability | What it is | Closes |
|---|---|---|---|
| S1 | **Tariff versioning and effective dating** | The tariff is a **Reference** with a `TARIFF_VERSION_ID` per row; a Job finds overlaps, gaps and bad rates **in the table itself** before any usage is judged. Every re-rated row records the version it used. | G1, G9 |
| S2 | **Ambiguous-tariff resolution** | Candidate set per call, then a three-way verdict: **agree** (all candidates give the same expected charge → resolved, a tariff-hygiene fact), **resolved by precedence** (an explicit `PRIORITY`, else latest `EFFECTIVE_FROM`; confidence lowered), **disagree** (expected is a **range** `[LOW, HIGH]`; leakage is claimed only for the part outside the range, i.e. against the nearest bound). | G1 |
| S3 | **Rate-structure handling** | Closed vocabulary of tariff columns: billing increment and first block, minimum charge, rounding mode and **scope**, destination zone (longest prefix on `B_NUMBER`), time band, tax class and inclusive/exclusive flag, **allowance / bundle** (stateful, last). | G3, G4 |
| S4 | **Rate-plan inference** | For a subscriber, the **implied rate** (`CHARGE / USAGE_UNITS` per service) is compared against every plan's rate for the same effective version; the plan whose rates explain at least a configured share of the subscriber's rows is the **inferred plan**. Compared with the assigned plan from a `subscription_history` Reference: a difference is cause `wrong_plan`. Confidence = share of rows explained. | G2 |
| S5 | **Confidence-scored mismatch explanation** | For each mismatch, **counterfactual re-rating**: recompute the expected charge under each hypothesis (previous tariff version, inferred plan, other rounding mode, no increment, tax excluded, allowance not applied) and keep those that reproduce the actual charge within tolerance. Output: `CAUSE`, `CAUSE_CONFIDENCE`, `CAUSE_ALTERNATIVE`. | G5 |
| S6 | **Root-cause grouping** | Findings grouped by `(CAUSE, PLAN, SERVICE, TARIFF_VERSION_ID, rate pair)` into a **root cause** with a stable `ROOT_CAUSE_ID`, call count, first and last day, total and per-day amount, and the example `ITEM_KEY`s. | G6 |
| S7 | **Revenue-at-risk Measure** | Columns `LEAKAGE_LOW`, `LEAKAGE_HIGH`, `OVERBILLED_AMOUNT` and `RUN_RATE_PER_DAY` computed in Job SQL; a **Measure** (`sum(...)`) over them feeds the KPI and the dashboard. Over-billing is its own class (D-RR4). Measures stay `{agg, field}` pairs (parked-rows D10), so the arithmetic must live in the Job, not the Measure. | G7 |
| S8 | **Auto-Incident per root cause** | An Alert Rule `by: ROOT_CAUSE_ID` over the root-cause Dataset, severity from revenue at risk; a recurring cause updates its Incident, it does not open a second one (§6.5). | G8 |

What it does **not** mean: no automatic impact-ledger write (decided **stay manual** 2026-10-04, item (2) of
`ASSURE-PACK-TELCO-RA-1` in `BACKLOG.md`, not re-litigated here); no rating of production traffic (§4).

## 3. Does re-rating need a generic kernel?

**Recommendation: no new kernel now. Build it as a staged chain of `sql.template` Jobs over Reference Datasets, reuse
the Reconciliation add-on for the billed-side comparison, and fix a measured gate (G-K, below) that would trigger a
kernel later.** Evidence:

| Engine | Can it express re-rating? | Evidence |
|---|---|---|
| **SQL controls** (`sql.template`) | **Yes, for everything in S1–S7 except possibly stateful allowances at volume.** | `ra_rerating` already expresses effective dating, ambiguity and tolerance in one statement and is pinned by a golden corpus. The platform already runs the telco-fraud detection windows and the whole `telco-ra` chain as `sql.template` Jobs; layering through Job-written Datasets is the recorded design (it "avoids cycles, hidden cost and lineage ambiguity", [`okf/capabilities/spaces/spaces.md`](../okf/capabilities/spaces/spaces.md) §3.5, business-assurance pack). Window functions cover the allowance running balance (order by `EVENT_TS`, `XDR_ID`; `transform.analytics.running` is a shipped Step if a Pipeline form is wanted). ⚠ **Not yet measured:** allowance state at 10M+ rows (slice R0). |
| **Reconciliation** | **Half.** Right for *re-rated total vs billed total* per subscriber and month; wrong for pricing. | It compares two or three Datasets at a key grain with `sum` or `count` and `exact` / `absolute` / `percent` tolerance, and gives the Break lifecycle (open, assigned, resolved, `auto_closed`, recurrence), dedupe and Incident promotion ([`stakeholders/modules/reconciliation.md`](../stakeholders/modules/reconciliation.md) §4, §5). It has no arithmetic over rows, so it cannot price. Its strength is exactly the step `telco-ra` lacks: comparing the **independently re-rated** amount, not the rater's own charge, with the invoice. |
| **Expectations** | **Only the tariff-table hygiene that is per row** (rate non-null, rate ≥ 0, plan and service referential). | An Expectation validates records against a Schema; an **overlap or gap between two rows** is a relation, not a record property, so it stays in SQL (S1). |
| **Decision Rules** | No. | They route and transform records; they are not a pricing or comparison engine. |
| **Alert Rule + Incident** | Yes, for S8. | `ra_leakage_found` already raises one Alert per control; a `by` on a root-cause column is the same mechanism (to confirm in R0: a high-cardinality `by`, `stormCap`). |
| **`transform.telecom.rating`** | **It does not exist and should not be this.** | Listed `PLANNED` in `ProcessorCatalog` and `EDITIONS.md` (SP-XFM-19, 🔲 in every edition): a "tariff, rating & usage billing engine" is a **producer** of charges. Re-rating validation's value is **independence** from the rater; building a rating engine adds scope and liability and gives a validator that shares a code path with whatever it validates only if the operator adopted it for billing, which nobody asked for. |

Why not a kernel anyway? Per the core-versus-module test in [`analytics-core-plan.md`](analytics-core-plan.md) §2 ("if its
definition only makes sense in one domain's vocabulary, it stays in its module"), tariff mathematics is **telecom
vocabulary**, so it would live in a feature module, not the Analytics Kernel, and would need a manifest, an edition entry, a
Job Type and a parity test. That cost is worth paying only if SQL fails.

**Gate G-K (measured in R0 and re-checked after R4; any one trips it, then slice R7 is opened and a design is written):**
(a) the stateful allowance stage cannot meet the throughput budget set in R0 on the synthetic volume; (b) a tariff
structure the closed vocabulary of S3 cannot express is named by a prospect (for example tiered volume discounts with
promotion stacking); (c) a stage's SQL cannot be unit-tested in isolation (it exceeds what one golden corpus can pin per
clause); (d) gap Q needs the **same** arithmetic and a second SQL copy would drift (the shared-definition test).
If it trips, the kernel is a **SQL builder with a JVM twin and a parity test**, the pattern of `StatsSql` and
`AccuracySql` in the Analytics Kernel, generated from the tariff Reference. It is not a rating engine.

## 4. Scope boundary

- **In:** retail subscriber usage (voice, SMS, data) re-rated against the operator's tariff **Reference**, and the
  re-rated amount compared with the invoice.
- **Out, v1:** roaming TAP and CIBER surcharging (`transform.telecom.roaming`, planned), wholesale and interconnect
  settlement (gap Q: partner statements against `ic_rates`), currency conversion, production rating, any write to a billing
  system, the vendor-specific feed mapping (parked in the assurance plan's §4).
- **Interface with gap Q.** Q's `ra_settlement` already prices partner traffic as minutes × `ic_rates`. To keep two lanes
  from forking the maths, this plan **publishes one contract**: the Dataset `rerated_usage` (§5.2) with a stable column
  list. Q may read it, and if Q needs a different re-rating of the same xDRs, gate G-K(d) opens.

## 5. Data model

### 5.1 References and Streams in

| Name | Kind | Grain and key columns | New or changed |
|---|---|---|---|
| `tariff_version` | **Reference** (`load: scd2`, to verify in R0) | `PLAN, SERVICE, ZONE, TIME_BAND` + `EFFECTIVE_FROM` / `EFFECTIVE_TO`; `RATE_PER_UNIT`, `BILLING_INCREMENT`, `FIRST_BLOCK`, `MIN_CHARGE`, `ROUNDING_MODE`, `ROUNDING_SCOPE`, `TAX_CLASS`, `PRIORITY`, `TARIFF_VERSION_ID` | extends today's 5-column `tariff` |
| `subscription_history` | **Reference** | `SUBSCRIBER_ID, PLAN, FROM_TS, TO_TS` | new (S4; D-RR8) |
| `plan_bundle` | **Reference** | `PLAN, BUNDLE_ID, SERVICE, ALLOWANCE_UNITS, CYCLE, OVERAGE_PLAN` | new (R5 only, D-RR7) |
| `tax_rate` | **Reference** | `JURISDICTION, TAX_CLASS, RATE, INCLUSIVE`, effective-dated | new (R4) |
| `destination_zone` | **Reference** | `PREFIX → ZONE`, longest prefix | new (R4) |
| `rated_usage` | Stream | as today; adds optional `TAX_AMOUNT`, `ZONE`, `TIME_BAND` as the rater reported them | extended |
| `switch_xdr` | Stream | `B_NUMBER`, `PARTNER` already present; joined on `XDR_ID` for zone | reused |
| `billed_invoice` | Stream | adds `TAX_AMOUNT` | extended |

A Reference is the slow-changing dimension counterpart of a Stream (glossary §3) and materialises as a Reference Dataset;
the `reference: {load: replace|upsert|scd2, key, refresh_seconds}` surface is authorable per the glossary. **R0 proves
that `scd2` actually keeps history in the Reference Dataset**, because if it replaces, versioning has to be carried in the
file itself (`TARIFF_VERSION_ID`, one row per version), which still works and costs nothing.

### 5.2 Datasets out (Job-written, one per stage)

| Dataset | Written by | Content |
|---|---|---|
| `rerating_tariff_findings` | `rr_tariff_hygiene` | one row per **tariff row or pair**: overlap (same rate / different rate), gap, zero or negative rate, orphan plan |
| `rerated_usage` | `rr_rerate` | **the contract**: one row per xDR with `EXPECTED_LOW`, `EXPECTED_HIGH`, `EXPECTED_CHARGE`, `TARIFF_VERSION_ID`, `CANDIDATES`, `RESOLUTION` (`single` / `agree` / `precedence` / `disagree` / `none`), and every intermediate (units after increment, rounding applied, tax) |
| `rerating_findings` | `rr_explain` | one row per mismatched or unratable xDR, with `REASON`, `FINDING` (`leakage` / `overbilled` / `data_quality`), `CAUSE`, `CAUSE_CONFIDENCE`, `CAUSE_ALTERNATIVE`; **keeps the column list `ra_leakage` unions** (`CONTROL, EVENT_DATE, ITEM_KEY, REASON, FINDING, LEAKAGE_AMOUNT`) so the existing union still reads it |
| `rerating_root_causes` | `rr_root_cause` | one row per `ROOT_CAUSE_ID` per window: calls, first / last day, `LEAKAGE_LOW`, `LEAKAGE_HIGH`, `OVERBILLED_AMOUNT`, `RUN_RATE_PER_DAY`, confidence, example `ITEM_KEY`s |
| `rerated_usage_monthly` | `rr_rerate_monthly` | re-rated amount per subscriber per month, the **left side** of `rr_rerated_vs_billed` |

Zero-row `schema-seed.parquet` seeds are needed for the Datasets an Alert Rule or KPI reads, because the save gate
reads the Dataset Schema and every pack Dataset is empty at apply (the `telco-ra` pattern,
[`okf/capabilities/spaces/spaces.md`](../okf/capabilities/spaces/spaces.md) §3.5).

### 5.3 The staged chain

```
rr_tariff_hygiene ─► rr_rerate ─► rr_explain ─► rr_root_cause ─► (Alert Rule by ROOT_CAUSE_ID)
                         └──────► rr_rerate_monthly ─► rr_rerated_vs_billed (recon.run) ─► Incident per breach
```

Chained with `on_signal: job.run.completed` and `when: "$signal.job == <previous> && $signal.outcome == SUCCESS"`, exactly
as `telco-ra` does (halt-on-failure by guard; every link records a SKIPPED run on the others, so a test waiting for a link
ignores SKIPPED). Incremental by partition (`window_date`), the Job-type mode the telco-fraud pack already uses
(BACKLOG `TELCO-FRAUD-STORY-GAPS-1`, 2026-10-10).

## 6. Design details that need to be right

### 6.1 Effective dating and time zones
A call is rated at its **start** (`EVENT_TS`); keep that, and keep the golden look-alike (a call spanning a rate change is
**not** flagged). Interval semantics stay half-open (`FROM <= ts < TO`). ⚠ **DuckDB's session `TimeZone` is the host's,
not UTC** (recorded gotcha): every `TIMESTAMP` comparison and every cycle boundary in the allowance stage is made on an
explicit zone parameter, and a test runs the golden corpus under two host zones.

### 6.2 Ambiguity resolution
`CANDIDATES` is the count of tariff rows in force. Verdict order: none → `no_tariff`; one → `single`; many with equal
expected charge → `agree` (not a call finding; emitted once in `rerating_tariff_findings`, see the 12 → 1 reduction in §1.2
G1); many with different charges → `precedence` if a `PRIORITY` or distinct `EFFECTIVE_FROM` orders them, else `disagree`.
`disagree` yields `[EXPECTED_LOW, EXPECTED_HIGH]`; a charge inside it is **explained, not leakage**, and the call goes to
data quality; a charge outside it is leakage against the nearest bound. This makes the pack **conservative by design**:
it never claims an amount an ambiguous tariff could justify.

### 6.3 Counterfactual explanation (S5) and confidence
Each hypothesis is a re-computation of `EXPECTED_CHARGE` with one parameter changed, so it is testable, not asserted:

| Hypothesis | Counterfactual |
|---|---|
| `stale_version` | the previous `TARIFF_VERSION_ID` for the same key |
| `wrong_plan` | the subscriber's inferred plan (S4) |
| `fractional_rate` | `ACTUAL / EXPECTED` is a clean ratio (for example 0.5) across the group |
| `rounding_mode` / `rounding_scope` | the other mode or scope |
| `increment_ignored` | units not rounded to the billing increment |
| `tax_basis` | tax added or removed |
| `allowance_not_applied` | free units not deducted |
| `unexplained` | none reproduces the charge |

`CAUSE_CONFIDENCE` is deterministic and stored with its inputs: **exactness** (the counterfactual reproduces the charge
exactly = 1.0, within tolerance = a configured lower value) multiplied by **support** (the share of the root-cause group's
calls the same hypothesis also explains). Two hypotheses that both fit are both kept (`CAUSE_ALTERNATIVE`), never one
silently dropped. The wording in the UI and runbook is **"consistent with"**, because a counterfactual match shows a cause is
sufficient, not that it is the only one. All constants are configuration (D-RR5).

### 6.4 Rate-plan inference (S4)
Per subscriber and service over a window: for each plan, the share of rows whose implied rate equals that plan's rate in the
version in force. A plan above the configured share is the inferred plan. **Benign look-alikes the corpus must plant:** a
subscriber who legitimately **changed plan mid-window** (effective-dated in `subscription_history`) and a subscriber whose
rows are too few to infer anything (confidence below the floor → no `wrong_plan` claim).

### 6.5 Incident granularity
An Alert Rule `by: ROOT_CAUSE_ID` yields one Alert per root cause; its Incident is deduped on the same key, so a cause that
persists tomorrow **updates** its Incident rather than opening another. This follows the recorded split for fraud
("an Alert per **Rule** and Incidents per entity") only in spirit: here the unit is the **cause**, which is what the analyst
fixes. Confirm in R0 that the Alert Rule `by` accepts the new column at the expected cardinality and what `stormCap` does.
Impact stays manual: the Incident description carries the amount; nothing writes the ledger.

### 6.6 Reconciliation use
`rr_rerated_vs_billed` is a `reconciliation` component: `leftDataset: rerated_usage_monthly`, `rightDataset:
billed_invoice`, `keyColumns: [SUBSCRIBER_ID]`, `includeRecordCount: false` (many rated rows face one invoice row, the same
reason `ra_rated_vs_billed` needs it), absolute and percent tolerances as configuration, `impact.column` carried. This is
the **re-rated versus billed** comparison; `ra_rated_vs_billed` compares the rater's own number.

## 7. Delivery, packaging and edition placement

- **A new Space Template `telco-rerating`** (`spaces/_templates/telco-rerating/`), not an edit of `telco-ra` (D-RR1).
  Reasons: `telco-ra`'s counts are pinned across tests and docs (eleven Jobs, 5 / 12 / 1, 17 + 17 rows, twenty-two
  mutations); gap Q will want to extend the same template and its golden test, and two lanes in one template and one golden
  is the failure the shared-tree memories record. Cost: three feed Schemas are copied (`tariff`, `rated_usage`,
  `billed_invoice`). `telco-ra`'s `ra_rerating` **stays** as the lightweight starter; nothing is retired.
- **A function-pack flavour, telecom-flavoured.** Per the glossary a function pack is domain-neutral and an industry pack
  carries a vertical's data; tariff vocabulary is telecom, so the template is a **content pack** on the telecom side
  (`product-map/solution-pack/telco-rerating.toon`, `kind: solution-pack`, `enforcement: build`, like `telco-ra`).
- **Edition.** Config only, **no new Step Processor and no new Job Type**. `minEdition: standard` (it ships Alert Rules and a
  Reconciliation, the same reason as `telco-ra`), listed in `offerings/standard.toon` `contentPacks` beside `telco-ra`; the
  Preview edition carries everything. ⚠ Only `basic.toon` and `standard.toon` list `contentPacks`; verify at R6 how Advanced
  inherits before claiming it. Edition vocabulary is Basic / Standard / Advanced / Preview; the older Professional /
  Enterprise names still sit in the `telco-ra` section of `spaces.md` and are not to be copied.
- **If gate G-K trips** (slice R7) the kernel is a **feature module** with a Job Type (for example `rerate.run`), added to
  `tools/bundle-modules.mjs` and the edition profiles, and `module.toon` declared; that is a separate design, not part of
  this plan's slices.

## 8. Phased slices

Per `CLAUDE.md`: **unit tests per slice** (`-pl <module> -Dtest=A,B`, commas, never `+`); the full reactor gate only at
handoff or before a push touching shared seams. `-pl` without `-am` tests the stale sibling jar, so rebuild what the slice
touched. Each slice below ends with a mutation pass: every clause of the new SQL gets one mutant that must turn a named
assertion red, and the probe is built so it would otherwise **succeed** (recorded traps: a one-clause mutant red for the
wrong reason; a negative test with a probe that cannot succeed).

| Slice | Delivers | Done when (verifiable) | Tests (unit level) |
|---|---|---|---|
| **R0 spike** | Facts, no shipped config: (1) does `load: scd2` keep history; (2) Alert Rule `by` on a high-cardinality column and `stormCap`; (3) staged-SQL throughput on a synthetic 5M-row usage day (rating + allowance window) and a memory ceiling; (4) the module that owns `TelcoRaGoldenTest`. | A measured table in this plan (§12) with budgets that gate G-K(a); each of the four answers recorded; no repository change except this document. | Throwaway harness, not committed; numbers only. |
| **R1 versioning + ambiguity** | Template skeleton `telco-rerating`; `tariff_version` Reference; `rr_tariff_hygiene`; `rr_rerate` with the S2 verdicts; `rerated_usage`; a corpus generator `RerateCorpus` (fixed seed) planting: a stale version, an overlap with the **same** rate, an overlap with **different** rates, a gap, a negative rate, no tariff, a call spanning a change, one-cent-off. | The golden asserts **exactly** the planted set per control; the 12 same-rate ambiguous calls become **0** call findings and **1** tariff finding; the different-rate overlap yields a range and a data-quality row, not leakage; look-alikes silent. | `TelcoRerateGoldenTest` (template creates; Jobs run; Datasets equal the planted set), `RerateCorpusTest` (corpus is deterministic and the planted/benign sets are disjoint), a SQL-clause mutation pass. |
| **R2 explanation + root cause + risk** | `rr_explain`, `rr_root_cause`, the `LEAKAGE_LOW` / `HIGH` / `OVERBILLED_AMOUNT` / `RUN_RATE_PER_DAY` columns; KPIs `revenue_at_risk` and `overbilled_amount`; the over-billing class (D-RR4). | Every planted mismatch gets its planted `CAUSE` with confidence at or above the floor; an unplanted mismatch is `unexplained` with confidence 0; three stale-rate calls form **one** root cause; an over-billed call never enters `revenue_at_risk`; the group identity is **stable across two runs**. | extend the golden; `RerateExplainTest` (each hypothesis, both-fit case keeps two causes); a two-run stability test. |
| **R3 plan inference** | `subscription_history` Reference; S4; cause `wrong_plan`. | Planted wrong-plan subscribers are inferred with the planted confidence; the mid-window plan change and the too-few-rows subscriber stay silent. | golden extension; `PlanInferenceTest` (floor and share parameters). |
| **R4 structure (stateless)** | Increments, first block, minimum charge, rounding mode and scope, zone, time band, tax; the four small References. | One planted defect per feature recovered with the right `CAUSE`; the same corpus under **two host time zones** gives identical findings; no feature changes the R1 or R2 expectations (regression). | per-feature golden fixtures; a time-zone test. |
| **R5 allowance / bundle (stateful)** | `plan_bundle`, cumulative free-unit split per subscriber and cycle. **Demand-gated** (D-RR7). | Planted cycle-boundary and order cases recovered; results independent of file arrival order; throughput inside the R0 budget, else G-K(a) trips. | golden extension; an order-permutation test. |
| **R6 recon + Incident + package** | `rr_rerated_vs_billed`; Alert Rule by `ROOT_CAUSE_ID`; runbook; dashboard Widgets; template and offering entries; `product-map` item; docs distilled. | One Alert per root cause on the golden; a **second day's** run updates the same Incident, it does not open another; the template gallery marks `creatable` per edition; guards pass (vocabulary, doc-links, openapi if a route changed, product-map check). | golden; `RerateAlertTest`; the CI guards run **locally** before any push (a green reactor is not green CI). |
| **R7 kernel (only if G-K trips)** | A design, then a SQL builder with a JVM twin. | Design reviewed by the operator before any code. | parity test (JVM equals DuckDB). |

Slices R1 → R2 → R3 are sequential (each extends the corpus and the golden). R4 can start beside R3 once the corpus
generator is split per feature (disjoint files). Nothing here edits `telco-ra`, so it cannot collide with the Q lane.

## 9. Open decisions for the operator

| ID | Decision | Recommendation |
|---|---|---|
| **D-RR1** | Home: new template `telco-rerating`, or extend `telco-ra`? | **New template**; keep `ra_rerating` as the starter. |
| **D-RR2** | Kernel: none now with gate G-K, or build `transform.telecom.rating` / a Job Type up front? | **None now**, gate G-K measured in R0 and after R4. |
| **D-RR3** | Ambiguity policy when candidate charges differ. | **Conservative range** (claim only what lies outside it), precedence only with an explicit `PRIORITY`. |
| **D-RR4** | Over-billing: its own class excluded from `revenue_at_risk`, with a separate customer-exposure Incident? This **changes the meaning** of today's net `leakage_found` sum (breaking changes are free here). | **Yes**, separate class, severity set by a threshold. |
| **D-RR5** | Confidence: the formula (exactness × support) and its floors; the USD threshold and severities for the Incident. | Ship the formula; defaults are configuration; operator or a prospect sets numbers. |
| **D-RR6** | Incident unit: per root cause (deduped), per call, or per control? | **Per root cause.** |
| **D-RR7** | Build allowances and bundles (R5) now, or only when a prospect names one? | **Demand-gated**, like the other P3 packs. |
| **D-RR8** | `subscription_history` is a per-subscriber Reference (large, changing). Accept it as a Reference, or a Stream? Classification of `SUBSCRIBER_ID`? | Reference with `scd2`; `INTERNAL`, synthetic ids as in the pack today. |
| **D-RR9** | Boundary with gap Q: retail here, wholesale there, one `rerated_usage` contract. | **Confirm**, and let Q's plan cite the contract. |
| **D-RR10** | Commercial: `billable`, `orderCode` for the new solution pack; the matrix cell for C after R6 (**P** to **Y**?). | The operator's call; the matrix is updated only when a slice ships. |

## 10. Risks

1. **Garbage-in tariff.** The dominant error source in re-rating is the tariff table itself, not the arithmetic. That is why
   R1 starts with tariff hygiene and why `agree` / `disagree` exist.
2. **Synthetic corpus only.** A real operator's tariff is richer. The closed vocabulary (S3) is chosen to cover common
   structures; anything else is the G-K(b) trigger, and the pack says so rather than guessing.
3. **Volume unknown.** Nothing is measured above the 600-xDR golden corpus for this control. R0 measures before any
   promise (the Reconciliation lane's own rule: every 10M-row claim is a hypothesis until measured).
4. **Overclaiming a cause.** A counterfactual shows sufficiency, not necessity; mitigated by keeping alternatives and the
   "consistent with" wording.
5. **Golden churn.** The 12-to-1 reduction deliberately changes counts that `telco-ra` pins; avoided by not editing
   `telco-ra`. If D-RR1 goes the other way, the whole of `TelcoRaGoldenTest`, `telco-ra`'s solution page and the template's
   `contents[]` line change together.
6. **Time zone and cycle boundaries** (§6.1).
7. **Netting.** If D-RR4 is declined, over-billing keeps netting against leakage and the headline can read lower than the
   truth; the plan records it either way.
8. **Two lanes.** The sibling Q lane and this one meet only at `rerated_usage`; any other shared file is a collision.
9. **Vocabulary.** Use *Pipeline*, *Dataset*, *Incident*, *Collector*, *Expectation* / *Alert Rule* / *Decision Rule*,
   *Measure*, *Reference*; never bare "rule", and avoid the other banned synonyms (`docs/GLOSSARY.md` §0).

## 11. Backlog and docs touched

- `BACKLOG.md`: a new P3 row **`PACK-RERATING-1`** after `PACK-PARTNER-SETTLEMENT-1` (the re-rating half, split out; the old
  row is left for the Q lane to narrow). Close it when R6 ships; file residuals as their own rows.
- `INDEX.md`: this plan's row in the in-flight table.
- On ship (R6): distil as-built facts into [`okf/capabilities/spaces/spaces.md`](../okf/capabilities/spaces/spaces.md) (a new
  subsection beside §3.5), add a solution page under `stakeholders/solutions/`, update the matrix row C, then `git mv` this
  plan to `archived-documents/plans-archive/`.

## References

- Matrix: [`stakeholders/COMPETITIVE_CAPABILITY_MATRIX.md`](../stakeholders/COMPETITIVE_CAPABILITY_MATRIX.md) rows C and Q.
- Revenue assurance pack: [`stakeholders/solutions/revenue-assurance.md`](../stakeholders/solutions/revenue-assurance.md).
- Reconciliation module: [`stakeholders/modules/reconciliation.md`](../stakeholders/modules/reconciliation.md); uplift plan
  [`reconciliation-uplift-plan.md`](reconciliation-uplift-plan.md).
- Analytics Kernel: [`okf/backend/analytics-kernel.md`](../okf/backend/analytics-kernel.md); [`analytics-core-plan.md`](analytics-core-plan.md).
- Canonical vocabulary: [`GLOSSARY.md`](../GLOSSARY.md) (the single source of truth).

## 12. Measurements (filled by slice R0)

_Empty until R0 runs: scd2 behaviour, Alert Rule `by` cardinality, staged-SQL throughput and memory, the test's owning module._
