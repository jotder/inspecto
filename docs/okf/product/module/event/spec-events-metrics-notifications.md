---
type: Concept
title: Events & Metrics — Notifications and the audit trail
description: Part 1 of 4 of Events & Metrics: Notifications & audit trail (shipped 2026-06-29, ddfa288)
tags: [control-plane, events, metrics, observability, deadlock]
timestamp: 2026-07-16T00:00:00Z
---

# Events & Metrics — Notifications and the audit trail

Part 1 of 4 of [Events & Metrics](spec-events-metrics.md) · next: [Notifications and audit — the audit hash chain onward](spec-events-metrics-audit-chain.md).

## Notifications & audit trail (shipped 2026-06-29, `ddfa288`)

* **`NotificationService`** — an `EventLog` subscriber that **hands off to a virtual-thread
  executor** (never runs inline — the sync-bus/run-claim hand-off seam above). Feed routes under
  `/notifications/*` (list / unread-count / read / unread / read-all / delete), real-time via **SSE**
  `GET /notifications/stream` (blocking handler on a virtual thread, heartbeat, close-hook registry),
  and `NotificationRateLimiter` (rolling per-hour cap on identical notifications — the anti-loop
  safeguard). `NotificationTemplate` uses `{{var}}` interpolation; delivery is gated by
  `NotificationPreferences` (category × channel; critical categories locked on).
* **Read state is per reader** (operator, 2026-09-25) — the feed (`NotificationStore`) is one shared
  state per Space, but each reader's read marks live in **`NotificationReadState`**, keyed by
  `(ApiContext.actor, notification id)`: the Subject id, or on Personal the `appUser` fallback
  (`X-Actor` was removed in 4.0.0). `POST /notifications/{id}/read`, `POST /notifications/{id}/unread` (new) and
  `POST /notifications/read-all` touch only the caller's marks and are **self-service** (open to any
  authenticated caller — `CapabilityManifest.EXEMPTIONS`); `GET /notifications`, the SSE frames and
  `/notifications/unread-count` report `read` / `state` / `readAt` **as the caller sees them**. Delete
  (archive) stays `canAdminister` — it writes the shared feed.
  In memory, like the feed it overlays (a restart forgets both); ≤1000 marks per reader, oldest evicted.
  ⚠ **One shared side effect, on purpose:** a read also acknowledges the notification in the shared store
  (`NotificationStore.markRead`), because that is what re-opens the dispatcher's dedupe collapse
  (`hasActiveDuplicate` matches only store-UNREAD rows) so the next identical alert is delivered again, as
  before. It can only let a repeat alert through, never hide one, and no reader's view reports it. Tests:
  `ControlApiNotificationsTest.readStateIsPerSubject` (real HTTP, with Subjects), `NotificationReadStateTest`.
* **A notification is a broadcast or addressed** (2026-09-26, `DUCKLE-C1-DATASET-FRESHNESS-1` residual 2) —
  `Notification.recipient` is `null` (every reader, the usual case) or a Subject id, taken from the triggering
  event's `recipient` attribute, which only an **owned** Alert Rule's `ALERT_FIRED` / `ALERT_CLEARED` carry.
  An addressed one is in its recipient's feed, badge and SSE stream only (`NotificationRoutes.visible`; no
  Subject ⇒ everything, as before) and its personal email goes to the recipient alone. Operator
  `ChannelConfig` destinations still receive every notification. As-built and the decisions in
  [`studio.md`](../../layer/product/plan-studio.md) §3.4.
