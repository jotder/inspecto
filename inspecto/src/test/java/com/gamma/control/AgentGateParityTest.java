package com.gamma.control;

import com.gamma.access.CapabilityManifest;
import com.gamma.access.Roles;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The agent gate's three buckets against the capability manifest (AGENT-KIT-1 AK-2a, plan §4.1, §7 "a write route that
 * skips the hold"): every non-GET entry of {@link CapabilityManifest#ENTRIES} and {@link CapabilityManifest#EXEMPTIONS}
 * lands in exactly one bucket; the held bucket is exactly {@link PendingChanges#REPLAYABLE} less the never-list; the
 * allowed bucket names only routes that exist and change nothing governed; the never-list is refused whatever else
 * would place it. A new write route is refused to an agent until someone places it.
 */
class AgentGateParityTest {

    /** Method + pattern → where the manifest records it: a capability, or an exemption's category. */
    private static Map<String, String> manifest() {
        Map<String, String> m = new LinkedHashMap<>();
        for (CapabilityManifest.Entry e : CapabilityManifest.ENTRIES) m.put(e.method() + " " + e.pattern(), e.capability());
        for (CapabilityManifest.Exemption e : CapabilityManifest.EXEMPTIONS)
            m.putIfAbsent(e.method() + " " + e.pattern(), "exempt:" + e.category());
        return m;
    }

    private static AgentGate.Bucket bucket(String key) {
        int sp = key.indexOf(' ');
        return AgentGate.bucket(key.substring(0, sp), key.substring(sp + 1));
    }

    @Test
    void everyNonGetManifestRouteLandsInExactlyOneBucket() {
        Map<AgentGate.Bucket, Set<String>> by = new LinkedHashMap<>();
        for (AgentGate.Bucket b : AgentGate.Bucket.values()) by.put(b, new TreeSet<>());
        for (String key : manifest().keySet()) {
            if (key.startsWith("GET ")) continue;
            by.get(bucket(key)).add(key);
        }
        int total = by.values().stream().mapToInt(Set::size).sum();
        assertEquals(manifest().keySet().stream().filter(k -> !k.startsWith("GET ")).count(), total);
        for (AgentGate.Bucket a : AgentGate.Bucket.values())
            for (AgentGate.Bucket b : AgentGate.Bucket.values())
                if (a != b) {
                    Set<String> both = new TreeSet<>(by.get(a));
                    both.retainAll(by.get(b));
                    assertTrue(both.isEmpty(), a + " and " + b + " share " + both);
                }
        assertFalse(by.get(AgentGate.Bucket.HELD).isEmpty());
        assertTrue(by.get(AgentGate.Bucket.REFUSED).size() > by.get(AgentGate.Bucket.HELD).size(), "default deny");
    }

    @Test
    void theHeldBucketIsTheReplayableListLessTheNeverList() {
        Map<String, String> m = manifest();
        Set<String> expected = new TreeSet<>();
        for (String key : PendingChanges.REPLAYABLE) {
            assertTrue(m.containsKey(key), "REPLAYABLE names a route the manifest does not list: " + key);
            int sp = key.indexOf(' ');
            if (!AgentGate.never(key.substring(0, sp), key.substring(sp + 1)) && !AgentGate.NOT_HELD.contains(key))
                expected.add(key);
        }
        for (String key : AgentGate.NOT_HELD) {
            assertTrue(PendingChanges.REPLAYABLE.contains(key), "NOT_HELD names a route REPLAYABLE does not list: " + key);
            assertEquals(AgentGate.Bucket.REFUSED, bucket(key), "writes without or before the hold, so refused: " + key);
        }
        Set<String> held = new TreeSet<>();
        for (String key : m.keySet()) if (bucket(key) == AgentGate.Bucket.HELD) held.add(key);
        assertEquals(expected, held);
        // §4.1: on REPLAYABLE, yet refused to an agent, never held
        for (String key : Set.of("POST /promotion/apply", "PUT /access/roles", "PUT /access/policies", "PUT /access/catalog",
                "PUT /access/profiles/([^/]+)", "DELETE /access/profiles/([^/]+)"))
            assertEquals(AgentGate.Bucket.REFUSED, bucket(key), key);
    }

    @Test
    void theAllowedBucketNamesRealRoutesThatChangeNothingGoverned() {
        Map<String, String> m = manifest();
        Set<String> gatedButAllowed = new LinkedHashSet<>();
        for (String key : AgentGate.ALLOWED) {
            assertTrue(m.containsKey(key), "ALLOWED names a route the manifest does not list: " + key);
            assertFalse(PendingChanges.REPLAYABLE.contains(key), key + " reaches the hold — it belongs in HELD");
            String where = m.get(key);
            if (where.startsWith("exempt:"))
                assertTrue(Set.of("exempt:read-shaped", "exempt:self-service", "exempt:group-gated", "exempt:replay-gated").contains(where), key + " " + where);
            else gatedButAllowed.add(key);
        }
        // the four capability-gated writes the plan allows: an Action Request (held by its own design), the promotion
        // preview (a read behind canAdminister), a run (D-AK2, a person only) and the Space tests (W6 group C, D-AK10,
        // a person only)
        assertEquals(Set.of("POST /action-requests", "POST /promotion/preview", "POST /pipelines/authored/([^/]+)/run",
                        "POST /tests/run"), gatedButAllowed);
        assertEquals(Set.of("POST /pipelines/authored/([^/]+)/run", "POST /tests/run"), AgentGate.PERSON_ONLY);
        assertEquals(Roles.CAN_AUTHOR_WORKBENCH, m.get("POST /tests/run"), "the Space tests keep the route's own gate");
    }

    /**
     * W6 group A (D-AK10): the four authoring previews are allowed to every agent — read-shaped exemptions, none
     * person-only. {@code /lookups/test} is not one: refused to agents until AGENT-LOOKUP-PREVIEW-1 (operator 2026-11-26).
     */
    @Test
    void theAuthoringPreviewsAreAllowedToEveryAgent() {
        Map<String, String> m = manifest();
        assertEquals(4, AgentGate.AUTHORING_PREVIEWS.size());
        assertNotEquals(AgentGate.Bucket.ALLOWED, bucket("POST /lookups/test"), "the lookup test stays refused to agents");
        for (String key : AgentGate.AUTHORING_PREVIEWS) {
            assertEquals("exempt:read-shaped", m.get(key), key);
            assertEquals(AgentGate.Bucket.ALLOWED, bucket(key), key);
            assertFalse(AgentGate.PERSON_ONLY.contains(key), key);
        }
    }

    /**
     * W6 group D, change D1 (signed design D-AK13, D-AK17): the two analytic reads are allowed to every agent — read-shaped
     * exemptions, none person-only — and each is a weighted data route, so its answer must prove its masking.
     */
    @Test
    void theAnalyticReadsAreAllowedToEveryAgentAsDataRoutes() {
        Map<String, String> m = manifest();
        assertEquals(Set.of("POST /bi/query", "POST /queries/([^/]+)/run"), AgentGate.ANALYTIC_READS);
        for (String key : AgentGate.ANALYTIC_READS) {
            assertEquals("exempt:read-shaped", m.get(key), key);
            assertEquals(AgentGate.Bucket.ALLOWED, bucket(key), key);
            assertFalse(AgentGate.PERSON_ONLY.contains(key), key);
            assertEquals(1, AgentGate.DATA_ROUTES.get(key), key + " weighs a page read");
        }
    }

    @Test
    void theActionBucketIsExactlyTheNamedOperationalRoutes() {
        Map<String, String> m = manifest();
        Set<String> actions = new TreeSet<>();
        for (String key : m.keySet()) if (bucket(key) == AgentGate.Bucket.ACTION) actions.add(key);
        assertEquals(new TreeSet<>(AgentGate.ACTIONS), actions, "the run (AK-2d) and case (AK-2e) actions of plan §4.6");
        for (String key : AgentGate.ACTIONS)
            assertFalse(PendingChanges.REPLAYABLE.contains(key), key + " reaches the config hold — it belongs in HELD");
        for (String key : AgentGate.RUN_ACTIONS)
            assertEquals(Roles.CAN_OPERATE_RUNS, m.get(key), "a run action is a run operation: " + key);
        // AK-2e (operator 2026-11-25): the §4.6 analyst rows, and W11's record edits (D-AK8 signed 2026-11-25) — each on
        // its own route's gate, none an admin's (merge, split, tags and the canAdminister PATCH stay refused)
        Map<String, String> caseGates = new java.util.TreeMap<>();
        for (String key : AgentGate.CASE_ACTIONS) caseGates.put(key, m.get(key));
        assertEquals(new java.util.TreeMap<>(Map.ofEntries(
                Map.entry("POST /objects/([^/]+)/comments", "exempt:collaboration"),
                Map.entry("POST /incidents/([^/]+)/briefs", Roles.CAN_WORK_INCIDENTS),
                Map.entry("POST /objects/([^/]+)/ack", Roles.CAN_WORK_INCIDENTS),
                Map.entry("POST /objects/([^/]+)/assign", Roles.CAN_WORK_INCIDENTS),
                Map.entry("POST /objects/([^/]+)/resolve", Roles.CAN_WORK_INCIDENTS),
                Map.entry("POST /objects/([^/]+)/transition", Roles.CAN_WORK_INCIDENTS),
                Map.entry("POST /alerts/([^/]+)/ack", Roles.CAN_WORK_INCIDENTS),
                Map.entry("POST /alerts/([^/]+)/resolve", Roles.CAN_WORK_INCIDENTS),
                Map.entry("POST /cases/from-entities", Roles.CAN_MANAGE_INCIDENTS),
                Map.entry("PUT /objects/([^/]+)/postmortem", Roles.CAN_WORK_INCIDENTS),
                Map.entry("PUT /objects/([^/]+)/category", Roles.CAN_WORK_INCIDENTS),
                Map.entry("PUT /objects/([^/]+)/impact", Roles.CAN_WORK_INCIDENTS),
                Map.entry("PUT /objects/([^/]+)/findings", "exempt:collaboration"),
                Map.entry("POST /objects/([^/]+)/rca", "exempt:collaboration"),
                Map.entry("POST /objects/([^/]+)/links", "exempt:collaboration"),
                Map.entry("DELETE /objects/([^/]+)/links", "exempt:collaboration"))), caseGates);
        for (String refused : java.util.List.of("PATCH /objects/([^/]+)", "POST /objects/([^/]+)/merge",
                "POST /objects/([^/]+)/split"))
            assertEquals(AgentGate.Bucket.REFUSED, bucket(refused), refused);
        // AK-2f (operator 2026-11-25): Drafts, attach to a Case, graph runs — never promote or a direct Investigation edit
        Map<String, String> laGates = new java.util.TreeMap<>();
        for (String key : AgentGate.LA_ACTIONS) laGates.put(key, m.get(key));
        assertEquals(new java.util.TreeMap<>(Map.of(
                "POST /inv/investigations/([^/]+)/drafts", Roles.CAN_DRAFT_INVESTIGATIONS,
                "POST /inv/investigations/([^/]+)/drafts/([^/]+)/ops", Roles.CAN_DRAFT_INVESTIGATIONS,
                "POST /inv/investigations/([^/]+)/drafts/([^/]+)/undo", Roles.CAN_DRAFT_INVESTIGATIONS,
                "POST /inv/investigations/([^/]+)/drafts/([^/]+)/discard", Roles.CAN_DRAFT_INVESTIGATIONS,
                "POST /inv/investigations/([^/]+)/drafts/([^/]+)/rebase", Roles.CAN_DRAFT_INVESTIGATIONS,
                "PUT /inv/investigations/([^/]+)/case", Roles.CAN_MANAGE_INCIDENTS,
                "POST /inv/graph/runs", Roles.CAN_RUN_LINK_GRAPH_ANALYSIS,
                "POST /inv/graph/runs/([^/]+)/cancel", "exempt:self-service")), laGates);
        for (String refused : java.util.List.of("POST /inv/investigations/([^/]+)/drafts/([^/]+)/promote",
                "POST /inv/investigations/([^/]+)/ops", "POST /inv/investigations/([^/]+)/undo",
                "POST /inv/investigations/([^/]+)/reveal", "POST /inv/investigations/([^/]+)/members",
                "POST /inv/investigations/([^/]+)/pending/([^/]+)/approve", "POST /inv/investigations/([^/]+)/pending/([^/]+)/deny"))
            assertEquals(AgentGate.Bucket.REFUSED, bucket(refused), refused);
        // the machine set keeps the Draft right, never the right to promote it
        assertTrue(Roles.AGENT_MACHINE_CAPABILITIES.contains(Roles.CAN_DRAFT_INVESTIGATIONS));
        // every action the manifest exempts needs a named right before a machine may queue it (phase-3 security review)
        Set<String> exemptActions = new TreeSet<>();
        for (String key : AgentGate.ACTIONS) if (String.valueOf(m.get(key)).startsWith("exempt:")) exemptActions.add(key);
        assertEquals(exemptActions, new TreeSet<>(AgentGate.MACHINE_HOLD_CAPABILITY.keySet()));
        // Until W2 step 3 a machine agent never kept canManageIncidents ("a new Case: people only"). The operator's
        // decision of 2026-11-25 (W2 step 3, D-AK9 Entity List edits, granted globally) gives it canManageIncidents and
        // canManageExclusionCategories; the set is pinned so any further widening is a deliberate edit here too.
        assertEquals(new TreeSet<>(Set.of(Roles.CAN_AUTHOR_WORKBENCH, Roles.CAN_AUTHOR_ALERT_RULES, Roles.CAN_WORK_INCIDENTS,
                        Roles.CAN_OPERATE_RUNS, Roles.CAN_DRAFT_INVESTIGATIONS, Roles.CAN_MANAGE_INCIDENTS,
                        Roles.CAN_MANAGE_EXCLUSION_CATEGORIES)),
                new TreeSet<>(Roles.AGENT_MACHINE_CAPABILITIES));
        // ...and every write those two rights open is held for a machine: none sits in ALLOWED, which runs at once
        for (Map.Entry<String, String> e : m.entrySet()) {
            boolean write = !e.getKey().startsWith("GET ");
            if (write && (Roles.CAN_MANAGE_INCIDENTS.equals(e.getValue())
                    || Roles.CAN_MANAGE_EXCLUSION_CATEGORIES.equals(e.getValue())))
                assertNotEquals(AgentGate.Bucket.ALLOWED, bucket(e.getKey()), e.getKey() + " would run at once for a machine");
        }
        assertEquals(AgentGate.Bucket.REFUSED, bucket("POST /pending-actions/([^/]+)/approve"));
        assertEquals(AgentGate.Bucket.REFUSED, bucket("POST /pending-actions/([^/]+)/decline"));
        assertEquals(AgentGate.Bucket.ALLOWED, bucket("POST /pending-actions/([^/]+)/withdraw"));
    }

    @Test
    void theNeverListIsRefusedOnEveryMethodIncludingReads() {
        Map<String, String> m = manifest();
        for (String n : AgentGate.NEVER) assertTrue(m.containsKey(n), "NEVER names a route the manifest does not list: " + n);
        int never = 0;
        for (Map.Entry<String, String> e : m.entrySet()) {
            String key = e.getKey();
            String pattern = key.substring(key.indexOf(' ') + 1);
            boolean approval = pattern.endsWith("/approve") || pattern.endsWith("/decline") || pattern.endsWith("/decision");
            if (approval || Roles.AGENT_NEVER_CAPABILITIES.contains(e.getValue())) {
                assertEquals(AgentGate.Bucket.REFUSED, bucket(key), key);
                never++;
            }
        }
        assertTrue(never >= 10, "the approve / decline / reveal routes were found: " + never);
        for (String key : Set.of("POST /pending-changes/([^/]+)/approve", "POST /action-requests/([^/]+)/retry",
                "POST /action-requests/([^/]+)/mark-failed", "POST /apps", "PUT /apps/([^/]+)", "DELETE /apps/([^/]+)",
                "POST /agent/approvals/(.+)/decision", "POST /inv/investigations/([^/]+)/reveal",
                "POST /inv/investigations/([^/]+)/pending/([^/]+)/approve"))
            assertEquals(AgentGate.Bucket.REFUSED, bucket(key), key);
    }

    @Test
    void holdRefusingOnlyWritesAndOrdinaryWritesAreRefused() {
        for (String key : Set.of("POST /bundle/import", "PUT /settings/approval", "POST /spaces", "POST /components/sql/ast"))
            assertEquals(AgentGate.Bucket.REFUSED, bucket(key), key);
    }
}
