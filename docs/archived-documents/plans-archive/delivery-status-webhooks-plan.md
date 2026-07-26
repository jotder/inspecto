# Inbound delivery-status webhooks (BACKLOG D8) — build plan

**Status:** planned, not started · **Opened:** 2026-07-26 · **Decision of record:** BACKLOG §2 D8
**Concept home (update on ship):** [`../okf/backend/control-plane/events-metrics.md`](../okf/backend/control-plane/events-metrics.md)

> **What this is.** Provider callbacks telling us what happened to an email we sent — `delivered`,
> `bounce`, `complaint`. **Inbound**, not outbound: the outbound `webhook` *channel* shipped 2026-07-22
> (`channel/WebhookChannel`) and is unrelated. Standard/Enterprise flavour territory.

---

## 1. What the decision did not anticipate

Three findings from the 2026-07-26 seam survey. **The status field is the easy part; none of these are.**

1. **Nothing correlates a sent message back to a `Notification`.** `NotificationChannel.deliver` returns
   `void` (`NotificationChannel.java:25,34`), `SmtpEmailChannel` discards the `Transport.send` result, and
   `WebhookChannel` checks only for 2xx and throws the response body away (`WebhookChannel.java:62-72`) —
   which is exactly where a provider message-id would arrive. So an inbound "message X bounced" has
   **nothing to attach itself to**. Establishing that correlation is the bulk of this build (§3).
2. **Route handlers cannot see raw request bytes.** `ApiContext.body` reads the stream fully and parses
   straight to a `Map` (`ControlApi.java:756-761`), and `ex.getRequestBody()` is **single-read** — once
   `body()` is called the bytes are gone. Every real provider signs the *raw* body, so this needs a new
   raw-body accessor (§4.1). Verifying against a re-serialised map is **not** an option: key ordering and
   whitespace would not survive, so signatures would fail non-deterministically.
3. **Good news — the store already updates in place.** `InMemoryNotificationStore` replaces map entries for
   `markRead`/`archive` (`InMemoryNotificationStore.java:64-70,86-91`), so a status mutator fits the
   existing pattern; and `/public/dashboards/*` is a working precedent for a self-verifying route exempted
   from platform auth (`ControlApi.java:614,648`).

## 2. Correlation strategy — our id, embedded outbound

**Decision: we mint the correlation id and embed it in the outbound message; we do NOT capture provider
message-ids.**

- **SMTP:** set the `Message-ID` header ourselves to `<inspecto.{deliveryId}@{domain}>`. Both SES and
  SendGrid echo the original `Message-ID`/`smtp-id` in bounce and complaint payloads, so the round trip
  closes without provider cooperation.
- **Outbound webhook:** send the id as a header (`X-Inspecto-Delivery-Id`).

**Rejected: widening the SPI so `deliver` returns a provider id.** `NotificationChannel` is
`@PublicApi(since = "4.4.0")`; changing `deliver`'s return type breaks every implementor, and a `default`
receipt-returning overload would leave two delivery paths where only one records. Our own id is sufficient
for the decided providers and costs no API break. **If a provider is ever adopted that will not echo an
id, revisit — a `default Optional<String> deliverWithReceipt(…)` is the escape hatch, deliberately not
built now.**

## 3. Status model

New `DeliveryStatus` enum: `DELIVERED`, `BOUNCED_HARD`, `BOUNCED_SOFT`, `COMPLAINED`, `UNKNOWN`.

**Per-status timestamps, not one mutable field** — D8 is explicit and the reason is concrete: a spam-button
click produces `delivered` *then* `complaint` for the same message, and a single enum loses that ordering.
So a delivery record carries `Map<DeliveryStatus, Long> statusAt` — each callback stamps its own slot, and a
later status never erases an earlier one.

