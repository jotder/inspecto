# Re-rating / tariff validation: a "smart" plan

> **ARCHIVED 2026-10-22** — open work moved to docs/superpower/telco-packs-plan.md; as-built facts in the telco-rerating hub
> ([`okf/product/solution-pack/telco-rerating/`](../../okf/product/solution-pack/telco-rerating/index.md), `plan-as-built.md`).

> 🟡 **PLAN 2026-10-18 (`PACK-RERATING-1`): R0 measured, R1 and R2 built 2026-10-20 (the `telco-rerating` Space Template: tariff hygiene, ambiguity verdicts, counterfactual causes with confidence, root causes, revenue at risk; an unbilled preview); R3 to R7 open (§12 the measurements, §13 R0 and R1 as built, §14 R2 as built).** Competitive gap **C** (re-rating / tariff
> validation) is **Partial** in [`COMPETITIVE_CAPABILITY_MATRIX.md`](../../okf/product/layer/product/brochure-competitive-matrix.md): Inspecto has one SQL
> control in the `telco-ra` Space Template and nothing more, while LATRO, Mobileum, Neural and Araxxe sell re-rating and
> Subex and TEOCO sell it partially. The row `PACK-PARTNER-SETTLEMENT-1` filed both gap C and gap Q together; this plan
> takes the **re-rating half**. Gap Q (partner settlement) is planned in a sibling document and is **not** touched here.
> Decisions **D-RR1…D-RR9 answered 2026-10-20** (§9; D-RR10 stays the operator's call). Volume claims are measured in §12 for the synthetic day only; gate G-K was not tripped at R0.
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
Risk Scores to ([`okf/product/module/anomaly/spec-anomaly-scores.md`](../../okf/product/module/anomaly/spec-anomaly-scores.md)): every verdict
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
| **SQL controls** (`sql.template`) | **Yes, for everything in S1–S7 except possibly stateful allowances at volume.** | `ra_rerating` already expresses effective dating, ambiguity and tolerance in one statement and is pinned by a golden corpus. The platform already runs the telco-fraud detection windows and the whole `telco-ra` chain as `sql.template` Jobs; layering through Job-written Datasets is the recorded design (it "avoids cycles, hidden cost and lineage ambiguity", [`okf/product/module/config/plan-spaces.md`](../../okf/product/module/config/plan-spaces.md) §3.5, business-assurance pack). Window functions cover the allowance running balance (order by `EVENT_TS`, `XDR_ID`; `transform.analytics.running` is a shipped Step if a Pipeline form is wanted). ⚠ **Not yet measured:** allowance state at 10M+ rows (slice R0). |
| **Reconciliation** | **Half.** Right for *re-rated total vs billed total* per subscriber and month; wrong for pricing. | It compares two or three Datasets at a key grain with `sum` or `count` and `exact` / `absolute` / `percent` tolerance, and gives the Break lifecycle (open, assigned, resolved, `auto_closed`, recurrence), dedupe and Incident promotion ([`okf/product/module/reconciliation/architecture-overview.md`](../../okf/product/module/reconciliation/architecture-overview.md) §4, §5). It has no arithmetic over rows, so it cannot price. Its strength is exactly the step `telco-ra` lacks: comparing the **independently re-rated** amount, not the rater's own charge, with the invoice. |
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
[`okf/product/module/config/plan-spaces.md`](../../okf/product/module/config/plan-spaces.md) §3.5).

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
| **R0 spike** ✅ 2026-10-20 (§12) | Facts, no shipped config: (1) does `load: scd2` keep history; (2) Alert Rule `by` on a high-cardinality column and `stormCap`; (3) staged-SQL throughput on a synthetic 5M-row usage day (rating + allowance window) and a memory ceiling; (4) the module that owns `TelcoRaGoldenTest`. | A measured table in this plan (§12) with budgets that gate G-K(a); each of the four answers recorded; no repository change except this document. | Throwaway harness, not committed; numbers only. |
| **R1 versioning + ambiguity** ✅ 2026-10-20 (§13) | Template skeleton `telco-rerating`; `tariff_version` Reference; `rr_tariff_hygiene`; `rr_rerate` with the S2 verdicts; `rerated_usage`; a corpus generator `RerateCorpus` (fixed seed) planting: a stale version, an overlap with the **same** rate, an overlap with **different** rates, a gap, a negative rate, no tariff, a call spanning a change, one-cent-off. | The golden asserts **exactly** the planted set per control; the 12 same-rate ambiguous calls become **0** call findings and **1** tariff finding; the different-rate overlap yields a range and a data-quality row, not leakage; look-alikes silent. | `TelcoRerateGoldenTest` (template creates; Jobs run; Datasets equal the planted set), `RerateCorpusTest` (corpus is deterministic and the planted/benign sets are disjoint), a SQL-clause mutation pass. |
| **R2 explanation + root cause + risk** ✅ 2026-10-20 (§14) | `rr_explain`, `rr_root_cause`, the `LEAKAGE_LOW` / `HIGH` / `OVERBILLED_AMOUNT` / `RUN_RATE_PER_DAY` columns; KPIs `revenue_at_risk` and `overbilled_amount`; the over-billing class (D-RR4). | Every planted mismatch gets its planted `CAUSE` with confidence at or above the floor; an unplanted mismatch is `unexplained` with confidence 0; three stale-rate calls form **one** root cause; an over-billed call never enters `revenue_at_risk`; the group identity is **stable across two runs**. | extend the golden; `RerateExplainTest` (each hypothesis, both-fit case keeps two causes); a two-run stability test. |
| **R3 plan inference** ✅ 2026-10-21 (§15) | `subscription_history` feed (a plain feed, not a Reference: D-RR8 revised, §15); S4; cause `wrong_plan`. | Planted wrong-plan subscribers are inferred with the planted confidence; the mid-window plan change and the too-few-rows subscriber stay silent. | golden extension; the floor and share parameters as `TelcoRerateGoldenTest` cases (no separate `PlanInferenceTest` class, §15). |
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

