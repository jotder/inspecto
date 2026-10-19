---
type: Plan
title: MaxMind geo-country resolver — plan
description: Requirement of record for the geo-country provider - what was required, built, left and refused.
resource: providers/inspecto-geo-country/
tags: [module, geo-country, geoip, plan]
timestamp: 2026-10-19T00:00:00Z
---

# MaxMind geo-country resolver — plan

## Required

Audit and sign-in rows carry the country of the client IP, so an alert can fire on a new country per
subject (T5). Origin: the notification work `D8-SES-SNS-1`, decision D12 in
[ses-sns-adapter-design](../../../../archived-documents/plans-archive/ses-sns-adapter-design.md) §6.

## Built

| When | What |
|---|---|
| 2026-09-28 | D12 decided by the operator: operator-supplied database, country only. The `GeoCountryResolver` SPI and `AuditTrail` stamping landed (integration `integ5`). |
| 2026-09-29 | MaxMind binding `MaxMindGeoCountryResolver` on `com.maxmind.db:maxmind-db:4.2.0` (operator-approved fetch). This closed `D8-SES-SNS-1` (`docs/BACKLOG.md`, the 47 → 46 entry). |
| 2026-10 (MODULE-REORG-1) | Moved to its own provider jar `inspecto-geo-country`, staged from Standard (`offerings/standard.toon`). |

As-built: [spec](spec.md), [architecture](architecture.md), [admin guide](admin-guide.md).

## Left open

Nothing on this module. The live SES checks from the same origin row stay P3 `SES-SNS-LIVE-CHECKS-1`
in `docs/BACKLOG.md`.

## Refused (by decision)

* **Bundling or auto-downloading a GeoIP database:** licence duties stay with the operator (D12).
* **City or finer granularity:** the `Geo` record has no city field on purpose.
* **Network lookup services:** the resolver must stay in-process and offline.
