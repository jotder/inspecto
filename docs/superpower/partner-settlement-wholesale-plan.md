# Partner / settlement / wholesale: plan (`PACK-PARTNER-SETTLEMENT-1`, settlement half)

> 🟡 **PLAN 2026-10-18; S0 to S3 (the smallest cut) BUILT 2026-10-20, see §16; S4 BUILT 2026-10-21, see §17; S5 to S8 and W1 to W3 not built.** Closes competitive gap **Q** (partner / settlement / wholesale) in
> [`okf/product/layer/product/brochure-competitive-matrix.md`](../okf/product/layer/product/brochure-competitive-matrix.md). Gap **C** (re-rating) has
> its own plan, written in parallel by a sibling session; this plan states only the **contract it needs from it** (§5) and
> does not design re-rating. Decisions below are marked **PS-D1…PS-D10**: each was a recommendation, accepted by the operator
> 2026-10-20 (§11), with how to reverse it. **What is NOT known:** no real partner statement, TAP file or MVNO wholesale invoice has been
> seen in this repository; every format below is synthetic or from public sources, and the vendor claims are published
> claims, not verified behaviour (matrix §2).

## 1. What exists, and what the matrix says it is

Checked against the tree on 2026-10-18.

| Piece | What it really is | Evidence |
|---|---|---|
| `telco-ra` Job `ra_settlement` | **A `sql.template` control, not a Reconciliation.** Voice only (`SERVICE = 'VOICE'`), one flat rate per partner, minutes rounded up per started minute, daily grain `(PARTNER, EVENT_DATE)`, one global `tolerance_pct` (1.0). Findings: `amount_mismatch`, `duplicate_statement`, `split_statement` (data quality), `missing_statement` (data quality), `unknown_partner`, `no_traffic`. Sink Dataset `ra_settlement_findings`. | `spaces/_templates/telco-ra/config/jobs/ra_settlement_job.toon` |
| `ic_statement` feed | Five columns: `STATEMENT_ID`, `PARTNER`, `EVENT_DATE`, `MINUTES`, `AMOUNT` (USD, `DECIMAL(18,4)`). One line per partner and day. No period, currency, direction, destination, version, or control total. | `config/ic_statement/ic_statement_schema.toon` |
| `ic_rates` Reference | Two columns: `PARTNER`, `RATE_PER_MIN`. **Not effective-dated**, no direction, destination class, tier or currency. | `config/ic_rates/ic_rates_schema.toon` |
| `mobile-money` Reconciliation `mm_partner_settlement` | The only true Reconciliation against a partner file: ledger `BILL_PAY` rows vs settlement lines per transaction, `cardinality: one_to_many`, absolute tolerance 0.01. Per-transaction, not per-period. | `spaces/_templates/mobile-money/config/registry/reconciliations/mm_partner_settlement.toon` |
| Reconciliation add-on | Two or three Datasets compared at a key grain; four Break types; Break lifecycle `open / assigned / resolved / auto_closed` with recurrence; `recon.run` Job; one deduped Incident per Reconciliation on a breach; Break → Incident via `POST /recon/promote`. | [`okf/product/module/reconciliation/architecture-overview.md`](../okf/product/module/reconciliation/architecture-overview.md) |
| Incidents, Cases, Workflow & SLA | A Case groups Incidents (Case Rule auto-groups); Impact (suspected / confirmed / recovered / prevented) on an Incident or Case; an authored `workflow` **per object type**; an SLA Policy per object type (targets per priority on a business calendar); Escalation Rule once per breach. | [`okf/product/module/workflow/architecture-workflow-sla.md`](../okf/product/module/workflow/architecture-workflow-sla.md), [`okf/product/module/case-management/architecture-overview.md`](../okf/product/module/case-management/architecture-overview.md) |
| Regulatory Reporting add-on | Report Templates rendered to XML / CSV / JSON from a Case or Incident, four-eyes approval, file-drop submission, audit log. | matrix row T |
| R7 research | The host–MVNO settlement mechanics (§4.2), dispute causes (§4.4) and controls C1…C23 (§5); controls C8, C9, C18 are the settlement ones. | `group-regulator-assurance/research/R7-mvno-roll-up-and-wholesale.md` (a git-excluded working set, so not linked) |

**Correction to carry into the matrix.** Row Q says "partner-statement reconciliation in `telco-ra`". It is a SQL
control with a flat rate, not a Reconciliation, and the `PACK-PARTNER-SETTLEMENT-1` row bundles gap Q and gap C. Both are
corrected in §12.

**Competitor reading (published claims).** Subex and TEOCO are the two strong marks: Subex *ROC / HyperSense* Partner
Settlement and Partner Lifecycle Management; TEOCO *SmartCOGS* network cost and invoice assurance. LATRO, Mobileum and
Araxxe cover billing verification and roaming checks. The common shape is: ingest the partner's invoice, price our own
traffic at the contract, compare, raise a dispute, track it to a credit note.

## 2. The gap

Everything the control does today is true and stays true. The gap is what a settlement team needs and the pack does not model:

1. **Agreements, not partners.** One partner has several agreements (interconnect voice, SMS termination, roaming, MVNO
   wholesale), each with a currency, rate card, rounding rule, tolerance, cut-off, cycle and dispute window. Today there is
   one rate per partner and one global tolerance.
2. **Statements as documents.** Real statements have a period, a version (restated, re-sent), a control total, a due date and
   a currency. A late, restated or duplicate statement is a different finding from a wrong amount.
3. **Position, not just variance.** A partner is owed and owes: net the two directions per cycle, convert to a settlement
   currency, carry a balance forward.
4. **A dispute that lives.** A finding today ends at an Incident. A settlement dispute has an agreed window, an
   evidence pack, a partner reply, a credit or debit note, and ages.
5. **Time.** Aging buckets, accruals for what has not been invoiced yet, and a settlement-cycle calendar.
6. **Wholesale beyond voice.** SMS, data, roaming TAP / RAP, and the MVNO per-unit, capacity and commitment constructs
   (R7 §4.1).

## 3. Scope

**In scope** (the pack, as configuration over existing engines; no new Step Processor is expected, as with `telco-ra`):

| Area | Content |
|---|---|
| Statement ingestion | A `settlement_statement` feed (header + lines) landed by a **Collector** and a Pipeline, versioned, with control totals. |
| References | `partner`, `settlement_agreement` (effective-dated commercial terms), `fx_rate` (daily), `settlement_calendar` (per agreement, per cycle). |
| Compare | Statement lines vs independently re-rated usage (§5), per agreement, period, service and destination class. |
| Netting and balances | Per partner and cycle: payable, receivable, net, in the settlement currency, plus a roll-forward. |
| Disputes | Break → Incident → **Case** per partner and cycle; authored Workflow; SLA Policy; Escalation Rule; Impact. |
| Aging and accruals | Aging buckets of open disputes and unpaid balances; accrual of expected-but-not-invoiced and of disputed amounts. |
| FX | Rate type and date rule per agreement; both currencies kept on every position row. |
| Tolerance and cut-off | Per agreement, from the Reference, never from the SQL. |
| Evidence pack | A per-Case export of our lines, the partner's lines, the variance, the agreement version and the rate applied. |

**Out of scope:** re-rating itself (sibling plan); payment execution and the general ledger (we do not move money, and
the platform already refuses financial trades); signalling, test calls and probes (matrix F, G); bypass detection beyond
CDRs (`TELCO-BYPASS-SIMBOX-1`); regulator roll-up across a group (`group-regulator-assurance/`); a clearing-house
connection (§10, demand-gated).

