---
type: Plan
title: "Screening — plan"
description: "Requirement of record for the screening add-on: what was required, built, left and refused."
resource: features/inspecto-screening/src/main/resources/META-INF/inspecto/module.toon
tags: [screening, plan]
timestamp: 2026-10-19T00:00:00Z
---

# Screening — plan

Requirement of record, distilled from the archived
[`screening-addon-plan.md`](../../../../archived-documents/plans-archive/screening-addon-plan.md) and the
`SCREENING-1` row of the [BACKLOG](../../../../BACKLOG.md). As-built detail: [spec](spec.md).

## Required

Match subjects (name and/or identifier) against Entity Lists of any purpose with a Match Score; an ad-hoc check
that persists nothing; a scheduled Run raising reviewable, tamper-evident Screening Hits; audited decisions.
Domain-neutral: the AML pack (`PACK-AML-1`) builds on it.

## Built (`SCREENING-1`)

- 2026-10-09: module `inspecto-screening`, `POST /screening/check`, the `screening.run` Job Type, signed hits with
  read and decide routes, the `/screening` review pane (decisions SCR-D1..SCR-D14).
- 2026-10-11: phonetic / transliteration block keys.
- 2026-10-14: four-eyes on `confirmed` (D1), a confirm opens an Incident and a Case (D2), the audited reveal (D3),
  the save-time check of a `screening.run` Job, the *Check a name* dialog.

## Left

Open items stay on the `SCREENING-1` row; the recorded limits are in the [spec](spec.md) (*Limits and what is open*).

## Refused or deferred

- `check` raising hits: refused (D4); raising belongs to the Job.
- A Space-level threshold default: not until a customer asks (D5, SCR-D2).
- A dedupe index: measure at 100 000 hits first (D6).
- Comparing an initial-letter difference that is not a known transliteration: needs a full cross-join (D7).
- A module-contributed capability: declined; `canWorkIncidents` decides.
