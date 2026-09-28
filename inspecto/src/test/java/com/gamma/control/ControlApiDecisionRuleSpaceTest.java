package com.gamma.control;

import com.gamma.event.EventType;
import com.gamma.metrics.MetricRegistry;
import com.gamma.service.SpaceId;
import com.gamma.service.SpaceManager;
import com.gamma.signal.Signal;
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
 * Cross-Space consequence slice 1 ({@code docs/superpower/cross-space-consequence-design.md} §6): a Decision
 * Rule's Signal is stamped with the Space whose ledger records it and the person who applied the rule, and
 * {@code /apply} refuses a consequence that names another Space (D1, test N7) with 422 before anything runs.
 *
 * <p>Spaces {@code alpha} (sorts first, so the Space un-prefixed routes bind to) and {@code beta}; every
 * request goes through {@code /spaces/beta/…} with an armed per-Space-roles authenticator.
 */
class ControlApiDecisionRuleSpaceTest {

    private final HttpClient client = HttpClient.newHttpClient();

    private static final Authenticator PER_SPACE_ROLES = ex -> {
        String h = ex.getRequestHeaders().getFirst("Authorization");
        if (h == null || !h.startsWith("Bearer ")) return Optional.empty();
        String role = h.substring(7);
        Roles.Def def = Roles.effective(ex).get(role);
        return Optional.of(new Subject(role, def == null ? Set.of() : def.capabilities()));
    };

    private record Ctx(SpaceManager spaces, ControlApi api, int port) implements AutoCloseable {
        public void close() {
            api.close();
            spaces.close();
            MetricRegistry.global().reset();
        }
    }

    @AfterEach
    void restore() {
        Authenticators.forTest(null);
    }

    private static String rule(String name, String consequences) {
        return "{\"name\":\"" + name + "\",\"targetType\":\"pipeline\",\"target\":\"orders\",\"consequences\":["
                + consequences + "],\"when\":{\"kind\":\"group\",\"op\":\"AND\",\"items\":["
                + "{\"kind\":\"condition\",\"field\":\"cost\",\"operator\":\">\",\"value\":\"5\"}]}}";
    }

    @Test
    void anAppliedRulesSignalCarriesItsSpaceAndThePersonWhoAppliedIt(@TempDir Path root) throws Exception {
        try (Ctx c = open(root)) {
            assertEquals(200, send(c, "POST", "/spaces/beta/decision-rules",
                    rule("announce", "{\"action\":\"emit-signal\",\"params\":{\"type\":\"fraud.alert\"}}"), "op").statusCode());
            HttpResponse<String> r = send(c, "POST", "/spaces/beta/decision-rules/announce/apply", "", "op");
            assertEquals(200, r.statusCode(), r.body());

            Signal s = signals(c, "beta", "fraud.alert").getFirst();
            assertEquals("beta", s.space(), "the Signal names the Space whose ledger records it");
            assertNotNull(s.actor(), "the person who applied the rule is on the Signal");
            assertEquals("user", s.actor().kind());
            assertEquals("op", s.actor().id());
            assertTrue(signals(c, "alpha", "fraud.alert").isEmpty(), "nothing lands on another Space's ledger");
        }
    }

    @Test
    void aConsequenceNamingAnotherSpaceIs422AndNothingRuns(@TempDir Path root) throws Exception {
        try (Ctx c = open(root)) {
            for (String key : List.of("targetSpace", "space")) {
                String name = "cross_" + key.toLowerCase();
                assertEquals(200, send(c, "POST", "/spaces/beta/decision-rules", rule(name,
                        "{\"action\":\"emit-signal\",\"params\":{\"type\":\"first.one\"}},"
                                + "{\"action\":\"emit-signal\",\"params\":{\"type\":\"second.one\",\"" + key + "\":\"alpha\"}}"),
                        "op").statusCode());
                HttpResponse<String> r = send(c, "POST", "/spaces/beta/decision-rules/" + name + "/apply", "", "op");
                assertEquals(422, r.statusCode(), key + ": " + r.body());
                assertTrue(r.body().contains("only Signals cross"), r.body());
            }
            for (String space : List.of("alpha", "beta"))
                for (String type : List.of("first.one", "second.one"))
                    assertTrue(signals(c, space, type).isEmpty(),
                            "no consequence ran in " + space + " (" + type + ")");
        }
    }

    // ── helpers ─────────────────────────────────────────────────────────────────

    private Ctx open(Path root) throws Exception {
        SpaceManager spaces = SpaceManager.discover(root);
        ControlApi api = new ControlApi(spaces, 0);
        api.start();
        Ctx c = new Ctx(spaces, api, api.port());
        for (String s : List.of("alpha", "beta"))
            assertEquals(200, send(c, "POST", "/spaces", "{\"id\":\"" + s + "\"}", null).statusCode());
        assertEquals("alpha", spaces.current().id().value());
        Roles.write(spaces.space(SpaceId.of("beta")).orElseThrow().root().config(), Map.of("op",
                new Roles.Def(Set.of(Roles.CAN_AUTHOR_WORKBENCH, Roles.CAN_OPERATE_RUNS), null)), List.of());
        Authenticators.forTest(PER_SPACE_ROLES);
        return c;
    }

    private static List<Signal> signals(Ctx c, String space, String type) {
        return c.spaces().space(SpaceId.of(space)).orElseThrow().service().eventLog().store().recent(500).stream()
                .filter(e -> EventType.SIGNAL.equals(e.type()))
                .map(Signal::fromEvent)
                .filter(s -> type.equals(s.type()))
                .toList();
    }

    private HttpResponse<String> send(Ctx c, String method, String path, String body, String role) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + c.port + "/api/v1" + path));
        if (role != null) b.header("Authorization", "Bearer " + role);
        b.header("Content-Type", "application/json").method(method, BodyPublishers.ofString(body));
        return client.send(b.build(), BodyHandlers.ofString());
    }
}
