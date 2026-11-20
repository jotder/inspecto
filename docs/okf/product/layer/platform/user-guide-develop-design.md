---
type: Concept
title: "On Inspecto — Design: design an App"
description: "The third page of the developer track: turn an App's requirements into a design — its findings and their keys, the data it reads, the logic written against neutral names, the Space Template's layout, the roles that work it, its Expectations and Alert Rules, and golden data that proves it."
item: layer.platform
audience: external
status: current
verified: 2026-11-20 code-read
---

# On Inspecto — Design: design an App

The third developer page. You know what the App declares ([Requirements](user-guide-develop-requirements.md)) and
where it plugs in ([Architecture](user-guide-develop-architecture.md)). Design is deciding, on paper, every file the
App will hold — before you build any of them. The shipped *Order-to-cash assurance* template is the worked example
throughout.

## 1. Start from the finding

Name the one thing the App finds, and its **key**: the column that says which thing it is about.

| App | Finding | Key |
|---|---|---|
| Order-to-cash assurance | an order whose invoiced and paid amounts differ | `order_id` |
| Acme Spend Watch (the App kit's starter) | an account that spends over a threshold in a day | `account_id` |

Everything else — Pipelines, Jobs, Alert Rules, golden data — exists to produce that finding, by that key, and
nothing else. The key is also what conformance compares ([Test](user-guide-develop-test.md)).

## 2. Map to the data

List the feeds the App reads and the **Datasets** they land in, with their columns and keys. If the App is for an
Industry, name the Datasets after the Industry's record types (for *Telecom*: voice calls, SMS, data sessions, SIM
registrations, top-ups …) so that the next App of the same Industry reads the same Datasets instead of a second
ingest.

| Feed | Dataset | Key | Columns the App uses |
|---|---|---|---|
| order files | `orders` | `order_id` | `order_date`, `customer_id`, `amount` |
| payment files | `payments` | `payment_id` | `payment_date`, `order_id`, `customer_id`, `amount` |
| *(written by a Job)* | `o2c_open_items` | `order_id` | `unmatched_value`, `status`, `days_late` |

## 3. Write the logic against neutral names

The logic that finds — the **Function pack** — is written against the Datasets' column names, not against one
customer's file layout. A customer's own layout is mapped onto those names in the Pipeline (its schema and Steps), so
the same Jobs, Expectations and Alert Rules work for every customer. Pick, for each piece of logic, the lowest tool that does it:

| The logic | Use | Needs |
|---|---|---|
| a rule every row must keep | an **Expectation** | base |
| one row per finding, computed with SQL | a `sql.template` **Job** writing a findings Dataset | base |
| two feeds that must agree | a **Reconciliation** | the `reconciliation` Add-on |
| raise an Incident when a measure crosses a threshold | an **Alert Rule** | the `incidents` Add-on |
| group Incidents into a Case | a **Case Rule** | the `caseManagement` Add-on |
| a score, a list to screen against | Scoring, Entity Lists | the `scoringLists` Add-on |

Every Add-on you use goes into `requires.addons`, and decides the editions the App runs on.

## 4. Lay out the Space Template

A Space Template is a folder of configuration that becomes a Space. Design its tree:

```
templates/<template-id>/
├─ template.toon                          name, tagline, description, minEdition, icon, contents
├─ config/
│  ├─ <feed>/<feed>_pipeline.toon         one Pipeline per feed …
│  ├─ <feed>/<feed>_schema.toon           … and the schema it applies
│  ├─ jobs/<job>_job.toon                 the Jobs (the conformance Jobs among them)
│  ├─ registry/datasets/<dataset>.toon    every Dataset, the findings Datasets included
│  ├─ registry/expectations/<name>.toon   Expectations
│  ├─ registry/alert-rules/<name>.toon    Alert Rules
│  ├─ registry/widgets/ · dashboards/     what users look at
│  └─ roles.toon                          the Space's role table (§5)
└─ data/
   ├─ samples/<feed>/                     the golden data (§7)
   └─ <findings dataset>/seed.parquet     a zero-row file, when an Alert Rule reads a Job's Dataset (§6)
```

What a Space Template does **not** carry: governance. A workflow with approval steps, for example, ships as a body
an administrator applies, as *Order-to-cash assurance* does.

## 5. Map the people to Roles

Write down who works the App — the analyst who triages, the manager who approves, the viewer who reads — and give
each a **Role** in the template's role table, `config/roles.toon`. A Role is a name plus the capabilities it grants:

```toon
roles[3]:
  - name: spend-analyst
    capabilities[2]: canWorkIncidents,canManageIncidents
  - name: spend-manager
    capabilities[3]: canWorkIncidents,canManageIncidents,canOperateRuns
  - name: spend-viewer
    capabilities[0]:
```

Your customer's identity provider then maps their own groups onto these Roles. Grant the least each person needs: a
user who creates a Space needs the administer capability, and from a template holding an Alert Rule also the
capability to author Alert Rules. See
[Roles and Access Policies](../../module/access/user-guide-concept-roles-access-policies.md).

The App's menu entries (`nav` in `app.toon`) choose the screens users land on; a `feature` on an entry keys it to a feature,
which a module of one of the App's required Add-ons must provide.

## 6. Decide the checks and the alerts

- **Expectations** guard the data the logic trusts. One file each, naming its target (a Pipeline or a Job), the
  column, the kind of check and a severity:

  ```toon
  name: orders_amount_non_negative
  targetType: pipeline
  target: orders
  column: amount
  kind: range
  min: 0
  severity: MAJOR
  ```

- **Alert Rules** turn findings into Incidents. One file each, naming the Dataset, the measure, what it is grouped
  by, the comparison and the severity:

  ```toon
  name: o2c_unmatched_value
  dataset: o2c_open_items
  measure: sum(unmatched_value)
  by[2]: customer_id,order_id
  comparator: gte
  threshold: 100
  severity: CRITICAL
  ```

  An Alert Rule over a Dataset that a Job writes needs that Dataset to exist when the Space is created, before the
  Job has ever run: ship a zero-row `seed.parquet` for it under `data/<dataset>/`, as the shipped templates do.

See [Expectation](../../module/elt/user-guide-concept-expectation.md) and
[Alert Rule](../../addon/incidents/user-guide-concept-alert-rule.md).

## 7. Design the golden data

Golden data is a small, synthetic day of feed files whose right answer you know. Design it to be hard to pass by
accident:

- **Planted findings** — the cases the App must find, each for one reason you can name (*O-1041: never paid*).
- **Look-alikes** — cases that resemble a finding but are not one (*paid in three instalments*, *paid on the due date*,
  *exactly at the threshold*). They catch a rule that is too loose.
- **One known day.** All of it falls on, or is read as of, one date: the `day` in `conformance.toon`.
- **No real data, ever.** Golden data ships inside the App.

Write the planted keys down as the ground truth: they become the `expected` lists in `conformance.toon`.

## Design checklist

| Decided | Becomes |
|---|---|
| the finding and its key | the findings Dataset and the `conformance.toon` key |
| feeds and Datasets | Pipelines, schemas, Dataset files |
| the logic | Jobs, Reconciliations, Case Rules — and `requires.addons` |
| the Space Template tree | `templates/<id>/` |
| people and Roles | `config/roles.toon` |
| checks and alerts | Expectation and Alert Rule files, seed files |
| golden data and ground truth | `data/samples/`, `conformance.toon` |

## Read next

- [Build an App](user-guide-develop-build.md)
- [Test your App](user-guide-develop-test.md)
