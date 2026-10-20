---
type: Feature
title: "Telecom bypass (SIM-box) — features"
description: "Twelve CDR signals per SIM per day and a box_like flag, with per-signal degradation."
tags: [telco-bypass, features, presales]
timestamp: 2026-10-20T00:00:00Z
---

# Telecom bypass (SIM-box) — features

**Product Map Item:** [`solution-pack.telco-bypass`](../../../../../product-map/solution-pack/telco-bypass.toon) · **Status:** preview (slice B1: signals only) · **Availability:** Standard and up (`minEdition: standard`; `offerings/standard.toon` `contentPacks`). Commercial terms are not set.

## What it is

A Space Template (`telco-bypass`) over a voice CDR feed that computes, once a day, twelve explainable signals per SIM and a `box_like` flag. It scores nothing and raises no Alert yet.

## Who benefits

Fraud and revenue-assurance teams at operators and MVNOs that see their own voice CDRs.

## Key capabilities

- Four Pipelines: the voice CDR feed and three small Reference feeds (cell sites, own number ranges, the subscriber profile) — [etl](../../module/etl/index.md)
- One daily `sql.template` Job writing one row per SIM per day: B-number diversity, call shape, answer rate, inbound share, service mix, cells and implied speed, sequential dialling, hour profile, cadence, overlapping calls, own-network targets, tenure and activation batch
- `box_like`: five strict, configurable clauses (inbound share, B-number diversity, short calls, voice only, static cell)
- Optional columns and References degrade per signal: the signal is omitted and named in `missing`; the Job never fails on them
- Zero-row schema seeds, a runbook skeleton and a seeded synthetic corpus with planted boxes and look-alikes

## Not yet

Risk Score, Entity Lists (watch, allow, block), Alert Rules, the box-level Incident and Case, an Anomaly Model factor, device and cluster signals, interconnect-side signals. A CDR pattern is evidence of consistency with a box, never proof; no probe, signalling or test-call evidence is used.

## Related hubs

[architecture](architecture.md) · [user guide](user-guide.md) · [admin guide](admin-guide.md)
