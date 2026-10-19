---
type: Feature
title: "Telecom revenue assurance — user guide"
description: "Working day to day in a Space created from the telco-ra Space Template."
tags: [telco-ra, user-guide]
timestamp: 2026-10-19T00:00:00Z
---

# Telecom revenue assurance — user guide

How a revenue-assurance analyst works in a Space created from the `telco-ra` Space Template. Design: [architecture](architecture.md). Install: [admin guide](admin-guide.md).

## The data

Eight Pipelines ingest the feeds dropped into `data/inbox/<feed>`: `switch_xdr`, `mediated_xdr`, `rated_usage`, `billed_invoice`, `tariff`, `balance_ledger`, `ic_rates`, `ic_statement`.

## The daily cycle

1. The Reconciliations run: `ra_xdr_completeness` (switch vs mediated vs rated) and `ra_rated_vs_billed`. A break opens an Incident.
2. `ra_xdr_lost_job` lists xDRs lost between stages in `ra_xdr_lost`.
3. The controls run: re-rating, balance roll-forward and interconnect settlement.
4. Leakage findings collect in `ra_leakage`; records a control could not judge collect in `ra_data_quality`.

## Alerts and Incidents

| Alert Rule | What it means | What to do |
|---|---|---|
| `ra_leakage_found` (CRITICAL) | a control found leakage; one Alert per control | read the rows in `ra_leakage` for the entities |
| `ra_data_quality` | a control could not judge some records | fix the data; the next run re-judges them |

Work Incidents in the Incidents screens ([Incidents user guide](../../addon/incidents/user-guide-incidents.md)). Setting an Incident's impact and Disposition feeds the impact ledger, which `ra_recovery_job` reads into `ra_recovery`.

## Dashboard

`ra_overview` shows leakage found, confirmed and recovered, leakage items, leakage by control, lost xDRs and data-quality items. The completeness tile fills once an administrator enables the completeness Job.

## Runbooks

Each control has a section in `config/runbooks/telco-ra-runbooks.md`.
