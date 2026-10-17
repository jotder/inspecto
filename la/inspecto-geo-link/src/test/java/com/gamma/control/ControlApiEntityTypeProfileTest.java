package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.gamma.la.api.InputFingerprintCache;
import com.gamma.la.core.CollectorCoveragePort;
import com.gamma.la.core.CollectorCoveragePorts;
import com.gamma.pipeline.ComponentStore;
import com.gamma.spi.auth.Authenticators;
import com.gamma.util.DuckDbUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

import static com.gamma.control.EntityProfileFixture.A;
import static com.gamma.control.EntityProfileFixture.OWNER;
import static com.gamma.control.EntityProfileFixture.STRANGER;
import static com.gamma.control.EntityProfileFixture.data;
import static com.gamma.control.EntityProfileFixture.enc;
import static com.gamma.control.EntityProfileFixture.get;
import static com.gamma.control.EntityProfileFixture.noRaw;
import static com.gamma.control.EntityProfileFixture.open;
import static com.gamma.control.EntityProfileFixture.send;
import static com.gamma.control.EntityProfileFixture.settings;
import static com.gamma.control.EntityProfileFixture.subjects;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code GET /inv/entity-types} and {@code GET /inv/entity-types/{id}/profile} over REAL HTTP (la-entity-profile-plan §4.5):
 * binding resolution and roles, aggregates only for a masked type, both sentinel kinds, the safe prefix per normaliser, the
 * per-day timeline, lineage, links from the Dataset fallback, relationships, the {@code entity_profile.*} limits, the cache
 * keyed by the input-file fingerprint, {@code assessed:false} paths and every gate.
 */
class ControlApiEntityTypeProfileTest {

    @AfterEach
    void reset() {
        Authenticators.forTest(null);
        CollectorCoveragePorts.forTest(null);
        InputFingerprintCache.forTest(null, 0);
    }

    private static JsonNode binding(JsonNode bindings, String column) {
        for (JsonNode b : bindings) if (column.equals(b.get("column").asText())) return b;
        throw new AssertionError("no binding of " + column + " in " + bindings);
    }

    @Test
    void theListResolvesBindingsWithRolesAndAHeadlineOnlyOnceAProfileWasRead(@TempDir Path cfg, @TempDir Path root) throws Exception {
        subjects();
        try (EntityProfileFixture.Ctx c = open(cfg, root)) {
            JsonNode items = data(get(c, "/inv/entity-types", OWNER)).get("items");
            assertEquals(9, items.size(), "the seeded types are in force");
            JsonNode msisdn = null, imei = null, wallet = null;
            for (JsonNode t : items) {
                if ("msisdn".equals(t.get("id").asText())) msisdn = t;
                if ("imei".equals(t.get("id").asText())) imei = t;
                if ("wallet".equals(t.get("id").asText())) wallet = t;
            }
            assertNotNull(msisdn);
            assertEquals("e164", msisdn.get("normaliser").asText());
            assertTrue(msisdn.get("masked").asBoolean());
            assertEquals("source", binding(msisdn.get("bindings"), "a_msisdn").get("role").asText(), "the saved view's source column");
            assertEquals("target", binding(msisdn.get("bindings"), "b_msisdn").get("role").asText());
            assertEquals("attribute", binding(imei.get("bindings"), "imei").get("role").asText(), "bound, but no link end");
            assertEquals(0, wallet.get("bindings").size(), "a type no column claims has no binding");
            assertFalse(msisdn.at("/headline/assessed").asBoolean());
            assertEquals("not computed yet", msisdn.at("/headline/reason").asText());

            data(get(c, "/inv/entity-types/msisdn/profile?sections=volume", OWNER));
            JsonNode after = data(get(c, "/inv/entity-types", OWNER)).get("items");
            for (JsonNode t : after)
                if ("msisdn".equals(t.get("id").asText())) {
                    assertTrue(t.at("/headline/assessed").asBoolean(), t.toString());
                    assertEquals(5, t.at("/headline/distinct").asLong(), "A, B, C, D and the -99 sentinel's key 99");
                    assertTrue(t.at("/headline/approximate").asBoolean());
                    assertFalse(t.at("/headline/computedAt").isNull());
                }

            JsonNode stranger = data(get(c, "/inv/entity-types", STRANGER)).get("items");
            for (JsonNode t : stranger) assertEquals(0, t.get("bindings").size(), "R3: a Dataset the caller cannot view binds nothing");
            for (JsonNode t : stranger)
                if ("msisdn".equals(t.get("id").asText()))
                    assertFalse(t.at("/headline/assessed").asBoolean(), "another caller's view of the bindings has no headline of its own yet");
        }
    }