New `DeliveryReceipt` record + store (`deliveryId`, `notificationId`, `channelConfigId`, `target`, `sentAt`,
`statusAt`, `providerRaw?` for the unknown case). It is a **new store, not fields on `Notification`** —
one notification fans out to several destinations, so status is per *delivery*, not per notification.
`Notification` stays an immutable record with no new fields.

**Hard vs soft bounce:** normalised at the adapter edge. A soft bounce is retryable and **must not** be
treated as a dead destination (a full or briefly-unavailable mailbox is not a bad address).

## 4. Build steps

Backend per [`java-backend`](../../.claude/skills/java-backend/SKILL.md); every route gate needs a
real-HTTP test per [`endpoint`](../../.claude/skills/endpoint/SKILL.md). Verify with `mvn -o clean test`
via `verify-runner`.

### 4.1 The raw-body seam (core, small but load-bearing)
- Add `byte[] rawBody(HttpExchange ex)` to `ApiContext` + its `ControlApi` impl beside `body`
  (`ControlApi.java:756`).
- ⚠ **The callback handler must call `rawBody` and never `body`** — single-read stream. Parse the JSON
  *from the returned bytes* inside the handler after verification. Document this on the method, because the
  failure mode (calling both) is a silent empty body, not an exception.
- Leave `body()` untouched — every existing route depends on it.
→ **verify:** a unit test that `rawBody` returns bytes byte-identical to the request, including for a body
whose JSON key order differs from any re-serialisation.

### 4.2 `DeliveryStatusAdapter` SPI (core interface, connectors impls)
```java
public interface DeliveryStatusAdapter {
    String id();                                              // URL segment, e.g. "sendgrid"
    boolean verify(byte[] raw, Map<String,String> headers);    // signature; false ⇒ 403, never throws-through
    List<DeliveryEvent> parse(byte[] raw);                     // normalised events
    default boolean configured() { return true; }              // mirrors NotificationChannel.configured()
}
```
`DeliveryEvent` = `(deliveryId, DeliveryStatus, long ts, String providerRaw)`.

ServiceLoader-discovered, mirroring `NotificationChannel` (`NotificationService.discoverChannels()`,
`NotificationService.java:91-98`) — **the interface lives in core, the impls live in
`inspecto-connectors`**, matching "email is an edition seam, deliberately not in core". An unconfigured
adapter is inert, not a per-request failure.

**Normalisation is the adapter's job.** SES `Bounce`/`Complaint`/`Delivery` and SendGrid
`bounce`/`spamreport`/`delivered` map to our three canonical values; **an unrecognised provider event is
recorded as `UNKNOWN` with its raw payload — never dropped silently and never mapped to a guess.**

### 4.3 Two adapters
- **`SendGridDeliveryStatusAdapter`** — Ed25519 over `timestamp + raw body`, public key from
  `notify.deliverystatus.sendgrid.publicKey`. Pure JDK crypto (`KeyFactory.getInstance("Ed25519")`,
  JDK 15+), no outbound calls. Maps `delivered` → `DELIVERED`, `bounce` → hard/soft by the payload's bounce
  classification, `spamreport` → `COMPLAINED`.
- **`HmacDeliveryStatusAdapter`** — HMAC-SHA256 over the raw body against
  `notify.deliverystatus.hmac.secret`, **constant-time compare via `MessageDigest.isEqual`**, reusing the
  `ShareTokens.java:50-51` precedent. Covers Postmark-style and self-hosted relays.
- **Both must enforce a timestamp freshness window** (default 5 min, configurable). A valid signature is
  replayable forever otherwise — signature verification alone is not replay protection.
→ **verify:** per adapter — a known-good vector passes; a tampered body fails; a stale timestamp fails; a
missing header fails; an unknown event type yields `UNKNOWN` rather than an exception.

### 4.4 The inbound route (fail-closed)
`POST /public/delivery-status/{adapterId}` — reusing the **`/public/dashboards/*` self-verifying prefix**
precedent, exempted in both `authenticate` (`ControlApi.java:614`) and `authorize` (`:648`).

