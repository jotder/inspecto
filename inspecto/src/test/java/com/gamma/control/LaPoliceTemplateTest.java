package com.gamma.control;

import com.gamma.access.Roles;
import com.gamma.alert.AlertRule;
import com.gamma.entitystore.EntityTypes;
import com.gamma.entitystore.LinkAnalysisSettings;
import com.gamma.job.JobConfig;
import com.gamma.util.ToonHelper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The {@code la-police} Space Template's Advanced tier (showcase plan step 8): settings, personas, the serial-match Alert Rule
 * and its Jobs, read with the REAL loaders ({@link LinkAnalysisSettings}, {@link Roles}, {@link JobConfig},
 * {@link AlertRoutes#parseContent}). That the rule's measure sees only the series sellers is pinned by {@code LaPoliceLinksJobTest}
 * (inspecto-engine); that the gallery lists the template creatable with its roster by {@code ControlApiSpaceTemplatesTest}
 * ({@code galleryCountsTheDemoUserSignInATemplateNeeds}), and the unarmed-on-Basic mechanism by its
 * {@code aBasicTierTemplateShipsItsAlertRulesUnarmedOnBasic}.
 */
class LaPoliceTemplateTest {

    private static final Path CFG = Path.of("..", "spaces", "_templates", "la-police", "config");

    @Test
    void settingsLeaveMaskingToTheEditionHoldExpandsForASecondPersonAndMaskNoType() {
        LinkAnalysisSettings s = LinkAnalysisSettings.forRoot(CFG);
        assertNull(s.maskingMode(), "a stated masking_mode would mask the Basic tier's graph with no way to reveal it");
        assertEquals(2000, s.fourEyesFanOutAbove());
        assertEquals(10, s.drafts().maxOpen());
        List<EntityTypes.EntityType> types = s.effectiveEntityTypes();
        assertTrue(types.stream().anyMatch(t -> t.id().equals("police_entity")), "the link Datasets' Entity Type");
        assertTrue(types.stream().noneMatch(EntityTypes.EntityType::masked), "synthetic ids, nothing masked: no persona needs a Reveal");
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
        assertEquals(Set.of("admin", "police.analyst", "police.sio", "police.reviewer", "police.outsider"), caps.keySet());

        Set<String> analyst = caps.get("police.analyst");
        assertTrue(analyst.containsAll(Set.of(Roles.CAN_MANAGE_INCIDENTS, Roles.CAN_RUN_LINK_GRAPH_ANALYSIS, Roles.CAN_AUTHOR_ALERT_RULES)), analyst.toString());
        assertFalse(analyst.contains(Roles.CAN_APPROVE_LINK_EXPANSIONS), "the requester cannot also be the approver");
        Set<String> sio = caps.get("police.sio");
        assertTrue(sio.containsAll(Set.of(Roles.CAN_APPROVE_LINK_EXPANSIONS, Roles.CAN_OPERATE_RUNS)), sio.toString());
        assertFalse(sio.contains(Roles.CAN_RUN_LINK_GRAPH_ANALYSIS), "the approver does not run the analysis");
        assertTrue(caps.get("admin").contains(Roles.CAN_AUTHOR_WORKBENCH), "the admin edits the Link Analysis settings");
        for (String id : List.of("police.analyst", "police.sio", "police.reviewer", "police.outsider"))
            assertFalse(caps.get(id).contains(Roles.CAN_AUTHOR_WORKBENCH), id + " does not author");
        for (String id : List.of("police.reviewer", "police.outsider")) assertEquals(Set.of(), caps.get(id), id + " is read-only");
    }

    @Test
    void theSerialMatchRuleIsAValidDatasetRuleOverTheCountsTheChainBuilds(@TempDir Path data) throws Exception {
        Map<String, Object> body = ToonHelper.load(CFG.resolve("registry/alert-rules/police_repeat_seller_serial_matches.toon").toString());
        AlertRule rule = AlertRoutes.parseContent(CFG, () -> data, body);
        assertEquals("police_serial_match_counts_dataset", rule.dataset());
        assertEquals("max(repeat_seller_matches)", rule.measure());
        assertEquals("gte", rule.comparator());
        assertEquals(2.0, rule.threshold());
        assertFalse(rule.isInvestigationRule(), "a registry rule, not one bound to an Investigation");
        Map<String, Object> ds = ToonHelper.load(CFG.resolve("registry/datasets/police_serial_match_counts_dataset.toon").toString());
        assertEquals("police_serial_match_counts", ds.get("physicalRef"));

        JobConfig counts = JobConfig.load(CFG.resolve("jobs/police_serial_match_counts_job.toon").toString());
        assertEquals("sql.template", counts.type());
        assertEquals("police_serial_match_counts", counts.params().get("sink_dataset"));
        assertEquals("job.dataset.produced", counts.onSignal());
        assertTrue(counts.when().contains("$signal.dataset == police_links"), counts.when());
        JobConfig evaluate = JobConfig.load(CFG.resolve("jobs/police_serial_match_alerts_job.toon").toString());
        assertEquals("alert.evaluate", evaluate.type());
        assertTrue(evaluate.when().contains("$signal.dataset == police_serial_match_counts"), evaluate.when());
    }
}
