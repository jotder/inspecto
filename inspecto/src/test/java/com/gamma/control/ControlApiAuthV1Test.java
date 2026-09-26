package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.event.Event;
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
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Real-HTTP tests for W6's AuthN/AuthZ gate in {@link ControlApi#dispatch}. The core ships no
 * {@link Authenticator}, so these force one via {@link Authenticators#forTest} to stand in for the
 * Standard edition's {@code inspecto-security} module — the only way to exercise the gate from this
 * module's own test classpath (a real {@code META-INF/services} registration here would poison every
 * other test in {@code inspecto} with an active Authenticator). Always restored in {@link #tearDown}.
 */
class ControlApiAuthV1Test {

    private static final ObjectMapper JSON = new ObjectMapper();
    private final HttpClient client = HttpClient.newHttpClient();

    /** {@code Bearer valid} → full grants; {@code Bearer limited} → authenticated but no capabilities;
     *  anything else (absent, garbage) → unauthenticated. */
    private static final Authenticator FAKE = ex -> {
        String auth = ex.getRequestHeaders().getFirst("Authorization");
        if ("Bearer valid".equals(auth)) return Optional.of(new Subject("jdoe", Set.of("canAuthorWorkbench", "canOperateRuns")));
        if ("Bearer limited".equals(auth)) return Optional.of(new Subject("guest", Set.of()));
        if ("Bearer named".equals(auth))
            return Optional.of(new Subject("f3a9-uuid", Set.of(), null, java.util.Map.of(), null, "Ana <b>Lopez</b>"));
        return Optional.empty();
    };

    @AfterEach
    void tearDown() {
        Authenticators.forTest(null);
    }

    private record Ctx(CollectorService svc, ControlApi api, int port) implements AutoCloseable {
        public void close() { api.close(); svc.close(); }
    }

    private Ctx open(Path cfg, Path writeRoot) throws Exception {
        Path pipe = PipelineConfigBatchTest.writePipeline(cfg, "");
        System.setProperty("assist.write.root", writeRoot.toString());
        try {
            CollectorService svc = new CollectorService(List.of(pipe), List.of(), List.of(), 3600L, 1, null);
            ControlApi api = new ControlApi(svc, 0);
            api.start();
            return new Ctx(svc, api, api.port());
        } finally {
            System.clearProperty("assist.write.root");
        }
    }

    private HttpResponse<String> post(int port, String path, String body, String... headers) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1" + path));
        if (headers.length > 0) b.headers(headers);
        return client.send(b.method("POST", body == null ? BodyPublishers.noBody() : BodyPublishers.ofString(body)).build(),
                BodyHandlers.ofString());
    }

    private HttpResponse<String> get(int port, String path, String... headers) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1" + path));
        if (headers.length > 0) b.headers(headers);
        return client.send(b.GET().build(), BodyHandlers.ofString());
    }

    @Test
    void writeRouteWithoutCredentialsIs401(@TempDir Path cfg, @TempDir Path root) throws Exception {
        Authenticators.forTest(FAKE);
        try (Ctx c = open(cfg, root)) {
            HttpResponse<String> r = post(c.port, "/components/widget", "{\"id\":\"w1\",\"vizType\":\"bar\"}");
            assertEquals(401, r.statusCode());
            assertEquals("UNAUTHENTICATED", V1Body.of(r.body()).get("error").get("errorCode").asText());
        }
    }

    @Test
    void writeRouteWithoutCapabilityIs403(@TempDir Path cfg, @TempDir Path root) throws Exception {
        Authenticators.forTest(FAKE);
        try (Ctx c = open(cfg, root)) {
            HttpResponse<String> r = post(c.port, "/components/widget", "{\"id\":\"w1\",\"vizType\":\"bar\"}",
                    "Authorization", "Bearer limited");
            assertEquals(403, r.statusCode());
            assertEquals("PERMISSION_DENIED", V1Body.of(r.body()).get("error").get("errorCode").asText());
        }
    }

    /**
     * AUDIT-REFUSAL-GAP-1: both refusals above must reach the audit trail. They used to unwind past
     * {@code AuditTrail.record} into the error boundary, so the audit log held every SUCCESSFUL call to a
     * route and none of the denied ones — the inverse of what an investigator needs.
     *
     * <p>⚠ Asserting the 401 and the 403 <em>separately</em> is deliberate: they are thrown from two
     * different places ({@code authenticate} before the handler, {@code requireCapability} inside it) and
     * are caught by two different guards, so one test passing would not prove the other path.
     */
    @Test
    void anUnauthenticatedRefusalIsAudited(@TempDir Path cfg, @TempDir Path root) throws Exception {
        Authenticators.forTest(FAKE);
        try (Ctx c = open(cfg, root)) {
            assertEquals(401, post(c.port, "/components/widget", "{\"id\":\"w1\",\"vizType\":\"bar\"}").statusCode());
            assertTrue(deniedWithStatus(c, "/components/widget", 401),
                    "a 401 on a matched route is recorded as ACCESS_DENIED");
            // Negative control for step 4a: a 401 never reached a capability check, so the row must NOT
            // claim one. Absence means "not checked" — the assertion above is only meaningful if this holds.
            assertFalse(deniedEvent(c, "/components/widget", 401).path("attributes").has("capability"),
                    "an authentication refusal carries no capability — no check ran");
        }
    }

    @Test
    void aCapabilityRefusalIsAudited(@TempDir Path cfg, @TempDir Path root) throws Exception {
        Authenticators.forTest(FAKE);
        try (Ctx c = open(cfg, root)) {
            assertEquals(403, post(c.port, "/components/widget", "{\"id\":\"w1\",\"vizType\":\"bar\"}",
                    "Authorization", "Bearer limited").statusCode());
            assertTrue(deniedWithStatus(c, "/components/widget", 403),
                    "a capability 403 is recorded as ACCESS_DENIED");
            assertEquals("guest", deniedEvent(c, "/components/widget", 403).path("attributes").path("actor").asText(),
                    "the refused attempt names the identity that made it");
            // Compliance plan step 4a (2026-09-15): the row answers "denied WHAT?". Until then the capability
            // reached only the exception message and the audit log held a 403 with no cause.
            assertEquals("canAuthorWorkbench",
                    deniedEvent(c, "/components/widget", 403).path("attributes").path("capability").asText(),
                    "the refusal names the capability that was missing");
        }
    }

    /**
     * A refused READ is audited too — unlike the 404/405 case, which stays non-GET only because a bare GET
     * there is usually an SPA deep link. Here the path matched a real route, so there is no ambiguity.
     */
    @Test
    void aRefusedReadIsAuditedAsWell(@TempDir Path cfg, @TempDir Path root) throws Exception {
        Authenticators.forTest(FAKE);
        try (Ctx c = open(cfg, root)) {
            assertEquals(401, get(c.port, "/components/widget").statusCode());
            assertTrue(deniedWithStatus(c, "/components/widget", 401), "a refused GET is recorded");
        }
    }

    private boolean deniedWithStatus(Ctx c, String path, int status) {
        return deniedEvent(c, path, status) != null;
    }

    private JsonNode deniedEvent(Ctx c, String path, int status) {
        // Read the store in process: /events is itself behind the gate under test.
        JsonNode events = JSON.valueToTree(c.svc.events().page(200, null, null).stream().map(Event::toMap).toList());
        for (JsonNode e : events) {
            JsonNode a = e.path("attributes");
            if ("ACCESS_DENIED".equals(e.path("type").asText())
                    && a.path("http_path").asText().contains(path)
                    && status == a.path("http_status").asInt()) return e;
        }
        return null;
    }

    /** The AUDIT-typed sibling of {@link #deniedEvent}: the mutation-trail row for {@code path} that was
     *  sent with {@code status}. Same in-process read of the store, for the same reason. */
    private JsonNode auditEvent(Ctx c, String path, int status) {
        JsonNode events = JSON.valueToTree(c.svc.events().page(200, null, null).stream().map(Event::toMap).toList());
        for (JsonNode e : events) {
            JsonNode a = e.path("attributes");
            if ("AUDIT".equals(e.path("type").asText())
                    && a.path("http_path").asText().contains(path)
                    && status == a.path("http_status").asInt()) return e;
        }
        return null;
    }

    @Test
    void writeRouteWithCapabilitySucceedsAndEnvelopeCarriesPermissions(@TempDir Path cfg, @TempDir Path root) throws Exception {
        Authenticators.forTest(FAKE);
        try (Ctx c = open(cfg, root)) {
            HttpResponse<String> r = post(c.port, "/components/widget", "{\"id\":\"w1\",\"vizType\":\"bar\"}",
                    "Authorization", "Bearer valid");
            assertEquals(200, r.statusCode(), r.body());
            JsonNode permissions = V1Body.envelope(r.body()).get("permissions");
            assertTrue(permissions.isArray());
            assertTrue(streamText(permissions).contains("canAuthorWorkbench"));
            // Compliance plan step 4b (2026-09-15): a write that PASSED a capability gate is marked as
            // privileged by that capability on its AUDIT row — so "every privileged write, by actor, by
            // capability, in the window" is one query over the store, not a join rebuilt by hand.
            JsonNode audited = auditEvent(c, "/components/widget", 200);
            assertNotNull(audited, "the permitted write is on the audit trail");
            assertEquals("jdoe", audited.path("attributes").path("actor").asText());
            assertEquals("canAuthorWorkbench", audited.path("attributes").path("capability").asText(),
                    "the AUDIT row names the capability the write was privileged by");
        }
    }

    /**
     * The other half of step 4b's contract: on Personal no Subject is ever attached, so no capability is
     * ever CHECKED — and the AUDIT row must therefore carry none. Absence has to mean "not checked" and
     * never "checked and passed", or an auditor reading a Personal log would be told writes were gated
     * on an edition that gates nothing. No Authenticator is armed here on purpose.
     */
    @Test
    void anUngatedWriteOnPersonalCarriesNoCapability(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            assertEquals(200, post(c.port, "/components/widget", "{\"id\":\"w1\",\"vizType\":\"bar\"}").statusCode(),
                    "Personal: the route is open, no capability check runs");
            JsonNode audited = auditEvent(c, "/components/widget", 200);
            assertNotNull(audited, "the write is still audited");
            assertFalse(audited.path("attributes").has("capability"),
                    "nothing was checked, so nothing is claimed");
        }
    }

    @Test
    void bootstrapStaysPublicButReflectsAnAuthenticatedCaller(@TempDir Path cfg, @TempDir Path root) throws Exception {
        Authenticators.forTest(FAKE);
        try (Ctx c = open(cfg, root)) {
            // No credentials: bootstrap still 200s, anonymous.
            HttpResponse<String> anon = get(c.port, "/bootstrap");
            assertEquals(200, anon.statusCode());
            JsonNode anonSession = V1Body.of(anon.body()).get("session");
            assertFalse(anonSession.get("authenticated").asBoolean());
            assertEquals(0, anonSession.get("capabilities").size());

            // With a valid token: bootstrap reports the real session, still without requiring one.
            HttpResponse<String> authed = get(c.port, "/bootstrap", "Authorization", "Bearer valid");
            assertEquals(200, authed.statusCode());
            JsonNode session = V1Body.of(authed.body()).get("session");
            assertTrue(session.get("authenticated").asBoolean());
            assertEquals("jdoe", session.get("actor").asText());
            assertTrue(streamText(session.get("capabilities")).contains("canAuthorWorkbench"));
            assertTrue(session.get("displayName").isNull(), "a Subject without a display name serves null");
        }
    }

    /** R2-16: the signed-in subject's display name rides the bootstrap session block verbatim (display-only,
     *  untrusted text — the SPA interpolates it), beside — never instead of — the actor id. */
    @Test
    void bootstrapSessionCarriesTheSubjectsDisplayName(@TempDir Path cfg, @TempDir Path root) throws Exception {
        Authenticators.forTest(FAKE);
        try (Ctx c = open(cfg, root)) {
            JsonNode session = V1Body.of(get(c.port, "/bootstrap", "Authorization", "Bearer named").body()).get("session");
            assertEquals("f3a9-uuid", session.get("actor").asText(), "identity stays the subject id");
            assertEquals("Ana <b>Lopez</b>", session.get("displayName").asText());
            assertTrue(V1Body.of(get(c.port, "/bootstrap").body()).get("session").get("displayName").isNull(),
                    "anonymous: no subject, no name");
        }
    }

    /** Landing-page plan D2 (2026-09-15): the public bootstrap must not hand an unauthenticated caller the
     *  Space roster or the spec catalogue — a sign-in page needs edition, features and the anonymous
     *  session, nothing more. The same call with a bearer serves everything. */
    @Test
    void anonymousBootstrapUnderAnAuthenticatorOmitsTheInventory(@TempDir Path cfg, @TempDir Path root) throws Exception {
        Authenticators.forTest(FAKE);
        try (Ctx c = open(cfg, root)) {
            JsonNode anon = V1Body.of(get(c.port, "/bootstrap").body());
            assertNull(anon.get("spaces"), "the Space roster is inventory, not sign-in context");
            assertNull(anon.get("configSpecs"));
            assertNull(anon.get("enumerations"));
            assertNotNull(anon.get("edition"));
            assertNotNull(anon.get("features"));
            assertNotNull(anon.get("session"));
            // Branding is pre-sign-in CONTEXT, not inventory: the sign-in page cannot read
            // /settings/branding (auth-gated), so this is its only source.
            assertNotNull(anon.get("branding"), "the sign-in page's only branding source");
            assertTrue(anon.get("branding").has("logoDataUrl"));

            JsonNode authed = V1Body.of(get(c.port, "/bootstrap", "Authorization", "Bearer valid").body());
            assertTrue(authed.get("spaces").isArray());
            assertTrue(authed.get("configSpecs").isObject());
            assertTrue(authed.get("enumerations").isObject());
        }
    }

    @Test
    void personalBootstrapServesTheInventoryToEveryone(@TempDir Path cfg, @TempDir Path root) throws Exception {
        // No Authenticator ⇒ Personal: there is no pre-sign-in state, so the full payload stays public.
        try (Ctx c = open(cfg, root)) {
            JsonNode b = V1Body.of(get(c.port, "/bootstrap").body());
            assertTrue(b.get("spaces").isArray());
            assertTrue(b.get("configSpecs").isObject());
            assertTrue(b.get("enumerations").isObject());
        }
    }

    @Test
    void healthStaysOpenWithNoAuthenticatorInvolved(@TempDir Path cfg, @TempDir Path root) throws Exception {
        Authenticators.forTest(FAKE);
        try (Ctx c = open(cfg, root)) {
            assertEquals(200, get(c.port, "/health").statusCode());
            assertEquals(200, get(c.port, "/ready").statusCode());
        }
    }

    @Test
    void personalEditionUnaffectedWhenNoAuthenticatorIsRegistered(@TempDir Path cfg, @TempDir Path root) throws Exception {
        // No Authenticators.forTest call — Authenticators.active() resolves empty, exactly like Personal.
        try (Ctx c = open(cfg, root)) {
            HttpResponse<String> r = post(c.port, "/components/widget", "{\"id\":\"w1\",\"vizType\":\"bar\"}");
            assertEquals(200, r.statusCode(), "no credential required when no Authenticator is present");
            assertNull(V1Body.envelope(r.body()).get("permissions"), "no Subject ⇒ no permissions block");
        }
    }

    @Test
    void singleResourceResponseRefinesPermissionsToTheApplicableSet(@TempDir Path cfg, @TempDir Path root) throws Exception {
        // SEC-7(b): a single-component GET declares {canAuthorWorkbench}; the envelope emits
        // grants ∩ applicable — the session's canOperateRuns is not applicable to a registry component.
        Authenticators.forTest(FAKE);
        try (Ctx c = open(cfg, root)) {
            new com.gamma.pipeline.ComponentStore(root.resolve("registry"))
                    .write("grammar", "g1", java.util.Map.of("delimiter", ","));

            JsonNode one = JSON.readTree(get(c.port, "/components/grammar/g1",
                    "Authorization", "Bearer valid").body());
            assertEquals(List.of("canAuthorWorkbench"), streamText(one.get("permissions")),
                    "per-resource ∩ resource-state, not the session-wide set");

            // the list response declares nothing → session-wide array unchanged
            JsonNode list = JSON.readTree(get(c.port, "/components/grammar",
                    "Authorization", "Bearer valid").body());
            assertEquals(2, list.get("permissions").size(), "lists keep the session-wide grants");
        }
    }

    @Test
    void xActorHeaderIsRejectedOnProfessional(@TempDir Path cfg, @TempDir Path root) throws Exception {
        // SEC-7(a): with an Authenticator active (Professional), a client-supplied X-Actor is a spoof → 403,
        // even alongside valid credentials — the actor must come from the authenticated Subject.
        Authenticators.forTest(FAKE);
        try (Ctx c = open(cfg, root)) {
            HttpResponse<String> r = get(c.port, "/components/grammar",
                    "Authorization", "Bearer valid", "X-Actor", "mallory");
            assertEquals(403, r.statusCode(), r.body());
            assertEquals("PERMISSION_DENIED", V1Body.of(r.body()).get("error").get("errorCode").asText());
        }
    }

    @Test
    void xActorHeaderStillHonouredOnPersonal(@TempDir Path cfg, @TempDir Path root) throws Exception {
        // No Authenticator (Personal): X-Actor stays the historic actor mechanism — the reject never fires.
        try (Ctx c = open(cfg, root)) {
            HttpResponse<String> r = get(c.port, "/components/grammar", "X-Actor", "alice");
            assertEquals(200, r.statusCode(), r.body());
        }
    }

    private static List<String> streamText(JsonNode array) {
        List<String> out = new java.util.ArrayList<>();
        array.forEach(n -> out.add(n.asText()));
        return out;
    }
}
