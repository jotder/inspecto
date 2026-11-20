---
type: Concept
title: "Learning Path — Operator"
description: "The order in which an operator or administrator reads the Handbook: editions and sign-in, the run-it tutorial, then install, reverse proxy, identity provider, Roles and Access Policies, backup and restore, upgrade and audit verification, with a self-check at the end of each stage."
item: layer.product
audience: external
status: current
verified: 2026-11-20 last-edit
---

# Learning Path — Operator

You install Inspecto, connect sign-in, decide who may do what, keep it backed up and upgraded, and prove its audit
trail is intact. The Apps your builders and partners make run on what you operate.

**Time:** about a day and a half. **Edition:** Basic has no sign-in; Roles, the identity provider and four-eyes start
in Standard, Access Policies in Advanced.

## Stage 1 — Understand (about 1 hour)

1. [The editions on one page](../../../../EDITIONS.md) · [Edition and Add-on](user-guide-concept-edition-addon.md)
2. [How the pieces fit](user-guide-concept-overview.md) · [Space](../../module/config/user-guide-concept-space.md)
3. [Roles and Access Policies](../../module/access/user-guide-concept-roles-access-policies.md)

**Self-check 1.**

1. Which edition is the first with sign-in?
2. A Role grants capabilities. What does an Access Policy add?

## Stage 2 — Ship: install it (about half a day)

1. [Install an edition](../platform/admin-guide-howto-install-an-edition.md)
2. [Connect the identity provider](../../module/oidc/admin-guide-howto-connect-the-identity-provider.md)
3. [Put Inspecto behind a reverse proxy](../platform/admin-guide-howto-reverse-proxy.md)
4. [Define Roles and Access Policies](../../module/access/admin-guide-howto-define-roles-and-access-policies.md)

**Self-check 2.**

1. A user holding only the seed `admin` Role cannot create a Space from the Order-to-cash template. Why, and what do
   you give them?
2. Why does the server start on loopback first?

## Stage 3 — Manage (about 1 day)

1. [Tutorial: run it — install, sign-in, backup, upgrade and audit](../platform/user-guide-tutorial-run-the-platform.md)
   — the whole operator lifecycle on two servers, end to end.
2. [Back up and restore](../../module/backup/admin-guide-howto-back-up-and-restore.md)
3. [Upgrade the platform](../platform/admin-guide-howto-upgrade-the-platform.md)
4. [Verify the audit chain](../../module/event/admin-guide-howto-verify-the-audit-chain.md)
5. Keep at hand: the [operations guide](../../../../ADVANCED_GUIDE.md).

**Self-check 3.**

1. What does a Space backup hold today — configuration, data, or both?
2. You run the upgrade script with a relative backup folder and nothing is upgraded. What do you change?
3. Verifying the audit chain: console or Control API?

## Self-check answers

- **1.1** Standard. **1.2** It only narrows what the Roles allow — by who the person is, what they reach or the
  circumstances — down to single rows (Advanced).
- **2.1** Creating a Space from a template that holds an Alert Rule needs `canAuthorAlertRules`, which `admin` alone
  lacks; give them `power` as well. **2.2** The server listens on every interface unless told otherwise; on loopback,
  only the reverse proxy on the same machine reaches it.
- **3.1** Configuration; the Datasets themselves are not in the archive. **3.2** Give the backup folder as a full path.
  **3.3** The Control API (`/audit/verify`); there is no console screen for it yet.
