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
 * {@code ModuleSettings}). The corpus ranking is pinned by {@code LaShowcaseGoldenTest} in inspecto-la-graph.
 */
class LaShowcaseTemplateTest {

    private static final Path TPL = Path.of("..", "spaces", "_templates");
    private static final Path CFG = TPL.resolve("la-showcase").resolve("config");

    @Test
    void settingsAreExplicitAndTheHubExpandWaitsForASecondPerson() {
        LinkAnalysisSettings s = LinkAnalysisSettings.forRoot(CFG);
        assertEquals("typed", s.maskingMode());
        assertEquals(5, s.fourEyesFanOutAbove());
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
        assertFalse(analyst.contains(Roles.CAN_REVEAL_LINK_ENTITIES));

        Set<String> manager = caps.get("fm.manager");
        assertTrue(manager.containsAll(Set.of(Roles.CAN_APPROVE_LINK_EXPANSIONS, Roles.CAN_MANAGE_INCIDENTS)), manager.toString());
        assertFalse(manager.contains(Roles.CAN_REVEAL_LINK_ENTITIES));
        assertFalse(manager.contains(Roles.CAN_AUTHOR_WORKBENCH), "no silent write access");

        assertTrue(caps.get("admin").contains(Roles.CAN_REVEAL_LINK_ENTITIES));
        // the read-only personas: nothing that writes anything
        for (String id : List.of("ra.analyst", "demo.manager")) assertEquals(Set.of(), caps.get(id), id);
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
