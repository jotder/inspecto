---
type: Concept
title: Link Analysis and Investigations
description: What Link Analysis is — seeing how the people, accounts and numbers in your data connect — and what an Investigation adds.
item: module.la-core
audience: external
status: current
verified: 2026-11-19 last-edit
code: la/inspecto-la-core
---

# Link Analysis and Investigations

## What it is

**Link Analysis** shows your data as a graph: the **entities** in it — people, accounts, phone numbers, devices —
as points, and the records that connect them — calls, payments, shared addresses — as links. You start from one
entity and **expand**, one step at a time, to see who it connects to, and find paths, clusters and patterns you
cannot see in a table.

An **Investigation** is a line of enquiry kept as evidence: every step you take — the starting entities, each
expansion, what you excluded or kept — is recorded in order and sealed, so the Investigation can be replayed,
compared and shown to someone else exactly as it was built.

```
  Dataset with Entity columns ─▶ graph ─▶ expand · find paths · patterns · measures
                                              │
                                              ▼
                                Investigation  sealed history of every step
                                   │      │
                         linked to a Case  Dossier: the evidence, ready to hand over
```

## Why it exists

Fraud, money laundering and leakage hide in connections: one device used by forty accounts, money moving in a circle.
Link Analysis makes those connections visible. An Investigation makes the result defensible: anyone can see how you
reached it.

## How it relates to its neighbours

- It reads **[Datasets](../../layer/product/user-guide-concept-dataset.md)**: the columns with the Entity role become
  the points of the graph.
- An Investigation can be linked to a **[Case](../../addon/incidents/user-guide-concept-alert-incident-case.md)**, as
  one line of enquiry within it. An Investigation is never a Case itself.
- **Watch** on an Investigation's Measure creates an
  **[Alert Rule](../../addon/incidents/user-guide-concept-alert-rule.md)**, so you are told when what the
  Investigation shows changes.
- Graphs and Geo maps can be placed on **[Dashboards](../../layer/product/user-guide-concept-widget-dashboard.md)**.

## What you do with it

Link Analysis is available as its own application, *Inspecto LA*, and inside the Inspecto console under
**Studio → Link Analysis**. You add data, choose how a Dataset is read as a graph, explore, and save views. With
Investigations you also keep the history, work in a private **Draft** alongside other analysts, run graph
algorithms on the server, and build a **Dossier** of the evidence.

To learn it hands-on, follow the Link Analysis training series, which walks from a first graph to sealed
Investigations.

## Which edition has it

The graph explorer — graphs from any Dataset, expand, paths, patterns, Entity Lists and saved views — is in every
edition. Investigations, Dossiers, Drafts, server-side graph runs and Alert Rules on Investigations come with the
**Investigations Add-on**, included by default in Standard and Advanced. The Geo map and entity profile pages need
Standard or Advanced. See [The editions on one page](../../../../EDITIONS.md).

## Read next

- [Alert, Incident and Case](../../addon/incidents/user-guide-concept-alert-incident-case.md)
- [Dataset](../../layer/product/user-guide-concept-dataset.md)
