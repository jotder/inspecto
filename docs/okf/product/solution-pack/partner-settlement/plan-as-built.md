---
type: Feature
title: "Partner settlement — decisions and as-built record"
description: "Slices S0 to S7 of PACK-PARTNER-SETTLEMENT-1 as built: the answered decisions with how to reverse them, the corpus, the mutation passes, the measurements, the deviations from the plan and the traps found."
tags: [partner-settlement, plan, as-built]
timestamp: 2026-11-01T00:00:00Z
---

# Partner settlement — decisions and as-built record

> **What this page is.** The durable record of slices **S0 to S7** of `PACK-PARTNER-SETTLEMENT-1` (S0 to S3 built 2026-10-20, S4
> 2026-10-21, S5, S6 and S7 2026-11-01), distilled from the original plan on 2026-10-22. How the pack works today is in [architecture](architecture.md) and
> [admin guide](admin-guide.md). **Open work** (S8, W1 to W3, the open decisions, the unsigned contract with the re-rating
> pack) lives in [`superpower/telco-packs-plan.md`](../../../../superpower/telco-packs-plan.md). Full history: the archived original
> [`partner-settlement-wholesale-plan.md`](../../../../archived-documents/plans-archive/partner-settlement-wholesale-plan.md)
> (sections 16 and 17 are the as-built narratives).

## Starting point (2026-10-18)

The `telco-ra` Job `ra_settlement` is a `sql.template` control, **not a Reconciliation**: voice only, one flat rate per partner
(`ic_rates`, not effective-dated), minutes rounded up per started minute, daily grain, one global `tolerance_pct`. The matrix row Q
calls it "partner-statement reconciliation"; that wording is corrected at S8. Competitors' common shape (published claims, Subex,
TEOCO, LATRO, Mobileum, Araxxe): ingest the partner's invoice, price our own traffic at the contract, compare, raise a dispute, track
it to a credit note. No real partner statement, TAP file or wholesale invoice has been seen; every format is synthetic.

## Decisions (PS-D1 to PS-D10, answered 2026-10-20: all as recommended)

| ID | Decision as answered | How to reverse |
|---|---|---|
| PS-D1 | A separate `partner-settlement` pack, not an extension of `telco-ra` (different buyer, six more feeds) | Fold into `telco-ra`: a file move plus a golden re-pin |
| PS-D2 | Leave `ra_settlement`, `ic_statement`, `ic_rates` in `telco-ra` as the starter | Delete the three files and re-pin `TelcoRaGoldenTest` |
| PS-D3 | Per-agreement tolerance by SQL normalisation before the Reconciliation, no add-on change | Add `toleranceFrom: <column>` to the Reconciliation add-on (a `ReconService` change with its own tests); take it only if analysts are confused |
| PS-D4 | FX rates from an operator-fed `fx_rate` Reference; the platform fetches no market rate | A Collector against a rate provider, demand-gated |
| PS-D5 | Ship an authored CASE workflow that replaces the Space default (states are a superset) | A `kind: settlement` Case attribute and a branching workflow (a Workflow & SLA change). Built at S5 as an administrator-applied body (PS-O5 below) |
| PS-D6 | Impact on a dispute Case: suspected = disputed, confirmed = accepted credit, recovered = credit note received, prevented = caught before payment | Documentation only |
| PS-D7 | Accrual provision on disputed amounts: a configurable default of 100 %, stated as finance policy | One config key |
| PS-D8 | The evidence pack through Regulatory Reporting (`settlement-dispute-pack`), no new document engine | A PDF letter engine, demand-gated |
| PS-D9 | The Impact ledger stays manual, as `telco-ra` decided 2026-10-04 | n/a |
| PS-D10 | Wholesale / MVNO (W1) before roaming (W3) | Order only |

PS-D3 and PS-D5 are the two whose reversal touches an existing engine. PS-D0 (split the board row) is done: `PACK-RERATING-1` is
its own row.

## S0: contract and corpus

