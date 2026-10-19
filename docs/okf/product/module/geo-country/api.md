---
type: Reference
title: GeoCountryResolver SPI
description: The Java contract the geo-country provider implements - methods, semantics, registration and a minimal implementation.
resource: spi/inspecto-auth-spi/
tags: [module, geo-country, spi, api]
timestamp: 2026-10-19T00:00:00Z
---

# GeoCountryResolver SPI

This module has no HTTP routes. Its API is the Java contract it implements:
`com.gamma.spi.auth.GeoCountryResolver` in `spi/inspecto-auth-spi/src/main/java/com/gamma/spi/auth/GeoCountryResolver.java`,
`@PublicApi(since = "4.0.0")`.

```java
public interface GeoCountryResolver {
    record Geo(String country, long dbBuild) {}
    Optional<Geo> resolve(String ip);
}
```

| Member | Semantics |
|---|---|
| `Geo.country` | ISO 3166-1 alpha-2 code. The caller upper-cases it. |
| `Geo.dbBuild` | Database build epoch in seconds, stamped as `geo_db_build` so a stale database is visible. |
| `resolve(ip)` | The country of the IP literal, or empty when unknown. |

There is deliberately **no city field**, so no implementation can put a city on an audit row (D12).

## Rules for an implementation

* **Fast and offline.** It runs in-process on the audit path: never make a network call, never resolve
  a host name.
* **Answer empty, do not throw,** for private, loopback or unknown addresses and for bad input. A throw
  is tolerated by `AuditTrail` (it costs the geo attributes only) but is a defect.
* **Thread-safe.** One instance serves every request thread.
* **Cheap constructor.** It is created by ServiceLoader at boot; defer I/O to the first lookup, as
  `MaxMindGeoCountryResolver` does.

## Registration

List the class in `META-INF/services/com.gamma.spi.auth.GeoCountryResolver` inside your jar and put
the jar on the classpath. `GeoCountryResolvers.active()` only consults the slot when `-Dgeoip.db` is set,
so an implementation that does not read a file still needs that property set to be called.

## Minimal example

```java
public final class FixedCountryResolver implements GeoCountryResolver {
    @Override public Optional<Geo> resolve(String ip) {
        return "203.0.113.7".equals(ip) ? Optional.of(new Geo("NZ", 0L)) : Optional.empty();
    }
}
```

Tests can bypass discovery with `GeoCountryResolvers.forTest(resolver)` (pass `null` to restore).

The shipped implementation is described in [spec](spec.md).