**ANSWERED 2026-10-20 (operator, accepted the recommended answer): D-RR1…D-RR10, all as recommended.** D-RR1 new
`telco-rerating` template; D-RR2 no kernel now, gate G-K measured in R0; D-RR3 conservative range; D-RR4 separate over-billing
class excluded from `revenue_at_risk` (**answered, but called out because it changes existing behaviour:** today's net
`leakage_found` sum no longer nets over-billing against leakage); D-RR5 formula shipped, defaults are configuration; D-RR6 per
root cause; D-RR7 R5 demand-gated; D-RR8 `scd2` Reference, `INTERNAL` (**revised at R3: a plain feed, §15**); D-RR9 retail here / wholesale there, one `rerated_usage`
contract; D-RR10 commercial (`billable`, `orderCode`, matrix cell C) **stays the operator's own call** and is still open.
R0 and R1 are now startable; R5 stays demand-gated.

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
- On ship (R6): distil as-built facts into [`okf/product/module/config/plan-spaces.md`](../../okf/product/module/config/plan-spaces.md) (a new
  subsection beside §3.5), add a solution page under `stakeholders/solutions/`, update the matrix row C, then `git mv` this
  plan to `archived-documents/plans-archive/`.

## References

- Matrix: [`okf/product/layer/product/brochure-competitive-matrix.md`](../../okf/product/layer/product/brochure-competitive-matrix.md) rows C and Q.
- Revenue assurance pack: [`okf/product/solution-pack/business-assurance/architecture-revenue-assurance.md`](../../okf/product/solution-pack/business-assurance/architecture-revenue-assurance.md).
- Reconciliation module: [`okf/product/module/reconciliation/architecture-overview.md`](../../okf/product/module/reconciliation/architecture-overview.md); uplift plan
  [`reconciliation-uplift-plan.md`](reconciliation-uplift-plan.md).
- Analytics Kernel: [`okf/product/module/analytics/spec.md`](../../okf/product/module/analytics/spec.md); [`analytics-core-plan.md`](analytics-core-plan.md).
- Canonical vocabulary: [`GLOSSARY.md`](../../GLOSSARY.md) (the single source of truth).

## 12. Measurements (slice R0, 2026-10-20)

Four questions, four answers, one table. Nothing here changed a repository file except this plan; the harness was a throwaway
(a single-file Java program over `duckdb_jdbc` 1.5.6.0 that reads the Job's own SQL files, not committed).

### 12.1 The four answers

1. **Does `load: scd2` keep history? It keeps versions, but not the history this pack needs. `tariff_version` is a plain feed with the
   versions in the file.** Read from `ReferenceReader`, `ConsignmentIngestStrategy.stampReferenceVersions` and pinned by
   `ReferenceScd2CharacterizationTest` (2 tests, re-run 2026-10-20, 0 failures). An `upsert` / `scd2` Reference is an **append-only version store**: a
   changed key appends one row with `__key_hash`, `__row_hash`, `__valid_from`, `__op`, `__batch_id`; nothing closes the old row, there is
   no `valid_to`, and `__valid_from` is the **ingest wall clock** (`now()`), not a date the feed carries. A `sql.template` Job that names
   the store as a source reads **every** version row with those system columns; only `ReferenceReader`'s view (latest version per key, one
   as-of instant per binding) hides them. So `scd2` cannot say "this rate applied from 1 June to 30 June" for a call that arrived late, and
   gives a Job superseded rows to filter. §5.1's fallback applies: `TARIFF_VERSION_ID`, `EFFECTIVE_FROM` and `EFFECTIVE_TO` are columns of
   the file and the whole table is delivered each time. **This also bears on D-RR8** (`subscription_history` as an `scd2` Reference): the same
   reasoning says the history (`FROM_TS`, `TO_TS`) must be in the file; decided at R3 (§15): a plain feed.
2. **Alert Rule `by` on the root-cause column, and `stormCap`: it works as a plain column key; size `stormCap`, and expect "no second
   Incident", not "updated Incident".** Read from `AlertService.evaluateGrouped` and the bypass pack's measured answers (plan §16.4 there);
   `PerEntityAlertTest` (module `platform/inspecto-engine`, 15 tests, 0 failures) re-run 2026-10-20. `AlertRule.by` is a list of plain
   Dataset columns, so `by: ROOT_CAUSE_ID` is valid; one Alert per Alert Rule counts the breached keys and one Incident opens per key
   (dedupe `<rule>|<keyId>`), at CRITICAL or ERROR. Above `stormCap` (default 100) the rule keeps its Alert and opens **one** storm
   Incident; no per-key Incident opens or heals until the count is back under it, and persisted open-key digests are cut at 1,000. Three
   consequences for R2 and R6: (a) the probe groups the **whole** Dataset, every retained day, so `rerating_root_causes` must carry one row
   per `ROOT_CAUSE_ID` per window and the Alert Rule must read a Dataset whose `ROOT_CAUSE_ID` is **stable across days** (no date in the
   id); (b) a persisting cause is edge-triggered: it opens one Incident and then nothing, **it does not update that Incident's description**
   (§6.5 and §2 S8 say "updates"; read them as "does not open a second one"), and a cause that clears and returns opens a new Incident unless
   `healAfterSweeps` bridges the gap; (c) the expected cardinality is the number of distinct (cause, plan, service, version, rate pair)
   groups, a few dozen on the synthetic corpus, so the default cap of 100 is enough there but a real tariff with hundreds of plans needs
   `stormCap` raised, never above 1,000.
