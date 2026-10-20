package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.gamma.spi.auth.Authenticators;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.gamma.control.EntityProfileFixture.A;
import static com.gamma.control.EntityProfileFixture.OWNER;
import static com.gamma.control.EntityProfileFixture.RAW;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code GET /inv/entities/{type}/{key}/profile} and {@code POST /inv/entities/{type}/resolve} over REAL HTTP
 * (la-entity-profile-plan §3.2, §4.5; operator 2026-10-15: a raw identifier never travels in a URL). A masked type's Entity page
 * takes only the exploration alias, which the resolve route mints from a raw value sent in the BODY - the same alias a projection
 * hands out - and a raw value in its URL is refused without being repeated. An unmasked type takes its value.
 */
class ControlApiEntityProfileTest {

    @AfterEach
    void reset() {
        Authenticators.forTest(null);
    }

    private static HttpResponse<String> resolve(EntityProfileFixture.Ctx c, String type, String key, String auth) throws Exception {
        return send(c, "POST", "/inv/entities/" + type + "/resolve", "{\"key\":\"" + key + "\"}", auth);
    }

    /** A's key as its Entity page takes it - resolved through the body, never the URL. */
    private static String keyOfA(EntityProfileFixture.Ctx c) throws Exception {
        return noRaw(resolve(c, "msisdn", A, OWNER)).get("key").asText();
    }

    private static String profileOf(String type, String key) {
        return "/inv/entities/" + type + "/" + enc(key) + "/profile";
    }

    @Test
    void aMaskedEntityIsProfiledByItsAliasAndNamedOnlyByIt(@TempDir Path cfg, @TempDir Path root) throws Exception {
        subjects();
        try (EntityProfileFixture.Ctx c = open(cfg, root)) {
            String alias = keyOfA(c);
            assertTrue(alias.startsWith("masked:"), alias);
            HttpResponse<String> r = get(c, profileOf("msisdn", alias), OWNER);
            JsonNode p = noRaw(r);
            for (String raw : RAW) assertFalse(r.body().contains(raw), "not even the envelope: " + r.body());
            assertTrue(p.get("assessed").asBoolean(), p.toString());
            assertTrue(p.at("/entity/masked").asBoolean());
            assertEquals(alias, p.at("/entity/key").asText());
            assertEquals("2026-10-01T10:00:00Z", p.get("firstSeen").asText());
            assertEquals("2026-10-03T11:00:00Z", p.get("lastSeen").asText());
            assertEquals(2, p.get("activeDays").asInt());
            assertEquals(2, p.get("coveredDays").asInt());
            assertEquals("[{\"day\":\"2026-10-01\",\"rows\":2},{\"day\":\"2026-10-03\",\"rows\":2}]", p.get("perDay").toString());
            assertEquals(2, p.get("where").size());
            assertEquals("a_msisdn", p.at("/where/0/column").asText());
            assertEquals(3, p.at("/where/0/rows").asLong());
            assertEquals(1, p.at("/where/1/rows").asLong());
            assertEquals(4, p.at("/links/degree").asLong(), "B, C, D and 99");
            assertEquals(90, p.at("/links/percentile").asInt(), "mid-rank of degree 4 among 1, 1, 2, 2, 4");
            assertEquals("call", p.at("/links/kinds/0/kind").asText());
            assertEquals(3, p.at("/links/kinds/0/count").asLong());
            assertEquals("imei", p.at("/related/0/type").asText());
            assertEquals(2, p.at("/related/0/count").asLong(), "A is seen with I1 and I3");
            for (JsonNode k : p.at("/related/0/keys")) assertTrue(k.asText().startsWith("masked:"), k.toString());
            assertEquals("calls_ds", p.at("/dataAsOf/0/dataset").asText());
        }
    }

