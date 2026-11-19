---
type: Concept
title: Space Template
description: What a Space Template is — a ready-made blueprint that creates a new Space with its Pipelines, Datasets and Dashboards — and how it differs from exporting a Space.
item: module.config
audience: external
status: current
verified: 2026-11-19 last-edit
code: platform/inspecto-config
---

# Space Template

## What it is

A **Space Template** is a ready-made blueprint for a [Space](user-guide-concept-space.md). It holds everything a
project of that kind needs to start: Collectors, Pipelines, Datasets, Expectations, Alert Rules, Widgets and
Dashboards, and often sample data so you can see it working at once.

The Template is the pattern; the Space you create from it is your own copy. Change the Space as much as you like:
the Template stays as it was, and other Spaces made from it are not affected.

```
  Space Template "Orders starter"
        │  create a Space from it
        ├─▶ Space "Orders — test"        your copy, change freely
        └─▶ Space "Orders — production"  another copy
```

## Why it exists

Building a project from nothing takes time and knowledge of the domain. A Space Template gives you a working
starting point — the data model, the checks and the Dashboards someone already designed — so you adapt it rather than
invent it.

## How it relates to its neighbours

- A Space Template creates a **[Space](user-guide-concept-space.md)**.
- An **[App](../../layer/product/user-guide-concept-app.md)** can bring its content as Space Templates, plus its own
  menus and landing page.
- **Export and import** move what you built between installations: **Settings → Import & Export** moves selected
  definitions (never data rows), and **Settings → Spaces** exports or imports a whole Space as a zip. Neither is a
  Space Template.

## What you do with it in the console

In **Settings → Spaces**, choose to create a Space from a Space Template, pick the Template, and name the new Space.
Then switch to it with the Space switcher and look around: its Pipelines, Datasets and Dashboards are already there.

## Which edition has it

Every edition can create Spaces from Space Templates; which Templates ship depends on the edition. Basic ships the
**Orders starter** and the Link Analysis Templates, one for each of many industries. Standard and Advanced add more
industry Templates — for example **Business assurance**, **AML**, **Mobile money**, **Payment fraud starter**,
**Partner settlement** and **Telecom fraud** — and **Link Analysis daily feed**, which runs on a schedule.
See [The editions on one page](../../../../EDITIONS.md).

## Read next

- [Space](user-guide-concept-space.md)
- [App](../../layer/product/user-guide-concept-app.md)
