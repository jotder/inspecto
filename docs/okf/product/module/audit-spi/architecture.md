---
type: Concept
title: Audit contract — architecture
description: How audit-spi separates the event and audit-trail contract from its writer (inspecto-event) so any module can emit audit facts without depending on the store.
resource: spi/inspecto-audit-spi/
tags: [module, audit-spi, spi, architecture, audit]
timestamp: 2026-10-19T00:00:00Z
---

# Audit contract — architecture (`module.audit-spi`)

## Position

`inspecto-audit-spi` is one of the three contract modules of the [SPI layer](../../layer/spi/architecture.md)
(decision D-MR1 in [spec-module-reorg-decisions](../../layer/platform/spec-module-reorg-decisions.md)). Its
`META-INF/inspecto/module.toon` declares `buildRole: contract`, `offeringRole: base`, `bindingTime: build`: it is
in every Edition and bound at build time.

```
 emitting modules (access policy AuditTrail, la-api, features, ...)
        |  EventSink.current().emit(Event)          <- audit-spi (contract)
        v
 inspecto-event: CurrentEventSink -> Space EventLog -> AuditChainLinker -> EventStore
                                                         (memory | parquet | db)
        ^
        |  GET /events*, /audit/verify (event routes) read EventStore + AuditChain
```

## Design decisions

- **Audit is an event, not a second store.** An audit entry is an `Event` with `type = AUDIT` (or
  `ACCESS_DENIED`) whose who/what/where rides in `AuditAttrs` keys. The immutable store, the `/events*` read API,
  partitioning and export are reused unchanged.
- **Contract split from writer.** `AuditChain` is split by design: the format and verifier helpers live here, the
  writer (`AuditChainLinker`, the per-Space `EventLog`) lives in `platform/inspecto-event/`. A module that only
  emits depends on this jar alone.
- **One chain per Space.** `EventLog` holds the chain's monitor across link and append, so `audit_seq` order is
  append order. The head is not persisted separately; it is recovered from `EventStore.chainHead()` on the first
  link after restart, and records replayed from the Parquet write-ahead journal are already linked (no fork).
  ⚠ A database store shared by several pods would interleave one chain per pod — a known residual recorded in
  the `AuditChain` Javadoc.
- **Canonical hashing.** Because an event round-trips through Parquet columns or JDBC rows, the hash is SHA-256
  over a canonical re-encoding of the fields (prev hash included), not over stored bytes. Genesis prev hash is
  the empty string; seq starts at 1 — the same conventions as the Link Analysis Entity Fact log.
- **Emitting never fails the caller.** `EventSink.emit` never throws; absence of any provider degrades to a
  logged no-op, but a present-and-broken provider is loud (`IllegalStateException`).
- **Country only.** `geo_country` is written only when an operator-supplied GeoIP database resolves the client IP
  (decision D12, via `GeoCountryResolver` in [auth-spi](../auth-spi/api.md)); there is no city key.

## Who implements what

| Contract | Implementation | Module |
|---|---|---|
| `EventSink` (ServiceLoader) | `com.gamma.event.CurrentEventSink` | [event](../event/index.md) |
| `EventStore` (selected by the event module) | in-memory, Parquet and DB stores | [event](../event/architecture-overview.md) |
| `AuditChain` verification | `GET /audit/verify` | [event api](../event/api-events.md) |

## Failure modes

| Symptom | Cause |
|---|---|
| WARN once, no events recorded | No `EventSink` provider on the classpath (contract-only deployment). |
| Boot or first emit fails with `IllegalStateException` | An `EventSink` provider is registered but cannot link. |
| `/audit/verify` reports a break at a seq | A stored audit record was edited, deleted, inserted or reordered. |

## References

- `spi/inspecto-audit-spi/src/main/java/com/gamma/audit/AuditChain.java`
- `spi/inspecto-audit-spi/src/main/java/com/gamma/audit/AuditAttrs.java`
- `platform/inspecto-event/src/main/resources/META-INF/services/com.gamma.audit.EventSink`
- `platform/inspecto-event/src/test/java/com/gamma/event/AuditChainTest.java`
- [API](api.md) · [Features](features.md) · [Tests](tests-inventory.md)