The contract with the re-rating pack (R1 to R8) was **not signed**; S3 runs on the planned fallback, a stand-in `rerated_usage` feed
that implements R1 to R8 as written. R6 (the completeness Signal) is not implemented: the chain starts on the calendar's daily run.
The contract and its comparison with what the re-rating pack really shipped are open work (the plan's cross-pack section).

The golden lives in module `inspecto-reconciliation`, whose test classpath carries `ControlApi`, the Collector and the Reconciliation
engine; whether it also carries Workflow and Regulatory Reporting is checked at S5 and S7.

**Corpus.** `PartnerSettlementCorpus`, hand-built and deterministic, four fictional partners (`P1` to `P4`, countries `XA`, `XB`,
holidays `2026-06-10`, `2026-07-13` for XA and `2026-06-15`, `2026-08-25` for XB), five agreement versions, three monthly cycles of
usage (2026-05-01 to 2026-07-31), judged as of 2026-08-20:

| Agreement | Terms | Planted |
|---|---|---|
| `AG-VOICE-A1` (P1, we pay, USD) | percent 1.0, then 2.0 from 2026-07-16; monthly, due 10 days | stale May re-rating run; May statement re-sent; July v1 with a bad line and a control total one unit off, restated as v2; mismatches `05-10` (+3 %), `05-20` (+1.01 %), `06-20` / `06-21` (+10 and -4), `07-15` (+1.5 % under the 1 % version); cut-off pair `06-14` / `06-15` (-60, +60); unrated `06-25`. Look-alikes: `05-12` exactly +1 %, `07-16` +1.5 % under 2 %, `07-20` (v1 billed 999, v2 right) |
| `AG-SMS-C1` (P3, they pay, rated USD, invoiced EUR) | absolute 5.0; semi-monthly, due 5 days; contract USD to EUR with decoy spot rows | FX difference `06-18` (partner 0.93, ours 0.91); mismatch `06-22` (+50) and `07-02` (+5.0001). Look-alikes `06-19`, `06-20` (exactly +5.0000), `06-21`, a balanced credit line |
| `AG-VOICE-D1` (P2, we pay, USD) | absolute 0.5; monthly | July statement overdue (`missing_statement`); mismatch `06-04` (+0.5001); look-alike `06-03` (exactly +0.5000) |
| `AG-VOICE-E1` (P4, they pay, USD) | percent 1.0; monthly, due 25 days | July statement not yet due: silent |

Two statements name an unknown partner (`P99`) and an unknown agreement for a known partner.

## S1 to S3: References, ingestion and the compare

Golden: 9 `amount_mismatch` (corrected 2026-11-01 from 8: the corpus also plants A1 `06-26`), 2 `cutoff_difference`, 1 `fx_difference`, 1 `missing_statement`, 1 `unrated_usage`, every look-alike
silent; every expected and billed amount equals a plain-Java oracle; the Breaks equal the oracle's key set; a second run gives the
same counts. Tests: `PartnerSettlementCorpusTest` (4), `PartnerSettlementReferenceTest` (4), `PartnerSettlementSeedTest` (1),
`PartnerSettlementPackGoldenTest` (12); `ControlApiSpaceTemplateSeedGateTest` applies the template and checks its runbook.

**Mutation pass.** Tolerance one step tighter; the snap comparator `<=` to `<`; the finding comparator `>` to `>=`; `rn = 1` to
`rn >= 1` (the May statement doubles); the oldest rating run; the superseded statement version; the cut-off neighbour test inverted;
a feed with no FX rate. Each turns a named key red.

**Deviations and traps** (beyond those in the architecture page):

- A variance FX explains is `fx_difference` even one step beyond the tolerance (under the finding-comparator mutant C1 `06-20` reads
  `fx_difference`).
- ⚠ `HUGEINT` (a `sum()` of an integer column) is written as `DOUBLE` by Parquet, so the schema-seed check fails: cast to `BIGINT`.
- ⚠ The template gate reads the Schema of every Dataset an Alert Rule counts, so Job-written Datasets need seeds before the template
  applies; regenerate them with `PARTNER_SETTLEMENT_REGENERATE=true`.
- The Reconciliation Break key joins the key columns with a middle dot, and `ReconStateStore` keeps a Break per day: a catch-up runs
  `recon.run` once per day (`day` parameter).
- An independent review found three SQL defects the first corpus could not see, now fixed and pinned: the unrated check read every
  rating run; statements naming an unknown partner or agreement fed the compare and could supersede a real version; an unrated day
  could be one side of a cut-off pair.
- The oracle shares the **business rules** with the SQL (tolerance inclusive, latest run, highest version, neighbour-day cut-off); it
  is independent of the implementation, not of the rules.

## S4: netting, roll-forward and FX

Delivered: the `settlement_payment` feed and the Jobs `ps_movement`, `ps_position`, `ps_rollforward` (now 7 feeds, 9 Job-written
Datasets, 9 chained Jobs). S5 was not started because its CASE workflow needs the governance decision (open work).

**Decisions made inside S4** (each reversible, none touches an existing engine): netting only inside one settlement currency (open
decision for cross-currency); the netting cycle is the calendar month a statement's cycle ends in, a payment's month is its date; the
position reads statements and the roll-forward adds finance's records, neither reads our pricing or a dispute (no disputed column
until S5); FX date per movement (usage line: `fx_date_rule`; fee, tax, credit, adjustment: the cycle end; payment or note: its own
date), latest rate on or before for the agreement's `fx_rate_type`; agreement version = the latest starting on or before the movement
date for the right partner; a movement that cannot be placed is named (`fx_missing`, `unknown_agreement`), never dropped; no
opening-balance feed (a series opens at zero; an older balance is an adjustment note); `paid` is money we paid minus money received.

