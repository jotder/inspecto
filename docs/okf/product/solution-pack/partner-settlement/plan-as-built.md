---
type: Feature
title: "Partner settlement — decisions and as-built record"
description: "Slices S0 to S4 of PACK-PARTNER-SETTLEMENT-1 as built: the answered decisions with how to reverse them, the corpus, the mutation passes, the measurements, the deviations from the plan and the traps found."
tags: [partner-settlement, plan, as-built]
timestamp: 2026-10-22T00:00:00Z
---

# Partner settlement — decisions and as-built record

> **What this page is.** The durable record of slices **S0 to S4** of `PACK-PARTNER-SETTLEMENT-1` (S0 to S3 built 2026-10-20, S4
> 2026-10-21), distilled from the original plan on 2026-10-22. How the pack works today is in [architecture](architecture.md) and
> [admin guide](admin-guide.md). **Open work** (S5 to S8, W1 to W3, the open decisions, the unsigned contract with the re-rating
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
| PS-D5 | Ship an authored CASE workflow that replaces the Space default (states are a superset) | A `kind: settlement` Case attribute and a branching workflow (a Workflow & SLA change). ⚠ As built, a Space Template cannot carry it: open item for S5 |
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

Golden: 8 `amount_mismatch`, 2 `cutoff_difference`, 1 `fx_difference`, 1 `missing_statement`, 1 `unrated_usage`, every look-alike
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
