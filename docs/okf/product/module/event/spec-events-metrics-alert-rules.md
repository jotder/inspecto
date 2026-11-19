---
type: Concept
title: Events & Metrics — Authorable notification rules and Alert Rule authoring
description: Part 3 of 4 of Events & Metrics: Alert-rule authoring (shipped 2026-07-09)
tags: [control-plane, events, metrics, observability, deadlock]
timestamp: 2026-07-16T00:00:00Z
---

# Events & Metrics — Authorable notification rules and Alert Rule authoring

Part 3 of 4 of [Events & Metrics](spec-events-metrics.md) · previous: [Notifications and audit — the audit hash chain onward](spec-events-metrics-audit-chain.md) · next: [Bounce handling, rate-limit classes and unmodelled keys](spec-events-metrics-decisions-keys.md).

## Notifications & audit trail (shipped 2026-06-29, `ddfa288`) (continued)

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
* **Security triggers T1–T5** (2026-09-28, ses-sns-adapter-design §8) — `SecurityTriggers` (engine,
  `com.gamma.notify`) is a second `EventLog` subscriber beside `NotificationService`, wired per
  `CollectorService`; it reads the audit rows and emits `EventType.SECURITY_TRIGGERED` back into the same log,
  which the built-in rule `builtin-security-triggered` maps to category `security` (now `available = true`).
  | id | input | key | default (`-Dnotify.security.<id>.threshold` / `.windowMinutes`) |
  |---|---|---|---|
  | t1 | `ACCESS_DENIED` 403, not a delivery-status path | actor | 20 in 10 min |
  | t2 | `ACCESS_DENIED` 401 | audit IP | 50 in 10 min |
  | t3 | `ACCESS_DENIED` 403 on `/public/delivery-status/{adapter}` | adapter | 10 in 60 min |
  | t4 | `AUDIT` of a 2xx (not 202-held) `PUT /access/roles` — the only authoring door to `roles.toon` (import refuses reserved files) | actor | every change |
  | t5 | `AUDIT` `auth.exchange` 200 carrying `geo_country` | subject | a country new for a subject already seen |
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
  * **T5 — sign-in from a new country** (2026-09-28, D12). Keys on the `auth.exchange` row's actor, which
    `AuthRoutes.sessionSubject` sets to the Subject the edition's `Authenticator` verifies the MINTED access token
    to (else `unknown`, which T5 skips). A subject's FIRST observed country is its baseline and does not fire
    (otherwise every first sign-in after a restart would page every admin); a later, unseen country fires once.
    Countries live in the same LRU, at most `MAX_COUNTRIES_PER_SUBJECT` = 16 per subject, in memory — a restart
    re-baselines. Silent without GeoIP.
