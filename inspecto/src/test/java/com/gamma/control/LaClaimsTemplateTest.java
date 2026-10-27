package com.gamma.control;

import com.gamma.access.Roles;
import com.gamma.entitystore.EntityTypes;
import com.gamma.entitystore.LinkAnalysisSettings;
import com.gamma.job.JobConfig;
import com.gamma.util.ToonHelper;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The {@code la-claims} Space Template's settings, Jobs, personas, Datasets and saved views, read with the REAL loaders
 * ({@link LinkAnalysisSettings}, {@link JobConfig}, {@link Roles}). The corpus plants are pinned by {@code LaClaimsGoldenTest}
 * (inspecto-graph) and the Job SQL by {@code LaClaimsLinksJobTest} (inspecto-engine).
 */
class LaClaimsTemplateTest {

    private static final Path TPL = Path.of("..", "spaces", "_templates", "la-claims");
    private static final Path CFG = TPL.resolve("config");

    @Test
    void settingsMaskTypedWithOneMaskedPartyTypeAndTheIndexOn() {
        LinkAnalysisSettings s = LinkAnalysisSettings.forRoot(CFG);
        assertEquals("typed", s.maskingMode());
        assertEquals(2000, s.fourEyesFanOutAbove());
        assertEquals(Boolean.TRUE, s.index().enabled());
        assertEquals(10, s.drafts().maxOpen());
        assertEquals(List.of("http://127.0.0.1:8099", "http://localhost:8099"), s.embed().frameAncestorsInForce(), "the partner portal origin may frame the embed link");
        List<EntityTypes.EntityType> types = s.effectiveEntityTypes();
        assertEquals(1, types.size());
        assertEquals("party", types.get(0).id());
        assertTrue(types.get(0).masked());
        assertEquals(List.of("PARTY"), types.get(0).classifications());
    }

    @Test
    @SuppressWarnings("unchecked")
    void theLinkDatasetDeclaresTheContractColumnsAnEventDayAndTheMaskedClassification() throws Exception {
        Map<String, Object> d = ToonHelper.load(CFG.resolve("registry/datasets/claims_links_dataset.toon").toString());
        assertEquals("claims_links", d.get("physicalRef"));
        assertEquals("event_date", d.get("eventDate"));
        List<Map<String, Object>> cols = (List<Map<String, Object>>) d.get("columns");
        assertEquals(List.of("src", "dst", "kind", "ts", "amount", "event_date", "via"), cols.stream().map(c -> c.get("name")).toList());
        assertEquals("PARTY", cols.get(0).get("classification"));
        assertEquals("PARTY", cols.get(1).get("classification"));
        for (String view : List.of("claims_network", "claims_provider_money", "claims_shared_identity")) {
            Map<String, Object> v = ToonHelper.load(CFG.resolve("registry/link-analysis-views/" + view + ".toon").toString());
            Map<String, Object> p = (Map<String, Object>) ((Map<String, Object>) v.get("query")).get("projection");
            assertEquals("claims_links_dataset", p.get("datasetId"));
            assertEquals("src", p.get("sourceCol"));
            assertEquals("dst", p.get("targetCol"));
            assertEquals("kind", p.get("linkKindCol"));
        }
        Map<String, Object> money = ToonHelper.load(CFG.resolve("registry/link-analysis-views/claims_provider_money.toon").toString());
        assertEquals("force", money.get("layout"), "dagre drew this wide, shallow graph as a vertical sliver");
    }

    @Test
    void theIndexBuildJobFiresWhenTheLinksJobProducesItsDatasetAndDetectIsOnACron() throws Exception {
        JobConfig c = JobConfig.load(CFG.resolve("jobs/la_index_build_job.toon").toString());
        assertEquals("la.index.build", c.type());
        assertEquals("job.dataset.produced", c.onSignal());
        assertTrue(c.when().contains("$signal.dataset == claims_links"), c.when());
        Map<String, String> p = c.params();
        assertEquals("claims_links_dataset", p.get("dataset"));
        assertEquals("src", p.get("source_col"));
        assertEquals("dst", p.get("target_col"));
        assertEquals("kind", p.get("kind_col"));
        assertEquals("ts", p.get("time_col"));
        assertEquals("admin", p.get("owner"));
        JobConfig d = JobConfig.load(CFG.resolve("jobs/la_detect_job.toon").toString());
        assertEquals("la.detect", d.type());
        assertTrue(d.cron() != null && d.cron().startsWith("*/"));
        JobConfig links = JobConfig.load(CFG.resolve("jobs/claims_links_job.toon").toString());
        assertEquals("sql.template", links.type());
        assertEquals("claims_links", links.params().get("sink_dataset"));
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
        Map<String, Set<String>> caps = new HashMap<>();
        for (Map<String, Object> u : users) caps.put(String.valueOf(u.get("id")), held(String.valueOf(u.get("roles"))));
        assertEquals(Set.of("admin", "siu.analyst", "siu.manager", "partner.reviewer", "outsider"), caps.keySet());

        Set<String> analyst = caps.get("siu.analyst");
        assertTrue(analyst.containsAll(Set.of(Roles.CAN_MANAGE_INCIDENTS, Roles.CAN_RUN_LINK_GRAPH_ANALYSIS, Roles.CAN_BUILD_LINK_INDEX,
                Roles.CAN_AUTHOR_ALERT_RULES)), analyst.toString());
        assertFalse(analyst.contains(Roles.CAN_APPROVE_LINK_EXPANSIONS), "the requester cannot also be the approver");
        Set<String> manager = caps.get("siu.manager");
        assertTrue(manager.containsAll(Set.of(Roles.CAN_APPROVE_LINK_EXPANSIONS, Roles.CAN_OPERATE_RUNS)), manager.toString());
        assertFalse(manager.contains(Roles.CAN_REVEAL_LINK_ENTITIES));
        assertTrue(caps.get("admin").contains(Roles.CAN_REVEAL_LINK_ENTITIES), "only the admin reveals masked claimants");
        assertTrue(caps.get("admin").contains(Roles.CAN_AUTHOR_WORKBENCH), "the admin edits the Link Analysis settings");
        for (String id : List.of("siu.analyst", "siu.manager", "partner.reviewer", "outsider"))
            assertFalse(caps.get(id).contains(Roles.CAN_AUTHOR_WORKBENCH), id + " does not author");
        for (String id : List.of("partner.reviewer", "outsider")) assertEquals(Set.of(), caps.get(id), id + " is read-only");
    }

    @Test
    void everyFeedHasAPipelineASchemaAndADatasetAndTheTemplateShipsItsExtract() throws Exception {
        for (String feed : List.of("claim", "claim_party", "policy", "provider_bill", "accident_location", "provider_directory")) {
            Map<String, Object> p = ToonHelper.load(CFG.resolve("claims/" + feed + "_pipeline.toon").toString());
            assertEquals(feed, p.get("name"));
            assertTrue(Files.exists(CFG.resolve("claims/" + feed + "_schema.toon")), feed + " schema");
            assertTrue(Files.exists(CFG.resolve("registry/datasets/" + feed + "_dataset.toon")), feed + " dataset");
            try (var files = Files.list(TPL.resolve("data/inbox/" + feed))) {
                assertEquals(1, files.filter(f -> f.toString().endsWith(".csv")).count(), feed + " extract");
            }
        }
    }
}
