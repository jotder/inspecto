---
type: Feature
title: "Telecom re-rating (tariff validation) — features"
description: "Independent re-rating of rated usage against an effective-dated tariff, with tariff-table hygiene, per-call ambiguity verdicts, counterfactual causes with confidence and root causes."
tags: [telco-rerating, features, presales]
timestamp: 2026-10-20T00:00:00Z
---

# Telecom re-rating (tariff validation) — features

**Product Map Item:** [`solution-pack.telco-rerating`](../../../../../product-map/solution-pack/telco-rerating.toon) · **Status:** preview (slices R0 to R2: tariff versioning, ambiguity, causes and root causes) · **Availability:** Standard and up (`minEdition: standard`; `offerings/standard.toon` `contentPacks`). Commercial terms are not set: this is an unbilled preview.

## What it is

A Space Template (`telco-rerating`) that re-rates one day of the rated usage a billing system produced against the operator's own tariff table, independently of that billing system, and reports where the two disagree. It prices nothing, writes to no billing system and raises no Alert yet. It also says what a mismatch is consistent with and groups the mismatches into root causes.

## Who benefits

Revenue-assurance teams at operators and MVNOs that want to know whether what was billed matches the tariff as written.

## Key capabilities

- Two Pipelines: the rated usage feed and the tariff as one row per version, each with its own effective dates and an optional `PRIORITY` — [etl](../../module/etl/index.md)
- `rr_tariff_hygiene`: judges the tariff table itself, before any usage: rows in force at once with the same rate or different rates, gaps, negative or missing rates, intervals that end before they start
- `rr_rerate`: re-rates each call at its start and gives one verdict per call: `single`, `agree`, `precedence`, `disagree` or `none`; an expected charge, or an expected range when an ambiguous tariff allows several amounts
- A charge an ambiguous tariff could justify is explained and goes to data quality, never to leakage
- `rr_explain`: for each leakage or over-billing, the causes a counterfactual re-rating is consistent with (a stale version, a lower-priority row, a version billed early), a confidence (exactness times support), and every other fitting cause kept; a cause that is not supported well enough is `unexplained`
- `rr_root_cause`: the findings grouped into root causes with a stable id, calls, first and last day, a run rate per day and revenue at risk
- Under-charged and over-billed amounts are separate columns and never net against each other; over-billing is its own class and never counts as revenue at risk (KPIs `revenue_at_risk` and `overbilled_amount`)
- Zero-row schema seeds, a runbook skeleton and a seeded synthetic corpus with planted defects and look-alikes

## Not yet

Billing increments, minimum charges, zones, time bands, tax, allowances; plan inference (and its cause); the causes that need them; Alert Rules and the over-billing Incident; the re-rated-versus-billed Reconciliation; the dashboard. Orphan-plan detection is also not built.

## Related hubs

[architecture](architecture.md) · [user guide](user-guide.md) · [admin guide](admin-guide.md)
