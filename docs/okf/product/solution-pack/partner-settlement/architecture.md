# Partner settlement

> **Level 2 solution page.** Content pack: Space Template `partner-settlement`
> (`spaces/_templates/partner-settlement/template.toon`). **Slices S0 to S7 of `PACK-PARTNER-SETTLEMENT-1` are built: the
> References, statement ingestion, the compare, netting positions with a roll-forward, disputes as Cases, aging with accruals
> and the dashboard, and the dispute evidence pack.** Docs and registration (S8) and W1 to W3 are open ([open work](../../../../superpower/telco-packs-plan.md); decisions, corpus and traps of S0 to S7 in the
> [as-built record](plan-as-built.md)). Checked against the template on 2026-11-01.

## Business problem

A wholesale finance team does not settle with a partner, it settles under an **agreement**: a currency, a tolerance, a cut-off, a
cycle and a dispute window that differ per partner and per service, and change over time. The partner sends a **statement** (a
document, versioned, with control totals); the team prices its own traffic independently and compares. The existing `telco-ra`
control `ra_settlement` is a voice-only SQL control with one flat rate per partner and one global tolerance. This pack is the
agreement-aware version: per-agreement tolerance, FX and cycle, a calendar that knows when a statement is overdue, and findings that
say whether a variance is an amount error, a cut-off convention or a currency conversion.

Every format here is synthetic or from public descriptions: no real partner statement, TAP file or wholesale invoice has been seen,
so the canonical statement Schema is a proposal, and mapping a partner's own layout into it is a Pipeline mapping outside this pack.

## Data in

Seven Pipelines with Schemas under `spaces/_templates/partner-settlement/config/`:

| Feed | Contents |
|---|---|
| `partner` | `partner_id, name, type, country, status` |
| `settlement_agreement` | effective-dated terms: `agreement_id, partner_id, kind, direction, currency, rounding_rule, tolerance_type, tolerance, cutoff_zone, cutoff_time, cycle, statement_due_days, dispute_window_days, payment_due_days, netting_group, fx_rate_type, fx_date_rule, valid_from, valid_to` (a change is a new row; `valid_to` blank = open) |
| `fx_rate` | `rate_date, from_currency, to_currency, rate_type, rate, rate_source`, fed by the operator's finance; the platform fetches no rate |
| `holiday` | `country, holiday_date, name`, matched to `partner.country` |
| `settlement_statement` | one row per statement line: `statement_id, statement_version, line_no, partner_id, agreement_id, period_start, period_end, usage_date, direction, service, destination_class, units, amount, currency, fx_rate_applied` (optional), `line_kind, control_total_amount, control_total_lines, received_at` |
| `rerated_usage` | the stand-in for the re-rating contract (R1 to R8, [open work](../../../../superpower/telco-packs-plan.md) section 4.1): one row per agreement, direction, service, destination class, day and rating run, with `units, amount_expected, currency, rate_id, rating_run_id, rated_at, record_count, reason`. **The re-rating pack's own `rerated_usage` is a different, per-call retail contract and cannot feed this one yet** (same plan, section 4.2) |
| `settlement_payment` | finance's record, read and never written: `payment_id, agreement_id, partner_id, direction, payment_date, kind` (`payment`, `credit_note`, `debit_note`)`, amount, currency, recorded_at` |

Every Dataset, feeds and Job outputs, ships a zero-row schema seed under `spaces/_templates/partner-settlement/data/`, so the
template applies (the Alert Rule gate reads the Schema of `settlement_findings`) and the chain runs on a fresh Space.

## What the pack sets up

