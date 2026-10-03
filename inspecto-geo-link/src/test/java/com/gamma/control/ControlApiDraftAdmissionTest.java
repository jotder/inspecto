package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.event.Event;
import com.gamma.event.EventLog;
import com.gamma.la.api.DraftAdmission;
import com.gamma.la.core.DraftLifecycle;
import com.gamma.la.core.DraftStore;
import com.gamma.la.core.InvestigationEvaluator;
import com.gamma.la.core.LinkEventTypes;
import com.gamma.pipeline.ComponentStore;
import com.gamma.pipeline.ViewDefinition;
import com.gamma.pipeline.ViewStore;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * D7-6 - Draft admission (the open-Draft cap, the heavy-job cap), hibernate / rehydrate and expiry on an injectable clock (no sleeps),
 * the crash sweep and the header-free listing, over real HTTP with an ARMED Authenticator (as {@link ControlApiDraftsTest}).
 */
class ControlApiDraftAdmissionTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String CREATE = "{\"id\":\"inv-a\",\"purpose\":\"Fraud referral FR-9\",\"dataset\":\"calls_ds\","
            + "\"sourceCol\":\"caller\",\"targetCol\":\"callee\",\"linkKindCol\":\"channel\"}";
    private static final String INV = "/inv/investigations/inv-a";
    private static final String DRAFTS = "/inv/investigations/inv-a/drafts";
    private static final String L = "Bearer lead", A = "Bearer analyst", A2 = "Bearer analyst2";
    private static final String SEED_E = "{\"op\":\"seed\",\"ids\":[\"erin-005\"]}";
    private final HttpClient client = HttpClient.newHttpClient();

    /** A clock a test moves by hand. */
    private static final class Tick extends Clock {
        volatile Instant now = Instant.parse("2026-10-03T08:00:00Z");

        @Override public ZoneId getZone() { return ZoneId.of("UTC"); }
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

    private final Tick tick = new Tick();

    @AfterEach
    void reset() {
        CaseTeamObjectEngine.CASES = null;
        Authenticators.forTest(null);
        AccessDeciders.forTest(null);
        DraftStore.mover = DraftStore.ATOMIC;
        DraftLifecycle.clock = Clock.systemUTC();
        DraftLifecycle.maxOpenDrafts = 50;
        DraftLifecycle.hibernateAfter = Duration.ofHours(1);
        DraftLifecycle.expireAfter = Duration.ofDays(30);
        DraftAdmission.setHeavyLimit(Math.max(1, Math.min(4, Runtime.getRuntime().availableProcessors() / 3)));
    }

    private static void subjects() {
        Set<String> all = Set.of("canManageIncidents", "canRunLinkGraphAnalysis", "canApproveLinkExpansions",
                "canRevealLinkEntities", "canAuthorAlertRules", "canBuildLinkIndex");
        Authenticators.forTest(ex -> switch (String.valueOf(ex.getRequestHeaders().getFirst("Authorization"))) {
            case L -> Optional.of(new Subject("lead-1", all));
            case A -> Optional.of(new Subject("analyst-2", all));
            case A2 -> Optional.of(new Subject("analyst-6", all));
            default -> Optional.empty();
        });
    }

    private Ctx open(Path configDir, Path writeRoot, String settings) throws Exception {
        Path pipe = PipelineConfigBatchTest.writePipeline(configDir, "");
        System.setProperty("assist.write.root", writeRoot.toString());
        try {
            CollectorService svc = new CollectorService(List.of(pipe), 3600, 1);
            ControlApi api = new ControlApi(svc, 0);
            api.start();
            Files.writeString(writeRoot.resolve("link-analysis.toon"), settings);
            new ViewStore(writeRoot.resolve("views")).write(new ViewDefinition("calls_view", "flow-x", List.of(),
                    "SELECT caller, callee, channel FROM (VALUES ('alice-001','bob-002','voice'),('alice-001','carol-003','sms'),"
                            + "('bob-002','dave-004','voice'),('erin-005','frank-006','voice')) AS t(caller,callee,channel)",
                    "2026-10-03T00:00:00Z"));
            new ComponentStore(writeRoot.resolve("registry")).write("dataset", "calls_ds", Map.of("view", "calls_view"));
            return new Ctx(svc, api, api.port(), writeRoot);
        } finally {
            System.clearProperty("assist.write.root");
        }
    }

    private Ctx open(Path configDir, Path writeRoot) throws Exception {
        return open(configDir, writeRoot, "masking_mode: none\n");
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

    private static String message(HttpResponse<String> r) throws Exception {
        return JSON.readTree(r.body()).path("error").path("message").asText();
    }

    private static String grantBody(String subject, String role) {
        return "{\"subject\":\"" + subject + "\",\"role\":\"" + role + "\"}";
    }

    /** inv-a: seed alice-001 (step 1), expand (step 2); analyst-2 and analyst-6 are analysts. */
    private void team(Ctx c) throws Exception {
        ok(c, "POST", "/inv/investigations", CREATE, L);
        ok(c, "POST", INV + "/ops", "{\"op\":\"seed\",\"ids\":[\"alice-001\"]}", L);
        ok(c, "POST", INV + "/ops", "{\"op\":\"expand\"}", L);
        ok(c, "POST", INV + "/members", grantBody("analyst-2", "analyst"), L);
        ok(c, "POST", INV + "/members", grantBody("analyst-6", "analyst"), L);
    }

    private String fork(Ctx c, String who) throws Exception {
        return data(send(c, "POST", DRAFTS, "{}", who), 201).get("draftId").asText();
    }

    private static String d(String draftId, String tail) {
        return DRAFTS + "/" + draftId + tail;
    }

    private Path invDir(Ctx c) {
        return c.root().resolve("audit/snapshots/investigations/inv-a");
    }

    private Path draftDir(Ctx c, String id) {
        return invDir(c).resolve("drafts").resolve(id);
    }

    private static JsonNode item(JsonNode list, String draftId) {
        for (JsonNode n : list.get("items")) if (draftId.equals(n.get("draftId").asText())) return n;
        return null;
    }

    // -- the open-Draft cap (D21) -------------------------------------------------------------------------------

    @Test
    void theNextForkBeyondTheCapIs409WithAWayOutAndADiscardFreesASeat(@TempDir Path cfg, @TempDir Path root) throws Exception {
        subjects();
        DraftLifecycle.maxOpenDrafts = 2;
        try (Ctx c = open(cfg, root)) {
            team(c);
            String da = fork(c, A);
            fork(c, A2);
            HttpResponse<String> refused = send(c, "POST", DRAFTS, "{}", L);
            assertEquals(409, refused.statusCode(), refused.body());
            assertTrue(message(refused).contains("2 open Drafts") && message(refused).contains("discard or promote one"), message(refused));
            try (Stream<Path> s = Files.list(invDir(c).resolve("drafts"))) {
                assertEquals(2, s.filter(p -> DraftStore.DRAFT_ID.matcher(p.getFileName().toString()).matches()).count(), "the refusal created nothing");
            }
            ok(c, "POST", d(da, "/discard"), "{}", A);
            assertEquals(201, send(c, "POST", DRAFTS, "{}", L).statusCode(), "a discarded Draft no longer holds a seat");
        }
    }

    @Test
    void theCapCountsAHibernatedDraftButNotAnExpiredOne(@TempDir Path cfg, @TempDir Path root) throws Exception {
        subjects();
        DraftLifecycle.clock = tick;
        DraftLifecycle.maxOpenDrafts = 1;
        try (Ctx c = open(cfg, root)) {
            team(c);
            fork(c, A);
            tick.advance(Duration.ofHours(2));
            assertEquals(409, send(c, "POST", DRAFTS, "{}", L).statusCode(), "hibernated is still open");
            tick.advance(Duration.ofDays(31));
            assertEquals(201, send(c, "POST", DRAFTS, "{}", L).statusCode(), "the idle Draft expired and freed its seat");
        }
    }

    // -- the heavy-job cap (D7-Q6) ------------------------------------------------------------------------------

    /** Hold every heavy permit (the limit is 1) on another thread until released. */
    private static CountDownLatch holdHeavy(CountDownLatch started) {
        CountDownLatch release = new CountDownLatch(1);
        Thread t = new Thread(() -> {
            try {
                DraftAdmission.heavy("test holder", () -> {
                    started.countDown();
                    try {
                        release.await(60, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    return null;
                });
            } catch (Exception ignored) {
                // the holder only occupies the permit
            }
        });
        t.setDaemon(true);
        t.start();
        return release;
    }

    @Test
    void aFullHeavyCapAnswers429WithASentenceAndNeverQueues(@TempDir Path cfg, @TempDir Path root) throws Exception {
        subjects();
        DraftAdmission.setHeavyLimit(1);
        try (Ctx c = open(cfg, root)) {
            team(c);
            String da = fork(c, A);
            ok(c, "POST", INV + "/ops", SEED_E, L);   // the main log moves, so a rebase has work
            CountDownLatch started = new CountDownLatch(1);
            CountDownLatch release = holdHeavy(started);
            assertTrue(started.await(10, TimeUnit.SECONDS));
            long t0 = System.nanoTime();
            for (HttpResponse<String> r : List.of(
                    send(c, "GET", d(da, "/conflicts"), null, A),
                    send(c, "POST", d(da, "/rebase"), "{}", A),
                    send(c, "GET", d(da, "/working-set"), null, A),            // a cold relation build
                    send(c, "POST", d(da, "/ops"), "{\"op\":\"expand\"}", A))) {
                assertEquals(429, r.statusCode(), r.request().uri() + " " + r.body());
                assertEquals("RATE_LIMITED", JSON.readTree(r.body()).path("error").path("errorCode").asText());
                assertTrue(message(r).contains("1 heavy Draft jobs are already running") && message(r).contains("retry"), message(r));
            }
            assertTrue(Duration.ofNanos(System.nanoTime() - t0).toSeconds() < 20, "refused at once, not queued behind the holder");
            assertEquals(0, ok(c, "GET", d(da, ""), null, A).get("steps").asInt(), "the refused expand appended nothing");
            release.countDown();
            for (int i = 0; i < 100 && send(c, "GET", d(da, "/conflicts"), null, A).statusCode() == 429; i++) Thread.sleep(20);
            assertEquals(200, send(c, "GET", d(da, "/conflicts"), null, A).statusCode(), "the permit came back");
            assertEquals(200, send(c, "GET", d(da, "/working-set"), null, A).statusCode());
        }
    }

    @Test
    void theDefaultHeavyLimitIsMinFourAndAThirdOfTheCores() {
        assertEquals(Math.max(1, Math.min(4, Runtime.getRuntime().availableProcessors() / 3)), DraftAdmission.heavyLimit());
    }

    // -- hibernate / rehydrate --------------------------------------------------------------------------------------

    @Test
    void anIdleDraftHibernatesAndRehydratesToTheIdenticalState(@TempDir Path cfg, @TempDir Path root) throws Exception {
        subjects();
        DraftLifecycle.clock = tick;
        try (Ctx c = open(cfg, root)) {
            team(c);
            String da = fork(c, A);
            ok(c, "POST", d(da, "/ops"), SEED_E, A);
            JsonNode before = ok(c, "GET", d(da, "/replay"), null, A);
            assertTrue(before.get("equivalent").asBoolean());
            long f0 = InvestigationEvaluator.foldCount();
            ok(c, "POST", d(da, "/ops"), "{\"op\":\"seed\",\"ids\":[\"dave-004\"]}", A);
            assertEquals(f0, InvestigationEvaluator.foldCount(), "warm: a checkpointed append folds nothing");
            JsonNode warm = ok(c, "GET", d(da, "/replay"), null, A);   // the audit folds (it never trusts a cache)
            String warmSetHash = warm.get("workingSet").get("hash").asText();

            // 59 minutes idle: still open; 61: hibernated (surfaced in the list AND the describe, and a marker file says so)
            tick.advance(Duration.ofMinutes(59));
            assertEquals("open", item(ok(c, "GET", DRAFTS, null, A), da).get("state").asText());
            tick.advance(Duration.ofMinutes(2));
            assertEquals("hibernated", item(ok(c, "GET", DRAFTS, null, A), da).get("state").asText());
            assertTrue(Files.isRegularFile(draftDir(c, da).resolve("hibernated.json")));
            assertTrue(Files.isRegularFile(draftDir(c, da).resolve("log.jsonl")), "the log is never released");
            assertTrue(Files.isDirectory(draftDir(c, da).resolve("sets")), "nor are the sets");
            assertEquals("hibernated", item(ok(c, "GET", DRAFTS, null, L), da).get("state").asText(), "listing is not an access: it does not wake the Draft");

            // the next access rehydrates: ONE cold fold, then the identical hash
            long f1 = InvestigationEvaluator.foldCount();
            JsonNode woke = ok(c, "GET", d(da, ""), null, A);
            assertEquals("open", woke.get("state").asText());
            assertTrue(woke.get("rehydrated").asBoolean());
            assertFalse(Files.exists(draftDir(c, da).resolve("hibernated.json")));
            ok(c, "POST", d(da, "/ops"), "{\"op\":\"seed\",\"ids\":[\"frank-006\"]}", A);
            assertEquals(f1 + 1, InvestigationEvaluator.foldCount(), "exactly one cold fold after hibernation, then the checkpoint is warm again");
            JsonNode after = ok(c, "GET", d(da, "/replay"), null, A);
            assertTrue(after.get("equivalent").asBoolean());
            assertEquals(0, after.get("mismatches").size());
            assertNotEquals(warmSetHash, after.get("workingSet").get("hash").asText(), "the third op changed the set");
        }
    }

    @Test
    void aRehydratedDraftAnswersTheSameWorkingSetAsBeforeItSlept(@TempDir Path cfg, @TempDir Path root) throws Exception {
        subjects();
        DraftLifecycle.clock = tick;
        try (Ctx c = open(cfg, root)) {
            team(c);
            String da = fork(c, A);
            ok(c, "POST", d(da, "/ops"), SEED_E, A);
            String before = ok(c, "GET", d(da, "/working-set"), null, A).toString();
            tick.advance(Duration.ofHours(3));
            ok(c, "GET", DRAFTS, null, A);
            assertTrue(Files.isRegularFile(draftDir(c, da).resolve("hibernated.json")));
            assertEquals(before, ok(c, "GET", d(da, "/working-set"), null, A).toString(), "same relation, byte for byte, after the wake-up");
        }
    }

    @Test
    void anUnauthorisedProbeDoesNotKeepADraftAliveOrWakeIt(@TempDir Path cfg, @TempDir Path root) throws Exception {
        subjects();
        DraftLifecycle.clock = tick;
        try (Ctx c = open(cfg, root)) {
            team(c);
            String da = fork(c, A);
            tick.advance(Duration.ofHours(2));
            ok(c, "GET", DRAFTS, null, A);   // hibernates
            assertEquals(404, send(c, "GET", d(da, ""), null, A2).statusCode(), "a peer analyst: absent");
            assertEquals(403, send(c, "POST", d(da, "/ops"), SEED_E, L).statusCode(), "a lead may not write another's Draft");
            assertTrue(Files.isRegularFile(draftDir(c, da).resolve("hibernated.json")), "neither probe woke it");
        }
    }

    // -- expiry ---------------------------------------------------------------------------------------------------

    private static final String BUILD = "{\"dataset\":\"calls_ds\",\"sourceCol\":\"caller\",\"targetCol\":\"callee\",\"kindCol\":\"channel\"}";

    @Test
    void aDraftIdleForThirtyDaysExpiresLikeADiscardAndReleasesItsPins(@TempDir Path cfg, @TempDir Path root) throws Exception {
        subjects();
        DraftLifecycle.clock = tick;
        try (Ctx c = open(cfg, root, "masking_mode: none\nindex:\n  enabled: true\n")) {
            team(c);
            String id = data(send(c, "POST", "/inv/index/builds", BUILD, L), 202).get("buildId").asText();
            long end = System.nanoTime() + 60_000_000_000L;
            while (!"COMPLETED".equals(ok(c, "GET", "/inv/index/builds/" + id, null, L).get("status").asText())) {
                assertTrue(System.nanoTime() < end, "index build");
                Thread.sleep(10);
            }
            String da = fork(c, A);
            ok(c, "POST", d(da, "/ops"), SEED_E, A);
            assertTrue(pins(c).contains(da));
            tick.advance(Duration.ofDays(10));
            assertFalse(ok(c, "GET", d(da, ""), null, A).get("expiryWarning").asBoolean(), "day 10 of 30: no warning");
            // that read was an access: the idle clock restarted. Idle 24 days from now - inside the 7 day warning window, not yet expired
            tick.advance(Duration.ofDays(24));
            JsonNode warned = item(ok(c, "GET", DRAFTS, null, A), da);
            assertEquals("hibernated", warned.get("state").asText());
            assertTrue(warned.get("expiryWarning").asBoolean(), "from 7 days out the Draft says it is about to expire: " + warned);

            List<Event> seen = new CopyOnWriteArrayList<>();
            Consumer<Event> sub = seen::add;
            EventLog.current().addSubscriber(sub);
            try {
                tick.advance(Duration.ofDays(7));   // 31 days idle
                assertEquals(0, ok(c, "GET", DRAFTS, null, A).get("items").size(), "an expired Draft leaves the default listing");
            } finally {
                EventLog.current().removeSubscriber(sub);
            }
            Path dir = draftDir(c, da);
            assertTrue(Files.isRegularFile(dir.resolve("discarded.json")));
            assertTrue(Files.isRegularFile(dir.resolve("header.json")), "the header stays: the record that it existed");
            assertFalse(Files.exists(dir.resolve("log.jsonl")), "the sealed rows do not outlive the expiry");
            assertFalse(Files.exists(dir.resolve("sets")));
            assertFalse(Files.exists(dir.resolve("hibernated.json")), "closing deletes the idle markers");
            assertFalse(pins(c).contains(da), "the pin was released: " + pins(c));
            JsonNode closed = item(ok(c, "GET", DRAFTS + "?closed=true", null, A), da);
            assertEquals("discarded", closed.get("state").asText());
            assertTrue(closed.get("expired").asBoolean());
            JsonNode draft = ok(c, "GET", d(da, ""), null, A);
            assertEquals("system:expiry", draft.get("discarded").get("discardedBy").asText());
            assertTrue(draft.get("discarded").get("expired").asBoolean());
            HttpResponse<String> gone = send(c, "GET", d(da, "/working-set"), null, A);
            assertEquals(409, gone.statusCode());
            assertTrue(message(gone).contains("expired after 30 days idle"), message(gone));

            List<Event> mine = seen.stream().filter(e -> e.type().startsWith("LINK_DRAFT_")).toList();
            assertEquals(List.of(LinkEventTypes.LINK_DRAFT_EXPIRED), mine.stream().map(Event::type).toList(), seen.toString());
            Map<String, ?> a = mine.get(0).attributes();
            assertEquals("inv-a", a.get("investigationId"));
            assertEquals(da, a.get("draftId"));
            assertEquals("analyst-2", a.get("draftActor"));
            assertEquals("1", String.valueOf(a.get("unpinned")));
            assertFalse(a.toString().contains("erin-005"), "never row content");
            assertEquals(201, send(c, "POST", DRAFTS, "{}", A).statusCode(), "the member's one seat (D17) is free again");
        }
    }

    private static String pins(Ctx c) throws Exception {
        Path idx = c.root().resolve("la-index");
        StringBuilder b = new StringBuilder();
        if (Files.isDirectory(idx))
            try (Stream<Path> s = Files.walk(idx)) {
                for (Path p : s.filter(p -> p.getFileName().toString().equals("pins.json")).toList()) b.append(Files.readString(p));
            }
        return b.toString();
    }

    // -- the crash sweep, over HTTP ---------------------------------------------------------------------------------

    @Test
    void aRebaseCrashedBetweenItsRenamesIsRecoveredOnTheNextAccess(@TempDir Path cfg, @TempDir Path root) throws Exception {
        subjects();
        try (Ctx c = open(cfg, root)) {
            team(c);
            String da = fork(c, A);
            ok(c, "POST", d(da, "/ops"), SEED_E, A);
            // the crash: the Draft was moved aside and the new one never arrived
            Path aside = invDir(c).resolve("drafts").resolve(".old-" + da + "-" + java.util.UUID.randomUUID());
            Files.move(draftDir(c, da), aside);
            assertFalse(Files.exists(draftDir(c, da)));
            JsonNode back = ok(c, "GET", d(da, ""), null, A);   // the access itself runs the sweep first
            assertEquals(da, back.get("draftId").asText());
            assertEquals(1, back.get("steps").asInt(), "the pre-rebase Draft came back with its log");
            assertFalse(Files.exists(aside));
            assertEquals(da, item(ok(c, "GET", DRAFTS, null, A), da).get("draftId").asText());
        }
    }

    // -- the listing reads no header ----------------------------------------------------------------------------------

    @Test
    void listingReadsNoHeaderAndTheIndexIsRebuildableAndSelfHealing(@TempDir Path cfg, @TempDir Path root) throws Exception {
        subjects();
        try (Ctx c = open(cfg, root)) {
            team(c);
            String da = fork(c, A), db = fork(c, A2), dl = fork(c, L);
            Path index = invDir(c).resolve("drafts").resolve("index.json");
            ok(c, "GET", DRAFTS, null, L);   // warms the index if the forks have not
            DraftStore.headerReads.set(0);
            JsonNode first = ok(c, "GET", DRAFTS, null, L);
            ok(c, "GET", DRAFTS, null, A2);
            ok(c, "GET", DRAFTS, null, A);
            assertEquals(0, DraftStore.headerReads.get(), "three listings of three Drafts read zero headers");
            assertEquals(3, first.get("items").size());
            assertTrue(Files.isRegularFile(index));

            // a deleted or corrupt index is rebuilt from the headers - and the answer is the same
            String listed = first.toString();
            Files.delete(index);
            assertEquals(listed, ok(c, "GET", DRAFTS, null, L).toString());
            assertEquals(3, DraftStore.headerReads.get(), "the rebuild read each header once");
            Files.writeString(index, "{ not json");
            DraftStore.headerReads.set(0);
            assertEquals(listed, ok(c, "GET", DRAFTS, null, L).toString());
            assertEquals(3, DraftStore.headerReads.get());

            // a rebase rewrites ONE header: only that entry is re-read, and the listing shows the new base
            ok(c, "POST", INV + "/ops", SEED_E, L);
            ok(c, "POST", d(da, "/ops"), "{\"op\":\"seed\",\"ids\":[\"dave-004\"]}", A);
            ok(c, "POST", d(da, "/rebase"), "{}", A);
            DraftStore.headerReads.set(0);
            JsonNode after = ok(c, "GET", DRAFTS, null, L);
            assertEquals(1, DraftStore.headerReads.get(), "only the rewritten header was re-read");
            assertEquals(3, item(after, da).get("baseStep").asInt());
            assertEquals(2, item(after, db).get("baseStep").asInt());
            assertEquals(dl, item(after, dl).get("draftId").asText());
        }
    }

}
