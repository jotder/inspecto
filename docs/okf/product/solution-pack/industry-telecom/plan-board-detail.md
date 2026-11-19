---
type: Plan
title: solution-pack.industry-telecom - board row detail
description: The narrative behind solution-pack.industry-telecom's long rows on the board (docs/BACKLOG.md), moved here verbatim so each board row stays a short summary.
tags: [solution-pack, industry-telecom, plan, backlog]
timestamp: 2026-11-19T00:00:00Z
item: solution-pack.industry-telecom
audience: internal
status: plan
verified: 2026-11-19 last-edit
code: platform/inspecto-entity-store/src/main/resources/META-INF/inspecto/industry-packs/telecom
---

# solution-pack.industry-telecom - board row detail

Each section below is one row of [the board](../../../../BACKLOG.md), headed by its id or name. The board row keeps
the row's priority, a one-line summary of what is open, its state and its tag, and stays the only list of open
work; this page keeps the narrative the row carried, verbatim as it stood on the board on 2026-11-19
(`INFO-RESTRUCTURE-1` W3). Close or narrow the board row first, then trim its section here.

## Open rows

### PACK-RERATING-1

- `PACK-RERATING-1` — **re-rating / tariff validation pack** (competitive gap C, split out of `PACK-PARTNER-SETTLEMENT-1` 2026-10-18; the partner-settlement half stays on that row): **R0 measured, R1 and R2 BUILT 2026-10-20, R3 BUILT 2026-10-21 (hub `okf/product/solution-pack/telco-rerating/plan-as-built.md`); R4…R7 open** — the `telco-rerating` Space Template of staged `sql.template` Jobs (built: tariff-table hygiene `rr_tariff_hygiene`, per-call re-rating with the single / agree / precedence / disagree / none verdicts `rr_rerate`, the `rerated_usage` contract, the explanation `rr_explain` (four counterfactual causes, the fourth a `wrong_plan` inferred per subscriber against the `subscription_history` feed; exactness times support confidence, a floor, alternatives kept) and the root-cause snapshot `rr_root_cause` with revenue at risk kept apart from over-billing, the KPIs `revenue_at_risk` and `overbilled_amount`, a synthetic golden corpus with an independent oracle and a 157-mutant clause pass (57 name the row they must change); **an unbilled preview**, D-RR10 commercial stays the operator's call). Still open: rate structure (R4), allowances (R5, demand-gated D-RR7), the re-rated-versus-billed Reconciliation, the Alert Rule by `ROOT_CAUSE_ID` and the over-billing Incident, the dashboard (R6), orphan-plan detection, and the kernel only if gate G-K trips (R7; **not tripped at R0**, measured). Decisions D-RR1…D-RR9 ANSWERED 2026-10-20 (D-RR4 changes today's net `leakage_found` sum); two deviations recorded: `tariff_version` and `subscription_history` are plain feeds, not `scd2` (R0 measured that `scd2` cannot carry past effective dates; D-RR8 revised at R3, hub `plan-as-built.md`); unmeasured: the Job over Parquet at volume, findings spread over many subscribers, a real operator's history. → `superpower/telco-packs-plan.md` §3, §4

### PACK-5G-IOT-ASSURANCE-1

- `PACK-5G-IOT-ASSURANCE-1` — **5G / IoT / cloud / digital service assurance industry pack** (competitive gap U, 2026-10-18: LATRO, Mobileum, TEOCO): a candidate industry pack (cable / IPDR, digital services) as Space Templates over the existing engines. **Planned 2026-10-18, nothing built** (`okf/product/solution-pack/industry-telecom/plan-5g-iot-assurance.md`, D-5G1…D-5G10, ANSWERED 2026-10-20 (operator, accepted the recommended answer): hold U2…U6, U0 now, U1 only with the RFP bid decision): entitlement and usage versus billing over charging and session records, IoT and cloud controls, no probes, no real-time; slices U0…U7, demand-gated (only U0, and U1 with the RFP bid, are candidates now); build when a prospect names it. → `okf/product/layer/product/brochure-competitive-matrix.md`
