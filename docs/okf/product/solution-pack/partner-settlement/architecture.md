# Partner settlement

> **Level 2 solution page.** Content pack: Space Template `partner-settlement`
> (`spaces/_templates/partner-settlement/template.toon`). **Slices S0 to S4 of `PACK-PARTNER-SETTLEMENT-1` are built: the
> References, statement ingestion, the compare, and netting positions with a roll-forward.** Disputes as Cases, aging, accruals and the evidence pack are later
> slices ([plan](../../../../superpower/partner-settlement-wholesale-plan.md), sections 16 and 17). Checked against the template on 2026-10-21.

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
| `rerated_usage` | the stand-in for the re-rating contract (plan section 5): one row per agreement, direction, service, destination class, day and rating run, with `units, amount_expected, currency, rate_id, rating_run_id, rated_at, record_count, reason`. **The re-rating pack's own `rerated_usage` is a different, per-call retail contract and cannot feed this one yet** (plan section 17.1) |
| `settlement_payment` | finance's record, read and never written: `payment_id, agreement_id, partner_id, direction, payment_date, kind` (`payment`, `credit_note`, `debit_note`)`, amount, currency, recorded_at` |

Every Dataset, feeds and Job outputs, ships a zero-row schema seed under `spaces/_templates/partner-settlement/data/`, so the
template applies (the Alert Rule gate reads the Schema of `settlement_findings`) and the chain runs on a fresh Space.

## What the pack sets up

Nine chained `sql.template` Jobs (the first daily at 01:00, each next one on the previous one's success), two `recon.run` Jobs
(05:30) and one Alert Rule:

| Job | Writes | Notes |
|---|---|---|
| `ps_calendar` | `settlement_calendar` | per agreement version and closed cycle (monthly, semi-monthly, weekly): `statement_due`, `dispute_window_end`, `payment_due`, each rolled to the next business day |
| `ps_statement_current` | `settlement_statement_current` | re-sent lines once; highest version per agreement and cycle |
| `ps_statement_findings` | `settlement_statement_findings` | `control_total_mismatch`, `duplicate_statement`, `restated_statement`, `unknown_partner`, `unknown_agreement` |
| `ps_expected` | `settlement_expected` | latest re-rating run per agreement and cycle, in the settlement currency, with the tolerance in force that day |
| `ps_billed` | `settlement_billed` | the partner's usage lines at the same grain; `amount` snapped to expected inside the tolerance |
| `ps_findings` | `settlement_findings` | `amount_mismatch`, `cutoff_difference`, `fx_difference`, `missing_statement`, `unrated_usage`, `fx_rate_missing` |
| `ps_movement` | `settlement_movement` | every current statement line and every payment or note at line level, in the settlement currency with both currencies and the FX row (rate, date, type, status), signed from our side; `status` is `ok`, `fx_missing` or `unknown_agreement` |
| `ps_position` | `settlement_position` | per netting group, cycle month and settlement currency: `payable`, `receivable`, `net` (positive = we pay), `net_direction`, `orig_net` (net per original currency), line counts, `lines_unconverted` |
| `ps_rollforward` | `settlement_rollforward` | per netting group and settlement currency, one row for every month to the as-of month: `opening + invoiced - paid + adjustments = closing` |
| `ps_voice`, `ps_sms` | Break state | one Reconciliation per service family over `settlement_expected` and `settlement_billed`, keys `agreement_id, direction, service, destination_class` at day grain |

`ps_findings_found` is one Alert Rule over `settlement_findings`, counted `by agreement_id`: one Alert per agreement.

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
  proposed in the plan (section 13) and enter the glossary with slice S8; the UI and these docs use the plan's words.
- **Netting and the roll-forward (S4).** Netting happens inside one settlement currency: agreements of one netting group in
  different settlement currencies give separate positions (cross-currency netting needs a position currency and a reverse-rate
  convention, an open decision). A statement belongs to the month its cycle ends in, a payment or note to the month of its date. The
  position reads what the current statements say, the roll-forward adds what finance recorded; neither reads our pricing or a dispute,
  so a position is not an agreed balance. A movement that cannot be placed is named (`fx_missing`, `unknown_agreement`), never
  summed or dropped; there is no opening-balance feed (a series opens at zero) and no payment-integrity finding yet.
- **Not built (later slices).** The authored dispute Workflow, SLA Policy and Escalation
  Rule, and the Case grouping (S5; a Space Template carries no governance objects, so these are applied by an administrator);
  aging, accruals and the dashboard (S6); the evidence pack (S7); wholesale, MVNO and roaming (W1 to W3, demand-gated).

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
counted and named. Eleven mutants each turn a named row red (see plan section 17.4); one, a flipped sign, leaves the SQL identity green, which
is why the oracle and hand-worked amounts exist.

## Related

[user guide](user-guide.md) · [admin guide](admin-guide.md) · [features](features.md) · [plan](../../../../superpower/partner-settlement-wholesale-plan.md) ·
the starter it extends: [Telecom revenue assurance](../telco-ra/architecture.md) · [Reconciliation](../../module/reconciliation/index.md).
