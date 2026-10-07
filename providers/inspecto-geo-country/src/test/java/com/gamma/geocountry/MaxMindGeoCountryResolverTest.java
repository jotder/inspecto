package com.gamma.geocountry;

import com.gamma.spi.auth.GeoCountryResolver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.ServiceLoader;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** The MaxMind binding against a real {@code .mmdb} written by {@link TestMmdb} (D12: country only, fail-soft). */
class MaxMindGeoCountryResolverTest {

    private static MaxMindGeoCountryResolver over(Path dir) throws Exception {
        return new MaxMindGeoCountryResolver(TestMmdb.write(dir.resolve("c.mmdb"),
                Map.of("198.51.100.0/24", "DE", "203.0.113.0/24", "BR")).toString());
    }

    @Test
    void aPublicAddressResolvesToItsIsoCountryAndTheDbBuildEpoch(@TempDir Path dir) throws Exception {
        var r = over(dir);
        assertEquals(Optional.of(new GeoCountryResolver.Geo("DE", TestMmdb.BUILD_EPOCH)), r.resolve("198.51.100.4"));
        assertEquals("BR", r.resolve(" 203.0.113.250 ").orElseThrow().country());
    }

    @Test
    void anAddressNotInTheDatabaseResolvesToNothing(@TempDir Path dir) throws Exception {
        assertEquals(Optional.empty(), over(dir).resolve("192.0.2.1"));
    }

    @Test
    void everyNonPublicRangeResolvesToNothing(@TempDir Path dir) throws Exception {
        // A database that maps every such range to a country, so an empty answer proves the filter, not a miss
        // (::1 falls inside 0.0.0.0/8 as the IPv6 tree stores it, at ::0.0.0.0/104).
        Map<String, String> m = new java.util.LinkedHashMap<>();
        for (String p : List.of("0.0.0.0/8", "10.0.0.0/8", "100.64.0.0/10", "127.0.0.0/8", "169.254.0.0/16",
                "172.16.0.0/12", "192.168.0.0/16", "224.0.0.0/4", "255.255.255.255/32",
                "fc00::/7", "fe80::/10", "ff00::/8", "2001:db8::/32")) m.put(p, "DE");
        var r = new MaxMindGeoCountryResolver(TestMmdb.write(dir.resolve("p.mmdb"), m).toString());
        assertEquals("DE", r.resolve("2001:db8::7").orElseThrow().country(), "control: the IPv6 tree answers");
        for (String ip : List.of("0.1.2.3", "10.1.2.3", "100.64.0.1", "100.127.255.254", "127.0.0.1",
                "169.254.1.1", "172.16.5.5", "192.168.1.1", "224.0.0.1", "255.255.255.255",
                "fc00::1", "fd12:3456::1", "fe80::1", "ff02::1", "::1")) {
            assertEquals(Optional.empty(), r.resolve(ip), ip);
        }
    }

    @Test
    void aDatabaseOverTheSizeCapIsRefusedSoft(@TempDir Path dir) throws Exception {
        var r = over(dir);
        System.setProperty(MaxMindGeoCountryResolver.MAX_BYTES_PROPERTY, "64");
        try {
            assertEquals(Optional.empty(), r.resolve("198.51.100.4"));
        } finally {
            System.clearProperty(MaxMindGeoCountryResolver.MAX_BYTES_PROPERTY);
        }
        assertEquals(Optional.empty(), r.resolve("198.51.100.4"), "latched off for the run");
        assertEquals("DE", over(dir).resolve("198.51.100.4")
                .orElseThrow().country(), "control: the same file under the default cap resolves");
    }

    @Test
    void aHostnameIsNeverLookedUpAndGarbageNeverThrows(@TempDir Path dir) throws Exception {
        var r = over(dir);
        for (String ip : new String[]{"example.com", "not an ip", "", null, "2001:db8::1"}) {
            assertEquals(Optional.empty(), r.resolve(ip), String.valueOf(ip));
        }
    }

    @Test
    void aMissingOrUnreadableDatabaseIsSoftAndNeverRetried(@TempDir Path dir) throws Exception {
        assertEquals(Optional.empty(), new MaxMindGeoCountryResolver(dir.resolve("absent.mmdb").toString())
                .resolve("198.51.100.4"));
        assertEquals(Optional.empty(), new MaxMindGeoCountryResolver(null).resolve("198.51.100.4"));
        Path junk = Files.writeString(dir.resolve("junk.mmdb"), "not a database");
        var r = new MaxMindGeoCountryResolver(junk.toString());
        assertEquals(Optional.empty(), r.resolve("198.51.100.4"));
        // Loaded once: repairing the file later does not bring it back mid-run.
        TestMmdb.write(junk, Map.of("198.51.100.0/24", "DE"));
        assertEquals(Optional.empty(), r.resolve("198.51.100.4"));
    }

    @Test
    void concurrentFirstLookupsAllResolve(@TempDir Path dir) throws Exception {
        var r = over(dir);
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Future<Optional<GeoCountryResolver.Geo>>> fs = new ArrayList<>();
            for (int i = 0; i < 64; i++) fs.add(pool.submit(() -> r.resolve("198.51.100.9")));
            for (var f : fs) assertEquals("DE", f.get().orElseThrow().country());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void theBindingIsRegisteredForServiceLoaderDiscovery() {
        assertTrue(ServiceLoader.load(GeoCountryResolver.class).stream()
                .anyMatch(p -> p.type() == MaxMindGeoCountryResolver.class));
    }
}
