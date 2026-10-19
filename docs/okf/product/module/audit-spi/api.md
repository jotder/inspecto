---
type: Reference
title: Audit contract — Java API
description: The audit-spi Java contract — Event, EventSink, EventStore, EventQuery, AuditAttrs, AuditChain — with method semantics, ServiceLoader registration and how to implement a sink or store.
resource: spi/inspecto-audit-spi/
tags: [module, audit-spi, spi, api, events, audit]
timestamp: 2026-10-19T00:00:00Z
---

# Audit contract — Java API (`module.audit-spi`)

Package `com.gamma.audit` in `spi/inspecto-audit-spi/`. Pure contract: it holds no store and no writer. The
HTTP read side (`/events*`, `/audit/verify`) is documented in the [event hub](../event/api-events.md).

## Types

| Type | Kind | What it is |
|---|---|---|
| `Event` | record, `@PublicApi(since 4.0.0)` | One immutable operational fact: `eventId`, `ts` (epoch ms UTC), `level`, `type`, `source`, `pipeline`, `correlationId`, `message`, `attributes` (immutable, never null). Build with `Event.builder(type)` or `Event.log(...)` (the log-capture path). The builder has audit helpers `actor`, `actorType`, `action`, `actionCategory`, `target(type,id)`, `ip`, `userAgent`. |
| `EventLevel` | enum | `TRACE`..`ERROR`, mirrors SLF4J. `CAPTURE_THRESHOLD = INFO`: automatic log capture records INFO and above; an explicit emit may still record DEBUG. `parse` defaults unknown text to `INFO`. |
| `EventType` | constants | Well-known `Event.type` strings (`LOG`, `AUDIT`, `ACCESS_DENIED`, `PIPELINE_*`, `FILE_*`, `BATCH_*`, `JOB_*`, `SIGNAL`, ...). Conventions, not a closed enum: any string is a valid type. |
| `AuditAttrs` | constants | Attribute keys of an audit entry — actor (`actor`, `actor_type`), action (`action`, `action_category`), target (`target_type`, `target_id`), environment (`ip`, `geo_country`, `geo_db_build`, `user_agent`, `http_*`), policy (`abac_action`, `policy`, `capability`) and the chain keys (`audit_seq`, `audit_prev_hash`, `audit_hash`, `audit_unlinked`). An audit entry is an ordinary `Event` with `type = AUDIT`; there is no parallel audit store. Location is country only, never a city. |
| `EventQuery` | record | Filter + page: `fromMs`, `toMs`, `minLevel`, `type`, `pipeline`, `correlationId`, `textContains`, `limit` (clamped to 1..`MAX_LIMIT` = 10 000, default 100), `offset`. `matches(Event)` is the in-memory predicate; SQL backends must return the same rows. |
| `EventSink` | interface (SPI) | Write side: `emit(Event)` and `emit(Event.Builder)`. **Never throws.** `EventSink.current()` resolves the implementation. |
| `EventStore` | interface, `AutoCloseable` | Append-only sink + query surface (see below). |
| `AuditChain` | final class | Hash-chain format and verifier helpers: `TYPES` (`AUDIT`, `ACCESS_DENIED`), `CHAIN_KEYS`, `canonical(Event)`, `hash(Event)` (SHA-256 lowercase hex), `seq`, `prevHash`, `storedHash`, `chained`, `unlinked`, `GENESIS` (empty string). |

## `EventSink` — emit only

```java
public interface EventSink {
    void emit(Event event);                       // never throws
    default void emit(Event.Builder builder) { ... }
    static EventSink current() { ... }            // ServiceLoader, cached
}
```

`current()` loads the first `META-INF/services/com.gamma.audit.EventSink` provider. Semantics:

- **No provider on the classpath** (contract-only deployment) → a no-op sink; events are dropped, logged once at WARN.
- **A provider that is present but fails to link** → logged and rethrown as `IllegalStateException`; it is never
  swallowed into the no-op.

The one registered provider is `com.gamma.event.CurrentEventSink` in
`platform/inspecto-event/src/main/resources/META-INF/services/com.gamma.audit.EventSink`, which delegates to the
calling Space's `EventLog`. Modules below `inspecto-event` (the access policy's audit trail, the Link Analysis API)
emit through `EventSink.current()` and never name `EventLog`.

## `EventStore` — the storage seam

Not ServiceLoader-discovered: the event module picks the backend (`-Devents.backend=memory|parquet`, see the
[event hub](../event/index.md)). Contract an implementation must honour:

| Method | Required semantics |
|---|---|
| `append(Event)` | Append-only; no update or delete exists. Thread-safe (ingest workers, log appender and bus write concurrently). |
| `query(EventQuery)` | Matching events **newest-first**, honouring `limit`/`offset`. |
| `recent(int)` | Live-tail fast path: newest `limit`, newest-first. |
| `page(limit, afterTs, afterId)` (default) | Keyset paging. |
| `prune(before, dryRun)` (default `-1`) | Retention; `-1` = not supported. |
| `chainHead()`, `chainPage(fromSeq, limit)`, `unlinkedSince(ts)`, `rebuildChainIndex()`, `claimChainWriter()` (defaults) | Audit-chain support: recover the head on first link after restart, page the chain for `/audit/verify`. |
| `presentIds`, `unreadableUnits`, `count`, `flush`, `close` (defaults) | Housekeeping. |

## Implementing an `EventSink`

1. Depend on `inspecto-audit-spi` (scope `provided` if the host supplies it).
2. Implement `emit(Event)` so it never throws.
3. Register the class name in `src/main/resources/META-INF/services/com.gamma.audit.EventSink`.

```java
public final class StdoutSink implements com.gamma.audit.EventSink {
    @Override public void emit(com.gamma.audit.Event e) {
        try { System.out.println(e.toMap()); } catch (RuntimeException ignored) { }
    }
}
```

Only the first provider found is used; shipping a second sink next to `inspecto-event` is not a supported
fan-out — subscribe to `EventLog` instead (see [event spec](../event/spec-events-metrics.md)).

## References

- `spi/inspecto-audit-spi/src/main/java/com/gamma/audit/EventSink.java`
- `spi/inspecto-audit-spi/src/main/java/com/gamma/audit/EventStore.java`
- `spi/inspecto-audit-spi/src/main/java/com/gamma/audit/AuditChain.java`
- [Architecture](architecture.md) · [Features](features.md)
