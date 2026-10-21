---
type: Feature
title: "Link Analysis showcase — user guide"
description: "How an investigator works through the la-showcase demo Space."
tags: [la-showcase, user-guide]
timestamp: 2026-10-19T00:00:00Z
---

# Link Analysis showcase — user guide

The showcase is a demo Space; the screens are the standard Link Analysis studio described in the
[end user guide](../../module/la-core/user-guide-end-user.md) and the [user manual](../../module/la-core/user-guide-manual.md).
This page names what the Space holds so a walk-through lands on the planted story. Install: [architecture](architecture.md) and the [variants runbook](admin-guide-variants-runbook.md).

## Sign in

Pick a Demo User (`spaces/_templates/la-showcase/config/demo-users.toon`, `roles.toon`):

| Demo User | Role in the story |
|---|---|
| `fm.analyst` | lead analyst; authors Alert Rules |
| `fm.manager` | second approver for four-eyes expands |
| `ra.analyst` | reviewer, read-only |
| `demo.manager` | not a member of the Investigation |
| `admin` | can reveal masked values |

## A walk-through

1. A new `mule_transfers` file commits (copy a file from `data/staged` into the inbox); `la_index_build` appends it to the index.
2. As `fm.analyst`, open the saved view `mule_structuring` and seed it with `MULE-HUB-01`: the twelve smurfs appear. Run cycle detection: the `SHELL-A`..`SHELL-D` loop is the only directed cycle.
3. Expanding the hub exceeds the four-eyes fan-out threshold, so it waits for `fm.manager`. With the seed applied, one pending expand on the Investigation `mule-hub-case` is already waiting.
4. Identifiers are masked in Standard and above (the edition default, `typed`); only `admin` can reveal. In Basic they show unmasked.
5. Work happens in the Investigation `mule-hub-case`; the seed also leaves an open Draft and a second Investigation, `delivery-gap-case`, whose window reaches one day past the landed files.
6. An Alert Rule bound to the Investigation with standing detection on is evaluated by `la_detect` every five minutes; the seed runs it once so the `mule-pass-through` Alert exists, and a hit can go to a Case.

## Telecom and Geo

- Saved view `telecom_fraud_network` over the telecom link Dataset; suspect `99979100001` reaches the SIM box at degree 4.
- Geo Views `mule_account_locations_map`, `mule_ring_route_map`, `mule_cashout_till_map` draw the ring route and the cash-out converging on `TILL-06`.

## References

[features](features.md) · [Investigation pivot guide](../../module/la-core/user-guide-investigation-pivot.md)
