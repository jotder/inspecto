---
type: How-to
title: "Define Roles and Access Policies"
description: "Author a Space's own Roles — capabilities and data scopes — and its Access Policies, in Settings → Access, and see what a Role is allowed to do."
item: module.access
audience: external
status: current
verified: 2026-11-20 code-reviewed
code: platform/inspecto-access
---

# Define Roles and Access Policies

## Goal

Decide what each kind of person may do in a Space: author **Roles** (sets of capabilities and data scopes) and, on
Advanced, **Access Policies** that allow or deny actions under conditions.

## Before you start

- **Edition:** Roles take effect on Standard, Advanced and Preview — Basic has no sign-in, so everyone may do
  everything. Access Policies are saved on any edition but **enforced only on Advanced** (and Preview).
- **Who:** a person with `canConfigureAccess` in the Space — the built-in `admin` Role has it.
- **What you need:** sign-in through your identity provider
  ([Connect the identity provider](../oidc/admin-guide-howto-connect-the-identity-provider.md)). People get Roles
  there, by name; Inspecto defines what a Role may do, never who holds it.

Concept: [Roles and Access Policies](user-guide-concept-roles-access-policies.md).

## Steps

### Author a Role

1. Open **Settings → Access**, tab **Roles**. Each card shows a Role, marked **Built-in** or **Authored**.
2. Click **New role**. Give a **Role name**, tick its **Capabilities** (for example `canAuthorWorkbench`,
   `canOperateRuns`, `canWorkIncidents`) and, if it should see only part of the data, its **Data scopes**.
3. Click **Save**. To change a built-in Role for this Space, use **Edit** on its card; **Revert to built-in
   defaults** undoes that.
4. At the identity provider, give the Role's name to the people who should hold it.

In a **closed** Space only its creator, administrators and holders of a Role **this Space defines** get in — so a
closed Space needs at least one authored Role for its members.

### Author an Access Policy (Advanced)

1. Same screen, tab **Policies**, click **New policy**.
2. Give a **Policy name**, an **Effect** (**Deny** or **Allow**), the **Actions** it covers (none ticked means
   every action) and, optionally, a **Condition (when)** on the person or the data. Click **Preview impact** to see
   what it would change before you save.
3. Click **Save**. A built-in policy offers **Override…** instead.

## Check it worked

The Space lists your Role as authored, and its effective capabilities show what it may do (here a Role named
`collections`):

```check
GET /spaces/{space}/access/roles
as admin
expect status 200
expect data.roles[*].name has collections
```

```check
GET /spaces/{space}/access/roles/collections/effective
as admin
expect status 200
expect data.role = "collections"
```

## What can go wrong

- The screen is read-only with *Editing role definitions requires the access-configuration capability* — you lack
  `canConfigureAccess`.
- 422 on **Save** — an unknown capability name, more than 200 Roles, or a policy condition the grammar refuses.
- A policy save is refused when it would lock you out of the Space.
- *Role grants suspended* — the Space's Role file cannot be read, so every grant is off until it is fixed (fail
  closed).
- People sign in but see nothing — the role names in their token do not match a Role (check spelling at the
  provider).
- If an approval policy covers access changes, **Save** waits for an approver.

## Next

- [Connect the identity provider](../oidc/admin-guide-howto-connect-the-identity-provider.md)
- [Call the Control API with a machine client](../oidc/admin-guide-howto-machine-client.md)