* **GeoIP on the audit trail (D12, operator 2026-09-28): operator-supplied, country only.** Inspecto bundles NO
  GeoIP database and never downloads one. The operator obtains one — MaxMind GeoLite2 (free account and licence
  key, GeoLite2 EULA, attribution, and a duty to apply each update within 30 days) or DB-IP Lite (CC BY 4.0,
  attribution) — and sets `-Dgeoip.db=<path to .mmdb>`; unset means off. `AuditTrail.located` stamps
  `AuditAttrs.GEO_COUNTRY` (`geo_country`, ISO 3166-1 alpha-2, upper-cased) and `GEO_DB_BUILD` (`geo_db_build`,
  the database build epoch, so a stale database is visible) on every audit and access-denied row whose
  `ApiContext.ip` resolves — the F3 trusted-proxy IP, so a spoofed `X-Forwarded-For` cannot choose a country.
  There is **no city key**, and `GeoCountryResolver.Geo` has no city field, so no implementation can supply one.
  The seam is the `@PublicApi` SPI `GeoCountryResolver` (ServiceLoader, via `GeoCountryResolvers.active()`); a
  resolver that throws costs only the geo attributes, never the row. ✅ **The MaxMind binding shipped
  2026-09-29:** `com.gamma.geocountry.MaxMindGeoCountryResolver` in `inspecto-geo-country` (so Standard/Enterprise
  only; Personal keeps the inert one-WARN path), on `com.maxmind.db:maxmind-db:4.2.0` (Apache 2.0, no runtime
  dependencies, `java.base` only, so the jlink `$runtimeModules` needed no change), shaded into the security
  sidecar. It reads `-Dgeoip.db` once, lazily, into memory (`FileMode.MEMORY`: the default memory-mapped mode
  keeps the file locked on Windows, so the operator could not replace it); takes `country.iso_code` only and
  never reads a city; `geo_db_build` is the file's `build_epoch`. It parses only IP literals
  (`InetAddress.ofLiteral`, so no DNS), answers empty for every address `EgressPolicy.deniedClass` calls
  non-public (reused, not mirrored: `0.0.0.0/8`, loopback, link-local, multicast, broadcast, RFC 1918, CGNAT
  `100.64/10`, ULA `fc00::/7`, embedded-IPv4 forms, this host) and for not-in-database addresses, and fails soft:
  a missing, unreadable or over-cap database (`-Dgeoip.db.maxBytes`, default 256 MB, checked before reading)
  or an `OutOfMemoryError` while loading it logs one WARN and resolves nothing for the rest of the run (a
  restart picks up a repaired file); other `VirtualMachineError`s propagate.
  Location is personal data; it rides on the audit row under the audit trail's retention.
  Tests: `SecurityTriggersTest` (T5 fires once on a new country; repeat and baseline silent; no-geo, refused and
  `unknown` rows skipped), `ControlApiGeoIpTest` (real HTTP with a fake resolver: country + db build on the
  sign-in row and no city key; an untrusted peer's XFF is located as the socket peer; `-Dgeoip.db` set without a
  reader, and unset, write no geo and no error; T5 fires once over HTTP and cannot be steered by a spoofed XFF),
  `MaxMindGeoCountryResolverTest` (against a real `.mmdb` that the test helper `TestMmdb` writes at test time,
  because MaxMind's writer is not in the offline cache: country + build epoch; private and not-in-DB addresses
  empty; hostnames and garbage never throw; missing, unset and corrupt DB soft; concurrent first lookups; the
  ServiceLoader registration), `MaxMindAuditRowTest` (real HTTP, real SPI discovery, no fake: the sign-in row
  carries `geo_country` and `geo_db_build` from the file, and no city although the record holds one).
* Deferred to editions/follow-ons: time-based retention sweep.

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
  `DeliveryStatusRoutes.MAX_CALLBACK_BYTES` = 512 KiB (raised from 256 KiB 2026-09-28: a 256 KiB SNS message
  outgrows it inside its envelope) — over it is **413 `PAYLOAD_TOO_LARGE`**, refused on
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
complaint, soft-bounce retry scheduling, a UI, and the `deliverWithReceipt` SPI escape hatch. (The SES/SNS
adapter was later built — next section.)

### SES via SNS (`D8-SES-SNS-1`, built 2026-09-28 on recorded fixtures)

⚠ **Fixtures-verified; live checks deferred until a customer names SES (operator 2026-09-29)** — whether SES
echoes our `Message-ID` (D5) and the real signing-certificate path grammar and chain are tracked as P3
`SES-SNS-LIVE-CHECKS-1`.

Adapter id `ses` (`SesSnsDeliveryStatusAdapter`, `inspecto-connectors`), so the SNS HTTPS subscription URL is
`/api/v1/public/delivery-status/ses`. Operator decisions D1–D11 of
[`ses-sns-adapter-design.md`](../../../../archived-documents/plans-archive/ses-sns-adapter-design.md) §9 accepted 2026-09-28; the design's
§3 is the security review. ⚠ **No SES account was available**: the fixtures (`src/test/resources/sns/`) are
built from AWS's documented shapes and re-signed at test time with a keytool-minted test CA — the live checks
owed are listed below.

* **SPI:** `DeliveryStatusAdapter.control(raw)` (a `default`, D10) runs after `verify`: a verified message with no
  delivery events answers **200 `{"control": kind}`**, a replayed `MessageId` `{"control":"duplicate",
  "duplicate":true}` — never 422, which SNS would retry.