    @Test
    void aMaskedTypesProfileIsAggregatesOnlyWithEverySection(@TempDir Path cfg, @TempDir Path root) throws Exception {
        subjects();
        try (EntityProfileFixture.Ctx c = open(cfg, root)) {
            JsonNode p = noRaw(get(c, "/inv/entity-types/msisdn/profile", OWNER));
            assertEquals("msisdn", p.at("/entityType/id").asText());
            assertEquals("typed", p.at("/masking/mode").asText());
            assertTrue(p.at("/masking/masked").asBoolean());
            assertFalse(p.get("cached").asBoolean(), "a view-backed Dataset has no file fingerprint: never cached");
            assertFalse(p.get("truncated").asBoolean());

            JsonNode a = binding(p.get("bindings"), "a_msisdn");
            assertEquals(6, a.get("rows").asLong());
            assertEquals(6, a.get("nonNull").asLong());
            assertEquals(1.0, a.get("fillRate").asDouble());
            assertEquals(3, a.get("distinct").asLong(), "A, B and 99");
            assertEquals(1, a.get("failNormaliser").asLong(), "'unknown' folds to nothing under e164");
            assertEquals("ts", a.get("timeColumn").asText());
            assertTrue(a.get("assessed").asBoolean());
            assertEquals(4, binding(p.get("bindings"), "b_msisdn").get("distinct").asLong());

            JsonNode v = p.get("volume");
            assertEquals(5, v.get("distinct").asLong());
            assertEquals(12, v.get("rows").asLong());
            List<String> sentinels = new ArrayList<>();
            for (JsonNode s : v.get("sentinels")) {
                sentinels.add(s.get("column").asText() + ":" + s.get("kind").asText() + ":" + (s.get("value").isNull() ? "null" : s.get("value").asText()));
                if ("dominant".equals(s.get("kind").asText())) assertTrue(s.get("value").isNull(), "D-EP3: a dominant value is never shown");
            }
            assertTrue(sentinels.contains("a_msisdn:builtin:-99"), sentinels.toString());
            assertTrue(sentinels.contains("a_msisdn:builtin:unknown"), sentinels.toString());
            assertTrue(sentinels.contains("a_msisdn:dominant:null"), "A holds 3 of 6 values, above the 20 % default: " + sentinels);

            JsonNode shapes = p.get("shapes");
            assertEquals(3, shapes.get("prefixLength").asInt(), "e164 declares a country-code prefix");
            assertEquals("249", shapes.at("/prefix/0/prefix").asText());
            assertEquals(10, shapes.at("/prefix/0/rows").asLong());
            assertEquals(2, shapes.get("length").size());
            assertEquals(2, shapes.at("/length/0/length").asInt(), "the key 99");
            assertEquals(13, shapes.at("/length/1/length").asInt());
            assertEquals(11, shapes.at("/charClass/digits").asLong());
            assertFalse(shapes.get("sampled").asBoolean());

            JsonNode time = p.get("time");
            assertTrue(time.get("assessed").asBoolean(), time.toString());
            assertEquals("2026-10-01T10:00:00Z", time.get("earliest").asText());
            assertEquals("2026-10-03T12:00:00Z", time.get("latest").asText());
            assertEquals(2, time.get("perDay").size());
            assertEquals("2026-10-01", time.at("/perDay/0/day").asText());
            assertEquals(4, time.at("/perDay/0/rows").asLong());
            assertEquals(3, time.at("/perDay/0/distinct").asLong());
            assertEquals(7, time.at("/perDay/1/rows").asLong());
            assertEquals(5, time.at("/perDay/1/distinct").asLong());
            assertEquals("[\"2026-10-02\"]", time.get("missingDays").toString());
            assertEquals("calls_ds", time.at("/dataAsOf/0/dataset").asText());

            JsonNode links = p.get("links");
            assertEquals("dataset", links.get("from").asText(), "lane A: the Dataset fallback only");
            assertTrue(links.get("assessed").asBoolean(), links.toString());
            assertEquals("call", links.at("/kinds/0/kind").asText());
            assertEquals(4, links.at("/kinds/0/edges").asLong());
            assertEquals(1, links.at("/kinds/1/edges").asLong());
            assertEquals(2, links.at("/degree/p50").asLong());
            assertEquals(4, links.at("/degree/max").asLong());
            assertEquals(2, links.get("singleLink").asLong(), "D and 99");
            assertEquals(500, links.get("hubThreshold").asInt());
            assertEquals(0, links.get("hubs").asLong());

            JsonNode rel = p.get("relationships");
            assertEquals(1, rel.size(), "IMEI is the one other type bound in the same rows");
            assertEquals("imei", rel.at("/0/otherType").asText());
            assertEquals(0.4, rel.at("/0/oneToOneShare").asDouble(), "D and 99 carry exactly one IMEI out of 5 MSISDNs");
            assertEquals(3, rel.at("/0/maxPerEntity").asLong(), "B is seen with three IMEIs");
            assertEquals(6, rel.at("/0/threshold").asInt());
            assertEquals(0, rel.at("/0/entitiesAtOrAboveThreshold").asLong());

            JsonNode lineage = p.at("/lineage/0");
            assertEquals("calls_ds", lineage.get("dataset").asText());
            assertFalse(lineage.get("assessed").asBoolean(), "no audit trail attributes the Dataset: not assessed, never zero Files");
            assertFalse(lineage.has("files"));
        }
    }

