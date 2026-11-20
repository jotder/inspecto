---
type: How-to
title: "Score entities"
description: "Define a Risk Score — weighted factors measured over Datasets per entity — run its Job to score every entity, read and explain a score, and alert on high ones."
item: module.scoring
audience: external
status: current
verified: 2026-11-20 code-reviewed
code: [features/inspecto-scoring, inspecto-ui/src/app/modules/admin/risk-scores]
---

# Score entities

## Goal

Give every account, customer or device a **Risk Score** from 0 to 100, built from factors you can explain, and see
which ones are high.

## Before you start

- **Edition:** Standard, Advanced or Preview (the Scoring and Entity Lists Add-on). Not in Basic.
- **Who:** a builder (`canAuthorWorkbench`) to define the Risk Score and its Job; `canOperateRuns` to run the Job;
  `canWorkIncidents` to read and preview scores.
- **What you need:** Datasets with a column that identifies the entity (an account id, a customer id) and the facts
  you will weigh.

## Steps

1. Open **Operations → Risk Scores** and click **New Risk Score**.
2. Give it an **Id**, the **Entity type** it scores and a **High threshold** (above 0, at most 100). Optionally a
   **Description**, a **Data scope**, an Entity List that high-scoring entities are added to (**Watch Entity List**,
   with an entry lifetime) and how long to keep history.
3. Under **Factors**, click **Add factor** for each one: **Id**, **Label**, the **Dataset**, its **Entity key column**,
   the **Measure** (`count` or an aggregate such as `sum(amount)`), its **Weight** and a **Cap**, and optionally a
   filter. Click **Save**.
4. **Make the Job that scores** — a saved Risk Score scores nothing by itself. Open
   **Platform → Workbench → Jobs**, **New job**, Type `risk.score`, a **Trigger** (a cron schedule, after a Pipeline,
   or manual only), and the parameter `model` = your Risk Score's id. **Continue**, give the Job id, **Create job**.
5. Run it once: open the Job and click **Run now**.
6. Back on **Risk Scores**, select your Risk Score: the **Scores** table lists each entity, its score and a *High*
   badge. Under *Latest score of an entity*, enter an **Entity key** and **Look up**, or **Preview** to score it now
   with each factor's contribution (Preview writes nothing).
7. To be told about high scores, use **Add per-entity Alert Rule** — it opens the Alert Rule form filled in
   ([Write an Alert Rule](../../addon/incidents/user-guide-howto-write-an-alert-rule.md)).

## Check it worked

On the worked example: a Risk Score `o2c_customer_risk` over customers, with one factor — the unmatched value of
each customer's open items, `sum(unmatched_value)`, weight 1, capped at 100 — and a high threshold of 50. Save it,
preview customer C-007, make and run its Job, and read the scores. The open items must be built first (the
`o2c_open_items` Job, as in Tutorial 2).

```check
POST /spaces/{space}/jobs/o2c_open_items/trigger
as builder
expect status 200|202
save data.runId as openItems
```

```check
GET /spaces/{space}/jobs/runs/{openItems}
as builder
expect data.status = "SUCCESS"
retry 30 every 2s
```

```check
POST /spaces/{space}/components/risk-score
as builder
body {"id": "o2c_customer_risk", "entityType": "customer", "highThreshold": 50, "factors": [{"id": "unpaid", "label": "Unmatched value", "dataset": "o2c_open_items", "key": "customer_id", "measure": "sum(unmatched_value)", "weight": 1, "cap": 100}]}
expect status 200|409
```

Previewing C-007 scores it now and shows each factor's contribution — C-007 owes 620, capped at 100, so it is high:

```check
POST /spaces/{space}/risk-scores/preview
as builder
body {"model": "o2c_customer_risk", "entityKey": "C-007"}
expect status 200
expect data.found = true
expect data.score = 100
expect data.high = true
expect data.factors[0].indicator = "unpaid"
```

```check
POST /spaces/{space}/jobs
as builder
body {"name": "o2c_customer_risk_score", "type": "risk.score", "model": "o2c_customer_risk", "enabled": true}
expect status 200|409
```

```check
POST /spaces/{space}/jobs/o2c_customer_risk_score/trigger
as builder
expect status 202
save data.runId as scoreRun
```

```check
GET /spaces/{space}/jobs/runs/{scoreRun}
as builder
expect data.status = "SUCCESS"
retry 30 every 2s
```

```check
GET /spaces/{space}/risk-score-lists/o2c_customer_risk
as builder
expect status 200
expect data.rows[*].entityKey has C-007
```

## What can go wrong

- `risk-score.highThreshold must be in (0, 100]`, `risk-score.factors must list at least one factor` (at most 32), a
  factor without a **Dataset** or **Measure**, or an id with characters other than letters, digits and `_`.
- *No scores match; the latest run may not have happened yet* — create and run the Job (steps 4–5).
- Preview answers 404 for an unknown Risk Score, and 422 when a factor's query fails (a wrong column name).
- The menu item is missing — the edition has no Scoring.

## Next

- [Screen names against an Entity List](../screening/user-guide-howto-screen-names.md)
- [Write an Alert Rule](../../addon/incidents/user-guide-howto-write-an-alert-rule.md)
