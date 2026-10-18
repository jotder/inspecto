# Partner / settlement / wholesale: plan (`PACK-PARTNER-SETTLEMENT-1`, settlement half)

> 🟡 **PLAN 2026-10-18: design only, nothing built.** Closes competitive gap **Q** (partner / settlement / wholesale) in
> [`stakeholders/COMPETITIVE_CAPABILITY_MATRIX.md`](../stakeholders/COMPETITIVE_CAPABILITY_MATRIX.md). Gap **C** (re-rating) has
> its own plan, written in parallel by a sibling session; this plan states only the **contract it needs from it** (§5) and
> does not design re-rating. Decisions below are marked **PS-D1…PS-D10**: each is a recommendation awaiting the operator
> (§11), with how to reverse it. **What is NOT known:** no real partner statement, TAP file or MVNO wholesale invoice has been
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
| Reconciliation add-on | Two or three Datasets compared at a key grain; four Break types; Break lifecycle `open / assigned / resolved / auto_closed` with recurrence; `recon.run` Job; one deduped Incident per Reconciliation on a breach; Break → Incident via `POST /recon/promote`. | [`stakeholders/modules/reconciliation.md`](../stakeholders/modules/reconciliation.md) |
| Incidents, Cases, Workflow & SLA | A Case groups Incidents (Case Rule auto-groups); Impact (suspected / confirmed / recovered / prevented) on an Incident or Case; an authored `workflow` **per object type**; an SLA Policy per object type (targets per priority on a business calendar); Escalation Rule once per breach. | [`stakeholders/modules/workflow-sla.md`](../stakeholders/modules/workflow-sla.md), [`stakeholders/modules/case-management.md`](../stakeholders/modules/case-management.md) |
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
| **S8** | **Docs and registration.** okf concept `docs/okf/capabilities/spaces/spaces.md` §3.5.x, a solution page `stakeholders/solutions/partner-settlement.md`, matrix row Q refreshed, `offerings/*.toon` and `tools/check-offerings.mjs`, glossary entries (§13), `docs/INDEX.md`, this plan distilled and `git mv`ed to `archived-documents/plans-archive/`. | all guards green: vocabulary, doc links, offerings, product map (`tools/check-product-map.mjs`) | the guards named |
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
- `docs/stakeholders/COMPETITIVE_CAPABILITY_MATRIX.md` rows C and Q carry the corrected description from §1 at S8, not now
  (this session edits only this plan, the INDEX entry and the BACKLOG row).

## 15. References

- [`stakeholders/COMPETITIVE_CAPABILITY_MATRIX.md`](../stakeholders/COMPETITIVE_CAPABILITY_MATRIX.md) rows C, H, Q and its gap table.
- [`stakeholders/solutions/revenue-assurance.md`](../stakeholders/solutions/revenue-assurance.md): the `telco-ra` pack.
- `group-regulator-assurance/research/R7-mvno-roll-up-and-wholesale.md` (git-excluded working set, not linked) §4.1, §4.2, §4.4, §5, §7.2.
- [`stakeholders/modules/reconciliation.md`](../stakeholders/modules/reconciliation.md), [`reconciliation-uplift-plan.md`](../archived-documents/plans-archive/reconciliation-uplift-plan.md).
- [`stakeholders/modules/workflow-sla.md`](../stakeholders/modules/workflow-sla.md), [`stakeholders/modules/case-management.md`](../stakeholders/modules/case-management.md).
- [`okf/capabilities/spaces/spaces.md`](../okf/capabilities/spaces/spaces.md) §3.5 (`telco-ra`, `mobile-money`, `aml` pack precedents).
- [`GLOSSARY.md`](../GLOSSARY.md): binding vocabulary.
