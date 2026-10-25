package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.audit.Event;
import com.gamma.event.EventLog;
import com.gamma.la.api.DraftAdmission;
import com.gamma.la.api.WorkingSetRoutes;
import com.gamma.la.api.WorkingSetTestSeams;
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
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PERF-P4-3: the Working Set GET is conditional - an ETag built from the store's {@code logToken} (never a read of the
 * log), a bodiless 304 on a matching {@code If-None-Match}. Armed Authenticator throughout (with no Subject the gates are
 * no-ops and nothing is proven). The access gate runs BEFORE the 304 decision: a non-member sending the owner's tag is a 404.
 */
class ControlApiWorkingSetEtagTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String CREATE = "{\"id\":\"inv-a\",\"purpose\":\"Fraud referral FR-9\",\"dataset\":\"calls_ds\","
            + "\"sourceCol\":\"caller\",\"targetCol\":\"callee\",\"linkKindCol\":\"channel\"}";
    private static final String INV = "/inv/investigations/inv-a";
    private static final String DRAFTS = "/inv/investigations/inv-a/drafts";
    private static final String OWNER = "Bearer owner", STRANGER = "Bearer stranger";
    private final HttpClient client = HttpClient.newHttpClient();

    private record Ctx(CollectorService svc, ControlApi api, int port, Path root) implements AutoCloseable {
        public void close() {
            api.close();
            svc.close();
        }
    }

    @AfterEach
    void reset() {
        Authenticators.forTest(null);
        AccessDeciders.forTest(null);
        WorkingSetTestSeams.reset();
        DraftAdmission.setHeavyLimit(Math.max(1, Math.min(4, Runtime.getRuntime().availableProcessors() / 3)));
    }

    private static void subjects() {
        Set<String> all = Set.of("canManageIncidents", "canAuthorAlertRules");
        Authenticators.forTest(ex -> switch (String.valueOf(ex.getRequestHeaders().getFirst("Authorization"))) {
            case OWNER -> Optional.of(new Subject("analyst-1", all));
            case STRANGER -> Optional.of(new Subject("analyst-9", all));   // authenticated, not a member
            default -> Optional.empty();
        });
    }

    private Ctx open(Path configDir, Path writeRoot, String mode) throws Exception {
        Path pipe = PipelineConfigBatchTest.writePipeline(configDir, "");
        System.setProperty("assist.write.root", writeRoot.toString());
        try {
            CollectorService svc = new CollectorService(List.of(pipe), 3600, 1);
            ControlApi api = new ControlApi(svc, 0);
            api.start();
            Files.writeString(writeRoot.resolve("link-analysis.toon"), "masking_mode: " + mode + "\n");
            new ViewStore(writeRoot.resolve("views")).write(new ViewDefinition("calls_view", "flow-x", List.of(),
                    "SELECT caller, callee, channel FROM (VALUES ('a','b','voice'),('a','c','sms'),('b','d','voice'))"
                            + " AS t(caller,callee,channel)", "2026-09-30T00:00:00Z"));
            new ComponentStore(writeRoot.resolve("registry")).write("dataset", "calls_ds", Map.of("view", "calls_view"));
            return new Ctx(svc, api, api.port(), writeRoot);
        } finally {
            System.clearProperty("assist.write.root");
        }
    }

    private HttpResponse<String> send(Ctx c, String method, String path, String body, String auth, String inm) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + c.port + "/api/v1" + path))
                .header("Content-Type", "application/json").header("Authorization", auth);
        if (inm != null) b.header("If-None-Match", inm);
        b = "GET".equals(method) ? b.GET() : b.method(method, BodyPublishers.ofString(body == null ? "" : body));
        return client.send(b.build(), BodyHandlers.ofString());
    }

    private JsonNode ok(Ctx c, String method, String path, String body, String auth) throws Exception {
        HttpResponse<String> r = send(c, method, path, body, auth, null);
        assertTrue(r.statusCode() == 200 || r.statusCode() == 201, method + " " + path + " -> " + r.statusCode() + " " + r.body());
        return JSON.readTree(r.body()).get("data");
    }

    private HttpResponse<String> get(Ctx c, String path, String auth, String inm) throws Exception {
        return send(c, "GET", path, null, auth, inm);
    }

    private static String etag(HttpResponse<String> r) {
        String t = r.headers().firstValue("ETag").orElse(null);
        assertNotNull(t, "a 200 Working Set carries an ETag");
        return t;
    }

    /** inv-a: seed a (step 1), expand (step 2), owned by analyst-1. */
    private void seeded(Ctx c) throws Exception {
        ok(c, "POST", "/inv/investigations", CREATE, OWNER);
        ok(c, "POST", INV + "/ops", "{\"op\":\"seed\",\"ids\":[\"a\"]}", OWNER);
        ok(c, "POST", INV + "/ops", "{\"op\":\"expand\"}", OWNER);
    }

    @Test
    void anUnchangedLogAnswers304WithNoBodyAndNoLogRead(@TempDir Path cfg, @TempDir Path root) throws Exception {
        subjects();
        try (Ctx c = open(cfg, root, "none")) {
            seeded(c);
            for (String of : List.of("entities", "links", "excluded")) {
                String path = INV + "/working-set?of=" + of;
                HttpResponse<String> first = get(c, path, OWNER, null);
                assertEquals(200, first.statusCode());
                String tag = etag(first);
                long reads = com.gamma.la.core.LogReadsProbe.sum();
                HttpResponse<String> second = get(c, path, OWNER, tag);
                assertEquals(304, second.statusCode(), of);
                assertEquals("", second.body(), "a 304 has no body");
                assertEquals(tag, second.headers().firstValue("ETag").orElse(null));
                assertEquals(reads, com.gamma.la.core.LogReadsProbe.sum(), "no committed-log read answered the 304 (" + of + ")");
                // a different variant is a different tag: the entities tag never validates the links body
                assertNotEquals(tag, etag(get(c, INV + "/working-set?of=" + (of.equals("links") ? "entities" : "links"), OWNER, null)));
            }
            String path = INV + "/working-set?of=entities";
            String tag = etag(get(c, path, OWNER, null));
            assertNotEquals(tag, etag(get(c, path + "&limit=1", OWNER, null)), "the page is part of the tag");
            assertNotEquals(tag, etag(get(c, path + "&at=1", OWNER, null)), "the pin is part of the tag");
            assertNotEquals(tag, etag(get(c, path + "&limit=1&offset=1", OWNER, null)), "the offset is part of the tag");
            String paged = etag(get(c, path + "&limit=1", OWNER, null));
            assertNotEquals(paged, etag(get(c, path + "&limit=1&offset=1", OWNER, null)), "a different page of the same size");
            assertEquals(200, get(c, path + "&limit=1&offset=1", OWNER, paged).statusCode(), "another page never answers 304");
            assertEquals(304, get(c, path, OWNER, "\"nope\", " + tag).statusCode(), "a tag list matches any member");
            assertEquals(200, get(c, path, OWNER, "\"nope\"").statusCode(), "a stale tag is a full 200");
        }
    }

    @Test
    void anAppendMovesTheTagAndTheNextGetCarriesTheNewData(@TempDir Path cfg, @TempDir Path root) throws Exception {
        subjects();
        try (Ctx c = open(cfg, root, "none")) {
            seeded(c);
            String path = INV + "/working-set?of=entities";
            HttpResponse<String> before = get(c, path, OWNER, null);
            String t0 = etag(before);
            int total0 = JSON.readTree(before.body()).get("data").get("total").asInt();

            ok(c, "POST", INV + "/ops", "{\"op\":\"hide\",\"ids\":[\"b\"]}", OWNER);
            HttpResponse<String> after = get(c, path, OWNER, t0);
            assertEquals(200, after.statusCode(), "the old tag no longer validates");
            String t1 = etag(after);
            assertNotEquals(t0, t1);
            assertTrue(after.body().contains("\"hidden\":true"), after.body());
            assertEquals(total0, JSON.readTree(after.body()).get("data").get("total").asInt());

            ok(c, "POST", INV + "/undo", "{}", OWNER);          // an undo is a later entry: it moves the tag and the body
            HttpResponse<String> undone = get(c, path, OWNER, t1);
            assertEquals(200, undone.statusCode());
            assertNotEquals(t1, etag(undone));
            assertFalse(undone.body().contains("\"hidden\":true"), undone.body());
        }
    }

    @Test
    void aDraftOwnOpAMainChangeAndAForkEachMoveTheTag(@TempDir Path cfg, @TempDir Path root) throws Exception {
        subjects();
        try (Ctx c = open(cfg, root, "none")) {
            seeded(c);
            String draft = ok(c, "POST", DRAFTS, "{}", OWNER).get("draftId").asText();
            String dpath = DRAFTS + "/" + draft + "/working-set?of=entities";
            String mpath = INV + "/working-set?of=entities";
            HttpResponse<String> d0 = get(c, dpath, OWNER, null);
            String dt0 = etag(d0);
            String mt0 = etag(get(c, mpath, OWNER, null));
            assertNotEquals(dt0, mt0, "a Draft and its main log are different answers");
            assertEquals(304, get(c, dpath, OWNER, dt0).statusCode());

            ok(c, "POST", DRAFTS + "/" + draft + "/ops", "{\"op\":\"hide\",\"ids\":[\"c\"]}", OWNER);   // the Draft's own log
            HttpResponse<String> d1 = get(c, dpath, OWNER, dt0);
            assertEquals(200, d1.statusCode());
            String dt1 = etag(d1);
            assertTrue(d1.body().contains("\"hidden\":true"));

            ok(c, "POST", INV + "/ops", "{\"op\":\"hide\",\"ids\":[\"b\"]}", OWNER);   // the main log the Draft forked from
            HttpResponse<String> d2 = get(c, dpath, OWNER, dt1);
            assertEquals(200, d2.statusCode(), "a main-log change is never answered 304 for the Draft");
            assertNotEquals(dt1, etag(d2));

            // a fork (reorder) is a new Investigation: its own log directory, its own tag, a different body (its id)
            ok(c, "POST", INV + "/reorder", "{\"id\":\"inv-b\",\"order\":[1,2,3]}", OWNER);
            String parentTag = etag(get(c, mpath, OWNER, null));
            HttpResponse<String> forked = get(c, "/inv/investigations/inv-b/working-set?of=entities", OWNER, null);
            assertEquals(200, forked.statusCode(), forked.body());
            assertNotEquals(parentTag, etag(forked));
            assertEquals(200, get(c, "/inv/investigations/inv-b/working-set?of=entities", OWNER, parentTag).statusCode(),
                    "the parent's tag never validates the fork");
        }
    }

    @Test
    void theGateRunsBeforeThe304AndAStrangerNeverLearnsExistence(@TempDir Path cfg, @TempDir Path root) throws Exception {
        subjects();
        try (Ctx c = open(cfg, root, "none")) {
            seeded(c);
            String path = INV + "/working-set?of=entities";
            String tag = etag(get(c, path, OWNER, null));
            assertEquals(304, get(c, path, OWNER, tag).statusCode());
            assertEquals(404, get(c, path, STRANGER, tag).statusCode(), "a non-member sending the owner's tag is a 404, never a 304");
            assertEquals(404, get(c, path, STRANGER, "*").statusCode(), "even If-None-Match: *");
            assertEquals(404, get(c, path, STRANGER, null).statusCode());
            String draft = ok(c, "POST", DRAFTS, "{}", OWNER).get("draftId").asText();
            String dpath = DRAFTS + "/" + draft + "/working-set?of=entities";
            String dtag = etag(get(c, dpath, OWNER, null));
            assertEquals(404, get(c, dpath, STRANGER, dtag).statusCode(), "the Draft route obeys the same order");
            assertEquals(401, send(c, "GET", path, null, "Bearer nobody", tag).statusCode(), "no credential is a 401, not a 304");
        }
    }

    @Test
    void theMaskingProfileIsPartOfTheTagSoAMaskedBodyIsNeverServedForAnotherProfile(@TempDir Path cfg, @TempDir Path root) throws Exception {
        subjects();
        try (Ctx c = open(cfg, root, "none")) {
            seeded(c);
            String path = INV + "/working-set?of=entities";
            HttpResponse<String> raw = get(c, path, OWNER, null);
            String rawTag = etag(raw);
            assertTrue(raw.body().contains("\"entityId\":\"a\""));
            assertEquals(304, get(c, path, OWNER, rawTag).statusCode());

            Files.writeString(root.resolve("link-analysis.toon"), "masking_mode: all\n");   // the same log under a stricter profile
            HttpResponse<String> masked = get(c, path, OWNER, rawTag);
            assertEquals(200, masked.statusCode(), "the unmasked tag must not validate under a stricter profile");
            assertNotEquals(rawTag, etag(masked));
            assertFalse(masked.body().contains("\"entityId\":\"a\""), masked.body());
            String maskedTag = etag(masked);
            assertEquals(304, get(c, path, OWNER, maskedTag).statusCode());

            Files.writeString(root.resolve("link-analysis.toon"), "masking_mode: none\n");   // and back: never the masked body
            HttpResponse<String> back = get(c, path, OWNER, maskedTag);
            assertEquals(200, back.statusCode());
            assertTrue(back.body().contains("\"entityId\":\"a\""));
        }
    }

    @Test
    void coldVersusWarmOnALogOfSeveralHundredOps(@TempDir Path cfg, @TempDir Path root) throws Exception {
        subjects();
        try (Ctx c = open(cfg, root, "none")) {
            seeded(c);
            for (int i = 0; i < 150; i++) {
                ok(c, "POST", INV + "/ops", "{\"op\":\"hide\",\"ids\":[\"b\"]}", OWNER);
                ok(c, "POST", INV + "/ops", "{\"op\":\"hide\",\"ids\":[\"c\"]}", OWNER);
            }
            String path = INV + "/working-set?of=entities";
            long t0 = System.nanoTime();
            HttpResponse<String> cold = get(c, path, OWNER, null);
            long coldMs = (System.nanoTime() - t0) / 1_000_000;
            String tag = etag(cold);
            long reads = com.gamma.la.core.LogReadsProbe.sum();
            t0 = System.nanoTime();
            HttpResponse<String> warm = get(c, path, OWNER, tag);
            long warmMs = (System.nanoTime() - t0) / 1_000_000;
            assertEquals(304, warm.statusCode());
            assertEquals(reads, com.gamma.la.core.LogReadsProbe.sum());
            System.out.println("PERF-P4-3 timing: log of ~300 ops, cold 200 = " + coldMs + " ms (" + cold.body().length()
                    + " bytes), warm 304 = " + warmMs + " ms (0 bytes, 0 log reads)");
        }
    }

    private static String sha256OfFile(Path log) throws Exception {
        return "sha256:" + java.util.HexFormat.of().formatHex(
                java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(log)));
    }

    @Test
    void theBodyKeyIsTheLogsRealSha256OnAMissAndOnAHit(@TempDir Path cfg, @TempDir Path root) throws Exception {
        subjects();
        try (Ctx c = open(cfg, root, "none")) {
            seeded(c);
            String expected = sha256OfFile(root.resolve("audit/snapshots/investigations/inv-a/log.jsonl"));
            JsonNode miss = JSON.readTree(get(c, INV + "/working-set?of=entities", OWNER, null).body()).get("data");
            assertEquals(expected, miss.get("key").asText(), "on a miss");
            long reads = WorkingSetTestSeams.fullReads();
            JsonNode hit = JSON.readTree(get(c, INV + "/working-set?of=entities", OWNER, null).body()).get("data");
            assertTrue(hit.get("cached").asBoolean());
            assertEquals(reads, WorkingSetTestSeams.fullReads(), "a hit within the trust window reads no content");
            assertEquals(expected, hit.get("key").asText(), "on a hit within the trust window");
        }
    }

    @Test
    void anOutOfBandSameSizeSameMtimeEditIsStaleForAtMostTheTrustWindowThenFullyRevalidated(@TempDir Path cfg, @TempDir Path root) throws Exception {
        subjects();
        java.util.concurrent.atomic.AtomicLong now = new java.util.concurrent.atomic.AtomicLong(1_000_000);
        WorkingSetTestSeams.clock(now::get);
        try (Ctx c = open(cfg, root, "none")) {
            seeded(c);
            ok(c, "POST", INV + "/ops", "{\"op\":\"hide\",\"ids\":[\"b\"]}", OWNER);       // step 3: the line the probe rewrites
            ok(c, "POST", INV + "/ops", "{\"op\":\"annotate\",\"ids\":[\"a\"],\"note\":\"tail\"}", OWNER);   // step 4: the last line stays intact
            String path = INV + "/working-set?of=entities";
            HttpResponse<String> first = get(c, path, OWNER, null);
            String tag = etag(first);
            assertTrue(first.body().contains("\"entityId\":\"b\",\"type\":null,\"hop\":1,\"seedId\":\"a\",\"opSeq\":2,\"hidden\":true"), first.body());

            // out-of-band: hide b -> hide c in the middle of the log, SAME size, mtime put back, last line untouched
            Path log = root.resolve("audit/snapshots/investigations/inv-a/log.jsonl");
            java.nio.file.attribute.FileTime mtime = Files.getLastModifiedTime(log);
            String text = Files.readString(log);
            String edited = text.replaceFirst("\"ids\":\\[\"b\"\\]", "\"ids\":[\"c\"]");
            assertNotEquals(text, edited, "the probe found its line");
            assertEquals(text.length(), edited.length());
            Files.writeString(log, edited);
            Files.setLastModifiedTime(log, mtime);
            assertEquals(mtime, Files.getLastModifiedTime(log));

            // inside the window the token cannot tell: stale, and that is the documented bound
            assertEquals(304, get(c, path, OWNER, tag).statusCode());

            long fullBefore = WorkingSetTestSeams.fullReads();
            now.addAndGet(WorkingSetRoutes.WORKING_SET_TRUST_MS + 1);
            HttpResponse<String> revalidated = get(c, path, OWNER, tag);
            assertEquals(200, revalidated.statusCode(), "after the window the old tag no longer validates");
            assertEquals(1, WorkingSetTestSeams.fullReads() - fullBefore, "one full content read for the window");
            assertNotEquals(tag, etag(revalidated), "same token, different content: the tag moved");
            assertTrue(revalidated.body().contains("\"entityId\":\"c\",\"type\":null,\"hop\":1,\"seedId\":\"a\",\"opSeq\":2,\"hidden\":true"), revalidated.body());
            assertEquals(sha256OfFile(log), JSON.readTree(revalidated.body()).get("data").get("key").asText());

            // the rest of the new window trusts the re-seeded entry: no more full reads
            for (int i = 0; i < 3; i++) assertEquals(304, get(c, path, OWNER, etag(revalidated)).statusCode());
            assertEquals(1, WorkingSetTestSeams.fullReads() - fullBefore, "exactly once per window");
            // an unchanged log re-validated in the next window keeps its tag (and costs one read per window)
            now.addAndGet(WorkingSetRoutes.WORKING_SET_TRUST_MS + 1);
            assertEquals(304, get(c, path, OWNER, etag(revalidated)).statusCode());
            assertEquals(2, WorkingSetTestSeams.fullReads() - fullBefore);
            assertEquals(304, get(c, path, OWNER, etag(revalidated)).statusCode());
            assertEquals(2, WorkingSetTestSeams.fullReads() - fullBefore);
        }
    }

    @Test
    void anErrorNeverCarriesAnEtagAndAConditionalRequestStillGetsTheError(@TempDir Path cfg, @TempDir Path root) throws Exception {
        subjects();
        try (Ctx c = open(cfg, root, "none")) {
            seeded(c);
            String good = etag(get(c, INV + "/working-set?of=entities", OWNER, null));
            for (String bad : List.of("&at=99", "&offset=-1", "&limit=x")) {
                String path = INV + "/working-set?of=entities" + bad;
                HttpResponse<String> r = get(c, path, OWNER, null);
                assertEquals(422, r.statusCode(), bad);
                assertTrue(r.headers().firstValue("ETag").isEmpty(), "an error has no ETag: " + bad);
                for (String inm : List.of(good, "*")) {
                    HttpResponse<String> again = get(c, path, OWNER, inm);
                    assertEquals(422, again.statusCode(), bad + " If-None-Match " + inm);
                    assertTrue(again.headers().firstValue("ETag").isEmpty());
                }
            }

            // 429: a Draft's cold relation under a full heavy-job cap
            DraftAdmission.setHeavyLimit(1);
            String draft = ok(c, "POST", DRAFTS, "{}", OWNER).get("draftId").asText();
            ok(c, "POST", INV + "/ops", "{\"op\":\"hide\",\"ids\":[\"b\"]}", OWNER);   // main moved: the Draft's relation is cold
            java.util.concurrent.CountDownLatch started = new java.util.concurrent.CountDownLatch(1);
            java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
            Thread holder = new Thread(() -> {
                try {
                    DraftAdmission.heavy("test holder", () -> {
                        started.countDown();
                        try { release.await(60, java.util.concurrent.TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                        return null;
                    });
                } catch (Exception ignored) { }
            });
            holder.setDaemon(true);
            holder.start();
            assertTrue(started.await(10, java.util.concurrent.TimeUnit.SECONDS));
            try {
                String dpath = DRAFTS + "/" + draft + "/working-set?of=entities";
                for (String inm : new String[] {null, good, "*"}) {
                    HttpResponse<String> r = get(c, dpath, OWNER, inm);
                    assertEquals(429, r.statusCode(), "If-None-Match " + inm + " -> " + r.body());
                    assertTrue(r.headers().firstValue("ETag").isEmpty(), "a 429 has no ETag");
                }
            } finally {
                release.countDown();
            }
        }
    }

    @Test
    void aConditionalReadIsAuditedWithTheSameFieldsAsTheFullReadForMainAndDraft(@TempDir Path cfg, @TempDir Path root) throws Exception {
        subjects();
        java.util.List<Event> seen = new java.util.concurrent.CopyOnWriteArrayList<>();
        java.util.function.Consumer<Event> sub = seen::add;
        EventLog.current().addSubscriber(sub);
        try (Ctx c = open(cfg, root, "none")) {
            seeded(c);
            String draft = ok(c, "POST", DRAFTS, "{}", OWNER).get("draftId").asText();
            for (String path : List.of(INV + "/working-set?of=entities&limit=2", DRAFTS + "/" + draft + "/working-set?of=links")) {
                String tag = etag(get(c, path, OWNER, null));   // miss: warms the entry
                seen.clear();
                assertEquals(200, get(c, path, OWNER, null).statusCode());
                assertEquals(304, get(c, path, OWNER, tag).statusCode());
                List<Event> reads = seen.stream().filter(e -> LinkEventTypes.LINK_INVESTIGATION_WORKING_SET_READ.equals(e.type())).toList();
                assertEquals(2, reads.size(), path);
                Map<String, String> full = new java.util.TreeMap<>(reads.get(0).attributes());
                Map<String, String> cond = new java.util.TreeMap<>(reads.get(1).attributes());
                assertEquals("true", cond.remove("notModified"), "the 304 is marked");
                assertFalse(full.containsKey("notModified"));
                assertEquals(full, cond, "the same audit fields on 200 and 304: " + path);
                for (String k : List.of("investigationId", "relation", "rows", "total", "truncated", "cached", "key"))
                    assertTrue(cond.containsKey(k), k + " on the 304 audit of " + path);
                assertEquals(path.contains("/drafts/"), cond.containsKey("draftId"), "a Draft read says so, even on a 304");
            }
        } finally {
            EventLog.current().removeSubscriber(sub);
        }
    }
}