    /** LA-EDITIONS-RESIDUALS-1 (4a): a deep link's window scopes the Entity page's rows, days and span, and the answer echoes it. */
    @Test
    void aWindowScopesTheEntityProfileAndIsEchoed(@TempDir Path cfg, @TempDir Path root) throws Exception {
        subjects();
        try (EntityProfileFixture.Ctx c = open(cfg, root)) {
            String p = profileOf("msisdn", keyOfA(c));
            JsonNode w = noRaw(get(c, p + "?from=" + enc("2026-10-02T00:00:00Z") + "&to=" + enc("2026-10-04T00:00:00Z"), OWNER));
            assertEquals("2026-10-02T00:00:00Z", w.at("/window/from").asText());
            assertEquals("2026-10-04T00:00:00Z", w.at("/window/to").asText());
            assertEquals("[{\"day\":\"2026-10-03\",\"rows\":2}]", w.get("perDay").toString());
            assertEquals("2026-10-03T09:00:00Z", w.get("firstSeen").asText());
            assertEquals(1, w.get("activeDays").asInt());
            assertEquals(1, w.get("coveredDays").asInt(), "the days covered inside the window");
            assertEquals(1, w.at("/where/0/rows").asLong());
            JsonNode open = noRaw(get(c, p + "?from=" + enc("2026-10-03T10:00:00Z"), OWNER));
            assertTrue(open.at("/window/to").isNull());
            assertEquals("2026-10-03T11:00:00Z", open.get("firstSeen").asText());
            assertFalse(noRaw(get(c, p, OWNER)).has("window"), "no window, no echo");
            assertEquals(422, get(c, p + "?from=yesterday", OWNER).statusCode());
            assertEquals(422, get(c, p + "?from=" + enc("2026-10-04T00:00:00Z") + "&to=" + enc("2026-10-01T00:00:00Z"), OWNER).statusCode());
        }
    }

    @Test
    void resolveGivesTheExplorationAliasForAnySpellingAndForTheAliasItself(@TempDir Path cfg, @TempDir Path root) throws Exception {
        subjects();
        try (EntityProfileFixture.Ctx c = open(cfg, root)) {
            JsonNode proj = data(send(c, "POST", "/inv/projection/neighbors",
                    "{\"dataset\":\"calls_ds\",\"sourceCol\":\"a_msisdn\",\"targetCol\":\"b_msisdn\",\"value\":\"" + A + "\"}", OWNER));
            assertTrue(proj.at("/masking/masked").asBoolean(), proj.toString());
            Map<String, Integer> seen = new HashMap<>();   // A is the one end of every row of its own neighbourhood
            for (JsonNode r : proj.get("rows"))
                for (String end : List.of("source", "target")) seen.merge(r.get(end).asText(), 1, Integer::sum);
            String alias = seen.entrySet().stream().max(Map.Entry.comparingByValue()).orElseThrow().getKey();
            assertEquals(proj.get("rows").size(), seen.get(alias));

            JsonNode byRaw = noRaw(resolve(c, "msisdn", A, OWNER));
            assertEquals("msisdn", byRaw.get("type").asText());
            assertTrue(byRaw.get("masked").asBoolean());
            assertEquals(alias, byRaw.get("key").asText(), "one alias for one id across the exploration reads and the Entity page");
            assertEquals(alias, noRaw(resolve(c, "msisdn", alias, OWNER)).get("key").asText(), "an alias resolves to itself");
            assertEquals(alias, noRaw(resolve(c, "msisdn", "00" + A.substring(1), OWNER)).get("key").asText(),
                    "00249... normalises to the same e164 key, so to the same alias");
            JsonNode viaAlias = noRaw(get(c, profileOf("msisdn", alias), OWNER));
            assertEquals(3, viaAlias.at("/where/0/rows").asLong(), "the projection's alias opens A's Entity page");

            assertEquals(422, resolve(c, "msisdn", "masked:0000000000000000", OWNER).statusCode(), "an alias this server never minted");
            assertEquals(422, resolve(c, "msisdn", "unknown", OWNER).statusCode(), "a key that folds to nothing is no Entity");
            assertEquals(422, send(c, "POST", "/inv/entities/msisdn/resolve", "{}", OWNER).statusCode(), "no key");
            assertEquals(422, get(c, "/inv/entities/msisdn/masked:0000000000000000/profile", OWNER).statusCode());
        }
    }

