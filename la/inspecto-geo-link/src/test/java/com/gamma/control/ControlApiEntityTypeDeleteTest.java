package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.gamma.spi.auth.Authenticators;
import com.gamma.spi.auth.Subject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static com.gamma.control.EntityProfileFixture.data;
import static com.gamma.control.EntityProfileFixture.get;
import static com.gamma.control.EntityProfileFixture.open;
import static com.gamma.control.EntityProfileFixture.send;
import static com.gamma.control.EntityProfileFixture.settings;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code DELETE /inv/entity-types/{id}} over REAL HTTP (LA-ENTITY-TYPE-RETIRE-1, delete if unused): every gate in order - 401, 403
 * without {@code canAuthorWorkbench}, 503 with no write root, 404 for a type not in force, 409 for each kind of use (a Dataset column,
 * a live Entity List, an Investigation) and for the last type - and the 200 that states the rest. The fixture's {@code calls_ds}
 * (owned by analyst-1) classifies columns MSISDN and IMEI, so {@code msisdn} and {@code imei} are in use from the start.
 */
class ControlApiEntityTypeDeleteTest {

    /** analyst-1 with every capability the arrangement needs. */
    static final String AUTHOR = "Bearer author";
    /** analyst-1 without canAuthorWorkbench. */
    static final String ANALYST = "Bearer analyst";
    /** analyst-3: may delete a type, but cannot view calls_ds. */
    static final String OTHER_AUTHOR = "Bearer other-author";

    @BeforeEach
    void arm() {
        Authenticators.forTest(ex -> switch (String.valueOf(ex.getRequestHeaders().getFirst("Authorization"))) {
            case AUTHOR -> Optional.of(new Subject("analyst-1", Set.of("canAuthorWorkbench", "canManageIncidents")));
            case ANALYST -> Optional.of(new Subject("analyst-1", Set.of("canManageIncidents")));
            case OTHER_AUTHOR -> Optional.of(new Subject("analyst-3", Set.of("canAuthorWorkbench")));
            default -> Optional.empty();
        });
    }

    @AfterEach
    void reset() {
        Authenticators.forTest(null);
    }

    private static HttpResponse<String> delete(EntityProfileFixture.Ctx c, String path, String auth) throws Exception {
        return send(c, "DELETE", path, null, auth);
    }

    private static List<String> typesInForce(EntityProfileFixture.Ctx c) throws Exception {
        List<String> ids = new ArrayList<>();
        for (JsonNode t : data(get(c, "/inv/entity-types", AUTHOR)).get("items")) ids.add(t.get("id").asText());
        return ids;
    }

    @Test
    void anUnusedTypeIsDeletedAndTheRestAreStated(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (EntityProfileFixture.Ctx c = open(cfg, root)) {
            JsonNode out = data(delete(c, "/inv/entity-types/handset", AUTHOR));
            assertEquals("handset", out.get("deleted").asText());
            assertEquals(com.gamma.entitystore.EntityTypes.DEFAULTS.size() - 1, out.get("entityTypesInForce").size());
            List<String> ids = typesInForce(c);
            assertEquals(com.gamma.entitystore.EntityTypes.DEFAULTS.size() - 1, ids.size(), ids.toString());
            assertFalse(ids.contains("handset"));
            String toon = Files.readString(root.resolve("link-analysis.toon"));
            assertTrue(toon.contains("entity_types"), "deleting an inherited default states the rest: " + toon);
            assertFalse(toon.contains("handset"), toon);
            assertEquals(404, delete(c, "/inv/entity-types/handset", AUTHOR).statusCode(), "gone: a second delete finds nothing");
        }
    }

    @Test
    void theStatedListKeepsEveryOtherKey(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (EntityProfileFixture.Ctx c = open(cfg, root)) {
            settings(c, """
                    masking_mode: none
                    hub_threshold: 77
                    entity_types[2]:
                      - id: msisdn
                        label: Phone number
                        normaliser: e164
                        masked: true
                        classifications[1]: MSISDN
                      - id: sweep_test
                        label: sweep test
                        normaliser: default
                        masked: false
                        classifications[1]: SWEEP
                    """);
            data(delete(c, "/inv/entity-types/sweep_test", AUTHOR));
            assertEquals(List.of("msisdn"), typesInForce(c));
            String toon = Files.readString(root.resolve("link-analysis.toon"));
            assertTrue(toon.contains("hub_threshold: 77") && toon.contains("masking_mode: none"), toon);
        }
    }

    @Test
    void aTypeADatasetColumnClaimsIsRefusedNamingTheColumn(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (EntityProfileFixture.Ctx c = open(cfg, root)) {
            HttpResponse<String> r = delete(c, "/inv/entity-types/msisdn", AUTHOR);
            assertEquals(409, r.statusCode(), r.body());
            assertTrue(r.body().contains("calls_ds.a_msisdn") && r.body().contains("calls_ds.b_msisdn"), r.body());
            HttpResponse<String> blind = delete(c, "/inv/entity-types/imei", OTHER_AUTHOR);
            assertEquals(409, blind.statusCode(), "a column in a Dataset the caller cannot view still uses the type: " + blind.body());
            assertTrue(blind.body().contains("1 in Datasets you cannot view"), blind.body());
            assertFalse(blind.body().contains("calls_ds"), "never names a Dataset the caller cannot view: " + blind.body());
            assertTrue(typesInForce(c).contains("msisdn"));
        }
    }