**Golden.** `PartnerSettlementPositionGoldenTest` (19), on `generateWithPositions()`: the S3 corpus plus `P5` / `AG-VOICE-F1`
(invoiced in EUR into a USD agreement, a rate change mid-June, decoy spot rates) and 11 finance records for 9 payment ids. All 24
columns of 442 movements, 11 of 7 positions and 10 of 11 roll-forward rows equal an independent `BigDecimal` oracle; one SQL assertion
per identity; hand-worked amounts (a payment sent twice is paid once, 10000.0000; E1's correction makes NG-XB June paid 430.0000; 800
EUR = 880.0000 USD at 1.1000); without the restated July statement the July and August closings rise by exactly 799.0000; with no FX
rate all 64 F1 movements are `fx_missing`.

**Mutation pass.** 11 mutants, each red for the named reason. Two lessons: a flipped side sign keeps the position equal to the
oracle and **the SQL identity green** (only the oracle and hand-worked amounts catch it); a credit line counted as invoiced moves the
split but **not the closing**.

**Measurements.** On the corpus, 28 runs on a loaded desktop JVM: `ps_movement` 90 to 141 ms (median 122), `ps_position` 55 to 84 ms
(median 71), `ps_rollforward` 45 to 74 ms (median 54). Not measured at volume; `ps_movement` re-converts every line each run (a full
rebuild, like S3).

**Traps.** ⚠ `string_agg(..., ';' ...)` inside a `sql.template` is refused ("only a single statement is allowed": the guard counts any
`;`, even in a quoted literal), so `orig_net` joins with a comma. A blank `netting_group` zeroed the roll-forward through an equality
join: both joins compare with `IS NOT DISTINCT FROM`. The corpus `contractRate` filters the currency pair. The S3 `SeedTest` ingests
the S4 corpus so the payment feed's seed has a Pipeline-written file to compare against.

## S5: disputes as Cases

Delivered: the Job `ps_dispute` writing `settlement_dispute` (between `ps_findings` and `ps_movement`, so the chain is now ten Jobs),
the position's `disputed_amount` and `disputed_lines`, the Alert Rules `ps_dispute_line` and `ps_dispute_window`, the Case Rule
`ps_dispute_case` with its `caserule.evaluate` Job, and three governance bodies in `config/governance/` (the CASE workflow, a Case SLA
Policy, an age Escalation Rule). Now 17 Datasets (10 Job-written), 3 Alert Rules, 1 Case Rule.

**Decisions made inside S5, under operator delegation, PENDING CONFIRMATION** (each reversible, none touches an engine):

