# SES/SNS delivery-status adapter — design and security review (`D8-SES-SNS-1`)

> **Status: DESIGN ONLY 2026-09-24 — nothing built.** ⏸ **The SES/SNS adapter (§2–§5, D1–D12) is ON HOLD (operator, 2026-09-25)** until an SES deployment is named. D13 was answered 2026-09-25 by per-user notification read state. Still live: §7 per-user preferences, §6 GeoIP, §8 security triggers, D14. F1 (body cap) and F3 (`X-Forwarded-For`) were fixed 2026-09-24.
> BACKLOG row *Notification residuals (`D8-SES-SNS-1`)*, §3.5. Owner concept:
> [`okf/backend/control-plane/events-metrics.md`](../okf/backend/control-plane/events-metrics.md)
> § *Inbound delivery-status webhooks*. Grounded against `master` at `7bb36309c`.
>
> The row names four residuals. The SES/SNS adapter gets most of this document because it adds the
> product's first **outbound network fetch that an unauthenticated caller can cause**, and the row
> requires that fetch to get its own security review and its own commit. The other three (GeoIP,
> per-user preferences, security triggers) are designed in §6–§8. While grounding them this review
> found **two defects in code that has already shipped** (§1.4 F1, F2) and **one that the adapter
> would inherit** (F3).

---

## 1. As-built baseline

### 1.1 The inbound callback seam (shipped 2026-07-26)

| Fact | Where |
|---|---|
| The SPI is `DeliveryStatusAdapter`: `id()`, `verify(raw, headers)` returning a boolean that must never throw, `parse(raw)` returning a list of events, `configured()`. It is `@PublicApi`. | `inspecto-engine/src/main/java/com/gamma/notify/DeliveryStatusAdapter.java:35-60` |
| The contract says `verify` must reject a **stale** timestamp, and that `parse` runs only after `verify` succeeds. | same file `:22-27` |
| Adapters are discovered once per API instance. An unconfigured adapter is left out of the list. | `inspecto/src/main/java/com/gamma/control/DeliveryStatusRoutes.java:46-54` |
| Route `POST /api/v1/public/delivery-status/{adapterId}`. Gate order: unknown adapter 404, then `verify` false 403, then no events 422, then every id unknown 202, otherwise 200. | same file `:58`, `:126-163` |
| The route hashes `rawBody`, never a re-serialised map. | same file `:130-132` |
| The path is exempt from platform authentication and from the ABAC PEP. | `inspecto/src/main/java/com/gamma/control/ControlApi.java:838-840`, `:846`, `:880` |
| It is listed in the capability manifest as a `self-verifying-public` exemption. | `inspecto/src/main/java/com/gamma/control/CapabilityManifest.java:277` |
| Shipped adapters: SendGrid (ECDSA P-256) and a generic HMAC adapter. | `inspecto-connectors/src/main/resources/META-INF/services/com.gamma.notify.DeliveryStatusAdapter` |
| Freshness helper: `DEFAULT_FRESHNESS_SECONDS = 300`, checked in both directions. | `inspecto-connectors/src/main/java/com/gamma/connect/notify/DeliveryIds.java:17`, `:40-44` |
| Correlation: we mint `<inspecto.{deliveryId}@{domain}>` as the SMTP `Message-ID` and read it back with `inspecto\.([A-Za-z0-9]+)@`. | `inspecto-notify-channels/src/main/java/com/gamma/notify/channel/SmtpEmailChannel.java:153-163`; `DeliveryIds.java:24`, `:30-34` |
| Receipts: `stamp` keeps any status already recorded, so the first observation of a status wins. An unknown id is a normal outcome. | `inspecto-engine/src/main/java/com/gamma/notify/DeliveryReceiptStore.java:21-28` |

### 1.2 Soft-bounce retry (shipped 2026-09-15)

`SoftBounceRetryTask` collects `softBounceRetryCandidates(maxAttempts)` (default 3 attempts, 60-minute
backoff). It applies the backoff in the task, not in the store, and counts every attempt whether it
succeeded or not (`inspecto-engine/src/main/java/com/gamma/job/SoftBounceRetryTask.java:43-87`). The
backoff clock starts when we **learned** of the bounce, not at `sentAt` (`:96-101`).
`NotificationService.retrySoftBounce` skips a notification that has been deleted, a channel config
that is gone or disabled, a kind with no transport, or a target that is suppressed
(`inspecto-engine/src/main/java/com/gamma/notify/NotificationService.java:346-380`). **Consequence for
SES:** once an SES `Transient` bounce is mapped to `BOUNCED_SOFT`, the existing retry task handles it
with no new code.

### 1.3 Outbound HTTP clients and SSRF guards

| Client | Redirects | Timeouts | Egress restriction |
|---|---|---|---|
| `WebhookChannel` | JDK default (`NEVER`), not set explicitly | connect and request both `notify.webhook.timeout.seconds` (default 10) | **none**: the URL is whatever the operator configured (`inspecto-notify-channels/src/main/java/com/gamma/notify/channel/WebhookChannel.java:52-56`, `:87-90`) |
| `HttpWebhookSinkTransport` | `NEVER`, set explicitly | connect 10 s | none (`inspecto-notify-channels/src/main/java/com/gamma/notify/channel/HttpWebhookSinkTransport.java:23-25`) |
| `WebhookSink` (Pipeline) | through the transport above | `timeout_seconds` | https only; the target comes from an admin-onboarded Connection; tunnels and proxies are **refused** (`inspecto-engine/src/main/java/com/gamma/pipeline/exec/WebhookSink.java:47-54`) |
| `S3Connector` and the other object-store connectors | — | — | signs with `AwsSigV4`, no AWS SDK (`inspecto-connectors/src/main/java/com/gamma/acquire/connectors/S3Connector.java:41`) |

