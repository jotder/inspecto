---
type: Concept
title: Action Requests — approved outbound API calls
description: An outbound call raised from an Incident or Case (or a Decision Rule's invoke-api), held for a four-eyes approval, then sent once to an https Connection with bounded retries under one idempotency key.
resource: inspecto/src/main/java/com/gamma/control/ActionRequestRoutes.java
tags: [control-plane, action-request, maker-checker, four-eyes, webhook, egress, decision-rule, incident]
timestamp: 2026-09-28T00:00:00Z
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

- `POST /action-requests` needs `canWorkIncidents`; approve / decline / retry / mark-failed need
  **`canApproveChanges`** (reused, no new capability, so `Roles.SEED` is unchanged).
- Deciding needs an authenticated Subject (403 without one — author and approver could not be told apart).
- **Four-eyes is always on**: the author approving or declining their own request is **403** — and so is any
  **co-author**: a request raised by a Decision Rule's `invoke-api` carries the rule's **makers** as `coAuthors`
  — every editor, read from the component version history, since the invoke-api consequence (connection, method,
  payload) last changed — so no maker of what the rule sends can approve or decline what it raised. A **version
  restore** (`POST /components/decision-rule/{id}/versions/{v}/restore`) makes the restorer the editor, so it also
  stamps the server-only `restoredMakers` — the restored version's own makers, read from the history at that
  version — and every chained version's `restoredMakers` join the set: the restored consequence's author can no
  more approve than the restorer. A body `restoredMakers` is discarded; a version whose makers the history cannot
  say is not restored (**409**, fail closed — save the rule instead). It is not a policy option. Retry and
  mark-failed are not four-eyes: they send nothing new.
- 🔴 **Not under the Approval Policy.** An Action Request is not config and carries its own mandatory approval;
  `PendingChanges.hold` is never reached and no policy kind names it. Holding the proposal as a Pending Change
  as well would make one call need two approvals.

### Is anyone left to approve it? — `approverCheck` (2026-09-28)

A pending request's read, list item and create response carry **`approverCheck`**, computed live on every read
(roles change while it waits) by `ActionRequestRoutes.approverCheck`, against the request's bound Space root (the
same root `ControlApi.dispatch` hands the Authenticator as `Roles.configRoot`); a list computes it after the store
lock, once per distinct maker set:

- **`none-eligible`** — no role in the Space's table grants `canApproveChanges` (deny grants applied, via
  `JobAuthority.capabilitiesNow`; every edition), **or** the Authenticator can enumerate its principals and every
  holder is the author or a co-author. Today only **Demo sign-in** enumerates (`Authenticator.principals`, a default
  method returning empty; `DemoAuthenticator` lists exactly what it authenticates: the bound Space's
  `demo-users.toon` plus every hosted Space's via `DemoTokenRelay.known()`).
- **Under OIDC (no principal directory) the Space's approver roster answers** (operator 2026-10-04, see
  *Approver roster* below): an empty roster is `none-eligible`, a listed group or a listed non-maker user is `ok`.
- **`unknown`** — enumerating fails (a corrupt `demo-users.toon`):
  logged at most once per root per 10 minutes, never a 500 — the request is already saved when the check runs.
- **`none-eligible` on Personal** too: no Authenticator ⇒ no Subject ⇒ deciding is always 403. Its raise-time
  audit event is **INFO**, not WARN: it holds for every request by construction, so a warning would be noise.
- **`ok`** — an enumerated non-maker holds `canApproveChanges` **unscoped**: none of their roles carries a data
  scope (or is a `case:` role) and the Space has no authored Access Policy. Deciding also needs `visible()` (the
  linked Incident / Case passes the approver's data scope and row policy), which cannot be evaluated without their
  request — so a non-maker holder who is scoped, or any Access Policy, reads **`unknown`**, never `ok`.
- A pending record whose MAC fails reads `unknown` (its makers cannot be trusted).
- ⚠ The built-in `space-isolation` policy (`inspecto-policy`) is not considered. It engages only when an IdP
  `space` claim is mapped, and the only Authenticator that enumerates principals, and so the only one that can yield
  `ok` from roles, is Demo sign-in, which carries no claims. Under OIDC the roster answers instead.

### Approver roster (operator 2026-10-04)

Under an Authenticator with no principal directory (`Authenticator.principals` empty — OIDC; a throwing directory
counts too) eligibility is decided by a **per-Space approver roster**, `approvers.toon` in the Space's config root
(`ApproverRoster`, `users: [...]`, `groups: [...]`). **One list per Space**, not per approval kind: both consumers
decide on the same notion (the approve capability plus "not a maker"), so a per-kind split had nothing to key on.

- **Enforced, not advisory**: approve / decline of an Action Request and of a Pending Change (maker-checker) add
  `ApproverRoster.requireOnRoster` after the capability and four-eyes gates — 403 *"not on this Space's approver
  roster"*. The capability gate and the maker-can-never-approve rule still apply on top (a listed maker is 403
  four-eyes). Retry / mark-failed are not decisions and are not gated by it.
