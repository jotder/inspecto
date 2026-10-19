---
type: Concept
title: MaxMind geo-country resolver — architecture
description: How the geo-country provider plugs into the audit trail through the GeoCountryResolver SPI and edition staging.
resource: providers/inspecto-geo-country/
tags: [module, geo-country, geoip, architecture]
timestamp: 2026-10-19T00:00:00Z
---

# MaxMind geo-country resolver — architecture

```
HTTP request ─▶ ApiContext.ip (F3 trusted-proxy IP)
                     │
              AuditTrail (inspecto-access)
                     │  GeoCountryResolvers.active()      ◀── -Dgeoip.db set?  no ─▶ empty, no lookup
                     │        │ SpiSlot / ServiceLoader
                     │        ▼
                     │  MaxMindGeoCountryResolver (inspecto-geo-country, optional jar)
                     │        │ EgressPolicy.deniedClass → private/loopback: empty
                     │        ▼
                     │  operator-supplied .mmdb (read once into memory)
                     ▼
        audit row + geo_country, geo_db_build
```

## Seams

* **Contract:** `GeoCountryResolver` in `spi/inspecto-auth-spi`, `@PublicApi(since = "4.0.0")`. The core
  ships no implementation: it is an edition seam, like `TokenRelay`. See [api](api.md).
* **Discovery:** `GeoCountryResolvers.active()` gates on `-Dgeoip.db` first, so a deployment that never
  sets it never loads the provider.
* **Binding:** `META-INF/services/com.gamma.spi.auth.GeoCountryResolver` in this jar, boot-time.
* **Single consumer today:** the audit trail. Any feature may call `GeoCountryResolvers.active()`; the
  lookup is in-process and must stay fast and offline.

## Edition staging

`geo-country` is in the `modules` list of `offerings/standard.toon`, so Standard, Advanced and Preview
bundles stage the jar; Basic does not, and a Basic install with `-Dgeoip.db` set logs one WARN and
carries no geo attributes. Being a separate jar keeps the `maxmind-db` dependency off Basic's
classpath.

## Design decisions

* **Operator-supplied, country only (D12, 2026-09-28).** Bundling or downloading a database would put
  licence duties (GeoLite2 EULA, 30-day update rule) on the product; country granularity keeps the audit
  row from carrying location data finer than needed.
* **Trusted IP only.** The IP comes from the F3 trusted-proxy resolution, so the country cannot be
  chosen by a client header.
* **Fail-soft.** Geo is decoration on the audit row; it must never cost the row or a request.
* **In-memory, not mapped.** Lets the operator replace the file in place on Windows; the trade-off is a
  restart to pick up a new build.

Operating details: [admin guide](admin-guide.md). Implementation: [spec](spec.md). Requirement history:
[plan](plan.md).
