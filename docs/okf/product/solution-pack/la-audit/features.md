---
type: Feature
title: "Link Analysis audit showcase — features"
description: "A seeded finance-and-audit Space that shows procure-to-pay and journal risk as a graph, ending in a verifiable Dossier."
tags: [la-audit, features, presales]
timestamp: 2026-10-19T00:00:00Z
---

# Link Analysis audit showcase — features

**Product Map Item:** [`solution-pack.la-audit`](../../../../../product-map/solution-pack/la-audit.toon) · **Status:** preview · **Availability:** not billable; Space Template `la-audit`, needs an Advanced or Preview build with Demo Auth.

## What it is

A demonstration Space on synthetic procure-to-pay and journal data (150 vendors, 60 employees, about 1 800 invoices over 12 months, 40 intercompany journals). Seven planted stories are pinned by `LaAuditGoldenTest`.

## Who benefits

Internal audit, finance controls and presales teams.

## Key capabilities

- Fictitious vendor: V-117 shares a bank account and an address with employee E-31; every invoice is approved by E-31's manager.
- Shell cluster on one bank account, with a sixth vendor proposed by Link prediction.
- Threshold splitting: 30 invoices 1 to 3 % under the 10 000 approval limit.
- Single point of control found by k-core, articulation points and bridges on the approval graph.
- Intercompany round trip: the only cycle in the journal graph, with maximum flow.
- Duplicate payments (13 pairs) as their own Dataset.
- Benign look-alikes (a busy utilities vendor, the CFO) that lead centrality and are not findings.
- Typed masking, four-eyes expand above 30, Drafts, and a Dossier that fails Verify if one byte changes.
- Read-only embed into a committee portal page.

Shared studio capabilities: [la-showcase features](../la-showcase/features.md) · [Link Analysis features](../../addon/linkAnalysis/features.md).

## Related hubs

[la-showcase](../la-showcase/index.md) · [la-core](../../module/la-core/index.md) · [linkAnalysis](../../addon/linkAnalysis/index.md)

More in this hub: [architecture](architecture.md) · [user-guide](user-guide.md) · [runbook](admin-guide-showcase-runbook.md) · [brochure](brochure.md).
