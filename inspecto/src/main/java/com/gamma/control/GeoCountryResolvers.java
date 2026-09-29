package com.gamma.control;

import java.util.Optional;
import java.util.logging.Logger;

/**
 * The active {@link GeoCountryResolver}, or none. GeoIP is OFF unless {@code -Dgeoip.db} is set (D12). With it set
 * and no resolver on the classpath — the Personal edition, since the {@code maxmind-db} binding ships in inspecto-security — the property is inert:
 * one WARN, no attributes, no error.
 */
final class GeoCountryResolvers {

    private static final Logger LOG = Logger.getLogger(GeoCountryResolvers.class.getName());
    private static final SpiSlot<GeoCountryResolver> SLOT = new SpiSlot<>(GeoCountryResolver.class);
    private static volatile GeoCountryResolver testOverride;
    private static volatile boolean warned;

    private GeoCountryResolvers() {}

    static Optional<GeoCountryResolver> active() {
        if (testOverride != null) return Optional.of(testOverride);
        String db = System.getProperty("geoip.db");
        if (db == null || db.isBlank()) return Optional.empty();
        Optional<GeoCountryResolver> r = SLOT.active();
        if (r.isEmpty() && !warned) {
            warned = true;
            LOG.warning("-Dgeoip.db is set but no GeoIP reader is on this edition's classpath; "
                    + "audit rows carry no geo_country");
        }
        return r;
    }

    /** Test seam: a resolver used regardless of {@code -Dgeoip.db}; {@code null} restores discovery. */
    static void forTest(GeoCountryResolver r) {
        testOverride = r;
    }
}
