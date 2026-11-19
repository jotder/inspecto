---
type: Concept
title: Space
description: What a Space is — the isolated project that owns your data, configuration and audit trail — and how you work with Spaces in the console.
item: module.config
audience: external
status: current
verified: 2026-11-19 last-edit
code: platform/inspecto-config
---

# Space

## What it is

A **Space** is an isolated project inside one Inspecto installation. It owns everything you build for that project:
its Connections and Collectors, Pipelines, Datasets, Queries, Widgets and Dashboards, Alert Rules, Incidents and
Cases, its settings and its audit trail.

What happens in one Space cannot be seen from another. Two teams can share one installation, each in its own Space,
without seeing each other's data.

```
  Inspecto installation
  ├─ Space "Revenue assurance"   Connections · Pipelines · Datasets · Dashboards · Incidents · audit trail
  ├─ Space "Fraud"               Connections · Pipelines · Datasets · Dashboards · Incidents · audit trail
  └─ Space "Sandbox"             ...
```

## Why it exists

Projects differ in data, people and risk. A Space keeps them apart, so a change in one project cannot break
another, a person sees only the projects they are admitted to, and each project keeps its own record of who changed
what.

## How it relates to its neighbours

- A **[Space Template](user-guide-concept-space-template.md)** is a ready-made starting point; a Space created from
  it is your own copy, which you then change freely.
- Every **[Dataset](../../layer/product/user-guide-concept-dataset.md)**,
  **[Pipeline](../../module/elt/user-guide-concept-pipeline.md)** and
  **[Dashboard](../../layer/product/user-guide-concept-widget-dashboard.md)** belongs to exactly one Space.
- What each **[Role](../../module/access/user-guide-concept-roles-access-policies.md)** may do is defined per Space,
  so the same Role can allow different things in different Spaces.
- An **[App](../../layer/product/user-guide-concept-app.md)** can bring its content as Space Templates.

## Who may enter a Space

Where people sign in (Standard and Advanced), a Space that a signed-in person creates is **closed**: it admits only
its creator, administrators, and people who hold a Role that the Space itself defines. To anyone else it looks as if
it does not exist. A Space without that setting is **open**: every signed-in person can read it.

On Basic nobody signs in, so whoever can reach the server can use every Space.

## What you do with it in the console

- **Switch Space** with the Space switcher in the top bar. It appears when the server hosts more than one Space.
  Switching reloads the console in the new Space, so every screen shows only that Space's data.
- **Create, edit or delete a Space** in **Settings → Spaces**. Create it empty or from a Space Template. Deleting
  can also remove the Space's data.
- **Export or import a whole Space** as a zip from the same screen, with a preview before anything is imported.
- **Turn features on or off for this Space** in **Settings → Modules**.

## Which edition has it

Spaces are in every edition. Closed Spaces need sign-in, so they are a Standard and Advanced feature. See
[The editions on one page](../../../../EDITIONS.md).

## Read next

- [Space Template](user-guide-concept-space-template.md)
- [Roles and Access Policies](../../module/access/user-guide-concept-roles-access-policies.md)
- [How the pieces fit](../../layer/product/user-guide-concept-overview.md)
