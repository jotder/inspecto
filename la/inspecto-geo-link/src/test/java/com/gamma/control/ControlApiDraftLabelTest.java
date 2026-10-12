package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.audit.Event;
import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.event.EventLog;
import com.gamma.la.core.DraftLifecycle;
import com.gamma.la.core.DraftStore;
import com.gamma.la.core.LinkEventTypes;
import com.gamma.pipeline.ComponentStore;
import com.gamma.pipeline.ViewDefinition;
import com.gamma.pipeline.ViewStore;
import com.gamma.service.CollectorService;
import com.gamma.spi.auth.AccessDeciders;
import com.gamma.spi.auth.Authenticators;
import com.gamma.spi.auth.Subject;
import org.junit.jupiter.api.AfterEach;
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
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An optional human {@code label} on a Draft (operator 2026-10-12), over real HTTP with an ARMED Authenticator: it is stored in
 * the Draft header, returned by fork / list / read, survives hibernate, is refused when blank or too long, is audited by
 * LENGTH only, and never reaches the main log or the Dossier on promote (a label is the analyst's own free text, unmasked).
 */
class ControlApiDraftLabelTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String CREATE = "{\"id\":\"inv-a\",\"purpose\":\"Fraud referral FR-9\",\"dataset\":\"calls_ds\","
            + "\"sourceCol\":\"caller\",\"targetCol\":\"callee\",\"linkKindCol\":\"channel\"}";
    private static final String INV = "/inv/investigations/inv-a";
    private static final String DRAFTS = "/inv/investigations/inv-a/drafts";
    private static final String L = "Bearer lead", A = "Bearer analyst";
    private static final String LABEL = "  Is erin-005 the hub? (note for Sam Reyes)  ";
    private static final String TRIMMED = "Is erin-005 the hub? (note for Sam Reyes)";
    private final HttpClient client = HttpClient.newHttpClient();

    private static final class Tick extends Clock {
        private Instant now = Instant.parse("2026-10-12T08:00:00Z");
        @Override public java.time.ZoneId getZone() { return ZoneId.of("UTC"); }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
        void advance(Duration d) { now = now.plus(d); }
    }

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
        DraftStore.mover = DraftStore.ATOMIC;
        DraftLifecycle.clock = Clock.systemUTC();
    }

    private static void subjects() {
        Set<String> all = Set.of("canManageIncidents", "canRunLinkGraphAnalysis", "canApproveLinkExpansions",
                "canRevealLinkEntities", "canAuthorAlertRules", "canBuildLinkIndex");
        Authenticators.forTest(ex -> switch (String.valueOf(ex.getRequestHeaders().getFirst("Authorization"))) {
            case L -> Optional.of(new Subject("lead-1", all));
            case A -> Optional.of(new Subject("analyst-2", all));
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
            Files.writeString(writeRoot.resolve("link-analysis.toon"), "masking_mode: none\n");
            new ViewStore(writeRoot.resolve("views")).write(new ViewDefinition("calls_view", "flow-x", List.of(),
                    "SELECT caller, callee, channel FROM (VALUES ('alice-001','bob-002','voice'),('alice-001','carol-003','sms'),"
                            + "('bob-002','dave-004','voice'),('erin-005','frank-006','voice')) AS t(caller,callee,channel)",
                    "2026-10-03T00:00:00Z"));
            new ComponentStore(writeRoot.resolve("registry")).write("dataset", "calls_ds", java.util.Map.of("view", "calls_view"));
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

    private JsonNode data(HttpResponse<String> r, int expected) throws Exception {
        assertEquals(expected, r.statusCode(), r.request().method() + " " + r.request().uri() + " -> " + r.body());
        return JSON.readTree(r.body()).get("data");
    }

    private JsonNode ok(Ctx c, String method, String path, String body, String auth) throws Exception {
        return data(send(c, method, path, body, auth), 200);
    }

    private void team(Ctx c) throws Exception {
        ok(c, "POST", "/inv/investigations", CREATE, L);
        ok(c, "POST", INV + "/ops", "{\"op\":\"seed\",\"ids\":[\"alice-001\"]}", L);
        ok(c, "POST", INV + "/ops", "{\"op\":\"expand\"}", L);
        ok(c, "POST", INV + "/members", "{\"subject\":\"analyst-2\",\"role\":\"analyst\"}", L);
    }

    private static JsonNode item(JsonNode list, String draftId) {
        for (JsonNode n : list.get("items")) if (draftId.equals(n.get("draftId").asText())) return n;
        throw new AssertionError("draft " + draftId + " not listed: " + list);
    }

    @Test
    void aLabelIsTrimmedReturnedListedAndReadBackAndAnOldClientForkStillWorks(@TempDir Path cfg, @TempDir Path root) throws Exception {
        subjects();
        try (Ctx c = open(cfg, root)) {
            team(c);
            JsonNode forked = data(send(c, "POST", DRAFTS, "{\"label\":" + JSON.writeValueAsString(LABEL) + "}", A), 201);
            String da = forked.get("draftId").asText();
            assertEquals(TRIMMED, forked.get("label").asText());
            assertEquals(TRIMMED, item(ok(c, "GET", DRAFTS, null, A), da).get("label").asText());
            assertEquals(TRIMMED, item(ok(c, "GET", DRAFTS, null, L), da).get("label").asText(), "a lead sees it too");
            assertEquals(TRIMMED, ok(c, "GET", DRAFTS + "/" + da, null, A).get("label").asText());
            ok(c, "POST", DRAFTS + "/" + da + "/discard", "{}", A);
            // an old client sends no label: the answer simply has none
            JsonNode plain = data(send(c, "POST", DRAFTS, "{}", A), 201);
            assertFalse(plain.has("label"), plain.toString());
            assertFalse(item(ok(c, "GET", DRAFTS, null, A), plain.get("draftId").asText()).has("label"));
        }
    }

    @Test
    void aBlankTooLongNonStringOrControlCharacterLabelIs422AndWritesNothing(@TempDir Path cfg, @TempDir Path root) throws Exception {
        subjects();
        try (Ctx c = open(cfg, root)) {
            team(c);
            for (String bad : List.of("{\"label\":\"   \"}", "{\"label\":\"\"}", "{\"label\":\"" + "x".repeat(81) + "\"}", "{\"label\":7}",
                    "{\"label\":\"a\\u0007b\"}", "{\"label\":\"a\\nb\"}"))
                assertEquals(422, send(c, "POST", DRAFTS, bad, A).statusCode(), bad);
            assertEquals(0, ok(c, "GET", DRAFTS, null, A).get("items").size(), "a refused fork wrote nothing");
            // the 80-character edge (after trim) is accepted
            assertEquals("y".repeat(80), data(send(c, "POST", DRAFTS, "{\"label\":\" " + "y".repeat(80) + " \"}", A), 201).get("label").asText());
        }
    }

    @Test
    void aLabelSurvivesHibernateAndRehydrate(@TempDir Path cfg, @TempDir Path root) throws Exception {
        subjects();
        Tick tick = new Tick();
        DraftLifecycle.clock = tick;
        try (Ctx c = open(cfg, root)) {
            team(c);
            String da = data(send(c, "POST", DRAFTS, "{\"label\":\"hub?\"}", A), 201).get("draftId").asText();
            tick.advance(Duration.ofMinutes(61));
            JsonNode asleep = item(ok(c, "GET", DRAFTS, null, A), da);
            assertEquals("hibernated", asleep.get("state").asText());
            assertEquals("hub?", asleep.get("label").asText());
            JsonNode woke = ok(c, "GET", DRAFTS + "/" + da, null, A);
            assertEquals("open", woke.get("state").asText());
            assertEquals("hub?", woke.get("label").asText());
        }
    }

    @Test
    void theForkAuditCarriesTheLabelLengthNeverItsText(@TempDir Path cfg, @TempDir Path root) throws Exception {
        subjects();
        try (Ctx c = open(cfg, root)) {
            team(c);
            List<Event> seen = new CopyOnWriteArrayList<>();
            Consumer<Event> sub = seen::add;
            EventLog.current().addSubscriber(sub);
            try {
                send(c, "POST", DRAFTS, "{\"label\":\"" + TRIMMED + "\"}", A);
            } finally {
                EventLog.current().removeSubscriber(sub);
            }
            Event forked = seen.stream().filter(e -> e.type().equals(LinkEventTypes.LINK_DRAFT_FORKED)).findFirst().orElseThrow();
            assertEquals(String.valueOf(TRIMMED.length()), String.valueOf(forked.attributes().get("labelLength")));
            assertFalse(seen.toString().contains("erin-005") || seen.toString().contains("Sam Reyes"), "no label text in any event");
        }
    }

    /** One run: fork (with or without a label), two Draft ops, promote; returns the normalised main log and the Dossier root. */
    private record Run(List<String> mainLog, String dossierRoot, String mainText) {}

    private Run promoteRun(Path cfg, Path root, String forkBody) throws Exception {
        try (Ctx c = open(cfg, root)) {
            team(c);
            String da = data(send(c, "POST", DRAFTS, forkBody, A), 201).get("draftId").asText();
            ok(c, "POST", DRAFTS + "/" + da + "/ops", "{\"op\":\"seed\",\"ids\":[\"erin-005\"]}", A);
            ok(c, "POST", DRAFTS + "/" + da + "/ops", "{\"op\":\"expand\",\"ids\":[\"erin-005\"]}", A);
            ok(c, "POST", DRAFTS + "/" + da + "/promote", "{\"expectHead\":2}", A);
            String text = Files.readString(c.root().resolve("audit/snapshots/investigations/inv-a/log.jsonl"));
            List<String> lines = text.lines().filter(l -> !l.isBlank())
                    .map(l -> l.replace(da, "DRAFT").replaceAll("\\d{4}-\\d\\d-\\d\\dT[0-9:.]+Z", "TS")).toList();
            // the manifest root hashes log.jsonl, which carries wall-clock timestamps, so two runs never share a root: compare what is
            // timestamp-free instead - the content hashes (entities, links, excluded, scores), every sets/N.json hash and the artefact paths
            JsonNode mf = ok(c, "GET", INV + "/dossier", null, L).get("manifest");
            StringBuilder stable = new StringBuilder(mf.get("content").toString());
            for (JsonNode art : mf.get("artefacts"))
                stable.append(art.get("path").asText()).append(art.get("path").asText().startsWith("sets/") ? art.get("sha256").asText() : "");
            String dossierRoot = stable.toString();
            return new Run(lines, dossierRoot, text);
        }
    }

    @Test
    void aPromotedDraftsMainLogAndDossierRootAreByteIdenticalWithAndWithoutALabel(@TempDir Path cfg1, @TempDir Path root1,
                                                                                   @TempDir Path cfg2, @TempDir Path root2) throws Exception {
        subjects();
        Run plain = promoteRun(cfg1, root1, "{}");
        Run labelled = promoteRun(cfg2, root2, "{\"label\":\"" + TRIMMED + "\"}");
        assertEquals(4, plain.mainLog().size());
        assertFalse(labelled.mainText().contains("Sam Reyes") || labelled.mainText().contains("hub?"), "the label never reaches the main log");
        assertEquals(plain.mainLog(), labelled.mainLog(), "main-log entries (seals included) are identical");
        assertFalse(plain.dossierRoot().isBlank());
        assertEquals(plain.dossierRoot(), labelled.dossierRoot(), "the Dossier content hashes, sets and artefact list are identical");
    }
}
