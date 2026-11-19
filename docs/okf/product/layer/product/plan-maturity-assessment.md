---
type: Plan
title: Product maturity assessment — every client-facing item
description: Scored maturity (feature breadth, build status, quality evidence, enterprise grade, RFP defensibility → L1–L5) of every add-on, solution pack, edition and platform capability, against market competitors, with RFP stances and the actions that raise each level.
tags: [maturity, assessment, rfp, competitive, enterprise, product]
timestamp: 2026-10-20T00:00:00Z
item: layer.product
audience: internal
status: plan
verified: 2026-11-19 last-edit
---

# Product maturity assessment — every client-facing item

> **As of 2026-10-20** (origin/master `56ba62d23`). Audience: product owner, presales, solution architects, leadership.
> Internal — it names our gaps frankly; brochures stay the external voice. Re-assess at each release or quarter:
> a score is a photograph, and several of the docs it read had already drifted from the code (§4.4).
>
> ⚠ **Since this photograph:** `OFFERING-DECLARATION-DRIFT-1`, cited below as open, was **closed 2026-10-20**
> (`78fc6e583`: `business-assurance` moved to Standard with `minEdition: standard`, the LA showcase templates
> listed in `offerings/preview.toon`, `la-daily-feed` kept in Basic by decision), and since 2026-11-08 the LA
> showcases have Basic tiers listed in `offerings/basic.toon`; since 2026-11-17 `la-daily-feed` is a Standard content pack
> (`DATA-TIERS-1` DT-18) and the ASN.1 decoder (`telecom-asn1`, `telecomIndustryPack`) is Advanced only (DT-12). Findings below that cite its D1 or D2 describe the
> state before those changes.
>
> ⚠ **Real-time, since this photograph (noted 2026-11-19):** every finding below that says "batch only", "no real-time"
> or "`REALTIME-DECISIONING-1` is a P3 placeholder" predates the **Real-time Detection add-on** (Standard, `REALTIME-DECISIONING-1`
> PA-9d, 2026-11-12): windowed Alert Rules and `sequence` / `absence` Patterns evaluated on each commit of a stream
> Pipeline, p95 2.4 s at 5,000 events/s in the harness and 1.9 s on a real broker. That is near-real-time
> **detection**. What those findings call a knock-out is still true for **inline** decisions — card authorisation,
> pre-call blocking, in-transaction scoring — which need an allow / block answer before a record is accepted and are
> unbuilt (`REALTIME-INLINE-DECISION-1`). The packs themselves still run as batch Jobs; none has been moved onto a
> stream Pipeline. The scores were not re-assessed.

## Parts

This page is split by topic into 8 parts; each part lists the sections it holds.

1. **[Verdict, scoring and scorecard](plan-maturity-assessment-verdict.md)** — 1. Verdict · 2. How it was scored · 3. Scorecard · 4. Cross-cutting findings · 5. What raises the levels — ranked by leverage
2. **[Per-item assessments — platform base](plan-maturity-assessment-platform-base.md)** — 6. Per-item assessments
3. **[Per-item assessments — add-ons for operations, investigation and AI](plan-maturity-assessment-addons-operations.md)**
4. **[Per-item assessments — add-ons for risk, assurance, data and Link Analysis](plan-maturity-assessment-addons-risk.md)**
5. **[Per-item assessments — telecom solution packs](plan-maturity-assessment-packs-telecom.md)**
6. **[Per-item assessments — fintech and commerce solution packs](plan-maturity-assessment-packs-fintech.md)**
7. **[Per-item assessments — Link Analysis packs, Inspecto LA and the editions](plan-maturity-assessment-la-editions.md)**
8. **[Per-item assessments — Preview edition, and references](plan-maturity-assessment-preview-references.md)** — 7. References
