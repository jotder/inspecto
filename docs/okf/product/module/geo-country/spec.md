---
type: Reference
title: MaxMind geo-country resolver — spec
description: Implementation of MaxMindGeoCountryResolver - lazy in-memory open, the non-public address filter, size cap and fail-soft rules.
resource: providers/inspecto-geo-country/
tags: [module, geo-country, geoip, spec]
timestamp: 2026-10-19T00:00:00Z
---

# MaxMind geo-country resolver — spec

One class, `com.gamma.geocountry.MaxMindGeoCountryResolver`, implements the `@PublicApi` SPI
`com.gamma.spi.auth.GeoCountryResolver` (see [api](api.md)). Dependency: `com.maxmind.db:maxmind-db`
4.2.0 (Apache 2.0, `java.base` only, so the jlinked runtime needed no extra module). Registered in
`providers/inspecto-geo-country/src/main/resources/META-INF/services/com.gamma.spi.auth.GeoCountryResolver`;
module manifest `providers/inspecto-geo-country/src/main/resources/META-INF/inspecto/module.toon`
(`offeringRole: provider`, `bindingTime: boot`).

## Construction and open

* The no-arg ServiceLoader constructor captures `System.getProperty("geoip.db")`. A package-private
  constructor takes the path directly (tests).
* Construction does no I/O. The `Reader` is opened on the **first** `resolve` call under double-checked
  locking (`opened` and `reader` are `volatile`).
* Open order: blank path refused, then `Files.size` against `Long.getLong("geoip.db.maxBytes", 256 MB)`,
  then `new Reader(file, Reader.FileMode.MEMORY)`. MEMORY, not MEMORY_MAPPED: a mapped file stays
  locked on Windows and the operator could not replace it.
* Any `Exception`, `LinkageError` or `OutOfMemoryError` during open logs one WARN and leaves `reader`
  null with `opened = true`: off for the run, never retried per request. Other `VirtualMachineError`s
  propagate.

## Lookup

`resolve(String ip)`:

1. null/blank answers empty.
2. `InetAddress.ofLiteral(ip.trim())`: literals only, so a lookup can never become a DNS query.
3. `EgressPolicy.deniedClass(address) != null` answers empty without touching the database. The
   classifier is reused from the egress policy, not mirrored, so the two non-public lists cannot drift.
4. `reader.get(address, Map.class)`; the result must hold `country.iso_code` as a non-blank string.
5. Returns `Geo(iso, metadata.buildEpoch())`.

Every exception inside `resolve` (unparseable IP, IPv6 against an IPv4 database, corrupt record) answers
empty. `Reader` is safe for concurrent lookups, so the resolver is shared across request threads.

## Caller side

`GeoCountryResolvers.active()` (in `inspecto-auth-spi`) answers empty unless `-Dgeoip.db` is set, then
takes the resolver from an `SpiSlot`; if the property is set but no implementation is on the classpath it
logs one WARN. `AuditTrail` upper-cases the country and stamps `geo_country` / `geo_db_build`; a resolver
that throws costs only the geo attributes, never the row (`platform/inspecto-access/src/main/java/com/gamma/access/AuditTrail.java`).

## Invariants

* No city, ever: the `Geo` record has no field for it.
* No network: literal parsing plus a local file.
* No per-request failure: worst case is an empty answer.

## Tests

`MaxMindGeoCountryResolverTest` (built against an in-test `.mmdb` from `TestMmdb`) and
`MaxMindAuditRowTest` (the attributes on a real audit row); inventory in [tests-inventory](tests-inventory.md).
