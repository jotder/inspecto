package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.gamma.spi.auth.Authenticators;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code GET /inv/entities/{type}/{key}/profile} over REAL HTTP (la-entity-profile-plan §3.2, §4.5): one Entity by raw value or by
 * the exploration alias - resolved through the same masking the exploration reads use, so the alias a projection hands out opens
 * the same Entity - and never a raw key back for a masked type.
 */
class ControlApiEntityProfileTest {

    @AfterEach
    void reset() {
        Authenticators.forTest(null);
    }

    private static final String ENTITY_A = "/inv/entities/msisdn/" + enc(A) + "/profile";

    @Test
    void aMaskedEntityByRawKeyIsProfiledAndNamedOnlyByItsAlias(@TempDir Path cfg, @TempDir Path root) throws Exception {
        subjects();
        try (EntityProfileFixture.Ctx c = open(cfg, root)) {
            JsonNode p = noRaw(get(c, ENTITY_A, OWNER));
            assertTrue(p.get("assessed").asBoolean(), p.toString());
            assertTrue(p.at("/entity/masked").asBoolean());
            assertTrue(p.at("/entity/key").asText().startsWith("masked:"), p.toString());
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
            assertEquals("dataset", p.at("/links/from").asText());
            assertEquals(90, p.at("/links/percentile").asInt(), "mid-rank of degree 4 among 1, 1, 2, 2, 4");
            assertEquals("call", p.at("/links/kinds/0/kind").asText());
            assertEquals(3, p.at("/links/kinds/0/count").asLong());
            assertEquals("imei", p.at("/related/0/type").asText());
            assertEquals(2, p.at("/related/0/count").asLong(), "A is seen with I1 and I3");
            for (JsonNode k : p.at("/related/0/keys")) assertTrue(k.asText().startsWith("masked:"), k.toString());
            assertEquals("calls_ds", p.at("/dataAsOf/0/dataset").asText());
        }
    }

    @Test
    void theExplorationAliasOpensTheSameEntityAndAnotherSpellingIsTheSameKey(@TempDir Path cfg, @TempDir Path root) throws Exception {
        subjects();
        try (EntityProfileFixture.Ctx c = open(cfg, root)) {
            JsonNode proj = data(send(c, "POST", "/inv/projection/neighbors",
                    "{\"dataset\":\"calls_ds\",\"sourceCol\":\"a_msisdn\",\"targetCol\":\"b_msisdn\",\"value\":\"" + A + "\"}", OWNER));
            assertTrue(proj.at("/masking/masked").asBoolean(), proj.toString());
            java.util.Map<String, Integer> seen = new java.util.HashMap<>();   // A is the one end of every row of its own neighbourhood
            for (JsonNode r : proj.get("rows"))
                for (String end : java.util.List.of("source", "target")) seen.merge(r.get(end).asText(), 1, Integer::sum);
            String alias = seen.entrySet().stream().max(java.util.Map.Entry.comparingByValue()).orElseThrow().getKey();
            assertEquals(proj.get("rows").size(), seen.get(alias));

            JsonNode byRaw = noRaw(get(c, ENTITY_A, OWNER));
            assertEquals(alias, byRaw.at("/entity/key").asText(), "one alias for one id across the exploration reads and this page");
            JsonNode byAlias = noRaw(get(c, "/inv/entities/msisdn/" + alias + "/profile", OWNER));
            assertEquals(alias, byAlias.at("/entity/key").asText());
            assertEquals(byRaw.get("where"), byAlias.get("where"));
            assertEquals(byRaw.get("perDay"), byAlias.get("perDay"));

            JsonNode otherSpelling = noRaw(get(c, "/inv/entities/msisdn/" + enc("00" + A.substring(1)) + "/profile", OWNER));
            assertEquals(byRaw.get("where"), otherSpelling.get("where"), "00249... normalises to the same e164 key");

            assertEquals(422, get(c, "/inv/entities/msisdn/masked:0000000000000000/profile", OWNER).statusCode(),
                    "an alias this server never minted");
            assertEquals(422, get(c, "/inv/entities/msisdn/unknown/profile", OWNER).statusCode(),
                    "a key that folds to nothing is no Entity");
        }
    }

    @Test
    void withMaskingOffTheKeyIsShownAndAnEntitySeenNowhereIsAnEmptyWhere(@TempDir Path cfg, @TempDir Path root) throws Exception {
        subjects();
        try (EntityProfileFixture.Ctx c = open(cfg, root)) {
            settings(c, "masking_mode: none\n");
            JsonNode p = data(get(c, ENTITY_A, OWNER));
            assertFalse(p.at("/entity/masked").asBoolean());
            assertEquals(A, p.at("/entity/key").asText());
            assertTrue(p.at("/related/0/keys/0").asText().startsWith("35"), "an unmasked Space shows the related keys as they are");

            JsonNode nowhere = data(get(c, "/inv/entities/msisdn/" + enc("+249999999999") + "/profile", OWNER));
            assertTrue(nowhere.get("assessed").asBoolean());
            assertEquals(0, nowhere.get("where").size());
            assertTrue(nowhere.get("firstSeen").isNull());
        }
    }

    @Test
    void everyGateRefuses(@TempDir Path cfg, @TempDir Path root, @TempDir Path cfg2) throws Exception {
        subjects();
        try (EntityProfileFixture.Ctx c = open(cfg, root)) {
            assertEquals(401, get(c, ENTITY_A, "Bearer nobody").statusCode());
            assertEquals(404, get(c, "/inv/entities/nosuch/" + enc(A) + "/profile", OWNER).statusCode());
            JsonNode stranger = data(get(c, ENTITY_A, STRANGER));
            assertFalse(stranger.get("assessed").asBoolean(), "R3: no Dataset the stranger can view binds the type");
            assertFalse(stranger.has("where"), "nothing read, nothing reported");
            assertTrue(stranger.at("/entity/key").asText().startsWith("masked:"), "still never the raw key");
        }
        try (EntityProfileFixture.Ctx c = open(cfg2, null)) {
            assertEquals(503, get(c, ENTITY_A, OWNER).statusCode(), "no write root");
        }
    }
}
