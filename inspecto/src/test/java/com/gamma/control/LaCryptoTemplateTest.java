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
 * The {@code la-crypto} Space Template's Advanced tier (showcase plan step 8), read with the REAL loaders
 * ({@link LinkAnalysisSettings}, {@link Roles}, {@link JobConfig}, {@link AlertRoutes#parseContent}): the Demo User roster and
 * roles, the four-eyes threshold, and the deposit-traced-to-a-hack Alert Rule with the counts Job it reads and the Job that
 * evaluates it. The template stays {@code minEdition: basic}; that the rule fires on the shipped data only because of the
 * wallet labelled hacked, never on what its owner paid before the theft nor on the payroll wallet's chain, is pinned by
 * {@code CryptoLinksJobGoldenTest} (inspecto-engine), the seed by {@code tools/seed-la-crypto.test.mjs}.
 */
class LaCryptoTemplateTest {

    private static final Path CFG = Path.of("..", "spaces", "_templates", "la-crypto", "config");

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
        assertEquals(2000, s.fourEyesFanOutAbove());
        assertEquals(10, s.drafts().maxOpen());
        List<EntityTypes.EntityType> types = s.effectiveEntityTypes();
        assertTrue(types.stream().anyMatch(t -> t.id().equals("crypto_entity")), "the link Dataset's Entity Type");
        assertTrue(types.stream().noneMatch(EntityTypes.EntityType::masked), "invented addresses, accounts and people, nothing masked: no persona needs a Reveal");
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
        assertEquals(Set.of("admin", "crypto.analyst", "crypto.manager", "crypto.reviewer", "crypto.outsider"), caps.keySet());

        Set<String> analyst = caps.get("crypto.analyst");
        assertTrue(analyst.containsAll(Set.of(Roles.CAN_MANAGE_INCIDENTS, Roles.CAN_RUN_LINK_GRAPH_ANALYSIS, Roles.CAN_AUTHOR_ALERT_RULES)), analyst.toString());
        assertFalse(analyst.contains(Roles.CAN_APPROVE_LINK_EXPANSIONS), "the requester cannot also be the approver");
        Set<String> manager = caps.get("crypto.manager");
        assertTrue(manager.containsAll(Set.of(Roles.CAN_APPROVE_LINK_EXPANSIONS, Roles.CAN_OPERATE_RUNS)), manager.toString());
        assertFalse(manager.contains(Roles.CAN_RUN_LINK_GRAPH_ANALYSIS), "the approver does not run the analysis");
        assertFalse(manager.contains(Roles.CAN_REVEAL_LINK_ENTITIES));
        Set<String> admin = caps.get("admin");
        assertTrue(admin.containsAll(Set.of(Roles.CAN_REVEAL_LINK_ENTITIES, Roles.CAN_RUN_LINK_GRAPH_ANALYSIS, Roles.CAN_AUTHOR_WORKBENCH)), admin.toString());
        // every role a persona names resolves here; the shared admin's fraud-analyst reads as in la-utility, and every role named in
        // la-card too reads the same (both are composed into the Banking and finance industry Space, whose packer refuses a difference)
        Map<String, Roles.Def> table = Roles.effective(CFG);
        for (Map<String, Object> u : users)
            for (String r : String.valueOf(u.get("roles")).split(";"))
                assertTrue(table.containsKey(r.trim().toLowerCase()), u.get("id") + ": role " + r + " is not defined");
        assertEquals(Roles.effective(Path.of("..", "spaces", "_templates", "la-utility", "config")).get("fraud-analyst").capabilities(),
                table.get("fraud-analyst").capabilities());
        Map<String, Roles.Def> card = Roles.effective(Path.of("..", "spaces", "_templates", "la-card", "config"));
        for (String r : List.of("fraud-analyst", "la-viewer", "la-settings"))
            assertEquals(card.get(r).capabilities(), table.get(r).capabilities(), r + " reads as in la-card");
        for (String id : List.of("crypto.analyst", "crypto.manager", "crypto.reviewer", "crypto.outsider"))
            assertFalse(caps.get(id).contains(Roles.CAN_AUTHOR_WORKBENCH), id + " does not author");
        for (String id : List.of("crypto.reviewer", "crypto.outsider")) assertEquals(Set.of(), caps.get(id), id + " holds no capability of its own");
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
    void theDepositTracedToHackRuleIsAValidDatasetRuleOverTheCountsTheChainBuilds(@TempDir Path data) throws Exception {
        Map<String, Object> body = ToonHelper.load(CFG.resolve("registry/alert-rules/crypto_deposit_traced_to_hack.toon").toString());
        AlertRule rule = AlertRoutes.parseContent(CFG, () -> data, body);
        assertEquals("crypto_hack_trace_counts_dataset", rule.dataset());
        assertEquals("max(hack_traced_deposits)", rule.measure());
        assertEquals(List.of(), rule.by(), "crypto_hack_trace_counts is a Job output: a by column cannot be checked when the Space is created");
        assertEquals("gte", rule.comparator());
        assertEquals(1.0, rule.threshold());
        assertFalse(rule.isInvestigationRule(), "a registry rule, not one bound to an Investigation");
        Map<String, Object> ds = ToonHelper.load(CFG.resolve("registry/datasets/crypto_hack_trace_counts_dataset.toon").toString());
        assertEquals("crypto_hack_trace_counts", ds.get("physicalRef"));

        JobConfig counts = JobConfig.load(CFG.resolve("jobs/crypto_hack_trace_counts_job.toon").toString());
        assertEquals("sql.template", counts.type());
        assertEquals("crypto_hack_trace_counts", counts.params().get("sink_dataset"));
        assertEquals("5", counts.require("max_hops"), "the spec's 'within 5 hops'");
        assertEquals("job.dataset.produced", counts.onSignal());
        assertTrue(counts.when().contains("$signal.dataset == crypto_links"), counts.when());
        JobConfig evaluate = JobConfig.load(CFG.resolve("jobs/crypto_hack_trace_alerts_job.toon").toString());
        assertEquals("alert.evaluate", evaluate.type());
        assertEquals("job.dataset.produced", evaluate.onSignal());
        assertTrue(evaluate.when().contains("$signal.dataset == crypto_hack_trace_counts"), evaluate.when());
    }
}