3. **Staged-SQL throughput** on a synthetic 5M-row usage day: table below. Gate G-K(a) is **not tripped** on this volume.
4. **The module that owns `TelcoRaGoldenTest`: `features/inspecto-reconciliation`** (`features/inspecto-reconciliation/src/test/java/com/gamma/job/TelcoRaGoldenTest.java`,
   corpus `TelcoRaCorpus.java`, both read and re-run 2026-10-20). The BACKLOG text saying it moved to module `inspecto` is wrong. The new
   pack's tests live in module `inspecto`, beside `BypassFeatureGoldenTest`, because they boot the template through `ControlApi`; the two
   golden tests do not share a module and do not need to.

### 12.2 Throughput and memory

Synthetic day: 50 plans by 3 services, one tariff row per key and a second, overlapping row for one key in ten (the same rate for half of
those), 300,000 subscribers, 99.9 % correctly charged. 12-core developer box, 4 threads, DuckDB memory cap 3,246 MiB (the engine default) unless
stated, peak read by sampling `duckdb_memory()` every 50 ms. The stage is the Job's own statement (`rr_rerate`); the allowance stage is a
harness cut (a running balance per subscriber, plan, service and month over `EVENT_TS, XDR_ID`, free units split off) and **its peak includes
the harness's resident usage and re-rated tables**, so it overstates the stage itself.

| Rows | `rr_rerate` | peak | allowance stage | peak | spill |
|---|---|---|---|---|---|
| 1,000,000 | 4.7 s | 0.38 GiB | 0.6 s | 0.49 GiB | none |
| 5,000,000 | 25.4 s | **1.78 GiB** | 2.8 s | 2.43 GiB | none |
| 5,000,000, 1 GiB cap | 28.2 s | 1.00 GiB (the cap) | 4.7 s | 1.00 GiB | yes |
| 10,000,000 | 52.5 s | 3.17 GiB (97 % of the cap) | 10.0 s | 3.17 GiB | yes |

