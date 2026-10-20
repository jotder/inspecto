---
type: Feature
title: "Telecom bypass (SIM-box) — features"
description: "Twelve CDR signals per SIM per day, an explainable Risk Score, an allow list with expiry, persistence, agreement, shadow mode and a SIM Alert, with per-signal degradation."
tags: [telco-bypass, features, presales]
timestamp: 2026-10-20T00:00:00Z
---

# Telecom bypass (SIM-box) — features

**Product Map Item:** [`solution-pack.telco-bypass`](../../../../../product-map/solution-pack/telco-bypass.toon) · **Status:** preview (slices B1 and B2: signals, score, lists and the SIM Alert) · **Availability:** Standard and up (`minEdition: standard`; `offerings/standard.toon` `contentPacks`). Commercial terms are not set.

## What it is

A Space Template (`telco-bypass`) over a voice CDR feed that computes, once a day, twelve explainable signals per SIM, scores each SIM 0 to 100 with every contribution stored beside the score, decides whether a high score is worth a page, and raises a CRITICAL Alert Rule per SIM.

## Who benefits

Fraud and revenue-assurance teams at operators and MVNOs that see their own voice CDRs.

## Key capabilities

- Four Pipelines: the voice CDR feed and three small Reference feeds (cell sites, own number ranges, the subscriber profile) — [etl](../../module/etl/index.md)
- A nightly chain of four Jobs: the SIM-day signals (`tb_features`), the score inputs (`tb_factors`), the Risk Score (`tb_risk_score`) and the findings (`tb_findings`)
- The `tb_sim` Risk Score: eight signals add, three protective factors subtract (receives calls, uses SMS or data, moves across cells), 0 to 100, recomputable from the stored factors — [scoring](../../module/scoring/index.md)
- Agreement: a high score alerts only when at least five of six primary signals hold, so one inflated factor cannot page anyone
- Persistence: high on two of the last three nights before an Alert (one fewer for a watched SIM)
- An allow list (`tb_allow`) for verified PBX, dialler and call-centre lines with an expiry that un-suppresses the SIM, and a lower-severity Alert when an allowed line changes shape, so an allow entry cannot become a hiding place — [entity-list](../../module/entity-list/index.md)
- A shadow switch: scores and findings are written, nothing is raised, so precision is measured before anyone is paged
- Optional columns and References degrade per signal: the signal is omitted and named in `missing`; the Jobs never fail on them
- Zero-row schema seeds, a runbook, and a seeded synthetic corpus with planted boxes and look-alikes, each held to its planted outcome and reason by a golden test

## Not yet

The cluster stage (SIMs per IMEI, cell and activation batch), the box-level Incident and Case, an Anomaly Model factor, the device signals, the barring Action Request, the quarterly tuning report, interconnect-side signals, and the `tb_block` and `tb_ranges` lists (documented, not yet read). A CDR pattern is evidence of consistency with a box, never proof; no probe, signalling or test-call evidence is used.

## Related hubs

[architecture](architecture.md) · [user guide](user-guide.md) · [admin guide](admin-guide.md)