🔴 **The repo has no SSRF guard.** A search of production code finds no host allowlist, no check for
loopback, link-local or site-local addresses, and no DNS pinning. Every outbound client today fetches a
URL that an **administrator** chose. The SNS adapter would be the first client whose URL comes from the
**request body of an unauthenticated caller**, so it cannot reuse an existing guard and has to build one
(§3).

⚠ **`AwsSigV4` does not apply to inbound verification.** It is a package-private request **signer**
(`inspecto-connectors/src/main/java/com/gamma/acquire/connectors/AwsSigV4.java:28`, `sign` at `:54`).
SNS signs the messages it pushes with an **RSA key whose X.509 certificate AWS publishes**, not with
SigV4. The only place SigV4 could matter is the rejected alternative in §4.3 (calling the
`ConfirmSubscription` API with AWS credentials).

### 1.4 Findings while grounding

| # | Finding | Evidence | Severity |
|---|---|---|---|
| **F1** | **The callback body has no size cap.** `rawBody` calls `readAllBytes()` on an unauthenticated request. The route is also not rate-limited. This already affects the SendGrid and HMAC URLs today. | `ControlApi.java:1153-1164` (no bound); `isRateLimited` covers only `/db/query`, `/bi/query`, `/recon/*` and `/agent/*` (`ControlApi.java:895-898`) | **Medium, live.** Mitigated today only because the route answers 404 until an adapter is configured. |
| **F2** | **`PUT /notifications/preferences` is exempt as "the caller's own delivery preferences", but it writes ONE global grid.** On Standard, any authenticated user, including a viewer, changes the email and webhook opt-ins for every user. The same false premise ("the caller's own…") sits behind the `read-all`, `/{id}/read` and `DELETE /{id}` exemptions, because `NotificationStore` is also one store per Space. | `CapabilityManifest.java:278-283`; `NotificationPreferences.java:9-11` ("one global preference set"); `NotificationRoutes.java:44-45`, `:84-97` | **Low–medium, live.** Critical categories cannot be turned off (`NotificationPreferences.java:13-15`), so security alerts are safe from it. |
| **F3** | **`ApiContext.ip` trusts the first `X-Forwarded-For` hop from any caller.** That IP is recorded on every `ACCESS_DENIED` audit row. It would also become the per-IP rate-limit key for the unauthenticated callback, and the GeoIP input. | `inspecto/src/main/java/com/gamma/control/ApiContext.java:342-351`; `AuditTrail.java:122-130`; `ControlApi.java:906-909` | **Medium once anything decides on it.** An attacker can pick their own rate-limit bucket and their own apparent location. |

F1 and F3 block this adapter (slice S0). F2 is fixed by the per-user preferences design (§7).

---

## 2. SNS/SES protocol facts the design depends on

⚠ These come from AWS's public documentation, not from this repo. Slice **S0** captures a real
message of each type as a fixture and re-checks every item marked *(verify)*.

* SNS posts a JSON envelope whose `Type` is `SubscriptionConfirmation`, `Notification` or
  `UnsubscribeConfirmation`. The request also carries an `x-amz-sns-message-type` header. That header
  is **not** signed, so it is only a routing hint.
* **String to sign.** `key\nvalue\n` pairs in a fixed order. For a `Notification`: `Message`,
  `MessageId`, `Subject` (only when present), `Timestamp`, `TopicArn`, `Type`. For the two
  confirmation types: `Message`, `MessageId`, `SubscribeURL`, `Timestamp`, `Token`, `TopicArn`,
  `Type`. `SubscribeURL`, `Timestamp` and `TopicArn` are **inside** the signature. `SigningCertURL`
  and `SignatureVersion` are **not**.
* `SignatureVersion` `1` means SHA1withRSA and `2` means SHA256withRSA. Version 2 is opt-in, per topic,
  through the `SignatureVersion` topic attribute.
* `SigningCertURL` points to a PEM certificate at
  `https://sns.<region>.amazonaws.com/SimpleNotificationService-<hex>.pem` *(verify the exact path
  grammar)*. AWS rotates this certificate occasionally, and the URL changes when it does.
* **Timestamp.** `Timestamp` is when the message was **published**. SNS HTTP/S retries resend the same
  envelope, and a custom delivery policy can keep retrying for up to an hour *(verify the current
  maximum)*. A 300-second window would therefore reject genuine retries.
* **SES content.** SES event publishing puts a JSON **string** in `Message`, with `eventType` equal to
  `Bounce`, `Complaint`, `Delivery`, `Send`, `Reject`, `DeliveryDelay`, `Open`, `Click`,
  `RenderingFailure` or `Subscription`. The legacy feedback notifications use `notificationType`
  instead. `bounce.bounceType` is `Permanent`, `Transient` or `Undetermined`.
* 🔴 **Correlation risk (verify first).** SES may give the message its **own** `Message-ID`, in which
  case our `<inspecto.{id}@…>` would not come back in `mail.messageId`. It may still appear in
  `mail.commonHeaders.messageId`, or in `mail.headers` if the configuration set includes original
  headers. If the S0 fixture shows it does not, correlation must use an SES message tag
  (`X-SES-MESSAGE-TAGS: inspecto_delivery=<id>`, echoed back as `mail.tags`) or a custom header. That
  would mean a small change to `SmtpEmailChannel` (decision **D5**). A one-line correlation assumption
  was exactly how the SendGrid plan got the algorithm wrong (events-metrics.md, correction 3).

