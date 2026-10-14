package com.gamma.control;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.audit.Event;
import com.gamma.entitystore.LinkAnalysisSettings;
import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.event.EventLog;
import com.gamma.pipeline.ComponentStore;
import com.gamma.pipeline.ViewDefinition;
import com.gamma.pipeline.ViewStore;
import com.gamma.service.CollectorService;
import com.gamma.spi.auth.AccessDecider;
import com.gamma.spi.auth.AccessDeciders;
import com.gamma.spi.auth.Authenticators;
import com.gamma.spi.auth.Subject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * LA-EMBED-VIEW-1 slices S1-S3 over real HTTP with an ARMED Authenticator (with no Subject {@code withCapability} is a no-op and every
 * gate would be open, so nothing would be proven): mint / list / revoke, the public read-only view, the uniform 404, masking that follows
 * the Space, the headers, auto-revoke, the rate limits, the Dossier root that does not move, and a token that appears in no log line or
 * audit event.
 */
class ControlApiInvestigationEmbedTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String CREATE = "{\"id\":\"inv-a\",\"purpose\":\"Fraud referral FR-9\",\"title\":\"Ring around a\",\"dataset\":\"calls_ds\","
            + "\"sourceCol\":\"caller\",\"targetCol\":\"callee\",\"linkKindCol\":\"channel\"}";
    private static final String INV = "/inv/investigations/inv-a";
    private static final String PUB = "/public/investigations/";
    /** lead-1 may mint; analyst-2 holds the verb but is only an analyst; stranger-4 is no member; nocap-6 is a lead without the verb. */
    private static final String L = "Bearer lead", A = "Bearer analyst", S = "Bearer stranger", NOCAP = "Bearer nocap";
    private final HttpClient client = HttpClient.newHttpClient();

    private record Ctx(CollectorService svc, ControlApi api, int port, Path root) implements AutoCloseable {
        public void close() {
            api.close();
            svc.close();
        }
    }

    @AfterEach
    void reset() {
        CaseTeamObjectEngine.CASES = null;
        Authenticators.forTest(null);
        AccessDeciders.forTest(null);
        for (String p : List.of("la.embed.enabled", "control.cors", "control.rateLimit.embed.capacity", "control.rateLimit.embed.refillPerSecond", "ui.dir"))
            System.clearProperty(p);
    }

    private static void subjects() {
        Set<String> all = Set.of("canManageIncidents", "canShareInvestigation");
        Authenticators.forTest(ex -> switch (String.valueOf(ex.getRequestHeaders().getFirst("Authorization"))) {
            case L -> Optional.of(new Subject("lead-1", all));
            case A -> Optional.of(new Subject("analyst-2", all));
            case S -> Optional.of(new Subject("stranger-4", all));
            case NOCAP -> Optional.of(new Subject("lead-1", Set.of("canManageIncidents")));
            default -> Optional.empty();
        });
    }

    private Ctx open(Path configDir, Path writeRoot) throws Exception {
        Path pipe = PipelineConfigBatchTest.writePipeline(configDir, "");
        System.setProperty("assist.write.root", writeRoot.toString());
        try {
            CollectorService svc = new CollectorService(List.of(pipe), 3600, 1);
            ControlApi api = new ControlApi(svc, 0);
            api.start();
            new ViewStore(writeRoot.resolve("views")).write(new ViewDefinition("calls_view", "flow-x", List.of(),
                    "SELECT caller, callee, channel FROM (VALUES ('a','b','voice'),('a','c','sms'),('b','d','voice'))"
                            + " AS t(caller,callee,channel)", "2026-09-30T00:00:00Z"));
            new ComponentStore(writeRoot.resolve("registry")).write("dataset", "calls_ds", Map.of("view", "calls_view"));
            return new Ctx(svc, api, api.port(), writeRoot);
        } finally {
            System.clearProperty("assist.write.root");
        }
    }

    private HttpResponse<String> send(Ctx c, String method, String path, String body, String auth) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + c.port + "/api/v1" + path))
                .header("Content-Type", "application/json");
        if (auth != null) b.header("Authorization", auth);
        b = "GET".equals(method) ? b.GET() : b.method(method, BodyPublishers.ofString(body == null ? "" : body));
        return client.send(b.build(), BodyHandlers.ofString());
    }

    private JsonNode ok(Ctx c, String method, String path, String body, String auth) throws Exception {
        HttpResponse<String> r = send(c, method, path, body, auth);
        assertEquals(200, r.statusCode(), method + " " + path + " as " + auth + " → " + r.body());
        return JSON.readTree(r.body()).get("data");
    }

    private int status(Ctx c, String method, String path, String body, String auth) throws Exception {
        return send(c, method, path, body, auth).statusCode();
    }

    private HttpResponse<String> pub(Ctx c, String token) throws Exception {
        return send(c, "GET", PUB + token, null, null);
    }

    /** What a refusal tells the client: status, error code and message (the correlation id differs per request by design). */
    private String refusal(HttpResponse<String> r) throws Exception {
        JsonNode e = JSON.readTree(r.body()).path("error");
        return r.statusCode() + " " + e.path("errorCode").asText() + " " + e.path("message").asText();
    }

    private static void enable() {
        System.setProperty("la.embed.enabled", "true");
    }

    /** inv-a: seed a, expand (a-b, a-c), an annotation naming a, led by lead-1; analyst-2 is an analyst. */
    private void investigation(Ctx c) throws Exception {
        ok(c, "POST", "/inv/investigations", CREATE, L);
        ok(c, "POST", INV + "/ops", "{\"op\":\"seed\",\"ids\":[\"a\"]}", L);
        ok(c, "POST", INV + "/ops", "{\"op\":\"expand\"}", L);
        ok(c, "POST", INV + "/ops", "{\"op\":\"annotate\",\"ids\":[\"a\"],\"note\":\"suspect a\"}", L);
        ok(c, "POST", INV + "/members", "{\"subject\":\"analyst-2\",\"role\":\"analyst\"}", L);
    }

    private String mint(Ctx c, String body) throws Exception {
        return ok(c, "POST", INV + "/embeds", body, L).get("token").asText();
    }

    private static void settings(Path root, String masking, LinkAnalysisSettings.Embed embed) throws Exception {
        new LinkAnalysisSettings(null, null, null, masking, null, null, null, null, null, null, null, null, null, null, null, embed)
                .write(root.resolve("link-analysis.toon"));
    }

    private static Path invDir(Ctx c) {
        return c.root().resolve("audit/snapshots/investigations/inv-a");
    }

    private static List<Path> grantFiles(Ctx c) throws Exception {
        try (Stream<Path> s = Files.list(invDir(c).resolve("embeds"))) {
            return s.toList();
        }
    }

    // ── S1: mint ───────────────────────────────────────────────────────────────────────────────────────

    @Test
    void mintIsGatedByTheKillSwitchTheVerbAndTheLeadRoleAndShowsTheTokenOnce(@TempDir Path cfg, @TempDir Path root) throws Exception {
        subjects();
        try (Ctx c = open(cfg, root)) {
            investigation(c);
            assertEquals(503, status(c, "POST", INV + "/embeds", "{}", L), "the kill switch is off by default");
            enable();
            assertEquals(403, status(c, "POST", INV + "/embeds", "{}", NOCAP), "a lead without canShareInvestigation");
            assertEquals(403, status(c, "POST", INV + "/embeds", "{}", A), "the verb without the lead role");
            assertEquals(404, status(c, "POST", INV + "/embeds", "{}", S), "a non-member reads as absence");
            assertEquals(404, status(c, "POST", "/inv/investigations/nope/embeds", "{}", L));

            Instant before = Instant.now();
            JsonNode m = ok(c, "POST", INV + "/embeds", "{\"label\":\"auditor visit\"}", L);   // the probe that WOULD succeed
            String token = m.get("token").asText();
            assertTrue(token.length() >= 50 && token.contains("."), token);
            assertEquals("/public/investigations/" + token, m.get("url").asText());
            Duration ttl = Duration.between(before, Instant.parse(m.get("expiresAt").asText()));
            assertTrue(ttl.toHours() >= 71 && ttl.toHours() <= 72, "the default lifetime is 72 h: " + ttl);
            assertEquals(m.get("grantId").asText(), token.substring(0, token.indexOf('.')));

            // shown once: the list, the grant file and the whole Space tree hold the hash, never the token
            JsonNode list = ok(c, "GET", INV + "/embeds", null, L);
            assertFalse(list.toString().contains(token), "the list never carries the token");
            assertFalse(list.toString().contains(token.substring(token.indexOf('.') + 1)), "nor its secret half");
            assertFalse(list.toString().toLowerCase().contains("sha"), "nor a hash: " + list);
            JsonNode g = list.at("/grants/0");
            assertEquals("active", g.get("status").asText());
            assertEquals("lead-1", g.get("issuedBy").asText());
            assertEquals("auditor visit", g.get("label").asText());
            assertEquals(0, g.get("viewCount").asInt());
            String secret = token.substring(token.indexOf('.') + 1);
            try (Stream<Path> all = Files.walk(root)) {
                for (Path f : all.filter(Files::isRegularFile).toList())
                    assertFalse(new String(Files.readAllBytes(f), StandardCharsets.UTF_8).contains(secret), "the token's secret is stored nowhere: " + f);
            }
            String stored = Files.readString(grantFiles(c).get(0));
            assertTrue(stored.contains("tokenSha256"), "only the hash is stored: " + stored);
        }
    }

    /** LA-EMBED-VIEW-1 remainder (a): the viewer page is framed by the allow-list of the TOKEN's Space, and by nobody for a token that is not live. */
    @Test
    void theViewerPageIsFramedOnlyByTheOriginsOfTheTokensOwnSpaceAndNobodyOnceTheLinkIsRevokedOrForged(@TempDir Path cfg, @TempDir Path root,
            @TempDir Path ui) throws Exception {
        subjects();
        enable();
        Files.writeString(ui.resolve("index.html"), "<!doctype html><html><head></head><body><app-root></app-root></body></html>");
        System.setProperty("ui.dir", ui.toString());
        try (Ctx c = open(cfg, root)) {
            investigation(c);
            settings(root, null, new LinkAnalysisSettings.Embed(null, null, null, List.of("https://portal.example")));
            String token = mint(c, "{}");
            String grantId = token.substring(0, token.indexOf('.'));
            java.util.function.Function<String, String> csp = t -> {
                try {
                    HttpResponse<String> r = client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + c.port + "/embed/inv/" + t)).GET().build(),
                            BodyHandlers.ofString());
                    assertEquals(200, r.statusCode());
                    return r.headers().firstValue("Content-Security-Policy").orElseThrow();
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            };
            assertTrue(csp.apply(token).endsWith("frame-ancestors https://portal.example"), "the live token's own Space");
            assertTrue(csp.apply(grantId + ".wrongsecret").endsWith("frame-ancestors 'none'"), "right id, wrong secret");
            assertTrue(csp.apply("nope.nope").endsWith("frame-ancestors 'none'"), "unknown grant");
            assertEquals(200, status(c, "POST", INV + "/embeds/" + grantId + "/revoke", "{}", L));
            assertTrue(csp.apply(token).endsWith("frame-ancestors 'none'"), "revoked: nobody");
        }
    }

    @Test
    void expiryDefaultsTo72hCapsAtThirtyDaysAndTheSpaceCanTightenIt(@TempDir Path cfg, @TempDir Path root) throws Exception {
        subjects();
        enable();
        try (Ctx c = open(cfg, root)) {
            investigation(c);
            Instant now = Instant.now();
            Duration one = Duration.between(now, Instant.parse(ok(c, "POST", INV + "/embeds", "{\"ttl_hours\":1}", L).get("expiresAt").asText()));
            assertTrue(one.toMinutes() >= 59 && one.toMinutes() <= 60, "one hour is allowed: " + one);
            Duration max = Duration.between(now, Instant.parse(ok(c, "POST", INV + "/embeds", "{\"ttl_hours\":720}", L).get("expiresAt").asText()));
            assertTrue(max.toHours() >= 719 && max.toHours() <= 720, "30 days is allowed: " + max);
            for (String bad : List.of("{\"ttl_hours\":721}", "{\"ttl_hours\":0}", "{\"ttl_hours\":-5}", "{\"ttl_hours\":1.5}", "{\"ttl_hours\":\"12\"}"))
                assertEquals(422, status(c, "POST", INV + "/embeds", bad, L), bad + " is refused, never clamped");
            assertEquals(422, status(c, "POST", INV + "/embeds", "{\"frame_ancestors\":[\"https://partner.example\"]}", L), "links are link-only");
            assertEquals(422, status(c, "POST", INV + "/embeds", "{\"label\":\"x\\u0007y\"}", L));

            settings(root, null, new LinkAnalysisSettings.Embed(24, 48, null));   // the Space tightens: default 24 h, cap 48 h
            Duration d = Duration.between(now, Instant.parse(ok(c, "POST", INV + "/embeds", "{}", L).get("expiresAt").asText()));
            assertTrue(d.toHours() >= 23 && d.toHours() <= 24, "the Space's default applies: " + d);
            assertEquals(200, status(c, "POST", INV + "/embeds", "{\"ttl_hours\":48}", L));
            assertEquals(422, status(c, "POST", INV + "/embeds", "{\"ttl_hours\":49}", L), "above the Space's cap");
            settings(root, null, new LinkAnalysisSettings.Embed(null, 2000, null));   // the Space RAISES the cap above the 30 d default
            assertEquals(200, status(c, "POST", INV + "/embeds", "{\"ttl_hours\":2000}", L));
            assertEquals(422, status(c, "POST", INV + "/embeds", "{\"ttl_hours\":2001}", L), "still refused above the Space's cap, never clamped");
        }
    }

    @Test
    void aSpaceCanRefuseToShareInvestigationsOfAClassificationAndAPolicyCanForbidTheActionEmbed(@TempDir Path cfg, @TempDir Path root)
            throws Exception {
        subjects();
        enable();
        try (Ctx c = open(cfg, root)) {
            investigation(c);
            new ComponentStore(root.resolve("registry")).write("dataset", "calls_ds", Map.of("view", "calls_view",
                    "columns", List.of(Map.of("name", "caller", "classification", "MSISDN"), Map.of("name", "callee", "classification", "PUBLIC"))));
            assertEquals(200, status(c, "POST", INV + "/embeds", "{}", L), "nothing is refused until the Space says so");
            settings(root, null, new LinkAnalysisSettings.Embed(null, null, List.of("MSISDN")));
            HttpResponse<String> refused = send(c, "POST", INV + "/embeds", "{}", L);
            assertEquals(422, refused.statusCode(), refused.body());
            assertTrue(refused.body().contains("MSISDN"), refused.body());
            settings(root, null, new LinkAnalysisSettings.Embed(null, null, List.of("IMSI")));
            assertEquals(200, status(c, "POST", INV + "/embeds", "{}", L), "a class the Space does not refuse");

            List<String> asked = new CopyOnWriteArrayList<>();
            List<Object> resources = new CopyOnWriteArrayList<>();
            AccessDeciders.forTest((ex, subject, action, route, kind, resource) -> {
                if (!"embed".equals(action)) return AccessDecider.Decision.ABSTAIN;
                asked.add(action + "/" + kind);
                if (resource != null) resources.add(resource);
                return kind != null ? AccessDecider.Decision.DENY : AccessDecider.Decision.ABSTAIN;
            });
            assertEquals(403, status(c, "POST", INV + "/embeds", "{}", L), "a policy forbids the action embed on this Investigation");
            assertTrue(asked.contains("embed/investigation"), asked.toString());
            assertTrue(resources.stream().anyMatch(r -> r instanceof Map<?, ?> m && "MSISDN".equals(m.get("classification"))), "the PDP sees the classification: " + resources);
            AccessDeciders.forTest(null);
            assertEquals(200, status(c, "POST", INV + "/embeds", "{}", L));
        }
    }

    // ── S2: the public view ───────────────────────────────────────────────────────────────────────────

    @Test
    void theHolderSeesTheViewMaskedAsTheSpaceMasksWithNoLoginAndTheHeadersAreHardened(@TempDir Path cfg, @TempDir Path root) throws Exception {
        subjects();
        enable();
        System.setProperty("control.cors", "http://localhost:4200");   // CORS is ON for the host: the embed answer must still carry none
        try (Ctx c = open(cfg, root)) {
            investigation(c);
            settings(root, "none", null);
            String token = mint(c, "{}");

            HttpResponse<String> r = pub(c, token);   // no Authorization header at all
            assertEquals(200, r.statusCode(), r.body());
            JsonNode d = JSON.readTree(r.body()).get("data");
            assertEquals("inv-a", d.at("/investigation/id").asText());
            assertEquals("Ring around a", d.at("/investigation/title").asText());
            assertEquals(3, d.at("/workingSet/entities").size(), d.toString());
            assertEquals(2, d.at("/workingSet/links").size());
            assertEquals("a", d.at("/workingSet/entities/0/entityId").asText(), "maskingMode none: the Space shows raw ids, so the embed does");
            assertEquals("suspect a", d.at("/annotations/0/note").asText());
            assertTrue(d.at("/head/workingSetHash").asText().startsWith("sha256:"));
            assertFalse(d.has("reveal") || d.has("pending") || d.has("drafts") || d.has("case"), "nothing past the view");
            assertFalse(r.body().contains("calls_ds"), "no Dataset name");
            assertFalse(r.body().contains("\"permissions\""), "no Subject, so no permissions");

            assertEquals("frame-ancestors 'none'", r.headers().firstValue("Content-Security-Policy").orElse(null), "link-only: never framed");
            assertEquals("DENY", r.headers().firstValue("X-Frame-Options").orElse(null));
            assertEquals("no-store", r.headers().firstValue("Cache-Control").orElse(null));
            assertEquals("no-referrer", r.headers().firstValue("Referrer-Policy").orElse(null));
            assertEquals("nosniff", r.headers().firstValue("X-Content-Type-Options").orElse(null));
            assertTrue(r.headers().firstValue("Access-Control-Allow-Origin").isEmpty(), "no CORS on the embed: " + r.headers().map());
            assertTrue(r.headers().firstValue("Set-Cookie").isEmpty());
            HttpResponse<String> nope = pub(c, token + "x");
            assertEquals("frame-ancestors 'none'", nope.headers().firstValue("Content-Security-Policy").orElse(null), "the 404 carries them too");
            assertTrue(nope.headers().firstValue("Access-Control-Allow-Origin").isEmpty());
            assertTrue(send(c, "GET", "/inv/investigations", null, L).headers().firstValue("Access-Control-Allow-Origin").isPresent(),
                    "the probe that WOULD carry CORS: any other route does");
        }
    }

    @Test
    void theViewFollowsTheSpaceMaskingModeAndNeverRevealsARawIdWhenMaskingIsOn(@TempDir Path cfg, @TempDir Path root) throws Exception {
        subjects();
        enable();
        try (Ctx c = open(cfg, root)) {
            investigation(c);
            settings(root, "all", null);
            String token = mint(c, "{}");
            JsonNode d = JSON.readTree(pub(c, token).body()).get("data");
            for (JsonNode e : d.at("/workingSet/entities")) {
                assertTrue(e.get("entityId").asText().startsWith("masked:"), "every id is a pseudonym: " + e);
                assertTrue(e.get("identity").asText().startsWith("masked:"), e.toString());
            }
            for (JsonNode l : d.at("/workingSet/links")) {
                assertTrue(l.get("source").asText().startsWith("masked:") && l.get("target").asText().startsWith("masked:"), l.toString());
                assertTrue(l.get("linkId").asText().startsWith("lk."), "link ids are minted from what the holder sees: " + l);
            }
            assertTrue(d.at("/annotations/0/note").asText().startsWith("suspect masked:"), "ids inside a note are masked too: " + d.at("/annotations"));
            assertEquals("all", d.at("/masking/mode").asText());
            String body = pub(c, token).body();
            for (String raw : List.of("\"a\"", "\"b\"", "\"c\"", "\"d\"")) assertFalse(body.contains(raw), "no raw id " + raw + " in " + body);
            assertEquals(ok(c, "GET", INV + "/working-set", null, L).at("/rows/0/entityId").asText(), d.at("/workingSet/entities/0/entityId").asText(),
                    "the same pseudonym an analyst's own read shows: one masking path");
        }
    }

    @Test
    void aLinkCanBeNarrowedInScopeAndStricterInMaskingThanItsSpaceNeverLooser(@TempDir Path cfg, @TempDir Path root) throws Exception {
        subjects();
        enable();
        try (Ctx c = open(cfg, root)) {
            investigation(c);
            settings(root, "none", null);
            String defaults = mint(c, "{}");
            assertEquals("full", ok(c, "GET", INV + "/embeds", null, L).at("/grants/0/scope").asText(), "the default scope shows everything");

            JsonNode minted = ok(c, "POST", INV + "/embeds", "{\"scope\":\"working_set\",\"masking_mode\":\"all\"}", L);
            assertEquals("working_set", minted.get("scope").asText());
            assertEquals("all", minted.get("maskingMode").asText());
            JsonNode narrow = JSON.readTree(pub(c, minted.get("token").asText()).body()).get("data");
            assertEquals(0, narrow.get("annotations").size(), "working_set: no notes");
            assertEquals(0, narrow.get("references").size());
            assertEquals("working_set", narrow.at("/link/scope").asText());
            for (JsonNode e : narrow.at("/workingSet/entities"))
                assertTrue(e.get("entityId").asText().startsWith("masked:"), "the link is stricter than its 'none' Space: " + e);
            JsonNode wide = JSON.readTree(pub(c, defaults).body()).get("data");
            assertEquals("a", wide.at("/workingSet/entities/0/entityId").asText(), "the other link follows the Space");
            assertEquals(1, wide.get("annotations").size());

            JsonNode notes = ok(c, "POST", INV + "/embeds", "{\"scope\":\"with_notes\"}", L);
            assertEquals(1, JSON.readTree(pub(c, notes.get("token").asText()).body()).at("/data/annotations").size());

            assertEquals(422, status(c, "POST", INV + "/embeds", "{\"scope\":\"everything\"}", L));
            assertEquals(422, status(c, "POST", INV + "/embeds", "{\"masking_mode\":\"bogus\"}", L));
            settings(root, "all", null);   // the Space tightens: a link that asks for LESS masking than the Space is refused
            assertEquals(422, status(c, "POST", INV + "/embeds", "{\"masking_mode\":\"none\"}", L), "a link cannot be looser than the Space");
            assertEquals(422, status(c, "POST", INV + "/embeds", "{\"masking_mode\":\"typed\"}", L));
            assertEquals(200, status(c, "POST", INV + "/embeds", "{\"masking_mode\":\"all\"}", L));
            assertEquals(404, status(c, "POST", INV + "/embeds", "{\"scope\":\"working_set\"}", S), "the gates are unchanged");
            JsonNode after = JSON.readTree(pub(c, defaults).body()).get("data");   // a stricter Space never revokes; it masks the old link too
            assertTrue(after.at("/workingSet/entities/0/entityId").asText().startsWith("masked:"), after.toString());
        }
    }

    @Test
    void everyKindOfBadTokenIsTheSameFourOhFourAndAValidSubjectOnThePublicPathChangesNothing(@TempDir Path cfg, @TempDir Path root)
            throws Exception {
        subjects();
        enable();
        try (Ctx c = open(cfg, root)) {
            investigation(c);
            String good = mint(c, "{}");
            assertEquals(200, pub(c, good).statusCode());
            String expiredTok = mint(c, "{}");
            String revokedTok = mint(c, "{}");
            String goneTok = mint(c, "{}");
            ok(c, "POST", INV + "/embeds/" + revokedTok.substring(0, revokedTok.indexOf('.')) + "/revoke", "{}", L);
            // expire one by rewriting its stored expiry (the clock is the server's; there is no other way to age a record)
            Path expiredFile = invDir(c).resolve("embeds").resolve(expiredTok.substring(0, expiredTok.indexOf('.')) + ".json");
            Files.writeString(expiredFile, Files.readString(expiredFile).replaceAll("\"expiresAt\":\"[^\"]+\"", "\"expiresAt\":\"2020-01-01T00:00:00Z\""));
            String flipped = good.substring(0, good.length() - 1) + (good.endsWith("A") ? "B" : "A");
            String otherSecret = good.substring(0, good.indexOf('.') + 1) + "A".repeat(43);

            List<String> answers = new ArrayList<>();
            for (String bad : List.of(flipped, otherSecret, "unknown", "no.dot.at.all", "zzzzzzzzzzzzzzzz.AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
                    expiredTok, revokedTok, "..%2F..%2Fheader", "A".repeat(200)))
                answers.add(refusal(pub(c, bad)));
            assertEquals(1, Set.copyOf(answers).size(), "unknown, tampered, expired, revoked, malformed: ONE answer, no oracle: " + answers);
            assertTrue(answers.get(0).startsWith("404 "), answers.get(0));

            // a deleted Investigation: its header goes, the link does not become a 200 - and answers the same
            Files.delete(invDir(c).resolve("header.json"));
            assertEquals(answers.get(0), refusal(pub(c, goneTok)));
            assertEquals(answers.get(0), refusal(pub(c, good)));
            Path goneFile = invDir(c).resolve("embeds").resolve(goneTok.substring(0, goneTok.indexOf('.')) + ".json");
            assertTrue(Files.readString(goneFile).contains("INVESTIGATION_GONE"), "the grant is flagged, not left live: " + Files.readString(goneFile));
            assertFalse(Files.readString(goneFile).contains("tokenSha256"), "and its hash is dropped");
        }
    }

    @Test
    void theKillSwitchTurnsEveryLinkIntoTheSameFourOhFourAndBackAgain(@TempDir Path cfg, @TempDir Path root) throws Exception {
        subjects();
        enable();
        try (Ctx c = open(cfg, root)) {
            investigation(c);
            String token = mint(c, "{}");
            String unknown = refusal(pub(c, "unknown"));
            assertEquals(200, pub(c, token).statusCode());
            System.clearProperty("la.embed.enabled");
            assertEquals(unknown, refusal(pub(c, token)), "disabled looks like unknown");
            enable();
            assertEquals(200, pub(c, token).statusCode(), "nothing was lost by switching off");
        }
    }

    @Test
    void theTokenOpensNothingElseAndOnlyAGetReachesThePublicPath(@TempDir Path cfg, @TempDir Path root) throws Exception {
        subjects();
        enable();
        try (Ctx c = open(cfg, root)) {
            investigation(c);
            settings(root, "all", null);
            String token = mint(c, "{}");
            HttpResponse<String> plain = pub(c, token);
            // a real session on the public path changes NOTHING: the holder is never a Subject
            HttpResponse<String> withSession = send(c, "GET", PUB + token, null, L);
            assertEquals(JSON.readTree(plain.body()).get("data"), JSON.readTree(withSession.body()).get("data"));
            assertFalse(withSession.body().contains("\"permissions\""), "no Subject was attached on the public path");

            for (String path : List.of(INV + "/log", INV + "/working-set", INV + "/dossier", "/inv/investigations", INV + "/embeds"))
                assertEquals(401, status(c, "GET", path, null, "Bearer " + token), "the token as a credential opens " + path + " for no one");
            assertEquals(401, status(c, "POST", "/bi/query", "{}", "Bearer " + token));
            assertEquals(401, status(c, "GET", INV + "/log", null, null));
            assertEquals(404, status(c, "GET", "/public/dashboards/" + token, null, null), "and no share-token route either");
            for (String method : List.of("POST", "PUT", "DELETE", "PATCH"))
                assertEquals(405, status(c, method, PUB + token, "{}", null), method + " never reaches the handler");
            assertEquals(405, status(c, "POST", PUB + token, "{}", L));
        }
    }

    // ── revoke ────────────────────────────────────────────────────────────────────────────────────────

    @Test
    void revokeIsImmediateGatedAndIdempotentAndTheListShowsUse(@TempDir Path cfg, @TempDir Path root) throws Exception {
        subjects();
        enable();
        try (Ctx c = open(cfg, root)) {
            investigation(c);
            String keep = mint(c, "{}");
            String token = mint(c, "{\"label\":\"temp\"}");
            String id = token.substring(0, token.indexOf('.'));
            assertEquals(200, pub(c, token).statusCode());
            assertEquals(200, pub(c, token).statusCode());

            String path = INV + "/embeds/" + id + "/revoke";
            assertEquals(403, status(c, "POST", path, "{}", NOCAP), "needs the verb");
            assertEquals(403, status(c, "POST", path, "{}", A), "needs the lead role");
            assertEquals(404, status(c, "POST", path, "{}", S));
            assertEquals(404, status(c, "POST", "/inv/investigations/inv-a/embeds/AAAAAAAAAAAAAAAA/revoke", "{}", L), "no such link");
            assertEquals(200, pub(c, token).statusCode(), "a refused revoke revoked nothing");

            JsonNode first = ok(c, "POST", path, "{}", L);
            assertEquals("revoked", first.get("status").asText());
            assertEquals("revoked-by-lead", first.get("revokedReason").asText());
            assertEquals(refusal(pub(c, "unknown")), refusal(pub(c, token)), "the very next request is the uniform 404");
            assertEquals(200, pub(c, keep).statusCode(), "the other link is untouched");
            assertEquals("revoked", ok(c, "POST", path, "{}", L).get("status").asText(), "idempotent");

            JsonNode list = ok(c, "GET", INV + "/embeds", null, A);   // any reader of the Investigation
            assertEquals(404, status(c, "GET", INV + "/embeds", null, S));
            JsonNode row = null;
            for (JsonNode g : list.get("grants")) if (g.get("grantId").asText().equals(id)) row = g;
            assertNotNull(row);
            assertEquals("revoked", row.get("status").asText());
            assertEquals(3, row.get("viewCount").asInt(), "three views counted before the revoke: " + row);
            assertNotNull(row.get("lastViewAt"));
        }
    }

    // ── S3: auto-revoke ────────────────────────────────────────────────────────────────────────────────

    @Test
    void aLinkDiesWhenItsMinterLosesTheLeadRoleOrTheMembership(@TempDir Path cfg, @TempDir Path root) throws Exception {
        subjects();
        enable();
        try (Ctx c = open(cfg, root)) {
            investigation(c);
            ok(c, "POST", INV + "/members", "{\"subject\":\"analyst-2\",\"role\":\"lead\"}", L);   // analyst-2 is a second lead
            String byA = ok(c, "POST", INV + "/embeds", "{}", A).get("token").asText();
            String byL = mint(c, "{}");
            assertEquals(200, pub(c, byA).statusCode());

            ok(c, "POST", INV + "/members/revoke", "{\"subject\":\"analyst-2\"}", L);   // analyst-2 leaves
            assertEquals(refusal(pub(c, "unknown")), refusal(pub(c, byA)), "the minter's link stops with their membership");
            assertEquals(200, pub(c, byL).statusCode(), "the other lead's link is not affected");
            JsonNode row = null;
            for (JsonNode g : ok(c, "GET", INV + "/embeds", null, L).get("grants")) if ("analyst-2".equals(g.get("issuedBy").asText())) row = g;
            assertEquals("MINTER_MEMBERSHIP_REVOKED", row.get("revokedReason").asText(), "flagged at once, not lazily: " + row);

            // demotion is caught at the next read: the flag follows
            ok(c, "POST", INV + "/members", "{\"subject\":\"analyst-2\",\"role\":\"lead\"}", L);
            String again = ok(c, "POST", INV + "/embeds", "{}", A).get("token").asText();
            assertEquals(200, pub(c, again).statusCode());
            ok(c, "POST", INV + "/members", "{\"subject\":\"analyst-2\",\"role\":\"analyst\"}", L);   // still a member, no longer a lead
            assertEquals(refusal(pub(c, "unknown")), refusal(pub(c, again)));
            boolean flagged = false;
            for (JsonNode g : ok(c, "GET", INV + "/embeds", null, L).get("grants"))
                if (g.get("grantId").asText().equals(again.substring(0, again.indexOf('.')))) flagged = "MINTER_NOT_LEAD".equals(g.get("revokedReason").asText());
            assertTrue(flagged, "revoked on the spot when the read found the minter is no longer a lead");
        }
    }

    @Test
    void aLinkDiesWhenTheLinkedCaseClosesOrTheSpaceLoosensMasking(@TempDir Path cfg, @TempDir Path root) throws Exception {
        subjects();
        enable();
        Map<String, Map<String, Object>> cases = CaseTeamObjectEngine.arm();
        cases.put("CASE-1", CaseTeamObjectEngine.caseOf("CASE-1", "lead-1", "lead-1", false));
        try (Ctx c = open(cfg, root)) {
            investigation(c);
            ok(c, "PUT", INV + "/case", "{\"caseRef\":\"CASE-1\"}", L);
            String token = mint(c, "{}");
            assertEquals(200, pub(c, token).statusCode(), "an open Case keeps it alive");
            cases.put("CASE-1", CaseTeamObjectEngine.caseOf("CASE-1", "lead-1", "lead-1", true));
            assertEquals(refusal(pub(c, "unknown")), refusal(pub(c, token)), "the Investigation closed with its Case");
            ok(c, "DELETE", INV + "/case", null, L);

            settings(root, "all", null);
            String strict = mint(c, "{}");
            settings(root, null, null);   // typed: looser than all
            assertEquals(refusal(pub(c, "unknown")), refusal(pub(c, strict)), "masking was loosened since the mint");
            String typed = mint(c, "{}");
            settings(root, "all", null);   // tighter: fine
            assertEquals(200, pub(c, typed).statusCode(), "a stricter mode never revokes");
        }
    }

    // ── invariants ────────────────────────────────────────────────────────────────────────────────────

    @Test
    void mintingUsingAndRevokingNeverMoveTheDossierRootNorAnySealedByte(@TempDir Path cfg, @TempDir Path root) throws Exception {
        subjects();
        enable();
        try (Ctx c = open(cfg, root)) {
            investigation(c);
            String rootBefore = ok(c, "GET", INV + "/dossier", null, L).at("/manifest/root").asText();
            assertTrue(rootBefore.startsWith("sha256:"));
            Map<String, byte[]> before = sealed(c);

            String token = mint(c, "{}");
            assertEquals(200, pub(c, token).statusCode());
            assertEquals(200, pub(c, token).statusCode());
            ok(c, "POST", INV + "/embeds/" + token.substring(0, token.indexOf('.')) + "/revoke", "{}", L);
            ok(c, "GET", INV + "/embeds", null, L);

            assertEquals(rootBefore, ok(c, "GET", INV + "/dossier", null, L).at("/manifest/root").asText(), "the Dossier root did not move");
            Map<String, byte[]> after = sealed(c);
            assertEquals(before.keySet(), after.keySet(), "no sealed file was added");
            before.forEach((k, v) -> assertTrue(java.util.Arrays.equals(v, after.get(k)), k + " is byte-identical"));
            assertFalse(Files.readString(invDir(c).resolve("log.jsonl")).contains("embed"), "no embed.* line in the op log");
        }
    }

    private static Map<String, byte[]> sealed(Ctx c) throws Exception {
        Map<String, byte[]> out = new java.util.TreeMap<>();
        Path dir = invDir(c);
        try (Stream<Path> s = Files.walk(dir)) {
            for (Path f : s.filter(Files::isRegularFile).toList()) {
                String rel = dir.relativize(f).toString().replace('\\', '/');
                if (rel.startsWith("embeds/") || rel.equals(".store.lock") || rel.endsWith(".tmp")) continue;   // the sidecar is not sealed evidence
                out.put(rel, Files.readAllBytes(f));
            }
        }
        return out;
    }

    @Test
    void theTokenIsInNoLogLineAndNoAuditEventAndTheAuditCarriesTheGrantAndTheIssuer(@TempDir Path cfg, @TempDir Path root) throws Exception {
        subjects();
        enable();
        ListAppender<ILoggingEvent> logs = new ListAppender<>();
        logs.start();
        ch.qos.logback.classic.Logger rootLogger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        Level was = rootLogger.getLevel();
        rootLogger.setLevel(Level.DEBUG);
        rootLogger.addAppender(logs);
        List<Event> events = new CopyOnWriteArrayList<>();
        Consumer<Event> sub = events::add;
        EventLog.current().addSubscriber(sub);
        try (Ctx c = open(cfg, root)) {
            investigation(c);
            String token = mint(c, "{\"label\":\"secret label\"}");
            String id = token.substring(0, token.indexOf('.'));
            String secret = token.substring(token.indexOf('.') + 1);
            assertEquals(200, pub(c, token).statusCode());
            assertEquals(404, pub(c, token + "x").statusCode());
            assertEquals(404, pub(c, secret).statusCode());
            assertEquals(405, status(c, "POST", PUB + token, "{}", null));
            assertEquals(401, status(c, "GET", INV + "/log", null, "Bearer " + token));
            String t2 = mint(c, "{}");
            ok(c, "POST", INV + "/embeds/" + id + "/revoke", "{}", L);
            assertEquals(404, pub(c, token).statusCode());
            Files.writeString(invDir(c).resolve("header.json"), "{ not json");   // a server fault on the public path: its ERROR line must redact too
            assertEquals(500, pub(c, t2).statusCode());

            String allLogs = String.join("\n", logs.list.stream().map(ILoggingEvent::getFormattedMessage).toList());
            String secret2 = t2.substring(t2.indexOf('.') + 1);
            for (String needle : List.of(secret, id, secret2, t2.substring(0, t2.indexOf('.'))))
                assertFalse(allLogs.contains(needle), "no log line carries a token (" + needle + ")");
            assertTrue(allLogs.contains("/public/investigations/[redacted]"), "the server fault was logged, with the path redacted: " + allLogs);
            String allEvents = events.stream().map(e -> String.valueOf(e.toMap())).reduce("", (x, y) -> x + "\n" + y);
            assertFalse(allEvents.contains(secret), "no audit event carries the token's secret");
            assertFalse(allEvents.contains("secret label"), "nor the free-text label");
            List<Event> minted = events.stream().filter(e -> "LINK_EMBED_MINTED".equals(e.type())).toList();
            assertEquals(2, minted.size(), events.stream().map(Event::type).toList().toString());
            assertTrue(minted.get(0).toMap().toString().contains("lead-1") && minted.get(0).toMap().toString().contains("issuedBy"), minted.get(0).toMap().toString());
            List<Event> viewed = events.stream().filter(e -> "LINK_EMBED_VIEWED".equals(e.type())).toList();
            assertEquals(1, viewed.size());
            assertTrue(viewed.get(0).toMap().toString().contains("embed:" + id), "the actor is embed:<grantId>: " + viewed.get(0).toMap());
            assertTrue(viewed.get(0).toMap().toString().contains("issuedBy"), viewed.get(0).toMap().toString());
            List<Event> denied = events.stream().filter(e -> "LINK_EMBED_DENIED".equals(e.type())).toList();
            assertTrue(denied.size() >= 3, "every refusal is audited: " + denied.size());
            assertTrue(denied.stream().anyMatch(e -> e.toMap().toString().contains("REVOKED")), "the internal reason goes to the audit row only");
            assertTrue(denied.stream().allMatch(e -> e.toMap().toString().contains("tokenHash")), "with a hash prefix of the token");
            assertTrue(events.stream().anyMatch(e -> "LINK_EMBED_REVOKED".equals(e.type())));
        } finally {
            EventLog.current().removeSubscriber(sub);
            rootLogger.detachAppender(logs);
            rootLogger.setLevel(was);
        }
    }

    // ── rate limits ───────────────────────────────────────────────────────────────────────────────────

    @Test
    void eachTokenHasItsOwnBucketSoOneLinkCannotStarveAnother(@TempDir Path cfg, @TempDir Path root) throws Exception {
        subjects();
        enable();
        System.setProperty("control.rateLimit.embed.capacity", "3");
        System.setProperty("control.rateLimit.embed.refillPerSecond", "0.001");
        try (Ctx c = open(cfg, root)) {
            investigation(c);
            String one = mint(c, "{}");
            String two = mint(c, "{}");
            for (int i = 0; i < 3; i++) assertEquals(200, pub(c, one).statusCode(), "request " + i);
            assertEquals(429, pub(c, one).statusCode(), "the fourth request on ONE link is throttled");
            assertEquals(200, pub(c, two).statusCode(), "another link has its own budget");
            assertNotEquals(429, pub(c, "unknown-token-1.AAAA").statusCode());
        }
    }
}
