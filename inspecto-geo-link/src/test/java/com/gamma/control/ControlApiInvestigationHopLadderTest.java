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
 * LA-13 — the hop ladder and the time model over real HTTP: every rung field of plan §2.4, the {@code window} op,
 * the timezone contract (plan §2.5) with a midnight-crossing slot, and how each downstream reader — replay, the
 * {@code ?at} prefix, the fork, the Dossier and the template — carries the new op.
 *
 * <p><b>Timezone fixture.</b> {@code ts} is a NAIVE timestamp holding São Paulo wall-clock time (UTC−3, no DST in
 * 2026), declared as {@code timeColZone: America/Sao_Paulo}. Windows are read on the Tokyo wall clock (UTC+9), so
 * Tokyo = São Paulo + 12 h. Neither zone is UTC, and neither is the host's, so an implementation that let DuckDB's
 * session (host) zone or UTC stand in for either declaration admits a different set. From seed {@code a}:
 * <pre>
 *   a→b 10:30 SP = 22:30 Tue Tokyo  IN  22:00–04:00      a→e 09:59 SP = 21:59 Tokyo  OUT (before start)
 *   a→c 15:30 SP = 03:30 Wed Tokyo  IN  (after midnight)  a→f 10:00 SP = 22:00 Tokyo  IN  (start inclusive)
 *   a→d 16:00 SP = 04:00 Wed Tokyo  OUT (end exclusive)   a→g 04:00 SP = 16:00 Tokyo  OUT (midday)
 * </pre>
 * <b>Rung fixture</b> around {@code h}: {@code h→i} sms ×3 on three days · {@code h→j} sms ×2 on one day ·
 * {@code h→k} and {@code k→h} transfer (reciprocal) · {@code m→h} voice (inbound) · {@code i→x1}, {@code i→x2} sms on
 * 2026-09-05, so {@code i}'s degree is 3 over the full range and 1 before 2026-09-04.
 */
class ControlApiInvestigationHopLadderTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String ROWS = String.join(",",
            "('a','b','voice','2026-09-01 10:30:00')", "('a','c','voice','2026-09-01 15:30:00')",
            "('a','d','voice','2026-09-01 16:00:00')", "('a','e','voice','2026-09-01 09:59:00')",
            "('a','f','voice','2026-09-01 10:00:00')", "('a','g','voice','2026-09-01 04:00:00')",
            "('h','i','sms','2026-09-01 12:00:00')", "('h','i','sms','2026-09-02 12:00:00')",
            "('h','i','sms','2026-09-03 12:00:00')", "('h','j','sms','2026-09-01 12:00:00')",
            "('h','j','sms','2026-09-01 13:00:00')", "('h','k','transfer','2026-09-01 12:00:00')",
            "('k','h','transfer','2026-09-02 12:00:00')", "('m','h','voice','2026-09-01 12:00:00')",
            "('i','x1','sms','2026-09-05 12:00:00')", "('i','x2','sms','2026-09-05 12:00:00')");
    /** The same events as INSTANTS: São Paulo wall clock + 3 h, written with an explicit +00 offset. */
    private static final String TZ_VIEW = "SELECT caller, callee, channel, "
            + "CAST(CAST(ts AS TIMESTAMP) + INTERVAL 3 HOUR AS VARCHAR) || '+00' AS raw FROM (VALUES " + ROWS
            + ") AS t(caller,callee,channel,ts)";
    private static final String SLOT = "{\"slot\":{\"start\":\"22:00\",\"end\":\"04:00\"},\"timezone\":\"Asia/Tokyo\"}";
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
                    "SELECT caller, callee, channel, CAST(ts AS TIMESTAMP) AS ts, ts AS ts_text FROM (VALUES " + ROWS
                            + ") AS t(caller,callee,channel,ts)", "2026-09-23T00:00:00Z"));
            views.write(new ViewDefinition("calls_tz_view", "flow-x", List.of(),
                    "SELECT caller, callee, channel, CAST(raw AS TIMESTAMPTZ) AS ts FROM (" + TZ_VIEW + ") v",
                    "2026-09-23T00:00:00Z"));
            ComponentStore reg = new ComponentStore(writeRoot.resolve("registry"));
            reg.write("dataset", "calls_ds", Map.of("view", "calls_view"));
            reg.write("dataset", "calls_tz_ds", Map.of("view", "calls_tz_view"));
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

    private HttpResponse<String> post(Ctx c, String path, String body) throws Exception {
        return send(c.port, "POST", path, body, null);
    }

    private static JsonNode data(HttpResponse<String> r) throws Exception {
        assertEquals(200, r.statusCode(), r.body());
        return JSON.readTree(r.body()).get("data");
    }

    private void create(Ctx c, String id, String extra) throws Exception {
        data(post(c, "/inv/investigations", "{\"id\":\"" + id + "\",\"dataset\":\"calls_ds\",\"sourceCol\":\"caller\","
                + "\"targetCol\":\"callee\",\"linkKindCol\":\"channel\"" + extra + "}"));
    }

    /** A timed Investigation over the São Paulo fixture, seeded with {@code seed}. */
    private void timed(Ctx c, String id, String seed) throws Exception {
        create(c, id, ",\"timeCol\":\"ts\",\"timeColZone\":\"America/Sao_Paulo\"");
        op(c, id, "{\"op\":\"seed\",\"ids\":[\"" + seed + "\"]}");
    }

    private JsonNode op(Ctx c, String id, String body) throws Exception {
        return data(post(c, "/inv/investigations/" + id + "/ops", body));
    }

    private int opStatus(Ctx c, String id, String body) throws Exception {
        return post(c, "/inv/investigations/" + id + "/ops", body).statusCode();
    }

    private static Set<String> admitted(JsonNode step) {
        Set<String> out = new TreeSet<>();
        for (JsonNode n : step.at("/delta/admitted")) out.add(n.asText());
        return out;
    }

    private static Set<String> ids(JsonNode workingSet) {
        Set<String> out = new TreeSet<>();
        for (JsonNode e : workingSet.get("entities")) out.add(e.get("id").asText());
        return out;
    }

    // ── the timezone contract ──────────────────────────────────────────────────────────────────────────

    /** The midnight-crossing slot, both boundaries, on the declared zones — never UTC, never the host. */
    @Test
    void aMidnightCrossingSlotIsReadOnTheWindowsWallClockFromTheDeclaredDataZone(@TempDir Path cfg, @TempDir Path root)
            throws Exception {
        try (Ctx c = open(cfg, root)) {
            timed(c, "t1", "a");
            JsonNode w = op(c, "t1", "{\"op\":\"window\",\"window\":" + SLOT + "}");
            assertEquals("22:00", w.at("/window/slot/start").asText());
            JsonNode e = op(c, "t1", "{\"op\":\"expand\"}");
            assertEquals(Set.of("b", "c", "f"), admitted(e),
                    "22:30 and 03:30 are inside 22:00–04:00 on each side of midnight; 22:00 is in (start inclusive), "
                            + "04:00 is out (end exclusive), 21:59 and 16:00 are out");
            assertEquals("Asia/Tokyo", e.at("/read/rung/window/timezone").asText(), "the inherited window is sealed");

            // Positive twin: no window, the same seed reaches all six.
            timed(c, "t0", "a");
            assertEquals(Set.of("b", "c", "d", "e", "f", "g"), admitted(op(c, "t0", "{\"op\":\"expand\"}")));
        }
    }

    /** The day mask tests the local day the EVENT fell on: 03:30 Wednesday is Wednesday, whatever slot it closes. */
    @Test
    void theDayMaskTestsTheLocalDayOfTheEvent(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            timed(c, "t", "a");
            op(c, "t", "{\"op\":\"window\",\"window\":{\"slot\":{\"start\":\"22:00\",\"end\":\"04:00\"},"
                    + "\"days\":[\"WED\"],\"timezone\":\"Asia/Tokyo\"}}");
            assertEquals(Set.of("c"), admitted(op(c, "t", "{\"op\":\"expand\"}")));
            timed(c, "t2", "a");
            op(c, "t2", "{\"op\":\"window\",\"window\":{\"slot\":{\"start\":\"22:00\",\"end\":\"04:00\"},"
                    + "\"days\":[\"TUE\"],\"timezone\":\"Asia/Tokyo\"}}");
            assertEquals(Set.of("b", "f"), admitted(op(c, "t2", "{\"op\":\"expand\"}")));
        }
    }

    /** The absolute range is two instants, half-open, and an offset other than Z means what it says. */
    @Test
    void theAbsoluteRangeIsHalfOpenOverInstants(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            timed(c, "t", "a");
            // 22:30+09:00 = 13:30Z = a→b exactly (from inclusive); 03:30+09:00 next day = 18:30Z = a→c (to exclusive).
            JsonNode e = op(c, "t", "{\"op\":\"expand\",\"window\":{\"from\":\"2026-09-01T22:30:00+09:00\","
                    + "\"to\":\"2026-09-02T03:30:00+09:00\"}}");
            assertEquals(Set.of("b"), admitted(e));
            assertEquals("2026-09-01T13:30:00Z", e.at("/read/rung/window/from").asText(), "normalised to UTC");
        }
    }

    /** A TIMESTAMPTZ column is already an instant: the same slot admits the same set, and it refuses a zone. */
    @Test
    void anInstantColumnNeedsNoZoneAndRefusesOne(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            String base = "{\"id\":\"tz\",\"dataset\":\"calls_tz_ds\",\"sourceCol\":\"caller\",\"targetCol\":\"callee\","
                    + "\"linkKindCol\":\"channel\",\"timeCol\":\"ts\"";
            HttpResponse<String> zoned = post(c, "/inv/investigations", base + ",\"timeColZone\":\"UTC\"}");
            assertEquals(422, zoned.statusCode(), zoned.body());
            assertTrue(zoned.body().contains("already an instant"), zoned.body());

            JsonNode header = data(post(c, "/inv/investigations", base + "}"));
            assertTrue(header.get("timeColZone").isNull());
            op(c, "tz", "{\"op\":\"seed\",\"ids\":[\"a\"]}");
            op(c, "tz", "{\"op\":\"window\",\"window\":" + SLOT + "}");
            assertEquals(Set.of("b", "c", "f"), admitted(op(c, "tz", "{\"op\":\"expand\"}")));
        }
    }

    /** A naive column with no declared zone is read as UTC — and the header SAYS so. */
    @Test
    void aNaiveColumnDefaultsToAnExplicitUtc(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            create(c, "u", ",\"timeCol\":\"ts\"");
            JsonNode log = data(send(c.port, "GET", "/inv/investigations/u/log", null, null));
            assertEquals("UTC", log.at("/header/timeColZone").asText());
            op(c, "u", "{\"op\":\"seed\",\"ids\":[\"a\"]}");
            op(c, "u", "{\"op\":\"window\",\"window\":" + SLOT + "}");
            // Read as UTC, a→b 10:30 = 19:30 Tokyo (out); a→c 15:30 = 00:30 (in); a→d 16:00 = 01:00 (in).
            assertEquals(Set.of("c", "d"), admitted(op(c, "u", "{\"op\":\"expand\"}")));
        }
    }

    @Test
    void theTimeContractRefusesWhatWouldFallBackToTheHost(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            String base = "{\"dataset\":\"calls_ds\",\"sourceCol\":\"caller\",\"targetCol\":\"callee\",";
            assertEquals(422, post(c, "/inv/investigations", base + "\"id\":\"x1\",\"timeCol\":\"ts_text\"}").statusCode(),
                    "a text column is not an event time");
            assertEquals(422, post(c, "/inv/investigations", base + "\"id\":\"x2\",\"timeCol\":\"ts\","
                    + "\"timeColZone\":\"+05:30\"}").statusCode(), "offset forms are refused — only region ids evaluate");
            assertEquals(422, post(c, "/inv/investigations", base + "\"id\":\"x3\",\"timeColZone\":\"UTC\"}").statusCode(),
                    "a zone without a column");
            assertEquals(200, post(c, "/inv/investigations", base + "\"id\":\"x4\",\"timeCol\":\"ts\","
                    + "\"timeColZone\":\"Asia/Kolkata\"}").statusCode(), "positive twin");

            timed(c, "t", "a");
            String ops = "/inv/investigations/t/ops";
            for (String bad : List.of(
                    "{\"op\":\"window\"}",
                    "{\"op\":\"window\",\"window\":{}}",
                    "{\"op\":\"window\",\"window\":{\"from\":\"2026-09-01T00:00:00\"}}",
                    "{\"op\":\"window\",\"window\":{\"from\":\"2026-09-02T00:00:00Z\",\"to\":\"2026-09-01T00:00:00Z\"}}",
                    "{\"op\":\"window\",\"window\":{\"slot\":{\"start\":\"22:00\",\"end\":\"04:00\"}}}",
                    "{\"op\":\"window\",\"window\":{\"days\":[\"MON\"]}}",
                    "{\"op\":\"window\",\"window\":{\"slot\":{\"start\":\"22:00\",\"end\":\"22:00\"},\"timezone\":\"UTC\"}}",
                    "{\"op\":\"window\",\"window\":{\"slot\":{\"start\":\"25:00\",\"end\":\"04:00\"},\"timezone\":\"UTC\"}}",
                    "{\"op\":\"window\",\"window\":{\"days\":[\"FUNDAY\"],\"timezone\":\"UTC\"}}",
                    "{\"op\":\"window\",\"window\":{\"from\":\"2026-09-01T00:00:00Z\",\"tz\":\"UTC\"}}"))
                assertEquals(422, post(c, ops, bad).statusCode(), bad);
            assertFalse(data(send(c.port, "GET", "/inv/investigations/t/log", null, null)).toString().contains("window"),
                    "no refused window reached the log");
            // Positive twins of the naive-instant and zone-less refusals.
            op(c, "t", "{\"op\":\"window\",\"window\":{\"from\":\"2026-09-01T00:00:00Z\"}}");
            op(c, "t", "{\"op\":\"window\",\"window\":{\"days\":[\"MON\"],\"timezone\":\"UTC\"}}");

            create(c, "untimed", "");
            op(c, "untimed", "{\"op\":\"seed\",\"ids\":[\"a\"]}");
            assertEquals(422, opStatus(c, "untimed", "{\"op\":\"window\",\"window\":" + SLOT + "}"),
                    "a window needs a time column");
            assertEquals(422, opStatus(c, "untimed", "{\"op\":\"expand\",\"minDistinctDays\":2}"));
            assertEquals(200, opStatus(c, "untimed", "{\"op\":\"expand\",\"window\":\"full\"}"), "positive twin");
        }
    }

    // ── the rung fields ────────────────────────────────────────────────────────────────────────────────

    @Test
    void directionAndLinkKindsChooseWhichLinksTheRungFollows(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            Map<String, Set<String>> want = Map.of(
                    "{\"op\":\"expand\"}", Set.of("i", "j", "k", "m"),
                    "{\"op\":\"expand\",\"direction\":\"out\"}", Set.of("i", "j", "k"),
                    "{\"op\":\"expand\",\"direction\":\"in\"}", Set.of("k", "m"),
                    "{\"op\":\"expand\",\"direction\":\"reciprocal\"}", Set.of("k"),
                    "{\"op\":\"expand\",\"linkKinds\":[\"transfer\"]}", Set.of("k"),
                    "{\"op\":\"expand\",\"linkKinds\":[\"sms\",\"voice\"]}", Set.of("i", "j", "m"));
            int n = 0;
            for (var e : want.entrySet()) {
                String id = "d" + (n++);
                timed(c, id, "h");
                assertEquals(e.getValue(), admitted(op(c, id, e.getKey())), e.getKey());
            }
            timed(c, "bad", "h");
            assertEquals(422, opStatus(c, "bad", "{\"op\":\"expand\",\"direction\":\"sideways\"}"));
            assertEquals(422, opStatus(c, "bad", "{\"op\":\"expand\",\"linkKinds\":[]}"));
            data(post(c, "/inv/investigations", "{\"id\":\"nokind\",\"dataset\":\"calls_ds\",\"sourceCol\":\"caller\","
                    + "\"targetCol\":\"callee\"}"));
            op(c, "nokind", "{\"op\":\"seed\",\"ids\":[\"h\"]}");
            assertEquals(422, opStatus(c, "nokind", "{\"op\":\"expand\",\"linkKinds\":[\"sms\"]}"),
                    "linkKinds needs a link-kind column");
        }
    }

    @Test
    void minEventsAndMinDistinctDaysAreCountedPerLink(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            timed(c, "e", "h");
            assertEquals(Set.of("i", "j"), admitted(op(c, "e", "{\"op\":\"expand\",\"minEvents\":2}")));
            timed(c, "d", "h");
            assertEquals(Set.of("i"), admitted(op(c, "d", "{\"op\":\"expand\",\"minDistinctDays\":2}")),
                    "h→j has two events on ONE day");
            timed(c, "bad", "h");
            assertEquals(422, opStatus(c, "bad", "{\"op\":\"expand\",\"minEvents\":0}"));
            assertEquals(422, opStatus(c, "bad", "{\"op\":\"expand\",\"minEvents\":1.5}"));
        }
    }

    /** The candidate's degree is evaluated INSIDE the window: i has degree 3 overall, 1 before 2026-09-04. */
    @Test
    void candidateDegreeIsEvaluatedWithinTheWindow(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            timed(c, "full", "h");
            assertEquals(Set.of("j", "k", "m"), admitted(op(c, "full", "{\"op\":\"expand\",\"candidateDegreeMax\":2}")));
            timed(c, "min", "h");
            assertEquals(Set.of("i"), admitted(op(c, "min", "{\"op\":\"expand\",\"candidateDegreeMin\":2}")));
            timed(c, "win", "h");
            op(c, "win", "{\"op\":\"window\",\"window\":{\"to\":\"2026-09-04T00:00:00Z\"}}");
            assertEquals(Set.of("i", "j", "k", "m"), admitted(op(c, "win", "{\"op\":\"expand\",\"candidateDegreeMax\":2}")),
                    "x1 and x2 fall after the window, so i's in-window degree is 1");
            timed(c, "bad", "h");
            assertEquals(422, opStatus(c, "bad", "{\"op\":\"expand\",\"candidateDegreeMin\":5,\"candidateDegreeMax\":2}"));
        }
    }

    /** maxFanOut keeps the strongest per frontier entity and reports what it left out; budget sets truncated. */
    @Test
    void fanOutKeepsTheStrongestAndBudgetTruncates(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            timed(c, "f", "h");
            JsonNode fan = op(c, "f", "{\"op\":\"expand\",\"maxFanOut\":1}");
            assertEquals(Set.of("i"), admitted(fan), "h→i has the most events");
            assertEquals(4, fan.at("/read/fanOutCapped").asInt());
            assertFalse(fan.get("truncated").asBoolean(), "a stated fan-out cap is not a truncation");

            timed(c, "b", "h");
            JsonNode small = op(c, "b", "{\"op\":\"expand\",\"budget\":2}");
            assertTrue(small.get("truncated").asBoolean());
            assertEquals(2, small.at("/read/rowCount").asInt());
            JsonNode log = data(send(c.port, "GET", "/inv/investigations/b/log", null, null));
            assertTrue(log.at("/entries/1/text").asText().contains("TRUNCATED at its budget of 2"),
                    log.at("/entries/1/text").asText());

            timed(c, "ok", "h");
            HttpResponse<String> renamed = post(c, "/inv/investigations/ok/ops", "{\"op\":\"expand\",\"limit\":5}");
            assertEquals(422, renamed.statusCode(), "the pre-LA-13 key is refused, never silently defaulted");
            assertTrue(renamed.body().contains("'budget'"), renamed.body());
            assertFalse(op(c, "ok", "{\"op\":\"expand\",\"budget\":5}").get("truncated").asBoolean(), "positive twin");
        }
    }

    // ── the window op and its downstream readers ───────────────────────────────────────────────────────

    /** inherit / full / override; undo of a window op; replay equivalence and a reread with no drift. */
    @Test
    void theWindowOpIsInheritedOverriddenUndoneAndReplays(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            timed(c, "w", "a");
            op(c, "w", "{\"op\":\"window\",\"window\":" + SLOT + "}");
            assertEquals(Set.of("b"), admitted(op(c, "w", "{\"op\":\"expand\",\"ids\":[\"a\"],"
                    + "\"window\":{\"from\":\"2026-09-01T13:30:00Z\",\"to\":\"2026-09-01T18:30:00Z\"}}")),
                    "an override replaces the inherited window for this rung only");
            assertEquals(Set.of("c", "f"), admitted(op(c, "w", "{\"op\":\"expand\",\"ids\":[\"a\"]}")), "inherited");
            assertEquals(Set.of("d", "e", "g"), admitted(op(c, "w", "{\"op\":\"expand\",\"ids\":[\"a\"],\"window\":\"full\"}")));

            JsonNode replay = data(post(c, "/inv/investigations/w/replay", "{\"reread\":true}"));
            assertTrue(replay.get("equivalent").asBoolean(), replay.toString());
            assertFalse(replay.get("diverged").asBoolean(), "each reread re-runs the rung AS SEALED: " + replay);
            assertEquals("Asia/Tokyo", replay.at("/workingSet/window/timezone").asText());

            timed(c, "u", "a");
            op(c, "u", "{\"op\":\"window\",\"window\":" + SLOT + "}");
            data(post(c, "/inv/investigations/u/undo", ""));
            assertEquals(6, admitted(op(c, "u", "{\"op\":\"expand\"}")).size(), "the undone window no longer applies");
            op(c, "u", "{\"op\":\"window\",\"window\":\"full\"}");
            assertTrue(data(post(c, "/inv/investigations/u/replay", "{}")).get("equivalent").asBoolean());
        }
    }

    /** ?at= prefix semantics, the D-E4 fork re-reading under the NEW order, and the log line. */
    @Test
    void prefixForkAndLogLineCarryTheWindow(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            timed(c, "p", "a");
            op(c, "p", "{\"op\":\"window\",\"window\":" + SLOT + "}");
            op(c, "p", "{\"op\":\"expand\",\"minEvents\":1,\"maxFanOut\":10,\"linkKinds\":[\"voice\"]}");
            JsonNode at2 = data(send(c.port, "GET", "/inv/investigations/p/working-set?of=entities&at=2", null, null));
            JsonNode at3 = data(send(c.port, "GET", "/inv/investigations/p/working-set?of=entities&at=3", null, null));
            assertEquals(1, at2.get("total").asInt(), at2.toString());
            assertEquals(4, at3.get("total").asInt(), at3.toString());

            JsonNode log = data(send(c.port, "GET", "/inv/investigations/p/log", null, null));
            String win = log.at("/entries/1/text").asText(), exp = log.at("/entries/2/text").asText();
            assertTrue(win.contains("daily 22:00–04:00 (crossing midnight)") && win.contains("Asia/Tokyo"), win);
            assertTrue(exp.contains("link kinds voice") && exp.contains("window daily 22:00–04:00"), exp);

            JsonNode fork = data(post(c, "/inv/investigations/p/reorder", "{\"id\":\"p-fork\",\"order\":[1,3,2]}"));
            assertEquals(7, fork.at("/workingSet/entities").asInt(),
                    "expanding BEFORE the window reads the full range — the order is the method");
        }
    }

    /** The Dossier renders the window and the rung in every rendering, and states the timezone contract. */
    @Test
    void theDossierStatesTheWindowTheRungAndTheTimezoneContract(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            timed(c, "d", "a");
            op(c, "d", "{\"op\":\"window\",\"window\":" + SLOT + "}");
            op(c, "d", "{\"op\":\"expand\",\"budget\":1}");
            HttpResponse<String> method = send(c.port, "GET", "/inv/investigations/d/dossier?format=method", null, null);
            assertEquals(200, method.statusCode(), method.body());
            String text = method.body();
            assertTrue(text.contains("Event time from ts, a wall clock read as America/Sao_Paulo time"), text);
            assertTrue(text.contains("Set the time window to daily 22:00–04:00 (crossing midnight)"), text);
            assertTrue(text.contains("TRUNCATED: step 3 read stopped at its budget of 1"), text);
            JsonNode json = data(send(c.port, "GET", "/inv/investigations/d/dossier", null, null));
            assertEquals("America/Sao_Paulo", json.at("/renderings/json/investigation/timeColZone").asText(),
                    json.toString());
            assertTrue(json.at("/renderings/steps").toString().contains("Set the time window to"), json.toString());
            assertTrue(data(post(c, "/inv/investigations/d/dossier/verify", json.toString()))
                    .get("verified").asBoolean(), "a dossier over window steps verifies");
        }
    }

    /** D-E8: a window templatises as a parameter whose default is the authored window; the rung travels whole. */
    @Test
    void aTemplateCarriesTheRungAndParameterisesTheWindow(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            timed(c, "src", "a");
            op(c, "src", "{\"op\":\"window\",\"window\":" + SLOT + "}");
            op(c, "src", "{\"op\":\"expand\",\"direction\":\"out\",\"maxFanOut\":50}");
            JsonNode tpl = data(post(c, "/inv/investigations/src/template", "{\"id\":\"tpl\"}"));
            JsonNode window = tpl.at("/parameters/1");
            assertEquals("window1", window.get("name").asText(), tpl.toString());
            assertEquals("Asia/Tokyo", window.at("/default/timezone").asText());
            assertEquals("out", tpl.at("/ops/2/direction").asText(), tpl.toString());
            assertEquals("ts", tpl.at("/roles/timeCol").asText());

            JsonNode byDefault = data(post(c, "/inv/investigation-templates/tpl/instantiate",
                    "{\"id\":\"i1\",\"params\":{\"seed1\":[\"a\"]}}"));
            assertEquals(4, byDefault.at("/workingSet/entities").asInt(), "the authored window by default");
            JsonNode overridden = data(post(c, "/inv/investigation-templates/tpl/instantiate",
                    "{\"id\":\"i2\",\"params\":{\"seed1\":[\"a\"],\"window1\":\"full\"}}"));
            assertEquals(7, overridden.at("/workingSet/entities").asInt(), "an override window re-binds the period");
            assertEquals(422, post(c, "/inv/investigation-templates/tpl/instantiate",
                    "{\"id\":\"i3\",\"params\":{\"seed1\":[\"a\"],\"window1\":{\"slot\":{\"start\":\"22:00\",\"end\":\"04:00\"}}}}")
                    .statusCode(), "an override is validated like an authored window");
        }
    }

    /** The window op rides the existing capability gate — asserted with a Subject that lacks it. */
    @Test
    void theWindowOpNeedsCanManageIncidents(@TempDir Path cfg, @TempDir Path root) throws Exception {
        Authenticators.forTest(ex -> switch (String.valueOf(ex.getRequestHeaders().getFirst("Authorization"))) {
            case "Bearer owner" -> Optional.of(new Subject("analyst-1", Set.of("canManageIncidents")));
            case "Bearer plain" -> Optional.of(new Subject("analyst-1", Set.of()));
            default -> Optional.empty();
        });
        try (Ctx c = open(cfg, root)) {
            assertEquals(200, send(c.port, "POST", "/inv/investigations", "{\"id\":\"g\",\"dataset\":\"calls_ds\","
                    + "\"sourceCol\":\"caller\",\"targetCol\":\"callee\",\"timeCol\":\"ts\"}", "Bearer owner").statusCode());
            String w = "{\"op\":\"window\",\"window\":" + SLOT + "}";
            assertEquals(403, send(c.port, "POST", "/inv/investigations/g/ops", w, "Bearer plain").statusCode());
            assertEquals(200, send(c.port, "POST", "/inv/investigations/g/ops", w, "Bearer owner").statusCode());
        } finally {
            Authenticators.forTest(null);
        }
    }
}