The real Job through `JobService` (feed Parquet, `IncrementalSink`, the Decision Rule pass and the Parquet write): **5,000,000 rows in 16.9 s, process commit peak 2.72 GiB** (JVM plus DuckDB), 5,000,000 rows written to one `window_date` partition, and `rr_tariff_hygiene` 0.24 s. (A throwaway test that wrote the day's Parquet straight into the Space and triggered both Jobs; not committed.) The standalone 25.4 s above ran with a build running beside it, the 16.9 s on an idle box, so the standalone figures are an upper bound.

**The first formulation was reworked.** The first `rr_rerate` joined every call to its candidate tariff rows and aggregated per call
(`count`, `string_agg`, `arg_max`). On the same data it took 28.6 s and 3.17 GiB at 5M rows (98 % of the cap, spilling), 82.7 s at 10M (with a build running beside it), and
ran out of memory under a 1 GiB cap. The statement now cuts each plan and service into **segments** between consecutive tariff boundaries,
decides the candidates once per segment (a few hundred rows) and joins each call to the one segment in force at its start; the
per-call work is a join and arithmetic. That halved the 5M peak, removed the spill and made a 1 GiB cap complete. Because `ROUND(units x rate)`
is monotone in the rate, the least and greatest candidate amounts are the amounts at the least and greatest rate.

### 12.3 Budget and gate G-K

**Budget (set here, gates G-K(a)):** a 5M-row day through `rr_rerate` in 60 s on 4 threads, peak DuckDB memory no more than the default
cap (3,246 MiB), no spill, and the stateful allowance stage inside the same envelope. It mirrors the bypass pack's G-SB(a).

| Clause | State at R0 |
|---|---|
| G-K(a) allowance stage cannot meet the budget on the synthetic volume | **Not tripped.** 5M rows: `rr_rerate` 25.4 s and 1.78 GiB, allowance 2.8 s; at 10M rows both stages are cap-bound and spill (52.5 s, 3.17 GiB), which the runbook turns into advice (raise `processing.duckdb.memory_limit` above roughly 8M rows a day). The allowance stage measured is a cut: no cycle roll-over, bundle priority or overage plan, which R5 would add. |
| G-K(b) a tariff structure outside S3 named by a prospect | **Not tripped.** No prospect has named one. |
| G-K(c) a stage's SQL cannot be unit-tested in isolation | **Not tripped for R1.** Both statements are pinned by a golden with an independent oracle and a 73-mutant clause pass (section 13). Re-check at R4, where a stage per feature is added. |
| G-K(d) gap Q needs the same arithmetic | **Open by design.** The `rerated_usage` contract is published; Q's plan has not yet asked for a different re-rating. |

Not measured: a real operator's volume or tariff, the cycle logic of allowances, the statement under a 10M+ row day with the cap raised, and
`rr_rerate` over more than one retained day of `tariff_version` history at volume (the tariff is hundreds of rows).

## 13. R0 and R1 as built (2026-10-20)

Commits on `lane/rr-r0r1`: `f2774acc4` (the template, both Jobs, the corpus and the golden, the hub slots, the offering and the Product Map Item) and the docs commit after it (this section, §12, the board row and the map). Hub: [`okf/product/solution-pack/telco-rerating/`](../../okf/product/solution-pack/telco-rerating/index.md).
R0 is section 12. Nothing else of R0 is in the repository.

**Delivered (R1).** The `telco-rerating` Space Template: two Pipelines (`rated_usage`, the canonical Schema of `telco-ra`, and
`tariff_version`), their Datasets and zero-row seeds, two chained `sql.template` Jobs, a runbook skeleton and the inert SLA examples every
template carries. `rr_tariff_hygiene` (04:30 daily) judges the whole tariff table into `rerating_tariff_findings`: `overlap_same_rate`,
`overlap_different_rate` (with `PRIORITY_ORDERED`), `gap`, `negative_rate`, `missing_rate`, `missing_start`, `inverted_interval`. `rr_rerate` (on the
signal `job.run.completed` of `rr_tariff_hygiene`; window `$yesterday`, `incremental` by `window_date`, `retain: 31`) writes the
`rerated_usage` contract, 21 columns, one row per call: `CANDIDATES`, `RESOLUTION` (`single`, `agree`, `precedence`, `disagree`, `none`),
`TARIFF_VERSION_ID`, `CANDIDATE_VERSIONS`, `EXPECTED_CHARGE`, `EXPECTED_LOW`, `EXPECTED_HIGH`, `UNDERCHARGED_AMOUNT`,
`OVERBILLED_AMOUNT`, `REASON`, `FINDING`. Registered where `telco-bypass` is: `offerings/standard.toon` `contentPacks[8]`,
`ControlApiSpaceTemplateSeedGateTest.RUNBOOK_PACKS`, Product Map Item `solution-pack.telco-rerating` (`billable: false`, `status:
preview`: **D-RR10 stays the operator's**), the hub's five slot documents. No new Step Processor, Job Type, route, edition entry other
than the content-pack list, or write to `telco-ra` (its counts are untouched).

**What the golden pins.** Test side, in module `inspecto` beside `BypassFeatureGoldenTest`: `RerateCorpus` (seed 20261021, 533 usage rows,
46 tariff rows, every plan, subscriber and rate fictional), `RerateCorpusTest` (5 tests) and `TelcoRerateGoldenTest` (15 tests).
(1) Every one of the 21 columns of every one of the 531 in-window calls, and all 13 columns of all 19 tariff findings, equal an
independent plain-Java oracle (`BigDecimal`, no SQL). (2) The findings are exactly the planted ones: 14 leakage (a stale version and its
instant-of-change boundary, a wrong charge inside a same-rate duplicate, charges below an overlap's range and at the tolerance edge), 6
over-billed, 7 inside an ambiguous range (data quality), 9 with no usable tariff (a gap, a plan with no row, a row with no rate, a row with no start); and
every look-alike is silent (a call that starts before a change and runs past it, `FROM` inclusive and `TO` exclusive, one cent either
way and AT the 0.02 tolerance, a zero rate, an inverted row, two versions that meet exactly, an open row that outlives a closed one, a
reversal). (3) The plan's headline: the 12 calls on a duplicate same-rate tariff row, which the starter control reports as 12
`ambiguous_tariff` rows, are **0 call findings and 1 tariff finding**, while a wrong charge inside that group is still leakage. (4) Different
rates give a range, not a guess: a charge inside it, or at its tolerated edge, is data quality; outside it is leakage or over-billing
measured to the nearest bound. (5) Precedence needs an explicit `PRIORITY`; a blank counts as lowest and a tie is a disagreement. (6)
No netting: under-charged and over-billed amounts are separate columns, never both on one call; the planted sums are 10.26 and 4.26 and
neither moves the other. (7) A tariff feed with no `PRIORITY` column still runs and loses only the calls that needed it. (8) `tolerance`
is honoured as a Job parameter. (9) Both statements give identical rows under two host session zones. (10) A same-window re-run
duplicates nothing, the next window keeps the earlier day, the hygiene snapshot is replaced and not doubled. (11) The signal chain runs
`rr_rerate` after `rr_tariff_hygiene`. (12) The seeds: the feed seeds equal what the Pipelines write, the two Job-written seeds equal the
Jobs' own `DESCRIBE`.

**Mutation pass.** The golden carries it: 46 `rr_rerate` and 27 `rr_tariff_hygiene` mutants, each a one-clause string change of the Job's own statement (join keys and boundary comparisons, `CASE` branches, the window edges, rounding, the tolerance in both directions, priority and tie logic, hygiene labels, periods and window frame). The unmutated statement is first run through the same harness and must equal the Job's own rows; a mutant that survives, or whose text is not found, fails the test. **This is a broad pass, not every clause.** Independent verification found hygiene mutants that still survive the corpus: dropping `EFFECTIVE_FROM` from the `row_number()` order, `AND a.USABLE AND b.USABLE` reduced to `AND b.USABLE`, the `TARIFF_VERSION_ID DESC` tie order (the corpus has no overlap with tied `EFFECTIVE_FROM`), `PREV_ID` taken by greatest `EFFECTIVE_FROM`, and moving the precedence test ahead of the agree test; those clauses are unpinned (R2 should add corpus rows for them). Three equivalent clauses found by that review were removed from `rr_rerate` (`TOP2[1] IS NOT NULL AND`, and `CANDIDATES > 0 AND` in the under and over tests); earlier the validity filter on the tariff, the first overlap bound and `PREV_N > 0` were removed the same way. Corpus calls were added for a rounding that matters only for a precedence winner, a precedence winner that is the dearer row, and a reversal.

**Known limits (verification findings, recorded not fixed).** (a) `PRIORITY_ORDERED` is judged per pair, `rr_rerate` per call: with two rows tied at the top priority and a blank third at another rate, hygiene marks both mixed pairs ordered while the call is a `disagree`. (b) A null `CHARGE` or null `USAGE_UNITS` on a call gives no finding (no `data_quality` row for an unratable call); R2 decides. (c) `agree` ranks above `precedence`: with zero units, rows at different priorities agree and the lower-priority row is named. (d) The gap row's `OTHER_TARIFF_VERSION_ID` is `arg_max` by `EFFECTIVE_TO`; two rows ending together make it unspecified, so `FINDING_KEY` may not be stable for that shape. (e) A row with a blank start is reported (`missing_start`) and excluded from both statements; before this review it was silent. **R2 decided (a) to (d) and pinned the five unpinned hygiene clauses: §14.**

**Deviations from the plan, and traps found.**

- **`tariff_version` is a plain feed, not `scd2`** (section 12.1): `scd2` stamps ingest time and has no end. D-RR8 (`subscription_history` as an
  `scd2` Reference) got the same look at R3 and is a plain feed too (§15).
- **Precedence is `PRIORITY` only (D-RR3).** Section 6.2 and S2 also named "latest `EFFECTIVE_FROM`" as a tie-break; D-RR3 as answered says
  precedence only with an explicit `PRIORITY`, and that is what is built. "Highest number wins", a blank counts as lowest.
- **`rerated_usage` carries the call verdict** (`REASON`, `FINDING`, the two amounts), not only the intermediates, because section 5.2's
  `rerating_findings` belongs to R2's `rr_explain` and R1's done-criteria ask for exact call findings. R2 reads `FINDING IS NOT NULL` from it and
  adds the cause; it does not need to re-rate.
- **The tariff is cut into segments.** The statement of section 12.2; the first, per-call formulation spilled at 5M rows.
- ⚠ **A seed at the root of a Hive-partitioned store breaks every read.** `partitionKey: EVENT_DATE` (as in `telco-ra`) writes
  `year=/month=/day=` folders, and a `schema-seed.parquet` beside them fails the view with `Hive partition mismatch`. This pack's
  `rated_usage` drops `partitionKey` (flat per-day files; the Job filters on `EVENT_TS`, which Parquet row-group statistics prune), and the seed sits
  at the store root as in `telco-bypass`. The warning "no partitions[] or partitionKey" at ingest is expected.
- ⚠ **A failed Job's message shows only the first line of a DuckDB error** ("Attempting to execute an unsuccessful or closed pending query
  result"); the cause is on the next lines, in the surefire report.
- `getString` of a `TIMESTAMP` prints a trailing `.0`; the golden reads it as `LocalDateTime`.
- `max(column, 2)` returns the two highest non-NULL values as a list: "is there exactly one highest `PRIORITY`" needs no window function.
- **Orphan-plan detection (S1) is not built** (it needs a plan Reference); the corpus plants no plan that tariff-only and usage-only disagree on
  except "no tariff row at all", which is a call finding.
- **Calls on a defective row are judged against its numbers.** A negative rate is reported by the hygiene Job, and the corpus plants no call on
  it; what `rr_rerate` should do with one (rate it, or refuse to) is a decision for R2, where cause and confidence arrive.

**Measurements.** Section 12. Not measured: the cycle logic of allowances (R5), a real operator's volume, tariff or signal quality.

**Not built, still open.** R2 (`rr_explain`, `rr_root_cause`, `LEAKAGE_LOW` / `HIGH`, `RUN_RATE_PER_DAY`, the KPIs and the over-billing Incident
class), R3 plan inference, R4 rate structure (and the second zone test), R5 allowances (demand-gated), R6 the re-rated-versus-billed
Reconciliation, the Alert Rule by `ROOT_CAUSE_ID`, the dashboard, sample CSVs for the gallery, the matrix row C; R7 only if G-K trips.
D-RR10 (commercial) is the operator's call.

## 14. R2 as built (2026-10-20)

Commits on `lane/rr-r2`: the slice (template, corpus, golden) and the docs commit after it. Hub: [`okf/product/solution-pack/telco-rerating/`](../../okf/product/solution-pack/telco-rerating/index.md).
R2 builds the explanation, the root-cause grouping and the revenue-at-risk columns of §8, nothing from R3 on, and does not touch the commercial
decision D-RR10 (no offering, `billable` or matrix change).

**Delivered.** Two more chained `sql.template` Jobs and two Datasets. `rr_explain` (on the signal of `rr_rerate`; the same window, `incremental` by `window_date`,
`retain: 31`) writes `rerating_findings`: one row per finding of the day (any `FINDING`, 23 columns), with `CAUSE`, `CAUSE_CONFIDENCE`, `CAUSE_ALTERNATIVE`,
`CAUSE_TARIFF_VERSION_ID` (the version the cause points at), the call's own `TARIFF_VERSION_ID` and `CANDIDATE_VERSIONS`, and the amounts: `LEAKAGE_LOW`
(the shortfall against the nearest bound: **revenue at risk**), `LEAKAGE_HIGH` (against the dearest candidate: an ambiguous tariff may justify the gap between the two),
`OVERBILLED_AMOUNT` (customer exposure, never in `LEAKAGE_LOW`) and `LEAKAGE_AMOUNT` (a copy of `LEAKAGE_LOW`, kept so the starter control's leakage union can read the Dataset).
`rr_root_cause` (on the signal of `rr_explain`) replaces the snapshot `rerating_root_causes`: one row per `ROOT_CAUSE_ID` over every retained day, with `CALLS`,
`FIRST_DAY`, `LAST_DAY`, the three sums, `RUN_RATE_PER_DAY` (`LEAKAGE_LOW` over the calendar days from first to last, a `DECIMAL(18,2)`), the weakest confidence in the group and up to three
example `ITEM_KEY`s. Two KPIs: `revenue_at_risk` = `sum(LEAKAGE_LOW)` and `overbilled_amount` = `sum(OVERBILLED_AMOUNT)`, both over `rerating_findings`, `direction: down`.
Zero-row seeds for both Datasets; no route, Step Processor, Job Type, edition entry or `telco-ra` change.

**The causes (S5, three of the nine hypotheses).** A leakage or over-billing is tested against counterfactual re-rating, each a one-parameter change that must reproduce the
charge within `tolerance`: `stale_version` (the most recently ended version of the plan and service), `lower_priority_rate` (a row in force that lost on `PRIORITY`; only for a
`precedence` call; the best-fitting loser) and `early_version` (the next version, billed before it starts). A data-quality finding carries its own reason as its cause (`no_tariff`,
`ambiguous_tariff`, `unratable_call`) with confidence 1. **Confidence** = exactness (1 when the counterfactual reproduces the charge exactly, `within_tolerance_confidence`, default 0.9, when only
within the tolerance) × support (the share of the **cohort**, the findings of one finding class with the same plan, service and candidate versions on the day, that the same hypothesis also explains),
rounded to four places. Below `confidence_floor` (0.5, a confidence AT it is kept) or with no fit, the cause is `unexplained` with confidence 0 and no alternative. Every other fitting hypothesis is kept in
`CAUSE_ALTERNATIVE`; the leader is the highest confidence, ties broken by the fixed order stale, lower priority, early. The wording is "consistent with", never proof. All three numbers are Job parameters.
Not built, by design of the slice: `wrong_plan` (R3, needs `subscription_history`), `fractional_rate`, `rounding_mode` / `rounding_scope`, `increment_ignored`, `tax_basis` (R4) and `allowance_not_applied` (R5).

**Root cause identity.** `ROOT_CAUSE_ID` = `CAUSE|FINDING|PLAN|SERVICE|CANDIDATE_VERSIONS|CAUSE_TARIFF_VERSION_ID` (empty parts dropped): readable, no date, so a cause keeps its id from day to day and a re-run
gives the same ids. The group is therefore also the rate pair (the version in force and the version the charge matches). Plan §12.1 answer 2 still governs the Alert Rule that R6 puts on it: one Incident per id, not updated while it persists.

**What R1 left open, decided.**

- **A call on a negative-rate row:** never rated against. `rr_rerate` reads only rows with `RATE_PER_UNIT >= 0`, so a call that only that row covers has no tariff (`no_tariff`, data quality) and the hygiene Job reports the row. A zero rate is still rated. (Before: judged against the negative number.) A negative-rate row also explains nothing in `rr_explain`.
- **A null `CHARGE` or `USAGE_UNITS`** on a call with a tariff: `REASON` `unratable_call`, `FINDING` `data_quality`, both amounts 0; never silent. With no tariff at all the call stays `no_tariff`.
- **`PRIORITY_ORDERED` against `RESOLUTION`:** the two answer different questions and both stay. `PRIORITY_ORDERED` is a fact about one **pair**; `RESOLUTION` is the verdict for a call and looks at every row in force at once. They differ (pinned both ways): two rows tied at the top plus a blank third (every pair with the blank row is ordered, the call is `disagree`), and one row alone at the top above a tied pair (the tied pair is not ordered, the call is `precedence`). The hygiene Job's description now says so; the call verdict is the authority.
- **`agree` outranks `precedence`:** kept. With zero units every candidate amount is 0, the call `agree`s and names the first version in id order, whatever the priorities; pinned by a corpus call.
- **The gap row's other version:** the row that ends last, ties to the greater `TARIFF_VERSION_ID` (`arg_max` over `(EFFECTIVE_TO, TARIFF_VERSION_ID)`), so the `FINDING_KEY` no longer depends on row order; a test writes the tariff file in reverse and every Dataset must be identical.

**The five unpinned hygiene clauses** are now pinned by corpus plans, each by a mutant that changes the named row: `row_number()` order by start (PLAN_U: the earlier row has the greater id), its tie order by id (PLAN_UT), `a.USABLE AND b.USABLE` (PLAN_KB: a row with no rate before a good one), the previous row taken by greatest end and not greatest start (PLAN_W), and, in `rr_rerate`, precedence moved ahead of agree (the zero-unit call). Plus the tied-end gap (PLAN_V).

**What the golden pins (extends §13; 24 + 5 tests in all).** Every one of the 23 columns of every one of the 63 findings and every column of the 33 root causes equals an independent plain-Java oracle (`BigDecimal`, no SQL), as before for the 21 columns of the 560 calls and the 37 tariff findings; hand-declared causes and confidences for every planted mismatch (`PLANTED_CAUSE`); three-plus stale calls are **one** root cause (five, exact and within tolerance, with the cohort's sixth call unexplained: confidence 0.8333 and 0.7500); both-fit calls keep both causes; the exact fit outranks the within-tolerance one; a cohort at support 2 of 5 is `unexplained` until `confidence_floor` is lowered; an over-billed call never enters `LEAKAGE_LOW`, any root cause's `LEAKAGE_LOW` or `revenue_at_risk`, and the KPI files are asserted (dataset, measure); a second day adds to the same `ROOT_CAUSE_ID` (first and last day, run rate over two calendar days) and a second run of `rr_root_cause` over the same data gives identical rows; both new statements give identical rows under two host session zones; the same-window re-run duplicates nothing and the snapshot is replaced; the signal chain runs all four Jobs.

**Mutation pass.** 132 one-clause mutants of the four statements (51 `rr_rerate`, 33 `rr_tariff_hygiene`, 34 `rr_explain`, 14 `rr_root_cause`), each must change the rows the oracle pins; the harness first reproduces each Job's own rows (the root-cause harness over both days). **32 of them also name the row they must change** (for example "a negative row is excluded" must change `XG_NEGATIVE-1`, "the floor keeps a confidence AT it" must change `XE_BLANK_PRIORITY-2`), so one that dies by breaking the one-row-per-key contract, or for another reason, fails. Two mutants of R1's list had to be rewritten (the null-rate exclusion became a whole-condition mutant; the `arg_max` text changed) and one clause was removed as equivalent (`RATE_PER_UNIT IS NOT NULL` beside `>= 0`, and `EFFECTIVE_FROM IS NOT NULL` in the explanation, which the validity test already implies).

**Known limits and unpinned clauses (recorded, not hidden).**
- Equivalent on this corpus, so no mutant claims them: `PLAN` and `SERVICE` in the cohort partitions (the candidate version ids imply them), `t.TARIFF_VERSION_ID <> m.TARIFF_VERSION_ID` in `lower_priority_rate` (the winner can never fit a mismatch), the id tie-break of the stale and early hypotheses (no corpus plan ends or starts two rows together beside a mismatch), `ORDER BY RN` of the alternatives (no call fits three hypotheses), and the `>` of the early boundary (no call starts exactly where a row starts and also loses on priority).
- Confidence, the run rate and the division in `LEAKAGE_HIGH` use `DOUBLE` division before rounding; the oracle uses exact decimals. They agree on this corpus, which avoids a value at an exact half in the fifth decimal; a real value at such a tie could round the other way by one unit in the last place.
- A call with no `USAGE_UNITS` and **several** candidate rows has an unspecified `RESOLUTION` (the amounts are null); the corpus plants it on a single-row plan only, and the oracle asserts that.
- Cohort support is per day: the same hypothesis on a day with one call is confidence 1, on a day with a mixed cohort lower. That is the definition, not a defect, but it means a cause's confidence in `rerating_root_causes` is the weakest of its days.
- `rr_explain` joins each finding to the tariff rows of its plan and service; it is cheap on the corpus (0.14 to 0.7 s for 63 findings and 0.06 to 0.2 s for `rr_root_cause` across runs on a loaded box) and **was not measured at volume**: the per-finding work is proportional to findings, not to calls, but a day with millions of findings is unmeasured. The R0 budgets cover `rr_rerate` only.
- A KPI is asserted as a definition (dataset, measure, class separation) and by summing the rows in Java; it was not evaluated through the KPI route.

**Not built, still open.** R3 plan inference and `wrong_plan`; R4 rate structure and its hypotheses (and the second zone test); R5 allowances (demand-gated, D-RR7); R6 the re-rated-versus-billed Reconciliation, the Alert Rule by `ROOT_CAUSE_ID`, the over-billing Incident (severity threshold, D-RR4/D-RR5), the dashboard Widgets, sample CSVs for the gallery, the matrix row C; R7 only if gate G-K trips. Orphan-plan detection (S1) is still not built. D-RR10 (commercial) is the operator's call and was not touched.

## 15. R3 as built (2026-10-21)

Commits on `lane/rr-r3`: the slice (template, corpus, golden) and the docs commit after it. Hub: [`okf/product/solution-pack/telco-rerating/`](../../okf/product/solution-pack/telco-rerating/index.md).
R3 builds plan inference (S4, §6.4) and the cause `wrong_plan` of §8, nothing from R4 on, and does not touch the commercial decision D-RR10 (no offering, `billable` or matrix change).

**D-RR8 revised: `subscription_history` is a plain feed, not an `scd2` Reference.** §12.1 answer 1 showed that an `scd2` store stamps the ingest wall clock as the version start and has no end column, so it cannot say "this subscriber was on plan P from 1 March to 15 June" for a call that arrives late; the same holds for a plan period. The recommendation of D-RR8 ("Reference with `scd2`") therefore does not fit, for the reason R0 recorded for the tariff. The periods are in the file (`SUBSCRIBER_ID, PLAN, FROM_TS, TO_TS`, `TO_TS` blank = open, `FROM_TS` inclusive, `TO_TS` exclusive) and the whole history is delivered each time, exactly as `tariff_version` is. The other half of D-RR8 stands as answered: `SUBSCRIBER_ID` is `INTERNAL`, ids synthetic or masked. A plain `Stream` was not used because a history is not a flow of events: it is a dimension that is read whole. The cost: the file is large and is delivered whole (a real operator's history is millions of rows; not measured, below).

**Delivered.** A third Pipeline and Schema (`subscription_history`), its Dataset, a zero-row seed, and `rr_explain` extended (no new Job, Job Type, Step Processor, route or edition entry; `telco-ra` untouched). `rr_explain` now reads `subscription_history` and has a fourth counterfactual, `wrong_plan` (cause rank 4), with two new Job parameters, `plan_share` (0.6) and `plan_min_rows` (3). The 23 columns of `rerating_findings` are unchanged, so the seed, the root-cause Job and the KPIs are untouched.

**The inference (S4).** For each subscriber with a leakage or over-billing and each service, over the calls of the window day (those with a charge and non-zero units, found or not): for each plan, the number of those calls whose charge the plan's rate **in the version in force at the call** reproduces within `tolerance`; the plan with the most is the inferred plan Q when it is the **only** plan at the top, when it explains at least `plan_share` of the calls and when there are at least `plan_min_rows` calls. A call gets a `wrong_plan` candidate when the history assigns the subscriber **exactly one** plan at the call's start (a call in no period, or in two periods with different plans, claims nothing), that assigned plan differs from Q, and Q's rate in force at the call reproduces the call's charge within `tolerance`. Confidence is exactness (1, or `within_tolerance_confidence`) times Q's share of the subscriber's calls, in the same ranking and under the same `confidence_floor` as every other cause; a call that also fits another hypothesis keeps both. The root cause is the plan pair: `ROOT_CAUSE_ID` is `wrong_plan|FINDING|PLAN|SERVICE|CANDIDATE_VERSIONS|CAUSE_TARIFF_VERSION_ID`, so every subscriber billed on plan Q while assigned plan P is one cause, with no change to `rr_root_cause`. **Deviation from §6.3/§6.4 (recorded):** the support of this cause is the plan's share of the **subscriber's** calls (S4: "confidence = share of rows explained"), not the share of the plan, service, candidate-versions cohort that the other three causes use; the cohort of a wrong-plan subscriber is the plan's whole population and would say nothing about this subscriber. A floor on the number of calls (`plan_min_rows`) is added to §6.4's "rows too few to infer anything", because a share of 2 of 2 is 100 percent and the confidence floor alone would not refuse it.

**What the golden pins (extends §14; 26 tests in all).** Corpus: 14 new subscribers (SUB9001 to SUB9014, none shared with the older archetypes, so no older finding or cause moved; the rotation of the older subscribers is untouched), plans `PLAN_P1` to `PLAN_P7`, 9 new tariff rows, the history file, 123 findings instead of 63 and 628 calls instead of 560 (the older 63 findings and 33 root causes are unchanged and still equal the oracle). The planted cases, each with a hand-declared cause and confidence: billed at another plan's rate in both directions (5 of 6 calls explained: 0.8333; a cent off and AT the tolerance: 0.9 times 1.0); two subscribers on the same plan pair, one root cause (19 over-billed calls of five subscribers, and 8 leakage calls of two); a charge AT the share (3 of 5: 0.6 kept); the version in force (4 of 6: 0.6667; a call at the old rate after the change and one at the new rate before it are not explained); the instant of the change (a call at exactly the end of one period and the start of the next takes the new plan); the top plan when a second plan explains 60 percent too; and the **silent look-alikes**: too few calls, a share of 3 of 6, two plans with the same rate, a plan that was changed at noon whose rating label did not follow (the assigned plan IS the inferred one), two plans assigned at once, and a subscriber with no history at all. (1) Every one of the 23 columns of every one of the 123 findings and every column of the 39 root causes equals the independent plain-Java oracle (`BigDecimal`, no SQL), which was extended with its own inference and its own history lookup. (2) The share, the number of calls and the confidence floor are parameters: three further runs equal the oracle under `plan_share` 0.8, `plan_min_rows` 2 and 6, and a floor of 0.7. (3) Day 2 adds a call of a wrong-plan subscriber at the correct rate: a window mutant that reads it changes the confidence. (4) The order of the history file changes nothing (the existing order test reverses it too). (5) The statement gives identical rows under two host session zones (the existing test).

**Mutation pass.** 25 new one-clause mutants of `rr_explain` (59 of it, 157 of the four statements), each **red for the named row** (57 of the 157 name one): zero units and no charge excluded from the calls inferred from, the window day, the version in force having started and not ended (once for the inference, once for the counterfactual), a rate of another service is not a hit (a MMS rate that charges the same 9.00 as a DATA rate must not tie it), a hit AT the tolerance, the top plan only (a second plan at 60 percent), a single top plan, the number of calls, the share AT its floor and the share itself (a share of 0.5 would pass the confidence floor of 0.5, so the share clause is pinned by a 3-of-6 subscriber), the history's subscriber, `FROM_TS` inclusive and present, `TO_TS` exclusive, present, and an open period, exactly one assigned plan, the history required, the assigned plan must differ, the confidence is the share (`SUP_N / SUP_D`), and the counterfactual is the inferred plan's. Three existing mutant texts were rewritten for the new `coalesce(SUP_D, COHORT_N)` form of the support expression. One mutant (the first draft's "another service" case) survived on the first run because the counterfactual join's own service condition masked it, which is how the MMS-rate case above came to exist; one (the share clause) survived because the confidence floor masked it, which is how the 3-of-6 subscriber came to exist.

**Known limits and unpinned clauses (recorded, not hidden).**
- Equivalent on this corpus, so no mutant claims them: the restriction of the inferred calls to the subscribers that have a finding (`sub`, a cost clause: the result is the same without it), the service on the inference-to-finding join and on the counterfactual join (the inferred plan has a DATA row only where the call is DATA), the best-fitting row of the inferred plan when it has several rows in force at the call (the corpus has none), and the fixed order of `wrong_plan` after the other three causes on a tie of confidence (no call fits `wrong_plan` and another cause with the same confidence).
- Inference is **per day**: a subscriber's share is computed over the day's calls, so a plan change in the middle of the day is read as one population, and a subscriber with calls of one service split across two plans on one day gets no inference when neither reaches the share. Weekly or monthly inference is not built (it would need a window the Job does not have).
- The history is trusted as given: a period with `TO_TS` not after `FROM_TS` is never in force (as an inverted tariff row), and is not reported. There is no hygiene Job for the history (S1 for plans is still not built).
- A call whose rating label is wrong (the history says Q and the rater labelled it P) is a different defect from `wrong_plan` and is `unexplained` here; a `plan_label` hypothesis is not built.
- The 4-decimal rounding of the confidence uses `DOUBLE` division in SQL and exact decimals in the oracle; they agree on this corpus (as in §14).

**Measurements.** `rr_explain` before and after R3 on a synthetic day (a throwaway DuckDB harness, not committed: the Job's own two statements, the pre-R3 one read from `origin/master`, over in-memory tables built to the shape of `rerated_usage`; 50 plans by 3 services, 300,000 subscribers, one plan history row each, 4 threads, the 3,246 MiB default cap, 12-core developer box, each statement run twice, peak by sampling `duckdb_memory()` every 40 ms). **0.1 percent of the calls are findings, on 300 subscribers billed at the wrong plan**, so every finding is a claim and the inference is exercised in full.

| Calls in the day | Findings | before R3 | after R3 | peak over the resident tables, before / after |
|---|---|---|---|---|
| 1,000,000 | 700 | 0.09 s | 0.15 to 0.17 s | 14 MiB / 27 to 33 MiB |
| 5,000,000 | 3,800 | 0.28 s | 0.47 s | 19 MiB / 32 to 34 MiB |
| 10,000,000 | 7,900 | 0.60 to 0.63 s | 0.89 to 0.98 s | 19 MiB / 35 to 36 MiB |

The inference adds about 0.2 s at 5 million calls and 0.3 to 0.4 s at 10 million, and about 15 MiB: its cost follows the calls of the subscribers that have a finding, not the day. R0's budget (a 5M-row day through `rr_rerate` in 60 s, no spill) is untouched, and gate G-K(a) is not affected. **Not measured:** the Job through `JobService` over Parquet (the real scan of the day's partition for the subscribers' calls is one more pass over `rerated_usage` that an in-memory table hides), findings spread over many more subscribers than 300 (the cost then follows the findings times a subscriber's daily calls), a real operator's history (millions of rows delivered whole each time), and any real tariff or plan population.

**Not built, still open.** R4 rate structure and its hypotheses (and the second zone test); R5 allowances (demand-gated, D-RR7); R6 the re-rated-versus-billed Reconciliation, the Alert Rule by `ROOT_CAUSE_ID`, the over-billing Incident (severity threshold, D-RR4/D-RR5), the dashboard Widgets, sample CSVs for the gallery, the matrix row C; R7 only if gate G-K trips. Orphan-plan detection (S1) is still not built, and neither is a history hygiene Job. D-RR10 (commercial) is the operator's call and was not touched.
