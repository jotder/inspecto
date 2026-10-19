---
type: Feature
title: "Link Analysis claims showcase — features"
description: "A seeded insurance SIU Space that finds a staged-accident ring through shared phones, addresses and providers."
tags: [la-claims, features, presales]
timestamp: 2026-10-19T00:00:00Z
---

# Link Analysis claims showcase — features

**Product Map Item:** [`solution-pack.la-claims`](../../../../../product-map/solution-pack/la-claims.toon) · **Status:** preview · **Availability:** not billable; Space Template `la-claims`, needs an Advanced or Preview build with Demo Auth.

## What it is

A demonstration Space on synthetic claims data (18 months, about 380 claimants, 25 providers, 520 claims). The graph is built from shared attributes, not money movement. Planted stories are pinned by `LaClaimsGoldenTest`.

## Who benefits

Insurance special-investigation units and presales teams.

## Key capabilities

- Staged-accident ring: 12 claimants sharing phones and addresses, one garage, clinic and lawyer, found as one community (Louvain, Label propagation) with a largest clique of 7.
- Repeat passenger on six claims with six drivers.
- Geo cluster: 11 night accidents near one junction, none with a police report, on saved Geo Views.
- Inflated clinic surfaced by HITS, PageRank and the provider money view.
- Early-claim policies through one broker (`claims_policy_timing_dataset`).
- Identity resolution of two records that are one person.
- Benign look-alikes (a family sharing a home, a busy GP clinic) that do not join the ring.
- Typed masking with a masked Party Entity Type, four-eyes, Drafts, a Dossier with untrusted external references, and a partner embed.

Shared studio capabilities: [la-showcase features](../la-showcase/features.md) · [Link Analysis features](../../addon/linkAnalysis/features.md).

## Related hubs

[la-showcase](../la-showcase/index.md) · [la-core](../../module/la-core/index.md) · [linkAnalysis](../../addon/linkAnalysis/index.md)

More in this hub: [architecture](architecture.md) · [user-guide](user-guide.md) · [runbook](admin-guide-showcase-runbook.md) · [brochure](brochure.md).