    @Test
    void aTypeALiveEntityListIsOfIsRefusedUntilTheListIsRetired(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (EntityProfileFixture.Ctx c = open(cfg, root)) {
            HttpResponse<String> created = send(c, "POST", "/entity-lists", "{\"id\":\"tills\",\"title\":\"Tills\",\"purpose\":\"exclusion\","
                    + "\"entityType\":\"wallet\",\"reason\":\"r\"}", AUTHOR);
            assertEquals(201, created.statusCode(), created.body());
            HttpResponse<String> r = delete(c, "/inv/entity-types/wallet", AUTHOR);
            assertEquals(409, r.statusCode(), r.body());
            assertTrue(r.body().contains("Entity List tills"), r.body());
            assertEquals(200, send(c, "POST", "/entity-lists/tills/retire", "{\"reason\":\"done\"}", AUTHOR).statusCode());
            assertEquals(200, delete(c, "/inv/entity-types/wallet", AUTHOR).statusCode(), "a retired list can seed nothing again");
        }
    }

    @Test
    void aTypeAnInvestigationNamesIsRefused(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (EntityProfileFixture.Ctx c = open(cfg, root)) {
            settings(c, "masking_mode: none\n");
            assertEquals(200, send(c, "POST", "/inv/investigations", "{\"purpose\":\"test\",\"id\":\"case-a\",\"dataset\":\"calls_ds\","
                    + "\"sourceCol\":\"a_msisdn\",\"targetCol\":\"b_msisdn\",\"linkKindCol\":\"kind\"}", AUTHOR).statusCode());
            HttpResponse<String> seeded = send(c, "POST", "/inv/investigations/case-a/ops",
                    "{\"op\":\"seed\",\"ids\":[\"ACC-1\"],\"entityType\":\"account\"}", AUTHOR);
            assertEquals(200, seeded.statusCode(), seeded.body());
            HttpResponse<String> r = delete(c, "/inv/entity-types/account", AUTHOR);
            assertEquals(409, r.statusCode(), r.body());
            assertTrue(r.body().contains("1 Investigation"), r.body());
            // an endpoint type sealed in the header alone (ONB-LA-ENDPOINT-TYPE-1) is a use too, before any step
            assertEquals(200, send(c, "POST", "/inv/investigations", "{\"purpose\":\"test\",\"id\":\"case-b\",\"dataset\":\"calls_ds\","
                    + "\"sourceCol\":\"a_msisdn\",\"targetCol\":\"b_msisdn\",\"linkKindCol\":\"kind\",\"entityType\":\"cell\"}", AUTHOR).statusCode());
            HttpResponse<String> header = delete(c, "/inv/entity-types/cell", AUTHOR);
            assertEquals(409, header.statusCode(), header.body());
            assertTrue(header.body().contains("1 Investigation"), header.body());
            // a seed keeps the type as sent, and masking reads it case-insensitively: so does the use check
            assertEquals(200, send(c, "POST", "/inv/investigations/case-a/ops",
                    "{\"op\":\"seed\",\"ids\":[\"H-1\"],\"entityType\":\"HANDSET\"}", AUTHOR).statusCode());
            HttpResponse<String> cased = delete(c, "/inv/entity-types/handset", AUTHOR);
            assertEquals(409, cased.statusCode(), cased.body());
            // a step only an open Draft holds is a use too
            HttpResponse<String> forked = send(c, "POST", "/inv/investigations/case-b/drafts", "{}", AUTHOR);
            assertEquals(201, forked.statusCode(), forked.body());
            String draft = EntityProfileFixture.JSON.readTree(forked.body()).at("/data/draftId").asText();
            HttpResponse<String> drafted = send(c, "POST", "/inv/investigations/case-b/drafts/" + draft + "/ops",
                    "{\"op\":\"seed\",\"ids\":[\"S-1\"],\"entityType\":\"imsi\"}", AUTHOR);
            assertEquals(200, drafted.statusCode(), drafted.body());
            HttpResponse<String> inDraft = delete(c, "/inv/entity-types/imsi", AUTHOR);
            assertEquals(409, inDraft.statusCode(), inDraft.body());
            assertTrue(inDraft.body().contains("1 Investigation"), inDraft.body());
            assertEquals(200, delete(c, "/inv/entity-types/agent", AUTHOR).statusCode(), "a type no step names is free");
        }
    }

    @Test
    void theLastTypeIsRefused(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (EntityProfileFixture.Ctx c = open(cfg, root)) {
            settings(c, """
                    entity_types[1]:
                      - id: sweep_test
                        label: sweep test
                        normaliser: default
                        masked: false
                        classifications[1]: SWEEP
                    """);
            HttpResponse<String> r = delete(c, "/inv/entity-types/sweep_test", AUTHOR);
            assertEquals(409, r.statusCode(), r.body());
            assertTrue(r.body().contains("last Entity Type"), r.body());
        }
    }

    @Test
    void everyGateBeforeTheUseCheck(@TempDir Path cfg, @TempDir Path root, @TempDir Path cfg2) throws Exception {
        try (EntityProfileFixture.Ctx c = open(cfg, root)) {
            assertEquals(401, delete(c, "/inv/entity-types/handset", "Bearer nobody").statusCode());
            assertEquals(403, delete(c, "/inv/entity-types/handset", ANALYST).statusCode(), "deleting needs canAuthorWorkbench");
            assertEquals(404, delete(c, "/inv/entity-types/no_such_type", AUTHOR).statusCode());
            assertTrue(typesInForce(c).contains("handset"), "a refused delete changes nothing");
        }
        try (EntityProfileFixture.Ctx c = open(cfg2, null)) {
            assertEquals(503, delete(c, "/inv/entity-types/handset", AUTHOR).statusCode(), "no write root");
        }
    }
}
