package com.gamma.control;

import com.gamma.access.Roles;
import com.gamma.alert.AlertRule;
import com.gamma.entitystore.LinkAnalysisSettings;
import com.gamma.job.JobConfig;
import com.gamma.util.ToonHelper;
import org.junit.jupiter.api.Test;

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
 * The {@code la-utility} Space Template's Advanced tier (showcase plan step 8), read with the REAL loaders
 * ({@link LinkAnalysisSettings}, {@link JobConfig}, {@link Roles}, {@link AlertRule}): the Demo User roster and roles, the
 * four-eyes threshold, and the transformer-loss Alert Rule with the Job that evaluates it. The template stays
 * {@code minEdition: basic}; the corpus and the rule's breaching keys are pinned by {@code UtilityLinksJobGoldenTest}
 * (inspecto-engine) and the seed by {@code tools/seed-la-utility.test.mjs}.
 */
class LaUtilityTemplateTest {

    private static final Path CFG = Path.of("..", "spaces", "_templates", "la-utility", "config");

    @Test
    void settingsLeaveMaskingToTheEditionAndHoldAnUnboundedExpandForASecondPerson() {
        LinkAnalysisSettings s = LinkAnalysisSettings.forRoot(CFG);
        assertNull(s.maskingMode(), "a stated masking_mode would mask the Basic tier's graph with no way to reveal it");
        String before = System.getProperty("auth.mode");
        try {
            System.clearProperty("auth.mode");
            assertEquals("none", s.effectiveMaskingMode(), "Basic (no sign-in) shows the ids");
            System.setProperty("auth.mode", "demo");
            assertEquals("typed", s.effectiveMaskingMode(), "a sign-in build masks by Entity Type");
        } finally {
            if (before == null) System.clearProperty("auth.mode"); else System.setProperty("auth.mode", before);
        }
        assertEquals(2000, s.fourEyesFanOutAbove());
        assertEquals(10, s.drafts().maxOpen());
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
        assertEquals(Set.of("admin", "rp.analyst", "rp.manager", "rp.reviewer", "rp.outsider"), caps.keySet());

        Set<String> analyst = caps.get("rp.analyst");
        assertTrue(analyst.containsAll(Set.of(Roles.CAN_MANAGE_INCIDENTS, Roles.CAN_RUN_LINK_GRAPH_ANALYSIS, Roles.CAN_AUTHOR_ALERT_RULES)), analyst.toString());
        assertFalse(analyst.contains(Roles.CAN_APPROVE_LINK_EXPANSIONS), "the requester cannot also be the approver");
        Set<String> manager = caps.get("rp.manager");
        assertTrue(manager.containsAll(Set.of(Roles.CAN_APPROVE_LINK_EXPANSIONS, Roles.CAN_OPERATE_RUNS)), manager.toString());
        assertFalse(manager.contains(Roles.CAN_REVEAL_LINK_ENTITIES));
        assertTrue(caps.get("admin").contains(Roles.CAN_REVEAL_LINK_ENTITIES));
        for (String id : List.of("rp.reviewer", "rp.outsider")) assertEquals(Set.of(), caps.get(id), id + " holds no capability of its own");
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
    }

    @Test
    void theLossRuleIsEvaluatedAfterEachLossBuild() throws Exception {
        AlertRule rule = AlertRule.fromMap(ToonHelper.load(CFG.resolve("registry/alert-rules/utility_transformer_loss.toon").toString()));
        assertEquals("transformer_loss_dataset", rule.dataset());
        assertEquals("max(loss_pct)", rule.measure());
        assertEquals(List.of(), rule.by(), "transformer_loss is a Job output: a by column cannot be checked when the Space is created");
        assertEquals("gte", rule.comparator());
        assertEquals(15.0, rule.threshold());
        JobConfig eval = JobConfig.load(CFG.resolve("jobs/utility_loss_alerts_job.toon").toString());
        assertEquals("alert.evaluate", eval.type());
        assertEquals("job.dataset.produced", eval.onSignal());
        assertTrue(eval.when().contains("transformer_loss"), eval.when());
    }
}
