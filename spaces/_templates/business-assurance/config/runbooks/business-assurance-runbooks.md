# Business assurance pack: runbooks

Synthetic content only. Every number below comes from the pack's generated corpus.

## Alert Rule `ba_revenue_outside_band` (CRITICAL, one Alert and one Incident per day)

**What fired.** The day's revenue fell outside the Holt-Winters forecast band (forecast ± 4σ̂) on
Dataset `ba_revenue_forecast`. The Incident names the day as `key.ds`.

1. Open the Dataset and read the day's `actual`, `forecast`, `lower_band`, `upper_band`.
2. A **drop** below `lower_band`: check the Pipelines that feed revenue for that day. Look for late or
   missing files and rejected rows before you treat the drop as real loss.
3. A **spike** above `upper_band`: look for duplicated loads, a price change or a one-off event.
4. If the cause is a known business event (a holiday, a campaign), record it as the Incident's Disposition.
   The forecast does not learn from a point outside the band, so the next season's forecast is not skewed.
   The same clipping means a real change of level is never learned day by day. After three days it is
   reported once as `ba_revenue_regime_change` instead.

## Alert Rule `ba_revenue_regime_change` (CRITICAL, one Incident per shift)

**What fired.** Three days in a row fell outside the band, so the forecast treated it as a new level. It
re-based on the actual. The model reports the shift once, on its third day, and does not report the first two
days as separate breaches. While a run is still shorter than three days, those days show as
`ba_revenue_outside_band` Alerts. They heal when the run becomes a regime change.

1. Confirm the new level is real: a price change, a lost or new partner, a changed feed.
2. A step down with no business cause is a loss. Look for the Pipeline or Collector change on that date.
3. Record the cause as the Incident's Disposition. The forecast already follows the new level.

## Alert Rule `ba_revenue_drift` (CRITICAL, one Incident per drift)

**What fired.** The in-band forecast errors have leaned one way for long enough that a CUSUM crossed its limit.
The trend is changing even though no single day left the band. The model re-bases its level and trend when this
fires.

1. Plot `actual` against `forecast` for the last few weeks. A steady gap that keeps growing is a trend change.
2. A steady decline can be churn, a tariff change or a slowly failing feed. Check before you accept it.

**Known limits.** The detector catches a trend change of roughly 0.2σ a day or more (about 4 a day on the
synthetic corpus, whose noise σ is 20; +4 a day was caught on day 43 of the ramp). A slower change is absorbed
into the forecast's trend term and is **never flagged**. A +2 a day ramp raises nothing. Watch slow leaks with
a KPI comparison (for example `ba_revenue` against last year), not with this Alert Rule. A shift or ramp
inside the first 28 days (the warm-up) is absorbed silently, or reported late as drift.

## Alert Rule `ba_margin_data_quality` (WARNING, one Alert per product / channel / partner)

**What fired.** The group has a line with null revenue or cost, or a negative one, in the last 56 days. Its
erosion is not assessed (`status = data_quality`), so a cost-feed outage cannot read as a margin gain.

1. Find the lines in `ba_margin_lines` and fix the feed (for example a missing cost file, or a refund booked as
   negative revenue).
2. Groups with `status = new` (no baseline yet) or `insufficient` (under 10 lines or 1000 revenue in a window)
   are not assessed either. They raise nothing.

## Alert Rule `ba_margin_erosion` (WARNING, one Alert per product / channel / partner)

**What fired.** Recent margin % (the last 28 days) is more than 5 points below the baseline margin %
(the 28 days before) on Dataset `ba_margin_erosion`. The Alert names `product`, `channel` and `partner`.

1. Compare `baseline_margin_pct` and `recent_margin_pct`. Then look at the combination's rows in
   `ba_margin_lines`.
2. Cost up while revenue per unit stays flat: check the partner's cost or settlement terms.
3. Revenue per unit down while cost stays flat: check discounts or price changes on that channel.
4. A fall in volume alone does not erode margin %, so it does not fire this Alert Rule.
5. A margin that is low but steady does not fire either. Erosion is the signal, not the level.

## Thresholds are configuration

| What | Where | Default |
|---|---|---|
| Band width | `z` in the forecast SQL (view and Job) | 4.0 |
| Warm-up | `warmup` in the forecast SQL | 28 days |
| Days out of band before a regime change | `regime_k` in the forecast SQL | 3 |
| Drift detector allowance and limit | `cusum_k`, `cusum_h` in the forecast SQL (in sigma) | 0.25, 8 |
| Minimum group size per window | `min_lines`, `min_revenue` in the margin SQL | 10 lines, 1000 |
| Erosion threshold | `threshold` in `alert-rules/ba_margin_erosion.toon` | 5 points |

## Running it on your own data

Ingest a `daily_revenue` store (`ds DATE`, `revenue DOUBLE`) and a `margin_lines` store (`ds`, `product`,
`channel`, `partner`, `revenue`, `cost`). Enable the Jobs `ba_revenue_forecast` and `ba_margin_erosion`.
Then point the Datasets at the Jobs' sinks (`revenue_forecast`, `margin_erosion`) with `physicalRef`.
