package com.gamma.security;

import com.gamma.control.GeoCountryResolver;
import com.gamma.pipeline.exec.EgressPolicy;
import com.maxmind.db.Reader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

/**
 * The {@code maxmind-db} binding of {@link GeoCountryResolver} (ses-sns-adapter-design §6, D12): reads the
 * operator-supplied {@code -Dgeoip.db} {@code .mmdb} (GeoLite2-Country / DB-IP Lite shape) and answers the ISO
 * {@code country.iso_code} only — a City database's city is never read. {@code dbBuild} is the file's
 * {@code build_epoch} metadata.
 *
 * <p>Fail-soft: a missing or unreadable database resolves nothing and logs ONE warning; a lookup never throws
 * and never blocks a request. The database is read into memory once, lazily, on first lookup (the file is not held open, so
 * an operator can replace it; a restart picks the new build up); {@link Reader} is safe for
 * concurrent lookups. Only IP literals are parsed ({@link InetAddress#ofLiteral}), so a lookup can never become
 * a DNS query. Every address {@link EgressPolicy#deniedClass} classifies as non-public answers empty without touching
 * the database: unspecified ({@code 0.0.0.0/8}, {@code ::}), loopback, link-local, multicast, broadcast, private
 * (RFC 1918, IPv6 ULA {@code fc00::/7}, site-local {@code fec0::/10}), CGNAT {@code 100.64.0.0/10}, IPv6 forms that
 * embed any of those, and this host's own addresses. The classifier is reused, not mirrored, so the two lists
 * cannot drift.
 *
 * <p>A file over {@code -Dgeoip.db.maxBytes} (default 256 MB; a Country DB is ~10 MB) is refused before it is read,
 * like a corrupt one: one WARN, off for the run. An {@link OutOfMemoryError} while reading it is caught the same way;
 * every other {@link VirtualMachineError} propagates.
 */
public final class MaxMindGeoCountryResolver implements GeoCountryResolver {

    static final String PROPERTY = "geoip.db";
    static final String MAX_BYTES_PROPERTY = "geoip.db.maxBytes";
    static final long DEFAULT_MAX_BYTES = 256L * 1024 * 1024;
    private static final Logger log = LoggerFactory.getLogger(MaxMindGeoCountryResolver.class);

    private final String path;
    private volatile boolean opened;
    private volatile Reader reader;   // stays null after a failed open: off, never retried per request

    /** ServiceLoader constructor: the database named by {@code -Dgeoip.db}. */
    public MaxMindGeoCountryResolver() {
        this(System.getProperty(PROPERTY));
    }

    MaxMindGeoCountryResolver(String path) {
        this.path = path;
    }

    private Reader reader() {
        if (!opened) {
            synchronized (this) {
                if (!opened) {
                    try {
                        if (path == null || path.isBlank()) throw new IllegalStateException("-Dgeoip.db is not set");
                        long max = Long.getLong(MAX_BYTES_PROPERTY, DEFAULT_MAX_BYTES);
                        long size = Files.size(Path.of(path));
                        if (size > max) throw new IllegalStateException(
                                size + " bytes exceeds -D" + MAX_BYTES_PROPERTY + "=" + max);
                        // MEMORY, not the default MEMORY_MAPPED: a mapped file stays locked on Windows, so the
                        // operator could not replace the .mmdb while the engine runs. A Country DB is ~10 MB.
                        reader = new Reader(new File(path), Reader.FileMode.MEMORY);
                    } catch (Exception | LinkageError | OutOfMemoryError e) {
                        log.warn("GeoIP database {} could not be opened ({}); audit rows carry no geo_country",
                                path, e.toString());
                    }
                    opened = true;
                }
            }
        }
        return reader;
    }

    @Override
    public Optional<Geo> resolve(String ip) {
        try {
            if (ip == null || ip.isBlank()) return Optional.empty();
            InetAddress a = InetAddress.ofLiteral(ip.trim());
            if (EgressPolicy.deniedClass(a) != null) return Optional.empty();
            Reader r = reader();
            if (r == null) return Optional.empty();
            Map<?, ?> rec = r.get(a, Map.class);
            if (rec == null || !(rec.get("country") instanceof Map<?, ?> country)
                    || !(country.get("iso_code") instanceof String iso) || iso.isBlank()) return Optional.empty();
            return Optional.of(new Geo(iso, r.getMetadata().buildEpoch().longValue()));
        } catch (Exception e) {
            return Optional.empty();   // unparseable IP, IPv6 against an IPv4 database, corrupt record
        }
    }
}
