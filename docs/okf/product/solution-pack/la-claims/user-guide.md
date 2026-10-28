---
type: Feature
title: "Link Analysis claims showcase — user guide"
description: "How an SIU investigator works the la-claims Space day to day: Investigations, communities, Geo, identity resolution, Entity profiles and the Case."
tags: [la-claims, user-guide]
timestamp: 2026-10-19T00:00:00Z
---

# Link Analysis claims showcase — user guide

Screens and controls are the shared studio ones: see the [la-showcase user guide](../la-showcase/user-guide.md) and the [Link Analysis user guide](../../module/la-core/user-guide.md). The scripted demo with expected values is the [runbook](admin-guide-showcase-runbook.md).

## Who signs in

| User | Works as |
|---|---|
| `siu.analyst` | lead: builds the Investigation, authors Alert Rules, runs Graph Runs |
| `siu.manager` | second approver; presses Run on Jobs |
| `partner.reviewer` | read-only member |
| `admin` | Reveal, Settings, embed links |

## A working day

1. **Start small.** Open `claims_shared_identity` (or `claims_network`) and seed a claimant such as `CL-0134`; expand. Always seed first.
2. **Find the ring.** Toolbox > Communities (Louvain), then Cliques and Triangle count. Client run first, then **Run on server**.
3. **Look at providers.** HITS and PageRank on the working set; open `claims_provider_money`.
4. **Where did it happen?** Geo page > load `claims_junction_cluster_map`.
5. **Two records, one person.** Seed both records in an Investigation, expand, then **Resolve** on the identity assertion.
6. **Entity profile.** The Entities page lists type Party; a masked type is opened through its alias. Reveal is `admin` only and audited.
7. **Close out.** Build the Dossier and Verify; external references (police-report requests) are listed as untrusted pointers. The seed opens a Case; a partner can be given a masked read-only embed link.
