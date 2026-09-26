---
type: Concept
title: Action Requests — approved outbound API calls
description: An outbound call raised from an Incident or Case (or a Decision Rule's invoke-api), held for a four-eyes approval, then sent once to an https Connection with bounded retries under one idempotency key.
resource: inspecto/src/main/java/com/gamma/control/ActionRequestRoutes.java
tags: [control-plane, action-request, maker-checker, four-eyes, webhook, egress, decision-rule, incident]
timestamp: 2026-09-27T00:00:00Z
---

# Action Requests

An **Action Request** (`ASSURE-ACTION-REQUESTS-1`, WS-24 of the assurance plan) is an outbound API call that a
person — or a Decision Rule's `invoke-api` consequence — raises from an **Incident** or **Case**. Nothing is sent
until a *different* person approves it; then the dispatcher sends it to the named Connection, retrying on
failure under one idempotency key, and records every answer on the request, where the Incident shows it.
Professional and Enterprise only (the wire is `inspecto-notify-channels`); on Personal create answers 503.

Code: `ActionRequests` (model + store), `ActionDispatcher` (sending), `ActionRequestRoutes` (HTTP) — all in
`inspecto/src/main/java/com/gamma/control/`; the wire is `WebhookSinkTransport.exchange` implemented by
`HttpWebhookSinkTransport` (`inspecto-notify-channels`).

## Lifecycle

`draft → pending → approved → dispatched → succeeded | failed`, plus `declined` and `expired` (undecided past
the Approval Policy's `expiresAfterHours`, default 168). A `failed` request can be **retried**: back to
`dispatched`, same idempotency key, attempts counted cumulatively. A record that fails its integrity check reads
back as `invalid`. Every transition is appended to `history` with who and when. `draft` exists in the model and
the history, but create moves straight on to `pending` — there is no separate submit route.

## What is sent is fixed at creation

The request stores the **Connection id**, the **URL it resolved to** (`targetUrl`), the **method** (POST, PUT or
PATCH), the **rendered payload** and the **idempotency key** (the request id unless the author gave one). The
payload template is a JSON object rendered ONCE at creation: every string leaf goes through
`NotificationTemplate.render` (`{{incident.id}}`, `{{case.id}}`, `{{context.x}}`, `{{author}}`, `{{origin}}`),
so a value can never break the JSON. The approver reads exactly what will go out. A decision body carries only
`reason` — any other key is a **422** — so an approve or retry can never change the target, method or payload.

## Approval — the Pending Change four-eyes model, not the policy

- `POST /action-requests` needs `canWorkIncidents`; approve / decline / retry need **`canApproveChanges`** (reused,
  no new capability, so `Roles.SEED` is unchanged).
- Deciding needs an authenticated Subject (403 without one — author and approver could not be told apart).
- **Four-eyes is always on**: the author approving or declining their own request is **403**. It is not a
  policy option. Retry is not four-eyes: it re-sends content already approved.
- 🔴 **Not under the Approval Policy.** An Action Request is not config and carries its own mandatory approval;
  `PendingChanges.hold` is never reached and no policy kind names it. Holding the proposal as a Pending Change
  as well would make one call need two approvals.

## Integrity — the Pending Change key, domain-separated

Each record is one JSON document at `<write-root>/action-requests/<id>.json` (the Pending Change store pattern:
atomic temp + move, path-jailed, fail closed on an unreadable file). It is signed with the **same per-Space
HMAC key** Pending Changes use (`<config>.secrets/.pending-changes.key` — no second key) through
`PendingChanges.domainMac(root, "action-request", rec)`, which prefixes the domain inside the MAC input, so an
Action Request can never verify as a Pending Change or the reverse. A record whose MAC fails — or that names
another id or record type than its file — reads back `invalid`: shown, never decidable (409), never
dispatched, and **never re-saved** (`save` refuses, which would sign a forgery). The dispatcher re-verifies
before EVERY attempt and before every write-back. ⚠ As for Pending Changes, the MAC defends against a record
written through a door that cannot read the key (an import, a forged upload), not against a local administrator.

Not an OperationalDb family, so no backup / bundle-staging lockstep: the documents ride in the config tree a
Space backup carries, and the key stays in the `.secrets` sibling the backup skips.

## Dispatch and egress

`ActionDispatcher.submit` resolves the Connection on the request thread (it carries the Space) through
**`WebhookSink.endpoint`** — the webhook sink's egress rules, extracted rather than copied: a registered
`https` Connection (onboarded under the admin-only `canOnboardConnections`), a host, **no tunnel or proxy**, the
bearer token resolved from its secret reference at send time. It then refuses if the Connection now resolves
to anything other than the approved `targetUrl`. **No SSRF / allowed-host policy exists beyond that in this
codebase**, so the Connection's base URL IS the allow-list: the client never follows redirects
(`Redirect.NEVER`) and a 3xx fails the request at once, without a retry.

Retries: up to `-Daction.dispatch.maxAttempts` (default 3, clamped 1..10), backoff from
`-Daction.dispatch.backoffMs` (default 1000 ms, doubling, capped at 30 s), each attempt under the Connection's
`timeout_seconds`. Retryable: I/O failure or timeout, 5xx, 408, 429; anything else fails at once. The
`Idempotency-Key` header is identical on every attempt and every retry. The last response (status, a 1 KiB
body excerpt, the error, the attempt) is kept on the record; nothing logs a payload, a token or a body.

## The Decision Rule `invoke-api` consequence

Applying a rule with `invoke-api` no longer emits a stub Signal: it **proposes a pending Action Request** on
the rule's open Incident (correlation `decision-rule:<rule>`, opened when none is), with
`params.connection`, `params.method` (default POST) and `params.payload` (default
`{incident: {{incident.id}}, rule: {{context.rule}}}`). Deduped while one is pending on that Incident. The
author is the person applying the rule, or `decision-rule:<rule>` for an engine-fired application. ⚠ Breaking:
the consequence takes a Connection id, never a `url` — a URL was never an authorable egress target.

## Routes

| Route | Gate | |
|---|---|---|
| `GET /action-requests[?status&incidentId&caseId]` | Space access | newest first, capped at 500 with `total` + `truncated`; no payload |
| `GET /action-requests/{id}` | Space access | with the rendered payload |
| `POST /action-requests` | `canWorkIncidents` | 503 no write root → 422 body → 503 no ops module → 404 linked object → 503 no transport → 422 Connection → 413 payload > 64 KiB |
| `POST /action-requests/{id}/approve` · `/decline` | `canApproveChanges` | Subject 403 → 503 → 422 body key / id → 404 → 409 invalid → 409 not pending → 403 four-eyes |
| `POST /action-requests/{id}/retry` | `canApproveChanges` | as above, but the request must be `failed` |

## UI

The **Action Requests** inbox (`/action-requests`, Operations nav, the Pending Changes inbox pattern) lists
waiting / all requests; selecting one shows target, key, attempts and the exact payload, with Approve / Decline
(pending) or Retry (failed) for a `canApproveChanges` holder. The Incident / Case detail page carries an
**Action Requests** panel: status badge, attempts, last response and Retry.

## Deferred / known gaps

- A request left `dispatched` by a process stop is not resumed at boot, and retry accepts only `failed` — it
  stays `dispatched`, and no route recovers it yet.
- No per-request `path` below the Connection's base path: one Connection per endpoint.
- Tests drive the dispatcher over a loopback **test** wire (plain http, same `Redirect.NEVER` client); the real
  wire's redirect refusal is pinned in `HttpWebhookSinkTransportTest`.
