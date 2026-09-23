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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * LA-23 — the <b>Investigation Template</b> over real HTTP: saving an Investigation's effective log as a method
 * (seeds become parameters; D-E8: the analyst's ad-hoc exclusions — and the other extensional judgement ops, hide
 * and keep — do NOT travel), reading it back, and instantiating it into a new Investigation over the same Dataset
 * or a different one with the same column roles; plus every gate.
 *
 * <p>Fixture: {@code calls_ds} is the LA-10 call graph {@code alice–bob, alice–carol, bob–dave, bob–erin,
 * carol–frank} over {@code (caller, callee, channel)}; {@code texts_ds} is a different graph
 * {@code x1–x2, x2–x3, x9–x8} over DIFFERENTLY NAMED columns {@code (src, dst, kind)}.
 */
class ControlApiInvestigationTemplateTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String CALLS = "('alice','bob','sms'),('alice','bob','sms'),('alice','carol','call'),"
            + "('bob','dave','call'),('bob','erin','sms'),('carol','frank','call')";
    private static final String TEXTS = "('x1','x2','sms'),('x2','x3','call'),('x9','x8','sms')";
    private static final String CREATE =
            "{\"id\":\"case-a\",\"dataset\":\"calls_ds\",\"sourceCol\":\"caller\",\"targetCol\":\"callee\",\"linkKindCol\":\"channel\"}";
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
            ViewStore views = new ViewStore(writeRoot.resolve("views"));
            views.write(new ViewDefinition("calls_view", "flow-x", List.of(),
                    "SELECT * FROM (VALUES " + CALLS + ") AS t(caller,callee,channel)", "2026-09-23T00:00:00Z"));
            views.write(new ViewDefinition("texts_view", "flow-x", List.of(),
                    "SELECT * FROM (VALUES " + TEXTS + ") AS t(src,dst,kind)", "2026-09-23T00:00:00Z"));
            ComponentStore registry = new ComponentStore(writeRoot.resolve("registry"));
            registry.write("dataset", "calls_ds", Map.of("view", "calls_view"));
            registry.write("dataset", "texts_ds", Map.of("view", "texts_view"));
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

    private int status(Ctx c, String method, String path, String body) throws Exception {
        return send(c.port, method, path, body, null).statusCode();
    }

    private Set<String> entities(Ctx c, String id) throws Exception {
        Set<String> out = new TreeSet<>();
        for (JsonNode r : data(send(c.port, "GET", "/inv/investigations/" + id + "/working-set", null, null)).get("rows"))
            out.add(r.get("entityId").asText());
        return out;
    }

    private void ops(Ctx c, String id, String... bodies) throws Exception {
        for (String b : bodies) post(c, "/inv/investigations/" + id + "/ops", b);
    }

    // ── D-E8: the method travels, the case does not ────────────────────────────────────────────────────

    @Test
    void aTemplateCarriesTheMethodAndLeavesTheAnalystsJudgementsBehind(@TempDir Path cfg, @TempDir Path root)
            throws Exception {
        try (Ctx c = open(cfg, root)) {
            post(c, "/inv/investigations", CREATE);
            ops(c, "case-a", "{\"op\":\"seed\",\"ids\":[\"alice\"],\"entityType\":\"subscriber\"}", "{\"op\":\"expand\"}",
                    "{\"op\":\"exclude\",\"ids\":[\"bob\"],\"reason\":\"marketing\"}", "{\"op\":\"expand\"}",
                    "{\"op\":\"hide\",\"ids\":[\"carol\"]}", "{\"op\":\"keep\",\"ids\":[\"alice\"]}");
            assertEquals(Set.of("alice", "carol", "frank"), entities(c, "case-a"), "the analyst's graph: bob excluded");

            HttpResponse<String> saved = send(c.port, "POST", "/inv/investigations/case-a/template",
                    "{\"id\":\"tpl-1\",\"title\":\"two-hop ring\"}", null);
            JsonNode tpl = data(saved);
            assertEquals("[{\"name\":\"seed1\",\"entityType\":\"subscriber\",\"step\":1}]", tpl.get("parameters").toString());
            assertEquals(3, tpl.get("ops").size(), "seed, expand, expand — the judgements are not steps of the method");
            assertEquals("seed1", tpl.at("/ops/0/param").asText());
            assertEquals("expand", tpl.at("/ops/1/op").asText());
            assertEquals(2000, tpl.at("/ops/1/limit").asInt());
            assertEquals("[{\"step\":3,\"op\":\"exclude\",\"count\":1},{\"step\":5,\"op\":\"hide\",\"count\":1},"
                    + "{\"step\":6,\"op\":\"keep\",\"count\":1}]", tpl.get("dropped").toString());
            assertEquals("calls_ds", tpl.at("/roles/dataset").asText());
            assertEquals("channel", tpl.at("/roles/linkKindCol").asText());
            String stored = Files.readString(root.resolve("audit/snapshots/investigation-templates/tpl-1.json"));
            for (String caseData : List.of("alice", "bob", "carol", "frank", "marketing"))
                assertFalse(stored.contains(caseData), "the stored method carries no case data: '" + caseData + "'");
            assertEquals(JSON.readTree(stored), JSON.readTree(saved.body()).get("data"), "the response is what was stored");
            assertEquals(tpl, data(send(c.port, "GET", "/inv/investigation-templates/tpl-1", null, null)));

            // Re-bound to the same seed over the same Dataset: bob is BACK — the exclusion was a judgement about
            // case-a's graph and stayed there. (A template that carried it would answer alice, carol, frank.)
            JsonNode inst = post(c, "/inv/investigation-templates/tpl-1/instantiate",
                    "{\"id\":\"case-b\",\"params\":{\"seed1\":[\"alice\"]}}");
            assertEquals(Set.of("alice", "bob", "carol", "dave", "erin", "frank"), entities(c, "case-b"));
            assertEquals(0, inst.at("/workingSet/excluded").asInt(), "no analyst-judgement exclusion set (G-E12)");
            assertEquals(3, inst.get("steps").asInt());
            JsonNode log = data(send(c.port, "GET", "/inv/investigations/case-b/log", null, null));
            assertEquals("tpl-1", log.at("/header/template/id").asText());
            assertEquals("tpl-1", log.at("/entries/0/derivedFrom/template").asText());
            assertEquals(4, log.at("/entries/2/derivedFrom/step").asInt(), "each step names the method step it ran");
            assertEquals("subscriber", log.at("/entries/0/params/entityType").asText());
            assertTrue(data(send(c.port, "POST", "/inv/investigations/case-b/replay", "{}", null))
                    .get("equivalent").asBoolean(), "an instantiated log replays like any other");
            assertEquals(Set.of("alice", "carol", "frank"), entities(c, "case-a"), "the source is untouched");
        }
    }

    @Test
    void aTemplateInstantiatesOverADifferentDatasetWithTheSameColumnRoles(@TempDir Path cfg, @TempDir Path root)
            throws Exception {
        try (Ctx c = open(cfg, root)) {
            post(c, "/inv/investigations", CREATE);
            ops(c, "case-a", "{\"op\":\"seed\",\"ids\":[\"alice\"]}", "{\"op\":\"expand\"}", "{\"op\":\"expand\"}");
            post(c, "/inv/investigations/case-a/template", "{\"id\":\"tpl-1\"}");

            JsonNode inst = post(c, "/inv/investigation-templates/tpl-1/instantiate", "{\"id\":\"texts-1\","
                    + "\"dataset\":\"texts_ds\",\"sourceCol\":\"src\",\"targetCol\":\"dst\",\"linkKindCol\":\"kind\","
                    + "\"params\":{\"seed1\":[\"x1\"]}}");
            assertEquals("texts_ds", inst.at("/header/dataset").asText());
            assertEquals("src", inst.at("/header/sourceCol").asText());
            assertEquals(Set.of("x1", "x2", "x3"), entities(c, "texts-1"), "the same two hops, over the other graph");
            JsonNode log = data(send(c.port, "GET", "/inv/investigations/texts-1/log", null, null));
            assertEquals("texts_ds", log.at("/entries/1/read/dataset").asText(), "every expand read the NEW binding");

            assertEquals(422, status(c, "POST", "/inv/investigation-templates/tpl-1/instantiate",
                    "{\"id\":\"texts-2\",\"dataset\":\"texts_ds\",\"params\":{\"seed1\":[\"x1\"]}}"),
                    "the template's column NAMES do not exist in texts_ds — the roles must be re-bound");
        }
    }

    /** An expand that named its frontier is generalised to the whole Working Set, and the template says so. */
    @Test
    void aNamedExpandIsGeneralisedAndAnUndoneStepIsNotPartOfTheMethod(@TempDir Path cfg, @TempDir Path root)
            throws Exception {
        try (Ctx c = open(cfg, root)) {
            post(c, "/inv/investigations", CREATE);
            ops(c, "case-a", "{\"op\":\"seed\",\"ids\":[\"alice\"]}", "{\"op\":\"expand\",\"ids\":[\"alice\"]}",
                    "{\"op\":\"exclude\",\"ids\":[\"carol\"],\"reason\":\"noise\"}");
            post(c, "/inv/investigations/case-a/undo", "");
            ops(c, "case-a", "{\"op\":\"expand\",\"ids\":[\"bob\"],\"limit\":50}");

            JsonNode tpl = post(c, "/inv/investigations/case-a/template", "{\"id\":\"tpl-1\"}");
            assertEquals(3, tpl.get("ops").size());
            assertEquals(0, tpl.get("dropped").size(), "an undone exclusion was never part of the effective log");
            assertEquals("[{\"step\":2,\"namedFrontier\":1,\"exact\":true},{\"step\":5,\"namedFrontier\":1,"
                    + "\"exact\":false}]", tpl.get("generalised").toString());
            assertEquals(50, tpl.at("/ops/2/limit").asInt());
        }
    }

    // ── gates ──────────────────────────────────────────────────────────────────────────────────────────

    @Test
    void savingAndInstantiatingFailClosed(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            post(c, "/inv/investigations", CREATE);
            assertEquals(422, status(c, "POST", "/inv/investigations/case-a/template", "{\"id\":\"t1\"}"),
                    "no effective seed — nothing to parameterise");
            ops(c, "case-a", "{\"op\":\"seed\",\"ids\":[\"alice\"]}", "{\"op\":\"expand\"}");
            assertEquals(404, status(c, "POST", "/inv/investigations/nope/template", "{}"));
            assertEquals(422, status(c, "POST", "/inv/investigations/case-a/template", "{\"id\":\"../escape\"}"));
            assertEquals(200, status(c, "POST", "/inv/investigations/case-a/template", "{\"id\":\"t1\"}"));
            assertEquals(409, status(c, "POST", "/inv/investigations/case-a/template", "{\"id\":\"t1\"}"),
                    "a saved method is never replaced — a changed one is a new id");

            String inst = "/inv/investigation-templates/t1/instantiate";
            assertEquals(404, status(c, "GET", "/inv/investigation-templates/nope", null));
            assertEquals(404, status(c, "POST", "/inv/investigation-templates/nope/instantiate", "{}"));
            assertEquals(422, status(c, "POST", inst, "{}"), "the seed parameter is required");
            assertEquals(422, status(c, "POST", inst, "{\"params\":{\"seed1\":[]}}"), "…and non-empty");
            assertEquals(422, status(c, "POST", inst, "{\"params\":{\"seed1\":[\"a\"],\"seed9\":[\"b\"]}}"));
            assertEquals(404, status(c, "POST", inst, "{\"dataset\":\"ghost_ds\",\"params\":{\"seed1\":[\"alice\"]}}"));
            assertEquals(422, status(c, "POST", inst, "{\"sourceCol\":\"no_such\",\"params\":{\"seed1\":[\"alice\"]}}"));
            assertEquals(422, status(c, "POST", inst, "{\"id\":\"../x\",\"params\":{\"seed1\":[\"alice\"]}}"));
            assertEquals(409, status(c, "POST", inst, "{\"id\":\"case-a\",\"params\":{\"seed1\":[\"alice\"]}}"),
                    "an existing Investigation is never overwritten");
        }
    }

    /** Owner-only, like the Investigation; writes take {@code canManageIncidents} (tested WITH a Subject, since
     *  with none {@code withCapability} is a no-op). */
    @Test
    void onlyTheOwnerSavesReadsAndInstantiatesAndWritesNeedTheCapability(@TempDir Path cfg, @TempDir Path root)
            throws Exception {
        Authenticators.forTest(ex -> switch (String.valueOf(ex.getRequestHeaders().getFirst("Authorization"))) {
            case "Bearer owner" -> Optional.of(new Subject("analyst-1", Set.of("canManageIncidents")));
            case "Bearer owner-nocaps" -> Optional.of(new Subject("analyst-1", Set.of()));
            case "Bearer other" -> Optional.of(new Subject("analyst-2", Set.of("canManageIncidents")));
            default -> Optional.empty();
        });
        try (Ctx c = open(cfg, root)) {
            assertEquals(200, send(c.port, "POST", "/inv/investigations", CREATE, "Bearer owner").statusCode());
            assertEquals(200, send(c.port, "POST", "/inv/investigations/case-a/ops",
                    "{\"op\":\"seed\",\"ids\":[\"alice\"]}", "Bearer owner").statusCode());
            String save = "/inv/investigations/case-a/template";
            assertEquals(403, send(c.port, "POST", save, "{\"id\":\"t1\"}", "Bearer owner-nocaps").statusCode(),
                    "saving a template writes the Investigation store — canManageIncidents");
            assertEquals(404, send(c.port, "POST", save, "{\"id\":\"t1\"}", "Bearer other").statusCode(),
                    "a non-owner cannot template someone else's Investigation");
            assertEquals(200, send(c.port, "POST", save, "{\"id\":\"t1\"}", "Bearer owner").statusCode());

            String get = "/inv/investigation-templates/t1", inst = get + "/instantiate";
            String body = "{\"id\":\"case-b\",\"params\":{\"seed1\":[\"alice\"]}}";
            assertEquals(200, send(c.port, "GET", get, null, "Bearer owner-nocaps").statusCode(), "a read takes no capability");
            assertEquals(404, send(c.port, "GET", get, null, "Bearer other").statusCode(), "…but is owner-only");
            assertEquals(404, send(c.port, "POST", inst, body, "Bearer other").statusCode());
            assertEquals(403, send(c.port, "POST", inst, body, "Bearer owner-nocaps").statusCode());
            HttpResponse<String> ok = send(c.port, "POST", inst, body, "Bearer owner");
            assertEquals("analyst-1", data(ok).at("/header/owner").asText(), "the instantiating Subject owns the result");
        } finally {
            Authenticators.forTest(null);
        }
    }
}
