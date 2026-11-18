---
type: Guide
title: Attribute-based access policy — user guide
description: Read, author and test Access Policies on the Settings > Access > Policies screen.
resource: providers/inspecto-policy/
tags: [module, policy, abac, user-guide]
timestamp: 2026-10-19T00:00:00Z
---

# Attribute-based access policy — user guide

Screen: **Settings ▸ Access ▸ Policies**
(`inspecto-ui/src/app/modules/admin/access/access-policies.component.ts`, editor
`inspecto-ui/src/app/modules/admin/access/policy-form.dialog.ts`). Everyone can read it; authoring needs
the `canConfigureAccess` capability. Policies are enforced only in Advanced (and Preview) bundles; on other
editions the screen and the preview say so.

## The table

Every policy in force for the current Space:

* **authored** rows come from the Space's `access-policies.toon`;
* **built-in** rows are the seeded `space-isolation` and `space-isolation-rows` denies, never written to the file.

Warnings (an unknown capability, role or resource kind; a resource reference in a route-level policy; a
built-in being overridden) show inline on the row.

## Write a policy

Each policy has a **name**, an **effect** (`allow` or `deny`), an optional **target** (actions `read`,
`write`, `operate`; resource kinds) and an optional **when** condition, for example:

```
subject.department == 'fraud' and env.action == 'write'
```

Operators: `== != in contains and or not ( )`. References: `subject.id`, `subject.capabilities`,
`subject.dataScopes`, `subject.roles`, allowlisted identity claims as `subject.<claim>`, `env.action`,
`env.route`, `env.space`, and `resource.<attribute>` for row-level policies.

Rules worth knowing:

* `deny` beats `allow`. An `allow` never grants a capability the role does not have.
* A `deny` with no target and no condition is refused (it would deny everything).
* Save is refused if the new set would lock **you** out of saving policies again.
* To change a built-in, create a policy with the same name; the confirm dialog says what the replacement loses.
* Saves replace the whole authored list and fail if someone else saved first; reload and retry.

## Why was I denied?

The **Why denied** panel runs `GET /access/explain` for your own session: the verdict, the policy that
decided it, and for every policy whether it was targeted and whether its condition held. It changes nothing.