| ID | Decision | How to reverse |
|---|---|---|
| PS-O5 | The dispute workflow, SLA Policy and Escalation Rule reach a Space by an **administrator apply step**: JSON bodies ship in `config/governance/` and are posted to their `canAdminister` routes once per Space. No governance seed: the template seed gate and every import refuse governance kinds on purpose (`ASSURE-WORKFLOW-SLA-1`), and a template that could seed a workflow would replace every Case's lifecycle in the Space without a review. | A governance seed in `SpaceManager.createFromTemplate` behind `canAdminister` (an engine and security change with its own tests) |
| PS-S5-1 | A dispute line is an `amount_mismatch` or `fx_difference`; a `cutoff_difference` (a timing convention whose pair nets inside tolerance) and every data-quality finding are not | One `IN (...)` list in `ps_dispute` |
| PS-S5-2 | `dispute_key` = partner, cycle start, cycle end (two agreements of one partner and one cycle share a Case; different cycles do not) | The key expression in `ps_dispute` |
| PS-S5-3 | `disputed_amount` = the absolute variance in the settlement currency; on the position it is a sum beside the net and does not follow the Case outcome (SQL cannot read Case state) | A signed column, or an outcome feed exported from the Cases |
| PS-S5-4 | Both new Alert Rules are CRITICAL, because only a CRITICAL Alert opens an Incident and the plan makes each line an Incident; `ps_findings_found` stays WARNING | Severity on the two rule files |
| PS-S5-5 | The window reminder reads the data (`window_alert` within `window_alert_days`, default 5, both ends inclusive), not the Case state: a Case already `DISPUTED` is reminded too | An Escalation Rule condition on a Case attribute, once a Case carries its window end |
| PS-S5-6 | The workflow keeps every built-in CASE state and move (it replaces the built-in for every Case in the Space) and adds `DISPUTED`, `AGREED`, `REJECTED`, `CREDIT_PENDING`; `REJECTED` goes back to `ESCALATED` or to `CLOSED` | Edit the body before applying it |
| PS-S5-7 | SLA (one working day to respond, 20 to resolve, Mon-Fri 09:00-17:00 UTC) and the escalation (notify at three days for a `partner-settlement` Case still `OPEN`, `INVESTIGATING` or `ESCALATED`) are illustrative | The administrator edits the bodies |
| PS-S5-8 | Disposition: the built-in ladder is not extended; the outcome is the Case state (`AGREED`, `REJECTED`, `CREDIT_PENDING`) plus the Impact, so no glossary change is needed at S8 for it | A disposition value in the glossary and the ladder |

**Known limit:** the position LEFT-joins the disputes onto the statement groups, so a dispute line whose (netting group, month,
currency) has no statement movement (only possible when an agreement version changes netting group or currency mid-cycle, which
the corpus does not do) is in `settlement_dispute` but not on any position; a full join is the fix if that case appears.

**Deferred from the S5 plan row:** the payment-due date as an Alert Rule (it needs the open balance per cycle, which is S6 aging), and
the statement-due date (already the `missing_statement` finding, so no new rule). The Impact is not written by the pack (PS-D9).

**Verification.** `PartnerSettlementDisputeGoldenTest` (5, `inspecto-reconciliation`) and `PartnerSettlementDisputeCaseTest` (2,
`inspecto-case-management`), described on the [architecture](architecture.md) page; the S3 and S4 goldens pass unchanged except the
chain list and the template-shape counts. The golden split mirrors the bypass pack's B3: the SQL half runs the real chain, the Case
half runs the shipped rules on the real object engine with the Measure probe answering the keys the SQL half pins.

**Traps.** ⚠ An Alert Rule severity must be `INFO`, `WARNING` or `CRITICAL` (the template gate refuses `MAJOR`), and only `CRITICAL`
opens an Incident. ⚠ An SLA Policy stamps `dueAt` / `responseDueAt` on the SLA sweep, not when the Case opens. ⚠ `PUT
/objects/{id}/impact` takes `{"impact": {...}}`.
⚠ `affected-modules` does not name `ControlApiSpaceTemplateSeedGateTest` (directory `inspecto`, artifact `inspecto-processor`), which applies every template: run it
when a template changes.

## S6: aging, accruals and the dashboard

Delivered: the Jobs `ps_aging` (`settlement_aging`) and `ps_accrual` (`settlement_accrual`) after `ps_rollforward` (the chain is now
twelve Jobs), the payment-due Alert Rule `ps_payment_due` deferred from S5, the KPIs `ps_open_disputed`, `ps_aged_over_60`,
`ps_accrued_unbilled`, three bar Widgets and the dashboard `settlement_overview`, and the runbook section. Now 19 Datasets (12
Job-written), 4 Alert Rules.

**Decisions made inside S6, under operator delegation, PENDING CONFIRMATION** (each reversible, none touches an engine):

