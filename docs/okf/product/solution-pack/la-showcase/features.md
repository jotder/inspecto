---
type: Feature
title: "Link Analysis showcase — features"
description: "A seeded Link Analysis demo Space with a planted mule ring, telecom fraud rings and a Geo scene."
tags: [la-showcase, features, presales]
timestamp: 2026-10-19T00:00:00Z
---

# Link Analysis showcase — features

**Product Map Item:** [`solution-pack.la-showcase`](../../../../../product-map/solution-pack/la-showcase.toon) · **Status:** preview · **Availability:** not billable; Space Template `la-showcase` declares `minEdition: advanced` and needs Demo Auth (`spaces/_templates/la-showcase/template.toon`).

## What it is

The Link Analysis demo as a Space Template: synthetic mule transfers with a planted ring, an eight-feed telecom corpus with one planted ring per typology, a synthetic Geo scene, five Demo Users and standing detection. Synthetic data only.

## Who benefits

Presales, trainers and evaluators of the [Link Analysis add-on](../../addon/linkAnalysis/features.md).

## Key capabilities

- `mule_transfers` Pipeline and Dataset: three landed days plus staged next-day and gap-day files.
- Planted story: 12 SMURF accounts into `MULE-HUB-01`, the `SHELL-A`..`SHELL-D` loop (the only directed cycle), `TILL-06` cash-out, `SKIMMER-01`.
- Eight 60-day telecom feeds folded by `telecom_links` into one MSISDN-to-MSISDN link Dataset, scored 0–100 by `telecom_msisdn_indicators`.
- Settings in `link-analysis.toon`: typed masking, four-eyes fan-out threshold, index ON, Drafts limits.
- Jobs `la_index_build` (on every committed batch) and `la_detect` (every 5 minutes).
- Saved views and three Geo Views over the mule ring route and cash-out.
- Five Demo Users with a role table; runtime state (Investigations, Entity Lists, a Case, a bound Alert Rule) from `tools/seed-la-showcase.mjs`.

## Related hubs

[architecture](architecture.md) · [user guide](user-guide.md) · [variants runbook](admin-guide-variants-runbook.md) · [la-core](../../module/la-core/index.md) · [la-showcase-off](../la-showcase-off/index.md)
