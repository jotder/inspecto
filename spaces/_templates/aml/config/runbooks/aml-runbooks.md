# AML runbooks

One runbook per control in this Space Template. The four `aml_<typology>` Alert Rules are per account: each raises one
Alert and one Incident per offender (`by` = the account), with the Measure taken as the `max` over every retained
window. Thresholds are configuration in two places: the Job's parameters (`reporting_limit`, `near_limit_band`,
`lookback_days`, `small_transfer_max`, `high_risk_countries`) and the Alert Rule's `threshold` (`gt`: a value exactly AT
it stays silent).

**Running the windows.** Each typology is a `sql.template` Job (`config/jobs/aml_<typology>_job.toon`) that runs at 03:00
over the previous day. Structuring, smurfing and watch-list traffic also read `lookback_days` (6) days before the
window, so a pattern spread over a week is one finding. Pass `window_start` / `window_end` on a run to check another day.

**Feeds.** Drop the day's files into `data/inbox/<feed>`: `aml_txn` (the account ledger: `CASH_DEPOSIT`,
`CASH_WITHDRAWAL`, `TRANSFER`, `WIRE`; `account_id` pays `counterparty_id`) and `aml_parties` (the party register
snapshot). Timestamps are naive in one agreed timezone. The shipped samples are synthetic; country codes XA-XF are
placeholders - set `high_risk_countries` to your own watch list of jurisdictions.

## aml_structuring - repeated deposits just under the limit
Counts cash deposits in `[limit x (1 - band), limit)` (default 9,000 to 9,999.99) per account over the look-back.
Alert above 4. Check whether the deposits are spread over days or branches, who the account then pays, and whether the
customer's profile explains cash. A deposit AT the limit is a reportable cash transaction, not structuring.

## aml_threshold - a day's cash over the reporting limit
Sums one account's cash deposits per day (single or split); alert above the limit (default 10,000.00). File the
currency transaction report your regulator requires; a split that adds up is the aggregation case.

## aml_fan_in - smurfing
Distinct payers sending transfers of at most `small_transfer_max` (1,000) into one account over the look-back; alert
above 9 payers. Open the `aml_account_transfers` Link Analysis view on the account and run the Inbound collector
pattern pack; the same test over the WHOLE Dataset is the structuring value Measure (below).

## aml_high_risk_traffic - watch-list traffic
Cross-border `WIRE` rows whose `country` is in `high_risk_countries` (XA,XB), summed per sending account over the
look-back; alert above 5,000. This is a jurisdiction watch list held in the Job's parameter. Names and identifiers are
screened against Entity Lists by `aml_screening` (below).

## aml_account - the account Risk Score (Scoring & Lists)
Structuring (x10, cap 50) + smurfing payers (x4, cap 40) + high-risk wires (x10, cap 30), high at 60. Two signals each
silent in its own rule can together reach 60 - the reason the score exists. Its Alert Rule ships PENDING
(`config/pending/alert-rules/aml_high_risk_account.toon`) and is created after the score's first `aml_risk_score` run.

## aml_screening - party screening (Screening)
Entity Lists cannot ship in a template. Create two lists (`aml_sanctions`, `aml_pep`; Entity Type with the default
normaliser), add the entries your compliance team supplies, then set `enabled: true` in
`config/jobs/aml_screening_job.toon`. It screens `aml_parties` (name and national id) and raises a Screening Hit per new
match for review; it fails, never partially screens, past `maxRows`.

## Link Analysis starters and what is NOT shipped
`aml_account_transfers` (view over `aml_txn`) and four money pattern packs ship. An Investigation Template and the
structuring / valueWeightedLinks value-Measure Alert Rule need a live Investigation, which is runtime state a template
cannot carry: create an Investigation over `aml_txn` (source `account_id`, target `counterparty_id`, value `amount`,
time `txn_ts`) and bind a `structuring` Alert Rule (min 9,000, max 10,000) on it.

## Reporting
`config/regulatory-report-templates/aml-str.toon` is an ILLUSTRATIVE suspicious transaction report shape for the
Regulatory Reporting add-on; it follows no regulator's schema. Replace its fields and drop directory with your
regulator's before any submission.
