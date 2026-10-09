# Mobile Money industry pack — plan (`PACK-MOBILE-MONEY-1`)

Status: ✅ SHIPPED + ARCHIVED 2026-10-09. As-built: okf `capabilities/spaces/spaces.md` §3.5.3. Model: the shipped `telco-fraud`, `telco-ra` and
`payment-fraud` Space Templates (okf `capabilities/spaces/spaces.md` §3.5).

## 1. What ships

One Space Template, `spaces/_templates/mobile-money/`, configuration only (no platform edit, no new Step Processor).

| Feed (Pipeline) | Grain | Columns |
|---|---|---|
| `wallet_txn` | one wallet-platform ledger row | `txn_id, txn_ts, txn_type, wallet_id, agent_id, partner_id, amount, fee, commission` |
| `bank_statement` | one line of the trust (float) bank account statement | `stmt_line_id, value_ts, bank_ref, direction, amount` |
| `partner_settlement` | one line of a biller / aggregator settlement file | `line_id, settle_date, partner_id, txn_ref, amount` |
| `wallets` | daily wallet-register snapshot (KYC + provisioning) | `snapshot_date, wallet_id, msisdn, kyc_tier, status, last_activity_date` |
| `core_subscribers` | daily core-network subscriber snapshot | `snapshot_date, msisdn, status` |

`txn_type` ∈ `CASH_IN, CASH_OUT, P2P, BILL_PAY, BANK_OUT, BANK_IN`. Money is `DECIMAL(18,2)`.

| # | Typology | Mechanism | Key | Rule |
|---|---|---|---|---|
| 1 | Wallet ↔ bank float | **Reconciliation** `mm_bank_float` (wallet `BANK_*` rows vs statement, `bank_ref` → `txn_id`, amount tolerance 0.01) | `txn_id` | Breaks |
| 2 | Partner settlement | **Reconciliation** `mm_partner_settlement` (wallet `BILL_PAY` rows vs settlement file) | `txn_id` | Breaks |
| 3 | Commission over/under-pay | `sql.template` `mm_commission` (paid vs `amount × rate` per type) | `agent_id` | `max(commission_variance) gt 5` |
| 4 | Fee mis-charge | `sql.template` `mm_fee` (charged vs `greatest(fee_min, amount × fee_rate)`) | `wallet_id` | `max(overcharged) gt 1` |
| 5 | Agent split transactions (structuring) | `sql.template` `mm_agent_split` (cash-ins just under the reporting limit, per agent-wallet pair) | `agent_id` | `max(max_split_count) gt 3` |
| 6 | Agent round-tripping (commission farming) | `sql.template` `mm_round_trip` (cash-in then cash-out of ≥ ratio at the SAME agent within N minutes) | `agent_id` | `max(round_trip_wallets) gt 2` |
| 7 | Dormant-wallet reactivation | `sql.template` `mm_dormant` (no activity for `dormant_days`, then outflow) | `wallet_id` | `max(outflow) gt 500` |
| 8 | KYC tier-limit breach | `sql.template` `mm_kyc_limit` (daily outflow over the tier's limit) | `wallet_id` | `max(breach) gte 1` |
| 9 | Provisioning mismatch wallet vs core | `sql.template` `mm_provisioning` (ACTIVE wallet on a missing / non-ACTIVE line) | `wallet_id` | `max(mismatch) gte 1` |
| — | Agent Risk Score | **Scoring & Lists** `risk-score` `mm_agent` over 3, 5, 6 + PENDING Alert Rule `mm_high_risk_agent` (§3.5.2) | `entity_key` | `max(score) gte 60` |

Every `sql.template` Job runs `0 3 * * *` over `$yesterday`..`$today`, keeps earlier windows (the telco-fraud
`WITH cur … UNION ALL BY NAME … retention_days` shape) and ships a zero-row seed snapshot of its sink.
Every Alert Rule is per entity (`by` = the offender alone). KPIs, a dashboard, a runbook.

## 2. Decisions (operator away — decided, recorded)

- **MM-D1 Reconciliation where both sides are records of the same event; `sql.template` where the check is a
  rule over one side.** Float and partner settlement are true two-ledger matches (Breaks with a lifecycle);
  commission, fee, structuring, round-tripping, dormancy, KYC and provisioning are rules over the ledger or a
  snapshot join, which need a per-entity offender and a window. Provisioning is NOT a Reconciliation: Recon
  compares aggregated numeric columns, not a status vocabulary.
- **MM-D2 Reconciliation sides are filtered, not separate feeds.** The Recon `filters` key selects
  `BANK_*` / `BILL_PAY` rows of the one ledger feed; `columnMap` maps the counterparty's reference column.
- **MM-D3 The agent cash-in/out reconciliation is the commission + round-trip pair, not an agent e-float
  statement match.** No standard agent-side statement exists to reconcile against; the agent's earnings and
  behaviour are what the operator can check from the ledger alone. An agent-float statement feed is a later
  slice if a customer supplies one.
- **MM-D4 Undercharged fees are reported, not alerted.** `mm_fee` emits `undercharged` beside
  `overcharged`; only overcharging harms the customer (the typology). Undercharging is revenue leakage for an
  RA pack.
- **MM-D5 KYC: a wallet missing from the register is held to the tier-1 limit** (fail closed).
- **MM-D6 The pack's requirements are declared by Offering placement, not a new template key.** It needs
  Reconciliation + Scoring & Lists + Alert Rules, i.e. Professional; `offerings/professional.toon` lists it in
  `contentPacks`. Template-level `requires` would be a platform edit (module-reorganisation rule: a new pack
  needs zero platform edits).
- **MM-D7 Two golden tests, one per add-on module.** No module has both Reconciliation and Scoring on its test
  classpath: `MobileMoneyPackGoldenTest` (inspecto-reconciliation) boots the template through `POST /spaces`,
  ingests on the production path, runs Reconciliations + Jobs + Alert Rules; `MobileMoneyRiskScoreGoldenTest`
  (inspecto-scoring) evaluates the agent Risk Score over the same committed corpus.

## 3. Corpus

`MobileMoneyCorpus` — fixed seed, one day (2026-07-01), fictional ids (`W…` wallets, `A…` agents, `P…` partners,
MSISDNs in the reserved `999` country code). Background: 150 wallets, 20 agents, far under every threshold.
Each typology plants offenders (one JUST above its threshold) and look-alikes (one exactly AT it plus realistic
benign patterns). The Risk Score plants one agent whose two signals are each AT their rule's threshold (silent
alone) but together score 60.
