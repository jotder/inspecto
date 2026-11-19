---
type: Concept
title: Alert, Incident and Case
description: What an Alert, an Incident and a Case are, how one leads to the next, and how you work them in the console.
item: addon.incidents
audience: external
status: current
verified: 2026-11-19 last-edit
code: features/inspecto-ops
---

# Alert, Incident and Case

Three words for three sizes of "something needs attention", always in this order:

```
  Alert ─────────▶ Incident ─────────────▶ Case
  a signal that    a problem someone       several related Incidents,
  fired            owns and works          worked as one
```

## Alert

An **Alert** is one firing of an
**[Alert Rule](user-guide-concept-alert-rule.md)**: a number crossed its threshold. It carries a severity — info,
warning or critical — and says what happened. You can **acknowledge** an Alert (someone is on it) and **resolve** it.
An Alert is remembered, so the same open Alert is not fired twice.

## Incident

An **Incident** is a tracked problem with an owner. It is opened automatically by an Alert, a failed Expectation or
a diagnosis of a failed run, or by hand — for example from a reconciliation break.

Its life has four states:

```
  Identified ─▶ Diagnosing ─▶ Resolved ─▶ Archived
                    ▲              │
                    └── reopen ────┘
```

- **Identified** — new, not yet accepted. Accepting it moves it to Diagnosing and asks for its category,
  subcategory and detail.
- **Diagnosing** — someone is working it. One person, the **Incident Commander**, owns it.
- **Resolved** — fixed, with a resolution comment. The resolution asks for a timeline of events, the cause analysis,
  the corrective and preventive actions, and the service level; you are warned if parts are missing.
- **Archived** — put away. A Resolved or Archived Incident can be reopened.

Each Incident has a priority: Critical, Major, Minor or Low.

## Case

A **Case** groups related Incidents that turn out to be one larger problem, so they are investigated and closed
together with one shared resolution. Its life runs open → investigating → escalated → resolved → closed.

- **Case Rules** group Incidents for you: when enough Incidents match a filter within a time window, they are put in
  one Case.
- Two Cases about the same thing can be **merged**: the surviving Case takes the members, and the other is closed
  with a link to it.
- When a Case closes, its **Findings** panel records the outcome — for example the disposition and the impact. Your
  team can choose the questions it asks.
- A Link Analysis **Investigation** can be linked to a Case as one line of enquiry within it. See
  [Link Analysis and Investigations](../../module/la-core/user-guide-concept-link-analysis.md).

## Why three levels

An Alert is cheap and frequent; most need only a glance. An Incident is a commitment: someone owns it until it is
fixed. A Case is for when the problem is bigger than one Incident. Keeping the levels apart stops a busy Alert list
from burying the work that matters.

## What you do with them in the console

- **Operations → Alerts**: what fired, newest first; acknowledge or resolve; the Alert Rules are below the list.
- **Operations → Incidents**: triage, assign, change status, add notes and record the resolution.
- **Cases** (the **Case Manager**): open a Case, see its member Incidents, work it to a decision, and set the
  Findings fields.
- **Settings → Incident governance**: workflows, service-level policies and escalation for Incidents and Cases.

## Which edition has it

Alerts are kept in every edition. Incidents and Cases need Standard or Advanced; Basic does not show the Incidents
and Case Manager screens. Workflows and service levels come with the **Workflow & SLA** Add-on. On Advanced,
Incidents and Cases are stored in PostgreSQL so several servers can share them. See
[The editions on one page](../../../../EDITIONS.md).

## Read next

- [Alert Rule, Expectation and Decision Rule](user-guide-concept-alert-rule.md)
- [Roles and Access Policies](../../module/access/user-guide-concept-roles-access-policies.md)