- **Fail closed**: absent, empty or unreadable roster admits nobody. A caller is admitted by `Subject.id()` or by any
  value of the `groups` Subject attribute — the IdP claim must be allowlisted in `roles.toon`
  `identity.attributeClaims`.
- **Demo sign-in keeps deciding on roles alone** (it enumerates); Personal has no Subject (already 403).
- `GET /settings/approvers` (ungated, adds `applies`), `PUT /settings/approvers` (`canAdminister`, 422 on unknown
  key / non-list / non-string / blank / control char / >256 chars / >500 entries; trimmed, de-duplicated; an absent
  key keeps its list; audited `approver-roster.changed`). Reserved from import (`ReservedConfigPaths`), so a bundled
  Space starts with an empty roster. Settings ▸ **Approvers** edits it.
- ⚠ Draft promote four-eyes (Link Analysis) is **not** a consumer: it decides on Investigation member roles
  (lead / reviewer), a different notion.

At raise time only (`propose`, so the route and the `invoke-api` consequence alike), a `none-eligible` answer emits
**one WARN audit event `action-request.no-eligible-approver`**; reads never re-emit. It is visibility only:
**nothing auto-declines** — four-eyes stays fail-closed and the request sits `pending` until it expires or a role is
granted. ⚠ `ok` from the Demo table ignores Access Grants tied to a request (`AccessGrants.deniedCapabilities` is
applied per role table only). The field is not in `openapi-v1.json`: every `/action-requests*` operation there is
still a generated skeleton.

## Who can read one

