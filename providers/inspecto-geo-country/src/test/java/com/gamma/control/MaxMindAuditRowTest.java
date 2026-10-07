package com.gamma.control;

import com.gamma.audit.AuditAttrs;
import com.gamma.audit.EventType;
import com.gamma.geocountry.TestMmdb;
import com.gamma.service.CollectorService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The join the core's {@code ControlApiGeoIpTest} cannot see (it injects a fake resolver): with THIS module on the
 * classpath and {@code -Dgeoip.db} pointing at a real {@code .mmdb}, SPI discovery picks up the MaxMind binding
 * and a sign-in audit row carries {@code geo_country} + {@code geo_db_build} — and no city, although the
 * database record has one.
 */
class MaxMindAuditRowTest {

    private static final TokenRelay RELAY = new TokenRelay() {
        @Override public Optional<Tokens> exchangeCode(String code, String verifier, String redirectUri) {
            return Optional.of(new Tokens("at-alice", 300, "rt", 1800L));
        }
        @Override public Optional<Tokens> refresh(String rt) { return Optional.empty(); }
    };

    private static final Authenticator AUTH = ex -> "Bearer at-alice".equals(ex.getRequestHeaders().getFirst("Authorization"))
            ? Optional.of(new Subject("alice", Set.of())) : Optional.empty();

    @AfterEach
    void reset() {
        TokenRelays.forTest(null);
        Authenticators.forTest(null);
        System.clearProperty(TrustedProxies.PROPERTY);
        System.clearProperty("geoip.db");
    }

    @Test
    void theSignInRowCarriesTheMaxMindCountryAndDbBuild(@TempDir Path dir) throws Exception {
        Path db = TestMmdb.write(dir.resolve("country.mmdb"), Map.of("198.51.100.0/24", "DE"));
        System.setProperty("geoip.db", db.toString());
        System.setProperty(TrustedProxies.PROPERTY, "127.0.0.1, ::1");
        TokenRelays.forTest(RELAY);
        Authenticators.forTest(AUTH);
        CollectorService svc = new CollectorService(List.of(), List.of(), List.of(), 3600L, 1, null);
        ControlApi api = new ControlApi(svc, 0);
        try {
            api.start();
            var req = HttpRequest.newBuilder(URI.create("http://localhost:" + api.port() + "/api/v1/auth/exchange"))
                    .header("Content-Type", "application/json").header("X-Forwarded-For", "198.51.100.4")
                    .POST(HttpRequest.BodyPublishers.ofString(
                            "{\"code\":\"c\",\"codeVerifier\":\"v\",\"redirectUri\":\"http://localhost:4200/\"}"))
                    .build();
            assertEquals(200, HttpClient.newHttpClient().send(req, BodyHandlers.ofString()).statusCode());
            Map<String, ?> a = svc.events().page(500, null, null).stream()
                    .filter(e -> EventType.AUDIT.equals(e.type())
                            && "auth.exchange".equals(e.attributes().get(AuditAttrs.ACTION)))
                    .findFirst().orElseThrow().attributes();
            assertEquals("198.51.100.4", a.get(AuditAttrs.IP));
            assertEquals("DE", a.get(AuditAttrs.GEO_COUNTRY));
            assertEquals(String.valueOf(TestMmdb.BUILD_EPOCH), String.valueOf(a.get(AuditAttrs.GEO_DB_BUILD)));
            assertTrue(a.keySet().stream().noneMatch(k -> k.contains("city")), "no city, ever: " + a.keySet());
        } finally {
            api.close();
            svc.close();
        }
    }
}