    @Test
    void aRawValueInTheUrlOfAMaskedTypeIsRefusedWithoutRepeatingIt(@TempDir Path cfg, @TempDir Path root) throws Exception {
        subjects();
        try (EntityProfileFixture.Ctx c = open(cfg, root)) {
            for (String raw : List.of(A, A.substring(1), "00" + A.substring(1))) {
                HttpResponse<String> r = get(c, profileOf("msisdn", raw), OWNER);
                assertEquals(422, r.statusCode(), r.body());
                assertTrue(r.body().contains("/resolve"), "the refusal points at the resolve route: " + r.body());
                for (String any : RAW) assertFalse(r.body().contains(any), "the refusal repeats no raw id: " + r.body());
                assertFalse(r.body().contains(A.substring(4)), "nor its digits in another spelling: " + r.body());
            }
        }
    }

    @Test
    void anUnmaskedTypeTakesItsValueAndAMaskingOffSpaceShowsKeys(@TempDir Path cfg, @TempDir Path root) throws Exception {
        subjects();
        try (EntityProfileFixture.Ctx c = open(cfg, root)) {
            JsonNode cell = data(resolve(c, "cell", " cell-7 ", OWNER));
            assertFalse(cell.get("masked").asBoolean(), "cell is not masked under typed");
            assertEquals("CELL-7", cell.get("key").asText(), "the normalised value");
            assertEquals("CELL-7", data(get(c, profileOf("cell", "cell-7"), OWNER)).at("/entity/key").asText());

            settings(c, "masking_mode: none\n");
            JsonNode p = data(get(c, profileOf("msisdn", A), OWNER));
            assertFalse(p.at("/entity/masked").asBoolean());
            assertEquals(A, p.at("/entity/key").asText());
            assertTrue(p.at("/related/0/keys/0").asText().startsWith("35"), "an unmasked Space shows the related keys as they are");
            assertEquals(A, data(resolve(c, "msisdn", A, OWNER)).get("key").asText());

            JsonNode nowhere = data(get(c, profileOf("msisdn", "+249999999999"), OWNER));
            assertTrue(nowhere.get("assessed").asBoolean());
            assertEquals(0, nowhere.get("where").size());
            assertTrue(nowhere.get("firstSeen").isNull());
        }
    }

    @Test
    void everyGateRefuses(@TempDir Path cfg, @TempDir Path root, @TempDir Path cfg2) throws Exception {
        subjects();
        try (EntityProfileFixture.Ctx c = open(cfg, root)) {
            String alias = keyOfA(c);
            assertEquals(401, get(c, profileOf("msisdn", alias), "Bearer nobody").statusCode());
            assertEquals(401, resolve(c, "msisdn", A, "Bearer nobody").statusCode());
            assertEquals(404, get(c, profileOf("nosuch", alias), OWNER).statusCode());
            assertEquals(404, resolve(c, "nosuch", A, OWNER).statusCode());
            JsonNode stranger = data(get(c, profileOf("msisdn", alias), STRANGER));
            assertFalse(stranger.get("assessed").asBoolean(), "R3: no Dataset the stranger can view binds the type");
            assertFalse(stranger.has("where"), "nothing read, nothing reported");
            assertEquals(alias, stranger.at("/entity/key").asText(), "still never the raw key");
        }
        try (EntityProfileFixture.Ctx c = open(cfg2, null)) {
            assertEquals(503, get(c, profileOf("msisdn", "masked:0000000000000000"), OWNER).statusCode(), "no write root");
            assertEquals(503, resolve(c, "msisdn", A, OWNER).statusCode());
        }
    }
}
