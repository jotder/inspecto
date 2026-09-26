package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.pipeline.ComponentStore;
import com.gamma.pipeline.ViewDefinition;
import com.gamma.pipeline.ViewStore;
import com.gamma.service.CollectorService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * LA-20 — the Working Set as a log-defined derived relation ({@code GET /inv/investigations/{id}/working-set}) over
 * real HTTP: the relation's shape and provenance columns, its bounds, the cache (a repeat read is served from it, and
 * a stale read is impossible after an op, an undo or a fork), and the D-E7 gate — owner-only below Enterprise, and an
 * Enterprise {@code AccessDecider} DENY that hides it even from its owner while an ALLOW never widens it.
 *
 * <p>Fixture: the LA-10 call graph {@code alice–bob, alice–carol, bob–dave, bob–erin, carol–frank}.
 */
class ControlApiInvestigationWorkingSetTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String ROWS = "('alice','bob','sms'),('alice','bob','sms'),('alice','carol','call'),"
            + "('bob','dave','call'),('bob','erin','sms'),('carol','frank','call')";
    private static final String CREATE =
            "{\"purpose\":\"test\",\"id\":\"case-a\",\"dataset\":\"calls_ds\",\"sourceCol\":\"caller\",\"targetCol\":\"callee\",\"linkKindCol\":\"channel\"}";
    private static final String EXCLUDE_BOB = "{\"op\":\"exclude\",\"ids\":[\"bob\"],\"reason\":\"marketing\"}";
    private final HttpClient client = HttpClient.newHttpClient();

    private record Ctx(CollectorService svc, ControlApi api, int port, Path root) implements AutoCloseable {
        public void close() {
            api.close();
            svc.close();
        }
    }

    private Ctx open(Path configDir, Path writeRoot) throws Exception {
        Path pipe = PipelineConfigBatchTest.writePipeline(configDir, "");
        System.setProperty("assist.write.root", writeRoot.toString());
        try {
            CollectorService svc = new CollectorService(List.of(pipe), 3600, 1);
            ControlApi api = new ControlApi(svc, 0);
            api.start();
            new ViewStore(writeRoot.resolve("views")).write(new ViewDefinition("calls_view", "flow-x", List.of(),
                    "SELECT * FROM (VALUES " + ROWS + ") AS t(caller,callee,channel)", "2026-09-23T00:00:00Z"));
            new ComponentStore(writeRoot.resolve("registry")).write("dataset", "calls_ds", Map.of("view", "calls_view"));
            return new Ctx(svc, api, api.port(), writeRoot);
        } finally {
            System.clearProperty("assist.write.root");
        }
    }

    private HttpResponse<String> send(int port, String method, String path, String body, String auth) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1" + path))
                .header("Content-Type", "application/json");
        if (auth != null) b.header("Authorization", auth);
        b = "GET".equals(method) ? b.GET() : b.method(method, BodyPublishers.ofString(body == null ? "" : body));
        return client.send(b.build(), BodyHandlers.ofString());
    }

    private static JsonNode data(HttpResponse<String> r) throws Exception {
        assertEquals(200, r.statusCode(), r.body());
        return JSON.readTree(r.body()).get("data");
    }

    private JsonNode post(Ctx c, String path, String body) throws Exception {
        return data(send(c.port, "POST", path, body, null));
    }

    private JsonNode ws(Ctx c, String id, String query) throws Exception {
        return data(send(c.port, "GET", "/inv/investigations/" + id + "/working-set" + query, null, null));
    }

    private static Set<String> ids(JsonNode rel) {
        Set<String> out = new TreeSet<>();
        for (JsonNode r : rel.get("rows")) out.add(r.get("entityId").asText());
        return out;
    }

    private static JsonNode row(JsonNode rel, String entityId) {
        for (JsonNode r : rel.get("rows")) if (entityId.equals(r.get("entityId").asText())) return r;
        throw new AssertionError("no row for " + entityId + " in " + rel);
    }

    // ── shape ──────────────────────────────────────────────────────────────────────────────────────────

    @Test
    void theWorkingSetReadsAsRelationsWithProvenanceColumnsAndIsBounded(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            post(c, "/inv/investigations", CREATE);
            JsonNode empty = ws(c, "case-a", "");
            assertEquals(0, empty.get("total").asInt());
            assertEquals(0, empty.at("/head/step").asInt());

            Files.writeString(root.resolve("link-analysis.toon"), "masking_mode: none\n");   // LA-17 step 5: subscriber is a masked Entity Type; masking is not under test here
            post(c, "/inv/investigations/case-a/ops", "{\"op\":\"seed\",\"ids\":[\"alice\"],\"entityType\":\"subscriber\"}");
            post(c, "/inv/investigations/case-a/ops", "{\"op\":\"expand\"}");
            post(c, "/inv/investigations/case-a/ops", "{\"op\":\"expand\"}");
            JsonNode last = post(c, "/inv/investigations/case-a/ops", EXCLUDE_BOB);

            JsonNode ents = ws(c, "case-a", "");
            assertEquals("entities", ents.get("relation").asText());
            assertEquals("[\"entityId\",\"type\",\"hop\",\"seedId\",\"opSeq\",\"hidden\",\"kept\"]", ents.get("columns").toString());
            assertEquals(Set.of("alice", "carol", "dave", "erin", "frank"), ids(ents));
            assertEquals(5, ents.get("total").asInt());
            assertFalse(ents.get("truncated").asBoolean());
            assertEquals(4, ents.at("/head/step").asInt());
            assertEquals(last.at("/workingSet/hash").asText(), ents.at("/head/workingSetHash").asText(),
                    "the relation is the Working Set the log evaluated to at its head");
            JsonNode alice = row(ents, "alice");
            assertEquals(0, alice.get("hop").asInt());
            assertEquals("subscriber", alice.get("type").asText());
            assertEquals(1, alice.get("opSeq").asInt());
            JsonNode dave = row(ents, "dave");
            assertEquals(2, dave.get("hop").asInt());
            assertEquals("alice", dave.get("seedId").asText(), "provenance: dave hangs off the alice seed");
            assertEquals(3, dave.get("opSeq").asInt(), "admitted by the second expand, step 3");

            JsonNode excluded = ws(c, "case-a", "?of=excluded");
            assertEquals(1, excluded.get("total").asInt(), "negative space is part of the relation (§2.7)");
            assertEquals("bob", excluded.at("/rows/0/entityId").asText());
            assertEquals("marketing", excluded.at("/rows/0/reason").asText());
            assertEquals(4, excluded.at("/rows/0/opSeq").asInt());

            JsonNode links = ws(c, "case-a", "?of=links");
            for (JsonNode l : links.get("rows")) {
                assertNotEquals("bob", l.get("source").asText());
                assertNotEquals("bob", l.get("target").asText());
            }
            assertEquals(2, links.get("total").asInt(), "alice–carol and carol–frank survive the exclusion");

            JsonNode page = ws(c, "case-a", "?limit=2&offset=1");
            assertEquals(2, page.get("rows").size());
            assertEquals(5, page.get("total").asInt(), "the TRUE total ships beside a bounded page");
            assertTrue(page.get("truncated").asBoolean());
            assertEquals(Set.of("carol", "dave"), ids(page), "rows are in a stable order");
            JsonNode past = ws(c, "case-a", "?offset=99");
            assertEquals(0, past.get("rows").size());
            assertFalse(past.get("truncated").asBoolean());

            assertEquals(422, send(c.port, "GET", "/inv/investigations/case-a/working-set?of=nodes", null, null).statusCode());
            assertEquals(422, send(c.port, "GET", "/inv/investigations/case-a/working-set?limit=many", null, null).statusCode());
            assertEquals(422, send(c.port, "GET", "/inv/investigations/case-a/working-set?offset=-1", null, null).statusCode());
            assertEquals(404, send(c.port, "GET", "/inv/investigations/nope/working-set", null, null).statusCode());
        }
    }

    // ── cache ──────────────────────────────────────────────────────────────────────────────────────────

    @Test
    void aRepeatReadIsCachedAndNoOpOrUndoCanLeaveItStale(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            post(c, "/inv/investigations", CREATE);
            post(c, "/inv/investigations/case-a/ops", "{\"op\":\"seed\",\"ids\":[\"alice\"]}");
            post(c, "/inv/investigations/case-a/ops", "{\"op\":\"expand\"}");

            JsonNode first = ws(c, "case-a", "");
            assertFalse(first.get("cached").asBoolean(), "the first read evaluates");
            JsonNode second = ws(c, "case-a", "?of=links");
            assertTrue(second.get("cached").asBoolean(), "a second tile over the same head is served from the cache");
            assertEquals(first.get("key").asText(), second.get("key").asText());
            assertEquals(Set.of("alice", "bob", "carol"), ids(first));

            // an OP moves the head: the cached relation must not be served
            post(c, "/inv/investigations/case-a/ops", EXCLUDE_BOB);
            JsonNode afterOp = ws(c, "case-a", "");
            assertFalse(afterOp.get("cached").asBoolean(), "an op invalidates");
            assertNotEquals(first.get("key").asText(), afterOp.get("key").asText());
            assertEquals(Set.of("alice", "carol"), ids(afterOp), "the exclusion is visible on the very next read");
            assertTrue(ws(c, "case-a", "").get("cached").asBoolean());

            // an UNDO moves the head too, and restores exactly the pre-op relation
            post(c, "/inv/investigations/case-a/undo", "");
            JsonNode afterUndo = ws(c, "case-a", "");
            assertFalse(afterUndo.get("cached").asBoolean(), "an undo invalidates");
            assertEquals(Set.of("alice", "bob", "carol"), ids(afterUndo), "the undone exclusion is gone on the next read");
            assertEquals(first.get("rows"), afterUndo.get("rows"));
            assertEquals(first.at("/head/workingSetHash").asText(), afterUndo.at("/head/workingSetHash").asText());
            assertNotEquals(first.get("key").asText(), afterUndo.get("key").asText(),
                    "the key is the LOG, and the log grew — same Working Set, different head");
            assertEquals(0, ws(c, "case-a", "?of=excluded").get("total").asInt());
        }
    }

    @Test
    void aForkIsADifferentRelationAndTheParentsCachedRelationStaysTrue(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            post(c, "/inv/investigations", CREATE);
            for (String s : List.of("{\"op\":\"seed\",\"ids\":[\"alice\"]}", "{\"op\":\"expand\"}", EXCLUDE_BOB,
                    "{\"op\":\"expand\"}"))
                post(c, "/inv/investigations/case-a/ops", s);
            JsonNode parent = ws(c, "case-a", "");
            assertEquals(Set.of("alice", "carol", "frank"), ids(parent));

            post(c, "/inv/investigations/case-a/reorder", "{\"order\":[1,2,4,3],\"id\":\"fork-1\"}");
            JsonNode fork = ws(c, "fork-1", "");
            assertFalse(fork.get("cached").asBoolean(), "a fork never inherits its parent's cached relation");
            assertNotEquals(parent.get("key").asText(), fork.get("key").asText());
            assertEquals(Set.of("alice", "carol", "dave", "erin", "frank"), ids(fork), "the fork's own order");

            JsonNode parentAgain = ws(c, "case-a", "");
            assertTrue(parentAgain.get("cached").asBoolean(), "forking did not move the parent's head");
            assertEquals(parent.get("rows"), parentAgain.get("rows"));
        }
    }

    // ── ?at= — the pinned step a Frozen Working Set Widget reads (LA-21) ─────────────────────────────────

    @Test
    void aPinnedStepReadsTheSameRelationForeverWhileTheHeadMovesOn(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            post(c, "/inv/investigations", CREATE);
            for (String s : List.of("{\"op\":\"seed\",\"ids\":[\"alice\"]}", "{\"op\":\"expand\"}", EXCLUDE_BOB,
                    "{\"op\":\"expand\"}"))
                post(c, "/inv/investigations/case-a/ops", s);
            JsonNode pinned = ws(c, "case-a", "");      // what a Widget saved now pins: head{step 4, hash}
            assertEquals(4, pinned.at("/head/step").asInt());
            assertEquals(Set.of("alice", "carol", "frank"), ids(pinned));

            // the head moves on — and the pinned step is the one an undo reverts, the hardest case for a pin
            post(c, "/inv/investigations/case-a/undo", "");
            post(c, "/inv/investigations/case-a/ops", "{\"op\":\"hide\",\"ids\":[\"carol\"]}");
            JsonNode head = ws(c, "case-a", "");
            assertEquals(6, head.at("/head/step").asInt());
            assertEquals(Set.of("alice", "carol"), ids(head), "the undo removed frank from the head");
            assertNotEquals(pinned.at("/head/workingSetHash").asText(), head.at("/head/workingSetHash").asText());

            JsonNode frozen = ws(c, "case-a", "?at=4");
            assertEquals(4, frozen.at("/head/step").asInt(), "the answer is the relation OF the pinned step");
            assertEquals(pinned.at("/head/workingSetHash").asText(), frozen.at("/head/workingSetHash").asText(),
                    "the pin's hash still matches — the tile can prove it renders what was saved");
            assertEquals(pinned.get("rows"), frozen.get("rows"), "the pinned relation never moves");
            assertEquals(1, ws(c, "case-a", "?at=4&of=excluded").get("total").asInt(), "bob, excluded at step 3");
            assertEquals(0, ws(c, "case-a", "?at=2&of=excluded").get("total").asInt(), "not yet excluded at step 2");
            assertEquals(Set.of("alice", "bob", "carol"), ids(ws(c, "case-a", "?at=2")));
            JsonNode empty = ws(c, "case-a", "?at=0");
            assertEquals(0, empty.get("total").asInt(), "step 0 is the empty Investigation");
            assertEquals(0, empty.at("/head/step").asInt());

            assertTrue(ws(c, "case-a", "?at=4").get("cached").asBoolean(), "a pinned step is cached too");
            assertNotEquals(ws(c, "case-a", "").get("key").asText(), frozen.get("key").asText(),
                    "a pinned step never answers for the head, nor the head for it");
            assertEquals(Set.of("alice", "carol"), ids(ws(c, "case-a", "")));

            for (String bad : List.of("?at=7", "?at=-1", "?at=four"))
                assertEquals(422, send(c.port, "GET", "/inv/investigations/case-a/working-set" + bad, null, null)
                        .statusCode(), bad);
        }
    }

    /** The pinned read is the same gate, run first: a non-owner cannot read a pinned step, nor probe the head with 422s. */
    @Test
    void onlyTheOwnerReadsAPinnedStepAndAPolicyDenyHidesItToo(@TempDir Path cfg, @TempDir Path root) throws Exception {
        AtomicReference<AccessDecider.Decision> verdict = new AtomicReference<>(AccessDecider.Decision.ABSTAIN);
        Authenticators.forTest(ex -> switch (String.valueOf(ex.getRequestHeaders().getFirst("Authorization"))) {
            case "Bearer owner" -> Optional.of(new Subject("analyst-1", Set.of("canManageIncidents")));
            case "Bearer other" -> Optional.of(new Subject("analyst-2", Set.of("canManageIncidents", "canConfigureAccess")));
            default -> Optional.empty();
        });
        AccessDeciders.forTest((ex, subject, action, route, kind, resource) ->
                "investigation".equals(kind) ? verdict.get() : AccessDecider.Decision.ABSTAIN);
        try (Ctx c = open(cfg, root)) {
            assertEquals(200, send(c.port, "POST", "/inv/investigations", CREATE, "Bearer owner").statusCode());
            assertEquals(200, send(c.port, "POST", "/inv/investigations/case-a/ops",
                    "{\"op\":\"seed\",\"ids\":[\"alice\"]}", "Bearer owner").statusCode());
            String pinned = "/inv/investigations/case-a/working-set?at=1";
            assertEquals(200, send(c.port, "GET", pinned, null, "Bearer owner").statusCode());
            assertTrue(data(send(c.port, "GET", pinned, null, "Bearer owner")).get("cached").asBoolean(), "warm");

            HttpResponse<String> other = send(c.port, "GET", pinned, null, "Bearer other");
            assertEquals(404, other.statusCode(), "a shared dashboard's viewer who is not the owner reads absence: "
                    + other.body());
            assertFalse(other.body().contains("alice"), "the cached pinned rows did not leak");
            assertEquals(404, send(c.port, "GET", "/inv/investigations/case-a/working-set?at=99", null, "Bearer other")
                    .statusCode(), "the gate runs before validation — a non-owner cannot learn the head from a 422");

            verdict.set(AccessDecider.Decision.DENY);
            assertEquals(404, send(c.port, "GET", pinned, null, "Bearer owner").statusCode(),
                    "a policy DENY hides the pinned step from its owner as well");
        } finally {
            AccessDeciders.forTest(null);
            Authenticators.forTest(null);
        }
    }

    // ── D-E7 ───────────────────────────────────────────────────────────────────────────────────────────

    /**
     * Professional and below (no {@code AccessDecider}): OWNER-ONLY. The calls Dataset carries no sharing envelope,
     * so every Subject can VIEW it — which is exactly why this proves there is no fallback to Dataset sharing.
     */
    @Test
    void onlyTheOwnerReadsTheRelationAndACachedCopyNeverLeaks(@TempDir Path cfg, @TempDir Path root) throws Exception {
        Authenticators.forTest(ex -> switch (String.valueOf(ex.getRequestHeaders().getFirst("Authorization"))) {
            case "Bearer owner" -> Optional.of(new Subject("analyst-1", Set.of("canManageIncidents")));
            case "Bearer owner-nocaps" -> Optional.of(new Subject("analyst-1", Set.of()));
            case "Bearer other" -> Optional.of(new Subject("analyst-2", Set.of("canManageIncidents", "canConfigureAccess")));
            default -> Optional.empty();
        });
        try (Ctx c = open(cfg, root)) {
            assertEquals(200, send(c.port, "POST", "/inv/investigations", CREATE, "Bearer owner").statusCode());
            assertEquals(200, send(c.port, "POST", "/inv/investigations/case-a/ops",
                    "{\"op\":\"seed\",\"ids\":[\"alice\"]}", "Bearer owner").statusCode());
            String path = "/inv/investigations/case-a/working-set";
            assertEquals(200, send(c.port, "GET", path, null, "Bearer owner").statusCode());
            JsonNode warm = data(send(c.port, "GET", path, null, "Bearer owner"));
            assertTrue(warm.get("cached").asBoolean(), "the relation is now cached");

            HttpResponse<String> other = send(c.port, "GET", path, null, "Bearer other");
            assertEquals(404, other.statusCode(), "a non-owner — even one who can view the Dataset — reads absence: "
                    + other.body());
            assertEquals(send(c.port, "GET", "/inv/investigations/nope/working-set", null, "Bearer other").statusCode(),
                    other.statusCode(), "indistinguishable from an id that does not exist");
            assertFalse(other.body().contains("alice"), "the cached rows did not leak");
            assertEquals(200, send(c.port, "GET", path, null, "Bearer owner-nocaps").statusCode(),
                    "a read-shaped GET takes no capability — the gate is ownership");
        } finally {
            Authenticators.forTest(null);
        }
    }

    /**
     * Enterprise: the edition's {@code AccessDecider} judges the resolved Investigation at the row PEP. A DENY hides it
     * even from its owner; an ALLOW does not widen owner-only (the {@code AccessDecider} contract). The real
     * {@code PolicyEngine} is exercised end to end in {@code inspecto-policy}'s {@code ControlApiInvestigationPolicyTest}.
     */
    @Test
    void anAccessDeciderDenyHidesTheRelationFromItsOwnerAndAnAllowNeverWidensIt(@TempDir Path cfg, @TempDir Path root) throws Exception {
        AtomicReference<AccessDecider.Decision> verdict = new AtomicReference<>(AccessDecider.Decision.ABSTAIN);
        AtomicReference<Map<String, Object>> seen = new AtomicReference<>();
        Authenticators.forTest(ex -> switch (String.valueOf(ex.getRequestHeaders().getFirst("Authorization"))) {
            case "Bearer owner" -> Optional.of(new Subject("analyst-1", Set.of("canManageIncidents")));
            case "Bearer other" -> Optional.of(new Subject("analyst-2", Set.of("canManageIncidents")));
            default -> Optional.empty();
        });
        AccessDeciders.forTest((ex, subject, action, route, kind, resource) -> {
            if (!"investigation".equals(kind)) return AccessDecider.Decision.ABSTAIN;
            seen.set(resource);
            return verdict.get();
        });
        try (Ctx c = open(cfg, root)) {
            assertEquals(200, send(c.port, "POST", "/inv/investigations", CREATE, "Bearer owner").statusCode());
            String path = "/inv/investigations/case-a/working-set";
            assertEquals(200, send(c.port, "GET", path, null, "Bearer owner").statusCode(), "ABSTAIN: owner-only decides");
            assertNotNull(seen.get(), "the row PEP consulted the decider with the resolved Investigation");
            assertEquals("case-a", seen.get().get("id"));
            assertEquals("analyst-1", seen.get().get("owner"), "the PDP sees the owner and the Dataset as resource.*");
            assertEquals("calls_ds", seen.get().get("dataset"));

            verdict.set(AccessDecider.Decision.DENY);
            assertEquals(404, send(c.port, "GET", path, null, "Bearer owner").statusCode(),
                    "a policy DENY hides the relation even from its owner");

            verdict.set(AccessDecider.Decision.ALLOW);
            assertEquals(200, send(c.port, "GET", path, null, "Bearer owner").statusCode());
            assertEquals(404, send(c.port, "GET", path, null, "Bearer other").statusCode(),
                    "a policy ALLOW never widens owner-only");
        } finally {
            AccessDeciders.forTest(null);
            Authenticators.forTest(null);
        }
    }
}
