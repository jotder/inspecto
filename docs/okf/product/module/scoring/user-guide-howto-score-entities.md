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

Previewing one entity's score answers its score and how each factor contributed (here a Risk Score
`payment_account` and an account `A-1001`):

```check
POST /spaces/{space}/risk-scores/preview
as builder
body {"model": "payment_account", "entityKey": "A-1001"}
expect status 200
expect data.model = "payment_account"
expect data.factors exists
```

After the Job has run, the list of scores is filled:

```check
GET /spaces/{space}/risk-score-lists/payment_account
as builder
expect status 200
expect data.rows exists
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
