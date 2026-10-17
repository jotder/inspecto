package com.gamma.control;

import com.gamma.access.Roles;
import com.gamma.entitystore.LinkAnalysisSettings;
import com.gamma.job.JobConfig;
import com.gamma.util.ToonHelper;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * LA-DEMO-SEED-1 (DR-S2, S3, S6, S7): the {@code la-showcase} Space Template's settings, Jobs, personas and its
 * feature-off companion, read with the REAL loaders ({@link LinkAnalysisSettings}, {@link JobConfig}, {@link Roles},
 * {@code ModuleSettings}). The corpus ranking is pinned by {@code LaShowcaseGoldenTest} in inspecto-graph.
 */
class LaShowcaseTemplateTest {

    private static final Path TPL = Path.of("..", "spaces", "_templates");
    private static final Path CFG = TPL.resolve("la-showcase").resolve("config");

    @Test
    void settingsAreExplicitAndTheHubExpandWaitsForASecondPerson() {
        LinkAnalysisSettings s = LinkAnalysisSettings.forRoot(CFG);
        assertEquals("typed", s.maskingMode());
        assertEquals(2000, s.fourEyesFanOutAbove());
        assertEquals(Boolean.TRUE, s.index().enabled());
        assertEquals(10, s.drafts().maxOpen());
    }

    @Test
    void theIndexBuildJobFiresOnAPipelineCommitOfTheMuleFeedOnly() throws Exception {
        JobConfig c = JobConfig.load(CFG.resolve("jobs").resolve("la_index_build_job.toon").toString());
        assertEquals("la.index.build", c.type());
        assertEquals("pipeline.commit", c.onSignal());
        assertTrue(c.when().contains("$signal.pipeline == mule_transfers"), c.when());
        Map<String, String> p = c.params();
        assertEquals("mule_transfers_dataset", p.get("dataset"));
        assertEquals("PAYER_ACCOUNT", p.get("source_col"));
        assertEquals("PAYEE_ACCOUNT", p.get("target_col"));
        assertEquals("CHANNEL", p.get("kind_col"));
        assertEquals("BOOKED_AT", p.get("time_col"));
        assertEquals("admin", p.get("owner"));
        assertEquals("true", p.get("allow_full"));
        JobConfig d = JobConfig.load(CFG.resolve("jobs").resolve("la_detect_job.toon").toString());
        assertEquals("la.detect", d.type());
        assertTrue(d.cron() != null && d.cron().startsWith("*/"), "a short cron");
    }

    private static Set<String> held(String personaRoles) {
        Map<String, Roles.Def> table = Roles.effective(CFG);
        Set<String> caps = new HashSet<>();
        for (String r : personaRoles.split(";")) {
            Roles.Def d = table.get(r.trim().toLowerCase());
            if (d != null) caps.addAll(d.capabilities());
        }
        return caps;
    }

    @Test
    @SuppressWarnings("unchecked")
    void personasHoldExactlyTheirCapabilities() throws Exception {
        List<Map<String, Object>> users = (List<Map<String, Object>>) ToonHelper.load(CFG.resolve("demo-users.toon").toString()).get("users");
        Map<String, Set<String>> caps = new java.util.HashMap<>();
        for (Map<String, Object> u : users) caps.put(String.valueOf(u.get("id")), held(String.valueOf(u.get("roles"))));
        assertEquals(Set.of("admin", "fm.analyst", "fm.manager", "ra.analyst", "demo.manager"), caps.keySet());

        Set<String> analyst = caps.get("fm.analyst");
        assertTrue(analyst.containsAll(Set.of(Roles.CAN_MANAGE_INCIDENTS, Roles.CAN_RUN_LINK_GRAPH_ANALYSIS,
                Roles.CAN_BUILD_LINK_INDEX, Roles.CAN_AUTHOR_ALERT_RULES)), analyst.toString());
        assertFalse(analyst.contains(Roles.CAN_APPROVE_LINK_EXPANSIONS), "the requester cannot also be the approver");
        assertTrue(analyst.contains(Roles.CAN_REVEAL_LINK_ENTITIES), "the analyst may reveal (91a03666c, 2026-10-16: Preview LA demo); each reveal is audited");

        Set<String> manager = caps.get("fm.manager");
        assertTrue(manager.containsAll(Set.of(Roles.CAN_APPROVE_LINK_EXPANSIONS, Roles.CAN_MANAGE_INCIDENTS)), manager.toString());
        assertFalse(manager.contains(Roles.CAN_REVEAL_LINK_ENTITIES));
        assertFalse(manager.contains(Roles.CAN_AUTHOR_WORKBENCH), "no silent write access");
        // D1: POST /jobs/{name}/trigger and POST /alerts/evaluate are gated canOperateRuns; the demo's Run button is
        // fm.manager's. The seed 'admin' role deliberately lacks it (administering is not operating), and so does
        // every other persona of this template - least privilege.
        assertTrue(manager.contains(Roles.CAN_OPERATE_RUNS), "fm.manager presses Run on the demo's Jobs: " + manager);
        for (String id : List.of("admin", "fm.analyst", "ra.analyst", "demo.manager"))
            assertFalse(caps.get(id).contains(Roles.CAN_OPERATE_RUNS), id + " does not operate runs");

        assertTrue(caps.get("admin").contains(Roles.CAN_REVEAL_LINK_ENTITIES));
        // Operator 2026-10-12: the demo's admin edits Settings > Link Analysis (PUT /settings/link-analysis needs
        // canAuthorWorkbench) through the template role 'la-settings'; no other persona gets it.
        assertTrue(caps.get("admin").contains(Roles.CAN_AUTHOR_WORKBENCH), "admin edits the Link Analysis settings");
        for (String id : List.of("fm.analyst", "fm.manager", "ra.analyst", "demo.manager"))
            assertFalse(caps.get(id).contains(Roles.CAN_AUTHOR_WORKBENCH), id + " does not author");
        // the read-only personas: nothing that writes anything
        for (String id : List.of("ra.analyst", "demo.manager")) assertEquals(Set.of(), caps.get(id), id);
    }