`GET /action-requests*` needs `canWorkIncidents` **or** `canApproveChanges` (checked literally in the handler — a
manifest entry names one capability). A request is visible exactly when its linked Incident / Case is
(`AnnotationTargets.objectVisibleTo`: the Subject's data scope on `caseType` and the row policy); otherwise it is
**absent — 404, never 403** — for reads and decisions alike. The target's response body (`lastResponse.bodyExcerpt`)
is withheld (`bodyRedacted: true`) from anyone without `canApproveChanges`; the status still shows.

## Integrity — the Pending Change key, domain-separated

Each record is one JSON document at `<write-root>/action-requests/<id>.json` (the Pending Change store pattern:
atomic temp + move, path-jailed, fail closed on an unreadable file). It is signed with the **same per-Space
HMAC key** Pending Changes use (`<config>.secrets/.pending-changes.key` — no second key) through
`PendingChanges.domainMac(root, "action-request", rec)`, which prefixes the domain inside the MAC input, so an
Action Request can never verify as a Pending Change or the reverse. A record whose MAC fails — or that names
another id or record type than its file — reads back `invalid`: shown to approvers, never decidable (409), never
dispatched, and **never re-saved** (`save` refuses, which would sign a forgery). The dispatcher re-verifies
before EVERY attempt and before every write-back. ⚠ As for Pending Changes, the MAC defends against a record
written through a door that cannot read the key (an import, a forged upload), not against a local administrator.

Not an OperationalDb family, so no bundle-staging lockstep. The store is **reserved from every import**
(`ReservedConfigPaths`: `action-requests/`), **skipped by a whole-Space export** (`BundleExporter.exportSpace`) and
**never archived by a backup** (`BackupTask`) — the records carry rendered payloads and target response excerpts.
⚠ So a restored Space has no Action Request history; the AUDIT rows are the durable trail.

## The egress policy

An Action Request may only reach where the **egress policy** (`EgressPolicy`, `inspecto-engine`) lets it:

1. **Host syntax** — at https Connection save, at Action Request create and in `WebhookSink.endpoint`: a DNS name
   or a CANONICAL IPv4 / IPv6 literal. Refused (422): userinfo (`trusted.example.com@attacker.example` reads as one
   host and dials another), decimal (`2130706433`), octal (`0177.0.0.1`), hex (`0x7f000001`), short (`127.1`) and
   leading-zero forms, zone ids, ports, whitespace. The built URL must parse back as the host.
2. **Address classes, deny by default** — before EVERY attempt the host is resolved once and every address is
   checked (an IPv6 address carrying an IPv4 — `::/96` except `::`/`::1`, `::ffff:0:0/96`, `64:ff9b::/96`,
   `64:ff9b:1::/48`, 6to4 `2002::/16` bits 16–47 — is classed by that IPv4, so `64:ff9b::a9fe:a9fe` is the
   metadata service; one denied answer among several refuses the host; the resolver is an injectable seam): loopback, link-local (`169.254.0.0/16` — the cloud metadata service — and `fe80::/10`), private (RFC 1918,
   `fc00::/7`, `fec0::/10`), CGNAT `100.64.0.0/10`, `0.0.0.0/8`, broadcast, multicast, unspecified, and any address
   of THIS host (which covers every address the control plane binds). A refusal fails the request at once, nothing
   sent, not retried.
3. **The Egress Allowlist** — `GET|PUT /settings/egress` (`egress.toon`, default empty; PUT is `canAdminister`,
   validated fail closed, audited as `egress-allowlist.changed` with before/after, reserved from imports; no
   approval-policy kind covers Space settings, so it is not held). An entry lifts **only the liftable classes** —
   private (RFC 1918, ULA, site-local) and CGNAT: a **host** entry for that exact name, a **CIDR** entry (or a bare
   IP literal, taken as /32 or /128) for its range. 🔴 **Never liftable**, by any entry: loopback, link-local
   (`169.254.169.254`, `fe80::/10`), unspecified, multicast, broadcast and this host — and a CIDR overlapping
   `0/8`, `127/8`, `169.254/16`, `224/4`, `255.255.255.255`, `::/127`, `fe80::/10` or `ff00::/8` is refused at
   `PUT /settings/egress` (422 naming the range), so `0.0.0.0/1` + `128.0.0.0/1` cannot reopen the metadata
   service and a name re-pointed by DNS rebinding cannot reach it. Real targets such as a CBS or a PCRF on private
   networks are allowed this way.
4. **Pinned connect** — the wire (`PinnedHttp` in `inspecto-notify-channels`, behind
   `WebhookSinkTransport.exchange(…, InetAddress connectTo, …)`) connects to the CHECKED address and never resolves
   the name itself, so rebinding cannot swap it between check and connect. The name still travels as the `Host`
   header, the TLS SNI and the name the server certificate is verified against (`endpointIdentificationAlgorithm =
   HTTPS` on an `SSLSocket` layered over the pinned socket). One HTTP/1.1 request, `Connection: close`, no
   redirects, the body read only up to the excerpt cap, CR/LF refused in every header value.

Each attempt's checked address is recorded (`attemptLog[]`: attempt, address, status, error — the last 50), and
the approver sees the host AS PARSED, the port, the path and whether the allowlist names it (`egress` on the
detail view, read live) before approving.

**Scope: all three outbound webhooks** (`WEBHOOK-EGRESS-POLICY-1`, 2026-09-27). The `sink.webhook` Step
(`WebhookSink` → `HttpWebhookSinkTransport.post`) and the webhook notification channel (`WebhookChannel`) go
through one helper, `WebhookEgress.post` (`inspecto-notify-channels`): host-syntax check → resolve once → every
address checked against the policy less the **current Space's** allowlist → `PinnedHttp` POST to the checked
address; non-2xx is a failure, a 3xx is never followed. Every refusal (bad host, no DNS answer, denied address)
throws `webhook egress refused: …` and dials nothing. The JDK `HttpClient` is gone from both. ⚠ A Step refusal is
retried by the Step's own `retry:` policy like any other failure (unlike an Action Request's); the result is the
same failed branch. ⚠ Loopback is never liftable, so a webhook to `localhost` / `127.0.0.1` that worked before is
now refused — by design.