    @Test
    void relationshipsPairOnlyColumnsOfTheSamePartyOfTheRow(@TempDir Path cfg, @TempDir Path root) throws Exception {
        subjects();
        try (EntityProfileFixture.Ctx c = open(cfg, root)) {
            String rel = "/inv/entity-types/msisdn/profile?sections=relationships,bindings";
            JsonNode before = noRaw(get(c, rel, OWNER));
            assertEquals(5, before.at("/relationships/0/entities").asLong(), "no party named: both numbers pair with the IMEI (A-D and the -99 placeholder)");
            assertEquals(3, before.at("/relationships/0/maxPerEntity").asLong(), "B is seen with I1, I2 and I3");
            assertFalse(binding(before.get("bindings"), "a_msisdn").has("party"));

            // a_msisdn and imei describe one party of the row (the caller and the handset), b_msisdn the other
            Map<String, Object> ds = new java.util.LinkedHashMap<>(EntityProfileFixture.callsDataset());
            ds.put("columns", List.of(Map.of("name", "a_msisdn", "classification", "MSISDN", "party", "A-side"),
                    Map.of("name", "b_msisdn", "classification", "MSISDN", "party", "b-side"),
                    Map.of("name", "imei", "classification", "IMEI", "party", " a-side ")));
            new ComponentStore(root.resolve("registry")).write("dataset", "calls_ds", ds);
            JsonNode after = noRaw(get(c, rel, OWNER));
            assertEquals("a-side", binding(after.get("bindings"), "a_msisdn").get("party").asText(), "trimmed, lower-case");
            assertEquals(3, after.at("/relationships/0/entities").asLong(), "only a_msisdn pairs with the IMEI: A, B and the placeholder");
            assertEquals(1, after.at("/relationships/0/maxPerEntity").asLong());
            assertEquals(1.0, after.at("/relationships/0/oneToOneShare").asDouble());

            String key = data(send(c, "POST", "/inv/entities/msisdn/resolve", "{\"key\":\"" + A + "\"}", OWNER)).get("key").asText();
            JsonNode related = data(get(c, "/inv/entities/msisdn/" + enc(key) + "/profile", OWNER)).get("related");
            for (JsonNode r : related)
                if ("imei".equals(r.get("type").asText()))
                    assertEquals(1, r.get("count").asLong(), "the Entity page pairs by party too: A's own handset, not B's (I3)");
        }
    }

