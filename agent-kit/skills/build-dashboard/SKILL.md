---
name: build-dashboard
description: Build Widgets over Datasets and arrange them in an Inspecto Dashboard, then propose both. Use when the person asks for a chart, a KPI tile, a table view or a Dashboard.
---

# Build a Dashboard

A **Widget** is one visual over a Dataset (an instance of a Visualization Type: a tile, a bar chart, a table). A
**Dashboard** arranges Widgets. Propose the Widgets first; the Dashboard refers to them by id.

## Steps

1. Agree the questions the Dashboard answers, one Widget per question.
2. `describe_dataset` for the columns each Widget needs. A Widget over a column that does not exist renders empty —
   check every column name against the description.
3. `query_dataset` to compute each Widget's number yourself, so you can tell the person what it will show and spot a
   wrong aggregation before it is approved.
4. Draft each Widget; `validate_config` with `type: widget`. Then draft the Dashboard naming the Widget ids;
   `validate_config` with `type: dashboard`.
5. `propose_change` for each Widget (`kind: widget`), then for the Dashboard (`kind: dashboard`). Say in each reason
   which Dashboard it belongs to, so the approver reviews them together.

## Read more

- Build a Dashboard: `/help/howto_build_a_dashboard.html`
- Widgets and Dashboards: `/help/concept_widget_dashboard.html`
- Tutorial — Dashboard: `/help/tutorial_dashboard.html`

## MCP Tools

- `describe_dataset` — the columns
- `query_dataset` — each Widget's number, computed by you
- `validate_config` — the save gate's findings
- `propose_change` — the held write
