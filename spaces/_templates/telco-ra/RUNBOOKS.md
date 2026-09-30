# Telecom revenue assurance — runbooks

Runbooks for the controls of the `telco-ra` Space Template. All data in this template is synthetic.
Tolerances and thresholds are configuration: change them in the file named for each control, never in SQL.

## KPI definitions

| KPI | Definition | Widget |
|---|---|---|
| Leakage found | `sum(LEAKAGE_AMOUNT)` over the `ra_leakage` Dataset (every finding of the re-rating, roll-forward and settlement controls) | `ra_leakage_found`, `ra_leakage_by_control`, `ra_leakage_items` |
| Leakage confirmed | `sum(confirmed)` over the latest impact-ledger snapshot (`ra_recovery` Dataset) | `ra_leakage_confirmed` |
| Leakage recovered | `sum(recovered)` over the latest impact-ledger snapshot (`ra_recovery` Dataset) | `ra_leakage_recovered` |

Reconciliation Breaks are not in `ra_leakage`: a breach opens one Incident per Reconciliation, and its money
reaches the KPIs once the analyst records the Incident's impact and Disposition (below).

## Working a finding (every control)

1. Open the Incident (Reconciliation) or the Alert (sql.template control) and read the items.
2. Record the money on the Incident or Case: `PUT /objects/{id}/impact` — suspected, confirmed, and later
   recovered.
3. Set the Disposition: CONFIRMED, RECOVERED, WRITTEN_OFF, FALSE_POSITIVE, and so on.
4. The `ra_objects_analytics` Job snapshots every impacted object into the `impact_ledger` Dataset. The
   `ra_recovery` Job then keeps the latest snapshot for the confirmed and recovered KPIs.

## Completeness — switch → mediation → rating

- **Control:** Reconciliation `ra_xdr_completeness`, run by the `ra_xdr_completeness` Job (`recon.run`).
  Key `XDR_ID`. `USAGE_UNITS` is compared with an absolute tolerance of 1 unit.
- **A↔B `missing_right`:** mediation lost the record. Check the mediation reject and error folders for that
  window. Re-feed the record, then re-rate it.
- **A↔C `missing_right` only:** mediation passed the record, but rating never saw it. Check the rating
  suspense queue.
- **`value_break`:** usage was changed between network and rating, for example by truncation. Compare the
  raw switch record.

## Rated vs billed

- **Control:** Reconciliation `ra_rated_vs_billed`. Key `SUBSCRIBER_ID`. The summed `CHARGE` is compared with
  an absolute tolerance of 0.05.
- **`missing_right`:** a subscriber was rated but never invoiced. Check the bill run's exclusions.
- **`value_break`:** the invoice is lower than the rated usage. Look for a discount or cap that should not
  apply, or for usage that reached the bill run late.

## Re-rating

- **Control:** Job `ra_rerating` (`sql.template`), with `tolerance` of 0.02 in the Job's file.
- **Alert Rule:** `ra_rerating_leakage`.
- **How it works:** every rated record is re-priced against the `tariff` Dataset. A record is flagged when the
  difference exceeds the tolerance, or when its plan and service have no tariff at all.
- **What to do:** a positive `LEAKAGE_AMOUNT` is under-charging. Check the rating engine's tariff version
  against the reference table.

## Roll-forward

- **Control:** Job `ra_rollforward`, with `tolerance` of 0.01.
- **Alert Rule:** `ra_rollforward_break`.
- **How it works:** each subscriber-day must satisfy opening + top-ups + adjustments − debits = closing.
- **Adjustments are not leakage.** A goodwill credit is an explained move, and it balances.
- **What to do:** an unexplained difference means value moved outside the ledger. Trace the charging
  system's balance events for that day.

## Settlement

- **Control:** Job `ra_settlement`, with `tolerance_pct` of 1.0.
- **Alert Rule:** `ra_settlement_overbilling`.
- **How it works:** each partner statement line is compared against our own switch minutes, counted per
  started minute, multiplied by the agreed `ic_rates`.
- **What to do:** a positive `LEAKAGE_AMOUNT` is an overcharge by the partner. Raise a dispute that carries
  our minute count. A statement line with no agreed rate is flagged as well.
