package com.gamma.etl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The arming rules as a rule set — the form the SAVE path needs.
 *
 * <p>{@code PipelineConfig.prepare()}'s behaviour (throw the FIRST refusal) is pinned elsewhere by
 * {@code RecordDedupRouteConfigTest}; what those tests cannot see is the property this extraction
 * exists for: that a draft with several problems reports ALL of them, so an author fixing a branch
 * list is not played one refusal at a time.
 */
class RouteArmingTest {

    private static Map<String, Object> branch(String key, String db) {
        return Map.of("key", key, "database", db);
    }

    @Test
    @DisplayName("a well-formed route arms — no refusals")
    void wellFormedRouteArms() {
        Map<String, Object> route = Map.of(
                "mode", "case",
                "default", "apac",
                "branches", List.of(branch("emea", "emea_db"), branch("apac", "apac_db")));
        assertEquals(List.of(), RouteArming.refusals(route, List.of("emea_db", "apac_db"), false));
    }

    @Test
    @DisplayName("no route at all is not a refusal")
    void noRouteIsSilent() {
        assertEquals(List.of(), RouteArming.refusals(null, List.of("a"), false));
    }

    @Test
    @DisplayName("EVERY refusal is reported, not just the first — the reason this is not prepare()")
    void reportsEveryRefusal() {
        // Four independent problems at once: clone mode, an unmatched database, no usable default,
        // and multi-schema. prepare() would surface only the clone one.
        Map<String, Object> route = Map.of(
                "mode", "clone",
                "branches", List.of(branch("emea", "nowhere_db")));
        List<String> refusals = RouteArming.refusals(route, List.of("emea_db"), true);

        assertEquals(4, refusals.size(), refusals.toString());
        assertTrue(refusals.get(0).contains("'clone' is authoring-only"), refusals.get(0));
        assertTrue(refusals.get(1).contains("matches no sinks[] destination"), refusals.get(1));
        assertTrue(refusals.get(2).contains("needs default:"), refusals.get(2));
        assertTrue(refusals.get(3).contains("multi-schema"), refusals.get(3));
    }

    @Test
    @DisplayName("an empty branch list short-circuits — every later rule reads that list")
    void emptyBranchesIsTheOnlyRefusal() {
        List<String> refusals = RouteArming.refusals(Map.of("branches", List.of()), List.of(), true);
        assertEquals(1, refusals.size(), refusals.toString());
        assertTrue(refusals.get(0).contains("non-empty branches list"), refusals.get(0));
    }

    @Test
    @DisplayName("two branches sharing one database — only one would ever receive rows")
    void sharedDatabaseIsRefused() {
        Map<String, Object> route = Map.of(
                "default", "emea",
                "branches", List.of(branch("emea", "one_db"), branch("apac", "one_db")));
        List<String> refusals = RouteArming.refusals(route, List.of("one_db"), false);
        assertEquals(1, refusals.size(), refusals.toString());
        assertTrue(refusals.get(0).contains("branches share database 'one_db'"), refusals.get(0));
    }

    @Test
    @DisplayName("a malformed branch is reported and the scan CONTINUES to the next one")
    void malformedBranchDoesNotStopTheScan() {
        Map<String, Object> route = Map.of(
                "default", "apac",
                "branches", List.of(Map.of("key", "emea"), branch("apac", "nowhere_db")));
        List<String> refusals = RouteArming.refusals(route, List.of("apac_db"), false);
        assertEquals(2, refusals.size(), refusals.toString());
        assertTrue(refusals.get(0).contains("needs both a key and a database"), refusals.get(0));
        assertTrue(refusals.get(1).contains("matches no sinks[] destination"), refusals.get(1));
    }

    // ── the draft-map readers (the save path's half) ─────────────────────────────

    @Test
    @DisplayName("draft sink databases are read in declaration order, skipping entries without one")
    void draftSinkDatabases() {
        Object sinks = List.of(Map.of("database", "a"), Map.of("table", "no_db"), Map.of("database", "b"));
        assertEquals(List.of("a", "b"), RouteArming.draftSinkDatabases(sinks));
        assertEquals(List.of(), RouteArming.draftSinkDatabases(null));
    }

    @Test
    @DisplayName("multi-schema is detected from schemas[] AND from either segments spelling")
    void draftMultiSchemaSpellings() {
        assertTrue(RouteArming.draftIsMultiSchema(Map.of("schemas", List.of(Map.of("col", 3))), Map.of()));
        // parsing.plugin.segments wins over processing.segments, but either alone counts.
        assertTrue(RouteArming.draftIsMultiSchema(Map.of(),
                Map.of("plugin", Map.of("segments", Map.of("cdr", "cdr.toon")))));
        assertTrue(RouteArming.draftIsMultiSchema(Map.of("segments", Map.of("cdr", "cdr.toon")), Map.of()));
        // A single-schema pipeline is not multi-schema, and neither is an EMPTY declaration.
        assertTrue(!RouteArming.draftIsMultiSchema(Map.of("schema_file", "one.toon"), Map.of()));
        assertTrue(!RouteArming.draftIsMultiSchema(Map.of("schemas", List.of()), Map.of()));
    }
}
