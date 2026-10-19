---
type: Guide
title: Attribute-based access policy — admin guide
description: Install, configure Space isolation and identity claims, edit access-policies.toon, audit decisions and recover from a broken policy document.
resource: providers/inspecto-policy/
tags: [module, policy, abac, admin-guide]
timestamp: 2026-10-19T00:00:00Z
---

# Attribute-based access policy — admin guide

## Install

`inspecto-policy.jar` is staged in Advanced and Preview bundles (`offerings/advanced.toon`); build it with the
`edition-advanced` Maven profile. There are no `-D` flags: the jar's presence turns the engine on. It needs a
signed-in Subject, so it pairs with [oidc](../oidc/admin-guide.md) (or [demo-auth](../demo-auth/admin-guide.md)
in a demo).

## Configure

| File (per Space config root) | What |
|---|---|
| `access-policies.toon` | Authored policies: `name`, `effect` (allow or deny), `target: {actions, resourceKinds}`, `when` |
| `roles.toon` → `identity.attributeClaims` | Which token claims become `subject.<claim>` (nothing else from the token is visible to policies) |

Edit through Settings ▸ Access ▸ Policies or `PUT /access/policies` ([api-access](../access/api-access.md)),
which validate before writing. A hand edit is also accepted but is checked on read.

### Space isolation

The built-in denies engage only when a `space` claim is allowlisted in `identity.attributeClaims` and the IdP
puts the user's home Space in it. Without that mapping there is no isolation (by design, rather than a
blocked API). Holders of `canConfigureAccess` are exempt. To tailor it, author a policy named
`space-isolation` or `space-isolation-rows`.

### PII masking

The same jar enables the pipeline `mask` Step (`PII_MASK`). Bundles without it refuse that Step with
`ERR_EDITION_FEATURE`.

## Operate

* **Audit** — every policy `DENY` and every route-level policy-matched `ALLOW` is an event
  (`access.denied` / `access.granted`, category `authorization`, naming the policy); read with
  `GET /events?type=ACCESS_DENIED` or `AUDIT`.
* **Explain** — `GET /access/explain?route=&method=&resourceKind=` is a dry run for the caller's own session.
* **Preview** — `POST /access/policies/preview` shows the impact of a draft before saving.
* **Back up** — `access-policies.toon` and `roles.toon` travel with the Space's config.

## Failure modes

| Symptom | Cause and fix |
|---|---|
| Every call 403s; log "access-policies doc unreadable — DENY … (fail-closed until fixed)"; audit names `<policies-unreadable>` | The file on disk is damaged or fails validation. Fix or restore it from backup; `GET /access/policies` names the failing policy and check. |
| Save refused 422 `would-lock-out` | The draft would deny the saver the next policy save. Narrow the policy or exempt `canConfigureAccess`. |
| Save refused 422 `unknown-ref` | The `when` uses a `subject.<claim>` not in `identity.attributeClaims`, or an `env` key other than `action`, `route`, `space`. |
| A policy `allow` does not grant access | By design: allow never bypasses capability gates. Grant the capability through the role. |
| Users see other tenants' Spaces | No `space` claim mapped, or the users hold `canConfigureAccess`. |
| Policies have no effect | Not an Advanced bundle (no `inspecto-policy.jar` in `modules.list`). |
