---
type: Guide
title: MaxMind geo-country resolver — admin guide
description: Install a GeoIP database, the -Dgeoip.db flags, licensing duties, failure modes and troubleshooting for the geo-country provider.
resource: providers/inspecto-geo-country/
tags: [module, geo-country, geoip, audit, admin-guide]
timestamp: 2026-10-19T00:00:00Z
---

# MaxMind geo-country resolver — admin guide

The `inspecto-geo-country` jar stamps an ISO 3166-1 country onto audit and access-denied rows, based on
the client IP. It is staged from Standard upward (`offerings/standard.toon`). Basic carries no resolver.
**Inspecto bundles no GeoIP database and never downloads one** (decision D12, operator 2026-09-28). You
supply the database file.

## 1. Obtain a database

Use a **Country** (or City) database in MaxMind `.mmdb` format. Two options are accepted by design:

| Database | Licence duties (yours, not Inspecto's) |
|---|---|
| MaxMind GeoLite2-Country | free account and licence key, GeoLite2 EULA, attribution, apply each update within 30 days |
| DB-IP Lite (country) | CC BY 4.0, attribution |

The resolver reads only `country.iso_code`. A City database works, but its city is never read, and
`GeoCountryResolver.Geo` has no city field. See [api](api.md).

## 2. Configure

| Flag | Default | Meaning |
|---|---|---|
| `-Dgeoip.db=<path>` | unset = **off** | Path to the `.mmdb` file. Unset means no geo attributes and no resolver lookup at all. |
| `-Dgeoip.db.maxBytes=<n>` | `268435456` (256 MB) | A file larger than this is refused before it is read. A Country DB is about 10 MB. |

Both are JVM system properties (`MaxMindGeoCountryResolver.PROPERTY` / `MAX_BYTES_PROPERTY`). There is no
TOON config key and no screen. Example:

```
java ... -Dgeoip.db=/opt/inspecto/geo/GeoLite2-Country.mmdb -jar inspecto.jar
```

Keep the file outside the install tree so an upgrade does not overwrite it, and readable by the service
account. No certificate or keystore is involved: the lookup is a local file read and never makes a
network call.

## 3. What you get

Each audit and access-denied row whose client IP resolves gets two attributes:

* `geo_country`: ISO alpha-2, upper-cased (`AuditAttrs.GEO_COUNTRY`)
* `geo_db_build`: the database build epoch in seconds (`AuditAttrs.GEO_DB_BUILD`), so a stale
  database is visible on every row it located

The IP is the F3 trusted-proxy client IP, so a spoofed `X-Forwarded-For` cannot choose a country. The
sign-in row uses it for T5 "new country per subject" alerts: see
[access admin guide](../access/admin-guide-auth-security.md).

These addresses never resolve and never touch the database: private (RFC 1918, IPv6 ULA, site-local),
loopback, link-local, multicast, broadcast, unspecified, CGNAT `100.64.0.0/10`, IPv6 forms embedding any
of these, and the host's own addresses. A test from `localhost` therefore shows no country, by design.

## 4. Updating the database

The file is read into memory once, lazily, on the first lookup, and is **not held open** (memory mode,
not memory-mapped), so on Windows too you can overwrite it while the engine runs. The new build takes
effect at the **next restart**. Check `geo_db_build` on recent rows to confirm.

## 5. Failure modes

All failures are fail-soft: a lookup never throws and never blocks a request. The audit row is always
written, only without geo attributes.

| Symptom | Cause | Fix |
|---|---|---|
| WARN `-Dgeoip.db is set but no GeoIP reader is on this edition's classpath` (once) | Basic bundle, or `inspecto-geo-country.jar` missing | Use Standard or higher, or stage the jar |
| WARN `GeoIP database <path> could not be opened (...)` (once) | file missing, unreadable, corrupt, or over `geoip.db.maxBytes`, or out of memory while reading | Fix the path or permissions, raise the cap, restart. Geo stays off for the rest of the run: no retry per request |
| Rows have no `geo_country` but no WARN | `-Dgeoip.db` unset, or the IP is private/loopback, or not in the database | Expected |
| IPv6 clients never resolve | IPv4-only database | Use a database that covers IPv6 |
| Country looks out of date | old build | Replace the file and restart; compare `geo_db_build` |

## References

* `providers/inspecto-geo-country/src/main/java/com/gamma/geocountry/MaxMindGeoCountryResolver.java`
* `spi/inspecto-auth-spi/src/main/java/com/gamma/spi/auth/GeoCountryResolvers.java`
* [spec](spec.md) · [architecture](architecture.md) · [GeoIP on the audit trail](../event/spec-events-metrics.md)
* Decision record: [ses-sns-adapter-design](../../../../archived-documents/plans-archive/ses-sns-adapter-design.md) §6, D12
