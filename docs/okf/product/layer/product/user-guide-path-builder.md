---
type: Concept
title: "Learning Path — Builder"
description: "The order in which a console builder reads the Handbook: concepts, the four builder tutorials on the Order-to-cash worked example, two Labs, the how-tos by stage, and a self-check at the end of each stage."
item: layer.product
audience: external
status: current
verified: 2026-11-20 last-edit
---

# Learning Path — Builder

You build business Apps in the console: bring data in, shape it, check it, detect what matters, respond and present.
This path puts the Handbook's pages in the order that teaches that, on **one worked example** — *Order-to-cash
assurance*, orders against payments — so you finish with one complete thing rather than a pile of exercises.

**Time:** about two days: a day for the core (stages 1–2 up to Tutorial 4), a day for the how-tos you need.
**Edition:** the worked example needs Standard, Advanced or Preview; the Basic pieces are marked on each page.

Every page carries a type: a **tutorial** teaches by doing, a **how-to** does one task, a **concept** explains, a
**reference** lists. A **Lab** is an exercise with a checker that grades your Space through the Control API; Labs are
training material, given to you by whoever runs your training, and not part of this Help.

## Stage 1 — Understand (about 2 hours)

Read in this order:

1. [How the pieces fit](user-guide-concept-overview.md)
2. [Space](../../module/config/user-guide-concept-space.md) · [Dataset](user-guide-concept-dataset.md)
3. [Connection and Collector](../../module/elt/user-guide-concept-connection-collector.md) ·
   [Pipeline and Steps](../../module/elt/user-guide-concept-pipeline.md) ·
   [Expectation](../../module/elt/user-guide-concept-expectation.md)
4. [Alert Rule, Expectation and Decision Rule](../../addon/incidents/user-guide-concept-alert-rule.md) ·
   [Alert, Incident and Case](../../addon/incidents/user-guide-concept-alert-incident-case.md)
5. [Widget and Dashboard](user-guide-concept-widget-dashboard.md) ·
   [Space Template](../../module/config/user-guide-concept-space-template.md) · [App](user-guide-concept-app.md)
6. [Edition and Add-on](user-guide-concept-edition-addon.md) — which pieces your edition has.

**Self-check 1.**

1. What lands rows in a Dataset: a Collector, a Pipeline or an Expectation?
2. An Expectation and an Alert Rule both fire on data. What does each one watch?
3. What is the difference between an Incident and a Case?
4. What does a Space Template give you that a Space does not?

## Stage 2 — Build (about 6 hours)

The core, on the worked example — do these in order:

1. [Tutorial 1 — Your first Space and Dataset](../../module/config/user-guide-tutorial-first-space.md)
2. [Tutorial 2 — Make it a Pipeline with Expectations](../../module/elt/user-guide-tutorial-pipeline-expectations.md)
3. **Lab 1** — [Trust the feeds](../../module/elt/user-guide-lab-trust-the-feeds.md): three Expectations of your own,
   graded by a checker.
4. [Tutorial 3 — Detect and respond](../../addon/incidents/user-guide-tutorial-detect-respond.md)
5. **Lab 2** — [Exposure per customer](../../addon/incidents/user-guide-lab-exposure-per-customer.md): an Alert Rule of
   your own and the first Incident worked, graded by a checker.
6. [Tutorial 4 — Present it](user-guide-tutorial-dashboard.md)

Then the how-tos, by what your App needs:

| Stage | How-tos |
|---|---|
| Bring data in | [Add a file](user-guide-howto-add-a-file.md) · [Watch a folder](../../module/elt/user-guide-howto-watch-a-folder.md) · [Connect SFTP or a database](../../module/connectors/user-guide-howto-connect-sftp-or-a-database.md) |
| Shape it | [Build a Pipeline from a sample](../../module/elt/user-guide-howto-pipeline-from-a-sample.md) |
| Trust it | [Add an Expectation](../../module/elt/user-guide-howto-add-an-expectation.md) · [Handle quarantined rows](../../module/elt/user-guide-howto-handle-quarantined-rows.md) |
| Analyse it | [Explore links between entities](../../module/la-core/user-guide-howto-explore-links.md) — the Link Analysis training videos go deeper |
| Detect | [Reconcile two Datasets](../../module/reconciliation/user-guide-howto-reconcile-two-datasets.md) · [Write an Alert Rule](../../addon/incidents/user-guide-howto-write-an-alert-rule.md) · [Detect in real time](../../module/realtime/user-guide-howto-detect-in-real-time.md) · [Score entities](../../module/scoring/user-guide-howto-score-entities.md) · [Screen names](../../module/screening/user-guide-howto-screen-names.md) |
| Respond | [Open and work a Case](../../module/case-management/user-guide-howto-open-and-work-a-case.md) |
| Present | [Build a Dashboard](user-guide-howto-build-a-dashboard.md) · [Schedule a report](../../module/analytics/user-guide-howto-schedule-a-report.md) |

**Self-check 2.**

1. A payment of -240.00 landed without a quarantine. Why, and what catches it?
2. Why must the open-items Job run before `orders_id_unique` means anything?
3. A Reconciliation lists eight Breaks, but the Alert Rule opened six Incidents. Where did the other two go?
4. Your Alert Rule fires but no Incident appears. What is the first thing you check?

## Stage 3 — Ship (about 1 hour)

A builder ships a change between Spaces; packaging an App is the partner developer's path.

1. [Change a Pipeline safely](../../module/elt/user-guide-howto-change-a-pipeline-safely.md)
2. [App](user-guide-concept-app.md), then — if you will hand your Space to a partner developer — skim
   [Tutorial: ship it](../platform/user-guide-tutorial-ship-an-app.md) to see what they need from you.

**Self-check 3.**

1. How do you see what a Pipeline change will produce before anything is written?
2. What happens on **Save pipeline** when the change would break the Pipeline?

## Stage 4 — Manage (about 1 hour)

Run what you built: runs, data-quality breaches, Incidents and Cases. Re-read the *What can go wrong* sections of the
how-tos you used, then the [screen reference](../../../../USER_GUIDE.md) for the screens you did not open yet.

**Self-check 4.**

1. Where do you see each file a Pipeline took in, and any it quarantined?
2. Running the checks again: does a still-failing Expectation open a second Incident?

## Self-check answers

- **1.1** A Pipeline; a Collector brings files to it, an Expectation only reads. **1.2** An Expectation counts the
  records that break a statement about the data; an Alert Rule watches a Measure against a threshold. **1.3** An
  Incident is one thing that needs attention; a Case groups Incidents (and evidence) for one piece of work. **1.4** A
  ready, reusable starting point — Pipelines, checks, Dashboards and sample data — to create many Spaces from.
- **2.1** It is well-formed (a number), so the Schema accepts it; `payments_amount_non_negative` catches it.
  **2.2** It reads the open items, which that Job writes; until then it reads nothing and passes. **2.3** They are
  under the threshold of 100 (O-1064 short by 4.50, the orphan payment O-9002 of 55.00) and stay Breaks on the
  Reconciliation board. **2.4** The Severity: only a Critical Alert opens an Incident.
- **3.1** **Dry-run**, then **Run dry-run** with sample rows: each Step's output, nothing written. **3.2** The server
  checks the change again and refuses it with its findings; nothing is saved.
- **4.1** **Platform → Workbench → Runs**, the Pipeline's **Open detail**: the **Files** and **Quarantine** tabs.
  **4.2** No, not while the first Incident for that breach is open.
