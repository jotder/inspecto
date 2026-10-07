package com.gamma.control;

import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.audit.AuditAttrs;
import com.gamma.audit.Event;
import com.gamma.audit.EventType;
import com.gamma.notify.Notification;
import com.gamma.service.CollectorService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * GeoIP on the audit trail and trigger T5 over real HTTP (ses-sns-adapter-design §6/§8, decision D12): an
 * operator-supplied resolver stamps {@code geo_country} + {@code geo_db_build} (never a city) on the sign-in row,
 * keyed on the F3 trusted-proxy client IP; an unconfigured one stamps nothing and breaks nothing; a new country for
 * a subject fires ONE security notification. A fake resolver stands in for the {@code .mmdb} reader (none is on
 * the offline build's classpath).
 */
class ControlApiGeoIpTest {

    private final HttpClient client = HttpClient.newHttpClient();

    /** The minted access token names the subject: code "code-alice" gives token "at-alice". */
    private static final TokenRelay RELAY = new TokenRelay() {
        @Override public Optional<Tokens> exchangeCode(String code, String verifier, String redirectUri) {
            return code.startsWith("code-")
                    ? Optional.of(new Tokens("at-" + code.substring(5), 300, "rt", 1800L)) : Optional.empty();
        }
        @Override public Optional<Tokens> refresh(String rt) { return Optional.empty(); }
    };

    private static final Authenticator AUTH = ex -> {
        String a = ex.getRequestHeaders().getFirst("Authorization");
        if (a != null && a.startsWith("Bearer at-")) return Optional.of(new Subject(a.substring(10), Set.of()));
        return Optional.empty();
    };

    /** 198.51.100.x is DE (lower-case, to prove normalisation), 203.0.113.x is BR, loopback is ZZ. */
    private static final GeoCountryResolver GEO = ip -> ip.startsWith("198.51.100.")
            ? Optional.of(new GeoCountryResolver.Geo("de", 1_760_000_000L))
            : ip.startsWith("203.0.113.") ? Optional.of(new GeoCountryResolver.Geo("BR", 1_760_000_000L))
            : (ip.startsWith("127.") || ip.contains(":")) ? Optional.of(new GeoCountryResolver.Geo("ZZ", 1_760_000_000L))
            : Optional.empty();

    private record Ctx(CollectorService svc, ControlApi api, int port) implements AutoCloseable {
        public void close() { api.close(); svc.close(); }
    }

    private Ctx open(Path cfg) throws Exception {
        Path pipe = PipelineConfigBatchTest.writePipeline(cfg, "");
        CollectorService svc = new CollectorService(List.of(pipe), List.of(), List.of(), 3600L, 1, null);
        ControlApi api = new ControlApi(svc, 0);
        api.start();
        return new Ctx(svc, api, api.port());
    }

    @AfterEach
    void reset() {
        TokenRelays.forTest(null);
        Authenticators.forTest(null);
        GeoCountryResolvers.forTest(null);
        System.clearProperty(TrustedProxies.PROPERTY);
        System.clearProperty("geoip.db");
    }

    private HttpResponse<String> exchange(int port, String subject, String xff) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/auth/exchange"))
                .header("Content-Type", "application/json")
                .POST(BodyPublishers.ofString("{\"code\":\"code-" + subject
                        + "\",\"codeVerifier\":\"v\",\"redirectUri\":\"http://localhost:4200/\"}"));
        if (xff != null) b.header("X-Forwarded-For", xff);
        return client.send(b.build(), BodyHandlers.ofString());
    }

    private static List<Event> signIns(Ctx c) {
        return c.svc.events().page(500, null, null).stream()
                .filter(e -> EventType.AUDIT.equals(e.type())
                        && "auth.exchange".equals(e.attributes().get(AuditAttrs.ACTION)))
                .toList();
    }

    private static List<Notification> security(Ctx c) throws InterruptedException {
        Thread.sleep(300);   // triggers are subscribers; let any firing (right or wrong) land
        return c.svc.notifications().recent(100).stream().filter(n -> "security".equals(n.category())).toList();
    }

    @Test
    void theSignInRowCarriesTheCountryAndDbBuildOfTheTrustedProxyClientAndNoCity(@TempDir Path cfg) throws Exception {
        System.setProperty(TrustedProxies.PROPERTY, "127.0.0.1, ::1");
        TokenRelays.forTest(RELAY);
        Authenticators.forTest(AUTH);
        GeoCountryResolvers.forTest(GEO);
        try (Ctx c = open(cfg)) {
            assertEquals(200, exchange(c.port, "alice", "198.51.100.4").statusCode());
            Map<String, ?> a = signIns(c).get(0).attributes();
            assertEquals("alice", a.get(AuditAttrs.ACTOR), "the Subject the minted token verified to");
            assertEquals("198.51.100.4", a.get(AuditAttrs.IP));
            assertEquals("DE", a.get(AuditAttrs.GEO_COUNTRY), "normalised ISO code of the resolved client");
            assertEquals("1760000000", String.valueOf(a.get(AuditAttrs.GEO_DB_BUILD)));
            assertTrue(a.keySet().stream().noneMatch(k -> k.contains("city")), "no city, ever: " + a.keySet());
        }
    }

    @Test
    void aForwardedForFromAnUntrustedPeerIsLocatedAsTheSocketPeer(@TempDir Path cfg) throws Exception {
        TokenRelays.forTest(RELAY);          // no control.trustedProxies: the header is not believed (F3)
        Authenticators.forTest(AUTH);
        GeoCountryResolvers.forTest(GEO);
        try (Ctx c = open(cfg)) {
            assertEquals(200, exchange(c.port, "alice", "198.51.100.4").statusCode());
            Map<String, ?> a = signIns(c).get(0).attributes();
            assertEquals("ZZ", a.get(AuditAttrs.GEO_COUNTRY), "the socket peer's country, not the header's: " + a);
        }
    }

    @Test
    void noGeoDatabaseMeansNoGeoAttributesAndNoError(@TempDir Path cfg) throws Exception {
        System.setProperty(TrustedProxies.PROPERTY, "127.0.0.1, ::1");
        System.setProperty("geoip.db", cfg.resolve("absent.mmdb").toString());   // set, but no reader on the classpath
        TokenRelays.forTest(RELAY);
        Authenticators.forTest(AUTH);
        try (Ctx c = open(cfg)) {
            assertEquals(200, exchange(c.port, "alice", "198.51.100.4").statusCode(), "set but inert");
            System.clearProperty("geoip.db");
            assertEquals(200, exchange(c.port, "alice", "203.0.113.9").statusCode(), "unset: off");
            List<Event> rows = signIns(c);
            assertEquals(2, rows.size());
            for (Event row : rows) {
                assertFalse(row.attributes().containsKey(AuditAttrs.GEO_COUNTRY), row.attributes().toString());
                assertFalse(row.attributes().containsKey(AuditAttrs.GEO_DB_BUILD), row.attributes().toString());
            }
            assertTrue(security(c).isEmpty(), "no geo, no T5");
        }
    }

    @Test
    void t5FiresOnceOnANewCountryForASubjectAndNotOnARepeat(@TempDir Path cfg) throws Exception {
        System.setProperty(TrustedProxies.PROPERTY, "127.0.0.1, ::1");
        TokenRelays.forTest(RELAY);
        Authenticators.forTest(AUTH);
        GeoCountryResolvers.forTest(GEO);
        try (Ctx c = open(cfg)) {
            assertEquals(200, exchange(c.port, "alice", "198.51.100.4").statusCode());   // baseline DE
            assertEquals(200, exchange(c.port, "alice", "198.51.100.5").statusCode());   // DE again
            assertTrue(security(c).isEmpty(), "baseline and repeat are silent");
            assertEquals(200, exchange(c.port, "alice", "203.0.113.9").statusCode());    // BR: new
            assertEquals(200, exchange(c.port, "alice", "203.0.113.10").statusCode());   // BR again
            List<Notification> sec = security(c);
            assertEquals(1, sec.size(), () -> sec.stream().map(n -> n.title() + " | " + n.body()).toList().toString());
            assertTrue(sec.get(0).body().contains("alice") && sec.get(0).body().contains("BR"), sec.get(0).body());
        }
    }

    @Test
    void t5CannotBeSteeredByASpoofedForwardedFor(@TempDir Path cfg) throws Exception {
        TokenRelays.forTest(RELAY);          // untrusted peer: every header value collapses to the socket peer
        Authenticators.forTest(AUTH);
        GeoCountryResolvers.forTest(GEO);
        try (Ctx c = open(cfg)) {
            assertEquals(200, exchange(c.port, "alice", "198.51.100.4").statusCode());
            assertEquals(200, exchange(c.port, "alice", "203.0.113.9").statusCode());
            assertTrue(security(c).isEmpty(), "a header an attacker wrote is not a new country");
        }
    }
}
