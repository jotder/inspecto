---
type: Feature
title: "Telecom re-rating (tariff validation) — features"
description: "Independent re-rating of rated usage against an effective-dated tariff, with tariff-table hygiene and per-call ambiguity verdicts."
tags: [telco-rerating, features, presales]
timestamp: 2026-10-20T00:00:00Z
---

# Telecom re-rating (tariff validation) — features

**Product Map Item:** [`solution-pack.telco-rerating`](../../../../../product-map/solution-pack/telco-rerating.toon) · **Status:** preview (slices R0 and R1: tariff versioning and ambiguity only) · **Availability:** Standard and up (`minEdition: standard`; `offerings/standard.toon` `contentPacks`). Commercial terms are not set: this is an unbilled preview.

## What it is

A Space Template (`telco-rerating`) that re-rates one day of the rated usage a billing system produced against the operator's own tariff table, independently of that billing system, and reports where the two disagree. It prices nothing, writes to no billing system and raises no Alert yet.

## Who benefits

Revenue-assurance teams at operators and MVNOs that want to know whether what was billed matches the tariff as written.

## Key capabilities

- Two Pipelines: the rated usage feed and the tariff as one row per version, each with its own effective dates and an optional `PRIORITY` — [etl](../../module/etl/index.md)
- `rr_tariff_hygiene`: judges the tariff table itself, before any usage: rows in force at once with the same rate or different rates, gaps, negative or missing rates, intervals that end before they start
- `rr_rerate`: re-rates each call at its start and gives one verdict per call: `single`, `agree`, `precedence`, `disagree` or `none`; an expected charge, or an expected range when an ambiguous tariff allows several amounts
- A charge an ambiguous tariff could justify is explained and goes to data quality, never to leakage
- Under-charged and over-billed amounts are separate columns and never net against each other
- Zero-row schema seeds, a runbook skeleton and a seeded synthetic corpus with planted defects and look-alikes

## Not yet

Billing increments, minimum charges, zones, time bands, tax, allowances; plan inference; a cause for each mismatch and root-cause grouping; a revenue-at-risk Measure and Alert Rules; the re-rated-versus-billed Reconciliation. Orphan-plan detection is also not built.

## Related hubs

[architecture](architecture.md) · [user guide](user-guide.md) · [admin guide](admin-guide.md)
