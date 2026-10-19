---
type: Guide
title: Action Requests — admin guide
description: Enabling the Action Requests module, who may approve, the dispatch flags, the Egress Allowlist, storage and common failures.
resource: features/inspecto-action-requests/src/main/resources/META-INF/inspecto/module.toon
tags: [action-requests, admin-guide, egress, approvers, configuration]
timestamp: 2026-10-19T00:00:00Z
---

# Action Requests — admin guide

## Enabling

Optional module `inspecto-action-requests` (module id `action-requests`, feature id `actionRequests`,
`bindingTime: boot`), Standard edition and above; it `requires` the module `notify-channels`
([module.toon](../../../../../features/inspecto-action-requests/src/main/resources/META-INF/inspecto/module.toon)).
Absent, every `/action-requests*` route answers 503 naming the module, and a Decision Rule naming `invoke-api`
reports `unavailable`.

## Permissions

- Approve, decline, retry and mark-failed need `canApproveChanges`, held unscoped, by someone who is not a
  maker of the request (its author, or the co-authors of the raising Decision Rule).
- Under OIDC the approver must also be on the Space roster `approvers.toon`; absent = nobody may approve.
- On Basic (no Subject) `approverCheck` reads `none-eligible`. Detail: [spec, *Approval*](spec.md).

## Configuration

| Key | Default | Meaning |
|---|---|---|
| `-Daction.dispatch.maxAttempts` | 3 (clamped 1..10) | attempts per dispatch |
| `-Daction.dispatch.backoffMs` | 1000, doubling, cap 30 s | backoff between attempts |
| `-Daction.dispatch.stuckAfterMinutes` | 15 | idle time before `mark-failed` is allowed |
| `egress.toon` (Space; `GET`/`PUT /settings/egress`, PUT `canAdminister`) | empty | Egress Allowlist; lifts private / CGNAT classes only |
| `approvers.toon` (Space) | absent | approver roster under OIDC |
| Approval Policy `expiresAfterHours` | 168 | a `pending` request becomes `expired` |

Each attempt uses the target Connection's `timeout_seconds`. Detail: [architecture § 5](architecture-overview.md).

## Storage and retention

One HMAC-signed JSON per request under `<write-root>/action-requests/`, signed with the Pending Change key.
Reserved from import, skipped by Space export, never in a backup: a restored Space has no request history, and
the `action-request.*` audit rows are the durable trail.

## Common failures

| Symptom | Cause |
|---|---|
| 503 on `/action-requests*` | module not in the bundle |
| `approverCheck: none-eligible`, WARN audit `action-request.no-eligible-approver` | no non-maker holds `canApproveChanges`, or the OIDC roster is empty; nothing auto-declines |
| status `invalid` | the record's MAC failed; never decidable |
| send refused before connect | target host not allowed by the egress policy (loopback, link-local and this host can never be allowlisted) |
| request stuck `dispatched` after a restart | never re-sent automatically; `mark-failed` after the stuck timeout, then retry |
| a Decision Rule raised nothing, audit `action-request.skipped-unknown-makers` | the rule's makers could not be named (fail closed) |