    @Test
    void theSpaceLimitsAreHonouredAndAPartialReadSaysTruncated(@TempDir Path cfg, @TempDir Path root) throws Exception {
        subjects();
        try (EntityProfileFixture.Ctx c = open(cfg, root)) {
            settings(c, "hub_threshold: 3\nentity_profile:\n  relationship_threshold: 3\n  top_n: 1\n"
                    + "  timeline_days: 1\n  sentinel_share: 60\n");
            JsonNode p = noRaw(get(c, "/inv/entity-types/msisdn/profile", OWNER));
            assertEquals(1, p.at("/relationships/0/entitiesAtOrAboveThreshold").asLong(), "B carries 3 IMEIs, the stated threshold");
            assertEquals(1, p.at("/links/hubs").asLong(), "A has 4 neighbours, above the stated hub threshold 3");
            assertEquals(1, p.at("/shapes/length").size());
            assertEquals(13, p.at("/shapes/length/0/length").asInt(), "the most frequent length is kept");
            assertTrue(p.at("/shapes/truncated").asBoolean(), p.get("shapes").toString());
            assertEquals(1, p.at("/time/perDay").size());
            assertEquals("2026-10-03", p.at("/time/perDay/0/day").asText(), "the newest day is kept");
            for (JsonNode s : p.at("/volume/sentinels")) assertFalse("dominant".equals(s.get("kind").asText()), "no value is above 60 %");
            assertTrue(p.get("truncated").asBoolean(), "a section cut by a limit marks the profile partial");

            settings(c, "entity_profile:\n  shape_sample_rows: 4\n");
            JsonNode sampled = noRaw(get(c, "/inv/entity-types/msisdn/profile?sections=shapes", OWNER)).get("shapes");
            assertTrue(sampled.get("sampled").asBoolean(), sampled.toString());
            assertEquals(4, sampled.get("sampleRows").asLong());
            JsonNode cc = sampled.get("charClass");
            assertTrue(cc.get("digits").asLong() + cc.get("letters").asLong() + cc.get("mixed").asLong() <= 4, "11 keys, 4 read");
        }
    }

    @Test
    void sectionsNarrowTheReadAndAnUnknownOneIsRefused(@TempDir Path cfg, @TempDir Path root) throws Exception {
        subjects();
        try (EntityProfileFixture.Ctx c = open(cfg, root)) {
            JsonNode p = data(get(c, "/inv/entity-types/imei/profile?sections=shapes,volume", OWNER));
            assertTrue(p.has("shapes") && p.has("volume"));
            assertFalse(p.has("bindings") || p.has("time") || p.has("links"));
            assertEquals(0, p.at("/shapes/prefixLength").asInt(), "digits declares no safe prefix");
            assertEquals(0, p.at("/shapes/prefix").size());
            assertEquals(422, get(c, "/inv/entity-types/imei/profile?sections=shapes,raw", OWNER).statusCode());
        }
    }

    @Test
    void aTypeWithNothingToReadIsNotAssessedNeverZero(@TempDir Path cfg, @TempDir Path root) throws Exception {
        subjects();
        try (EntityProfileFixture.Ctx c = open(cfg, root)) {
            JsonNode p = data(get(c, "/inv/entity-types/wallet/profile", OWNER));
            assertEquals(0, p.get("bindings").size());
            for (String s : List.of("volume", "shapes", "time", "links")) {
                assertFalse(p.at("/" + s + "/assessed").asBoolean(), s);
                assertFalse(p.at("/" + s + "/reason").asText().isBlank(), s);
                assertFalse(p.get(s).has("distinct") || p.get(s).has("rows"), s + " reports no number nobody measured");
            }
            JsonNode imei = data(get(c, "/inv/entity-types/imei/profile?sections=links", OWNER));
            assertFalse(imei.at("/links/assessed").asBoolean(), "IMEI is no link end of any mapping");

            JsonNode stranger = data(get(c, "/inv/entity-types/msisdn/profile", STRANGER));
            assertEquals(0, stranger.get("bindings").size(), "R3: the owner's Dataset is not there for a stranger");
            assertFalse(stranger.at("/volume/assessed").asBoolean());
        }
    }

