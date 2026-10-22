package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.gamma.spi.auth.Authenticators;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static com.gamma.control.EntityProfileFixture.A;
import static com.gamma.control.EntityProfileFixture.B;
import static com.gamma.control.EntityProfileFixture.C;
import static com.gamma.control.EntityProfileFixture.D;
import static com.gamma.control.EntityProfileFixture.OWNER;
import static com.gamma.control.EntityProfileFixture.STRANGER;
import static com.gamma.control.EntityProfileFixture.data;
import static com.gamma.control.EntityProfileFixture.noRaw;
import static com.gamma.control.EntityProfileFixture.open;
import static com.gamma.control.EntityProfileFixture.send;
import static com.gamma.control.EntityProfileFixture.settings;
import static com.gamma.control.EntityProfileFixture.subjects;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code POST /inv/entities/{type}/suggest} over REAL HTTP (LA-ONBOARD-1 OB-9, the Start box's type-ahead): the values of a
 * type that start a graph - only its link-end columns, busiest first, with their link rows and first / last seen - and how many
 * Entities match. A masked type lists nothing and is counted only for an empty prefix. Fixture rows (a_msisdn, b_msisdn):
 * A-B, A-C, A-D, B-C, -99-A, unknown-B; the saved view links a_msisdn to b_msisdn, imei is an attribute.
 */
class ControlApiEntitySuggestTest {

    @AfterEach
    void reset() {
        Authenticators.forTest(null);
    }

    private static HttpResponse<String> suggest(EntityProfileFixture.Ctx c, String type, String body, String auth) throws Exception {
        return send(c, "POST", "/inv/entities/" + type + "/suggest", body, auth);
    }

    private static List<String> values(JsonNode d) {
        List<String> out = new ArrayList<>();
        for (JsonNode i : d.get("items")) out.add(i.get("value").asText());
        return out;
    }

    @Test
    void listsTheLinkEndValuesBusiestFirstWithLinksAndFirstAndLastSeen(@TempDir Path cfg, @TempDir Path root) throws Exception {
        subjects();
        try (EntityProfileFixture.Ctx c = open(cfg, root)) {
            settings(c, "masking_mode: none\n");
            JsonNode d = data(suggest(c, "msisdn", "{\"prefix\":\"+2499120000\"}", OWNER));
            assertTrue(d.get("assessed").asBoolean(), d.toString());
            assertFalse(d.get("masked").asBoolean());
            assertEquals(List.of(A, B, C, D), values(d), "A 4 rows, B 3, C 2, D 1");
            assertEquals(4, d.at("/items/0/links").asLong());
            assertEquals("2026-10-01T10:00:00Z", d.at("/items/0/firstSeen").asText());
            assertEquals("2026-10-03T11:00:00Z", d.at("/items/0/lastSeen").asText());
            assertEquals(4, d.get("distinct").asLong());
            assertEquals("[\"calls_ds\"]", d.get("datasets").toString());

            assertEquals(List.of(A, B, C, D), values(data(suggest(c, "msisdn", "{\"prefix\":\"002499120000\"}", OWNER))),
                    "a prefix spelt another way matches through the type's normaliser");
            assertEquals(List.of(A, B), values(data(suggest(c, "msisdn", "{\"prefix\":\"+249\",\"limit\":2}", OWNER))));

            JsonNode all = data(suggest(c, "msisdn", "{}", OWNER));
            assertEquals(List.of(A, B, C, D, "-99"), values(all), "'unknown' normalises to nothing under e164: no Entity");
            assertEquals(5, all.get("distinct").asLong());
            assertEquals(List.of(A), values(data(suggest(c, "msisdn", "{\"prefix\":\"+249912000001\",\"dataset\":\"calls_ds\"}", OWNER))));
        }
    }

    @Test
    void onlyLinkEndsAndOnlyTheNamedDatasetStartAGraph(@TempDir Path cfg, @TempDir Path root) throws Exception {
        subjects();
        try (EntityProfileFixture.Ctx c = open(cfg, root)) {
            settings(c, "masking_mode: none\n");
            JsonNode imei = data(suggest(c, "imei", "{}", OWNER));
            assertFalse(imei.get("assessed").asBoolean(), "imei is an attribute column, never a link end");
            assertEquals(0, imei.get("items").size());
            assertTrue(imei.get("reason").asText().contains("no link column"), imei.toString());
            JsonNode other = data(suggest(c, "msisdn", "{\"dataset\":\"other_ds\"}", OWNER));
            assertFalse(other.get("assessed").asBoolean());
            assertEquals(0, other.get("items").size());
            JsonNode stranger = data(suggest(c, "msisdn", "{}", STRANGER));
            assertFalse(stranger.get("assessed").asBoolean(), "R3: no Dataset the stranger can view");
            assertEquals(0, stranger.get("items").size());
        }
    }

    @Test
    void aMaskedTypeListsNoValueAndIsCountedOnlyWhole(@TempDir Path cfg, @TempDir Path root) throws Exception {
        subjects();
        try (EntityProfileFixture.Ctx c = open(cfg, root)) {
            JsonNode byPrefix = noRaw(suggest(c, "msisdn", "{\"prefix\":\"+2499120000\"}", OWNER));
            assertTrue(byPrefix.get("masked").asBoolean());
            assertFalse(byPrefix.get("assessed").asBoolean());
            assertEquals(0, byPrefix.get("items").size());
            assertTrue(byPrefix.get("distinct").isNull(), "a count per prefix would spell a raw value out");
            JsonNode whole = noRaw(suggest(c, "msisdn", "{}", OWNER));
            assertEquals(0, whole.get("items").size());
            assertEquals(5, whole.get("distinct").asLong(), "the whole count is an aggregate - the start box warns with it");
        }
    }

    @Test
    void everyGateRefuses(@TempDir Path cfg, @TempDir Path root, @TempDir Path cfg2) throws Exception {
        subjects();
        try (EntityProfileFixture.Ctx c = open(cfg, root)) {
            assertEquals(401, suggest(c, "msisdn", "{}", "Bearer nobody").statusCode());
            assertEquals(404, suggest(c, "nosuch", "{}", OWNER).statusCode());
            for (String bad : List.of("{\"prefix\":7}", "{\"limit\":0}", "{\"limit\":21}", "{\"limit\":\"5\"}", "{\"dataset\":\" \"}",
                    "{\"prefix\":\"" + "9".repeat(513) + "\"}"))
                assertEquals(422, suggest(c, "msisdn", bad, OWNER).statusCode(), bad);
        }
        try (EntityProfileFixture.Ctx c = open(cfg2, null)) {
            assertEquals(503, suggest(c, "msisdn", "{}", OWNER).statusCode(), "no write root");
        }
    }
}