Twelve chained `sql.template` Jobs (the first daily at 01:00, each next one on the previous one's success), two `recon.run` Jobs
(05:30), one `caserule.evaluate` Job (06:00), four Alert Rules, one Case Rule, three KPIs and the `settlement_overview` dashboard:

| Job | Writes | Notes |
|---|---|---|
| `ps_calendar` | `settlement_calendar` | per agreement version and closed cycle (monthly, semi-monthly, weekly): `statement_due`, `dispute_window_end`, `payment_due`, each rolled to the next business day |
| `ps_statement_current` | `settlement_statement_current` | re-sent lines once; highest version per agreement and cycle |
| `ps_statement_findings` | `settlement_statement_findings` | `control_total_mismatch`, `duplicate_statement`, `restated_statement`, `unknown_partner`, `unknown_agreement` |
| `ps_expected` | `settlement_expected` | latest re-rating run per agreement and cycle, in the settlement currency, with the tolerance in force that day |
| `ps_billed` | `settlement_billed` | the partner's usage lines at the same grain; `amount` snapped to expected inside the tolerance |
| `ps_findings` | `settlement_findings` | `amount_mismatch`, `cutoff_difference`, `fx_difference`, `missing_statement`, `unrated_usage`, `fx_rate_missing` |
| `ps_dispute` | `settlement_dispute` | one row per `amount_mismatch` or `fx_difference` finding: `dispute_key` (partner, cycle start, cycle end), `disputed_amount` (absolute variance), the agreement's currency and netting group, `dispute_window_end`, `days_to_window_end`, `window_alert` |
| `ps_movement` | `settlement_movement` | every current statement line and every payment or note at line level, in the settlement currency with both currencies and the FX row (rate, date, type, status), signed from our side; `status` is `ok`, `fx_missing` or `unknown_agreement` |
| `ps_position` | `settlement_position` | per netting group, cycle month and settlement currency: `payable`, `receivable`, `net` (positive = we pay), `net_direction`, `orig_net` (net per original currency), line counts, `lines_unconverted`, `disputed_amount` and `disputed_lines` beside the net |
| `ps_rollforward` | `settlement_rollforward` | per netting group and settlement currency, one row for every month to the as-of month: `opening + invoiced - paid + adjustments = closing` |
| `ps_aging` | `settlement_aging` | per agreement and statement cycle: `invoiced` (in the agreement's direction), `settled_applied` (finance records up to `as_of`, oldest cycle first), `open_balance`, `open_disputed`, `age_days` from the statement's received date, `age_bucket` (boundaries 30 / 60 / 90 by parameter), `payment_due`, `payment_alert`, and the `kpi_` columns for `kpi_currency` |
| `ps_accrual` | `settlement_accrual` | per agreement and closed cycle: `expected_amount` (the latest run, due or not), `invoiced_amount`, `status` (`billed`, `unbilled`, `statement_late`), `accrued_unbilled`, `dispute_provision` (`provision_pct`, default 100), `accrual_total` |
| `ps_voice`, `ps_sms` | Break state | one Reconciliation per service family over `settlement_expected` and `settlement_billed`, keys `agreement_id, direction, service, destination_class` at day grain |

`ps_findings_found` is one Alert Rule over `settlement_findings`, counted `by agreement_id`: one Alert per agreement (WARNING, no
Incident). `ps_dispute_line` (CRITICAL, `by dispute_key, item_key`) opens one Incident per dispute line and `ps_dispute_window`
(CRITICAL, `sum(window_alert)` `by dispute_key`) one per partner cycle whose window closes within the lead time. The Case Rule
`ps_dispute_case` (`config/ops/ps_dispute_caserule.toon`, `groupBy: dispute_key`, threshold 1, category `partner-settlement`) groups
every Incident that carries a `dispute_key` into one Case per partner and cycle; the Job `ps_dispute_case` evaluates it daily.
`ps_payment_due` (WARNING, `sum(payment_alert)` `by aging_key`) names every cycle whose open balance is within `payment_alert_days`
(default 5) of its payment due date or past it. The KPIs `ps_open_disputed`, `ps_aged_over_60` and `ps_accrued_unbilled` (time field
`as_of`, day grain, no comparison) and three bar Widgets make up `settlement_overview`.

## Decisions and seams worth knowing

- **PS-D3, as built: the compare is normalised in SQL, then the Reconciliation runs at tolerance 0.** The plan sketched a
  `variance_beyond_tolerance` Measure (0 inside the tolerance, the excess outside). As built, `ps_billed` writes `amount`,
  which is the billed amount **snapped to our expected amount when the variance is inside the tolerance** (inclusive), and the true
  billed amount otherwise; `amount_billed`, `amount_expected` and `variance` ride along on the same row. A Break therefore means
  "beyond tolerance", and its two compared values are the real expected and billed amounts, not an "excess" that reads oddly.
  This closes plan risk R2 without touching the Reconciliation add-on.
- **Tolerance, FX and cut-off are agreement data.** The Jobs name no currency and no tolerance (a test asserts it). The agreement
  in force on `usage_date` supplies the tolerance (`pct` of the expected amount, or `abs`); a mid-cycle change is two rows and each
  day uses its own. FX picks the latest `fx_rate` row on or before the date named by `fx_date_rule` with the agreement's
  `fx_rate_type`; a missing rate is a named `fx_rate_missing` finding and the day is left out, never an invented amount.
- **Overdue, not absent.** `ps_expected` includes a cycle only when its statement is received or `statement_due` is before the
  as-of day (`as_of`, default today), so a statement not yet due is silent and a statement past due is one `missing_statement`
  finding per cycle. (The Reconciliation still Breaks on each day of that cycle, ours-only; the finding is the cycle-level reading of those Breaks.)
- **Classification.** A variance beyond tolerance is `cutoff_difference` when the neighbouring day (either side) carries an opposite
  variance beyond tolerance that nets to within tolerance; else `fx_difference` when the partner billed in the settlement currency at
  its own `fx_rate_applied` and that rate explains the variance; else `amount_mismatch`. A cut-off pair is two findings, one per
  day.
- **Unrated usage is data quality.** A re-rating row with a blank amount and a reason becomes `unrated_usage` and the day is kept
  out of every variance finding (plan R7). Only the latest rating run counts: a day unrated in a superseded run and priced in the latest is not unrated. The Reconciliation does not know the reason and still Breaks on that day (statement-only).
- **Statements that name an unknown partner or agreement are not compared.** `ps_statement_current` drops lines whose partner or
  partner-and-agreement pair is not in the References before it picks the highest version, so a stray statement can neither add to nor
  supersede a real one; `ps_statement_findings` reports it.
- **Statement shape, as built.** The plan sketched a header row; the canonical feed is flat: each line carries the statement's
  control totals, and the integrity control reads them back. A real partner file with a separate header is flattened by the Pipeline
  mapping. Non-usage lines (tax, fee, credit, adjustment) count toward the control totals and are not compared in this slice.
- **Calendar.** A cycle belongs to the agreement version in force at its last day; business days exclude weekends and the partner
  country's holidays; the settlement calendar is separate from the Workflow and SLA business calendar. A cycle change between two
  versions inside one cycle is not supported.
- **Vocabulary.** *Settlement Agreement*, *Settlement Statement*, *Settlement Cycle*, *Settlement Position* and *Dispute* are
  proposed in the [open-work plan](../../../../superpower/telco-packs-plan.md) (section 2.4) and enter the glossary with slice S8; the UI and these docs use the plan's words.
- **Netting and the roll-forward (S4).** Netting happens inside one settlement currency: agreements of one netting group in
  different settlement currencies give separate positions (cross-currency netting needs a position currency and a reverse-rate
  convention, an open decision). A statement belongs to the month its cycle ends in, a payment or note to the month of its date. The
  position reads what the current statements say, the roll-forward adds what finance recorded; neither nets our pricing or a dispute in
  (S5 adds the disputed amount as its own columns beside the net), so a position is not an agreed balance. A movement that cannot be placed is named (`fx_missing`, `unknown_agreement`), never
  summed or dropped; there is no opening-balance feed (a series opens at zero) and no payment-integrity finding yet.
- **Disputes (S5, PS-O5).** The Incident is the line, the Case is the dispute. The authored CASE workflow, a Case SLA Policy and an
  age Escalation Rule ship as JSON bodies in `config/governance/` that an administrator posts to `/components/workflow`,
  `/components/sla-policy` and `/components/escalation-rule` once per Space: a Space Template carries no governance (the seed gate
  and every import refuse `registry/workflows/`, `registry/sla-policies/`, `registry/escalation-rules/`). The workflow is a superset of
  the built-in CASE states, because it replaces the built-in for every Case of the Space. The contractual dispute window is data
  (`settlement_calendar.dispute_window_end`), not the SLA clock; the reminder reads the data and not the Case state, so a Case
  already `DISPUTED` is reminded too. The disputed amount is the absolute variance of the dispute lines, beside the net and never in
  it, and it does not follow the Case outcome. Impact is the analyst's (PS-D6, PS-D9).
- **Aging and accruals (S6, decided under delegation, pending confirmation).** A payment names its agreement, not a cycle, so
  `ps_aging` applies finance's records to the statement cycles of the agreement **oldest first**, per agreement, direction and
  settlement currency; payments after `as_of` and statements received after it are not counted. Balances and disputes are both aged
  from the received date of the current statement version (SQL cannot read when a Case reached `DISPUTED`, and the Break's `ageDays`
  is read-time). The payment-due Alert is a WARNING Alert, not an Incident, and counts the disputed amount in the open balance.
  `ps_accrual` prices the latest re-rating run itself, because `settlement_expected` holds a cycle only once its statement is due or
  received; a statement-less cycle is `unbilled` up to and including `statement_due` and `statement_late` after it, the same day the
  `missing_statement` finding starts. The KPI tiles count one currency (`kpi_currency`, default USD) and add payables and receivables as
  amounts at stake; the bar Widgets show every currency. The pack reports accruals, never posts them.
- **The evidence pack (S7; PS-S7-1 operator-confirmed 2026-11-01, the rest decided under delegation, pending confirmation).** Two Report Templates of Regulatory Reporting,
  `settlement-dispute-pack` (JSON) and `settlement-dispute-pack-csv`, in `config/regulatory-report-templates/`, drafted from the
  dispute Case: the Case and its Incident timeline, plus the `settlement_dispute` rows whose `dispute_key` a member Incident
  carries in its Alert Rule key (the Report Template `rows` source, a generic Regulatory Reporting seam added for this slice).
  `ps_dispute` now also writes the evidence of each line: our `rate_ids` and `rating_run_id`, the `agreement_version`
  (`valid_from` of the version in force), `tolerance_type`, `tolerance`, `tolerance_amount`, both FX rows (`rated_currency`,
  `our_fx_rate`, `our_fx_rate_date`; `invoiced_currency`, `partner_fx_rate`) and the `statement_id` and `statement_version`. A line
  with no re-rating run (`rate_ids` or `rating_run_id` blank) refuses the draft (422), never exports blank. Four-eyes and the file
  drop are Regulatory Reporting's own; a maker without `canRevealLinkEntities` cannot read a rows report back (it is withheld). No PDF
  letter (demand-gated, PS-D8).
- **Not built (later slices).** Docs and registration (S8); wholesale, MVNO and roaming (W1 to W3, demand-gated).

## Verification

`PartnerSettlementPackGoldenTest`, `PartnerSettlementReferenceTest`, `PartnerSettlementSeedTest` and `PartnerSettlementCorpusTest`
(module `inspecto-reconciliation`, beside `TelcoRaGoldenTest`). They create the Space through `POST /spaces`, ingest the seeded
synthetic `PartnerSettlementCorpus` (four fictional partners, five agreement versions, three monthly cycles of usage) through the
template's own Pipelines and run the Jobs as authored. The golden holds the findings to the exact planted set with every look-alike
silent (a day exactly at the tolerance, the day after a mid-cycle tolerance change, an FX rate coinciding with ours, a balanced
credit line, a statement not yet due), every expected and billed amount to an independent plain-Java oracle, the Reconciliation
Breaks to the oracle's key set, and a re-run to the same row counts. The calendar equals a `java.time` oracle and hand-worked dates
including a holiday plus weekend roll. Mutation checks, each of which turns a named key red: tolerance one step tighter, the snap
and the finding comparator made strict / non-strict, the re-sent line counted twice, the oldest rating run, the superseded statement
version, the cut-off neighbour test inverted, and a feed without FX rates.

`PartnerSettlementPositionGoldenTest` (S4, same module, 19 tests) runs the same Space on `generateWithPositions()`: the S3 corpus plus
an agreement invoiced in EUR into a USD agreement and finance payments, credit and debit notes. Every column of every movement,
position and roll-forward row equals an independent oracle; the identities (net = payable - receivable; opening + invoiced - paid +
adjustments = closing; opening = previous closing; the last closing = the sum of the movements) are one SQL assertion each; a
restated statement moves the closing by exactly the corrected line and the roll-forward still closes; with no FX rate the lines are
counted and named. Eleven mutants each turn a named row red (see the [as-built record](plan-as-built.md)); one, a flipped sign, leaves the SQL identity green, which
is why the oracle and hand-worked amounts exist.

`PartnerSettlementDisputeGoldenTest` (S5, same module, 5 tests) holds `settlement_dispute` to the ten planted variance lines (nine
amount mismatches, one FX difference) with every disputed amount equal to the S3 oracle's, one dispute per partner and cycle against a
hand-written list, the window end equal to the calendar's and the lead time inclusive at both ends (5 days before and the last day
alert, 6 days before and the day after do not), the two shipped Alert Rules through the production grouped Measure probe, and the
position's disputed columns equal to the dispute lines with the net identity intact; dropping `fx_difference` from the Job turns the
P3 June dispute red. `PartnerSettlementDisputeCaseTest` (module `inspecto-case-management`, 2 tests) posts the three shipped governance
bodies through their routes over real HTTP, runs the shipped Alert Rules and Case Rule on the real object engine (ten lines and one
reminder become six Cases; an unrelated Incident never joins), stamps the SLA on the sweep, escalates an `OPEN` settlement Case and not a
`DISPUTED` one, moves Cases `DISPUTED → AGREED → CREDIT_PENDING → CLOSED` and `DISPUTED → REJECTED → ESCALATED → RESOLVED → CLOSED`, and
records the Impact as PS-D6 maps it.

`PartnerSettlementAgingGoldenTest` (S6, same module, 9 tests) runs the S4 corpus through all twelve Jobs. Every aging row equals an
oracle built from the S4 movement oracle (oldest-cycle-first application, received dates, the calendar's payment due date) and every
accrual row an oracle that prices the latest re-rating run from the corpus; hand-worked figures pin A1's June (12624.5 recorded, May
takes 10183.01, June gets 2441.49), D1's May left open by a record filed under another partner, and the three KPI values. The
bucket boundaries are inclusive (30, 31, 60, 61, 90, 91 days) and configurable; the payment-due Alert Rule raises exactly the open
cycles at their lead time (the corpus holds a 5-day and a 6-day cycle) and follows its parameter; a payment after `as_of` is not
counted; E1's July accrual is `unbilled` on its statement due date and `statement_late` the day after, when the `missing_statement`
finding starts; a 50 % provision halves the dispute provision; every `settlement_overview` tile renders through the KPI and BI routes
with each bar equal to the Dataset's sum. Six mutants each turn a named row red (see the [as-built record](plan-as-built.md)).

`PartnerSettlementDisputeGoldenTest` also pins the S7 evidence (6 tests now): every dispute line's run and rate ids equal the
corpus's latest run of that day (the stale May run loses), the agreement version and tolerance equal the corpus agreement in force,
C1's FX line carries the partner's 0.93 beside our 0.91 and A1's July line the restated statement version 2, and every `rows.<column>`
both shipped templates read is a column the Job writes. `PartnerSettlementDisputePackTest` (module `inspecto-regulatory-reporting`, 4
tests) renders both shipped templates: the rate and run of every line are named, and a line with no run is refused for each format.
The draft, the four-eyes approval and the file drop of a rows template run over HTTP in `ControlApiRegulatoryReportRowsTest` (6 tests).

## Related

[user guide](user-guide.md) · [admin guide](admin-guide.md) · [features](features.md) · [as-built record](plan-as-built.md) ·
[open work](../../../../superpower/telco-packs-plan.md) ·
the starter it extends: [Telecom revenue assurance](../telco-ra/architecture.md) · [Reconciliation](../../module/reconciliation/index.md).