---

## 3. Security review — the callback and the fetch it causes

### 3.1 Threat model

| Asset | Threat | Attacker |
|---|---|---|
| Internal network: cloud metadata at `169.254.169.254`, localhost admin ports, internal services | **SSRF**: the attacker makes our server request a URL they chose | anyone who can reach the public callback, with no credentials |
| Delivery state: receipts and the suppression list | **Forged bounces** that mark a good address dead, which denies notification to it | same |
| Subscription state | **Unwanted subscription**: we confirm a topic we never meant to join, or our endpoint gets tied to someone else's topic | same, or another AWS tenant |
| Availability | **Amplification**: one cheap inbound POST costs us an outbound fetch plus RSA work, or fills a cache | same |
| Audit trail | **Log noise and forged IPs** (F3) | same |

**Trust boundary.** Everything in the POST body is attacker-controlled until the signature verifies.
The signature proves authenticity only if the certificate used to check it is proven to belong to
AWS SNS. So the certificate fetch sits **before** authentication, and every rule in §3.2 applies to
a request made on behalf of an anonymous caller.

### 3.2 SSRF — the certificate fetch

**Order of work (each step fails closed with 403 and does not fetch):**

1. **Size cap first** (fixes F1). Bound the read at `notify.deliverystatus.maxBodyBytes`, default
   **512 KiB**. SNS messages are at most 256 KiB, and the envelope plus base64 adds overhead. Over the
   cap, the route answers 413. This is a generic change to the route and protects every adapter, not
   only SNS.
2. Parse the JSON with a depth-bounded Gson read. Require `Type` to be one of the three known values.
3. **`SignatureVersion` must equal `"2"`.** Reject `"1"` and any unknown value (§3.3).
4. **`TopicArn` must exactly match an entry in `notify.deliverystatus.sns.topicArns`** (a
   comma-separated allowlist, required; an empty list leaves the adapter unconfigured, so its URL
   answers 404). This comes **before** the fetch, so a caller who does not name one of our topics
   cannot cause any outbound request. TopicArns are not secret, so this check narrows who can trigger a
   fetch but is not authentication.
5. **Derive the expected host from the allowlisted ARN, not from a pattern.** Split the ARN
   `arn:<partition>:sns:<region>:<account>:<name>` and require the `SigningCertURL` host to **equal**
   `sns.<region>.amazonaws.com`, or `sns.<region>.amazonaws.com.cn` for the `aws-cn` partition
   (decision **D2**). Compare as a string after `URI` parsing, with no userinfo, no explicit port other
   than 443, and a lower-case host.
   🔴 **Why the review's "strict host pattern `sns.<region>.amazonaws.com`" is not enough on its own:**
   an open-ended pattern such as `sns\.[a-z0-9-]+\.amazonaws\.com` also matches
   **`sns.s3.amazonaws.com`**, the virtual-hosted S3 endpoint for a bucket named `sns`, which is
   content that **somebody else controls on an AWS domain**. Loose patterns of this kind are a known
   bypass class in SNS verification libraries. Taking the region from our own configured ARN removes
   the class: the only host we will ever contact is one we could have written down ourselves.
6. **Scheme is `https` only**. Path must match `^/SimpleNotificationService-[0-9a-f]{32}\.pem$` *(the
   hex length is to be verified in S0)*, with no query and no fragment. The fixed path grammar is
   what bounds the number of distinct URLs, and so the cache-miss amplification in §3.6.
7. **Cache lookup.** Cache key is the full URL. On a hit, no fetch happens (§3.2.1).
8. **The fetch.** A dedicated `HttpClient`, never the webhook clients:
   * `followRedirects(NEVER)` set explicitly, and **any 3xx is a failure**.
   * connect timeout **3 s**, request timeout **5 s**.
   * body read through `ofInputStream` and **capped at 16 KiB**. A PEM certificate is about 2 KiB, and a
     longer body is refused without reading the rest.
   * Only `200` with a parseable single X.509 PEM is accepted.
   * No proxy by default. Whether to honour a configured egress proxy is decision **D9**.
   * **Single-flight:** concurrent misses for the same URL share one fetch.
   * **Global fetch budget:** at most `notify.deliverystatus.sns.maxFetchesPerHour` (default **12**)
     across the process. Once the budget is spent, a miss is a 403 and makes no request.
9. **Address check (defence in depth).** Resolve the host once and refuse the fetch if **any**
   resolved address is loopback, link-local (which covers `169.254.169.254` and the IPv6 metadata
   address `fd00:ec2::254`), any-local, multicast, or IPv4-mapped forms of those. Private RFC 1918 and
   ULA addresses are refused by default. An SNS **interface VPC endpoint** with private DNS resolves
   the regional name to private addresses, so there is an opt-in (decision **D3**).
10. **Certificate trust.** This step makes the signature meaningful, and it is independent of steps
    5–9. The certificate must:
    * chain to the **JVM trust store** under PKIX, with revocation checking off by default (an
      OCSP/CRL fetch would be yet another outbound call);
    * be inside its validity period;
    * carry the subject `sns.amazonaws.com` in its SAN or CN *(verify the exact name against the S0
      fixture; the regional certs may name a regional host)*;
    * use an RSA key of at least 2048 bits.

#### DNS rebinding

