---
type: Feature
title: "Identity and Access — screens"
description: "Where Identity and Access lives in the UI and what each screen does."
tags: [access, access, user-guide]
timestamp: 2026-10-19T00:00:00Z
---

# Identity and Access — screens

Screens for Identity and Access. Design and internals: [Identity and Access architecture](architecture-overview.md).

- **Sign-in** — `inspecto-ui/src/app/inspecto/auth/sign-in.component.ts`: SSO button, or the Demo User picker
  with a "not secured" warning in a demo build.
- **Admin → Access** — `inspecto-ui/src/app/modules/admin/access/access.component.ts`, with the role table
  (`access-roles.component.ts`) and Access Policies (`access-policies.component.ts`, `policy-form.dialog.ts`).
- Capability-driven visibility in the SPA comes from `LensService`; see [Security §3.14](plan-security.md).