    @Test
    void lineageReadsTheFilesTheAuditTrailAttributes(@TempDir Path cfg, @TempDir Path root) throws Exception {
        subjects();
        CollectorCoveragePorts.forTest(new CollectorCoveragePort() {
            @Override
            public Optional<List<Delivery>> deliveries(com.gamma.spi.http.ApiContext api, Map<String, Object> dataset) {
                return Optional.empty();
            }

            @Override
            public Optional<List<FileEntry>> files(com.gamma.spi.http.ApiContext api, Map<String, Object> dataset) {
                return Optional.of(List.of(
                        new FileEntry("a.csv", "2026-10-01", 4, 0, "2026-10-02T10:00:00Z", "SUCCESS", "col1", "calls", "b1", List.of()),
                        new FileEntry("b.csv", "2026-10-03", 2, 1, "2026-10-04T10:00:00Z", "PARTIAL", "col1", "calls", "b2", List.of())));
            }
        });
        try (EntityProfileFixture.Ctx c = open(cfg, root)) {
            JsonNode l = data(get(c, "/inv/entity-types/msisdn/profile?sections=lineage", OWNER)).at("/lineage/0");
            assertTrue(l.get("assessed").asBoolean(), l.toString());
            assertEquals("[\"calls\"]", l.get("pipelines").toString());
            assertEquals("[\"col1\"]", l.get("collectors").toString());
            assertEquals(2, l.get("files").asInt());
            assertEquals(6, l.get("rows").asLong());
            assertEquals("2026-10-04T10:00:00Z", l.get("latestIngestedAt").asText());
            assertEquals(1, l.get("problemFiles").asInt());
        }
    }

    @Test
    void theProfileIsCachedByTheInputFileFingerprintAndANewFileInvalidatesIt(@TempDir Path cfg, @TempDir Path root) throws Exception {
        subjects();
        AtomicLong tick = new AtomicLong();
        InputFingerprintCache.forTest(() -> tick.addAndGet(60_000L), 30_000L);   // every read re-takes the fingerprint
        String store = "ep_cache_" + System.nanoTime();
        Path dir = Path.of("database").resolve(store);
        boolean hadDatabase = Files.isDirectory(Path.of("database"));
        try (EntityProfileFixture.Ctx c = open(cfg, root)) {
            new ComponentStore(root.resolve("registry")).write("dataset", "f_ds", Map.of("physicalRef", store, "owner", "analyst-1",
                    "shares", List.of(), "columns", List.of(Map.of("name", "s", "classification", "CELL"))));
            plant(dir.resolve("p1.parquet"), "('CELL-1'),('CELL-2')", 1_700_000_000_000L);
            String path = "/inv/entity-types/cell/profile?sections=volume";
            JsonNode first = data(get(c, path, OWNER));
            assertFalse(first.get("cached").asBoolean());
            assertEquals(2, first.at("/volume/rows").asLong());
            JsonNode second = data(get(c, path, OWNER));
            assertTrue(second.get("cached").asBoolean(), "same files: served from the cache");
            assertEquals(first.get("computedAt"), second.get("computedAt"));

            plant(dir.resolve("p2.parquet"), "('CELL-3')", 1_700_000_001_000L);
            JsonNode third = data(get(c, path, OWNER));
            assertFalse(third.get("cached").asBoolean(), "a new File invalidates the profile");
            assertEquals(3, third.at("/volume/rows").asLong());
            assertTrue(data(get(c, path, OWNER)).get("cached").asBoolean());
            JsonNode more = data(get(c, "/inv/entity-types/cell/profile?sections=volume,shapes", OWNER));
            assertFalse(more.get("cached").asBoolean(), "a section not yet computed is computed");
            assertTrue(data(get(c, "/inv/entity-types/cell/profile?sections=shapes", OWNER)).get("cached").asBoolean());
        } finally {
            rmTree(dir);
            if (!hadDatabase) Path.of("database").toFile().delete();
        }
    }