* **Preferences are two layers** (operator, 2026-09-25; ses-sns-adapter-design §7, fixes SEC review F2) — the
  **deployment default** is `NotificationPreferences` (per Space's `CollectorService`, in memory, as before),
  and each Subject may hold a sparse **override** in **`NotificationPreferenceOverrides`**:
  `enabled(defaults, category, channel, subject, email)` = the override, else the default; critical categories
  are locked on at both layers (nothing is ever stored for them). Only `inApp` and `email` are personal —
  `webhook` is an operator destination with no per-user address, so it always inherits.
  * Routes: `GET /notifications/preferences` = the caller's **effective** grid, each row the old shape plus
    `source` (`inherited` / `overridden`) and `editable` per channel; `PUT /notifications/preferences` =
    the caller's override only (**self-service**; a `null` cell resets it; `503` if the file is unreadable);
    `GET /notifications/preferences/default` (open) and **`PUT /notifications/preferences/default`
    (`canAdminister`)** = the default grid. **Personal** (no Subject): no override layer, `PUT
    /notifications/preferences` writes the single grid as before, and every cell reads `inherited`.
  * **Email destination = `Subject.email()`, the verified email claim only** — never a body field (none is
    read), never user-editable. A Subject without one cannot turn email on, and reads email as off even where
    the default says on.
  * **Storage:** ONE per-deployment file, `notification-preferences.toon` (users span Spaces), found at
    `-Dnotify.preferences.file`, else `<-Dspaces.root>/`, else single-tenant `<-Dassist.write.root>/`, else
    in memory — never the CWD. Written through `AtomicFiles` + `ConfigCodec`, keyed by stable subject id.
    Owned by `SpaceManager` and wired into every Space's `NotificationService` as it joins. An existing but
    unreadable file makes personal saves refuse (503) rather than overwrite everyone else's overrides.
  * **Delivery consults it:** the in-app leg stores a notification when the default OR any Subject's override
    enables in-app, and each reader's feed / SSE / unread count then hides a category that reader's
    **effective** in-app is off for. Personal email goes to every **enrolled** Subject (one that has saved its
    preferences with a verified address) whose effective email is on — through the discovered `email` SPI
    transport, with suppression and a receipt like any addressed destination.
  * ⚠ **Known limits:** enrolment happens on the Subject's first `PUT`, so a user who never saved receives no
    personal email even when the default says on; the default grid is still per Space and in memory (a
    restart resets it; unchanged by this work); with several pods on one `-Dspaces.root` each pod holds its
    own copy of the overrides file, so the last writer wins across pods; and the email transport is only
    discovered when `notify.smtp.to` is set, exactly as for `ChannelConfig` email destinations.
  * Tests: `ControlApiSubjectPreferencesTest` (real HTTP, with Subjects: A's PUT leaves B's grid unchanged,
    `/default` 403 without `canAdminister`, critical cannot be turned off, a body address is ignored,
    Personal unchanged, durable across a restart), `NotificationPreferenceOverridesTest`,
    `NotificationServiceTest` (personal email + in-app opt-in), `CapabilityManifestTest.sharedNotificationStateWritesStayAdminGated`.
  * SPA: `notification-preferences.component.ts` shows the caller's effective grid with Inherited / Overridden
    markers and a per-cell Reset (saving only changed cells); administrators also get
    `deployment-default-preferences.component.ts`.
* **`NotificationChannel`** is a ServiceLoader SPI — in-app is intrinsic; **email is an edition
  seam**, deliberately not in core. A message broker is deliberately not used: in-process
  virtual-thread executor + append-only `EventStore` is the idiom.
* **Channel destinations admin CRUD** (2026-07-18) — `GET/POST /notifications/channels` +
  `PUT/DELETE /notifications/channels/{id}` manage persisted `ChannelConfig` records
  (`{id, kind, target, description?, enabled, createdAt}`) as a `channel` `ComponentStore` kind, per
  space (write-root 503 → 422 missing fields → 409 dup / 404 unknown; `canAuthorWorkbench`).
* **Persisted channels are wired into dispatch** (2026-07-19) — `NotificationService.dispatch` reads a
  live `Supplier<List<ChannelConfig>>` (backed by `<write-root>/registry`, resolved per space, no
  restart needed on edits) alongside the SPI-discovered channels above. For each enabled `ChannelConfig`
  whose `kind` matches a discovered `NotificationChannel.id()` (case-insensitive), it delivers through
  that transport via a new `default void deliver(Notification n, String target)` (delegates to
  `deliver(n)` for impls that resolve their destination from `notify.*` flags instead). A `kind` with no
  matching transport, or a disabled destination, delivers nothing. **Still open**: `ChannelConfig` has
  no `template` field, so a persisted channel can't override the rule-level `NotificationTemplate` — that
  was scoped as the notification-templating slice (S2) of the Signal Backbone plan, now archived
  (`docs/archived-documents/plans-archive/event-signal-backbone-plan.md`) with that slice unbuilt and no board row —
  recorded as `UNTRACKED` in `okf/product/addon/incidents/plan-incidents.md` §5 (2026-09-08).
* **`AuditTrail`** — a central interceptor in `ControlApi.dispatch` records every successful
  state-changing request plus non-GET forbidden-route attempts (actor/action/target, secret
  scrubbing, immutable store). One seam covers all routes; 405 immutability is inherent to dispatch.
  ✅ **And refusals on a route that DID match — `AUDIT-REFUSAL-GAP-1`, 2026-09-15.** A `401` from the
  AuthN gate and a `403` from a capability check both unwound past the recording call into the error
  boundary, which only shapes the response — so the log held every *successful* call to a route and none
  of the denied ones, the inverse of what an investigator needs. Both are now caught in `routeDispatch`
  and recorded as `ACCESS_DENIED`.
  ✅ **And the refusal now says denied WHAT — compliance plan step 4a/4b, 2026-09-15.** `requireCapability`
  records the capability it checks on the exchange (`ApiContext.ATTR_CAPABILITY`) **before** the outcome is
  known, and both audit writers read it: `accessDenied` stamps `AuditAttrs.CAPABILITY` on the `ACCESS_DENIED`
  row (the capability that was MISSING — until then it lived only in the exception message and never reached
  the log), and `record` stamps it on the `AUDIT` row of a write that passed the gate (the capability the write
  was PRIVILEGED by), so *"every privileged write, by actor, by capability, in the window"* is one
  `/audit/search` query. ⚠ **The attribute is set only when a `Subject` is attached** — i.e. only when a
  check actually ran. On Personal nothing is checked, so nothing is stamped: **absence means "not checked",
  never "checked and passed"**, and a Personal log cannot be read as claiming gates it does not have. Pinned
  four ways in `ControlApiAuthV1Test`: a 403 names `canAuthorWorkbench`; a 401 carries **no** capability (no
  check was reached); a permitted write's `AUDIT` row carries it; a Personal write's row carries none.
  🔴 **The first cut leaked.** `ATTR_CAPABILITY` is request-scoped and was not on
  `ControlApi.REQUEST_SCOPED_ATTRS`; `ExchangeAttributeScopeTest` refused it — on a shared-attribute runtime
  one request's capability would have been readable by the next. The guard exists for exactly this and it
  fired; every new `ATTR_*` goes on that roster. ⚠ The audit-shaped CSV on `/events/export` derives its
  columns from `AuditAttrs.ALL`, so it gained a `capability` column; nothing pinned the header literally.
  ⚠ **Two deliberate asymmetries, both load-bearing.** (1) The AuthZ **policy** DENY is *not* caught
  there: `authorize` already writes its own `access.denied` via `AuditTrail.policyDecision`, and catching
  it again would log one refusal twice — so `authorize` sits outside the guard on purpose. (2) Unlike the
  404/405 path, this records **GET** too: a 404 on a bare GET is usually an SPA deep link, but a matched
  route is unambiguously an API call, and a refused read is exactly the attempt worth keeping.
  Pinned by three tests in `ControlApiAuthV1Test`, each proven red by removing the recording call.
