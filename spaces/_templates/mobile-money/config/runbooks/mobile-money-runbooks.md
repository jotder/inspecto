# Mobile money runbooks

One runbook per control in this Space Template. The seven `mm_<typology>` Alert Rules are per entity: each
raises one Alert and one Incident per offender (`by` = the agent or wallet), with the Measure taken as the `max`
over every retained window, so an offender seen on several days is one Alert. A key heals only when all its
evidence ages out of retention (`retention_days`, default 30); that resolves its Alert but never its Incident. A
person records the Disposition. `stormCap` is 500 per rule.

**Running the windows.** Each typology is a `sql.template` Job (`config/jobs/mm_<typology>_job.toon`) that runs
at 03:00 over the previous day (`$yesterday` to `$today`). Pass `window_start` / `window_end` on a run to check
another day. A run replaces its own window's rows and keeps every other retained window.

**Thresholds are configuration** in two places: the Job's parameters (rates, limits, minutes, days) and the
Alert Rule's `threshold` (`gt`: a value exactly AT it stays silent). Both Reconciliations carry their tolerance
(`0.01` absolute) in `config/registry/reconciliations/`.

**Feeds.** Drop the day's files into `data/inbox/<feed>`: `wallet_txn` (the wallet-platform ledger),
`bank_statement` (the trust-account statement), `partner_settlement` (biller / aggregator settlement files),
`wallets` (the daily wallet-register snapshot) and `core_subscribers` (the daily core-network snapshot). Every
feed must use one agreed timezone; timestamps are naive.

## mm_bank_float — wallet vs bank float (Reconciliation)

Matches every `BANK_OUT` / `BANK_IN` ledger row to the statement line(s) whose `bank_ref` carries its `txn_id`;
several lines may settle one transfer (`one_to_many`).
- `missing_right` — the wallet moved money the bank never saw: a failed or stuck transfer, or a ledger entry
  with no real transfer behind it. Ask the bank for the transfer trace; if none, reverse the ledger entry.
- `missing_left` — money moved on the trust account with no wallet transaction: an unmatched credit (suspense)
  or a debit nobody authorised. Escalate a debit at once.
- `value_break` — the amounts differ by more than the tolerance: partial settlement or a keying error.
The float is safe only when every Break is resolved: the trust account must cover all wallet balances.

## mm_partner_settlement — wallet vs partner settlement (Reconciliation)

Matches every `BILL_PAY` ledger row to the partner's settlement lines (`txn_ref`). A payment settled on a later
day is matched by reference, not by date.
- `missing_right` — the customer paid but the partner did not credit it: chase the partner before the
  customer's bill falls overdue.
- `missing_left` — the partner settles a payment the platform never took: an over-claim; dispute it.
- `value_break` — the partner credited a different amount.

## mm_commission — commission over / under-pay

Expected commission = `amount x cash_in_rate` (CASH_IN) or `amount x cash_out_rate` (CASH_OUT), rounded to the
cent per transaction. Alerts when `|paid - expected|` for the agent's day exceeds the threshold (5 USD); the row's
`direction` says OVERPAID or UNDERPAID. Overpay: check the commission plan the platform applied (a wrong tier or a
duplicated payout) and claw back. Underpay: a dealer-relations risk; correct it before the agent raises it. If the
operator runs tiered or campaign rates, these two flat rates will misfire: replace them before relying on the rule.

## mm_fee — fee mis-charge

Expected fee on CASH_OUT and P2P = `greatest(fee_min, amount x fee_rate)`. Alerts on the customer's total
overcharge above the threshold (1 USD). Undercharged fees are reported in `undercharged` but never alerted (a
revenue question, not customer harm). Refund the customer and find the fee-table version the platform used.

## mm_agent_split — agent split transactions

Counts cash-ins in `[reporting_limit x (1 - near_limit_band), reporting_limit)` per agent and wallet; alerts when
the agent's busiest wallet takes more than 3 in a day (structuring to stay under the reporting limit). Many
near-limit cash-ins spread over different wallets (payroll, a merchant) do not alert. Review the agent's CCTV / KYC
records for the wallet and file a suspicious-transaction report when warranted. Set `reporting_limit` to the
regulator's threshold.

## mm_round_trip — agent round-tripping

Counts wallets whose cash-in was cashed out again at the SAME agent within `round_trip_minutes` for at least
`round_trip_ratio` of the amount: commission farming (the agent earns both commissions on money that never moved).
A cash-out at another agent or hours later does not count. Claw back the commissions and review the agent.

## mm_dormant — dormant-wallet reactivation

A wallet whose `last_activity_date` in the register is more than `dormant_days` before the window, then moves more
than the threshold out: account-takeover or mule activation. Money IN alone does not alert. Contact the customer on a
verified channel before releasing further outflow.

## mm_kyc_limit — KYC tier-limit breach

Daily outflow (CASH_OUT, P2P, BILL_PAY, BANK_OUT) above the wallet's tier limit (`tier1/2/3_daily_limit`). A wallet
missing from the register is held to the tier-1 limit (fail closed). A breach means the platform's limit
enforcement failed: raise it with the platform owner and restrict the wallet until KYC is upgraded.

## mm_provisioning — provisioning mismatch wallet vs core

An ACTIVE wallet whose line is missing, SUSPENDED or TERMINATED in the core network snapshot. Line numbers are
compared digits only (`+999 77…` and `99977…` match). A terminated line recycled to a new subscriber gives the new
holder the old wallet: suspend the wallet at once and re-verify the owner. A closed or suspended wallet on a dead
line is consistent and silent.

## mm_agent — agent Risk Score

The `mm_risk_score` Job (`risk.score`, 04:30 daily) scores every agent: 10 per near-limit cash-in into one wallet
(cap 50), 15 per round-trip wallet (cap 60) and 1 per USD of commission variance (cap 30). Two signals that each
sit at their own rule's threshold add up: an agent at 60 or more is high risk. The `mm_high_risk_agent` Alert Rule
is created by the score's first run (`config/pending/alert-rules/`), keyed by model and agent. Weights and caps are
configuration in `config/registry/risk-scores/mm_agent.toon`.
