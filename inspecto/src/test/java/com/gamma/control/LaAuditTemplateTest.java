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
 * The {@code la-audit} Space Template (vertical B of the multi-vertical showcase plan): settings, Jobs, personas,
 * Datasets and saved views, read with the REAL loaders. The planted stories are pinned by {@code LaAuditGoldenTest}
 * in inspecto-la-graph.
 */
class LaAuditTemplateTest {

    private static final Path CFG = Path.of("..", "spaces", "_templates", "la-audit", "config");

    @Test
    void settingsMaskPartiesNotLegalEntitiesAndFourEyesHoldsTheCfoExpand() {
        LinkAnalysisSettings s = LinkAnalysisSettings.forRoot(CFG);
        assertEquals("typed", s.maskingMode());
        assertEquals(30, s.fourEyesFanOutAbove());
        assertEquals(Boolean.TRUE, s.index().enabled());
        assertEquals(10, s.drafts().maxOpen());
        Map<String, EntityTypes.EntityType> types = new HashMap<>();
        for (EntityTypes.EntityType t : s.effectiveEntityTypes()) types.put(t.id(), t);
        assertEquals(Set.of("party", "legal_entity"), types.keySet());
        assertTrue(types.get("party").masked());
        assertFalse(types.get("legal_entity").masked());
        assertEquals(List.of("PARTY"), types.get("party").classifications());
    }

    @Test
    void theIndexBuildFollowsTheApLinksJobAndReadsTheLinkColumns() throws Exception {
        JobConfig c = JobConfig.load(CFG.resolve("jobs").resolve("la_index_build_job.toon").toString());
        assertEquals("la.index.build", c.type());
        assertEquals("job.dataset.produced", c.onSignal());
        assertTrue(c.when().contains("$signal.dataset == ap_links"), c.when());
        Map<String, String> p = c.params();
        assertEquals("ap_links_dataset", p.get("dataset"));
        assertEquals("src", p.get("source_col"));
        assertEquals("dst", p.get("target_col"));
        assertEquals("kind", p.get("kind_col"));
        assertEquals("ts", p.get("time_col"));
        assertEquals("audit.admin", p.get("owner"));
        assertEquals("la.detect", JobConfig.load(CFG.resolve("jobs").resolve("la_detect_job.toon").toString()).type());
    }

