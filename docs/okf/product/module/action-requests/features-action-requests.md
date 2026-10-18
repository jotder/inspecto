---
type: Feature
title: "Action Requests — features"
description: "An **Action Request** is an outbound API call raised from an Incident or a Case - by a person, or by a Decision Rule's invoke-api consequence."
tags: [action-requests, action-requests, features, presales]
timestamp: 2026-10-19T00:00:00Z
---

# Action Requests — features

**Availability:** Offering: Standard and Advanced (optional module `inspecto-action-requests`, module id `action-requests`).

## What it does

An **Action Request** is an outbound API call raised from an Incident or a Case - by a person, or by a Decision
Rule's `invoke-api` consequence. Nothing is sent until a *different* person approves it. The dispatcher then
sends it once to a registered `https` Connection, retrying on failure under one idempotency key, and records
every answer on the request.

Code: `features/inspecto-action-requests/src/main/java/com/gamma/actionrequests/` -
`ActionRequests.java` (model + store), `ActionDispatcher.java` (sending), `ActionRequestRoutes.java` (HTTP),
`InvokeApiConsequence.java` (the Decision Rule consequence). Core: `ApproverCheck`, `ApproverRoster`.

## Where it fits

An optional feature module; its manifest is
`features/inspecto-action-requests/src/main/resources/META-INF/inspecto/module.toon`. Absent, every
`/action-requests*` route answers 503 naming the module and a rule's `invoke-api` reports `unavailable`. The
wire is `inspecto-notify-channels` (`HttpWebhookSinkTransport`, pinned connect). Linked Incidents / Cases are
resolved through the core `LinkedSubjectProvider` seam that `inspecto-ops` fills.

More: [screens](user-guide-action-requests.md) · [API](api-action-requests.md) · [architecture](architecture-overview.md).
