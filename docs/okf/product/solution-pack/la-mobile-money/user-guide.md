---
type: Feature
title: "Link Analysis mobile money — user guide"
description: "How a mobile-money investigator works the la-mobile-money Space day to day: Investigations, Measures, four-eyes, Entity profiles, Alerts and Cases."
tags: [la-mobile-money, user-guide]
timestamp: 2026-10-19T00:00:00Z
---

# Link Analysis mobile money — user guide

Screens and controls are the shared studio ones: see the [la-showcase user guide](../la-showcase/user-guide.md) and the [Link Analysis user guide](../../module/la-core/user-guide-link-analysis.md). The scripted demo with expected values is the [runbook](admin-guide-showcase-runbook.md).

## Who signs in

| User | Works as |
|---|---|
| `mm.analyst` | lead: runs algorithms, builds the index, authors the Alert Rule; no Reveal, no approval |
| `mm.manager` | second person: approves expansions, presses Run on Jobs, owns the Case |
| `mm.reviewer` | read-only member (regulator liaison) |
| `admin` | Reveal, Settings, embed links; opens the Case |

## A working day

1. **Check data trust.** Open the Investigation `mm-structuring-case`; read the footer counts and the data-trust strip.
2. **Seed and expand.** Seed `HUB-01` and expand; wallets show as `masked:` tokens. An expand above 100 neighbours is held until `mm.manager` approves it in Approvals.
3. **Measure.** Value Measures: `structuring` (4 750 to 5 000), `passThrough`, `cashOutConcentration`.
4. **Follow the money.** Shortest path and Maximum flow from the hub to the cash-out agent; Find cycles on a `LOOP-1` neighbourhood. Client run first, then **Run on server**.
5. **Rank with care.** Suspicion score and its factor breakdown; the biller shows high connectivity is not guilt.
6. **Entity profile.** Reference rows come from the KYC Dataset `mm_kyc_dataset`; the `mm_device_farm` view shows the shared handset.
7. **Alert to Case.** Alerts > `mm-structuring` (aggregate only) > Incident > the Case owned by `mm.manager`. Build and Verify the Dossier; share a masked read-only embed with the regulator.
