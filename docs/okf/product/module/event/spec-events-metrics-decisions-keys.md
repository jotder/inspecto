---
type: Concept
title: Events & Metrics — Bounce handling, rate-limit classes and unmodelled keys
description: Part 4 of 4 of Events & Metrics: Decision 2026-09-06 — bounce/complaint handling stays manual until receipts persist; Link Analysis rate-limit classes; Unmodelled keys on a Notification Rule and Channel (MODULE-REORG-P4-2); Unmodelled keys on a Saved View (MODULE-REORG-P4-2)
tags: [control-plane, events, metrics, observability, deadlock]
timestamp: 2026-07-16T00:00:00Z
---

# Events & Metrics — Bounce handling, rate-limit classes and unmodelled keys

Part 4 of 4 of [Events & Metrics](spec-events-metrics.md) · previous: [Authorable notification rules and Alert Rule authoring](spec-events-metrics-alert-rules.md).

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

## Link Analysis rate-limit classes

`RateLimiter` throttles per subject (IP when unauthenticated) with fixed in-memory token buckets and answers 429 `RATE_LIMITED`. Link Analysis routes are classified by `LinkAnalysisRateClasses`: twelve expensive ones (`/geo/*` computations, `/inv/projection*`, `/inv/reference-rows`, `/inv/traversal/recursive-paths`, `/inv/pattern/*`, `/inv/schema/overlap-profile`, `POST /inv/graph/runs`, `POST /inv/index/builds`) share their own bucket `RateLimiter.linkAnalysis()` (burst 30, 1 per 2 s since 2026-10-14; since 2026-10-15 the install limits `rate.link_analysis_burst` / `rate.link_analysis_per_s` in `la-limits.toon` win over the `control.rateLimit.linkAnalysis.*` properties and a `PUT /system/link-analysis/limits` rebuilds the bucket, measured: opening an Investigation spends 0 and each expensive gesture 1, see `LA-DAILY-INGEST-1`; tunable with `-Dcontrol.rateLimit.linkAnalysis.capacity` / `.refillPerSecond` — operator, 2026-10-06 — and a malformed or non-positive value keeps the default, never disables it); everything else is explicitly exempt. `LinkAnalysisRateClassCoverageTest` fails if a route in `the la-api and geo-link manifests' `provides.routes`` or `AbsentModuleRoutes.SURFACE` has no class. Deliberate limits: only the Link Analysis bucket and (since 2026-10-18, so `MixedLoadBenchmark` measures load rather than the throttle) the `/bi/query` dashboard bucket (`-Dcontrol.rateLimit.dashboard.capacity` / `.refillPerSecond`, default burst 120, 2/s) are configurable, no `Retry-After`, per node. **Idle-bucket eviction (2026-10-06):** every `RateLimiter` sweeps its per-subject map at most once a minute and drops only buckets that have refilled to FULL capacity — a full bucket is indistinguishable from a fresh one, so waiting less than a full refill (`capacity / refillPerSecond`, 60 s for the standard and Link Analysis budgets) never resets a budget; a bucket is marked evicted under its lock so a concurrent request retries rather than spending from an orphan. Memory is bounded by the keys seen within one refill window (no hard cap: a cap would have to evict non-full buckets and so reset budgets). `RateLimiter.useClock` / `ControlApi.useRateLimitClock` inject a nanosecond clock; `ControlApiRateLimitTest` freezes it, which removed the 121st-`/bi/query` flake (under load the 120 real-HTTP calls took over 0.5 s, the 2/s bucket refilled one token, and the request reached the handler, which answers 503 here). **Link Analysis limits were kept at burst 20, 1 per 3 s on 2026-10-06 and retuned to burst 30, 1 per 2 s on 2026-10-14:** no SPA screen calls the eleven expensive routes yet, so there is no measured fan-out per opened Investigation; a burst of 20 already covers one opening firing several projections/traversals; retune via the two system properties once a client exists.

## Unmodelled keys on a Notification Rule and Channel (`MODULE-REORG-P4-2`)

`/notifications/rules*` and `/notifications/channels*` rebuild the stored component from `NotificationRule.toMap()` / `ChannelConfig.toMap()`, so any key they do not model used to vanish behind a 200. Now an author-owned `x-` key is kept (create, update, a held save once approved, a bundle import) and returned by GET, and any other unmodelled key is refused 422 `ERR_UNKNOWN_CONFIG_KEY` naming it (`AuthorKeys`; `ComponentRoutes.validateKind` applies the same key check to the raw `/components` door and to import). The store's own `name` stamp is accepted back. A Rule PUT replaces (send the `x-` key back or lose it); a Channel PUT merges. Pinned by `RebuiltWritersUnmodelledKeysTest`.

## Unmodelled keys on a Saved View (`MODULE-REORG-P4-2`)

`POST /events/views` read only its named fields (`name` and the seven search keys), so any other key vanished behind a 200 - including a nested `filters` object, which saved the view with no filter at all. Now an `x-` key is kept (`SavedView.extra`: response, `GET /events/views`, the JSON store on reload, and a bundle `saved-view` import) and any other key is refused 422 `ERR_UNKNOWN_CONFIG_KEY` naming it (send the search keys flat; the server stamps `createdAt`); a bundle item with an unknown key fails that item. Pinned by `SavedViewUnmodelledKeysTest`.
