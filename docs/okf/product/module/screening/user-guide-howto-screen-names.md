---
type: How-to
title: "Screen names against an Entity List"
description: "Build an Entity List of names or identifiers, then check names against it — one off with Check a name, or in bulk with a screening Job that raises Screening Hits."
item: module.screening
audience: external
status: current
verified: 2026-11-20 code-reviewed
code: [features/inspecto-screening, features/inspecto-entity-list]
---

# Screen names against an Entity List

## Goal

Find out whether a name or identifier is on a list you care about — sanctioned parties, known fraudsters, blocked
devices — with a score for how close the match is.

## Before you start

- **Edition:** Standard, Advanced or Preview for screening (the Screening Add-on). Entity Lists themselves exist in
  every edition.
- **Who:** creating a list and its entries needs `canManageIncidents`; checking a name needs no special capability;
  deciding a Screening Hit needs `canWorkIncidents`.
- **What you need:** the list's entries — names or identifiers — and the reason you keep them.

## Steps

### Make the Entity List

1. Open **Platform → Studio → Link Analysis**, the **Settings** (gear) panel, section **Entity Lists**, and click
   **New list…**.
2. Give a **Title**, a **Purpose**, the **Entity Type** of its entries (for names, an organisation or a person type
   your Space uses) and a **Reason**, then **Create list**.
3. **Edit entries…**: choose Add, paste the **Entries, one per line**, optionally an expiry and a reason, and save.

### Check names

1. Open **Operations → Screening Hits** and click **Check a name**. *Nothing is raised* — it is a look-up only.
2. Paste the **Names to check**, one per line (up to 1,000), tick the **Entity Lists** to check against, and
   optionally a **Threshold** (0.5 to 1; 0.85 by default) and **Most matches per name**.
3. **Run check**. Each match shows the name, the list, the entry it matched, the method (name or identifier) and the
   **Match Score**. A clean name reads *Checked: no match at or above <threshold>*.

To screen whole Datasets regularly, a Job of type `screening.run` raises **Screening Hits** on that page; confirming a
Hit opens an Incident and a Case.

## Check it worked

The list exists, and checking a name against it answers a result per name (here a list `blocked_parties`):

```check
GET /spaces/{space}/entity-lists
as builder
expect status 200
expect data.lists[*].id has blocked_parties
```

```check
POST /spaces/{space}/screening/check
as builder
body {"subjects": [{"name": "Acme Trading Ltd"}], "lists": ["blocked_parties"]}
expect status 200
expect data.results.length = 1
```

## What can go wrong

- *There are no active Entity Lists to screen against.* — make a list first.
- `'subjects[i]' needs a 'name' or an 'identifier'`, `'lists' must name 1..20 Entity Lists`, `'threshold' must be a
  number in 0.5..1.0`, or `unknown entity list(s) […]`.
- `entity list '<id>' already exists (an id is never reused…)` — a retired list's id cannot be taken again.
- 403 — a restricted list whose members you may not see.
- *Screening is not available on this deployment* — the edition has no Screening.
- In a Space that masks data, matched entries show as mask tokens.

## Next

- [Score entities](../scoring/user-guide-howto-score-entities.md)
- [Open and work a Case](../case-management/user-guide-howto-open-and-work-a-case.md)
