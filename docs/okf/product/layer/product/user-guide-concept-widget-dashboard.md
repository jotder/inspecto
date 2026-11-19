---
type: Concept
title: Widget and Dashboard
description: What a Visualization Type, a Widget, a Dashboard and a Report are, and how a Dataset becomes a page of charts.
item: layer.product
audience: external
---

# Widget and Dashboard

## What they are

- A **Visualization Type** is a kind of display: a bar chart, a line chart, a table, a single-number KPI tile, a
  map, a graph. It is the template, not a chart of your data.
- A **Widget** is one configured display: a Visualization Type, its settings, and the data it is bound to — a
  [Dataset](user-guide-concept-dataset.md) or a saved Query over one. "Revenue by region, last 30 days, as a bar
  chart" is a Widget.
- A **Dashboard** is a page of Widgets laid out on a grid, shared with everyone in the
  [Space](../../module/config/user-guide-concept-space.md) who may see it.
- A **Report** is a scheduled delivery of a Dashboard: a copy as CSV, PDF or PNG, sent to a list of recipients on
  a schedule.

```
  Visualization Type     Dataset or Query
   "bar chart"            "sales"
          └──────┬──────────┘
                 ▼
              Widget  "Revenue by region"   ─┐
              Widget  "Failed payments today" ├─▶ Dashboard "Daily sales" ─▶ Report (every morning, PDF)
              Widget  "Top 10 customers"     ─┘
```

## Why a Type and a Widget are different words

One Visualization Type is used by many Widgets. Keeping the words apart makes it clear whether you are choosing a kind
of chart (a Type) or changing one chart on a Dashboard (a Widget). The same goes for the data: a Widget reads the
**Measures** defined on its Dataset, so "revenue" means the same thing on every Widget that shows it.

## How they relate to their neighbours

- Widgets read **[Datasets](user-guide-concept-dataset.md)**, usually through a saved **Query**.
- A KPI tile is a Widget showing one Measure against a target. To be told when it goes out of line, watch the same
  Measure with an **[Alert Rule](../../addon/incidents/user-guide-concept-alert-rule.md)**.
- Link Analysis graphs and Geo maps can also be Widgets. See
  [Link Analysis and Investigations](../../module/la-core/user-guide-concept-link-analysis.md).

## What you do with them in the console

- **Studio → Query Library**: write a Query over a Dataset and preview its result.
- **Studio → Viz Library**: every saved Widget; build a new one or edit one.
- **Studio → Dashboard Builder**: add, arrange and resize Widgets on a Dashboard, with quick filters and drill-through.
- **Business → KPI & Reports**: every Dashboard, with its Reports beneath it. **Schedule export** creates a Report;
  you can run it now or download its latest output.
- A Dashboard can be shared by a public link. Its viewer is anonymous, so sensitive values on it are masked unless the
  Space has set a masking mode of its own.

## Which edition has it

Queries, Widgets and Dashboards are in every edition. Link Analysis and Geo map Widgets need Standard or Advanced.
See [The editions on one page](../../../../EDITIONS.md).

## Read next

- [Dataset](user-guide-concept-dataset.md)
- [Alert Rule, Expectation and Decision Rule](../../addon/incidents/user-guide-concept-alert-rule.md)