| ID | Decision | How to reverse |
|---|---|---|
| PS-S6-1 | Grain: one aging row per agreement and statement cycle (not per netting group): the payment due date, the dispute window and the statement date are per agreement cycle | Aggregate the aging by `netting_group` in a Widget, or a second Job |
| PS-S6-2 | A payment names its agreement, not a cycle, so finance's payments and notes are applied to the agreement's cycles **oldest first** (FIFO), per agreement, direction and settlement currency; a cycle with a negative statement takes nothing; money beyond every cycle is not shown (the roll-forward carries it); records after `as_of` and statements received after it are not counted | A cycle reference on the payment feed (a Schema change) and an exact match before FIFO |
| PS-S6-3 | Balances **and** disputes are aged from the received date of the current statement version. The plan's alternative, the Case's `DISPUTED` timestamp, cannot reach SQL; ageing from `settlement_dispute` gives the same date because a dispute line has no date of its own | An outcome feed exported from the Cases (the PS-S5-3 reversal) adding the date the Case reached `DISPUTED` |
| PS-S6-4 | Buckets `0-30`, `31-60`, `61-90`, `90+` (both ends inclusive), boundaries as Job parameters; the labels follow them | Parameters only |
| PS-S6-5 | `ps_payment_due` is a WARNING (an Alert, not an Incident: a payment reminder is not a dispute), one key per agreement cycle (`aging_key`), raised from `payment_alert_days` (default 5) before the due date and every day after while the balance is open; the balance includes the disputed amount | Severity on the rule file; `open_balance - open_disputed` in the flag |
| PS-S6-6 | `ps_accrual` prices the latest re-rating run itself (the `ps_expected` pricing, without its due filter), because `settlement_expected` holds a cycle only once its statement is due or received; for a due cycle the two agree to the cent (pinned on D1 July) | Widen `ps_expected` with a status column (changes the compare's input and the S3 golden) |
| PS-S6-7 | A statement-less cycle is `unbilled` up to and including `statement_due` and `statement_late` from the day after, which is the day `missing_statement` starts; it accrues in both states. A billed cycle accrues nothing unbilled (`accrued = expected - invoiced` only for a period with no statement, as the plan says) | Accrue the expected-minus-invoiced difference on billed cycles too |
| PS-S6-8 | The dispute provision is `provision_pct` (default 100, PS-D7) of the absolute disputed amount of the cycle, whatever the Case outcome (PS-S5-3) | The outcome feed above |
| PS-S6-9 | The KPI tiles count **one** settlement currency (`kpi_currency`, default USD, a Job parameter writing `kpi_` columns, since a KPI has no filter) and add payables and receivables as amounts at stake, never netted; the Widgets show every currency | Converting into a reporting currency with `fx_rate` (needs a reporting rate-type decision), or one KPI per direction |
| PS-S6-10 | Amounts on the aging and accrual rows are in the agreement's direction (positive = owed in that direction), not signed from our side as the movements are; `direction` is on every row | One sign expression per Job |

**Known limits.** A cycle with a statement but no rated usage at all has no accrual row (so no dispute provision). A cycle whose
lines resolve to two directions, or to two agreement versions with a different `currency` or `netting_group`, gives two aging (and
accrual) rows with the same key, and the cycle's `open_disputed` and dispute provision then appear on both (the corpus never does
this; an agreement keeps its direction, currency and netting group inside one cycle). A cycle whose statement totals negative takes no
payment; the corpus has none, so that branch is not exercised. The KPIs read `as_of` at day grain with no comparison (each run
replaces the snapshot, so there is no previous day to compare).

**Verification.** `PartnerSettlementAgingGoldenTest` (9, `inspecto-reconciliation`), described on the [architecture](architecture.md)
page. The earlier goldens pass unchanged except the chain list and the template-shape counts. On the corpus as of 2026-08-20: 13 aged
cycles, 10 payment-due Alerts, 15 accrual rows (D1 July `statement_late` 1702.0000, E1 July `unbilled` 2573.0000), KPI values 213.2351
open disputed, 4660.0000 aged over 60 days, 4275.0000 accrued unbilled (USD).