## 4. Delivery shape

**A new function pack: Space Template `partner-settlement`** (`spaces/_templates/partner-settlement/`), configuration only,
`minEdition: standard`, listed in `offerings/standard.toon` `contentPacks` (and in whichever Offerings list `telco-ra`, as held
by `tools/check-offerings.mjs`). It needs Reconciliation, Incidents, Case Management, Workflow & SLA and Regulatory Reporting,
all of which are `built` in `offerings/standard.toon`. Basic carries none of them: not offered there.

**PS-D1 (recommended): a separate pack, not an extension of `telco-ra`.** `telco-ra` is the generic RA starter with eight feeds
and a pinned golden corpus; settlement adds six more feeds, four References and a dispute chain, and has a different buyer
(wholesale finance, not only RA). *Reverse:* fold the pack's content into `telco-ra` later; template contents are
configuration, so the move is a file move plus a golden re-pin.

**PS-D2 (recommended): leave `ra_settlement`, `ic_statement` and `ic_rates` in `telco-ra` untouched.** They stay the minimal
starter and `TelcoRaGoldenTest` keeps its pinned Settlement count. The new pack's runbook and the `telco-ra` solution page
say "start here; graduate to `partner-settlement`". Breaking changes are free here (operator 2026-09-20), so the only cost
of removing them is a golden re-pin; there is no benefit to paying it now. *Reverse:* delete the three files and re-pin.

Vocabulary: the pack adds the terms in §13; every other word is already canonical (**Pipeline, Dataset, Collector,
Reference, Expectation, Alert Rule, Decision Rule, Measure, Incident, Case**). No banned synonym from `GLOSSARY.md` §0 is used.

## 5. The contract needed from the re-rating plan

Settlement compares **their statement** with **our independently priced usage**. It must not use the billing system's own
rating output (that is what `ra_rated_vs_billed` already checks). From the sibling plan this pack needs one Dataset, called
here `rerated_usage`, and does not care how it is produced.

| Requirement | Detail |
|---|---|
| **R1 Grain** | One row per `(agreement_id, direction, service, destination_class, usage_date, rate_id, rating_run_id)`. Per-record rows are welcome but the pack consumes the aggregate; Reconciliation pre-aggregates per key anyway. |
| **R2 Measures** | `units` (already rounded **by the agreement's rounding rule**: started minute, per second, per 10 KB…), `amount_expected` (`DECIMAL(18,4)`), `currency` (ISO 4217), `record_count`. |
| **R3 Key and attribution** | `agreement_id` is the join key. The pack **owns** `partner` and `settlement_agreement` (commercial terms: currency, cut-off, tolerance, cycle, dispute window, netting group). The re-rating plan owns **price** (rate card lines) and **attribution of an event to an agreement** (trunk, route, PLMN, IMSI range). *Open with the sibling plan: whether the rate card is a separate Reference keyed by `rate_id`, with `agreement_id → rate_card` held in `settlement_agreement`.* |
| **R4 Effective dating** | The rate applied is the one in force at `usage_date` **in the agreement's cut-off zone**; `rate_id` identifies the version, so a mid-period rate change is explainable line by line. |
| **R5 Run identity and re-run** | Every produced row carries a `rating_run_id` and a `rated_at`. A re-run (late usage, corrected rate) produces a new run; the pack keys the compare to the **latest complete run per period** and keeps the older ones for the evidence pack. |
| **R6 Completeness signal** | A Signal (the existing `job.run.completed` guard pattern) naming the period it completed for. The settlement chain starts only on that signal, so a half-written period is never compared. |
| **R7 Exceptions as data** | Events the re-rater could not price (`no_rate_found`, `ambiguous_rate`, `unknown_agreement`) appear as rows with `amount_expected` NULL and a `reason`, not silently dropped. Settlement turns them into `data_quality` findings, never into a partner variance. |
| **R8 Cut-off semantics** | Event time in UTC plus the agreement's `cutoff_zone` and `cutoff_time`; `usage_date` is already bucketed that way. The pack never re-buckets. |

**If the sibling plan cannot deliver R1…R8 as stated**, this plan is the one that adapts: the minimum is R1 (aggregate at the
named grain), R2, R3 and R6; R4, R5, R7 and R8 can be added later without changing the pack's shape, at the price of weaker
evidence. Both plans' owners should confirm this table before either builds (S0).

## 6. What the Reconciliation add-on gives, and what is missing

| Need | Reconciliation today | Verdict |
|---|---|---|
| Two Datasets at a key grain; sum / count Measures | Yes (`keyColumns`, `compareColumns`) | **Use as is** |
| Break lifecycle, recurrence, `auto_closed` when fixed, assignment | Yes (`ReconBreaks`, `ReconStateStore`) | **Use as is** |
| Break → Incident, deduped Incident per run | Yes | **Use as is** |
| One statement line settling in several usage rows and the reverse | `cardinality: one_to_many` and `includeRecordCount: false`, proved by `mm_partner_settlement` and `ra_rated_vs_billed` | **Use as is** |
| Side filters and a column map | `filters`, `columnMap` | **Use as is** |
| Schedule from the UI | Shipped (RECON-UPLIFT-1 U5, `47542c37d`) | **Use as is** |
| **Tolerance per agreement** | `toleranceType` / `tolerance` are fixed in the Reconciliation definition, one per compared column | **Missing** |
| **Currency-aware compare** (compare in the settlement currency) | Compares the stored numeric only | **Missing**, solved upstream in SQL |
| **Cut-off and period windows per agreement** | Day pruning exists; a per-key window does not | **Missing**, solved upstream in SQL |
| **Netting across keys** (our payable vs their receivable) | A Break is one key; no cross-key aggregate | **Missing**, a Dataset, not a Break |
| **Dispute state with a partner-facing lifecycle** | Break states are internal (`open / assigned / resolved / auto_closed`) | **Missing**, lives on the Case (§8) |
| Break detail for >50k Breaks per Reconciliation | Capped at 50,000 recorded Breaks (`MAX_BREAKS`, deliberate) | **Constraint**, §10 risk R4 |

**PS-D3 (recommended): normalise before comparing; do not extend the add-on.** A `sql.template` Job builds
`settlement_expected` and `settlement_billed`, both **already in the settlement currency, already inside the agreement's
cut-off window, and already net of the agreement's tolerance** (a `variance_beyond_tolerance` Measure that is 0 inside the
tolerance and the excess outside it). The Reconciliation then compares the two at exact tolerance 0 on that Measure and
supplies the Break lifecycle, recurrence and Incident. This keeps per-agreement logic in one reviewable SQL control (as
`ra_settlement` does today) and changes no add-on code. **Risk:** a Break whose Measure is "the excess" reads oddly, so S3 must
show the original amounts as extra Break context. *Reverse / alternative:* add per-key tolerance to the add-on
(`toleranceFrom: <column>`), which is a backend change to `ReconService` with its own tests; take it if S3 shows the
normalised Measure confuses analysts. Needs an operator call only if it becomes an add-on change.

## 7. The data model

All Datasets and Pipelines follow the `telco-ra` convention (Pipeline + Schema per feed, `DECIMAL(18,4)` money, fictional
partner names and number ranges, classification on every column).

**References** (effective-dated `valid_from` / `valid_to`, so a change is a new row, never an edit):