**The object-store connectors too** (2026-09-27): S3 / GCS / Azure Blob (`AbstractHttpObjectStoreConnector.send`,
`inspecto-connectors`) apply the same resolve-once → policy (current Space's allowlist) → pinned-connect path over
their own wire, `PinnedObjectStoreHttp` (any method, the pre-signed headers, streamed bodies both ways — the capped
JSON `PinnedHttp` cannot carry an object). `Host` = the SigV4-signed value; SNI/certificate use the name; a 3xx is
never followed; a refusal is an `AcquisitionException` naming `egress refused: …` and dials nothing. Details in
[connectors](../acquisition/connectors.md).

**No writable config root ⇒ the launch config is trusted, in memory** (session decision 2026-09-27). A single-tenant
server or engine CLI with no `-Dassist.write.root` offers no way to change config through the product, so
`EgressAllowlist.bootDefaultSpace(launchRoot, loadedObjectStoreHosts)` builds an IN-MEMORY allowlist at boot from
the launch config's targets — object-store Connection hosts, `sink.webhook` Step Connection hosts, `WEBHOOK`
channel hosts (the same scan as the migration, over the launch dir) and `-Dnotify.webhook.url` — never persisted,
rebuilt each boot, and served by `forCurrentSpace()` only for the default Space when it has no root. With a
writable root the persisted one-time migration below applies unchanged (and the in-memory list is dropped). Wired
in `SpaceManager.single` (launch dir = `SpaceRoot.legacy().base()`, plus the service's loaded object-store
Connections), `CollectorService.main` (the launch dir) and `CollectorProcessor.main` (the pipeline file's
directory). ⚠ Other engine entry points (`MultiCollectorProcessor`, `MainApp`) are not wired: with no root they
stay deny-by-default.

**The allowlist reader** is `EgressAllowlist` (`inspecto-engine`, `com.gamma.pipeline.exec`): `of(root)` for a
caller holding the config root (the Action Request routes and dispatcher, via `EgressRoutes`), `forCurrentSpace()`
(`SpaceConfigRoot.current()`, the Space MDC) for the two webhooks. ⛔ **The read path never seeds**: no root or no
`egress.toon` ⇒ EMPTY (deny), with one `[EGRESS] … has no egress.toon` warning per root.

**One-time upgrade seeding** (operator decision 2026-09-27, "seed the allowlist"; tightened after adversarial
review the same day): `EgressAllowlist.migrate(root)` runs **at service start only** — `SpaceManager.discover` for
each Space it boots, `SpaceManager.single` for the default root. A Space with no `egress.toon` is seeded from the
hosts it targets at that moment — each `sink.webhook` Step's Connection host (a flat `webhook: {connection}` block
or a graph `sink.webhook` node, scanned from the config root's `*.toon`, depth 4, `.history` skipped; the host from
`<id>_connection.toon`, else `ConnectionRegistry`), each `WEBHOOK` channel's URL host (`registry/channels/`), and
the host of `-Dnotify.webhook.url` (what the channel actually posts to), and every object-store Connection's host
(any `*_connection.toon` under the root with `connector: s3 | gcs | azure` — a MinIO on a LAN keeps working; for
`SpaceManager.single` also the object-store hosts of the Connections loaded from the launch config, outside the
write root) — writes `{allow, seededAt}`, logs
`[EGRESS] seeded …` and emits the audit `egress-allowlist.seeded`. A seed host that can never be allowlisted (a
loopback literal) is skipped and logged. 🔴 **A Space created through the product** (`SpaceManager.create`,
`createFromBundle`, `createFromTemplate`) gets an EMPTY `egress.toon` (`{allow: [], createdAt}`) written before it
boots (`EgressAllowlist.recordEmpty`), so it is never seeded — otherwise anyone who can create a Space, a Connection
and a webhook Step could allowlist a private host without `canAdminister`. ⚠ A Space folder an operator drops
onto disk before a boot IS seeded at that boot: an operator action, trusted. The file's existence is the
"recorded" marker, so a later `PUT` that removes entries is never re-seeded. If the seed cannot be written the
error is logged and the list stays EMPTY — fail closed. ⚠ Seeding is shared: a seeded host also lifts Action
Requests to that host (the allowlist is one per Space).
⚠ Embedded-IPv4 IPv6 forms other than IPv4-mapped (6to4, NAT64 `64:ff9b::/96`) are classified as public.

## Dispatch

