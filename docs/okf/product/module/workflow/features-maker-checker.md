---
type: Feature
title: "Maker-checker (Pending Changes) — features"
description: "Maker-checker holds a config change for a second person's approval."
tags: [workflow, maker-checker, features, presales]
timestamp: 2026-10-19T00:00:00Z
---

# Maker-checker (Pending Changes) — features

**Availability:** Offering: every edition; deciding needs an Authenticator, so on Basic it cannot be switched on through the API.

## What it does

Maker-checker holds a config change for a second person's approval. When the Space's **Approval Policy**
requires approval for a kind, an authoring route does not write: it stores a **Pending Change** and answers
`202 {status: pending, written: false, pendingChange}`. A person holding the approver capability then approves
(the original request is replayed) or declines it. The author cannot decide their own change; they can withdraw it.

Code: `inspecto/src/main/java/com/gamma/control/PendingChanges.java` (hold, store, replay check, expiry),
`inspecto/src/main/java/com/gamma/control/PendingChangeRoutes.java` (HTTP),
`inspecto/src/main/java/com/gamma/control/ApprovalPolicy.java` (policy and the governable kinds),
`inspecto/src/main/java/com/gamma/control/ApproverCheck.java` and
`inspecto/src/main/java/com/gamma/control/ApproverRoster.java` (who may decide).

## Where it fits

Core control plane (`inspecto` module), not an add-on. It sits between every config-writing route's validation
gates and its first side effect. The same approver-eligibility reading (`ApproverCheck`) and the same per-Space
HMAC key are reused by [Action Requests](../action-requests/architecture-overview.md), which has its own mandatory approval and is never
held as a Pending Change.

More: [screens](user-guide-maker-checker.md) · [API](api-maker-checker.md) · [architecture](architecture-maker-checker.md).
