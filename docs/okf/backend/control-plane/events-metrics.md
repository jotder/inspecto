---
type: Concept
title: Events & Metrics
description: EventLog (synchronous bus + the run-claim hand-off seam), MetricRegistry, the StabilityGate, notifications + audit trail, and alert-rule authoring.
resource: inspecto-event/src/main/java/com/gamma/event/EventLog.java
tags: [control-plane, events, metrics, observability, deadlock]
timestamp: 2026-07-16T00:00:00Z
---

# Events & Metrics

* **`EventLog`** (`inspecto-event/src/main/java/com/gamma/event/EventLog.java`) — the event bus. `global()` +
  per-space instances; `current()` routes by the calling thread's `space` MDC, falling back to global.
  **Emission is synchronous on the publishing thread** (`emit()` calls each subscriber inline). This is the
  hand-off seam: the publishing thread holds that pipeline's `PipelineRunGuard` claim, so a subscriber that
  triggered a new ingest of the **same** pipeline inline would block forever on the claim its own thread holds
  — hence event-triggered work is handed to an off-bus virtual-thread pool (see [jobs](jobs.md)). Before
  2026-08-01 this was the global `ingestLock`; being re-entrant, it would have double-ingested rather than
  hung (see [cross-cutting gotchas](../gotchas/cross-cutting.md)). `emit()` uses no SLF4J (avoids re-entrant capture) and
  swallows subscriber errors. A startup store-swap (`InMemoryEventStore` → configured backend) drains the old
  store oldest-first so nothing is lost.
* **`MetricRegistry`** (`inspecto-event/src/main/java/com/gamma/metrics/MetricRegistry.java`) — counters/gauges/
  histograms keyed by name + sorted labels; `scrape()` runs registered collectors then renders Prometheus
  text. The per-space `space` label is supplied by callers as a label (no registry-level space awareness).
  The scrape endpoint `/metrics` (with `/metrics/acquisition`, `/health`, `/ready`) is one of the four
  routes that stay **unversioned** — see [versioned API](api-v1.md). *(The sunset counter
  `inspecto_legacy_api_requests_total` was removed 2026-07-25 with the unversioned business surface.)*