`ActionDispatcher.submit` resolves the Connection on the request thread (it carries the Space) through
**`WebhookSink.endpoint`** — a registered `https` Connection (onboarded under the admin-only
`canOnboardConnections`), a host, **no tunnel or proxy**, the bearer token resolved from its secret reference at
send time — and refuses if it now resolves to anything other than the approved `targetUrl` (pinned by
`aRetryAfterTheConnectionMovedFailsAndSendsNothing`). A 3xx fails the request at once, without a retry.

Retries: up to `-Daction.dispatch.maxAttempts` (default 3, clamped 1..10), backoff from
`-Daction.dispatch.backoffMs` (default 1000 ms, doubling, capped at 30 s), each attempt under the Connection's
`timeout_seconds`. Retryable: I/O failure or timeout, 5xx, 408, 429; anything else fails at once. The
`Idempotency-Key` header is identical on every attempt and every retry. The last response (status, a 1 KiB
body excerpt, the error, the attempt, the address) is kept on the record; nothing logs a payload, a token or a body.

**Stuck in `dispatched`.** A request left there by a process stop is NOT resumed at boot — nothing is ever re-sent
automatically. `POST /action-requests/{id}/mark-failed` (`canApproveChanges`, not four-eyes, audited) moves it to
`failed` once it has made no progress for `-Daction.dispatch.stuckAfterMinutes` (default 15); a retry then re-sends
it under the SAME idempotency key, which is what lets a receiver drop a delivery it did get.

## The Decision Rule `invoke-api` consequence

Applying a rule with `invoke-api` no longer emits a stub Signal: it **proposes a pending Action Request** on
the rule's open Incident (correlation `decision-rule:<rule>`, opened when none is), with
`params.connection`, `params.method` (default POST) and `params.payload` (default
`{incident: {{incident.id}}, rule: {{context.rule}}}`). Deduped while one is pending on that Incident. The
author is the person applying the rule, or `decision-rule:<rule>` for an engine-fired application; the rule's
makers (above, from the version history) become `coAuthors`. When the history cannot say — an unstamped version
on the chain, or a chain running past the retained history — the rule raises nothing, *skipped* — fail closed,
because its makers cannot be excluded; save it again. Each skip emits one WARN audit
`action-request.skipped-unknown-makers` (rule name only, no payload) so the gap is visible (2026-10-04).

**At save** (`POST|PUT /decision-rules`), a rule with an `invoke-api` consequence needs the saver to hold
`canWorkIncidents` (403) — otherwise a `canAuthorWorkbench`-only author could propose outbound calls by proxy — and
its params are validated: `params.connection` required, registered, `https` (422); a legacy `params.url` is 422
with a message saying the target is always an onboarded Connection. ⚠ Breaking.

🔴 **One guard on every writer.** `decision-rule` is a `ComponentStore.WRITABLE_TYPES` kind, so the same check runs
in `DecisionRuleGuard` from EVERY door that writes one: `/decision-rules`, `/components/decision-rule` (and version
restore, which lands there), `/import`, `/bundle/import`, a new Space's bundle; a pipeline rename may move
`target` only; the agent's fix drafts refuse the kind. `createdBy` / `updatedBy` are **server-stamped** — body
values are discarded. An import carrying an invoke-api rule by someone without `canWorkIncidents` is 403 before
anything is written (all-or-nothing). A Space created from a bundle (`POST /spaces/import`) is pinned end to end in
`ControlApiSpaceBundleActionRequestsTest`: the importer becomes the rule's editor, and — since no version history
travels with a bundle — the bundled `updatedBy` and a well-formed `restoredMakers` stay makers beside the importer
(an imported value can only ADD refused approvers; a malformed `restoredMakers` is 422, and so is one over 64 ids or an id over 256 characters — a restore that would carry more than 64 forward is 409, never truncated); every other door strips it and
recomputes. The
new Space's empty Egress Allowlist denies the target until that Space lifts it. `DecisionRuleWritersTest` enumerates `ConfigWriteFunnelTest`'s writer
inventory and fails for a new writer that neither calls the guard nor is listed with its reason.