    @Test
    void everyGateRefuses(@TempDir Path cfg, @TempDir Path root, @TempDir Path cfg2) throws Exception {
        subjects();
        try (EntityProfileFixture.Ctx c = open(cfg, root)) {
            assertEquals(401, get(c, "/inv/entity-types", "Bearer nobody").statusCode());
            assertEquals(401, get(c, "/inv/entity-types/msisdn/profile", "Bearer nobody").statusCode());
            assertEquals(404, get(c, "/inv/entity-types/nosuch/profile", OWNER).statusCode());
        }
        try (EntityProfileFixture.Ctx c = open(cfg2, null)) {
            assertEquals(503, get(c, "/inv/entity-types", OWNER).statusCode(), "no write root");
            assertEquals(503, get(c, "/inv/entity-types/msisdn/profile", OWNER).statusCode());
        }
    }

    private static void plant(Path file, String values, long mtime) throws Exception {
        Files.createDirectories(file.getParent());
        DuckDbUtil.loadDriver();
        java.io.File db = DuckDbUtil.tempDbFile("ep_cache_");
        try (java.sql.Connection conn = DuckDbUtil.openConnection(db); java.sql.Statement st = conn.createStatement()) {
            st.execute("COPY (SELECT * FROM (VALUES " + values + ") AS v(s)) TO '" + file.toString().replace('\\', '/') + "' (FORMAT PARQUET)");
        } finally {
            DuckDbUtil.deleteTempDb(db);
        }
        Files.setLastModifiedTime(file, FileTime.fromMillis(mtime));
    }

    private static void rmTree(Path dir) throws Exception {
        if (!Files.exists(dir)) return;
        try (Stream<Path> s = Files.walk(dir)) {
            for (Path p : s.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p);
        }
    }

    @Test
    void aValueTheNormaliserRejectsStillDominatesByItsRawText(@TempDir Path cfg, @TempDir Path root) throws Exception {
        subjects();
        try (EntityProfileFixture.Ctx c = open(cfg, root)) {
            String name = "Ahmed Ali Osman";   // fails e164, so its normalised key is empty
            List<String> b = List.of(EntityProfileFixture.B, EntityProfileFixture.C, EntityProfileFixture.D, "+249912000005",
                    "+249912000006", "+249912000007");   // every b_msisdn distinct: only a_msisdn has a dominant value
            List<String> parts = new ArrayList<>();
            for (int i = 0; i < 6; i++)
                parts.add("('" + (i < 4 ? name : A) + "','" + b.get(i) + "','350000000000011','call',TIMESTAMP '2026-10-01 10:00:00')");
            String rows = String.join(",", parts);
            new com.gamma.pipeline.ViewStore(root.resolve("views")).write(new com.gamma.pipeline.ViewDefinition("calls_view", "flow-x",
                    List.of(), "SELECT a_msisdn, b_msisdn, imei, kind, ts FROM (VALUES " + rows + ") AS t(a_msisdn,b_msisdn,imei,kind,ts)",
                    "2026-10-15T00:00:00Z"));
            settings(c, "entity_profile:\n  sentinel_share: 50\n");
            HttpResponse<String> r = get(c, "/inv/entity-types/msisdn/profile?sections=volume", OWNER);
            assertFalse(r.body().contains(name), "D-EP3: the dominant raw value is never shown");
            List<String> dominant = new ArrayList<>();
            for (JsonNode s : data(r).at("/volume/sentinels"))
                if ("dominant".equals(s.get("kind").asText())) {
                    assertTrue(s.get("value").isNull());
                    dominant.add(s.get("column").asText() + ":" + s.get("rows").asLong());
                }
            assertEquals(List.of("a_msisdn:4"), dominant, "the name holds 4 of 6 a_msisdn values; A only 2 (33 %, below 50 %)");
        }
    }
}
