---
type: Concept
title: App
description: What an App is — a finished business application on Inspecto, such as Revenue Assurance or Link Analysis — and how it differs from a Space Template and an Add-on.
item: layer.product
audience: external
---

# App

## What it is

An **App** is a finished business application built on Inspecto: it does one job end to end. Some Apps are made for
one industry; others work in any industry.

| App | Industry | The job |
|---|---|---|
| **Revenue Assurance** | Telecom | find revenue that leaks between your systems |
| **Fraud Management** | Telecom | detect and work fraud |
| **Link Analysis** | any | investigate how people, accounts and numbers connect |
| **Case Management** | any | work Alerts, Incidents and Cases to a decision |

An App brings together:

- its **content** — one or more [Space Templates](../../module/config/user-guide-concept-space-template.md) with the
  Pipelines, Datasets, checks and Dashboards the job needs;
- its own **landing page and menus**, so people see the App's screens, not a general toolbox;
- its **personas**, mapped to [Roles](../../module/access/user-guide-concept-roles-access-policies.md);
- the **[Add-ons](user-guide-concept-edition-addon.md)** it needs to run;
- a **version**, and the range of Inspecto versions it works with.

## Why it exists

Inspecto on its own is a platform: it can do many things, but you have to decide what to build. An App is the
answer already built for a common job. You install it, connect your data, and start working.

## How it relates to its neighbours

```
  Product "Inspecto Telco"
    ├─ App Revenue Assurance ─▶ Space Template "Telecom revenue assurance" ─▶ your Space
    ├─ App Case Management
    └─ (optional) Fraud Management, Link Analysis
```

- An App is **not a Space Template**: an App installs one or more Space Templates, and adds the menus, landing and
  roles around them.
- An App is **not an Add-on**: an Add-on is a general capability (Incidents, Reconciliation, Scoring …) that any App
  or any Space can use. An App *requires* Add-ons.
- What you buy is a **product** made of Apps: *Inspecto LA* is the Link Analysis App on its own; *Inspecto Telco*
  brings Revenue Assurance and Case Management, with Fraud Management and Link Analysis as options.

## What you do with it

An administrator installs, upgrades or removes an App on your installation. Once it is installed, people use the
App's own landing page and menus, and builders work in the Spaces created from its Space Templates like in any
other Space.

## Which edition has it

An App runs in the editions that include the Add-ons it requires. Link Analysis runs in every edition. Revenue
Assurance, Fraud Management and Case Management need Add-ons that start in Standard (for example Incidents,
Reconciliation and Case Management), so they need Standard or Advanced. See
[The editions on one page](../../../../EDITIONS.md) and [Edition and Add-on](user-guide-concept-edition-addon.md).

## Read next

- [Edition and Add-on](user-guide-concept-edition-addon.md)
- [Space Template](../../module/config/user-guide-concept-space-template.md)