* **`verify` order:** flat-envelope parse (streamed, strings only, depth 1, duplicate keys refused) →
  `SignatureVersion` exactly `2` (v1 refused and logged once per topic, D4) → `TopicArn` on
  `notify.deliverystatus.sns.topicArns` **before any lookup** → `Timestamp` within 3600 s past / 300 s future (D6)
  → certificate → SHA256withRSA.
* 🔒 **The fetch** (`SnsSigningCerts`, `SnsHttpsFetcher`; its own commit and adversarial review): the host is
  derived from the allowlisted ARN's partition and region, never a pattern (`sns.s3.amazonaws.com` is an S3
  bucket); the URL's raw authority must equal it exactly; path `/SimpleNotificationService-<32 hex>.pem`.
  `EgressPolicy` classes, resolved once and dialled at the checked address; private ranges only with
  `…sns.allowPrivateAddresses=true` (D3) and `fd00:ec2::254` refused even then (it sits in the liftable ULA
  range). TLS SNI + endpoint identification, no redirects, 200 only, 16 KiB, 3 s connect, a **5 s watchdog on
  the whole exchange** (per-read timeouts do not stop a byte-a-second server). Cache 8, negative cache 10 min
  (1 min for a transport error), single-flight, **12 fetches/hour** for never-seen URLs; a URL that once passed
  trust is re-fetched outside the budget. Pinned PEM (`…sns.signingCert`, D1) never fetches. Proxy only with
  `…sns.useProxy=true` (D9). The adapter refuses to arm if `jdk.internal.httpclient.disableHostnameVerification`
  is set.
* **Trust** (`SnsCertTrust`): PKIX to the JVM trust store (revocation off), validity, SAN `dNSName`
  `sns.amazonaws.com` or the regional host (CN only when there is no DNS SAN), RSA ≥ 2048. Intermediates from
  the PEM or the TLS session are path material, never anchors.
* **Confirmation** (`SnsSubscriptionConfirmer`): `SubscribeURL` must be exactly `https://<host>/?Action=
  ConfirmSubscription&TopicArn=<ours>&Token=<ours>`; one background thread, queue 4 (D7), its own budget.
  `autoConfirm=false` fetches nothing and writes an AUDIT row with the Token (D8). An `UnsubscribeConfirmation`
  is audited and never re-subscribes.
* **Mapping** (`SesEventMapper`, design §4.3): Transient and Undetermined bounces → `BOUNCED_SOFT` (the existing
  soft-bounce retry), `DeliveryDelay` → `UNKNOWN` (D11), Reject / Rendering Failure → `BOUNCED_HARD`. Correlation
  reads our `Message-ID` from `mail.commonHeaders.messageId`, then `mail.headers` (D5, provisional). A verified
  event that is not ours is an `UNKNOWN` under a `ses:` id → 202.

**Deploy note.** Set the topic attribute `SignatureVersion=2`, set `-Dnotify.deliverystatus.sns.topicArns`, and
subscribe `https://<public host>/api/v1/public/delivery-status/ses`. For restricted egress use a pinned
`…sns.signingCert` plus `autoConfirm=false` (zero outbound) and confirm from the audit row with
`aws sns confirm-subscription`. When AWS rotates its certificate, pinned mode fails closed (403s, trigger T3).

**Owed to a live account:** whether SES echoes OUR `Message-ID` (D5 — otherwise an `X-SES-MESSAGE-TAGS` tag and a
`SmtpEmailChannel` change); the real certificate path grammar (32 hex) and subject name; whether the served PEM
chains to the JVM trust store (it may be leaf-only, in which case verification fails closed and the TLS-session
intermediates are what carry it); `Subject` absent vs JSON null; the SNS retry horizon behind the 3600 s window.

**Residual risk, accepted by the design review.** (1) Trust rests on the JVM CA store: anyone who can get a
certificate for `sns.amazonaws.com` from a trusted CA can forge messages; pinned mode narrows that to one
certificate. (2) Revocation is not checked, because checking it is another outbound fetch; exposure is bounded
by the certificate's validity. (3) A VPC-endpoint deployment that opts into private addresses (D3) loses the
private-range block; the ARN-derived host and TLS verification remain its whole defence. (4) The envelope is
parsed before the signature is checked, so the JSON parser is attack surface, bounded by the size cap, depth 1
and a tree parse with no type binding.