* **`StabilityGate`** (`inspecto-acquire/src/main/java/com/gamma/acquire/StabilityGate.java`) — the acquisition
  file-readiness gate (not a health gate); one shared instance per space (see [acquisition](../acquisition/framework.md)).

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
  `(ApiContext.actor, notification id)`: the Subject id, or on Personal the `appUser` / `X-Actor`
  fallback. `POST /notifications/{id}/read`, `POST /notifications/{id}/unread` (new) and
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
  [`studio.md`](../../capabilities/studio/studio.md) §3.4.
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
  recorded as `UNTRACKED` in `okf/capabilities/incidents/incidents.md` §5 (2026-09-08).
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
* **Tamper evidence — the audit hash chain (`ASSURE-AUDIT-CHAIN-1`, 2026-09-27).** Every `AUDIT` and
  `ACCESS_DENIED` event a Space's `EventLog` emits is linked onto that Space's chain
  (`inspecto-event/src/main/java/com/gamma/event/AuditChain.java`): attributes `audit_seq` (from 1),
  `audit_prev_hash` (`""` at genesis — the Entity Fact log's convention) and `audit_hash`, SHA-256 over a
  canonical encoding (JSON, keys sorted at every depth, nulls written, UTF-8, format version `v: 1`; the payload
  is normalised through the store's own JSON round trip first, so a double reads back as the same text).
  - **The seam is `EventLog.emit`, not `AuditTrail`** — `AuditTrail` is not the only class that emits `AUDIT` rows, and the hash must cover
    the row AFTER `SecretScrubber` has run. The log holds its `AuditChain` monitor across link + append: one
    writer per Space, so seq order is append order.
  - **No stored head.** The head is recovered from the store (`EventStore.chainHead`) on first use and after a
    store swap. A row replayed from the write-ahead journal is already linked and is appended as-is, so a hard
    kill neither forks nor duplicates the chain; the replay also skips events already flushed to Parquet (a kill
    between flush and truncate used to store them twice — a duplicate seq). A store swap re-links the carried
    audit rows onto the incoming store's head. `chainHead` THROWS on an unreadable store, because answering
    "empty" would restart the chain at genesis; the row is then kept unlinked rather than lost.
  - **Time never goes back along the chain:** linking raises `ts` to the head's when the clock reads earlier,
    which is what lets the verifier call an out-of-time record a `reorder`.
  - `GET /audit/verify?from=&to=` (seqs; `canAdminister`) walks `[from, to]` in 1000-record pages with one
    record of look-ahead, at most 100 000 per call (`complete: false` + `next`), and names the FIRST bad seq:
    `duplicate`, `gap`, `reorder`, `broken-link`, `hash-mismatch`, `anchor-mismatch`, `missing`
    (`inspecto/src/main/java/com/gamma/control/AuditVerifier.java`). A broken chain is a 200 with `ok: false`.
  - **Anchors** (`inspecto/src/main/java/com/gamma/control/AuditAnchors.java`): `{day, firstSeq, lastSeq,
    lastHash, count}`, HMAC-SHA256 with the Space's Pending Change key over `"audit-anchor\n"` + canonical JSON
    (domain-separated from the Pending Change MAC, whose input starts with `{`). Stored in
    `<config root>.secrets/audit-anchors.jsonl`, the key's sibling — outside every import, export, Exchange and
    backup. A `daily` anchor closes each finished UTC day, rolled in the background on the first request the
    Space serves after midnight (`ControlApi.routeDispatch` → `rollIfDue`); `POST /audit/anchors` anchors on
    demand; `GET /audit/anchors` exports them. Neither signs a chain that does not verify: the roll stops before
    a break and never anchors the day the break is in; on-demand answers 409, as it does when the last anchor's
    MAC fails or its record no longer hashes to what it names.
  - **Records from before 2026-09-27 carry no seq and are outside the chain**: it starts at seq 1 with the first
    row linked after the upgrade.
  - ⚠ **What it cannot see.** SHA-256 needs no key, so a chain rewritten end to end AFTER the last anchor
    verifies — only an anchor catches it (`AuditVerifierTest.aChainRewrittenEndToEndIsCaughtOnlyByTheAnchor`
    pins both halves). Rows deleted from the FRONT look exactly like retention (`prune` drops whole old days), so
    a walk starting above seq 1 reports `fromGenesis: false` rather than a break. A local administrator can read
    the key and re-sign; exporting the anchors off the box is the answer to both. Anchors are not backed up
    (`BackupTask` skips `*.secrets`), so a restored store verifies without them.
  - 🔴 **Hardened after an independent verification FAILED the first cut (2026-09-27, same day):**
    - *No silent holes.* A row that cannot be linked (unreadable head, hash failure) is stored marked
      `audit_unlinked=true`, with an ERROR event and `inspecto_audit_unlinked_total`; verify counts every
      chainable row without a seq written since the chain began → `unlinked`. One corrupt `.parquet` used to
      break the glob read, unlink every later row and still verify: chain reads now fall back to one query PER
      FILE, keep answering, and verify fails with `unreadable-file` naming it.
    - *The anchors are a chain.* Each anchor carries `prevAnchorMac` in its MAC input and must start at the
      previous `lastSeq + 1`; a garbled line (`anchor-unreadable`), a removed or reordered anchor
      (`anchor-chain-broken`) and a finished day (before yesterday) no anchor covers (`anchor-missing`) all
      fail. The file is created owner-only like the key. The roll and `POST /audit/anchors` REFUSE (409) over a
      broken file, and over a missing or empty one while the chain holds rows that should already be anchored —
      a deleted file is never silently re-signed; an operator resolves it (⚠ there is no reset route: moving
      the file aside is a manual, visible act). An anchor whose rows were cut from the front of a day that still
      has rows is `truncated-before-anchor`; an anchor whose whole day is gone is what retention leaves, not a
      failure.
    - *Bounded work.* The roll runs on a control-plane schedule (every 10 min, once per UTC day per Space,
      backing off 5 min → 6 h after a failure) — never on a request; `POST /audit/anchors` is limited to one per
      10 s per Space (429); both walk only from the last anchor. `DbEventStore` keeps the seq in an indexed
      `audit_seq` column. ⚠ The Parquet store still scans every file per chain page (the seq lives in the
      attributes JSON) — a large store makes a verify slow, not unbounded in memory.
    - *One writer per directory.* The first link takes an OS lock on `<events>/.chain-writer.lock`; a second
      EventLog or process on the same directory is refused and its rows land marked unlinked. A carried row the
      incoming store already holds is not re-linked on a store swap, and verify fails one eventId at two seqs
      (`duplicate-event`).
    - ⛔ **On-box anchors do not defend against anyone who holds the key** (a local administrator can rewrite
      the store and re-sign every anchor). The defence against that party is the OFF-box copy: export
      `GET /audit/anchors` on a schedule to storage the Space's administrators cannot write, and compare.
  - 🔴 **Round 3 (a second verification FAILED round 2, 2026-09-27):**
    - *The writer lock is checked by identity.* An OS lock guards a file, not a path — the lock file can be
      deleted while held and a second writer locks a new one. The lock now covers a byte far past a random token
      written into the file, and before EVERY link the store re-reads the path and compares the token; on a
      mismatch it stops linking for good (rows marked unlinked, ERROR, metric).
    - *"Anchoring started" is durable.* `<config root>.secrets/audit-anchoring.json` (owner-only, created
      first-writer-wins with the first anchor) names the first anchor of the current file and the latest one
      written. Once it exists, a missing file (`anchor-file-missing`) or one that no longer holds both anchors
      (`anchor-file-truncated`) always fails and is always refused; the young-install grace applies ONLY before
      it exists. ⚠ It is exactly as strong as the secrets directory — whoever can write there can read the key.
    - *Truncation is judged against the configured retention.* `truncated-before-anchor` now compares with the
      shortest enabled `event_prune` window (`JobService.eventRetentionDays`): an anchored day whose rows are gone
      is retention only when it is before that cutoff; with no prune configured, any missing anchored day fails.
  - **Operator runbook — when `/audit/verify` fails.** (1) Read `firstBad`; do NOT delete or edit anything under
    `data/events/` or `config.secrets/`. (2) Compare the Space's last off-box export of `GET /audit/anchors` with
    the current one to see what changed, and open an Incident. (3) Only once the cause is understood, record it:
    `POST /audit/anchors/rebaseline {"reason": "…"}` (`canAdminister`, audited, once a minute). It refuses (409)
    when nothing fails, repairs nothing, moves the old anchor file aside as `audit-anchors.<ms>.replaced.jsonl`,
    and starts a new anchor file with a signed `break` anchor holding the reason, the problem verify found, the
    last good anchor and the new start seq (the head + 1). (4) From then on the DEFAULT `/audit/verify` is never
    `ok`: it answers `ok: false` with `acknowledged: true`, `breaks: [{at, reason, problem, lastGoodSeq, by, …}]`
    (every epoch, back through each replaced anchor file) and `epochStart`. `/audit/verify?epoch=current` may say
    `ok` for the current epoch alone, and still lists the breaks. The replaced file is KEPT and pinned by digest
    in the break; it missing or edited is `prior-epoch-missing` / `prior-epoch-altered`. The rebaseline is also
    its own chained audit event, `audit.rebaseline`, carrying the reason, the problem, `lastGoodSeq` and the
    actor. (5) Export the anchors off the box again.
  - 🔴 **Round 4:** truncation is judged by what a prune ACTUALLY removed. `event_prune` now writes a chained
    `events.pruned` AUDIT row (`prune_before`, `partitions_removed`, `retention_days`, the job as actor) through
    the Space's EventLog; anchored rows gone from the front are retention only for days before the cutoff of a
    VERIFIED prune record. A configured `retention_days` job that never ran accounts for nothing; the configured
    window is only reported (`retention.configuredSource`), beside `retention.source: prune-record | none`.
  - ⛔ **By design, after ANY rebaseline the default `/audit/verify` stays `ok: false` PERMANENTLY** — there is
    no route that clears a break, and none will be added: a trail that once needed a break must say so to every
    later reader. Day-to-day checking uses `?epoch=current`.
  - *A prune is only a prune in the task's own shape:* verify counts a row as a prune record only when
    `action=events.pruned` AND `source=job` AND `actionCategory=retention`. And because that record decides
    which audit rows may lawfully vanish, authoring or editing an `event_prune` job needs `canAdminister` — on
    `POST/PUT /jobs` and `POST /config/write` (on top of `canAuthorWorkbench`); a bundle import refuses to carry
    one at all.
  - ⚠ **Two known windows, stated so they are not overclaimed.** (1) *Lock race:* a writer that already linked
    rows it had not yet flushed when its lock file was deleted can collide with a second writer that recovered an
    older head from disk — the fork is not prevented, it is DETECTED afterwards as a `duplicate`. (2) *One-anchor
    crash window:* an anchor line is appended before the anchoring record's `latestMac` is updated; a crash
    between the two leaves the record naming the previous anchor, so removing that newest line in that window is
    not seen.
  - **Residuals (not built):** a scheduled off-box anchor export target; the multi-pod `DbEventStore` has no
    chain-writer lock (two pods linking one table fork the chain — filed on the board row); a `DbEventStore` shared by several pods gets one chain per process interleaved in
    one table (verifies as duplicates) and serves the chain reads through the `EventStore` keyset-walk defaults
    (linear per page); no offline checker tool ships (the JSON `/audit/export` carries every hashed field);
    per decision D-P8, classification-driven masking of audit rows and read auditing beyond what exists stay
    out of scope.
* **Email/SMTP channel wired to `deliver(n, target)`** (2026-07-20) — `SmtpEmailChannel`
  (`inspecto-notify-channels/src/main/java/com/gamma/notify/channel/SmtpEmailChannel.java`, id `email`,
  ⚠ **relocated from `inspecto-connectors` 2026-09-07, EDG-01 cell 1** — CP-15 is not for Personal and that sidecar ships in every edition,
  already discovered via `ServiceLoader` and configured from `notify.smtp.*` system properties, the
  same idiom as `WebhookChannel`) now overrides `deliver(Notification n, String target)` to address
  the mail to the persisted `ChannelConfig`'s own `target` (comma-separated addresses supported),
  falling back to the fixed `notify.smtp.to` only when `target` is blank — so an operator-managed
  `email` destination actually reaches its own recipient instead of the single configured inbox.
  `ChannelConfig.fromMap` additionally fails closed (422) at channel-creation time when `kind=EMAIL`
  and `target` isn't a valid email address (or comma-separated list) — SMTP config/target problems
  surface at CRUD time, not silently at dispatch. Template rendering (`NotificationTemplate` via the
  shared `DottedPath` grammar) was already generic in `NotificationService.dispatch` and needed no
  channel-specific change. Tests: `SmtpEmailChannelTest` (message addressing, no live server),
  `ControlApiNotificationChannelsTest` (422 on an invalid EMAIL target). Still deferred:
  delivery-status webhooks, digest batching.
* **Conservation imbalance now notifies** (2026-07-23) — `NotificationRules.defaults()` gained a rule
  for `FLOW_CONSERVATION_IMBALANCE` (category `ops`, `minLevel=WARN` so both `LOSS`/ERROR and
  `AMPLIFICATION`/WARN reach the feed, matching that `EventObjectBridge` opens an ALERT object for both
  kinds). Closes the gap `docs/ops/provenance-conservation-verification.md` flagged (OPS-5 product Q).
* **Authorable notification rules** (2026-07-24) — an operator can now add/override notification rules
  at runtime, not just channels. `NotificationRule` gained an `id` + `enabled` flag and
  `fromMap`/`toMap` (mirrors `ChannelConfig`); `NotificationRoutes` exposes `/notifications/rules*`
  admin CRUD (`GET/POST` + `PUT/DELETE /{id}`, `canAuthorWorkbench`, the same 503/422/409/404 gate
  order as channels — id bound from the path, unknown `minLevel` → 422) persisting a new
  `notification-rule` ComponentStore kind (added to `ComponentStore.WRITABLE_TYPES` +
  `ComponentRegistry.TYPE_BY_DIR`). `NotificationRules` now takes a `Supplier<List<NotificationRule>>`
  overlay resolved at dispatch time and checked **ahead of** the built-in `defaults()` in `forEvent`,
  wired from `CollectorService.persistedRules()` (per-space, live-reloaded, best-effort — a missing
  root / unreadable registry / malformed entry yields no rule, never an exception). So an authored
  rule for an already-covered event type overrides the built-in's copy/routing, a rule for a new event
  type extends coverage, and `enabled:false` mutes a built-in via a shadowing authored rule — all
  without a recompile. Chose a ComponentStore kind over a boot-scanned TOON file to match the adjacent
  `channel` CRUD precedent. Tests: `NotificationRulesTest` (overlay-first ordering, disabled fall-
  through, new-type coverage), `NotificationRuleTest` (`fromMap`/`toMap`), `ControlApiNotificationRulesTest`
  (real-HTTP CRUD + gates). Still open: no UI editor yet (backend + HTTP only).
* **Digest batching** (2026-07-24) — opt-in per destination: `ChannelConfig` gained `digestMinutes`
  (`0` = immediate delivery, the default; negative → 422 at CRUD time). For a config with a window,
  `NotificationService.dispatch` buffers the (template-rendered) delivery into a per-destination
  `DigestBuffer` instead of delivering; the first buffered item arms a one-shot timer (lazy single
  daemon `ScheduledExecutorService`) that flushes the batch after the window as ONE combined
  notification (`"Digest: N notifications"`, one `• title — body` line per item) through the matching
  transport to the config's target. `close()` flushes pending digests rather than dropping them.
  In-app feed copies stay per-event — only the external destination delivery batches. Package-private
  `flushDigest(id)` is the test seam. Tests: `NotificationServiceTest` (buffer+combined flush,
  empty-flush no-op), `ControlApiNotificationChannelsTest` (default/round-trip/negative-422).
* **Security triggers T1–T4** (2026-09-28, ses-sns-adapter-design §8) — `SecurityTriggers` (engine,
  `com.gamma.notify`) is a second `EventLog` subscriber beside `NotificationService`, wired per
  `CollectorService`; it reads the audit rows and emits `EventType.SECURITY_TRIGGERED` back into the same log,
  which the built-in rule `builtin-security-triggered` maps to category `security` (now `available = true`).
  | id | input | key | default (`-Dnotify.security.<id>.threshold` / `.windowMinutes`) |
  |---|---|---|---|
  | t1 | `ACCESS_DENIED` 403, not a delivery-status path | actor | 20 in 10 min |
  | t2 | `ACCESS_DENIED` 401 | audit IP | 50 in 10 min |
  | t3 | `ACCESS_DENIED` 403 on `/public/delivery-status/{adapter}` | adapter | 10 in 60 min |
  | t4 | `AUDIT` of a 2xx (not 202-held) `PUT /access/roles` — the only authoring door to `roles.toon` (import refuses reserved files) | actor | every change |
  * **Once per key per window**: a sliding window of at most `threshold` timestamps per (trigger, key); on firing
    the key is quiet until the window elapses. Keys live in ONE access-ordered LRU of `MAX_KEYS` = 10,000, so
    rotating keys can evict a count but never grow the heap.
  * **T2 and F3:** the key is `AuditAttrs.IP` = `ApiContext.ip` — `X-Forwarded-For` only from a
    `-Dcontrol.trustedProxies` peer, else the socket peer — so a spoofed header cannot mint keys.
  * ⚠ **T4 does not see an admin-only `restore` Job** that overwrites `roles.toon` (`overwrite: true`): it writes
    files, not a `PUT /access/roles`. Out of scope by decision — that Job already needs `canAdminister` at author
    and run time (`MAINT-TASK-AUTHORITY-1`); watch it here only if a restore must alert too.
  * **Read/unread by id** use the same `visible()` rule: a non-admin gets 404 for a `security` notification's id.
  * **Recipients = administrators.** In-app: `NotificationRoutes.visible` shows `security` only to a Subject
    holding `canAdminister` (Personal: everyone, no Subject). Personal email: `NotificationPreferenceOverrides`
    records an `admin` flag per enrolled Subject (on `PUT`, refreshed on `GET /notifications/preferences`);
    `securityRecipients()` is the list. Critical, so no layer can opt out. Mail goes through the per-recipient
    `DigestBuffer` (`security-digest:<subject>`, window `-Dnotify.security.digestMinutes`, default 10, 0 =
    immediate). Operator `ChannelConfig` destinations get it too, with their own digest setting.
  * Dedupe key includes the firing's `ts`, so a second window (or a second roles write) is not collapsed into
    an unread first one.
  * ⚠ Limits: counts are in memory, per Space log (a 401 with no Space in scope lands in the global log, i.e.
    the default Space's evaluator); an admin who never saved preferences gets in-app only; the admin flag is
    as fresh as the Subject's last preferences visit.
  * Tests: `SecurityTriggersTest` (threshold−1, once per window, sliding window, T2/T3 keys, 10,000-key LRU,
    T4, cannot be opted out + admin-only digest), `ControlApiSecurityTriggersTest` (real HTTP: 12 403s → one
    notification, admin-only feed; spoofed XFF counts against 127.0.0.1; a roles write fires T4).
* Deferred to editions/follow-ons: time-based retention sweep, GeoIP and trigger T5 (operator decision D12).

### Inbound delivery-status webhooks (BACKLOG D8, shipped 2026-07-26)

**Inbound** provider callbacks — what happened to an email we sent. Not outbound push: the outbound
`webhook` *channel* shipped 2026-07-22 (`channel/WebhookChannel`) and is unrelated. Standard/Enterprise
flavour territory (core ships the SPI; the adapters live in `inspecto-connectors`).

**Shape.** `DeliveryStatusAdapter` SPI (core, `@PublicApi`, ServiceLoader-discovered exactly like
`NotificationChannel`) → `SendGridDeliveryStatusAdapter` + `HmacDeliveryStatusAdapter` in
`inspecto-connectors` → `POST /api/v1/public/delivery-status/{adapterId}` (`DeliveryStatusRoutes`) →
`DeliveryReceipt` / `InMemoryDeliveryReceiptStore`. Read surface: `GET /notifications/deliveries`
(`canAuthorWorkbench`), backend + HTTP only, as `notification-rule` shipped.

**Three things the D8 decision did not anticipate — the corrections are the build:**

1. **Nothing correlated a sent message back to a `Notification`.** `deliver` returned `void`, SMTP
   discarded the `Transport.send` result, the outbound webhook threw the response body away. Fixed by
   **minting the id ourselves and embedding it outbound** — an SMTP `Message-ID` of
   `<inspecto.{deliveryId}@{domain}>`, or `X-Inspecto-Delivery-Id` on a webhook — never by capturing a
   provider id, which would have meant widening a `@PublicApi` signature. The seam is a **`default`
   three-arg `deliver` overload**: an implementor that ignores it simply does not correlate.
   ⚠ `SmtpEmailChannel` needs the `PreservedMessageIdMessage` subclass — stock `MimeMessage.saveChanges()`
   (which `Transport.send` calls) regenerates `Message-ID` and would break the round trip *silently, while
   the mail still sends*.
2. **Handlers could not see raw request bytes.** `ApiContext.rawBody` now reads once and **caches on the
   exchange**, and `body()` reads through it, so the two are safe in any order. Verifying a signature
   against a re-serialised `Map` is not an option: key order and whitespace do not survive, so signatures
   would fail *non-deterministically*.
3. **SendGrid signs with ECDSA P-256 / SHA-256, not the Ed25519 the plan specified.** An Ed25519
   implementation would have rejected every genuine callback and only revealed it against the real
   provider. Signed payload is `timestamp + rawBody`; the HMAC adapter signs `timestamp + "." + rawBody`
   (the timestamp must be *inside* the signature or the freshness check it enables is attacker-controlled).

**Load-bearing invariants — do not "simplify" these:**

* **`statusAt` is a `Map<DeliveryStatus, Long>`, not one mutable enum.** A spam-button click is
  `delivered` **then** `complaint`; a single field loses the ordering. **First observation of a status
  wins**, so a provider retry cannot shift a recorded time.
* **Verification precedes every write.** An unverified callback able to mark a destination dead is a cheap
  denial-of-notification vector. Gate order: unknown/unconfigured adapter **404** → bad/absent/stale
  signature **403** → no usable events **422** → every id unknown **202** → **200**.
* **Unknown and unconfigured adapters answer identically (404).** Differing would let an unauthenticated
  caller enumerate which providers a deployment has wired.
* **An unknown `deliveryId` is 202, not an error** — receipts are prunable and providers retry forever on a
  non-2xx, so rejecting one buys an infinite retry loop and no information.
* **Hard vs soft bounce is normalised at the adapter edge** (SendGrid `blocked`/`deferred` ⇒ soft): a full
  mailbox is not a dead address. An unrecognised event is `UNKNOWN` **with its raw payload** — never
  dropped, never guessed.
* **A receipt is per *delivery*, not per notification** (one notification fans out to many destinations);
  `Notification` gained no fields. **One receipt per digest delivery**, flagged `digest` — the single place
  the model is lossy about *which* notification bounced.
* `/public/delivery-status/` joins `/public/dashboards/` in `ControlApi.isSelfVerifyingPublic`, exempt from
  platform auth in both `authenticate` and `authorize`. It is **not** in `PUBLIC_PATHS` (exact-match infra
  paths) and **not** `isInfraRoute` — and it still lives under `/api/v1`, which is what an operator must
  paste into the provider console.
* **The unauthenticated edge is bounded twice, before the handler buffers anything** (SEC review F1,
  2026-09-24). The body is read through `ApiContext.rawBody(ex, maxBytes)` with
  `DeliveryStatusRoutes.MAX_CALLBACK_BYTES` = 256 KiB — over it is **413 `PAYLOAD_TOO_LARGE`**, refused on
  the declared `Content-Length` before reading, or after at most cap+1 bytes of a chunked body, and never
  handed to `verify`. And the path is in `ControlApi.isRateLimited` on its own per-caller-IP bucket
  (`RateLimiter.callback()`: burst 60, 5/s) → **429 `RATE_LIMITED`**. ⚠ Until then the route read the
  whole body into the heap with no cap and no throttle — any anonymous caller could post gigabytes.
  The bucket key is `ApiContext.ip`, which honours `X-Forwarded-For` only from a
  `-Dcontrol.trustedProxies` peer (F3) — otherwise a rotating header would mint a fresh bucket per request.

Config: `notify.deliverystatus.sendgrid.publicKey` · `notify.deliverystatus.hmac.secret` ·
`…{sendgrid,hmac}.freshnessSeconds` (default 300). Unset ⇒ the adapter is inert and its URL 404s.

Tests: `ControlApiDeliveryStatusTest` (every gate, over real HTTP, plus the raw-body byte-identity
assertion), `DeliveryReceiptStoreTest`, `NotificationServiceTest` (receipt per destination / none for
in-app / one per digest), `Hmac…`/`SendGridDeliveryStatusAdapterTest`, `SmtpEmailChannelTest`.

**Deliberately not built** (residuals → BACKLOG §6): auto-disable / suppression on hard bounce +
complaint, soft-bounce retry scheduling, an SES/SNS adapter (needs subscription confirmation and an
outbound cert fetch from a callback path — its own review), a UI, and the `deliverWithReceipt` SPI escape
hatch.

**Receipt retention (shipped 2026-07-26).** The `receipt_prune` maintenance task forgets receipts sent
before `retention_days` (required, like every other prune — deliberate forgetting), with a
`countPrunable` dry-run preview. It reaches the store through `JobService.deliveryReceiptStore`, a hook
this added: the store was wired into `NotificationService` **only**, so nothing in the job layer could see
it. Fail-open — no store attached is a reported no-op, never a throw. ⚠ `InMemoryDeliveryReceiptStore`'s
oldest-first eviction at 5000 is an **unconditional backstop on `add()`, not a retention policy**; bounding
receipts by age still requires scheduling the task, and receipts accrue per *external delivery*, i.e.
faster than notifications. Test: `MaintenanceLibraryTest` (`receiptPrune…`).

## Alert-rule authoring (shipped 2026-07-09)

* `AlertRoutes` — `POST/PUT/DELETE /alerts/rules[/{name}]` per the `endpoint` skill's fail-closed
  gate order; writes/deletes an `alert-rule` component (`ComponentStore`) under
  `<write-root>/registry/alert-rules/` (moved off `<name>_alert.toon` files 2026-07-18), gated on the
  `canAuthorAlertRules` capability. The engine does **not** hot-load the registry: the write routes arm
  rules in the running `AlertService` **in-process** (always present, empty until armed); a restart
  re-arms from the persisted components via `ServiceBootstrap.loadAlerts`.
  Gate coverage: `ControlApiAlertRuleWriteTest`.
* **Alert → Incident promotion** (2026-07-19) — `AlertService.persistAlertObject` still always opens an
  `ObjectType.ALERT`; a **critical or error** severity rule additionally opens a deduped
  `ObjectType.INCIDENT` (one open Incident per rule+pipeline, same guard as the ALERT dedup), so a
  high-severity breach enters the triage workflow, not only the alert feed. Lower severities stay
  alerts. Reuses the `ExpectationRoutes` signal→Incident dedup+open pattern (`active(INCIDENT, corr)`
  then `open(INCIDENT, …)`) — see also `jobs.md` (recon breaches) and `decision-rules.md` (`create-alert`).
  Since 2026-08-10 the promoted Incident also carries an **`ESCALATED_FROM` link to the ALERT object**
  that raised it (actor `alert-rule:<name>`, mirroring the Case Rules auto-linker), so the correlation is
  traversable in the `OBJECT_LINK` graph rather than only implied by the shared `rule`/`causedByEvent`
  attributes — matching what operator-created Incidents (`POST /objects`, ≥1 link mandatory) always had.
  ⚠ A *suppressed* promotion adds no edge: if the earlier ALERT was resolved while its INCIDENT is still
  open, the re-fire's fresh ALERT stays unlinked, because `IncidentAccess` reports suppressed and dry-run
  alike as an empty result. Gate coverage: `AlertServiceTest.thePromotedIncidentIsLinkedEscalatedFromItsAlert`.

The matching UI surfaces are the [events](../../frontend/features/events.md) and
[dashboard](../../frontend/features/dashboard.md) screens in the frontend bundle.
Production-investigation detail: [`docs/ADVANCED_GUIDE.md`](../../../ADVANCED_GUIDE.md).

## Decision 2026-09-06 — bounce/complaint handling stays manual until receipts persist

`DeliveryReceipt`s live in an in-memory store, so any suppression policy built today forgets on restart.
**Decided:** no auto-disable of a channel (one bad address must not silence a channel); when a DB-backed
receipt store exists, build a **per-recipient suppression list** — TTL for hard bounces, permanent for
complaints — consulted before delivery, with `GET/DELETE /notifications/suppressions`. Until then the
`enabled` flag is the operator's tool. (BACKLOG §3 `D8-SUPPRESS-1`, gated on receipt persistence.)

### Discharged 2026-09-07 — the store, then the policy

`DbDeliveryReceiptStore` shipped (`db-layer.md` §3.12), and with it the **suppression policy**:
`SuppressionList` in `notify/`, consulted in `NotificationService`'s ChannelConfig delivery loop before
the transport is called.

* **Complaint ⇒ permanent.** No elapsed time makes sending after a spam report acceptable.
* **Hard bounce ⇒ a TTL** (`-Dnotify.suppression.bounce.ttl`, ISO-8601, default `P30D`). Addresses get
  recreated, so this expires. ⛔ **Soft bounce never suppresses** — that is the whole reason
  `DeliveryStatus` splits hard from soft; collapsing them would stop mail to anyone once over quota.
* **Off switch:** `-Dnotify.suppression=off`.

🔴 **It arms itself only over a DURABLE store** (`DeliveryReceiptStore.durable()`, default `false`). Asking
an evicting cache "has this address ever bounced" answers "no" both for a clean address and for evidence
it forgot, and those must not collapse into a silent "deliver". When a TTL is configured but the store
cannot honour it, `NotificationService` logs a WARNING naming the reason — the alternative, suppressing
nothing while appearing configured, is this codebase's most-repeated failure shape.

⚠ **Two documented boundaries.** (1) Suppression covers **persisted `ChannelConfig` destinations only**;
the SPI-channel path passes no target (the channel resolves its own destination from `notify.*` flags), so
there is nothing to match a bounce against — and such a destination is operator-configured rather than
recipient-supplied, so it is not the kind that goes stale. (2) A suppressed send writes **no receipt**, or
the next check would read its own non-delivery as delivery history.

**The operator surface shipped the same day**, closing the 2026-09-06 decision:

* `GET /notifications/suppressions` — the suppressed destinations and why, plus `armed` and the TTL in
  force. ⚠ It reports `armed: false` on a deployment that cannot suppress rather than an empty list:
  "nothing is suppressed" and "suppression cannot run here" are different answers and must not look alike.
  Capped at 500 with `?limit=`, reporting the TRUE `total` and a `truncated` flag — a diagnostic read is
  not an export.
* `DELETE /notifications/suppressions?target=…` — 🔴 **records an override; deletes nothing.** ⚠ `target`
  is a QUERY parameter, not a path segment: a webhook destination is a URL and would not survive one,
  and the value is matched against stored receipts, never resolved as a path. Gates: blank target → 422;
  a store that cannot hold an override → 409 naming `-Ddelivery.receipts.backend`, because succeeding
  would report a change that did not happen.

**Decision 2026-09-07 (operator) — how "unsuppress" works.** An override row (`db-layer.md` §3.13)
forgives history up to its own timestamp; a LATER bounce or complaint is not covered and re-suppresses on
its own. Rejected: pruning the target's receipts, which would have destroyed the audit trail AND
permanently masked a genuinely dead address. That choice is why the mechanism needs no clearing job and no
expiry — it is one timestamp comparison.

Tests: `SuppressionListTest`, `ControlApiSuppressionsTest` (one case per gate, over real HTTP, including
the disarmed deployment and the later-bounce re-suppression).

🔴 **Two registration sites a capability-gated route needs, both found by a guard rather than by review.**
(1) `CapabilityManifest` — `CapabilityManifestTest` fails on any `withCapability` site missing from the
manifest, and on any manifest entry with no site. (2) **Route order**: matching is FIRST-MATCH in
`ControlApi`'s `RouteModule` registration order, and `NotificationRoutes` registers before
`DeliveryStatusRoutes` — so its archive-by-id catch-all `DELETE /notifications/([^/]+)` swallowed
`DELETE /notifications/suppressions` and answered *"no notification 'suppressions'"*. ⛔ A literal path
cannot outrank a parameterised one registered earlier; the catch-all now carries `(?!suppressions$)`, and
**any future exact `/notifications/<word>` DELETE in another module must be added to that lookahead.**

⚠ A third trap, test-side: `V1Body.of(body)` **already peels the envelope's `data`** (the same unwrap the
SPA's `v1Interceptor` does). Reaching for `.get("data")` on top of it lands on `null` and surfaces as an
NPE that reads exactly like a handler bug. Gate tests asserting only a status code pass either way, so
this appears in the HAPPY PATH alone.

### SMTP transport security — both halves are now closed

✅ **`NOTIFY-SMTP-TLS-VERIFY-1` closed 2026-09-17.** `SmtpEmailChannel` now sets
`mail.smtp.ssl.checkserveridentity` unconditionally. ⚠ The defect was narrower than filed: `javax.mail 1.6.2`'s
`SocketFetcher` already used `SSLSocketFactory.getDefault()`, so the certificate CHAIN was always validated —
only the hostname check was missing, making it "any CA-valid certificate, for any name". No escape hatch was
added: a self-signed relay already failed, and `-Djavax.net.ssl.trustStore` serves that case with
authentication left ON.

⚠ **Release-note-worthy:** verification is always on and not configurable. A relay whose certificate does not
name `notify.smtp.host` goes from silently trusted to failing to deliver, and **this will not be noticed on its
own** — notification failures are logged and isolated by `NotificationService`, never surfaced.

✅ **`NOTIFY-SMTP-STARTTLS-OPPORTUNISTIC-1` closed 2026-09-17 — same call, same direction.** `SmtpEmailChannel`
now sets `mail.smtp.starttls.required=true` alongside `.enable` whenever `notify.smtp.starttls` is on (the
same existing config flag — no new knob was added). `mail.smtp.starttls.enable` alone is opportunistic: a
relay that simply declines to advertise `STARTTLS` still got a plaintext session, the hostname-identity check
above never ran because no TLS session existed, and SMTP AUTH credentials went out in the clear regardless of
the fix above — so the parent row's threat model was only half closed until now.

⚠ **Release-note-worthy, and a LARGER behaviour change than the hostname fix:** an install that set
`notify.smtp.starttls=true` against a relay that never actually offered `STARTTLS` — and so has been silently
sending plaintext mail and cleartext credentials — now fails to deliver instead of falling back silently.
That is the intended outcome: a loud failure converts a silent weakness, which is exactly the call this
session has made elsewhere for a genuine silent security gap (`NO-RATE-LIMIT-EXPENSIVE-ROUTES-1`,
`UI-CAPABILITY-AFFORDANCE-1`). ⛔ No escape hatch to restore the opportunistic behaviour — the operator's
options are to point at a relay that offers `STARTTLS`, or to stop setting `notify.smtp.starttls`. As with the
hostname fix, notification failures are logged and isolated by `NotificationService`, never surfaced, so an
affected install may not notice the switch to "loud failure" is happening beyond emails simply stopping.
Documented in `SmtpEmailChannel`'s javadoc; proved by `SmtpEmailChannelStarttlsRequiredTest` (a fake relay that
never advertises `STARTTLS` is refused, and no `AUTH` line ever reaches it).