    @Test
    @SuppressWarnings("unchecked")
    void theLinkJobsSinkTheDatasetsTheViewsReadAndExcludePlaceholders() throws Exception {
        String sql = Files.readString(CFG.resolve("jobs").resolve("ap_links_job.toon"));
        for (String kind : List.of("'approved'", "'paid_to'", "'reports_to'", "'shares_account'", "'shares_address'"))
            assertTrue(sql.contains(kind), kind);
        assertTrue(sql.contains("'PENDING'"), "the placeholder account is excluded");
        assertTrue(sql.contains("x.m < y.m"), "shared-attribute pairs once");
        assertTrue(Files.readString(CFG.resolve("jobs").resolve("gl_links_job.toon")).contains("'journal_flow'"));
        Path ds = CFG.resolve("registry").resolve("datasets");
        for (String sink : List.of("ap_links", "ap_approval_links", "ap_identity_links", "ap_org_links", "gl_links", "ap_duplicate_payments")) {
            JobConfig j = JobConfig.load(CFG.resolve("jobs").resolve(sink + "_job.toon").toString());
            assertEquals("sql.template", j.type());
            assertEquals(sink, j.params().get("sink_dataset"));
            assertTrue(Files.exists(ds.resolve(sink + "_dataset.toon")), sink + " Dataset");
        }
        Path views = CFG.resolve("registry").resolve("link-analysis-views");
        for (String v : List.of("ap_approval_graph", "ap_vendor_identity", "gl_intercompany")) {
            Map<String, Object> view = ToonHelper.load(views.resolve(v + ".toon").toString());
            Map<String, Object> proj = (Map<String, Object>) ((Map<String, Object>) view.get("query")).get("projection");
            String dsId = String.valueOf(proj.get("datasetId"));
            assertTrue(Files.exists(ds.resolve(dsId.replace("_dataset", "") + "_dataset.toon")), dsId);
            assertEquals("src", proj.get("sourceCol"));
            assertEquals("kind", proj.get("linkKindCol"));
        }
        // every link Dataset carries a real event-day column; the endpoints are classified (typed masking)
        for (String sink : List.of("ap_links", "gl_links")) {
            Map<String, Object> d = ToonHelper.load(ds.resolve(sink + "_dataset.toon").toString());
            assertEquals("event_date", d.get("eventDate"));
            List<Map<String, Object>> cols = (List<Map<String, Object>>) d.get("columns");
            assertEquals(sink.equals("ap_links") ? "PARTY" : "LEGAL_ENTITY", cols.get(0).get("classification"));
            assertEquals(cols.get(0).get("classification"), cols.get(1).get("classification"));
        }
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
        assertEquals(Set.of("audit.admin", "audit.lead", "audit.manager", "audit.committee", "audit.outsider"), caps.keySet());

        Set<String> lead = caps.get("audit.lead");
        assertTrue(lead.containsAll(Set.of(Roles.CAN_MANAGE_INCIDENTS, Roles.CAN_RUN_LINK_GRAPH_ANALYSIS, Roles.CAN_BUILD_LINK_INDEX,
                Roles.CAN_AUTHOR_ALERT_RULES)), lead.toString());
        assertFalse(lead.contains(Roles.CAN_APPROVE_LINK_EXPANSIONS), "the requester cannot also be the approver");
        Set<String> manager = caps.get("audit.manager");
        assertTrue(manager.containsAll(Set.of(Roles.CAN_APPROVE_LINK_EXPANSIONS, Roles.CAN_OPERATE_RUNS)), manager.toString());
        assertFalse(manager.contains(Roles.CAN_REVEAL_LINK_ENTITIES));
        assertTrue(caps.get("audit.admin").contains(Roles.CAN_REVEAL_LINK_ENTITIES));
        assertTrue(caps.get("audit.admin").contains(Roles.CAN_AUTHOR_WORKBENCH));
        for (String id : List.of("audit.lead", "audit.manager", "audit.committee", "audit.outsider"))
            assertFalse(caps.get(id).contains(Roles.CAN_AUTHOR_WORKBENCH), id);
        for (String id : List.of("audit.committee", "audit.outsider")) assertEquals(Set.of(), caps.get(id), id + " is read-only");
        for (String id : List.of("audit.admin", "audit.lead", "audit.committee", "audit.outsider"))
            assertFalse(caps.get(id).contains(Roles.CAN_OPERATE_RUNS), id + " does not operate runs");
    }

    @Test
    void everyFeedHasAPipelineSchemaAndDatasetAndTheSharesNameOnlyDemoUsers() throws Exception {
        for (String feed : List.of("ap_invoice", "ap_approval", "vendor_master", "employee_master", "gl_journal")) {
            Map<String, Object> p = ToonHelper.load(CFG.resolve("audit").resolve(feed + "_pipeline.toon").toString());
            assertEquals(feed, p.get("name"));
            assertTrue(Files.exists(CFG.resolve("audit").resolve(feed + "_schema.toon")), feed + " schema");
            assertTrue(Files.exists(CFG.resolve("registry").resolve("datasets").resolve(feed + "_dataset.toon")), feed + " Dataset");
        }
        String ds = Files.readString(CFG.resolve("registry").resolve("datasets").resolve("ap_links_dataset.toon"));
        for (String id : ds.lines().filter(l -> l.startsWith("  user,")).map(l -> l.split(",")[1]).toList())
            assertTrue(id.startsWith("audit."), id + " is not an audit Demo User");
    }
}