The address check in step 9 and the connection the JDK `HttpClient` makes resolve the name separately.
The JVM's own DNS cache (`networkaddress.cache.ttl`) narrows that gap but does not close it. So step 9
**cannot** be the rebinding defence on its own. The defence that holds is **TLS with hostname
verification**. If the name were rebound to `127.0.0.1` or to a metadata address, whatever answered
there would have to present a certificate for `sns.<region>.amazonaws.com` issued by a trusted CA. A
successful rebind therefore fails the TLS handshake, and nothing is sent after the ClientHello: no
path, no headers. Step 9 remains because it keeps us from **opening** a TCP connection to an internal
address at all, and that alone can be a side effect (port scanning by timing). Two requirements
follow, and tests must pin both:

* ⛔ **No trust-all or skip-verify option, ever.** This is the same stance `SmtpEmailChannel` takes
  (`SmtpEmailChannel.java:175-186`). An internal-CA deployment uses the JVM trust store instead.
* ⛔ **`jdk.internal.httpclient.disableHostnameVerification` must never be set.** A boot-time check
  refuses to arm the SNS adapter if that property is present (test T-S7).

#### 3.2.1 Certificate cache

* Keyed by the exact URL. Holds at most **8** entries, evicting the least recently used. Each entry is
  kept until the certificate's `notAfter`, re-checked on every use.
* **Failures are cached** for 10 minutes per URL (the negative TTL), so a URL that failed validation
  cannot be re-fetched every request.
* Held in process only; a restart starts empty. It is not persisted, because persisting it would
  create a second trust anchor to protect.
* **Pinned-certificate mode (air-gapped, decision D1).** `notify.deliverystatus.sns.signingCert`
  names a local PEM file. When it is set, the adapter **never fetches**. `SigningCertURL` must still
  pass steps 5–6, so a message that names a different certificate is refused, and the pinned
  certificate must still pass step 10 at boot. When AWS rotates its certificate, verification fails
  closed, the 403s show up in the audit trail, and a security trigger fires (§8, trigger T3). The
  runbook note goes in the OKF concept when this ships.

### 3.3 Signature verification

* Build the string to sign from the parsed envelope, in the §2 order, for the specific `Type`. A
  message missing any required key is rejected. `Subject` is included only when present **and not
  JSON null** *(verify)*.
* Accept only `SignatureVersion == "2"`, verified with `SHA256withRSA`. The signature is base64 in
  `Signature`.
* 🔴 **Reject version 1 (SHA-1) even though AWS still sends it by default.** Accepting SHA-1 would
  let a forged bounce through if a SHA-1 chosen-prefix collision became practical against this string
  format. More immediately, allowing both versions creates a downgrade path for no benefit. The
  operator therefore **must set `SignatureVersion=2` on the topic**. A version 1 message returns 403 and
  logs an operator-readable reason once per topic (not per request). Decision **D4** covers this.
* `verify` keeps the SPI contract: it never throws; any exception returns `false`
  (`DeliveryStatusAdapter.java:22-25`).
* Verification runs **before** `parse` inspects the SES payload inside `Message`, as the SPI requires
  (`DeliveryStatusAdapter.java:26-27`).

### 3.4 Replay

* **Timestamp window.** `Timestamp` is ISO-8601 and signed. Its freshness is checked against
  `notify.deliverystatus.sns.freshnessSeconds`. SNS retries resend the original publish time (§2), so
  the default **cannot** be the shared 300 s. The recommended default is **3600 s**, with future skew
  capped at 300 s. That is an asymmetric window, where `DeliveryIds.fresh` is symmetric
  (`DeliveryIds.java:40-44`), so SNS needs its own helper. Decision **D6**.