**Mutation pass** (six mutants, all pinned in the golden, each red for the named reason and each probe green on the shipped Job):
payments applied newest cycle first (the oracle differs); the payment-date filter dropped (C1's first cycle as of 07-09 shows
566.0000 open, not 1566.0000); the first bucket boundary made exclusive (D1 May at exactly 30 days reads `31-60`); the payment lead
time made exclusive (C1's late-July cycle, due in exactly 5 days, goes silent); the KPI currency filter removed (the open-disputed tile
reads 288.2352, C1's EUR 75.0001 added); the statement-due day counted late (E1 July reads `statement_late` on its due date).

**Traps.** ⚠ Every Control API response is enveloped in `data`: a test reading `content` at the top level gets `null`. ⚠ A KPI has no
filter: a measure is `agg(column)` over the whole Dataset, so a subset needs its own column. ⚠ `PARTNER_SETTLEMENT_REGENERATE=true`
rewrites every seed byte-wise (same Schema): restore the unchanged ones (`git checkout -- spaces/_templates/partner-settlement/data`)
before committing.

## S7: the dispute evidence pack

Delivered: the Report Templates `settlement-dispute-pack` (JSON) and `settlement-dispute-pack-csv` in
`config/regulatory-report-templates/`, thirteen evidence columns on `settlement_dispute` (`ps_dispute` now also reads
`settlement_expected` and `settlement_billed`), and a generic Regulatory Reporting seam: the Report Template `rows` block
(`rows: {dataset, matchOn}`, [Regulatory Reporting spec](../../module/regulatory-reporting/spec.md)).

**Decisions made inside S7, assistant-decided 2026-11-01 under delegation, PENDING OPERATOR CONFIRMATION:**

| ID | Decision | How to reverse |
|---|---|---|
| PS-S7-1 | **Option A.** A Report Template could read only the Case, its Incidents, attachments and typed inputs, and a dispute-line Incident carries only its Alert Rule key (`dispute_key`, `item_key`), so the line evidence could not reach a filing. Regulatory Reporting gained a generic `rows` source: the rows of a named Dataset whose `matchOn` column equals a value a member Incident carries in `key.<matchOn>`. **Rejected, option B** (pack only): widen `ps_dispute_line`'s `by` to about twelve evidence columns. Every Incident title would then hold the whole row; a new re-rating run would change the key and open a second Incident for the same line in the same Case; the renderer still could not address the dotted `key.*` attributes; and the `ps_dispute_window` Incident in the same Case, which carries no line, would break a required per-Incident run field. | Remove the `rows` block from the templates and the `rows` support from `ReportTemplate` / `ReportRows` |
| PS-S7-2 | The evidence rides on `settlement_dispute` (thirteen more columns), not a new Job and Dataset: it is the same grain and key, and the chain, the Alert Rules and the Case Rule stay as they were | A separate `ps_dispute_evidence` Job |
| PS-S7-3 | "No re-rating run" = `rate_ids` or `rating_run_id` blank on a line (a `statement_only` variance has no expected row); both are required fields, so the draft is refused 422 naming the line, never filed blank. A Case with no dispute row behind its keys is refused too | `required` on the two fields |
| PS-S7-4 | Two templates, JSON (`rootElement: settlementDisputePack`, lines and Incidents as arrays) and CSV (one header and one data row, each line field joined by `; `, as the renderer does for every CSV); the layout is illustrative, no partner's format | Edit the templates |
| PS-S7-5 | A report whose template reads `rows` is withheld (`content`, `subjectTitle`, `inputs` removed, `keyMasked: true`) from a reader without `canRevealLinkEntities`: the rows carry columns no object key names, so masking by the keys would be guesswork. The settlement desk (role `operations`) can draft and send for approval but not read the pack back; the approver (`admin`) reads it | Mask rows with the Dataset's `BiResultMasking` (needs a public seam in `inspecto`) |

**Seam facts** (Regulatory Reporting): the rows are read through the Dataset's relation with the keys bound, `ORDER BY ALL`, at most
1000 per report (more is 422, never truncated); a Dataset the drafter cannot see is 404; the rows join the source fingerprint, so a
row changed after the draft shows `sourceChanged` to the approver. Values are passed as text, numbers or booleans (a date as its
ISO text).

**Verification.** `PartnerSettlementDisputeGoldenTest` (6 tests, one new: run and rate ids against the corpus's latest run, agreement
version, tolerance, both FX rows of C1 June, A1 July's restated statement version 2, every template `rows.<column>` written by the
Job); `PartnerSettlementDisputePackTest` (4, `inspecto-regulatory-reporting`: both shipped templates render, name the rate and run,
refuse a run-less line and an empty Case); `ControlApiRegulatoryReportRowsTest` (6: only the matched rows in a stable order, withholding that survives a later template edit, the
four-eyes path delivering them, `sourceChanged` after a row change, the 422 and 404 gates, the `rows` block validation).

**Traps.** ⚠ A template source walks maps by `.`, so an Incident attribute named `key.<column>` cannot be addressed by a field;
the `rows` source reads it on the server instead. ⚠ The seed of `settlement_dispute` was regenerated (new columns); restore every
other seed after `PARTNER_SETTLEMENT_REGENERATE=true`.