| Reference | Key and columns |
|---|---|
| `partner` | `partner_id`, `name`, `type` (interconnect, roaming, MVNO, aggregator), `country`, `status` |
| `settlement_agreement` | `agreement_id`, `partner_id`, `kind` (interconnect voice, SMS termination, roaming IOT, MVNO per-unit, capacity, commitment), `direction` (we_pay, they_pay, both), `currency`, `rounding_rule`, `tolerance_type` / `tolerance`, `cutoff_zone`, `cutoff_time`, `cycle` (`monthly`, `semi_monthly`, `weekly`), `statement_due_days`, `dispute_window_days`, `payment_due_days`, `netting_group`, `fx_rate_type`, `fx_date_rule`, `valid_from`, `valid_to` |
| `fx_rate` | `rate_date`, `from_currency`, `to_currency`, `rate_type` (spot, monthly average, contract), `rate`, `rate_source` |
| `settlement_calendar` | `agreement_id`, `period_start`, `period_end`, `statement_due`, `dispute_window_end`, `payment_due` (generated by an S1 Job from the agreement, with a holiday list as its own small Reference) |

**Streams:** `settlement_statement` (one row per statement line: `statement_id`, `statement_version`, `partner_id`,
`agreement_id` or a partner-side code mapped to it, `period_start`, `period_end`, `usage_date`, `service`,
`destination_class`, `units`, `amount`, `currency`, `line_kind` (usage, adjustment, tax, credit, fee) and a header row with
`control_total_amount`, `control_total_lines`, `received_at`); `settlement_payment` (payments and credit / debit notes
**as recorded by the operator's finance, as a feed**: the pack reads them, never creates them); `rerated_usage` from §5.

**Derived Datasets (all `sql.template`, chained by `on_signal: job.run.completed` with the `telco-ra` halt-on-failure guard):**
`settlement_expected`, `settlement_billed`, `settlement_findings` (the `ra_settlement` shape plus the new reasons in §8),
`settlement_position`, `settlement_rollforward`, `settlement_aging`, `settlement_accrual`.

## 8. How each concern is handled

**Statement ingestion and integrity.** The Collector lands the file; the Pipeline parses it against the Schema. A SQL control
checks, per statement: `SUM(lines) = control_total_amount` and the line count (`control_total_mismatch`, data quality);
a re-sent statement with the same `statement_id` and `statement_version` (`duplicate_statement`); a higher
`statement_version` supersedes the earlier one for that period and agreement (`restated_statement`: reported, and the
older version stays for evidence); an unmapped partner code (`unknown_partner`, already a finding today). *Gap in the
platform to confirm in S2:* version-aware "latest wins" is done in SQL (`QUALIFY row_number()`), not by Pipeline dedup.

**Tolerance and cut-off.** Both come from `settlement_agreement` through the SQL normalisation of PS-D3 and from nothing
else. Cut-off differences are a first-class finding (`cutoff_difference`: the variance disappears when the partner's
cut-off is applied), because R7 §4.4 names cut-off and time-zone mismatch the first dispute cause. A zero-tolerance
look-alike at exactly the threshold is part of the golden corpus (house rule: look-alikes at the boundary stay silent).

**Missing, late and early statements.** A statement not yet received is a finding **only after `statement_due`** from the
calendar; before that it is an accrual (below). This replaces `missing_statement`'s "no statement for a day with traffic",
which cannot tell a late partner from a missing one.

**Netting and balances.** `settlement_position` per `(netting_group, cycle)`: payable (we_pay lines), receivable (they_pay
lines), net, in the agreement's settlement currency **and** the original currencies. `settlement_rollforward`: opening +
invoiced − paid ± adjustments = closing, mirroring `ra_rollforward`. Disputed amounts are tracked as a separate column, never
netted away silently.

**FX.** The conversion row carries the `rate_id`, its date and its type so a variance can be reproduced. A variance that
exists only because the partner converted at a different rate or date is `fx_difference`, not an `amount_mismatch`. **PS-D4
(recommended):** rates come from an operator-fed `fx_rate` Reference; the platform fetches no market rate. *Reverse:* a
Collector against a rate provider, demand-gated.

**Dispute lifecycle as Incidents and Cases.**

- A Break (or a `settlement_findings` Alert) opens an **Incident** through the existing path.
- A **Case Rule** groups Incidents per `(partner, cycle)` into one **Case**, the thing sent to the partner. The Incident is
  the line; the Case is the dispute. Graph and Link Analysis are not needed.
- **Workflow.** The built-in CASE workflow is `OPEN → INVESTIGATING → ESCALATED → RESOLVED → CLOSED`. A dispute needs
  `OPEN → INVESTIGATING → DISPUTED (sent) → AGREED | REJECTED → CREDIT_PENDING → CLOSED`. ⚠ **A Workflow is one per object
  type per Space** (`workflow-sla.md` §4): the pack's CASE workflow replaces the built-in for **every** Case in the Space.
  **PS-D5 (recommended): ship the authored CASE workflow in the pack, and document that it replaces the Space's default**; a
  Space that also handles non-settlement Cases keeps working because the new states are a superset (every built-in state
  stays reachable). *Reverse:* a `kind: settlement` Case attribute and a workflow that branches on it (needs a Workflow &
  SLA change).
- **SLA.** An SLA Policy for `CASE` gives response and resolution targets per priority on a business calendar. It does **not**
  know the agreement's contractual dispute window. Window handling is therefore a **derived `dispute_window_end` per
  agreement and cycle** (in `settlement_calendar`) and an Alert Rule that fires N days before it for any Case still
  before `DISPUTED`; the SLA Policy is the internal response target. An **Escalation Rule** raises priority and notifies at
  age. *Gap:* an SLA target per agreement is not expressible; this is why the window is data, not policy.
- **Impact.** Use the Case's Impact block. Mapping proposed in **PS-D6 (recommended):** *suspected* = disputed amount,
  *confirmed* = amount the partner accepts to credit, *recovered* = credit note received, *prevented* = overbilling caught
  before payment. Impact stays an analyst action, as `telco-ra` decided on 2026-10-04 (no automatic ledger write).
  *Reverse:* a different mapping is documentation only.
- **Disposition** uses the built-in ladder, extended in the pack's glossary entry only if the ladder cannot say
  "credit note agreed".

**Aging.** `settlement_aging`: open balance and open dispute amount by age bucket (0–30, 31–60, 61–90, 90+ days; the
boundaries are config), age from the statement date for balances and from the Case's `DISPUTED` timestamp for disputes. The
Break's `ageDays` is derived at read time by the add-on and is not reused as a stored value.

**Accruals.** `settlement_accrual` per `(agreement, period)`: `accrued = expected − invoiced` for a period with no statement
yet, and a provision for disputed amounts by a configurable policy (**PS-D7**, open: provision 0 %, 50 % or 100 % of
disputed; no recommendation because it is a finance-policy call). The pack reports accruals; it never posts them.

**Settlement-cycle calendar.** A Job generates `settlement_calendar` rows from the agreement; statement-due, dispute-window
and payment-due dates are Expectations and Alert Rules over it. This is **separate from the SLA business calendar**
(an object-SLA concept). Public holidays: a `holiday` Reference per country, fed by the operator.

**Evidence pack.** A Report Template (Regulatory Reporting add-on, CSV and JSON, four-eyes approval, file-drop delivery),
rendered **from the Case**: statement header and disputed lines, our re-rated lines with `rate_id` and `rating_run_id`,
the variance and its reason, the agreement version and the tolerance applied, the FX row, and the Incident timeline. **PS-D8
(recommended):** reuse Regulatory Reporting for the export, with the template named `settlement-dispute-pack`; no new
document engine. *Gap:* the add-on is built around regulator submissions (XSD, file drop); a PDF dispute letter is not
produced. A PDF is demand-gated.

## 9. Wholesale, MVNO and roaming (later phases)

| Construct | Plan | Phase |
|---|---|---|
| MVNO per-unit and retail-minus (R7 §4.1) | Same `settlement_agreement` kinds; the rate card carries the construct (sibling plan). Attribution by IMSI range / MSISDN block / APN is the sibling plan's R3, held in an effective-dated `hosting_agreement` Reference (R7 §7.2). R7 controls C8 (invoice verification), C9 (usage-file completeness), C18 (dispute register) map to S3, S2 and S5. | W1 |
| Capacity "pipe", bulk volume, minimum commitment | A **commitment true-up** control: purchased vs measured peak or volume, shortfall invoice check, drawdown and expiry. Needs measured busy-hour throughput as a feed. | W2 |
| Pass-through (TAP charges, termination, VAS, MNP fees) | R7 control C5; a re-bill reconciliation of carrier charges against amounts re-billed to the MVNO. | W2 |
| Roaming (TAP / RAP, IOT, SDR-denominated) | Inbound and outbound TAP files, late TAP, SDR conversion, rejected records. **A TAP decoder is not verified to exist in this repository** (ASN.1 decoding was not checked here); S10 begins by checking, and the slice is **demand-gated** (no prospect named it). | W3 |
| Clearing house (e.g. a financial clearing function) | Statement ingestion from a clearing house is a Collector and a mapping; **no connection is built without a named customer.** | W3 |

## 10. Phased slices

Order is a dependency order. Each slice is **unit-level tested** (CLAUDE.md: run the affected classes with
`-pl <module> -Dtest=A,B`, commas, never `+`; the full reactor only at handoff or on request). The pack's golden test is
`PartnerSettlementPackGoldenTest`, beside `MobileMoneyPackGoldenTest` in `features/inspecto-reconciliation/src/test/java/com/gamma/job/`
(that module's test classpath carries Reconciliation and the ops object service; confirm in S0 that it also carries Workflow
and Regulatory Reporting, else the golden goes where `AmlPackGoldenTest` went). Corpus: `PartnerSettlementCorpus`, fixed seed,
planted findings and benign look-alikes, regenerate with a system property, as every other pack does.

| Slice | Content | Done when (verifiable) | Tests |
|---|---|---|---|
| **S0** | **Contract and decisions.** Operator takes PS-D1…PS-D10; the sibling plan's owner confirms the §5 table; the synthetic corpus is specified on paper (partners, agreements, three cycles, planted findings). | §5 table signed by both plans; decisions recorded in §11; corpus spec appended as §14 | none (documents); `tools/check-vocabulary.mjs`, `check-doc-links.mjs` green |
| **S1** | **References.** `partner`, `settlement_agreement`, `fx_rate`, `holiday`, plus the `settlement_calendar` generator Job. Pipeline + Schema each. | A rate change mid-cycle yields two effective-dated rows and a lookup at each side of the boundary returns the right one; calendar rows match a hand-computed month incl. a holiday-shifted due date | `PartnerSettlementReferenceTest` (SQL golden over the corpus), template-seed gate `ControlApiSpaceTemplateSeedGateTest` applies the template |
| **S2** | **Statement ingestion.** `settlement_statement` Pipeline + Schema, header control totals, versioning, the integrity control and its findings (`control_total_mismatch`, `duplicate_statement`, `restated_statement`, `unknown_partner`). | A re-sent statement raises one finding and no double count; a restated version supersedes and the older one stays queryable; a control-total mismatch is `data_quality`, not leakage | golden asserts the exact finding set, a same-window re-run duplicates nothing (house pattern), a look-alike one unit under the control total stays silent |
| **S3** | **Compare.** `settlement_expected` / `settlement_billed` normalisation (PS-D3), `settlement_findings`, one `recon.run` Reconciliation per service family (voice, SMS), Alert Rule per agreement. Uses the §5 `rerated_usage` Dataset; until the sibling plan lands, a **stand-in `rerated_usage` seed** in the corpus implements the contract so S3 does not block. | exact Breaks for amount mismatch, cut-off difference, FX difference and missing-after-due; a variance exactly at tolerance is silent; a mutation (tolerance +1 step) turns the at-threshold look-alike red | `PartnerSettlementPackGoldenTest` (compare half); mutation check recorded in the okf entry |
| **S4** | **Netting, roll-forward, FX.** `settlement_position`, `settlement_rollforward`, FX as a Dataset column set. | per `(netting_group, cycle)` payable + receivable = net on the corpus, both currencies shown; a restated statement moves the closing balance and the roll-forward still closes to zero | golden over positions; one SQL assertion per identity (opening + movements = closing) |
| **S5** | **Disputes.** Case Rule, authored CASE workflow (PS-D5), SLA Policy and Escalation Rule for `CASE`, dispute-window Alert Rule, Impact mapping (PS-D6). | a planted mismatch becomes an Incident, groups into one Case per `(partner, cycle)`, moves through `DISPUTED → AGREED → CREDIT_PENDING`, and a Case near its window end alerts; the Case's Impact reads as mapped | golden through the Control API (real HTTP, as `TelcoRaGoldenTest` does); a workflow `problems()` unit test on the authored file |
| **S6** | **Aging and accruals.** `settlement_aging`, `settlement_accrual`, KPIs (open disputed amount, aged > 60 days, accrued unbilled), `settlement_overview` dashboard, runbook. | KPI tiles render on the corpus; a late statement is an accrual before `statement_due` and a finding after | golden: every dashboard tile renders (the `mobile-money` assertion) |
| **S7** | **Evidence pack.** `settlement-dispute-pack` Report Template over a Case. | the rendered CSV / JSON names the rate and run id for every disputed line, and a line with no re-rating run is refused (422), not exported blank | template-render unit test; four-eyes path asserted |
| **S8** | **Docs and registration.** okf concept `docs/okf/product/module/config/plan-spaces.md` §3.5.x, a solution page `stakeholders/solutions/partner-settlement.md`, matrix row Q refreshed, `offerings/*.toon` and `tools/check-offerings.mjs`, glossary entries (§13), `docs/INDEX.md`, this plan distilled and `git mv`ed to `archived-documents/plans-archive/`. | all guards green: vocabulary, doc links, offerings, product map (`tools/check-product-map.mjs`) | the guards named |
| **W1…W3** | Wholesale and roaming (§9). **Demand-gated**; W1 first (it reuses S3 on a hosting agreement). | a named prospect or the operator's call | per slice at that time |

**Smallest useful cut: S0 + S1 + S2 + S3.** That alone lifts row Q from the flat-rate voice control to agreement-aware,
cycle-aware, tolerance-per-agreement settlement with Breaks, which is the part competitors' demos lead with. S4…S7 are the
finance-team depth.

## 11. Open operator decisions

| ID | Question | Recommendation | Reverse |
|---|---|---|---|
| PS-D1 | Separate pack `partner-settlement`, or extend `telco-ra` | separate (§4) | fold in later, re-pin golden |
| PS-D2 | Leave `ra_settlement`, `ic_statement`, `ic_rates` in `telco-ra` | leave | delete and re-pin |
| PS-D3 | Per-agreement tolerance via SQL normalisation, or extend the Reconciliation add-on (`toleranceFrom`) | SQL normalisation | add-on change, own tests |
| PS-D4 | FX rates: operator-fed Reference, or a rate-provider Collector | operator-fed | provider Collector, demand-gated |
| PS-D5 | Ship an authored CASE workflow that replaces the Space default | ship; states are a superset | a `kind` branch in Workflow & SLA |
| PS-D6 | Impact mapping on a dispute Case (§8) | as written | docs only |
| PS-D7 | Accrual provision on disputed amounts: 0 / 50 / 100 % | **none; finance policy**, ship 100 % as a configurable default and say so | one config key |
| PS-D8 | Evidence pack through Regulatory Reporting | reuse | a PDF letter engine, demand-gated |
| PS-D9 | Does the Impact ledger stay manual for settlement | yes, as for `telco-ra` (2026-10-04) | n/a |
| PS-D10 | Wholesale/MVNO (W1) before or after roaming (W3) | W1, because R7 already has the controls and it reuses S3 | order only |

**ANSWERED 2026-10-20 (operator, accepted the recommended answer): PS-D1…PS-D10, all as recommended** (the Recommendation
column above). PS-D1 separate `partner-settlement` pack; PS-D2 leave `ra_settlement`, `ic_statement`, `ic_rates` in `telco-ra`;
PS-D3 SQL normalisation; PS-D4 operator-fed FX Reference; PS-D5 ship the authored CASE workflow; PS-D6 Impact mapping as written
(§8); PS-D7 accrual provision shipped as a **configurable default (100 %) and stated as a finance policy**, not an engineering
call; PS-D8 reuse Regulatory Reporting for the evidence pack; PS-D9 Impact ledger stays manual; PS-D10 W1 before W3. Slices
S0…S3 (the smallest cut) are now startable.

Everything above is "recommended" because it is the smaller, reversible change; none is security-loosening and none needs
sign-off beyond preference. PS-D3 and PS-D5 are the two the operator should look at, because each touches an existing
engine's behaviour if reversed.

## 12. Risks

| # | Risk | Mitigation |
|---|---|---|
| R1 | **The §5 contract does not match what the sibling plan builds.** Highest risk: S3 cannot be real without it. | S0 sign-off; a stand-in `rerated_usage` seed so S1…S3 proceed; the pack adapts, not the sibling (§5 last paragraph) |
| R2 | Per-agreement tolerance does not fit the Reconciliation add-on, so the normalised Measure confuses analysts (PS-D3). | S3 shows original amounts as Break context; fallback is the `toleranceFrom` add-on change |
| R3 | **A worked-out finding is a false positive from a bad convention, not a bad partner** (cut-off, rounding, FX date). | Each is its own finding reason; the corpus plants all three plus look-alikes at the boundary |
| R4 | Break volume: the add-on caps recorded Breaks at 50,000 per Reconciliation, and a wholesale partner can exceed it at a usage-line grain. | Compare at `(agreement, period, service, destination_class, day)`, not per record; if still over, one Reconciliation per service family (S3). Measured scale above ~400k rows is unknown (`reconciliation-uplift-plan.md`) |
| R5 | One CASE workflow per Space (PS-D5) surprises a Space with other Cases. | superset states; stated in the runbook and the solution page |
| R6 | Format drift: every partner has its own statement layout. | The pack ships **one canonical Schema**; per-partner mapping is a Pipeline mapping, and the pack states the vendor/partner mapping half is out of scope (the `telco-ra` precedent) |
| R7 | Synthetic data only: a real statement may break assumptions (tax lines, multi-currency in one file, adjustments mid-period). | `line_kind` and per-line `currency` are in the Schema now; a real-file pass is the first customer slice |
| R8 | A peer's uncommitted tree changes `telco-ra` or Reconciliation while this builds. | build in a worktree; verify the golden there, not in the shared tree (see the memory note on dirty shared trees) |
| R9 | Scope creep into re-rating, payments or the ledger. | §3 out-of-scope list; the contract in §5 is the only seam |

## 13. Vocabulary this pack adds to `docs/GLOSSARY.md` (at S8, not now)

| Term | Meaning | Not |
|---|---|---|
| **Settlement Agreement** | The effective-dated commercial terms with one partner for one service (currency, rounding, tolerance, cut-off, cycle, dispute window). A **Reference**. | ⛔ *Contract* in UI text, ⛔ *Rate card* (that is the price list, the sibling plan's) |
| **Settlement Statement** | A partner's document of what it says is owed for a period; versioned, with a control total. A **Stream**. | ⛔ *Invoice* for an interconnect statement (an invoice is the retail `billed_invoice`) |
| **Settlement Cycle** | One period of an agreement between statement dates; the unit of netting and of a dispute Case. | ⛔ *Billing cycle* |
| **Settlement Position** | Per netting group and cycle: payable, receivable, net, in both currencies. A **Dataset**. | ⛔ *Balance* alone |
| **Dispute** | A **Case** that groups the Incidents sent to a partner for one cycle. | ⛔ a new object type: it is a Case; ⛔ *Claim* (la-claims is another pack) |

## 14. Backlog and index changes made with this plan

- `docs/BACKLOG.md`: row `PACK-PARTNER-SETTLEMENT-1` now says the row **bundles two gaps**, points to this plan for gap Q
  (settlement) and to the sibling re-rating plan for gap C, and records the S0…S8 order and smallest cut. It stays **P3**
  and demand-gated for slices past S3. No new row is opened until the operator splits it (a split is PS-D0, below).
- `docs/INDEX.md`: this plan is added under *In-flight plans*.
- **PS-D0 (recommended): split the row** into `PACK-PARTNER-SETTLEMENT-1` (gap Q, this plan) and `PACK-RERATING-1` (gap C, the
  sibling plan), once the sibling plan lands, so each row has one owner and one definition of done. Not done here because
  the sibling session owns that text.
- `docs/okf/product/layer/product/brochure-competitive-matrix.md` rows C and Q carry the corrected description from §1 at S8, not now
  (this session edits only this plan, the INDEX entry and the BACKLOG row).

## 15. References

- [`okf/product/layer/product/brochure-competitive-matrix.md`](../okf/product/layer/product/brochure-competitive-matrix.md) rows C, H, Q and its gap table.
- [`okf/product/solution-pack/business-assurance/architecture-revenue-assurance.md`](../okf/product/solution-pack/business-assurance/architecture-revenue-assurance.md): the `telco-ra` pack.
- `group-regulator-assurance/research/R7-mvno-roll-up-and-wholesale.md` (git-excluded working set, not linked) §4.1, §4.2, §4.4, §5, §7.2.
- [`okf/product/module/reconciliation/architecture-overview.md`](../okf/product/module/reconciliation/architecture-overview.md), [`reconciliation-uplift-plan.md`](../archived-documents/plans-archive/reconciliation-uplift-plan.md).
- [`okf/product/module/workflow/architecture-workflow-sla.md`](../okf/product/module/workflow/architecture-workflow-sla.md), [`okf/product/module/case-management/architecture-overview.md`](../okf/product/module/case-management/architecture-overview.md).
- [`okf/product/module/config/plan-spaces.md`](../okf/product/module/config/plan-spaces.md) §3.5 (`telco-ra`, `mobile-money`, `aml` pack precedents).
- [`GLOSSARY.md`](../GLOSSARY.md): binding vocabulary.

## 16. S0 to S3 as built (2026-10-20)

Branch `lane/ps-s0s3`; the commit is named in the hand-back of the lane. Hub: [`okf/product/solution-pack/partner-settlement/`](../okf/product/solution-pack/partner-settlement/index.md).
The slices follow the order of §10; the smallest useful cut (S0 + S1 + S2 + S3) is built, S4 to S8 and W1 to W3 are not.

**S0: contract and decisions.** PS-D1 to PS-D10 are answered (§11). The §5 contract with the re-rating plan is **not signed**: the
sibling plan's owner has not confirmed R1 to R8, so S3 runs on the planned fallback, a stand-in `rerated_usage` feed that implements
R1 to R8 as written (grain, `units`, `amount_expected`, `currency`, `record_count`, `rate_id`, `rating_run_id`, `rated_at`, blank
amount plus `reason` for R7). R6 (the completeness Signal) is not implemented: the chain starts on the calendar's daily run, not on a
re-rating Signal, so a half-written period is not protected against. The golden is in module `inspecto-reconciliation`, whose test
classpath carries `ControlApi`, the Collector and the Reconciliation engine; it does **not** need Workflow or Regulatory Reporting
until S5 and S7 (the §10 check is deferred to S5).

*Corpus spec (the part of S0 the plan named §14).* `PartnerSettlementCorpus`, hand-built and deterministic (no random draw), four
fictional partners (`P1` to `P4`, made-up country codes `XA` and `XB`, holidays `2026-06-10` and `2026-07-13` for XA, `2026-06-15` and
`2026-08-25` for XB), five agreement versions, three monthly cycles of usage (2026-05-01 to 2026-07-31), judged as of 2026-08-20:

| Agreement | Terms | Planted |
|---|---|---|
| `AG-VOICE-A1` (P1, we pay, USD) | percent tolerance 1.0, then 2.0 from 2026-07-16; monthly, due 10 days | stale May re-rating run; May statement re-sent; July statement v1 with a bad line and a control total one unit off, restated as v2; mismatches on `05-10` (+3 %), `05-20` (+1.01 %), `06-20` and `06-21` (+10 and -4, not offsetting), `07-15` (+1.5 % under the 1 % version); cut-off pair `06-14` and `06-15` (-60 and +60); unrated day `06-25`. Look-alikes: `05-12` exactly +1 %, `07-16` +1.5 % under the 2 % version, `07-20` (v1 billed 999, v2 correct) |
| `AG-SMS-C1` (P3, they pay, rated USD, invoiced EUR) | absolute tolerance 5.0; semi-monthly, due 5 days; contract USD to EUR with decoy spot rows | FX difference `06-18` (partner at 0.93, ours 0.91), mismatch `06-22` (+50, not FX) and `07-02` (+5.0001). Look-alikes: `06-19` (partner at our rate), `06-20` (exactly +5.0000), `06-21` (partner rate, +2), a balanced credit line |
| `AG-VOICE-D1` (P2, we pay, USD) | absolute tolerance 0.5; monthly | no July statement and it is overdue (`missing_statement`); mismatch `06-04` (+0.5001); look-alike `06-03` (exactly +0.5000) |
| `AG-VOICE-E1` (P4, they pay, USD) | percent 1.0; monthly, due 25 days | no July statement, not due until 2026-08-26: silent |

Two statements name an unknown partner (`P99`) and an unknown agreement for a known partner.

**S1: References.** Four References (`partner`, `settlement_agreement`, `fx_rate`, `holiday`), each a Pipeline, a Schema, a Dataset
and a zero-row seed, and the calendar Job `ps_calendar`. A mid-cycle change is two effective-dated rows, and a lookup on each side
of the boundary returns the right one (`PartnerSettlementReferenceTest`); the calendar equals a `java.time` oracle for every
agreement version and the hand-worked dates for a holiday plus weekend roll.

**S2: statement ingestion.** The `settlement_statement` Pipeline and Schema, `ps_statement_current` (re-sends once, highest version)
and `ps_statement_findings` (`control_total_mismatch`, `duplicate_statement`, `restated_statement`, `unknown_partner`,
`unknown_agreement`). The golden asserts the exact finding set, that a re-sent statement raises one finding and counts once, that the
restated version supersedes and the older one stays in the feed, and that a mismatch is `data_quality`.

**S3: compare.** `ps_expected`, `ps_billed`, `ps_findings`, two Reconciliations (`ps_voice`, `ps_sms`) and the Alert Rule
`ps_findings_found` (one Alert per agreement). The golden holds the findings to the exact planted set (8 `amount_mismatch`, 2
`cutoff_difference`, 1 `fx_difference`, 1 `missing_statement`, 1 `unrated_usage`; every look-alike silent), every expected and
billed amount to an independent plain-Java oracle, the Reconciliation Breaks to the oracle's key set, and a second run to the same
row counts. Tests (module `inspecto-reconciliation`): `PartnerSettlementCorpusTest` (4), `PartnerSettlementReferenceTest` (4),
`PartnerSettlementSeedTest` (1), `PartnerSettlementPackGoldenTest` (12); the seed gate `ControlApiSpaceTemplateSeedGateTest` now
also applies the template and checks its runbook.

**Mutation pass (recorded, as S3 required).** Each mutation of the shipped SQL or data below is a real test that turns a named key
red: the tolerance one step tighter (`D1` `06-03` becomes `amount_mismatch`); the snap comparator `<=` to `<` (`06-03` becomes a
Break); the finding comparator `>` to `>=` (the at-tolerance days of A1, C1 and D1 appear); `rn = 1` to `rn >= 1` (the May statement
doubles to 126 lines and an ordinary day turns red); the oldest rating run (an ordinary May day turns red); the superseded statement
version (`07-20` turns red); the cut-off neighbour test inverted (the offsetting pair turns into mismatches and the non-offsetting
pair into cut-off differences); a feed with no FX rate (`fx_rate_missing`, no invented amount, no partner variance, same-currency
agreements untouched).

**Deviations from the plan, and traps found.**

- **PS-D3 as built differs in form, not in kind.** §6 sketched a `variance_beyond_tolerance` Measure (0 inside, the excess outside).
  As built `ps_billed.amount` is the billed amount snapped to the expected amount when the variance is inside the tolerance
  (inclusive), else the true billed amount; the Reconciliation compares `amount` at absolute tolerance 0. A Break then shows the
  real expected and billed amounts, so risk R2 ("a Break whose Measure is the excess reads oddly") does not arise, and no add-on
  change is needed. `amount_billed`, `amount_expected` and `variance` ride on the billed row.
- **The statement feed is flat**, not header plus lines: each line repeats the statement's `control_total_amount` and
  `control_total_lines` and adds `line_no`, the key the re-send and control-total checks need. A real file with a header is flattened
  in the Pipeline mapping.
- **Non-usage lines (tax, fee, credit, adjustment) count toward the control totals and are not compared** in S3 (they have no
  expected counterpart); S4 positions will take them.
- **A variance FX explains is named `fx_difference`, even right at one step beyond the tolerance.** Under the finding-comparator
  mutation the C1 `06-20` at-tolerance day reads `fx_difference`, not `amount_mismatch`: the partner's own rate explains the whole
  variance.
- **`fx_rate` lookup is "latest row on or before the date"**, not an exact date match, so a weekend needs no row; a lookup with no
  earlier row is `fx_rate_missing`.
- **A Space Template carries no Workflow, SLA Policy or Escalation Rule** (the inert examples every template ships say so:
  governance is written only through its `canAdminister` routes). S5's authored CASE workflow (PS-D5) therefore cannot ship as
  template config; it needs a documented apply step or a decision on a governance seed. Raise this before S5.
- ⚠ `HUGEINT` (a `sum()` of an integer column) is written as `DOUBLE` by Parquet, so the schema-seed check fails; cast to `BIGINT`.
- ⚠ The template gate reads the Schema of every Dataset an Alert Rule counts, so the Job-written Datasets need seeds **before** the
  template applies; the seed test regenerates them with `PARTNER_SETTLEMENT_REGENERATE=true`.
- The Reconciliation Break key joins the key columns with a middle dot; `ReconStateStore` keeps a Break per day, so a catch-up runs
  the `recon.run` Job once per day (`day` parameter).
- An independent review found three SQL defects the first corpus could not see, now fixed and pinned by corpus cases: the unrated check read every rating run (a day unrated only in a superseded run hid a real variance); statements naming an unknown partner or agreement still fed the compare and could supersede a real version; an unrated day could be the partner of a cut-off pair.
- The oracle shares the **business rules** with the SQL (tolerance inclusive, latest run, highest version, neighbour-day cut-off
  test); it is independent of the implementation, not of the rules. The rules are pinned by the planted corpus and the mutation pass.

**Not built, and open.** S4 (netting, positions, roll-forward), S5 (disputes), S6 (aging, accruals, dashboard), S7 (evidence pack),
S8 (the glossary entries of §13, the matrix row Q refresh, the `okf` plan-spaces entry, the solution page); W1 to W3. Open for the
operator or the sibling plan: (1) sign the §5 table, in particular R6 (the completeness Signal); (2) PS-D0 (`PACK-RERATING-1` already exists as its own row; the open part is moving the matrix wording and the shared row text); (3) the real statement layout (flat or header plus lines) once a real file is seen; (4) whether the
dispute workflow of S5 is applied by an administrator step or a governance seed; (5) the class of `restated_statement` (`info`,
neither variance nor data quality).

## 17. S4 as built (2026-10-21)

Branch `lane/ps-s4`, on top of S0 to S3 (§16). Hub: [`okf/product/solution-pack/partner-settlement/`](../okf/product/solution-pack/partner-settlement/index.md).
S4 is netting, the roll-forward and FX as a column set; S5 (disputes) is **not** built, because its authored CASE workflow needs a
Workflow and SLA seed decision that is the operator's (§16, open item 4). Decisions PS-D1 to PS-D10 are followed; PS-D6 to PS-D9 do not apply to S4.

### 17.1 The §5 contract against what the re-rating pack really shipped

S3's stand-in `rerated_usage` implements R1 to R8 as §5 wrote them. Re-rating R1 and R2 are shipped ([`rerating-tariff-validation-plan.md`](rerating-tariff-validation-plan.md)
§13, §14) and publish a Dataset also called `rerated_usage`. Compared on 2026-10-21 against `spaces/_templates/telco-rerating/config/registry/datasets/rerated_usage.toon`
(21 columns) and the plan text, **the two are not the same contract**, and the real one cannot feed S3 as it stands:

| §5 requirement | The real `rerated_usage` (telco-rerating) | Match |
|---|---|---|
| R1 grain `(agreement_id, direction, service, destination_class, usage_date, rate_id, rating_run_id)` | One row per **call** (`ITEM_KEY`, `EVENT_TS`, `SUBSCRIBER_ID`, `PLAN`, `SERVICE`); no agreement, direction or destination class | **No**: retail per-call, not a wholesale aggregate |
| R2 `units`, `amount_expected`, `currency`, `record_count` | `USAGE_UNITS`; a **range** `EXPECTED_LOW` / `EXPECTED_HIGH` plus `EXPECTED_CHARGE` when the tariff is unambiguous; no currency (one implied retail currency); no count (the row is the call) | **Partly**: an amount exists, as a range |
| R3 `agreement_id` is the join key; rate card a separate Reference keyed by `rate_id` | A rate card exists as the `tariff_version` Reference keyed by `TARIFF_VERSION_ID`, so the open question of §5 is answered the way §5 hoped; but nothing maps a call to a settlement agreement | **Half**: price yes, attribution no |
| R4 effective dating, `rate_id` names the version | `TARIFF_VERSION_ID` and `CANDIDATE_VERSIONS`; a call is rated at its start (`EVENT_TS`) | **Yes** |
| R5 `rating_run_id` and `rated_at`, latest complete run per period | None. `rr_rerate` is incremental by `window_date` and a re-run replaces that day's partition, so older runs are not kept | **No** |
| R6 a completeness Signal naming the period | `job.run.completed` of `rr_rerate` exists for the chain, with the window `$yesterday`; it names no period and S3's chain does not consume it | **No** |
| R7 exceptions as rows with a blank amount and a `reason` | `REASON` and `FINDING` (`no_tariff`, `ambiguous_tariff`, `unratable_call`, class `data_quality`) | **Yes in spirit**; the names differ and a blank amount was not checked |
| R8 cut-off zone and time, never re-bucketed | `EVENT_DATE` from the call; no cut-off zone | **No** |

The retail plan states wholesale and interconnect settlement are out of its v1 scope (its §4), and D-RR9 puts retail there and wholesale here.
So the gap is real and not a defect of either pack: **no wholesale re-rater exists**, and the `rerated_usage` seam of §5 is still the stand-in.
**S3 is unchanged**: `ps_expected` reads the stand-in as before, no column was added, renamed or reinterpreted, and the S3 golden is unmodified except that its
shipped-shape counts now include the new Jobs and feed. Recorded, not decided (open decision 1 below): who writes the adapter that turns a per-call retail
`rerated_usage` plus an attribution Reference (trunk, route, PLMN, IMSI range to agreement) into the §5 aggregate. `rerating_findings` plays no role: it is a
retail finding list, and settlement must not read billing output.

### 17.2 What S4 delivers

Three more chained `sql.template` Jobs (after `ps_findings`, each on the previous one's success), one more feed, four more Datasets, in the Space Template
`partner-settlement` (now 7 feeds, 9 Job-written Datasets, 9 chained Jobs).

- **`settlement_payment` (feed).** Finance's payments, credit notes and debit notes, read and never written (plan §7 named it): `payment_id`, `agreement_id`, `partner_id`,
  `direction`, `payment_date`, `kind` (`payment`, `credit_note`, `debit_note`), a positive `amount`, `currency`, `recorded_at`. A re-sent record counts once and the latest
  `recorded_at` wins.
- **`ps_movement` writes `settlement_movement`**: every current statement line and every payment at line level, in the agreement's settlement currency **with both currencies and the
  FX row** (`amount_orig`, `currency_orig`, `amount`, `currency`, `fx_rate`, `fx_rate_date`, `fx_rate_type`, `fx_status`), signed from our side (`signed_amount`, positive = we owe the
  partner), `effect` (`invoiced`, `paid`, `adjustment`) and a `status` (`ok`, `fx_missing`, `unknown_agreement`). This is the "FX as a Dataset column set" of the S4 row.
- **`ps_position` writes `settlement_position`** per `(netting_group, cycle_month, currency)`: `payable` (we_pay statement lines), `receivable` (they_pay), `net = payable - receivable`
  (positive = we pay) and `net_direction`, `orig_net` (the net in each original currency, `EUR:-4.0000,USD:123.0000`), statement and line counts and `lines_unconverted`.
- **`ps_rollforward` writes `settlement_rollforward`** per `(netting_group, currency, cycle_month)`: `opening + invoiced - paid + adjustments = closing`, one row for **every month** from
  the first movement to the as-of month, so a quiet month carries the balance (`as_of` parameter, default today).

### 17.3 Decisions made inside S4 (each reversible, none touches an existing engine)

1. **Netting is inside one settlement currency.** The corpus group `NG-XA` holds a USD agreement (A1) and a EUR one (C1); they give two positions and two roll-forward series, not one. Cross-currency
   netting needs a position currency per group and, for EUR to USD, a reverse rate the `fx_rate` Reference does not carry; inventing `1 / rate` would be a convention. **Open decision 2.**
2. **Cycle for netting is the calendar month in which a statement's cycle ends** (a semi-monthly or weekly agreement puts several statements in one month); a payment or note belongs to the month of its date.
3. **The position reads what statements say, the roll-forward adds what finance recorded.** Neither reads our own pricing or any dispute: the "disputed amount, never netted away" column of §8 belongs to S5
   (it needs a dispute state), so S4 shows no disputed column rather than a stand-in for one.
4. **FX date for a movement:** a usage line uses the agreement's `fx_date_rule`; a fee, tax, credit or adjustment line has no usage day and uses the cycle end; a payment or note uses its own date. The rate is the latest on
   or before the date for the agreement's `fx_rate_type`, as in S3.
5. **Agreement version for a movement** is the latest version starting on or before the movement date, for the right partner (so a final payment after the last version ended still finds its agreement); a movement with no such version is `unknown_agreement`.
6. **A movement that cannot be placed is named, never dropped:** `fx_missing` counts in `lines_unconverted` and adds no amount; `unknown_agreement` stays in `settlement_movement` and in no balance. A payment-integrity finding (duplicate or orphan payment) is **not built**: the orphan is visible in the Dataset, not raised.
7. **No opening balance feed**: a series opens at zero. A balance older than the first statement is entered as an adjustment note.
8. **`paid` is money we paid minus money received**, so it is negative in a month where partners paid us more.

### 17.4 What the golden holds (module `inspecto-reconciliation`)

`PartnerSettlementPositionGoldenTest` (18 tests), on `PartnerSettlementCorpus.generateWithPositions()`: the S3 corpus plus partner `P5` and agreement `AG-VOICE-F1` (invoiced in EUR into a USD agreement, converted at the cycle end by a rate that changes mid-June, with
decoy spot rates), and 11 finance records for 9 payment ids (a payment sent twice, a payment corrected by a later record, a payment in EUR, a credit note, a debit note in a month with no statement, an unknown agreement and an agreement under another partner). The S3 corpus and
`generate()` are unchanged (only `contractRate` now also filters the currency pair, a no-op for `generate()`); the extension raises no S2 or S3 finding, and a test asserts the planted sets are unchanged.

- **Oracle, column for column**: all 24 columns of all 442 movements, all 11 columns of the 7 positions and all 10 columns of the 11 roll-forward rows equal an independent plain-Java oracle (`BigDecimal`, own statement and payment dedup, own agreement and rate lookup, no SQL, no shared code with the Jobs). The oracle shares the business rules with the SQL, as in S3 (§16).
- **One SQL assertion per identity**: `net = payable - receivable` and `net_direction` on every position; `opening + invoiced - paid + adjustments = closing` on every roll-forward row; each opening equals the previous closing, the first is zero, no month is skipped, every series runs to the as-of month; the last closing of each series equals the sum of its movements; and the usage movements per agreement and cycle equal `settlement_billed.amount_billed` (S4 is tied to S3's money).
- **Hand-worked amounts that do not come from the oracle**: a payment sent in two files is paid once (10000.0000), the corrected payment is the later record (E1 received 450, so NG-XB June paid is 880.0000 - 450.0000 = 430.0000), a EUR payment converts at the rate in force on its date (800 EUR = 880.0000 USD at 1.1000 from 2026-06-16), a credit note lowers and a debit note raises the balance, a month with no movement carries the balance, F1's June statement sums to the hand-computed 1.1000 conversion.
- **A restated statement moves the closing balance and the roll-forward still closes**: the corpus without the restated July statement leaves the superseded line (999.0000, right amount 200.0000) current; July and August closings are higher by exactly 799.0000, the position's `payable` by the same, May and June do not move, and the closing identity holds in both runs.
- **No FX rate**: with every rate removed, all 64 F1 movements are `fx_missing` with no amount, the June position counts 31 unconverted lines and sums only the converted ones, the roll-forward counts the unconverted payment too, and the whole set still equals the oracle.
- A second run over the same window duplicates nothing.

**Mutation pass (recorded): 11 mutants of the shipped S4 SQL, each a real test that is red for the named reason.** Rate type ignored (an F1 line takes the 2.0000 decoy spot rate); usage day instead of cycle end for a period-end agreement (2026-06-05 converts at 1.2000, not 1.1000); side sign flipped (the position stays equal to the oracle, every roll-forward balance is negated, **and the SQL identity stays green**, so only the oracle and the hand-worked amounts catch it); payment sign flipped (June paid -10000.0000); the earlier of two payment records taken (paid 380.0000, not 430.0000); net as payable plus receivable; opening not carried (June opens at zero and the closing identity breaks); series ending at the last movement (NG-XB loses July and August); a credit line counted as invoiced (the split moves, **the closing does not**); payment FX date taken from the cycle end (the EUR payment becomes `fx_missing`); partner not matched (another partner's payment settles the agreement). Each mutant first asserts its target is present in the shipped text.

**Measurements (honest).** On the corpus: 442 movements, 7 positions, 11 roll-forward rows. Across 28 runs in the test run: `ps_movement` 90 to 141 ms (median 122), `ps_position` 55 to 84 ms (median 71), `ps_rollforward` 45 to 74 ms (median 54), on a loaded desktop JVM. **Not measured at volume**: a real wholesale feed is orders of magnitude larger, `ps_movement` re-converts every line each run (a full rebuild, like S3), and the roll-forward's cumulative window is per netting group and currency. The S3 risk R4 (Break volume) is unchanged and not touched by S4.

**Traps found.** `string_agg(..., ';' ...)` inside a `sql.template` is refused ("only a single statement is allowed": the guard counts any `;`, even inside a quoted literal), so `orig_net` joins with a comma. A currency-blind `contractRate` in the corpus would have taken the new EUR to USD rows for the SMS agreement; it now filters the pair. The S3 `SeedTest` ingests the S4 corpus so the new feed has a Pipeline-written file to compare its seed against.

**Not built, and open.** S5 disputes (Case Rule, authored CASE workflow, SLA, Impact), S6 aging and accruals and the dashboard, S7 evidence pack, S8 docs and registration, W1 to W3. Open for the operator: (1) who writes the wholesale re-rating adapter of §17.1 (the §5 table is still unsigned); (2) cross-currency netting: a position currency per netting group and a reverse-rate convention, or keep one currency per position; (3) an opening-balance feed or a first-month convention; (4) a payment-integrity finding (duplicate, orphan, wrong partner); (5) the S5 governance seed decision of §16.
