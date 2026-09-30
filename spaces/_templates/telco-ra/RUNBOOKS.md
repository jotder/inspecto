# Telecom revenue assurance — runbooks

These are the runbooks for the controls of the `telco-ra` Space Template. All data in this template is
synthetic. Called numbers use the fictional `+1-555-01xx` range.

- **Tolerances are configuration.** Each control's tolerance is a key in the file named for it; the SQL
  never hard-codes one.
- **Money is `DECIMAL(18,4)` end to end,** so a tolerance compares exact amounts, not float noise.
- **Edition:** the template needs **Professional or Enterprise**, because it ships Alert Rules
  (`alert.dispatch`). A Personal build lists it in the gallery as not available. The confirmed and recovered
  KPIs also need the ops module (`objects.analytics`).

## Findings: leakage vs data quality

Every control Job writes one row per finding, with these columns:

- `ITEM_KEY`: what is wrong.
- `REASON`: why, with the values listed per control below.
- `FINDING`: either `leakage` or `data_quality`.
- `LEAKAGE_AMOUNT`: the money.

Rules for the two kinds:

- **A `leakage` finding always carries its amount.** A sum never drops one.
- **A `data_quality` finding** means the control could not judge the record: an ambiguous tariff, a NULL
  balance field, or a missing partner statement. Its amount is NULL, because none can be computed. Fix the
  data, and the next run judges the record.

## KPI definitions

| KPI | Definition | Widget |
|---|---|---|
| Leakage found | `sum(LEAKAGE_AMOUNT)` over `ra_leakage` (every finding of the re-rating, roll-forward and settlement controls; data-quality rows add nothing) | `ra_leakage_found`, `ra_leakage_by_control`, `ra_leakage_items` |
| Lost or short xDRs | distinct xDRs in `ra_xdr_lost` (lost at mediation, lost at rating, or short by more than the tolerance) | `ra_xdr_lost` |
| Leakage confirmed | `sum(confirmed)` over the latest impact-ledger snapshot (`ra_recovery`) | `ra_leakage_confirmed` |
| Leakage recovered | `sum(recovered)` over the latest impact-ledger snapshot (`ra_recovery`) | `ra_leakage_recovered` |

⚠ **Breaks are not xDRs.** The completeness Reconciliation counts a record lost at mediation twice: A↔B
and A↔C. On the golden corpus, **15 Breaks are 9 distinct lost or short xDRs**. Report xDRs from
`ra_xdr_lost`, and use Breaks to show where in the chain a record went missing.

## Working a finding (every control)

1. Open the item:
   - For a Reconciliation, open its Incident. The title counts the Breaks of every pair.
   - For a control Job, open its Alert.
2. Record the money on the Incident or Case with `PUT /objects/{id}/impact`: suspected, confirmed, and later
   recovered.
3. Set the Disposition: CONFIRMED, RECOVERED, WRITTEN_OFF, FALSE_POSITIVE, and so on.
4. The `ra_objects_analytics` Job snapshots every impacted object into `impact_ledger`. `ra_recovery` then
   keeps the latest snapshot for the confirmed and recovered KPIs.

## Completeness — switch → mediation → rating

- **Controls:**
  - Reconciliation `ra_xdr_completeness`, with key `XDR_ID` and a `USAGE_UNITS` tolerance of 1 unit.
  - Job `ra_xdr_lost`, which gives the distinct-xDR view.
- **A↔B `missing_right` / `lost_at_mediation`:** mediation lost the record. Check the mediation reject and
  error folders, re-feed the record, then re-rate it.
- **A↔C `missing_right` only / `lost_at_rating`:** mediation passed the record, but rating never saw it.
  Check the rating suspense queue.
- **`value_break` / `short`:** usage changed between the network and rating, for example by truncation.
  Compare against the raw switch record.

## Rated vs billed

- **Control:** Reconciliation `ra_rated_vs_billed`, with key `SUBSCRIBER_ID` and an absolute tolerance of
  0.05 on the summed `CHARGE`.
- **`missing_right`:** rated but never invoiced. Check the bill run's exclusions.
- **`value_break`:** the invoice is lower than the rated usage. Look for a wrong discount or cap, or for
  usage that reached the bill run late.

## Re-rating

- **Control:** Job `ra_rerating`, with `tolerance` 0.02. Alert Rule `ra_rerating_leakage`.
- **Which tariff row applies:** the one in force at the **call start**, meaning
  `EFFECTIVE_FROM <= EVENT_TS < EFFECTIVE_TO`, where a blank `EFFECTIVE_TO` means the row is still open.
- **A call that spans a tariff change** is priced entirely at the rate in force when it started. The rate is
  never split, and never taken at the call's end.
- **Reasons:**
  - `rate_mismatch` (leakage): the charge differs from the re-rated amount by more than the tolerance. A
    positive `LEAKAGE_AMOUNT` is under-charging. Check the rating engine's tariff version, especially after a
    tariff change.
  - `no_tariff` (data quality): no tariff row covers the call. The reference table has a gap.
  - `ambiguous_tariff` (data quality): two or more tariff rows cover the call, from an overlapping or
    duplicate row.
    - The control emits **one row per call**. It never fans out, and it never guesses a rate.
    - Fix the tariff table, and the call is re-judged on the next run.

## Roll-forward

- **Control:** Job `ra_rollforward`, with `tolerance` 0.05. Alert Rule `ra_rollforward_break`.
- **Reasons** (one finding per subscriber-day, first match wins):
  - `null_value` (data quality): opening, closing or a movement is blank, so the day cannot be judged.
  - `movement` (leakage): opening + top-ups + adjustments − debits ≠ closing. Value moved outside the ledger.
    Trace the charging system's balance events for that day.
  - `continuity` (leakage): day N's opening ≠ day N−1's closing. A negative `LEAKAGE_AMOUNT` is money minted
    between days. Check the end-of-day balance extract.
- **Adjustments are not leakage.** A goodwill credit is an explained move, and it balances.

## Settlement

- **Control:** Job `ra_settlement`, with `tolerance_pct` 1.0. Alert Rule `ra_settlement_overbilling`.
- **How it compares:** each partner statement line is matched against our own switch minutes (counted per
  started minute), multiplied by the agreed `ic_rates`.
- **Reasons:**
  - `amount_mismatch` (leakage): the difference is more than the tolerance. A positive amount is an
    overcharge by the partner. Dispute it, with our minute count attached.
  - `unknown_partner` (leakage): the statement comes from a partner with no agreed rate. **The whole
    statement amount is the leakage.** Refuse it until an agreement exists.
  - `no_traffic` (leakage): a known partner billed a day on which our switch carried nothing to them. The
    whole amount is the leakage.
  - `missing_statement` (data quality): our switch carried traffic to a partner that sent no statement for
    that day. Chase the statement, and accrue the expected amount.
