package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.service.CollectorService;
import com.gamma.spi.auth.Authenticators;
import com.gamma.spi.auth.Subject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The honest, forgiving import over real HTTP ({@code LA-FULL-CYCLE-TEST-1} phase 3, findings 3, 4, 5 and 10), on the
 * committed synthetic fixture {@code onboard/full-cycle/} (461 transfers with four dirty rows, 138 accounts; written by
 * {@code tools/gen-la-import-fixture.mjs}, the dirty rows named in the README beside the files): a column that is almost
 * all dates with a time, or almost all numbers, is proposed as that at low confidence with the odd value named; the link's
 * time and measure follow; a type the person states is answered again by the server ({@code POST
 * /onboard/files/{draftId}/redraft}); and the result accounts for every row and every blanked value.
 */
class ControlApiOnboardFullCycleTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private final HttpClient client = HttpClient.newHttpClient();
    private final List<String> ids = new ArrayList<>();

    private record Ctx(CollectorService svc, ControlApi api, int port, Path root) implements AutoCloseable {
        public void close() {
            api.close();
            svc.close();
        }
    }

    @AfterEach
    void cleanUp() {
        Authenticators.forTest(null);
        OnboardImport.followOnsForTest(null);
        Path data = Path.of("database").toAbsolutePath();
        for (String id : ids) {
            OnboardRoutes.deleteTree(data.resolve(id));
            OnboardRoutes.deleteTree(data.resolve("inbox").resolve(id));
        }
        OnboardRoutes.deleteTree(data.resolve(OnboardRoutes.DRAFTS));
        for (Path p : List.of(data.resolve("inbox"), data))
            try {
                Files.deleteIfExists(p);
            } catch (java.io.IOException notEmpty) {
                // another fixture's data lives there
            }
    }

    private Ctx open(Path cfg, Path writeRoot) throws Exception {
        Path pipe = PipelineConfigBatchTest.writePipeline(cfg, "");
        System.setProperty("assist.write.root", writeRoot.toString());
        try {
            CollectorService svc = new CollectorService(List.of(pipe), 3600, 1);
            ControlApi api = new ControlApi(svc, 0);
            api.start();
            return new Ctx(svc, api, api.port(), writeRoot);
        } finally {
            System.clearProperty("assist.write.root");
        }
    }

    private HttpResponse<String> send(Ctx c, String method, String path, byte[] body, String auth) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + c.port + "/api/v1" + path));
        if (auth != null) b.header("Authorization", auth);
        b = body == null ? (method.equals("GET") ? b.GET() : b.method(method, BodyPublishers.noBody()))
                : b.method(method, BodyPublishers.ofByteArray(body));
        return client.send(b.build(), BodyHandlers.ofString());
    }

    private HttpResponse<String> json(Ctx c, String method, String path, String body, String auth) throws Exception {
        return send(c, method, path, body == null ? null : body.getBytes(StandardCharsets.UTF_8), auth);
    }

    private static JsonNode data(HttpResponse<String> r, int expected) throws Exception {
        assertEquals(expected, r.statusCode(), r.body());
        return V1Body.of(r.body());
    }

    private static String error(HttpResponse<String> r) throws Exception {
        return JSON.readTree(r.body()).path("error").path("message").asText();
    }

    private String uniqueId(String base) {
        String id = base + "_" + UUID.randomUUID().toString().substring(0, 8);
        ids.add(id);
        return id;
    }

    private static byte[] fixture(String name) throws Exception {
        try (InputStream in = ControlApiOnboardFullCycleTest.class.getResourceAsStream("/onboard/full-cycle/" + name)) {
            assertNotNull(in, "the committed fixture " + name);
            return in.readAllBytes();
        }
    }

    private JsonNode draft(Ctx c, String name, String kind, byte[] content) throws Exception {
        return data(send(c, "POST", "/onboard/files?name=" + name + "&kind=" + kind, content, null), 200);
    }

    private static JsonNode type(JsonNode draft, String column) {
        for (JsonNode c : draft.get("columns")) if (c.get("name").asText().equals(column)) return c.get("type");
        throw new AssertionError("no column " + column + " in " + draft.get("columns"));
    }

    private static JsonNode proposal(JsonNode draft, String column, String what) {
        for (JsonNode c : draft.get("entityTypes").get("columns")) if (c.get("column").asText().equals(column)) return c.get(what);
        throw new AssertionError("no proposal for " + column);
    }

    private static JsonNode named(JsonNode list, String column) {
        for (JsonNode e : list) if (e.get("column").asText().equals(column)) return e;
        throw new AssertionError("nothing about " + column + " in " + list);
    }

    /**
     * The clicks the screen needs after the drop (finding 6): one per step that holds a guess - its own Confirm, or
     * Confirm all - plus Create and run. The guesses are counted as the screen counts them ({@code lowChoices} in
     * {@code add-data.model.ts}): a low choice with a value; a role only where the column holds no Entity Type.
     */
    private static int clicksAfterTheDrop(JsonNode d) {
        int columns = 0, hold = 0;
        for (String k : List.of("type", "delimiter", "header", "quote")) if (low(d.get("format").get(k))) columns++;
        for (JsonNode c : d.get("columns")) if (low(c.get("type"))) columns++;
        JsonNode et = d.get("entityTypes");
        for (JsonNode c : et.get("columns")) {
            boolean holds = !c.get("entityType").get("value").isNull();
            if (holds && low(c.get("entityType"))) hold++;
            if (!holds && low(c.get("role"))) hold++;
        }
        if (low(et.get("primary")) && !et.get("primary").get("value").isNull()) hold++;
        JsonNode link = et.get("link");
        if (link != null && !link.get("from").get("value").isNull() && (low(link.get("from")) || low(link.get("to")))) hold++;
        for (String k : List.of("kind", "primaryTime", "primaryMeasure"))
            if (link != null && !link.get(k).get("value").isNull() && low(link.get(k))) hold++;
        JsonNode id = et.get("identifying");
        if (id != null && !id.get("value").isNull() && low(id)) hold++;
        return (columns > 0 ? 1 : 0) + (hold > 0 ? 1 : 0) + 1;
    }

    private static boolean low(JsonNode choice) {
        return choice != null && "low".equals(choice.path("confidence").asText());
    }

    private static String redraftBody(String column, String key, String value) {
        return "{\"columns\":[{\"name\":\"" + column + "\",\"" + key + "\":\"" + value + "\"}]}";
    }

    // ── finding 3: one bad value no longer hides the time column or the measure ─────────────────────────────────────

    @Test
    void aColumnThatIsAlmostAllDatesIsProposedAsOneAndAlmostAllNumbersStaysTextBothWithTheOddValueNamed(@TempDir Path cfg, @TempDir Path root)
            throws Exception {
        try (Ctx c = open(cfg, root)) {
            JsonNode d = draft(c, "transfers.csv", "stream", fixture("transfers.csv"));
            assertEquals(461, d.get("file").get("rows").asLong());

            JsonNode booked = type(d, "booked_at");
            assertEquals("TIMESTAMP", booked.get("value").asText(), booked.toString());
            assertEquals("low", booked.get("confidence").asText(), "a guess the person confirms: " + booked);
            assertEquals("%Y-%m-%dT%H:%M:%S", booked.get("format").asText(), "the format the 460 good values are written in");
            String why = booked.get("evidence").asText();
            assertTrue(why.contains("460 of 461 sampled values are dates with a time"), why);
            assertTrue(why.contains("but 1 is not (e.g. 'not-a-date')"), "singular, and the odd value is named: " + why);
            assertTrue(why.contains("it will be left blank"), why);

            // operator ruling 4 (2026-11-19, BASIC-ELT-PROFILE-1): a number is proposed only when every value is one - the
            // 'abc' keeps amount text, said as a guess the person may change (it was a DOUBLE with 'abc' blanked)
            JsonNode amount = type(d, "amount");
            assertEquals("VARCHAR", amount.get("value").asText(), amount.toString());
            assertEquals("low", amount.get("confidence").asText());
            assertTrue(amount.get("evidence").asText().contains(
                    "460 of 461 sampled values are numbers, but 1 is not (e.g. 'abc'), so it is kept as text"), amount.toString());

            // finding 6: the two guessed types are one Confirm all, then Create and run - what follows a guessed type
            // (the Time, the Measure, the link's When and Weighed by) does not ask a second time
            assertEquals(2, clicksAfterTheDrop(d), "Confirm all 2 guesses, Create and run: " + d.get("entityTypes"));

            // the roles that follow see the columns: When is proposed; nothing is a number, so nothing weighs a link
            JsonNode link = d.get("entityTypes").get("link");
            assertEquals("from_account", link.get("from").get("value").asText());
            assertEquals("to_account", link.get("to").get("value").asText());
            assertEquals("booked_at", link.get("primaryTime").get("value").asText(), link.toString());
            assertTrue(link.get("primaryMeasure").get("value").isNull(), link.toString());
            assertEquals("temporal", proposal(d, "booked_at", "role").get("value").asText());

            // the person makes amount a number: the odd value is named, and amount weighs the links, in one edit
            JsonNode asNumber = data(json(c, "POST", "/onboard/files/" + d.get("draftId").asText() + "/redraft",
                    redraftBody("amount", "type", "DOUBLE"), null), 200);
            JsonNode n = type(asNumber, "amount");
            assertEquals("DOUBLE", n.get("value").asText(), n.toString());
            assertTrue(n.get("evidence").asText().contains("460 of 461 sampled values are numbers, but 1 is not (e.g. 'abc')"), n.toString());
            JsonNode measure = asNumber.get("entityTypes").get("link").get("primaryMeasure");
            assertEquals("amount", measure.get("value").asText(), measure.toString());
            assertEquals("high", measure.get("confidence").asText(), "what follows a stated type does not ask again: " + measure);
            assertTrue(measure.get("evidence").asText().contains("460 of 461 sampled values are numbers"),
                    "never 'all 461' when one is not: " + measure);
            assertEquals("measure", proposal(asNumber, "amount", "role").get("value").asText());

            // finding 10: the Entity Type's label, never its id or a column type's engine name
            String from = proposal(d, "from_account", "entityType").get("evidence").asText();
            assertTrue(from.contains("the name 'from_account' says Account"), from);
            String all = d.toString();
            assertFalse(all.contains("timestamp values") || all.contains("parse as"), "no engine type in a reason: " + all);
        }
    }

    @Test
    void aMostlyTypedGuessNeedsNineInTenAndTenAgreeingValues(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            // 8 of 10 dates: four in five is not enough to blank the rest, and 8 values are too few to say "almost all"
            StringBuilder few = new StringBuilder("ref,seen_at,points\n");
            for (int i = 0; i < 10; i++)
                few.append("R").append(i).append(',').append(i < 8 ? "2026-03-0" + (i + 1) + "T10:00:00" : "soon").append(',')
                        .append(i < 8 ? i + ".5" : "n/a").append('\n');
            JsonNode d = draft(c, "few.csv", "stream", few.toString().getBytes(StandardCharsets.UTF_8));
            assertEquals("VARCHAR", type(d, "seen_at").get("value").asText(), type(d, "seen_at").toString());
            assertEquals("VARCHAR", type(d, "points").get("value").asText(), type(d, "points").toString());
            String text = type(d, "points").get("evidence").asText();
            assertTrue(text.contains("8 of 10 sampled values are numbers, but 2 are not (e.g. 'n/a')"), "plural: " + text);

            // almost all day-first dates, one of them not a real date: a date, as a guess, in the format that reads them -
            // and the fix catalogue has nothing left to offer for it
            StringBuilder orders = new StringBuilder("order_id,ordered\n");
            for (int i = 0; i < 240; i++)
                orders.append("O").append(i).append(',').append(i == 7 ? "31/02/2026" : String.format("%02d/%02d/2026", 13 + i % 15, 1 + i % 12)).append('\n');
            JsonNode o = draft(c, "orders.csv", "stream", orders.toString().getBytes(StandardCharsets.UTF_8));
            JsonNode ordered = type(o, "ordered");
            assertEquals("DATE", ordered.get("value").asText(), ordered.toString());
            assertEquals("low", ordered.get("confidence").asText());
            assertEquals("%d/%m/%Y", ordered.get("format").asText());
            assertTrue(ordered.get("evidence").asText().contains("239 of 240 sampled values are dates, but 1 is not (e.g. '31/02/2026')"), ordered.toString());
            assertEquals(0, o.get("findings").size(), "proposed, so not also offered as a fix: " + o.get("findings"));

            // almost all whole numbers stay text whatever the name says (operator ruling 4, 2026-11-19)
            StringBuilder ids = new StringBuilder("badge,visits\n");
            for (int i = 0; i < 40; i++) ids.append(i == 7 ? "X-17" : String.valueOf(5000 + i)).append(',').append(i).append('\n');
            JsonNode b = draft(c, "badges.csv", "stream", ids.toString().getBytes(StandardCharsets.UTF_8));
            assertEquals("VARCHAR", type(b, "badge").get("value").asText(), "39 of 40 whole numbers, not named as an amount: " + type(b, "badge"));
            assertTrue(type(b, "badge").get("evidence").asText().contains("but 1 is not (e.g. 'X-17'), so it is kept as text"),
                    type(b, "badge").toString());
        }
    }

    // ── finding 4: a stated type is answered again by the server ────────────────────────────────────────────────────

    @Test
    void aStatedTypeOrRoleIsProposedAgainByTheServerInOneCall(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            JsonNode d = draft(c, "transfers.csv", "stream", fixture("transfers.csv"));
            String draftId = d.get("draftId").asText();

            // the person says booked_at is text: the link's time and its reason follow, they do not argue
            JsonNode asText = data(json(c, "POST", "/onboard/files/" + draftId + "/redraft", redraftBody("booked_at", "type", "VARCHAR"), null), 200);
            assertEquals(draftId, asText.get("draftId").asText(), "the same draft, answered again");
            assertEquals("VARCHAR", type(asText, "booked_at").get("value").asText());
            assertEquals("high", type(asText, "booked_at").get("confidence").asText(), "the person's own choice needs no confirm");
            assertTrue(type(asText, "booked_at").get("evidence").asText().startsWith("you chose Text"), type(asText, "booked_at").toString());
            JsonNode time = asText.get("entityTypes").get("link").get("primaryTime");
            assertTrue(time.get("value").isNull(), time.toString());
            assertEquals("no column holds dates or times", time.get("evidence").asText());
            assertEquals(d.get("entityTypes").get("link").get("primaryMeasure"), asText.get("entityTypes").get("link").get("primaryMeasure"),
                    "the rest stands");

            // back to a date and time, in one edit: the type, its format, the role and the link's time all follow
            JsonNode asTime = data(json(c, "POST", "/onboard/files/" + draftId + "/redraft", redraftBody("booked_at", "type", "TIMESTAMP"), null), 200);
            JsonNode t = type(asTime, "booked_at");
            assertEquals("TIMESTAMP", t.get("value").asText());
            assertEquals("high", t.get("confidence").asText());
            assertEquals("%Y-%m-%dT%H:%M:%S", t.get("format").asText(), "the server finds the format; the screen never has to");
            assertTrue(t.get("evidence").asText().contains("you chose Date and time: 460 of 461 sampled values are dates with a time, but 1 is not"),
                    t.toString());
            assertEquals("temporal", proposal(asTime, "booked_at", "role").get("value").asText());
            assertEquals("booked_at", asTime.get("entityTypes").get("link").get("primaryTime").get("value").asText());

            // a stated role is one edit too: channel as a Detail is no longer the link's kind
            JsonNode detail = data(json(c, "POST", "/onboard/files/" + draftId + "/redraft",
                    "{\"columns\":[{\"name\":\"booked_at\",\"type\":\"TIMESTAMP\"},{\"name\":\"channel\",\"role\":\"detail\"}]}", null), 200);
            assertEquals("detail", proposal(detail, "channel", "role").get("value").asText());
            assertTrue(proposal(detail, "channel", "role").get("evidence").asText().startsWith("you chose Detail"),
                    proposal(detail, "channel", "role").toString());
            assertTrue(detail.get("entityTypes").get("link").get("kind").get("value").isNull(), detail.get("entityTypes").get("link").toString());

            // the gates of the route
            HttpResponse<String> r = json(c, "POST", "/onboard/files/d" + "0".repeat(32) + "/redraft", "{\"columns\":[]}", null);
            assertEquals(404, r.statusCode(), r.body());
            r = json(c, "POST", "/onboard/files/" + draftId + "/redraft", redraftBody("nope", "type", "DATE"), null);
            assertEquals(422, r.statusCode(), r.body());
            assertTrue(error(r).contains("the draft has no column 'nope'"), r.body());
            r = json(c, "POST", "/onboard/files/" + draftId + "/redraft", redraftBody("amount", "type", "MONEY"), null);
            assertEquals(422, r.statusCode(), r.body());
            r = json(c, "POST", "/onboard/files/" + draftId + "/redraft", redraftBody("amount", "role", "entity"), null);
            assertEquals(422, r.statusCode(), "an Entity is stated by its Entity Type, not as a role: " + r.body());

            // and the commit takes the draft as it was last answered: the stated type with the format the server found
            String id = uniqueId("restated");
            JsonNode s = data(json(c, "POST", "/onboard/files/" + draftId + "/commit", "{\"name\":\"" + id + "\"}", null), 200);
            assertEquals(1, named(s.get("blanked"), "booked_at").get("values").asLong(), "460 dates read, 1 blanked - not all 461: " + s);
        }
    }

    @Test
    void redraftIsGatedLikeTheDraft(@TempDir Path cfg, @TempDir Path root) throws Exception {
        Authenticators.forTest(ex -> switch (String.valueOf(ex.getRequestHeaders().getFirst("Authorization"))) {
            case "Bearer uploader" -> Optional.of(new Subject("uploader-1", Set.of("canUploadData")));
            case "Bearer uploader2" -> Optional.of(new Subject("uploader-2", Set.of("canUploadData")));
            case "Bearer analyst" -> Optional.of(new Subject("analyst-1", Set.of("canManageIncidents")));
            case "Bearer admin" -> Optional.of(new Subject("admin-1", Set.of("canAdminister", "canUploadData")));
            default -> Optional.empty();
        });
        try (Ctx c = open(cfg, root)) {
            data(json(c, "PUT", "/settings/data", "{\"selfImport\":true}", "Bearer admin"), 200);
            String draftId = data(send(c, "POST", "/onboard/files?name=transfers.csv&kind=stream", fixture("transfers.csv"), "Bearer uploader"), 200)
                    .get("draftId").asText();
            String path = "/onboard/files/" + draftId + "/redraft", body = redraftBody("booked_at", "type", "VARCHAR");
            assertEquals(401, json(c, "POST", path, body, null).statusCode(), "no credential");
            assertEquals(403, json(c, "POST", path, body, "Bearer analyst").statusCode(), "a Subject without canUploadData is refused");
            assertEquals(404, json(c, "POST", path, body, "Bearer uploader2").statusCode(), "another person's draft is not there for this caller");
            assertEquals(200, json(c, "POST", path, body, "Bearer uploader").statusCode());
            // the Space's switch is checked on this route too: turned off, the same call is refused
            data(json(c, "PUT", "/settings/data", "{\"selfImport\":false}", "Bearer admin"), 200);
            HttpResponse<String> off = json(c, "POST", path, body, "Bearer uploader");
            assertEquals(403, off.statusCode(), off.body());
            assertTrue(error(off).contains("an administrator can turn it on"), off.body());
        }
    }

    // ── finding 5: the result accounts for every row and every blanked value ────────────────────────────────────────

    @Test
    void theResultNamesAllFourDirtyRowsAndItsCountsAddUp(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            String transfers = uniqueId("transfers");
            JsonNode d = draft(c, "transfers.csv", "stream", fixture("transfers.csv"));
            JsonNode s = data(json(c, "POST", "/onboard/files/" + d.get("draftId").asText() + "/commit", "{\"name\":\"" + transfers + "\"}", null), 200);

            // every row is accounted for: read = kept + set aside + left out by a filter
            assertEquals(461, s.get("rowsIn").asLong(), s.toString());
            assertEquals(0, s.get("rowsRejected").asLong(), "a value that does not fit its type blanks the value, never the row: " + s);
            assertEquals(461, s.get("rowsAfter").asLong());
            assertEquals(s.get("rowsIn").asLong(), s.get("rowsAfter").asLong() + s.get("rowsRejected").asLong());

            // dirty row 2: the value a type could not read, per column, with one of it. Dirty row 3's 'abc' is kept: amount
            // is text since operator ruling 4 (2026-11-19) - a number is proposed only when every value is one
            JsonNode blanked = s.get("blanked");
            assertEquals(1, blanked.size(), blanked.toString());
            JsonNode when = named(blanked, "booked_at");
            assertEquals(1, when.get("values").asLong(), when.toString());
            assertEquals("not-a-date", when.get("example").asText());
            assertEquals("timestamp", when.get("type").asText(), "the Dataset's own word for the column type");

            // dirty row 1: kept, but it cannot be a link, and the result says which end is missing
            JsonNode unlinked = s.get("unlinked");
            assertEquals(1, unlinked.get("rows").asLong(), unlinked.toString());
            assertEquals(1, unlinked.get("noFrom").asLong());
            assertEquals(0, unlinked.get("noTo").asLong());
            assertEquals("from_account", unlinked.get("from").asText());

            // dirty row 4: a spelling that differs only by spaces and letter case is the same entity, and the result says so
            JsonNode respelled = named(s.get("respelled"), "from_account");
            assertEquals(1, respelled.get("values").asLong(), respelled.toString());
            assertEquals(" acc-1003 ", respelled.get("example").asText());
            assertEquals("ACC-1003", respelled.get("readAs").asText());

            // the Arrivals grid: Left out and Covers are filled once the Time column really holds dates
            JsonNode arrivals = data(json(c, "GET", "/datasets/" + transfers + "/arrivals", null, null), 200);
            assertEquals("booked_at", arrivals.get("eventDateColumn").asText(), arrivals.toString());
            JsonNode a = arrivals.get("arrivals").get(0);
            assertEquals(461, a.get("rowsRead").asLong(), a.toString());
            assertEquals(0, a.get("rowsLeftOut").asLong(), "nothing was left out, and the grid says 0, not blank: " + a);
            assertTrue(a.get("coversFrom").asText().startsWith("2026-01-01"), a.toString());
            assertTrue(a.get("coversTo").asText().startsWith("2026-04-30"), a.toString());

            // the details file: a clean import has nothing to report, and every count still adds up
            String accounts = uniqueId("accounts");
            JsonNode ad = draft(c, "accounts.csv", "reference", fixture("accounts.csv"));
            assertEquals("account_id", ad.get("entityTypes").get("identifying").get("value").asText(), ad.get("entityTypes").toString());
            // 138 rows are under OF-2's 200, so every choice is a guess (it took sixteen Confirm clicks): now one
            // Confirm all per step, then Create and run
            assertEquals(3, clicksAfterTheDrop(ad), ad.toString());
            assertEquals("DATE", type(ad, "opened_on").get("value").asText());
            String distinct = proposal(ad, "type", "role").get("evidence").asText();
            assertTrue(distinct.contains("3 distinct values in 138 sampled rows"), distinct);
            JsonNode as = data(json(c, "POST", "/onboard/files/" + ad.get("draftId").asText() + "/commit", "{\"name\":\"" + accounts + "\"}", null), 200);
            assertEquals(138, as.get("rowsIn").asLong(), as.toString());
            assertEquals(138, as.get("rowsAfter").asLong());
            assertEquals(0, as.get("blanked").size(), as.toString());
            assertFalse(as.has("unlinked"), "details about entities draw no links, so none can be missing: " + as);
        }
    }

    @Test
    void aTypeStatedOnlyInTheCommitStillGetsTheFormatItsValuesAreWrittenIn(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            // half the values are no dates, so the draft keeps the column as text; the person (or an API client) says Date and time
            StringBuilder sb = new StringBuilder("ref,seen_at\n");
            for (int i = 0; i < 40; i++)
                sb.append("R").append(i).append(',').append(i % 2 == 0 ? String.format("2026-03-%02dT10:00:00", 1 + i / 2) : "unknown").append('\n');
            String id = uniqueId("seen");
            JsonNode d = draft(c, "seen.csv", "stream", sb.toString().getBytes(StandardCharsets.UTF_8));
            assertEquals("VARCHAR", type(d, "seen_at").get("value").asText());
            JsonNode s = data(json(c, "POST", "/onboard/files/" + d.get("draftId").asText() + "/commit",
                    "{\"name\":\"" + id + "\",\"link\":null,\"columns\":[{\"name\":\"seen_at\",\"type\":\"TIMESTAMP\"}]}", null), 200);
            JsonNode seen = named(s.get("blanked"), "seen_at");
            assertEquals(20, seen.get("values").asLong(), "the 20 'unknown' are blanked and counted; the 20 dates are read, not blanked too: " + s);
            assertEquals("unknown", seen.get("example").asText());
        }
    }

    // ── finding 10: one plural helper ───────────────────────────────────────────────────────────────────────────────

    @Test
    void theWordsHelperAgreesInNumber() {
        assertEquals("1 value", Words.count(1, "value", "values"));
        assertEquals("3 values", Words.count(3, "value", "values"));
        assertEquals("0 values", Words.count(0, "value", "values"));
        assertEquals("is", Words.pick(1, "is", "are"));
        assertEquals("are", Words.pick(2, "is", "are"));
        assertEquals("dates with a time", Words.plural("TIMESTAMP"));
        assertEquals("a decimal number", Words.singular("DOUBLE"));
        assertEquals("Date and time", Words.label("TIMESTAMP"));
    }
}