Tests: `SnsFixturesTest`, `SnsVerificationTest` (S1), `SesSnsAdapterEventsTest` (S2), `SnsFetchSecurityTest` (S3,
a real loopback `HttpsServer`, never amazonaws.com), `ControlApiDeliveryStatusTest` (the control path).

**Receipt retention (shipped 2026-07-26).** The `receipt_prune` maintenance task forgets receipts sent
before `retention_days` (required, like every other prune — deliberate forgetting), with a
`countPrunable` dry-run preview. It reaches the store through `JobService.deliveryReceiptStore`, a hook
this added: the store was wired into `NotificationService` **only**, so nothing in the job layer could see
it. Fail-open — no store attached is a reported no-op, never a throw. ⚠ `InMemoryDeliveryReceiptStore`'s
oldest-first eviction at 5000 is an **unconditional backstop on `add()`, not a retention policy**; bounding
receipts by age still requires scheduling the task, and receipts accrue per *external delivery*, i.e.
faster than notifications. Test: `MaintenanceLibraryTest` (`receiptPrune…`).

### Mail attachments (`ASSURE-XLSX-ATTACHMENTS-1`, built 2026-09-29 — reverses `BI-4`'s "path, not attachment")

* `MailAccess.send(to, cc, subject, body, attachments)` is the abstract method; the four-argument form is a
  default that sends none. `MailAccess.overChannels()` hands attachments to the new
  `NotificationChannel.deliver(n, target, List<MailAttachment>)`. Its **default refuses** a non-empty list
  (`UnsupportedOperationException`): a channel that cannot carry files must not silently drop a report the
  author asked to attach. `SmtpEmailChannel` overrides it and builds `multipart/mixed` (text first, then
  one base64 part per file).
* **One policy home: `com.gamma.notify.MailAttachments`** (`inspecto-engine`). Caps: `notify.mail.attachment.max.bytes`
  (default 10 MiB) per file and `notify.mail.message.max.bytes` (default 20 MiB) per message, checked by
  `MailAccess` AND again inside the channel. Content types: allowlist keyed by extension, `csv json pdf png xlsx`
  only. Filenames: `sanitizeFilename` keeps `[A-Za-z0-9._-]`, takes the last path segment, drops leading dots and
  caps at 100 chars; the `MailAttachment` record applies it in its constructor, so no caller can hand a raw
  name (CR/LF = header injection) to a channel.
* ⛔ **Attachments come only from the sending Job's own Run Artifact.** `MailAttachments.fromRunArtifact` re-jails
  the file through `PathJail.requireJobPathUnderAny` (allowed roots, `*.secrets` refused on the spelling and the
  real path) and checks the size before reading. No parameter anywhere names a path to attach. The one caller is
  `ReportJob` (`recipients` + `attach: true`); `mail.send` does not attach, because it has no Run Artifacts of its own.
* 🔴 **Recipient domain allowlist — attachments are OFF until an admin configures it** (operator 2026-09-29).
  `MailAttachDomains` reads the Space's `mail-attachments.toon` (`allow: [example.com, …]`), stored like the
  egress allowlist: `GET /settings/mail-attachments`, `PUT /settings/mail-attachments` (canAdminister, 422 on a
  non-domain, audited `mail-attachment-domains.changed` with before/after, reserved from imports in
  `ReservedConfigPaths`). It is **EMPTY by default and empty denies**, so a Job with `attach: true` fails its Run
  with "attachments are off in this Space" until a domain is named. `MailAccess.overChannels()` checks it on
  EVERY send that carries an attachment, over every To AND Cc: the domain must equal an entry or be a subdomain
  of one (`notexample.com` does not match `example.com`), compared lower-case on the punycode (IDN) form. One
  address outside refuses the whole send. A missing or invalid file reads as empty (fail closed).
  🔴 Each To/Cc entry must be exactly ONE plain mailbox (`x@d` or `Name <x@d>`): RFC 822 group syntax
  (`grp: a@evil.test;b@example.com`, which JavaMail delivers to both), a comma-joined second address, quoted or
  commented local parts, source routes, and `%` / `!` routing in the local part are all refused, not parsed. Tests:
  `MailAttachDomainsTest`, `ControlApiActionRequestsTest.theMailAttachmentDomainAllowlistIsEmptyByDefaultAdminOnlyAndValidated`.
