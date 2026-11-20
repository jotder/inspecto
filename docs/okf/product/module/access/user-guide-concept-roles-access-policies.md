---
type: Concept
title: Roles and Access Policies
description: What a Role and an Access Policy are, how they decide what each signed-in person may see and do, and how they differ from a Lens.
item: module.access
audience: external
status: current
verified: 2026-11-20 code-reviewed
code: platform/inspecto-access
---

# Roles and Access Policies

## What they are

A **Role** is a named set of permissions that a person holds — for example *Admin*, *Operations* or *Pipeline
Developer*. Each permission allows a kind of action: authoring Pipelines and other Workbench content, operating Runs,
writing Alert Rules, working Incidents, configuring access, revealing masked values. A person can hold several Roles
and may do what any of them allows.

An **Access Policy** is a finer statement on top of Roles: **allow** or **deny** an action when a condition about the
person, the thing they are reaching, or the circumstances is true — for example "deny reading rows of a region other
than the person's own". Access Policies can reach down to single rows. They can only **narrow** what Roles
allow; they never grant more.

```
  your identity provider ─▶ who you are, and which Roles you hold
                                   │
            Role definitions (per Space) ─▶ what those Roles may do
                                   │
                 Access Policies ─▶ narrow it further, down to rows   (Advanced)
                                   │
                                   ▼
                       what you can see and do
```

## Why they exist

Different people need different powers: an analyst reads and investigates, a builder changes Pipelines, an
administrator decides who may do what. Roles give each person what their work needs and no more. Access Policies
handle the cases Roles are too coarse for, such as a team that may see only its own region's rows.

## Who decides what

- **Who holds which Role** is decided in your organisation's identity provider (single sign-on). Inspecto reads it
  when you sign in.
- **What each Role may do** is defined in Inspecto, per [Space](../../module/config/user-guide-concept-space.md).
  The installation ships starting Roles; an administrator can change them, add new ones, or revert a changed Role to
  its shipped definition. Changes apply from the next request.
- A **closed Space** admits only its creator, administrators, and holders of a Role the Space defines.

## A Role is not a Lens

The **View as** switch in the top bar (Business, Builder, Ops) is a **Lens**: it changes where the console opens and
which actions it puts first. It is a convenience you switch freely. It never grants or removes a permission — that is
what Roles do, and the server enforces them.

## How they relate to their neighbours

- Some actions need a second person's approval (four-eyes), for example sensitive changes and outbound actions. Who
  may approve is set in **Settings → Approvers**.
- Values marked as sensitive are **masked**; revealing one needs a Role that allows it, and the reveal is recorded in
  the audit trail.

## What you do with them in the console

**Settings → Access** shows the Role table — each Role and what it may do — and, on Advanced, the Access Policies.
With permission to configure access you can edit a Role, add one, or revert it, and write Access Policies.

## Which edition has it

- **Basic** has no sign-in: whoever can reach the server may do everything.
- **Standard** adds sign-in through your OpenID Connect identity provider, Roles from its claims, enforced masking and
  audited reveal, and four-eyes approval.
- **Advanced** adds Access Policies.

See [The editions on one page](../../../../EDITIONS.md).

## Read next

- [Space](../../module/config/user-guide-concept-space.md)
- [Edition and Add-on](../../layer/product/user-guide-concept-edition-addon.md)