**Rejected: adding it to `PUBLIC_PATHS`.** That list is exact-match infrastructure/auth paths; a prefix
carrying a variable segment does not belong in it, and the dashboards prefix already exists for precisely
this "authenticates itself in-path/in-signature" case.

⚠ **Confirm at build time** how the `/api/v1` dispatch gate (`ControlApi.java:571-579`) interacts with the
`/public/` prefix — the gate requires `/api/v1` or `isInfraRoute`, so check whether the dashboards
precedent sits under the version prefix and follow it exactly. **Do not extend `isInfraRoute`** (four
unversioned infra probes; a business callback is not one).

Gate order:
1. unknown/unconfigured `adapterId` → **404** (do not reveal which adapters exist by erroring differently).
2. signature invalid, absent, or stale → **403**, nothing written, an audit record made.
3. unparseable payload → **422**.
4. unknown `deliveryId` → **202 accepted, recorded, not an error** — provider retries on non-2xx, and a
   receipt we pruned must not cause an infinite retry loop.
5. valid → stamp `statusAt`, **200**.

**An unverified callback must never be able to mark a destination dead** — that is a cheap
denial-of-notification vector, which is why verification precedes every write.
→ **verify:** `ControlApiDeliveryStatusTest` — real HTTP, one case per gate above, plus the
`delivered`-then-`complaint` ordering assertion.

### 4.5 Wire the send path
- `NotificationService.dispatch` (`NotificationService.java:140-187`) mints a `deliveryId` and writes a
  `DeliveryReceipt` per external destination before `deliver`, passing the id to the transport.
- `SmtpEmailChannel` sets the `Message-ID`; `WebhookChannel` sets `X-Inspecto-Delivery-Id`.
- Digest deliveries (`DigestBuffer`) batch N notifications into one message ⇒ **one receipt per digest
  delivery**, referencing the digest rather than a single notification id. Note this asymmetry explicitly;
  it is the one place the per-delivery model is lossy about which notification bounced.
→ **verify:** `NotificationServiceTest` — a receipt per enabled destination, none for in-app-only, one for
a digest flush.

### 4.6 A read surface
`GET /notifications/deliveries?notificationId=` — receipts + statuses, `canAuthorWorkbench`. Backend +
HTTP only, matching how `notification-rule` shipped (no UI editor).

### 4.7 Docs
Rewrite the "Delivery-status webhooks re-scoped" bullet in `events-metrics.md` as-built including §1's
three corrections; delete the D8 row from `BACKLOG.md` §2 and the Notifications row's D8 clause in §4;
`git mv` this plan to `plans-archive/`; update `docs/INDEX.md`; `graphify update .`.

## 5. Scope boundaries

- **No auto-disabling of a destination on hard bounce.** This build *records* status. Acting on it —
  suppression lists, disabling a `ChannelConfig` — is a separate decision with a denial-of-notification
  blast radius, and D8 only decided the *tracking* model. Raise it as a §6 residual.
- **No retry of soft bounces.** Recording the soft/hard distinction is in scope; a retry scheduler is not.
- **No SES adapter.** The SPI plus two impls proves the seam; SES needs SNS subscription confirmation and a
  cert-chain fetch from a validated `amazonaws.com` URL — a meaningfully larger piece of work, and an
  outbound fetch from a callback path deserves its own review.
- **No UI.** Consistent with `notification-rule`.
- **`Notification` gains no fields** (§3).

## 6. Residuals to expect in §6 on ship

- Auto-disable / suppression policy on hard bounce + complaint (§5) — needs a call.
- Soft-bounce retry scheduling.
- SES/SNS adapter.
- Digest deliveries correlate to the digest, not per notification (§4.5).
- `deliverWithReceipt` SPI escape hatch remains unbuilt (§2).
- Receipt retention/pruning — receipts accumulate per external delivery; `countPrunable`/`prune` on the
  notification store is the precedent to follow.
