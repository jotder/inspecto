---
type: Concept
title: Dataset
description: What a Dataset is — any table of data you can query in Inspecto — how you get one, and what its columns and Measures are for.
item: layer.product
audience: external
status: current
verified: 2026-11-19 last-edit
---

# Dataset

## What it is

A **Dataset** is any table of data you can query in Inspecto. Queries, Widgets, Dashboards, Alert Rules, Expectations
and Link Analysis all read Datasets. It is the one word for "data you can ask questions of", whatever made it.

A Dataset is one of these:

| Kind | What it is |
|---|---|
| **Table** | data a Pipeline or *Add data* wrote, stored by date |
| **Derived Table** | a table computed from other Datasets, for example a daily summary |
| **Reference Dataset** | slowly changing lookup data, such as a price list or a list of branches |
| **View** | a saved query that behaves like a table but stores nothing of its own |

## Why it exists

Data arrives in many shapes: files, database tables, feeds. Once it is a Dataset it has one shape every screen
understands: named columns with types, roles and reusable Measures. You describe the data once, and every chart,
check and investigation uses that description.

## How it relates to its neighbours

```
  Add data ──────────────┐
                          ├─▶ Dataset ─▶ Query ─▶ Widget ─▶ Dashboard
  Connection ─▶ Collector ─▶ Pipeline ┘     │
                                            ├─▶ Expectations check it
                                            ├─▶ Alert Rules watch its Measures
                                            └─▶ Link Analysis draws it as a graph
```

- A **[Pipeline](../../module/elt/user-guide-concept-pipeline.md)** writes Tables; *Add data* does the same for a
  single file, folder or database table, without you building a Pipeline.
- **[Expectations](../../module/elt/user-guide-concept-expectation.md)** check the records in a Dataset.
- A **[Widget](user-guide-concept-widget-dashboard.md)** shows a Dataset as a chart, table or map.
- An **[Alert Rule](../../addon/incidents/user-guide-concept-alert-rule.md)** can watch a Measure over a Dataset.
- **[Link Analysis](../../module/la-core/user-guide-concept-link-analysis.md)** reads a Dataset's Entity columns as
  the people, accounts or numbers in a graph.
- Every Dataset belongs to one **[Space](../../module/config/user-guide-concept-space.md)**.

## Columns, roles and Measures

When a Dataset is created you review its columns: each has a name, a type and a **column role**:

- **Entity** — who or what a row is about (a customer, an account, a phone number), of a named Entity Type.
- **Time** — when it happened.
- **Measure** — a number you add up or average.
- **Category** — a label you group by.
- **Detail** — anything else worth keeping.

On a Dataset you can also define **Measures**: named sums, counts or averages, written once and then picked by every
Widget, KPI tile and Alert Rule that uses this Dataset. You can add **calculated columns**, worked out from other
columns on every row.

## What you do with it in the console

- **Add data** to get a Dataset from a file you upload, from files left in an import folder (*Import waiting files*,
  or *Watch this folder*), or from a PostgreSQL table copied as a snapshot (*Refresh now*).
- **Browse Datasets** in **Catalog → Datasets**: open one to edit its columns, roles, calculated columns and
  Measures, and restore an earlier version with **History**. Deleting a Dataset removes its definition, never the
  files underneath.
- **Trace where the data came from** in the Catalog's **Lineage** tab.
- **Query it** in **Studio → Query Library**.

## Which edition has it

Datasets, *Add data*, column review and Measures are in every edition. A retention policy on an imported Dataset,
*Keep this up to date* on a schedule, and enrichment on the Dataset page (*Add the details from X to each row*) need
Standard or Advanced. See [The editions on one page](../../../../EDITIONS.md).

## Read next

- [Pipeline](../../module/elt/user-guide-concept-pipeline.md)
- [Widget and Dashboard](user-guide-concept-widget-dashboard.md)
- [Expectation](../../module/elt/user-guide-concept-expectation.md)
