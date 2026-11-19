---
type: Concept
title: How the pieces fit
description: A one-page map of the Inspecto concepts — from a file arriving to a closed Case — and where to read about each.
item: layer.product
audience: external
status: current
verified: 2026-11-19 last-edit
---

# How the pieces fit

Inspecto takes data from the systems you run, makes it trustworthy, and helps you watch it, analyse it and act on
what it shows. Every screen in the console works with a small set of named things. This page is the map; each name
links to its own page.

## The path of your data

```
  Connection ─▶ Collector ─▶ Pipeline ─▶ Dataset ─▶ Query ─▶ Widget ─▶ Dashboard
  how to reach   what to       read and    clean,                 a chart    a page of
  a system       fetch, when   shape it    queryable data                    Widgets
                                  │
                             Expectations check the data on the way in
                                  │
                  Alert Rule ─▶ Alert ─▶ Incident ─▶ Case
                  watches a      it        someone     related Incidents,
                  number         fired     works it    worked as one
```

- A **[Connection](../../module/elt/user-guide-concept-connection-collector.md)** says how to reach a remote
  system; a **Collector** says what to fetch from it and when.
- A **[Pipeline](../../module/elt/user-guide-concept-pipeline.md)** reads what arrives, Step by Step, and writes
  a **[Dataset](user-guide-concept-dataset.md)**: clean data you can query.
- **[Expectations](../../module/elt/user-guide-concept-expectation.md)** check that data is what you expect.
- **[Widgets and Dashboards](user-guide-concept-widget-dashboard.md)** turn Datasets into charts and pages.
- An **[Alert Rule](../../addon/incidents/user-guide-concept-alert-rule.md)** watches a number and fires an
  **[Alert](../../addon/incidents/user-guide-concept-alert-incident-case.md)**; an Alert can open an **Incident**,
  and related Incidents are grouped into a **Case**.
- **[Link Analysis](../../module/la-core/user-guide-concept-link-analysis.md)** shows how the people, accounts
  and numbers in your Datasets connect.

## Where all of it lives

Everything above belongs to one **[Space](../../module/config/user-guide-concept-space.md)**: an isolated project
with its own data, configuration and people. You can start a Space empty or from a
**[Space Template](../../module/config/user-guide-concept-space-template.md)**, which brings ready-made Pipelines,
Datasets and Dashboards with it.

## What you have and who may use it

- An **[App](user-guide-concept-app.md)** is a finished business application on Inspecto, such as Revenue
  Assurance or Link Analysis.
- Your **[edition](user-guide-concept-edition-addon.md)** (Basic, Standard or Advanced) and your **Add-ons**
  decide which features your installation has.
- **[Roles and Access Policies](../../module/access/user-guide-concept-roles-access-policies.md)** decide what
  each signed-in person may see and do.

## Three words that are easy to mix up

| Word | Means | Is not |
|---|---|---|
| **Pipeline** | the Steps that read and shape incoming data | a Job, which works on data already stored |
| **Expectation** | a data-quality check on records | an Alert Rule or a Decision Rule |
| **Widget** | one configured chart, table or map | a Visualization Type, which is the kind of chart |

For screen-by-screen help, see the [User guide](../../../../USER_GUIDE.md). For what each edition includes, see
[The editions on one page](../../../../EDITIONS.md).
