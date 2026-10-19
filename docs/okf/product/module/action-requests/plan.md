---
type: Capability
title: Action Requests — requirement of record
description: What was required of Action Requests (four-eyes approved outbound API calls), what is built, what is left and what was refused.
resource: features/inspecto-action-requests/
tags: [action-requests, plan, requirement, four-eyes, egress, decision-rule]
timestamp: 2026-10-19T00:00:00Z
---

# Action Requests — requirement of record

## Required

An outbound API call raised from an Incident or a Case — by a person, or by a Decision Rule's `invoke-api`
consequence — that is sent only after a *different* person approves it, sent once to a registered `https`
Connection under one idempotency key, with every answer recorded on the request
(`ASSURE-ACTION-REQUESTS-1`, assurance wave 2; [BACKLOG](../../../../BACKLOG.md) "Startable now" table).

## Built

- `ASSURE-ACTION-REQUESTS-1` shipped 2026-09-27 ([BACKLOG](../../../../BACKLOG.md)). Mechanism: [spec](spec.md);
  overview: [architecture](architecture-overview.md).
- `WEBHOOK-EGRESS-POLICY-1` (P2, closed 2026-09-27): the dispatcher, the `sink.webhook` Step and the webhook
  channel all pass the egress policy with a pinned connect ([spec, *The egress policy*](spec.md)).
- Approver roster and `approverCheck` (2026-09-28 / 2026-10-04): [spec, *Approval*](spec.md).
- MODULE-REORG-P7 (2026-10-08): the surface became the optional module `inspecto-action-requests`
  (module id `action-requests`); absent, every `/action-requests*` route answers 503
  ([spec, *The module seam*](spec.md)).

## Left

P3 `ASSURE-ACTION-REQUESTS-RESIDUALS-1` was closed 2026-10-06; its limits are kept as known gaps, not rows:
no per-request path below the Connection's base path, and no request history in a restored Space
([spec, *Deferred / known gaps*](spec.md); [architecture § 9](architecture-overview.md)).

## Refused

- Holding a request also as a Pending Change, or making four-eyes an Approval Policy option: one call, one
  approval, always on ([architecture § 4](architecture-overview.md)).
- Automatic re-send of a request left `dispatched` by a process stop; an approver must `mark-failed`, then retry.
