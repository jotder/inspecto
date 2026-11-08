---
type: Feature
title: "Link Analysis claims showcase — user guide"
description: "How an SIU investigator works the la-claims Space day to day: Investigations, communities, Geo, identity resolution, Entity profiles and the Case."
tags: [la-claims, user-guide]
timestamp: 2026-11-07T00:00:00Z
---

# Link Analysis claims showcase — user guide

Screens and controls are the shared studio ones: see the [la-showcase user guide](../la-showcase/user-guide.md) and the [Link Analysis user guide](../../module/la-core/user-guide-link-analysis.md). The scripted demo with expected values is the [runbook](admin-guide-showcase-runbook.md).

## Which edition

The Space has two tiers.

- **Basic tier: every edition, Basic included.** The data, both link Jobs, the five saved views and the exercises below all
  work on Basic. Nobody signs in, so masking is off and you read the real ids (`GAR-03`, `SUP-07`) on the canvas.
- **Advanced tier: a build where people sign in.** You get the users below, typed masking with the admin's Reveal,
  Investigations, four-eyes approval, Drafts, the Dossier and the seeded Case, so this section and "A working day" need it.
  On Basic these settings are present but do nothing.

## Who signs in

| User | Works as |
|---|---|
| `siu.analyst` | lead: builds the Investigation, authors Alert Rules, runs Graph Runs |
| `siu.manager` | second approver; presses Run on Jobs |
| `partner.reviewer` | read-only member |
| `admin` | Reveal, Settings, embed links |

## A working day

1. **Start small.** Open `claimant_ring_shared_identity` (or `claims_network`) and seed a claimant such as `CL-0134`; expand. Always seed first.
2. **Find the ring.** Toolbox > Communities (Louvain), then Cliques and Triangle count. Client run first, then **Run on server**.
3. **Look at providers.** HITS and PageRank on the working set; open `provider_money_trail`.
4. **Where did it happen?** Geo page > load `staged_accident_junction_map`.
5. **Two records, one person.** Seed both records in an Investigation, expand, then **Resolve** on the identity assertion.
6. **Entity profile.** The Entities page lists type Party; a masked type is opened through its alias. Reveal is `admin` only and audited.
7. **Close out.** Build the Dossier and Verify; external references (police-report requests) are listed as untrusted pointers. The seed opens a Case; a partner can be given a masked read-only embed link.

## Exercises

Try each one before you read its answer. The answers are the tester's checklist too, including what must **not** appear.

### Exercise 1: the parts that were never fitted

Open the saved view `parts_supplier_ring`. It shows only `supplied_parts` links, from a parts supplier to each garage it
invoiced parts through. A link's `amount` is the value of the parts that the garage did **not** fit. Two suppliers reach every
garage. Which one is a fraud ring, and how do you prove it from the Dataset `repair_invoice_dataset`?

**Answer.** `SUP-07`. Its link to each of the nine garages carries money (about £1 700 to £4 300 a garage). It invoiced
61 parts worth £25 906 on 30 claims, and the garage fitted none of them: in `repair_invoice_dataset`, filter
`supplier_id = SUP-07`, and every row says `fitted = N`. None of the 30 claims is a staged-accident ring claim, so this is a
separate story. The look-alike is `SUP-01`. It also reaches all nine garages and is far busier (429 parts on 218 claims), but
every part it sold was fitted, so each of its links carries 0. Reach alone is not guilt. Elsewhere only 3 claims have an
unfitted part (ordinary returns through `SUP-02` and `SUP-03`).

### Exercise 2: the broker nobody can find

Open the saved view `ghost_broker_premium_payers`. It shows only `paid_by` links, from a policyholder to a bank account that pays the
premiums of two or more policies. `via` names the policies. Three accounts appear. Which two are a ghost broker's, and what in
`policy_dataset` gives them away?

**Answer.** `BA-93000017` (13 policyholders) and `BA-93000029` (12). The 25 policies they pay, `POL-0381` to `POL-0405`, were all
sold through broker `BRK-07` between January and July 2026, at premiums of £219 to £333 (every genuine policy costs £380 or more).
Their holders, `CL-0381` to `CL-0405`, are on no claim and have no other link. Their addresses are on four streets (Quarry, Saltmarsh,
Tollgate and Wyvern Road) that appear on no claim and no other policy: the addresses do not exist. The look-alike is
`BA-71000001`. It pays four policies, those of the family of four (`CL-0103`, `CL-0217`, `CL-0218`, `CL-0249`), all at
`80 Kestrel Road, Leeds`: the address they gave on their own claims. None of the family's policies came through `BRK-07` (three came through `BRK-05`, the early-claim broker of story 5, but none was claimed on soon after inception), and
the family is linked to neither ghost account.
