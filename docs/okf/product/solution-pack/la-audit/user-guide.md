---
type: Feature
title: "Link Analysis audit showcase — user guide"
description: "How an auditor works the la-audit Space day to day: the workpaper Investigation, Entity profiles, algorithms, Dossier and Case."
tags: [la-audit, user-guide]
timestamp: 2026-10-19T00:00:00Z
---

# Link Analysis audit showcase — user guide

Screens and controls are the shared studio ones: see the [la-showcase user guide](../la-showcase/user-guide.md) and the [Link Analysis user guide](../../module/la-core/user-guide-link-analysis.md). The scripted demo with expected values is the [runbook](admin-guide-showcase-runbook.md).

## Which edition

The Space has two tiers.

- **Basic tier: every edition, Basic included.** The data, the link Jobs, the three saved views and the stories all
  work on Basic. Nobody signs in, so masking is off and you read the real ids (`V-117`, `E-31`) on the canvas.
- **Advanced tier: a build where people sign in.** You get the users below, typed masking of parties with the admin's
  Reveal, Investigations, four-eyes approval, Drafts, the Dossier, the committee portal, the bound Alert Rule and the
  seeded Case, so this section and the walk-through after it need it.

## Who signs in

| User | Works as |
|---|---|
| `audit.lead` | works Investigations, runs Graph Runs, builds the index, authors Alert Rules |
| `audit.manager` | approves held expands, operates Jobs |
| `audit.committee` | read-only reviewer; ids are masked |
| `audit.admin` | Reveal, Settings, embed links |

## A working day

1. **Open the workpaper.** Investigations > `vendor-v117-workpaper` (bound to `ap_links_dataset`). Seed first: an all-nodes canvas is unreadable.
2. **Is this vendor real?** On the `ap_vendor_identity` view seed V-117 and expand; run **Connect seeds** between V-117 and E-31. Switch to `ap_approval_graph` to see who approved.
3. **Entity profile.** Open a vendor's profile from the node panel: reference rows from `vendor_master_dataset`, invoice links and shared links.
4. **Is the control chain sound?** On `ap_approval_graph` run k-core, articulation points and bridges (client first, then **Run on server**). On `gl_intercompany` find cycles and maximum flow.
5. **Four-eyes and Drafts.** An unbounded expand (or one above 2000 neighbours) is held until `audit.manager` approves it; try what-ifs in a Draft.
6. **Dossier.** Build the Dossier of the workpaper and Verify it; it is the audit workpaper.
7. **Hand-off.** The seed creates a Case; the committee reads the Investigation, or an embedded, masked, read-only view.
