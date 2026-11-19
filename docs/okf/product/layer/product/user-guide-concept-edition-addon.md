---
type: Concept
title: Edition and Add-on
description: What an edition is (Basic, Standard, Advanced) and what an Add-on is, and how together they decide which features your installation has.
item: layer.product
audience: external
status: current
verified: 2026-11-19 last-edit
---

# Edition and Add-on

## What they are

An **edition** is how much of Inspecto your installation is built with. There are three:

- **Basic** — for one analyst on one machine. Add data, explore it, find links. Nobody signs in.
- **Standard** — for a team. Everything in Basic, plus sign-in and Roles, Incidents and Cases, Investigations,
  automatic data feeds, the Geo map and the Assistant.
- **Advanced** — for an organisation. Everything in Standard, plus Access Policies and shared PostgreSQL storage
  for several servers.

Every edition runs the same engine and gives the same answers. An edition never sells weaker analysis; it differs in
who uses Inspecto, how much data it handles, and how much governance surrounds it.

An **Add-on** is a general capability, sold on its own, that any industry can use. Examples: **Incidents**, **Case
Management**, **Reconciliation**, **Scoring & Lists**, **Action Requests**, **Regulatory Reporting**, **Workflow &
SLA**, **Link Analysis Investigations**, **AI Assist & Intelligence**, **Integration & Delivery** and **Premium Connectors**.

```
  edition  = who uses it and how it is governed    Basic ─▶ Standard ─▶ Advanced
  Add-on   = what extra it can do                  Incidents · Reconciliation · Scoring & Lists · …
  App      = a finished job built on both          Revenue Assurance · Link Analysis · …
```

## Why there are two

They answer different questions. The edition answers *who is using this and how carefully is it governed* — one
person, a team, or a whole organisation. An Add-on answers *what job does it need to do* — reconcile two systems,
score risk, work Cases. Keeping them apart means you do not have to buy governance you do not need to get a
capability you do, or the other way round.

## How they relate to their neighbours

- An **[App](user-guide-concept-app.md)** requires certain Add-ons, so it runs in the editions that include them.
- Most Add-ons start in Standard. The Investigations Add-on, for example, is included by default in Standard and
  Advanced and is never part of Basic.
- **[Roles and Access Policies](../../module/access/user-guide-concept-roles-access-policies.md)** come with the
  edition, not with an Add-on.

## What you see in the console

A feature your installation does not include is shown **locked**, with a one-line reason and the edition that has
it, rather than failing with an error. Each Space can also turn installed features on or off in
**Settings → Modules**. To find out which edition you run, ask your administrator: the bundle names it.

## Where to read the details

[The editions on one page](../../../../EDITIONS.md) lists every feature by edition, the Investigations Add-on, and
what changes when you upgrade.

## Read next

- [App](user-guide-concept-app.md)
- [Roles and Access Policies](../../module/access/user-guide-concept-roles-access-policies.md)