    /** GEO scene (operator 2026-10-12): synthetic locations + geocoded legs, their Datasets, three saved Geo Views. */
    @Test
    @SuppressWarnings("unchecked")
    void theGeoSceneIsCarriedAndItsViewsPointAtCarriedDatasets() throws Exception {
        Path ds = CFG.resolve("registry").resolve("datasets");
        for (String store : List.of("mule_account_locations", "mule_geo_legs")) {
            Map<String, Object> p = ToonHelper.load(CFG.resolve("mule").resolve(store + "_pipeline.toon").toString());
            assertEquals(store, p.get("name"));
            assertTrue(Files.exists(CFG.resolve("mule").resolve(store + "_schema.toon")), store + " schema");
        }
        Map<String, Object> loc = ToonHelper.load(ds.resolve("mule_account_locations_dataset.toon").toString());
        assertEquals("mule_account_locations/database", loc.get("physicalRef"));
        List<Map<String, Object>> cols = (List<Map<String, Object>>) loc.get("columns");
        assertEquals("ACCOUNT", cols.get(0).get("name"));
        assertEquals("ACCOUNT", cols.get(0).get("classification"), "the key carries the ACCOUNT classification");
        for (String scene : List.of("ring", "cashout")) {
            Map<String, Object> d = ToonHelper.load(ds.resolve("mule_" + scene + "_legs_dataset.toon").toString());
            assertEquals("mule_geo_legs", d.get("sourceName"));
            assertTrue(String.valueOf(d.get("sql")).contains("SCENE = '" + scene + "'"), String.valueOf(d.get("sql")));
        }
        Path views = CFG.resolve("registry").resolve("geo-map-views");
        Map<String, String> dataset = Map.of("mule_ring_route_map", "mule_ring_legs_dataset",
                "mule_cashout_till_map", "mule_cashout_legs_dataset");
        for (var e : dataset.entrySet()) {
            Map<String, Object> v = ToonHelper.load(views.resolve(e.getKey() + ".toon").toString());
            Map<String, Object> routes = (Map<String, Object>) ((Map<String, Object>) v.get("query")).get("routes");
            assertEquals(e.getValue(), routes.get("datasetId"));
            assertTrue(Files.exists(ds.resolve(e.getValue() + ".toon")), e.getValue());
        }
        Map<String, Object> pts = ToonHelper.load(views.resolve("mule_account_locations_map.toon").toString());
        assertEquals("mule_account_locations_dataset",
                ((Map<String, Object>) ((Map<String, Object>) pts.get("query")).get("projection")).get("datasetId"));
        assertFalse(Files.exists(TPL.resolve("la-showcase-off").resolve("config").resolve("registry")),
                "the feature-off companion carries no geo views or datasets");
    }

    @Test
    void theCompanionSwitchesTheFeatureOffAndSharesThePersonas() throws Exception {
        Path off = TPL.resolve("la-showcase-off").resolve("config");
        assertEquals(Set.of("geoLink"), ModuleSettings.disabled(off));
        assertEquals(Files.readString(CFG.resolve("demo-users.toon")), Files.readString(off.resolve("demo-users.toon")),
                "one id must mean one person across Spaces (DemoUsers.all refuses a different definition)");
        assertEquals(Files.readString(CFG.resolve("roles.toon")), Files.readString(off.resolve("roles.toon")));
        assertEquals(Set.of(), ModuleSettings.disabled(CFG));
    }
}
