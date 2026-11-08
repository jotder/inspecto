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
 * The {@code la-benefits} Space Template's Advanced tier (showcase plan step 8), read with the REAL loaders
 * ({@link LinkAnalysisSettings}, {@link Roles}, {@link JobConfig}, {@link AlertRoutes#parseContent}): the Demo User roster and
 * roles, the four-eyes threshold, and the payments-to-the-deceased Alert Rule with the counts Job it reads and the Job that
 * evaluates it. The template stays {@code minEdition: basic}; that the rule fires on the shipped data only because of the nine
 * pensions still paid after the death notices, and never on the death notified 40 days late, is pinned by
 * {@code BenefitsLinksJobGoldenTest} (inspecto-engine), the seed by {@code tools/seed-la-benefits.test.mjs}.
 */
class LaBenefitsTemplateTest {

    private static final Path CFG = Path.of("..", "spaces", "_templates", "la-benefits", "config");
    private static final Path CARD = Path.of("..", "spaces", "_templates", "la-card", "config");

    @Test
    void settingsLeaveMaskingToTheEditionHoldAnUnboundedExpandForASecondPersonAndMaskNoType() {
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
        // the Public sector industry Space composes la-benefits with la-procurement: the packer refuses a setting stated differently
        assertEquals(2000, s.fourEyesFanOutAbove());
        assertEquals(10, s.drafts().maxOpen());
        LinkAnalysisSettings card = LinkAnalysisSettings.forRoot(CARD);
        assertEquals(card.drafts(), s.drafts(), "the Drafts limits read as in every other showcase");
        List<EntityTypes.EntityType> types = s.effectiveEntityTypes();
        assertTrue(types.stream().anyMatch(t -> t.id().equals("public_entity")), "the link Dataset's Entity Type");
        assertTrue(types.stream().noneMatch(EntityTypes.EntityType::masked), "synthetic CIT- ids, nothing masked: no persona needs a Reveal");
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
        assertEquals(Set.of("admin", "benefits.analyst", "benefits.manager", "benefits.reviewer", "benefits.outsider"), caps.keySet());

        Set<String> analyst = caps.get("benefits.analyst");
        assertTrue(analyst.containsAll(Set.of(Roles.CAN_MANAGE_INCIDENTS, Roles.CAN_RUN_LINK_GRAPH_ANALYSIS, Roles.CAN_AUTHOR_ALERT_RULES)), analyst.toString());
        assertFalse(analyst.contains(Roles.CAN_APPROVE_LINK_EXPANSIONS), "the requester cannot also be the approver");
        Set<String> manager = caps.get("benefits.manager");
        assertTrue(manager.containsAll(Set.of(Roles.CAN_APPROVE_LINK_EXPANSIONS, Roles.CAN_OPERATE_RUNS)), manager.toString());
        assertFalse(manager.contains(Roles.CAN_RUN_LINK_GRAPH_ANALYSIS), "the approver does not run the analysis");
        assertFalse(manager.contains(Roles.CAN_REVEAL_LINK_ENTITIES));
        Set<String> admin = caps.get("admin");
        assertTrue(admin.containsAll(Set.of(Roles.CAN_REVEAL_LINK_ENTITIES, Roles.CAN_RUN_LINK_GRAPH_ANALYSIS, Roles.CAN_AUTHOR_WORKBENCH)), admin.toString());
        // every role a persona names resolves here
        Map<String, Roles.Def> table = Roles.effective(CFG);
        for (Map<String, Object> u : users)
            for (String r : String.valueOf(u.get("roles")).split(";"))
                assertTrue(table.containsKey(r.trim().toLowerCase()), u.get("id") + ": role " + r + " is not defined");
        for (String id : List.of("benefits.analyst", "benefits.manager", "benefits.reviewer", "benefits.outsider"))
            assertFalse(caps.get(id).contains(Roles.CAN_AUTHOR_WORKBENCH), id + " does not author");
        for (String id : List.of("benefits.reviewer", "benefits.outsider")) assertEquals(Set.of(), caps.get(id), id + " holds no capability of its own");
    }

    @Test
    @SuppressWarnings("unchecked")
    void theSharedAdminAndRolesAreDefinedExactlyAsInTheOtherShowcases() throws Exception {
        // DemoUsers refuses a user id defined differently in two Spaces (409 ROSTER_CONFLICT), so `admin` must read the same
        Path other = Path.of("..", "spaces", "_templates", "la-showcase", "config", "demo-users.toon");
        List<Map<String, Object>> mine = (List<Map<String, Object>>) ToonHelper.load(CFG.resolve("demo-users.toon").toString()).get("users");
        List<Map<String, Object>> theirs = (List<Map<String, Object>>) ToonHelper.load(other.toString()).get("users");
        assertEquals(theirs.stream().filter(u -> "admin".equals(u.get("id"))).findFirst().orElseThrow(),
                mine.stream().filter(u -> "admin".equals(u.get("id"))).findFirst().orElseThrow());
        // an industry download merges roles.toon by name and refuses a role two use cases define differently
        Map<String, Roles.Def> table = Roles.effective(CFG);
        Map<String, Roles.Def> card = Roles.effective(CARD);
        for (String shared : List.of("fraud-analyst", "la-viewer", "la-settings"))
            assertEquals(card.get(shared).capabilities(), table.get(shared).capabilities(), shared + " reads as in la-card");
    }

    @Test
    void thePaymentsToTheDeceasedRuleIsAValidDatasetRuleOverTheCountsTheChainBuilds(@TempDir Path data) throws Exception {
        Map<String, Object> body = ToonHelper.load(CFG.resolve("registry/alert-rules/benefits_paid_to_the_deceased_after_notice.toon").toString());
        AlertRule rule = AlertRoutes.parseContent(CFG, () -> data, body);
        assertEquals("benefits_deceased_counts_dataset", rule.dataset());
        assertEquals("max(deceased_claims_paid_after_notice)", rule.measure());
        assertEquals(List.of(), rule.by(), "benefits_deceased_counts is a Job output: a by column cannot be checked when the Space is created");
        assertEquals("gte", rule.comparator());
        assertEquals(1.0, rule.threshold());
        assertFalse(rule.isInvestigationRule(), "a registry rule, not one bound to an Investigation");
        Map<String, Object> ds = ToonHelper.load(CFG.resolve("registry/datasets/benefits_deceased_counts_dataset.toon").toString());
        assertEquals("benefits_deceased_counts", ds.get("physicalRef"));

        JobConfig counts = JobConfig.load(CFG.resolve("jobs/benefits_deceased_counts_job.toon").toString());
        assertEquals("sql.template", counts.type());
        assertEquals("benefits_deceased_counts", counts.params().get("sink_dataset"));
        assertEquals("30", counts.require("grace_days_after_notice"));
        assertEquals("job.dataset.produced", counts.onSignal());
        assertTrue(counts.when().contains("$signal.dataset == benefits_links"), counts.when());
        JobConfig evaluate = JobConfig.load(CFG.resolve("jobs/benefits_deceased_alerts_job.toon").toString());
        assertEquals("alert.evaluate", evaluate.type());
        assertEquals("job.dataset.produced", evaluate.onSignal());
        assertTrue(evaluate.when().contains("$signal.dataset == benefits_deceased_counts"), evaluate.when());
    }
}