🔴 **Makers are stamped on the rule at save** (`ASSURE-ACTION-REQUESTS-RESIDUALS-1` item 3, option C, 2026-10-04).
`DecisionRuleGuard.prepare` — so every door above: `/decision-rules`, `/components/decision-rule`, version restore,
`/bundle/import`, `/import`, `POST /spaces/import` and Space Template creation — writes `makers`, the version's
complete maker set: the writer, any `restoredMakers`, and, when the invoke-api consequences are unchanged, the prior
version's makers (its `makers` stamp, else the history walk). `makers()` reads the stamp first, so pruning the
history no longer silences a rule. Server-only: a body `makers` is discarded and recomputed (a template's is dropped);
only a new Space's bundle keeps it, additively, validated like `restoredMakers` (422). When the prior makers are
unknown, or the set would pass 64, nothing is stamped and the history walk decides — the
`action-request.skipped-unknown-makers` WARN audit and fail-closed skip remain for such legacy rules until a save
whose history can still name them. ⚠ An import into an existing Space replaces the head without archiving it, so the
stamp there is written but only becomes load-bearing at the next archiving save, which stamps itself; pinned by
`ControlApiActionRequestsTest` `*StampsTheMakersSoAPrunedHistoryStillRaises` + `aClientCannotForgeTheStampedMakers`,
`ControlApiSpaceBundleActionRequestsTest.aBundledRulesStampedMakersStayMakersInTheNewSpace` and
`ControlApiSpaceTemplateSeedGateTest`.

## Routes

| Route | Gate | |
|---|---|---|
| `GET /action-requests[?status&incidentId&caseId]` | `canWorkIncidents` or `canApproveChanges` | only requests on visible objects; capped at 500 with `total` + `truncated`; no payload |
| `GET /action-requests/{id}` | `canWorkIncidents` or `canApproveChanges` | 404 when its object is invisible; the rendered payload and the `egress` view |
| `POST /action-requests` | `canWorkIncidents` | 503 no write root → 422 body → 503 no ops module → 404 linked object → 503 no transport → 422 Connection / host syntax → 413 payload > 64 KiB |
| `POST /action-requests/{id}/approve` · `/decline` | `canApproveChanges` | Subject 403 → 503 → 422 body key / id → 404 (absent or invisible) → 409 invalid → 409 not pending → 403 four-eyes (author or co-author) |
| `POST /action-requests/{id}/retry` | `canApproveChanges` | as above, but the request must be `failed` |
| `POST /action-requests/{id}/mark-failed` | `canApproveChanges` | as above, but `dispatched` and idle ≥ the stuck timeout (409 otherwise) |
| `GET|PUT /settings/egress` | PUT `canAdminister` | the Egress Allowlist |

## UI

The **Action Requests** inbox (`/action-requests`, Operations nav, the Pending Changes inbox pattern) lists
waiting / all requests; selecting one shows, first, **where it is sent** — the parsed host in large type, scheme,
port, path and an allowlist badge — then key, attempts (with each attempt's address) and the exact payload, with
Approve / Decline (pending), Retry (failed) or Mark as failed (dispatched) for a `canApproveChanges` holder. The
Incident / Case detail page carries an **Action Requests** panel: status badge, attempts, last response and Retry.

**Settings ▸ Egress Allowlist** (`modules/admin/settings/egress.component.ts`, `EgressSettingsService`) lists the
Space's entries for every caller (the GET is ungated) and edits them only under `canAdminister` — the capability
the PUT checks; without it every add / remove / save control is hidden. Entries are edited as a draft (trimmed and
lowercased, as the server stores them; shape checks only: no spaces, no duplicate, at most 200) and replaced in one
PUT. An info notice states what can never be allowlisted; the server stays the only judge, and its 422 refusal
(e.g. a range overlapping `169.254.0.0/16`) is shown inline, verbatim, with the draft kept. A 503 (no writable
config root) is an in-place warning; anything else toasts via `apiErrorMessage`. The dirty draft is guarded by the
Settings route's `canDeactivate` (`SettingsComponent.canLeave`, which also runs on a section switch and asks any
section exposing `hasUnsavedChanges()`) and by `beforeunload`. ⚠ A host name such as `localhost` passes the PUT (it
is a valid DNS name) but still never lifts loopback at connect time — the notice says so.

## Deferred / known gaps

- No per-request `path` below the Connection's base path: one Connection per endpoint.
- `inspecto`-module tests drive the dispatcher over a loopback **test** wire (it connects to the pinned address like
  the real one); the real wire's pinning, SNI / certificate verification and redirect refusal are pinned in
  `HttpWebhookSinkTransportTest` and `PinnedHttpTlsTest`.
