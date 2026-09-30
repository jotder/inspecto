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
5. Many days firing together usually means the pattern has shifted (new trend or new weekly shape). Review
   the band: `z` in the forecast SQL, or the smoothing constants `a`, `b`, `g`.

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
| Erosion threshold | `threshold` in `alert-rules/ba_margin_erosion.toon` | 5 points |

## Running it on your own data

Ingest a `daily_revenue` store (`ds DATE`, `revenue DOUBLE`) and a `margin_lines` store (`ds`, `product`,
`channel`, `partner`, `revenue`, `cost`). Enable the Jobs `ba_revenue_forecast` and `ba_margin_erosion`.
Then point the Datasets at the Jobs' sinks (`revenue_forecast`, `margin_erosion`) with `physicalRef`.
