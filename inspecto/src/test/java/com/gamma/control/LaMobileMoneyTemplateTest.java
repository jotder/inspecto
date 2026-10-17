package com.gamma.control;

import com.gamma.access.Roles;
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
 * The {@code la-mobile-money} Space Template's settings, Jobs, personas and saved views, read with the REAL loaders
 * ({@link LinkAnalysisSettings}, {@link JobConfig}, {@link Roles}). The corpus promises are pinned by
 * {@code LaMobileMoneyGoldenTest} (inspecto-graph) and {@code MmLinksJobGoldenTest} (inspecto-engine).
 */
class LaMobileMoneyTemplateTest {

    private static final Path CFG = Path.of("..", "spaces", "_templates", "la-mobile-money", "config");

    @Test
    void settingsAreExplicitAndTheHubExpandWaitsForASecondPerson() {
        LinkAnalysisSettings s = LinkAnalysisSettings.forRoot(CFG);
        assertEquals("typed", s.maskingMode());
        assertEquals(100, s.fourEyesFanOutAbove());
        assertEquals(Boolean.TRUE, s.index().enabled());
        assertEquals(10, s.drafts().maxOpen());
    }

    @Test
    void theJobsFoldTheFeedsAndIndexTheLinkDataset() throws Exception {
        JobConfig links = JobConfig.load(CFG.resolve("jobs").resolve("mm_links_job.toon").toString());
        assertEquals("sql.template", links.type());
        assertEquals("mm_links", links.params().get("sink_dataset"));
        assertEquals("wallet_txn,wallet_master", links.params().get("sources"));
        JobConfig idx = JobConfig.load(CFG.resolve("jobs").resolve("la_index_build_job.toon").toString());
        assertEquals("la.index.build", idx.type());
        assertTrue(idx.cron() != null && !idx.cron().isBlank(), "the index build follows the mm_links cron");
        Map<String, String> p = idx.params();
        assertEquals("mm_links_dataset", p.get("dataset"));
        assertEquals("src", p.get("source_col"));
        assertEquals("dst", p.get("target_col"));
        assertEquals("kind", p.get("kind_col"));
        assertEquals("ts", p.get("time_col"));
        assertEquals("admin", p.get("owner"));
        assertEquals("la.detect", JobConfig.load(CFG.resolve("jobs").resolve("la_detect_job.toon").toString()).type());
        for (String ds : List.of("mm_links", "mm_kyc", "wallet_txn", "agent_master"))
            assertTrue(Files.exists(CFG.resolve("registry").resolve("datasets").resolve(ds + "_dataset.toon")), ds);
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
        assertEquals(Set.of("admin", "mm.analyst", "mm.manager", "mm.reviewer", "mm.outsider"), caps.keySet());

        Set<String> analyst = caps.get("mm.analyst");
        assertTrue(analyst.containsAll(Set.of(Roles.CAN_MANAGE_INCIDENTS, Roles.CAN_RUN_LINK_GRAPH_ANALYSIS,
                Roles.CAN_BUILD_LINK_INDEX, Roles.CAN_AUTHOR_ALERT_RULES)), analyst.toString());
        assertFalse(analyst.contains(Roles.CAN_APPROVE_LINK_EXPANSIONS), "the requester cannot also be the approver");
        assertFalse(analyst.contains(Roles.CAN_REVEAL_LINK_ENTITIES));
        Set<String> manager = caps.get("mm.manager");
        assertTrue(manager.containsAll(Set.of(Roles.CAN_APPROVE_LINK_EXPANSIONS, Roles.CAN_MANAGE_INCIDENTS, Roles.CAN_OPERATE_RUNS)), manager.toString());
        assertFalse(manager.contains(Roles.CAN_REVEAL_LINK_ENTITIES));
        assertTrue(caps.get("admin").contains(Roles.CAN_REVEAL_LINK_ENTITIES));
        assertTrue(caps.get("admin").contains(Roles.CAN_AUTHOR_WORKBENCH));
        for (String id : List.of("mm.reviewer", "mm.outsider")) assertEquals(Set.of(), caps.get(id), id);
    }

    @Test
    @SuppressWarnings("unchecked")
    void theSharedAdminIsDefinedExactlyAsInLaShowcase() throws Exception {
        // DemoUsers refuses a user id defined differently in two Spaces (409 ROSTER_CONFLICT), so `admin` must read the same.
        Path other = Path.of("..", "spaces", "_templates", "la-showcase", "config", "demo-users.toon");
        List<Map<String, Object>> mine = (List<Map<String, Object>>) ToonHelper.load(CFG.resolve("demo-users.toon").toString()).get("users");
        List<Map<String, Object>> theirs = (List<Map<String, Object>>) ToonHelper.load(other.toString()).get("users");
        assertEquals(theirs.stream().filter(u -> "admin".equals(u.get("id"))).findFirst().orElseThrow(),
                mine.stream().filter(u -> "admin".equals(u.get("id"))).findFirst().orElseThrow());
        Set<String> theirIds = new HashSet<>();
        theirs.forEach(u -> theirIds.add(String.valueOf(u.get("id"))));
        mine.stream().map(u -> String.valueOf(u.get("id"))).filter(id -> !id.equals("admin"))
                .forEach(id -> assertFalse(theirIds.contains(id), id + " must not collide with la-showcase"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void theSavedViewsProjectTheLinkDataset() throws Exception {
        for (String v : List.of("mm_money_trail", "mm_structuring_band", "mm_device_farm")) {
            Map<String, Object> view = ToonHelper.load(CFG.resolve("registry/link-analysis-views").resolve(v + ".toon").toString());
            Map<String, Object> proj = (Map<String, Object>) ((Map<String, Object>) view.get("query")).get("projection");
            assertEquals("mm_links_dataset", proj.get("datasetId"), v);
            assertEquals("src", proj.get("sourceCol"));
            assertEquals("kind", proj.get("linkKindCol"));
        }
    }
}