* 🔴 **Turning `attach: true` on is APPROVAL-REQUIRED — four-eyes, with or without a policy** (operator 2026-09-29).
  ONE gate, `JobWriteGuard` (`inspecto/src/main/java/com/gamma/control/JobWriteGuard.java`):
  `JobWriteGuard.mandatory` runs INSIDE `PendingChanges.hold` for `kind = job`, so every route that holds a
  job write gets it with no call site to forget (`/jobs` POST/PUT/enable/disable/reschedule, `/config/write`,
  `/config/patch`). It returns a `canApproveChanges` four-eyes rule when the proposed Job is a report with
  `attach: true` and either the current one is not, or any of `type attach recipients dataset scope measures
  group_by format out_dir limit connection use` changed. That set decides what data leaves and to whom, so an
  approved attach Job cannot be quietly re-pointed. A schedule-only edit falls back to the normal policy. With a
  job policy too, its approver applies and four-eyes is forced on. This fits the Pending Change design unchanged:
  approve reads the approver and four-eyes flag from the RECORD, never the live policy.
  **Imports cannot be one Pending Change**, so `ImportCapabilityGuard.requireIfAdministerOnly` (the one path every
  imported job takes: `/bundle/import` items, pipeline closures, the `/import` zip, a new Space's bundle) calls
  `JobWriteGuard.refuseImport`: the whole import is refused 403 *"a report job that attaches data needs
  approval; create it through /jobs"* before any item is written. `JobWritersTest` enumerates
  `ConfigWriteFunnelTest`'s route inventory and fails on a config-writing route that neither holds, calls
  the guard or the import gate, nor is listed with a reason.
  🔴 **The real lock is at RUN time** (round 3, operator 2026-09-29). Write paths kept being found:
  `*_job_template.toon` expansion at load, hand edits, the zero-Space recovery create. So `ReportJob` with
  `attach: true` sends only the exact Job version a four-eyes approval fingerprinted (`AttachApprovals`,
  `platform/inspecto-engine/src/main/java/com/gamma/job/AttachApprovals.java`). The fingerprint is a SHA-256 over the
  Job as the scheduler holds it (template-EXPANDED): its name, type and every param except the
  server-stamped author keys, plus the Dataset definition it reads. The approve path
  (`PendingChangeRoutes.recordAttachApproval`) records it in `attach-approvals.json`, which is reserved from
  every import. Anything else fails the Run with an AUDIT row (`report.attach.refused`) and a
  `report.attach.refused` Signal: "attach not approved for this job version; re-approve". Whether a Job
  attaches is decided by ONE predicate, `AttachApprovals.attaches`, which `JobWriteGuard` also uses. It reads
  the AUTHORED config only, never trigger args or a bound Signal. The write-time holds stay as the friendly
  path. As defence in depth an import refuses a `*_job_template.toon` whose report block carries any
  `attach` but a literal false (a `${placeholder}` included).
  🔴 **Round 5: the approval file proves nothing on its own.** Approve writes a random `attachNonce` into the
  approved Pending Change (HMAC-signed by `PendingChanges.save`) AND into the approval record. At run time
  `AttachApprovals.approved` also asks the installed `AttachApprovals.Verifier`, which the control plane
  installs as `PendingChangeRoutes.verifyAttachApproval`. It honours the record only if the Pending Change it
  names:
  - exists and passes its MAC;
  - is `approved`;
  - is for this Job;
  - carries the same nonce;
  - fixed the same `attachFingerprint`.

  **Round 5 item 2:** the fingerprint resolves the Dataset relation under the report Job's OWN data root. At
  hold and approve it takes `JobService.dataDir()`, and at run time the `dataDir` `ReportJob` holds. So a
  `physicalRef` Dataset hashes its real glob. A Dataset that is missing or cannot be resolved is NOT
  approvable: 422 at the hold, 409 at approve, and a refused run, never a hashed error string.
  ⚠ The resolved relation can list files when the Consignment catalog marks some unreadable, so such a change
  un-approves the Job until it is re-saved.
  With no verifier installed, i.e. no control plane, nothing attaches (fail closed). A copied record, a forged
  nonce, a fingerprint edited to match, an edited Pending Change (its MAC fails) and a withdrawn one all
  refuse (`ControlApiPendingChangesTest.aCopiedForgedOrUnbackedApprovalRecordIsNeverHonoured`).
  🔴 **Round 4: the approval is pinned to content fixed at HOLD time** (the same shape as `PublicationApproval`; both now
  sit on ONE mechanism, `ApprovalFingerprint`, see [studio](../../layer/product/plan-studio.md)).
  `AttachApprovals.fingerprint` hashes ONLY what decides what is sent and to whom:
  - the `SENSITIVE` keys (`type attach recipients dataset scope measures group_by format out_dir limit
    connection use`), template-EXPANDED against the Space's `*_job_template.toon`;
  - the Dataset definition;
  - its resolved relation SQL (`DatasetRelation.relationSql`), so a view's SQL is inside it. Cron and `enabled`
    are not.

  `PendingChanges.hold` computes it from the PROPOSED write and stores it on the Pending Change as
  `attachFingerprint`, but only when the attachment rule itself raised the hold. So approving a policy-held
  cron edit blesses nothing.

  Approve (`PendingChangeRoutes.requireAttachUnchanged`) re-hashes the proposal against today's Dataset,
  view and templates, and refuses 409 if anything moved. It then records the STORED fingerprint, never the
  live Job, bound to the Pending Change id plus a nonce. Any write whose fingerprint is not the approved
  one is held whatever the policy, so re-saving the Job is the re-approve path. A delete through any
  holding route revokes the approval (`JobWriteGuard.revokeOnDelete`).

  The import guard judges a carried job AFTER expanding its template, against the templates the import
  carries and the Space's own, so a plain report template plus `attach: "true"` in the job file is refused.
  ⚠ **With no authenticator (a Personal / no-auth build) four-eyes cannot tell the author from the approver**:
  every request is the same anonymous actor, so the hold only delays the write. In that build the recipient
  domain allowlist above is the ONLY real control on where an attachment can go.
  Tests: `ControlApiPendingChangesTest.turningAttachOnIsHeldForFourEyesEvenWithNoPolicy`,
  `…configWriteAndPatchCannotTurnAttachOnWithoutFourEyes`,
  `ControlApiBundleNewKindsTest.aBundleCarryingAnAttachingReportJobIsRefusedBeforeAnyWrite`, `JobWritersTest`.
* Tests: `MailAttachmentsTest` (jail, `config.secrets`, allowlist, both caps, filename), `SmtpEmailChannelAttachmentTest`
  (MIME structure after serialise-and-reparse, header injection, the channel's own cap check), `ReportJobDeliveryTest`
  (the Job attaches its own artifact; an over-cap file fails the Run with the reason). ⚠ No live-SMTP round trip: the
  only SMTP stub in the tests (`SmtpEmailChannelStarttlsRequiredTest`) is a STARTTLS-refusal relay, not a mail sink.

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
  then `open(INCIDENT, …)`) — see also `../processor/spec-jobs.md` (recon breaches) and `../processor/spec-decision-rules.md` (`create-alert`).
  Since 2026-08-10 the promoted Incident also carries an **`ESCALATED_FROM` link to the ALERT object**
  that raised it (actor `alert-rule:<name>`, mirroring the Case Rules auto-linker), so the correlation is
  traversable in the `OBJECT_LINK` graph rather than only implied by the shared `rule`/`causedByEvent`
  attributes — matching what operator-created Incidents (`POST /objects`, ≥1 link mandatory) always had.
  ⚠ A *suppressed* promotion adds no edge: if the earlier ALERT was resolved while its INCIDENT is still
  open, the re-fire's fresh ALERT stays unlinked, because `IncidentAccess` reports suppressed and dry-run
  alike as an empty result. Gate coverage: `AlertServiceTest.thePromotedIncidentIsLinkedEscalatedFromItsAlert`.

The matching UI surfaces are the [events](user-guide-events.md) and
[dashboard](../../layer/product/user-guide-dashboard.md) screens in the frontend bundle.
Production-investigation detail: [`docs/ADVANCED_GUIDE.md`](../../../../ADVANCED_GUIDE.md).
