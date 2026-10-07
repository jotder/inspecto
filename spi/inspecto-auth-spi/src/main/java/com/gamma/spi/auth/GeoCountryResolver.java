package com.gamma.spi.auth;

import com.gamma.api.PublicApi;

import java.util.Optional;

/**
 * Resolves a client IP to an ISO 3166-1 country for the audit trail (ses-sns-adapter-design §6, decision D12).
 * An edition seam like {@link TokenRelay}: an implementation arrives via
 * {@code META-INF/services/com.gamma.spi.auth.GeoCountryResolver}; the core ships none.
 *
 * <p><b>D12 (operator, 2026-09-28): operator-supplied, country only.</b> Inspecto bundles no GeoIP database and
 * never downloads one. The operator points {@code -Dgeoip.db=<path to .mmdb>} at a database they obtained under a
 * licence they accept (MaxMind GeoLite2, or DB-IP Lite). The result carries a country and the database build and
 * nothing finer — there is deliberately no city field, so no implementation can hand one to the audit row.
 *
 * <p>Lookups run in-process on the audit path: an implementation must be fast, must never make a network call,
 * and should answer empty (not throw) for private, loopback or unknown addresses.
 */
@PublicApi(since = "4.0.0")
public interface GeoCountryResolver {

    /** A resolved location: {@code country} is the ISO 3166-1 alpha-2 code; {@code dbBuild} is the database's
     *  build epoch (seconds), so a stale database is visible on every row it located. */
    record Geo(String country, long dbBuild) {}

    /** The country of {@code ip}, or empty when unknown. */
    Optional<Geo> resolve(String ip);
}