* **`MessageId` de-duplication.** An in-memory LRU of recently seen `MessageId`s, sized for the window
  (default 10,000 entries). A duplicate answers **200** with `duplicate: true` and does no work.
  **Why it is cheap to get wrong in the harmless direction:** a replayed *Notification* is already
  harmless, because `stamp` lets the first observation of a status win
  (`DeliveryReceiptStore.java:21-28`, and the events-metrics.md invariant "first observation of a
  status wins"). So de-duplication is **not** what protects receipt integrity. It exists to stop a
  replayed **SubscriptionConfirmation** from causing another confirmation GET, and to spare the RSA
  work. It is not durable: after a restart a replay inside the window re-verifies and is still
  harmless.
* **Answer 200 on a duplicate, not 409.** A non-2xx makes SNS retry, and a retry of a duplicate is
  still a duplicate. This is the same reasoning as the existing 202 for an unknown id
  (`DeliveryStatusRoutes.java:37-39`).

### 3.5 Subscription confirmation

`SubscribeURL` is fetched **only** when all of the following hold:

1. The signature verified (§3.3). `SubscribeURL` and `TopicArn` are inside the signed string, so
   neither can be changed in transit.
2. `TopicArn` is in the allowlist (§3.2 step 4).
3. The `SubscribeURL` host is exactly the host derived in §3.2 step 5, the scheme is https, the path is
   `/`, and the query contains `Action=ConfirmSubscription`, a `TopicArn` equal to the envelope's, and
   a `Token`. No other parameters are allowed.
4. The same client rules as §3.2 step 8 apply (no redirects, timeouts, a 16 KiB response cap, the
   fetch budget), and so does the step 9 address check. The response must be 200 and contain a
   `SubscriptionArn`. Its body is **logged but never trusted** for anything.
5. `MessageId` has not been seen before (§3.4).

**Run it asynchronously.** The route answers **200 `{"control":"SubscriptionConfirmation"}`** at once
and hands the confirmation to a single-thread executor with a bounded queue (default 4 items; when
full, the task is dropped and logged). That keeps an outbound call off the request thread. The cost:
if the GET fails, SNS does not resend the confirmation, so the operator has to use *Request
confirmation* in the SNS console. Decision **D7**.

**Auto-confirm is configurable** through `notify.deliverystatus.sns.autoConfirm`, default **true**.
Only a topic we allowlisted and AWS signed can reach the GET, so the one thing auto-confirm can do is
the thing the operator asked for. When it is **false** (the air-gapped setting), the adapter does not
fetch and writes an **AUDIT** event holding the TopicArn and the `Token`, so the operator can confirm
out of band with `aws sns confirm-subscription`. ⚠ The Token is sensitive only to the extent that it
lets someone confirm *our* endpoint's subscription to *our allowlisted* topic, so recording it in the
audit trail is acceptable. Decision **D8** covers it anyway.

**`UnsubscribeConfirmation`** is verified, audited, and answered 200. It **never** triggers a
re-subscribe.

### 3.6 DoS and amplification

| Vector | Cost to the attacker | Our cost | Bound |
|---|---|---|---|
| Large POST body | one request | memory | 512 KiB cap (§3.2 step 1) |
| POST flood to the callback | one request each | parsing plus possibly RSA | **per-IP token bucket** on `/public/delivery-status/` (join `isRateLimited`, `ControlApi.java:895`). The IP must come from the trusted-proxy fix for F3, or the attacker picks their own bucket. |
| Unique `SigningCertURL` per request, to force cache misses | one request each | one outbound GET each | path grammar (step 6), host derived from the ARN (step 5), negative cache (§3.2.1), and a **global budget of 12 fetches per hour** (step 8). The attacker can at most use up the budget, and the result is a delay in verifying genuine messages under a new certificate, not an outbound flood. |
| Replayed SubscriptionConfirmation | one request each | one outbound GET each | `MessageId` de-duplication, the global budget, and the queue of 4 |
| Forged 403s filling the audit trail | one request each | one audit row each | this cost exists today for every adapter. The per-IP bucket bounds it, and trigger T3 in §8 **aggregates** these rows so the notification fan-out is not a second amplifier. |

The worst case for outbound traffic is therefore **12 GETs per hour plus the confirmations (queue
of 4)**, all to one AWS host we could have written down ourselves, whatever the inbound volume.

### 3.7 Air-gapped behaviour

SNS needs AWS to **reach us inbound**, so a truly air-gapped deployment cannot receive SES events at
all. What matters is behaviour when **outbound** is blocked:

| Configuration | Result |
|---|---|
| Adapter not configured (the default on every edition) | URL answers 404 and **no code path fetches anything**. Test T-S1 pins this. |
| Configured, outbound blocked, no pinned certificate | Fetch fails, the failure is negatively cached, the callback gets 403, SNS retries and then drops the message. The first failure writes an operator-readable log line and T3 fires. Fails closed. |
| Configured with a pinned certificate and `autoConfirm=false` | **Zero outbound.** Verification works offline and confirmation is done out of band from the audit row (§3.5). This is the recommended setting for restricted egress. |

The adapter ships in `inspecto-connectors`, which means Standard and Enterprise, the same as the
SendGrid adapter. Personal gets none of it.

### 3.8 Residual risk after the design

* **Trust in the JVM CA store.** Anyone able to issue a certificate for `sns.amazonaws.com` from a
  trusted CA can forge messages. This is the same trust every HTTPS client in the product places in
  that store. Pinned mode shrinks it to one certificate.
* **Revocation is not checked** by default, because checking it means another outbound fetch. The
  exposure is bounded by the certificate's validity period.
* **VPC-endpoint deployments** that opt in to private addresses (D3) accept that step 9 no longer
  blocks internal targets. For them the ARN-derived host and TLS verification are the whole defence,
  and those still hold.
* **Parsing before verification.** Steps 1–6 parse attacker JSON before any signature check, so Gson
  is part of the attack surface. It is bounded by the size cap, the depth limit, and the lack of type
  binding (tree parse only, as the SendGrid adapter already does).

---

## 4. Adapter design

### 4.1 The SPI gap: a verified message that carries no events

The route answers **422** when `parse` returns no events (`DeliveryStatusRoutes.java:140-141`). A
verified `SubscriptionConfirmation` or `UnsubscribeConfirmation` carries no delivery events. With
today's SPI it would therefore get a 422, and SNS would treat the endpoint as failed. **The SPI needs
a place for control messages.** Options:

* **(A) Recommended: add a `default` method** `Optional<String> control(byte[] raw)` that runs after
  `verify` and before `parse`. When it returns a value, the route answers 200 `{"control": <kind>}`
  and skips the event path. The adapter schedules any side effect, such as the confirmation, itself.
  The default returns empty, so SendGrid and HMAC do not change. This is additive on a `@PublicApi`
  interface, and breaking changes are acceptable here anyway.
* (B) Let `parse` return a synthetic `DeliveryEvent` with a sentinel status. Rejected: it would make
  `DeliveryStatus` carry a value that is not a delivery status.
* (C) A separate route `/public/sns-confirm`. Rejected: it doubles the unauthenticated surface.

Decision **D10**.

### 4.2 Classes (all in `inspecto-connectors`, `com.gamma.connect.notify`)

| Class | Job |
|---|---|
| `SesSnsDeliveryStatusAdapter` | SPI implementation, `id() = "ses"`. `configured()` requires a non-empty TopicArn allowlist. `verify`, `control` and `parse` as in §3–§4. |
| `SnsEnvelope` | Parses the envelope and builds the string to sign for each `Type`. Pure, and unit-testable from fixtures. |
| `SnsSigningCerts` | The §3.2 steps 5–10 plus the §3.2.1 cache. It is the **only** class that makes a network call, and it takes an injectable `Fetcher` so tests never touch the network. |
| `SnsSubscriptionConfirmer` | The §3.5 checks plus the bounded executor. |
| `SesEventMapper` | SES payload to `DeliveryEvent`s (§4.3). |

### 4.3 SES event to `DeliveryStatus` mapping

| SES | Maps to |
|---|---|
| `Delivery` | `DELIVERED` |
| `Bounce`, `Permanent` | `BOUNCED_HARD` |
| `Bounce`, `Transient` | `BOUNCED_SOFT`, so the existing retry task picks it up |
| `Bounce`, `Undetermined` | `BOUNCED_SOFT`: treating an unknown as permanent would mark a good address dead, the same reasoning as SendGrid's `blocked` (`SendGridDeliveryStatusAdapter.java:138-155`) |
| `Complaint` | `COMPLAINED` |
| `DeliveryDelay` | `BOUNCED_SOFT` *(decision D11: it may instead be `UNKNOWN`, since SES itself is still retrying)* |
| `Reject`, `RenderingFailure` | `BOUNCED_HARD`, because the message never left SES |
| `Send`, `Open`, `Click`, `Subscription`, anything else | `UNKNOWN` with the raw payload, following the SPI's "never guess, never drop" rule (`DeliveryStatusAdapter.java:28-30`) |

A bounce lists `bouncedRecipients[]`. One receipt is one delivery to one target, so each recipient
**that matches the receipt's target** stamps that receipt. A multi-recipient `target` string is the
digest edge case already recorded in events-metrics.md.

**Rejected:** confirming the subscription through the SigV4-signed `ConfirmSubscription` API. It
would need AWS credentials on the server. The client is package-private under
`acquire.connectors`. And it would still be an outbound call to the same host. It adds a credential
and removes no risk.

---

## 5. Slices and tests

**Each slice is its own commit.** The fetch (S3) is isolated as the row requires, so it can be reviewed
and reverted on its own.

| Slice | Content | Tests (unit per change; `ControlApiDeliveryStatusTest` for the route) |
|---|---|---|
| **S0** Prerequisites | Capture real SNS/SES fixtures: SubscriptionConfirmation, Notification (Bounce Permanent and Transient, Complaint, Delivery), UnsubscribeConfirmation, a v1 and a v2 signature, and the certificate. Confirm every *(verify)* item in §2 and §3. **F1:** bounded `rawBody`, 413 over the cap, callback added to `isRateLimited`. **F3:** a trusted-proxy list (`-Dcontrol.trustedProxies`) so `X-Forwarded-For` is honoured only from a listed peer. | T-F1a: a 600 KiB body gets 413 and nothing is read past the cap (mutation: remove the bound and the test must go red). T-F1b: the callback returns 429 after the bucket empties. T-F3a: XFF from an unlisted peer is ignored. T-F3b: XFF from a listed peer is used. Fixtures are checked in **only if** they contain no customer data (address rewritten, signature regenerated with a test key). |
| **S1** Envelope and signature, no network | `SnsEnvelope`, and `SnsSigningCerts` verification against an **injected certificate**. `SignatureVersion` gate, freshness, de-duplication. | T-S2: a valid v2 message verifies. T-S3: the same message as v1 gets 403. T-S4: one byte of `Message` changed gets 403 (and the same for each signed key). T-S5: a `Timestamp` outside the window gets 403, both past and future. T-S6: a duplicate `MessageId` gets 200 `duplicate` with no stamp. **Negative-test probe:** each 403 test also runs a control that would succeed, so a test cannot pass by verifying nothing. |
| **S2** SES mapper and route | `SesEventMapper`, `control()` SPI method, the route handling `control`. Soft bounce flows into the existing retry task. | T-S8: every row of §4.3. T-S9: SubscriptionConfirmation gets 200 `control` and **no fetch** while autoConfirm is false. T-S10: Transient bounce becomes a candidate in `SoftBounceRetryTask`. T-S1: adapter unconfigured means 404 **and the Fetcher is never called** (asserted with a counting fake). |
| **S3** 🔒 The fetch (own commit, own review) | `SnsSigningCerts` real fetcher, the address check, the cache, the budget, single-flight. `SnsSubscriptionConfirmer`. | T-S11 host: `sns.s3.amazonaws.com`, `sns.us-east-1.amazonaws.com.evil.com`, `sns.eu-west-1.amazonaws.com` for an ARN in us-east-1, userinfo `https://x@sns…`, port `:8443`, `http://` are all refused **before** the Fetcher is called. T-S12 address: a resolver fake returning `127.0.0.1`, `169.254.169.254`, `::ffff:169.254.169.254`, `10.0.0.1` (with D3 off) is refused. T-S13: a 302 is refused and not followed (local HTTPS stub). T-S14: a 20 KiB body is refused at 16 KiB. T-S15: a slow stub is cut at 5 s. T-S16: the 13th distinct miss in an hour makes no request. T-S17: a certificate that does not chain, is expired, has the wrong subject, or a 1024-bit key is refused. T-S7: the hostname-verification-disabled property stops the adapter arming. T-S18: SubscribeURL with extra parameters or a different TopicArn is refused. T-S19: pinned mode makes **zero** Fetcher calls. |
| **S4** Documents | OKF concept section; `DEPLOY` note (set SignatureVersion 2 on the topic, and the URL to paste); BACKLOG row updated; this plan archived. | doc guards |

Run the tests with `-pl inspecto-connectors -Dtest=…` and `-pl inspecto -Dtest=ControlApiDeliveryStatusTest`
(comma-separated, never `+`). The **route change in S0 touches a shared seam** (`rawBody` is used by every
body-reading route), so S0 also runs the `inspecto` module suite.

---

## 6. GeoIP

**What exists.** `AuditAttrs` records only `ip` and leaves location out on purpose: "editions resolve
it" (`inspecto-event/src/main/java/com/gamma/event/AuditAttrs.java:12-13`). The Pipeline enricher
`enrichment.geoip` is `PLANNED` (`inspecto-engine/src/main/java/com/gamma/pipeline/ProcessorCatalog.java:148`,
`docs/EDITIONS.md` `SP-ENR-02`). ⚠ **New Step Processors are on hold** (operator, 2026-09-23), so the
enricher is **out of scope**. This design covers only the **audit and security** use.

**Database licensing (decision D12):**

| Database | Licence | Can we **bundle** it? | Note |
|---|---|---|---|
| MaxMind GeoLite2 | GeoLite2 EULA (account and licence key; attribution; 30-day update duty) | **No**, redistribution is restricted | the most accurate free option; the operator downloads it |
| DB-IP Lite | CC BY 4.0 | yes, with attribution | `.mmdb` format, monthly |
| IP2Location LITE | CC BY-SA 4.0 | yes, but share-alike applies to the data | also offered in `.mmdb` |

**Design.**

* **Never bundle, never download.** The operator points `-Dgeoip.db=<path to .mmdb>` at a file they
  obtained under whatever licence they accept. With it unset, GeoIP is off and no `location` attribute
  is written. Downloading at runtime is exactly the outbound call this document is trying to minimise,
  and it would make the licence acceptance ours.
* Reader: `com.maxmind.db:maxmind-db` (Apache 2.0), which reads every `.mmdb` in the table. It is a new
  dependency, so it needs an SBOM entry and `dependencies.lock`. Enterprise and Standard only.
* Write `geo_country` (ISO code), and `geo_city` only if decision D12 allows, plus `geo_db_build`
  (the database's build epoch). Without the build date a stale database gives a confident wrong
  answer.
* **Resolve in-process when the audit row is emitted, never through a network lookup service.**
* 🔴 **Blocked on F3.** Until `X-Forwarded-For` is trusted only from listed proxies, the location is
  whatever the attacker wrote in a header. No trigger may use `geo_*` before S0's F3 fix ships.
* Privacy: location derived from an IP is personal data. It rides on the audit row and is subject to
  the audit trail's existing retention. It gets a `compliance/` line when it ships.

---

## 7. Per-user notification preferences (fixes F2)

**Model.** Two layers. The **deployment default** grid is today's `NotificationPreferences`, editable
only with `canAdminister`. On top of it, each `Subject.id()` can store a sparse **override**.
`enabled(category, channel, subject)` returns the override when there is one, otherwise the default.
Critical categories stay locked on at both layers (`NotificationPreferences.java:47-52`).

**Routes.**

* `GET /notifications/preferences` returns the caller's **effective** grid, each cell marked
  `inherited` or `overridden`.
* `PUT /notifications/preferences` writes **the caller's override only**. This makes the existing
  `self-service` exemption at `CapabilityManifest.java:282` finally true.
* `PUT /notifications/preferences/default` is new and gated `canAdminister`. It is a new mutating
  ControlApi route, so it clears the four gates (openapi entry, manifest, test with a Subject, literal
  capability).
* Personal edition has no Subject. There the override layer is skipped, and `PUT` keeps writing the
  single grid it writes today, because a one-user deployment has no one else to affect.

**Destination.** A per-user email preference is delivered to the address from **the Subject's
verified email claim**. ⛔ **It is never user-editable**: an editable address would let any user send
notifications, including security ones, to an arbitrary mailbox, which is data exfiltration. A Subject
with no email claim can turn only in-app delivery on.

**Storage.** A durable per-deployment file, not per Space, because users span Spaces. It is written
through the existing atomic config-write path. Overrides are keyed by the stable subject id, never by
display name.

**Same premise, other routes.** `read-all`, `/{id}/read` and `DELETE /{id}` also claim "the caller's
own" but act on a shared per-Space store (F2). A per-user read state is a larger change to
`NotificationStore`, and it is decision **D13**: fix them here, or file them as a separate row and
correct the exemption text now.

**Tests.** User A's `PUT` does not change user B's effective grid. A `PUT` to `/default` without
`canAdminister` gets 403 **with a Subject attached**, because without a Subject `withCapability` does
nothing and the test would pass against an ungated route. A critical category cannot be overridden off.
A body that tries to set an email address is ignored.

---

## 8. Security triggers

**What exists.** `NotificationCategory.SECURITY` is `critical = true, available = false`: shown in
the grid, but never emitted (`inspecto-engine/src/main/java/com/gamma/notify/NotificationCategory.java:26`).
The built-in Notification Rules cover only operational events
(`inspecto-engine/src/main/java/com/gamma/notify/NotificationRules.java:41-80`). Every 401 or 403 on a
matched route already becomes an `ACCESS_DENIED` event carrying actor, IP, path, status and the missing
capability (`inspecto/src/main/java/com/gamma/control/AuditTrail.java:106-133`). **The raw material is
already there. What is missing is aggregation.**

**Why aggregation is required.** `SECURITY` is critical, so nobody can opt out of it. A trigger
that fires once per 401 would let an unauthenticated caller **send email to every admin with one
request each**: the notification path becomes a second amplifier (§3.6). Every trigger is therefore a
**windowed threshold** that fires once per key per window, and delivers through the existing
`DigestBuffer`.

| # | Trigger | Input | Default threshold |
|---|---|---|---|
| T1 | Repeated authorization refusals by one authenticated subject | `ACCESS_DENIED`, status 403, grouped by actor | 20 in 10 minutes |
| T2 | Burst of authentication failures from one IP | `ACCESS_DENIED`, status 401, grouped by IP (**requires the F3 fix**) | 50 in 10 minutes |
| T3 | Rejected delivery-status signatures | 403 on `/public/delivery-status/*`, grouped by adapter | 10 in 1 hour. This is also the **certificate-rotation alarm** for pinned mode (§3.2.1). |
| T4 | A change to role or capability config (`roles.toon` written) | AUDIT `config.written` on the roles file | every change. It is rare, and the actor is authenticated. |
| T5 | Sign-in from a new country for a subject | `/auth/exchange` success plus `geo_country` (**requires GeoIP and F3**) | the first time each (subject, country) pair is seen |

⚠ **What Inspecto cannot see.** Password and MFA failures happen at the IdP (OIDC), not here. We see
token-exchange and refresh failures only. The "Security & passwords" label overstates what the product
can observe, so decision **D14** is whether to rename it.

**Implementation.** A new built-in trigger evaluator subscribed to the event log. It keeps an
in-memory sliding-window count per (trigger, key), with the key count bounded (LRU of 10,000) so that
counting cannot be used to exhaust memory. It emits a new `EventType.SECURITY_TRIGGERED`, which a
built-in Notification Rule maps to category `security`. `NotificationCategory.SECURITY` then flips to
`available = true`. Thresholds are system properties, the same idiom the delivery-status adapters use.
Recipients are Subjects holding `canAdminister`, through per-user preferences (§7).

**Tests.** A threshold minus one does not fire. The threshold fires exactly once per window. 10,000
distinct keys hold memory steady (the LRU is honoured). A `SECURITY` notification cannot be opted out.
T2 keyed by a spoofed XFF from an unlisted peer counts against the **socket** peer.

---

## 9. Decisions owed (operator)

| # | Decision | Recommendation |
|---|---|---|
| **D1** | Certificate source: fetch from `SigningCertURL` (with the cache), or require a pinned PEM, or allow both. | **Both. Fetch is the default and pinned is opt-in.** Fetch copes with AWS's certificate rotation. Pinned gives zero egress for restricted networks. |
| **D2** | Accept the `aws-cn` partition (`.amazonaws.com.cn`) and GovCloud regions. | **Take the host from the configured ARN's partition and region.** Nothing extra to allowlist; a China or GovCloud ARN works only if the operator configured one. |
| **D3** | Allow the certificate host to resolve to private (RFC 1918 or ULA) addresses, for SNS VPC endpoints. | **Off by default, with an opt-in property.** Loopback, link-local and metadata stay refused **unconditionally**. |
| **D4** | Reject `SignatureVersion 1`. The operator must then set `SignatureVersion=2` on the topic. | **Reject v1.** The setting is one topic attribute, and the deploy note says so. Accepting v1 is a downgrade path with no benefit. |
| **D5** | Correlation key for SES: our `Message-ID`, an SES message tag, or a custom header. | **Decide after the S0 fixture.** Prefer `Message-ID` if SES echoes it. Otherwise use an `X-SES-MESSAGE-TAGS` tag, which needs a configuration set. |
| **D6** | SNS freshness window. | **3600 s past, 300 s future**, set as an SNS-specific property. The shared 300 s would reject genuine SNS retries. |
| **D7** | Subscription confirmation: synchronous in the request, or asynchronous on a bounded executor. | **Asynchronous** (queue of 4). The cost is a manual console re-request if the GET fails, and that is visible in the log. |
| **D8** | Default for `autoConfirm`, and whether the Token may be recorded in the audit trail when it is false. | **`autoConfirm=true`.** When it is false, **record the Token**: it can only confirm our own allowlisted subscription. |
| **D9** | Honour a configured egress proxy for the certificate and confirmation fetches. | **Honour `ProxySelector.getDefault()` only when `-Dnotify.deliverystatus.sns.useProxy=true`.** Unlike `WebhookSink`, refusing a proxy here would make the adapter unusable behind a corporate egress proxy. TLS hostname verification still covers the endpoint. |
| **D10** | SPI shape for control messages (§4.1). | **(A): a `default control(raw)` method.** It is additive, and SendGrid and HMAC are unchanged. |
| **D11** | `DeliveryDelay` maps to `BOUNCED_SOFT` or to `UNKNOWN`. | **`UNKNOWN`.** SES is still retrying on its own, and a soft bounce would make our retry task resend alongside it. |
| **D12** | GeoIP: which database the documentation recommends, and whether `geo_city` is recorded or only `geo_country`. | **Recommend nothing we bundle. Document GeoLite2 (the operator downloads it) and DB-IP Lite. Record country only by default.** City is more personal data for little security value. |
| **D13** | The false-premise `self-service` exemptions on `read-all`, `/{id}/read` and `DELETE /{id}` (F2). | **File a separate row and correct the exemption rationale text now.** Per-user read state is a larger change to `NotificationStore` than preferences are. |
| **D14** | Rename the "Security & passwords" category, given that password events happen at the IdP. | **Rename it to "Security"** (a UI-only change; the category id `security` stays). |

