---
type: Concept
title: Expectation
description: What an Expectation is — a data-quality check on the records of a Dataset — what happens when it fails, and how it differs from an Alert Rule.
item: module.elt
audience: external
---

# Expectation

## What it is

An **Expectation** is a data-quality check: a statement about the records of a
**[Dataset](../../layer/product/user-guide-concept-dataset.md)** that should always be true. Inspecto counts the
records that break it. Zero means the Expectation passed; anything else means it failed, and you see how many
records broke it.

The kinds of check include:

- **not null** — a column always has a value;
- **range** — a number lies between two limits;
- **pattern** — a value matches a format, such as a phone number or an account code;
- **referential** — a value exists in another Dataset, for example every order's customer is a known customer;
- **condition** — any combination of conditions you write;
- **baseline** — this delivery looks like the deliveries you accepted before.

## Why it exists

Bad data produces confident, wrong answers. An Expectation states what "good" means, checks it, and makes a failure visible before anyone builds a Dashboard or a decision on it.

## What happens when it fails

- The failure and its count are kept, so you can see which Expectations are breached right now.
- Records that fail while a file is being read can be set aside in **quarantine**, with the reason, instead of
  reaching the Dataset. You fix the cause and process them again.
- A failure is announced as a notification and, where your edition has Incidents, can open an
  **[Incident](../../addon/incidents/user-guide-concept-alert-incident-case.md)**.

## How it relates to its neighbours

```
  Pipeline writes ─▶ Dataset ◀─ Expectation checks its records   "is the data right?"
                        │
                        └──── Alert Rule watches a number         "is the business normal?"
```

- An Expectation is about **data quality**. An
  **[Alert Rule](../../addon/incidents/user-guide-concept-alert-rule.md)** is about a number crossing a threshold.
  A **Decision Rule** routes records. They are three different things.
- Expectations attach to the Datasets a **[Pipeline](user-guide-concept-pipeline.md)** writes, and to Datasets made
  with *Add data*: they are checked in the import's own run.

## What you do with it in the console

- **Expectations** (in **Platform → Workbench**): create one, choose the Dataset and the kind of check, and save it.
  **Run check now** evaluates it on demand. Each Expectation keeps its version history, so you can restore an
  earlier one.
- **Runs**: open a Run to see its quarantined records and the reason for each.
- The **Assistant** (Standard and Advanced) can help you write an Expectation.

## Which edition has it

Expectations, quarantine and duplicate removal are in every edition. Opening an Incident from a failure needs the
Incidents Add-on, included in Standard and Advanced. See [The editions on one page](../../../../EDITIONS.md).

## Read next

- [Alert Rule, Expectation and Decision Rule](../../addon/incidents/user-guide-concept-alert-rule.md)
- [Pipeline and Steps](user-guide-concept-pipeline.md)
