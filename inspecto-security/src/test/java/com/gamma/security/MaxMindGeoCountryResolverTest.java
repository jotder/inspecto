package com.gamma.security;

import com.gamma.control.GeoCountryResolver;
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
    void privateLoopbackAndLinkLocalAddressesResolveToNothing(@TempDir Path dir) throws Exception {
        // A database that maps private space to a country, so an empty answer proves the guard, not a miss.
        var r = new MaxMindGeoCountryResolver(TestMmdb.write(dir.resolve("p.mmdb"),
                Map.of("10.0.0.0/8", "DE", "127.0.0.0/8", "DE", "192.168.0.0/16", "DE", "169.254.0.0/16", "DE"))
                .toString());
        for (String ip : List.of("10.1.2.3", "127.0.0.1", "192.168.1.1", "169.254.1.1", "::1", "fe80::1")) {
            assertEquals(Optional.empty(), r.resolve(ip), ip);
        }
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
