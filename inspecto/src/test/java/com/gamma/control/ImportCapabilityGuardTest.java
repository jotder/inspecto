package com.gamma.control;

import com.gamma.pipeline.ComponentRegistry;
import com.gamma.pipeline.ComponentStore;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link ImportCapabilityGuard} is COMPLETE against {@link CapabilityManifest}: for every writable kind, every declared
 * write route under that kind's own route prefix whose capability is stricter than {@code canAuthorWorkbench} (the
 * generic door's and every import door's) must be covered — the table demands that same capability, or the kind is
 * {@link ImportCapabilityGuard#DEDICATED_ONLY}. The expectations are DERIVED from the manifest, so a new kind, a new
 * stricter route for an existing kind, or a forgotten table entry fails here.
 *
 * <p>The only hand-kept inputs are (1) where a kind's own routes live when that is not {@code /<dir>},
 * {@code /<kind>} or a literal {@code /components/<kind>} ({@link #ALIASES}) and (2) the routes under a kind's prefix that are ACTS on it (run, evaluate,
 * promote), not writes of its config ({@link #ACTS}) — each with its reason. Both are checked for staleness.
 */
class ImportCapabilityGuardTest {

    /** Kinds whose own routes are not at {@code /<registry dir>} or {@code /<kind>}. */
    private static final Map<String, List<String>> ALIASES = Map.of(
            "alert-rule", List.of("/alerts/rules"),
            "access-profile", List.of("/access/profiles"),
            "access-catalog", List.of("/access/catalog"),
            "channel", List.of("/notifications/channels"),
            "notification-rule", List.of("/notifications/rules"),
            "reconciliation", List.of("/recon"));

    /** method + pattern → why it is not a write of the kind's config (so the generic door need not match it). */
    private static final Map<String, String> ACTS = Map.of(
            "POST /datasets/([^/]+)/materialize", "runs a materialize Job; the Dataset's config is untouched",
            "POST /decision-rules/([^/]+)/apply", "applies a rule to data (canOperateRuns); the rule is untouched",
            "POST /expectations/evaluate", "evaluates; results are run state, not config",
            "POST /expectations/([^/]+)/evaluate", "evaluates one Expectation; run state",
            "POST /expectations/([^/]+)/baseline/accept", "accepts a drift baseline; run state (canOperateRuns)",
            "POST /expectations/([^/]+)/baseline/clear", "clears a drift baseline; run state (canOperateRuns)",
            "POST /recon/promote", "opens an Incident from a break (canManageIncidents)",
            "POST /recon/([^/]+)/record", "records a run into recon-state/ (R2-03), not the config",
            "POST /recon/([^/]+)/breaks/status", "sets a break's status in recon-state/, not the config");

    private static final Set<String> WRITE_METHODS = Set.of("POST", "PUT", "PATCH", "DELETE");

    private static Set<String> kinds() {
        Set<String> k = new TreeSet<>(ComponentStore.WRITABLE_TYPES);
        k.add("connection");   // a BundleRoutes / import kind with its own store and route
        return k;
    }

    private static List<String> prefixes(String kind) {
        List<String> p = new ArrayList<>(ALIASES.getOrDefault(kind, List.of()));
        p.add("/" + kind);
        p.add("/" + kind + "s");
        p.add("/components/" + kind);   // a literal per-kind component route (findings-spec's)
        ComponentRegistry.dirForType(kind).ifPresent(d -> p.add("/" + d));
        return p;
    }

    private static boolean under(String pattern, String prefix) {
        return pattern.equals(prefix) || pattern.startsWith(prefix + "/");
    }

    @Test
    void everyStricterRouteOfAWritableKindIsCoveredByTheTable() {
        List<String> uncovered = new ArrayList<>();
        Set<String> actsSeen = new LinkedHashSet<>();
        for (String kind : kinds()) {
            for (CapabilityManifest.Entry e : CapabilityManifest.ENTRIES) {
                if (!WRITE_METHODS.contains(e.method())) continue;
                if (prefixes(kind).stream().noneMatch(p -> under(e.pattern(), p))) continue;
                if (Roles.CAN_AUTHOR_WORKBENCH.equals(e.capability())) continue;   // the generic door's own gate
                String route = e.method() + " " + e.pattern();
                if (ACTS.containsKey(route)) { actsSeen.add(route); continue; }
                boolean covered = ImportCapabilityGuard.DEDICATED_ONLY.containsKey(kind)
                        || ImportCapabilityGuard.GOVERNANCE_ONLY.containsKey(kind)   // refused on every door but its own
                        || e.capability().equals(ImportCapabilityGuard.KIND_CAPABILITY.get(kind));
                if (!covered) uncovered.add(kind + " ← " + route + " needs " + e.capability());
            }
        }
        assertEquals(List.of(), uncovered, "ImportCapabilityGuard.KIND_CAPABILITY (or DEDICATED_ONLY) misses a kind's own, stricter "
                + "write route — an import or /components/{kind} would be the wider door");
        assertEquals(ACTS.keySet(), actsSeen, "a listed ACT no longer matches any manifest route — drop it");
    }

    @Test
    void everyTableEntryIsJustifiedByItsKindsOwnRoute() {
        for (Map.Entry<String, String> w : ImportCapabilityGuard.KIND_CAPABILITY.entrySet()) {
            boolean found = CapabilityManifest.ENTRIES.stream().anyMatch(e -> WRITE_METHODS.contains(e.method())
                    && e.capability().equals(w.getValue())
                    && prefixes(w.getKey()).stream().anyMatch(p -> under(e.pattern(), p)));
            assertTrue(found, w.getKey() + " → " + w.getValue() + " has no route of that kind declaring it");
        }
        assertTrue(kinds().containsAll(ImportCapabilityGuard.KIND_CAPABILITY.keySet()), "a table kind no store writes");
        assertTrue(kinds().containsAll(ImportCapabilityGuard.DEDICATED_ONLY.keySet()), "a dedicated-only kind no store writes");
        assertTrue(kinds().containsAll(ImportCapabilityGuard.GOVERNANCE_ONLY.keySet()), "a governance kind no store writes");
        assertTrue(kinds().containsAll(ALIASES.keySet()), "an alias for a kind that no longer exists");
    }

    @Test
    void importPathsAreClassifiedAsTheFilesystemResolvesThem() {
        for (String p : List.of("registry/findings-specs/x.toon", "Registry/findings-specs/x.toon",
                "REGISTRY/FINDINGS-SPECS/x.toon", "registry./findings-specs./x.toon", "registry /findings-specs/x.toon",
                "./registry//findings-specs/x.toon", "registry\\findings-specs\\x.toon"))
            assertEquals("registry/findings-specs/x.toon", ImportCapabilityGuard.normalizedPath(p), p);
    }

    /** Defence in depth behind ImportPaths' shape rule (which already refuses an unknown registry dir today). */
    @Test
    void anUnclassifiableFileUnderRegistryFailsClosed() {
        ApiException e = assertThrows(ApiException.class, () -> ImportCapabilityGuard.checkFiles(null,
                Map.of("orders/orders_pipeline.toon", new byte[0], "Registry/No-Such-Kind/x.toon", new byte[0])));
        assertEquals(403, e.status);
        ImportCapabilityGuard.checkFiles(null, Map.of("orders/orders_pipeline.toon", new byte[0], "notes.toon", new byte[0],
                "orders/registry/misc/x.toon", new byte[0]));
    }

    @Test
    void aDedicatedOnlyKindIsRefusedOnEveryImportButANewSpace() {
        Map<String, byte[]> req = Map.of("REGISTRY/requirements./r1.toon", new byte[0]);
        assertEquals(403, assertThrows(ApiException.class, () -> ImportCapabilityGuard.checkFiles(null, req)).status);
        ImportCapabilityGuard.checkFiles(null, req, true);
        assertEquals(403, assertThrows(ApiException.class, () -> ImportCapabilityGuard.checkItems(null,
                List.of(Map.of("kind", "Requirement", "id", "r1")))).status);
        assertEquals(403, assertThrows(ApiException.class, () -> ImportCapabilityGuard.requireKind(null,
                "access-profile")).status);
        ImportCapabilityGuard.requireKind(null, "dataset");
    }

    /** ASSURE-WORKFLOW-SLA-1: a Workflow / SLA policy / Escalation Rule rides NO import — not even a new Space's. */
    @Test
    void aGovernanceKindIsRefusedOnEveryImportANewSpacesIncluded() {
        for (String[] k : List.of(new String[]{"workflow", "workflows"}, new String[]{"sla-policy", "sla-policies"},
                new String[]{"escalation-rule", "escalation-rules"})) {
            Map<String, byte[]> file = Map.of("Registry/" + k[1] + "./incident.toon", new byte[0]);
            assertEquals(403, assertThrows(ApiException.class, () -> ImportCapabilityGuard.checkFiles(null, file)).status, k[0]);
            assertEquals(403, assertThrows(ApiException.class, () -> ImportCapabilityGuard.checkFiles(null, file, true)).status, k[0]);
            assertEquals(403, assertThrows(ApiException.class, () -> ImportCapabilityGuard.checkItems(null,
                    List.of(Map.of("kind", k[0].toUpperCase(java.util.Locale.ROOT), "id", "incident")))).status, k[0]);
            assertEquals(403, assertThrows(ApiException.class, () -> ImportCapabilityGuard.requireKind(null, k[0])).status, k[0]);
        }
    }
}
