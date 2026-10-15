package com.gamma.la.api;

import com.gamma.entitystore.LaLimit;
import com.gamma.entitystore.LaLimits;
import com.gamma.la.core.Algorithm;
import com.gamma.la.core.GraphRunService;
import com.gamma.la.core.InvestigationTime;
import com.gamma.la.storage.BucketFunction;
import com.gamma.la.storage.IndexBuildService;
import com.gamma.la.storage.IndexPlan;
import com.gamma.la.storage.IndexedTraversal;
import com.gamma.la.storage.SqlGraphEngine;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code LA-CONFIG-LIMITS-1}: an item whose code site still holds a constant has its catalogue default pinned to that
 * constant (reflection, since most are private), so the two cannot drift until the phase that rewires the site moves both
 * together. P3 rewired the Space analysis limits: their sites read {@code LaLimits} (the constant is GONE, asserted here,
 * and the real-HTTP tests prove each site honours a Space-stated value), and their defaults are the decided ones (CL-10,
 * CL-11, CL-15). P2 pins its
 * decided canvas defaults, derives each {@code browser_algorithms.<id>} default from the measured server ceiling ({@link
 * Algorithm#inlineNodeCeiling()}) - one item per algorithm but suspicionScore - and holds the SPA's one fallback table
 * ({@code la-limits.ts}) to the catalogue.
 */
class LaLimitCatalogueParityTest {

    private static long constant(Class<?> owner, String field) {
        try {
            Field f = owner.getDeclaredField(field);
            f.setAccessible(true);
            return ((Number) f.get(null)).longValue();
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(owner.getSimpleName() + "." + field + " is gone - move the catalogue default with it", e);
        }
    }

    /**
     * One item per algorithm the browser CONSULTS (it refuses above it, or hands the run to the server) - exactly the
     * {@code browser_algorithms.*} keys the SPA's fallback table holds - each a real catalogue algorithm. No item for one the
     * browser never caps (lead decision 2026-10-15: no dead settings), and none for suspicionScore: its cap is
     * {@code suspicion_node_cap}, one key for browser and server (CL-2).
     */
    @Test
    void oneBrowserAlgorithmItemPerConsultedAlgorithm() throws Exception {
        Set<String> items = Arrays.stream(LaLimit.values()).map(LaLimit::path).filter(p -> p.startsWith("browser_algorithms."))
                .collect(Collectors.toCollection(TreeSet::new));
        Set<String> spa = spaFallbacks(Files.readString(SPA_FALLBACKS)).keySet().stream().filter(k -> k.startsWith("browser_algorithms."))
                .collect(Collectors.toCollection(TreeSet::new));
        assertEquals(spa, items, "the catalogue's browser_algorithms items are exactly the ones the SPA reads");
        Set<String> algorithms = Arrays.stream(Algorithm.values()).map(Algorithm::id).collect(Collectors.toCollection(TreeSet::new));
        for (String k : items) assertTrue(algorithms.contains(k.substring("browser_algorithms.".length())), k + " names no algorithm");
        assertEquals(17, items.size());
        for (String never : List.of("suspicionScore", "pageRank", "degreeCentrality", "propagatedRisk"))
            assertTrue(LaLimit.byPath("browser_algorithms." + never).isEmpty(), never);
    }

    /** CL-3: 2,500 per algorithm, or the measured (server inline) ceiling where that is lower. */
    @Test
    void eachBrowserAlgorithmDefaultIsTheGeneralCapOrItsLowerMeasuredCeiling() {
        List<String> drift = new ArrayList<>();
        for (Algorithm a : Algorithm.values()) {
            var item = LaLimit.byPath("browser_algorithms." + a.id());
            if (item.isEmpty()) continue;                                  // not consulted by the browser: no item
            long want = Math.min(2_500, a.inlineNodeCeiling());
            Number got = item.get().defaultValue();
            if (got.longValue() != want) drift.add(a.id() + ": catalogue " + got + ", measured " + want);
        }
        assertTrue(drift.isEmpty(), drift.toString());
        assertEquals(500L, LaLimit.BROWSER_BETWEENNESS_CENTRALITY.defaultValue(), "the slowest browser algorithm");
        assertEquals(2_500L, LaLimit.BROWSER_LOUVAIN_COMMUNITIES.defaultValue());
    }

    /** CL-1 / CL-2 / CL-3: the decided P2 defaults (operator 2026-10-15). */
    @Test
    void theCanvasAndBrowserDefaultsAreTheDecidedOnes() {
        assertEquals(1_000L, LaLimit.PROJECTION_NODE_CAP.defaultValue());
        assertEquals(1_000L, LaLimit.SUSPICION_NODE_CAP.defaultValue());
        assertEquals(2_500L, LaLimit.ANALYSIS_NODE_CAP.defaultValue());
    }

    /** {@code inspecto-ui/projects/link-analysis/src/link-analysis/la-limits.ts}, from this module's directory. */
    private static final Path SPA_FALLBACKS = Path.of("..", "..", "inspecto-ui", "projects", "link-analysis", "src", "link-analysis",
            "la-limits.ts");

    /** The {@code LA_LIMIT_FALLBACKS} object literal: {@code key: number} per line, a key bare or single-quoted. */
    static Map<String, Long> spaFallbacks(String ts) {
        int from = ts.indexOf("export const LA_LIMIT_FALLBACKS = {");
        int to = ts.indexOf("} as const", from);
        assertTrue(from >= 0 && to > from, "LA_LIMIT_FALLBACKS not found in la-limits.ts");
        Map<String, Long> out = new TreeMap<>();
        Matcher m = Pattern.compile("^\\s*'?([A-Za-z_][\\w.]*)'?\\s*:\\s*([\\d_]+)\\s*,?\\s*(//.*)?$", Pattern.MULTILINE)
                .matcher(ts.substring(from, to));
        while (m.find()) out.put(m.group(1), Long.parseLong(m.group(2).replace("_", "")));
        return out;
    }

    /**
     * The SPA's ONE fallback table (used only until the server answers) states the catalogue's own default for every key it
     * holds, and holds only catalogue keys, so the browser never runs on a number the server would not have said.
     */
    @Test
    void theSpaFallbackTableAgreesWithTheCatalogue() throws Exception {
        Map<String, Long> spa = spaFallbacks(Files.readString(SPA_FALLBACKS));
        assertTrue(spa.size() >= 20, "parsed too few keys - the table's shape changed: " + spa);
        List<String> disagree = new ArrayList<>();
        spa.forEach((k, v) -> {
            var item = LaLimit.byPath(k);
            if (item.isEmpty()) disagree.add(k + ": not a catalogue key");
            else if (item.get().nullable() || item.get().defaultValue().longValue() != v)
                disagree.add(k + ": SPA " + v + ", catalogue " + item.get().defaultValue());
        });
        assertTrue(disagree.isEmpty(), "la-limits.ts LA_LIMIT_FALLBACKS vs LaLimit: " + disagree);
        for (String k : List.of("projection_node_cap", "analysis_node_cap", "suspicion_node_cap", "browser_algorithms.betweennessCentrality"))
            assertTrue(spa.containsKey(k), "the SPA reads " + k);
    }

    /** The parser itself: a table whose number moved is caught (the probe that would otherwise pass). */
    @Test
    void theFallbackParserReadsBareQuotedAndUnderscoredKeys() {
        Map<String, Long> m = spaFallbacks("export const LA_LIMIT_FALLBACKS = {\n    // c\n    projection_node_cap: 1000,\n"
                + "    'display.lod_label_cap': 300,\n    'graph_run.max_result_items': 10_000,\n} as const;");
        assertEquals(Map.of("projection_node_cap", 1_000L, "display.lod_label_cap", 300L, "graph_run.max_result_items", 10_000L), m);
    }

    /** P3: the decided defaults (plan §7, CL-10 doubled, CL-11 hops 3 / 6, CL-15 input shapes unchanged). */
    private static Map<LaLimit, Long> p3Defaults() {
        Map<LaLimit, Long> d = new EnumMap<>(LaLimit.class);
        d.put(LaLimit.INVESTIGATION_EXPAND_BUDGET_DEFAULT, 4_000L);
        d.put(LaLimit.INVESTIGATION_EXPAND_BUDGET_MAX, 40_000L);
        d.put(LaLimit.INVESTIGATION_MAX_FRONTIER, 2_000L);
        d.put(LaLimit.INVESTIGATION_ONLY_EXEMPT_MAX, 50L);
        d.put(LaLimit.INVESTIGATION_LOG_PAGE_DEFAULT, 500L);
        d.put(LaLimit.INVESTIGATION_LOG_PAGE_MAX, 5_000L);
        d.put(LaLimit.INVESTIGATION_LIST_PAGE_DEFAULT, 100L);
        d.put(LaLimit.INVESTIGATION_LIST_PAGE_MAX, 1_000L);
        d.put(LaLimit.CONNECT_MAX_SEEDS, 40L);
        d.put(LaLimit.CONNECT_MAX_HOPS, 6L);
        d.put(LaLimit.CONNECT_DEFAULT_HOPS, 3L);
        d.put(LaLimit.TRAVERSAL_DEPTH_DEFAULT, 12L);
        d.put(LaLimit.TRAVERSAL_DEPTH_MAX, 20L);
        d.put(LaLimit.TRAVERSAL_TIMEOUT_S, 10L);
        d.put(LaLimit.TRAVERSAL_PATHS_DEFAULT, 2_000L);
        d.put(LaLimit.TRAVERSAL_PATHS_MAX, 20_000L);
        d.put(LaLimit.TRAVERSAL_PAIRS_DEFAULT, 200L);
        d.put(LaLimit.TRAVERSAL_PAIRS_MAX, 1_000L);
        d.put(LaLimit.TRAVERSAL_EDGE_YIELD_DEFAULT, 20_000L);
        d.put(LaLimit.TRAVERSAL_EDGE_YIELD_MAX, 200_000L);
        d.put(LaLimit.TRAVERSAL_ROWS_DEFAULT, 4_000L);
        d.put(LaLimit.TRAVERSAL_ROWS_MAX, 40_000L);
        d.put(LaLimit.NEIGHBOURS_PAGE_DEFAULT, 100L);
        d.put(LaLimit.NEIGHBOURS_PAGE_MAX, 400L);
        d.put(LaLimit.SNAPSHOTS_PAGE_DEFAULT, 200L);
        d.put(LaLimit.SNAPSHOTS_PAGE_MAX, 2_000L);
        d.put(LaLimit.FILES_PAGE_DEFAULT, 200L);
        d.put(LaLimit.FILES_PAGE_MAX, 2_000L);
        d.put(LaLimit.WORKING_SET_PAGE_DEFAULT, 2_000L);
        d.put(LaLimit.WORKING_SET_PAGE_MAX, 20_000L);
        d.put(LaLimit.PATTERN_MAX_LEGS, 200_000L);
        d.put(LaLimit.PATTERN_MATCHES_DEFAULT, 400L);
        d.put(LaLimit.PATTERN_MATCHES_MAX, 2_000L);
        d.put(LaLimit.PATTERN_TIMEOUT_S, 10L);
        d.put(LaLimit.PATTERN_TEMPORAL_MAX_ROWS, 400_000L);
        d.put(LaLimit.PATTERN_TEMPORAL_LIMIT_DEFAULT, 400L);
        d.put(LaLimit.PATTERN_TEMPORAL_LIMIT_MAX, 2_000L);
        d.put(LaLimit.MEASURES_MAX_ENTITIES, 20_000L);
        d.put(LaLimit.MEASURES_TIMEOUT_S, 10L);
        d.put(LaLimit.MEASURES_MAX_WINDOW_DAYS, 31L);
        d.put(LaLimit.COMPARISON_MAX_ENTITIES, 4_000L);
        d.put(LaLimit.COMPARISON_MAX_ROWS, 100_000L);
        d.put(LaLimit.COMPARISON_LIST_CAP, 1_000L);
        d.put(LaLimit.REFERENCE_MAX_KEYS, 500L);
        d.put(LaLimit.REFERENCE_MAX_ROWS, 4_000L);
        d.put(LaLimit.CASE_HISTORY_PAGE_MAX, 400L);
        d.put(LaLimit.CASE_HISTORY_MAX_KEY_LENGTH, 256L);
        d.put(LaLimit.CASE_HISTORY_MAX_LINKED_INVESTIGATIONS, 200L);
        d.put(LaLimit.CASE_HISTORY_MAX_ATTACHMENTS, 1_000L);
        d.put(LaLimit.DOSSIER_MAX_STEPS, 10_000L);
        d.put(LaLimit.DOSSIER_MAX_SNAPSHOTS, 40L);
        d.put(LaLimit.IDENTITY_IMPORT_DEFAULT, 1_000L);
        d.put(LaLimit.IDENTITY_IMPORT_MAX, 10_000L);
        d.put(LaLimit.IDENTITY_IMPORT_TIMEOUT_S, 10L);
        d.put(LaLimit.INPUT_ID_LENGTH, 512L);
        d.put(LaLimit.INPUT_MAX_IDS, 1_000L);
        d.put(LaLimit.INPUT_NOTE_LENGTH, 2_000L);
        d.put(LaLimit.INPUT_PURPOSE_LENGTH, 1_000L);
        d.put(LaLimit.INPUT_REVEAL_IDS, 100L);
        d.put(LaLimit.INPUT_LINK_KINDS, 100L);
        d.put(LaLimit.INPUT_LIST_MEMBERS, 5_000L);
        d.put(LaLimit.INPUT_PROJECTION_MAPPINGS, 16L);
        d.put(LaLimit.INPUT_REFERENCES, 200L);
        d.put(LaLimit.INPUT_REFERENCE_ID_LENGTH, 256L);
        d.put(LaLimit.INPUT_REFERENCE_URL_LENGTH, 2_048L);
        d.put(LaLimit.INPUT_REFERENCE_LABEL_LENGTH, 200L);
        d.put(LaLimit.INPUT_DRAFT_LABEL_LENGTH, 80L);
        d.put(LaLimit.INPUT_EMBED_ACTIVE_GRANTS, 20L);
        d.put(LaLimit.INPUT_ENTITY_TYPES, 64L);
        d.put(LaLimit.INPUT_RECURRING_EXCLUDES, (long) InvestigationTime.DEFAULT_MAX_RULES);
        d.put(LaLimit.INPUT_EXCLUDES, (long) InvestigationTime.DEFAULT_MAX_EXCLUDES);
        d.put(LaLimit.INPUT_IDENTITY_KEY_LENGTH, 512L);
        d.put(LaLimit.INDEX_TRAVERSAL_DEPTH, (long) IndexedTraversal.DEFAULT_MAX_DEPTH);
        d.put(LaLimit.INDEX_FRONTIER_CAP, (long) IndexedTraversal.DEFAULT_FRONTIER_CAP);
        d.put(LaLimit.INDEX_MAX_DELTAS, (long) IndexPlan.DEFAULT_MAX_DELTAS);
        return d;
    }

    @Test
    void p3ItemsDefaultToTheDecidedValuesInsideTheirRange() {
        List<String> wrong = new ArrayList<>();
        p3Defaults().forEach((l, v) -> {
            if (!v.equals(l.defaultValue())) wrong.add(l.path() + ": catalogue " + l.defaultValue() + ", decided " + v);
            if (!l.accepts(v)) wrong.add(l.path() + ": the decided " + v + " is outside " + l.min() + ".." + l.max());
        });
        assertTrue(wrong.isEmpty(), "P3 defaults: " + wrong);
        // CL-11: a default hop count never above the ceiling; CL-10: every default never above its own max
        assertTrue(LaLimit.CONNECT_DEFAULT_HOPS.defaultValue().longValue() <= LaLimit.CONNECT_MAX_HOPS.defaultValue().longValue());
        assertTrue(LaLimit.TRAVERSAL_DEPTH_DEFAULT.defaultValue().longValue() <= LaLimit.TRAVERSAL_DEPTH_MAX.defaultValue().longValue());
        assertTrue(LaLimit.INVESTIGATION_EXPAND_BUDGET_DEFAULT.defaultValue().longValue()
                <= LaLimit.INVESTIGATION_EXPAND_BUDGET_MAX.defaultValue().longValue());
    }

    /** P3: the sites read LaLimits, so the constant each held is gone; one left behind would be a second, silent source. */
    @Test
    void p3SitesHoldNoConstantOfTheirOwn() {
        Map<Class<?>, List<String>> gone = new java.util.LinkedHashMap<>();
        gone.put(InvestigationRoutes.class, List.of("DEFAULT_EXPAND_BUDGET", "MAX_EXPAND_BUDGET", "MAX_FRONTIER", "ONLY_EXEMPT_MAX",
                "LOG_DEFAULT", "LOG_MAX", "LIST_DEFAULT", "LIST_MAX", "MAX_ID_LENGTH", "MAX_IDS", "MAX_NOTE_LENGTH",
                "MAX_PURPOSE_LENGTH", "MAX_REVEAL", "MAX_LINK_KINDS", "MAX_LIST_MEMBERS"));
        gone.put(ConnectSeeds.class, List.of("MAX_SEEDS", "MAX_HOPS"));
        gone.put(InvRoutes.class, List.of("DEFAULT_DEPTH", "MAX_DEPTH", "TRAVERSAL_TIMEOUT_SECONDS", "DEFAULT_PATHS", "MAX_PATHS",
                "DEFAULT_PAIRS", "MAX_PAIRS", "DEFAULT_EDGE_YIELD", "MAX_EDGE_YIELD", "DEFAULT_LIMIT", "MAX_LIMIT", "MAX_MAPPINGS",
                "NEIGHBOUR_PAGE_DEFAULT", "NEIGHBOUR_PAGE_MAX", "SNAPSHOT_LIST_DEFAULT", "SNAPSHOT_LIST_MAX"));
        gone.put(DatasetFilesRoutes.class, List.of("DEFAULT_LIMIT", "MAX_LIMIT"));
        gone.put(WorkingSetRoutes.class, List.of("DEFAULT_LIMIT", "MAX_LIMIT"));
        gone.put(PatternRoutes.class, List.of("MAX_LEGS", "DEFAULT_MATCHES", "MAX_MATCHES", "TIMEOUT_SECONDS", "TEMPORAL_MAX_ROWS",
                "TEMPORAL_DEFAULT_LIMIT", "TEMPORAL_MAX_LIMIT"));
        gone.put(ValueMeasures.class, List.of("MAX_ENTITIES", "TIMEOUT_SECONDS", "MAX_WINDOW_DAYS"));
        gone.put(WindowComparison.class, List.of("MAX_ENTITIES", "MAX_ROWS", "LIST_CAP"));
        gone.put(ReferenceRows.class, List.of("MAX_KEYS", "MAX_ROWS"));
        gone.put(CaseHistory.class, List.of("MAX_LIMIT", "MAX_KEY", "MAX_LINKED_INVESTIGATIONS", "MAX_ATTACHMENTS"));
        gone.put(DossierRoutes.class, List.of("MAX_STEPS", "MAX_SNAPSHOTS"));
        gone.put(EntityIdentityRoutes.class, List.of("IMPORT_DEFAULT_LIMIT", "IMPORT_MAX_LIMIT", "IMPORT_TIMEOUT_SECONDS", "MAX_KEY_LENGTH"));
        gone.put(InvestigationReferenceRoutes.class, List.of("MAX", "ID_MAX", "URL_MAX", "LABEL_MAX"));
        gone.put(InvestigationEmbedRoutes.class, List.of("MAX_LABEL"));
        gone.put(DraftRoutes.class, List.of("LABEL_MAX"));
        gone.put(EmbedGrants.class, List.of("MAX_ACTIVE"));
        gone.put(IndexedExpand.class, List.of("FRONTIER_CAP"));
        gone.put(IndexedTraversal.class, List.of("MAX_DEPTH", "FRONTIER_CAP"));
        gone.put(SqlGraphEngine.class, List.of("MAX_HOPS", "FRONTIER_CAP"));
        gone.put(IndexPlan.class, List.of("MAX_DELTAS"));
        gone.put(GeoRoutes.class, List.of("DEFAULT_LIMIT"));                     // P2: reads display.geo_point_cap
        gone.put(InvestigationTime.class, List.of("MAX_RULES", "MAX_EXCLUDES"));
        gone.put(com.gamma.entitystore.EntityTypes.class, List.of("MAX_TYPES"));
        gone.forEach((owner, fields) -> {
            for (String f : fields)
                assertThrows(NoSuchFieldException.class, () -> owner.getDeclaredField(f), owner.getSimpleName() + "." + f + " is back");
        });
        // the one constant a site keeps is the catalogue default itself, for a caller with no Space
        assertEquals(LaLimit.INPUT_EMBED_ACTIVE_GRANTS.defaultValue().longValue(), (long) EmbedGrants.DEFAULT_MAX_ACTIVE);
        assertEquals(new ValueMeasures.Fences(20_000, 10, 31), ValueMeasures.Fences.DEFAULT);
    }

    @Test
    void everyDefaultIsTheCodeConstantItReplaces() throws Exception {
        Map<LaLimit, Long> code = new EnumMap<>(LaLimit.class);
        code.put(LaLimit.INDEX_EDGES_PER_BUCKET, BucketFunction.EDGES_PER_BUCKET);
        code.put(LaLimit.INDEX_MIN_BUCKETS, (long) BucketFunction.MIN_BUCKETS);
        code.put(LaLimit.INDEX_MAX_BUCKETS, (long) BucketFunction.MAX_BUCKETS);
        GraphRunService.Limits std = GraphRunService.Limits.standard();
        code.put(LaLimit.GRAPH_RUN_MAX_NODES, (long) std.defaults().maxNodes());
        code.put(LaLimit.GRAPH_RUN_MAX_EDGES, (long) std.defaults().maxEdges());
        code.put(LaLimit.GRAPH_RUN_TIMEOUT_MS, std.defaults().timeoutMs());
        // P4: the graph run ceilings and pool are READ from the catalogue (Limits.standard()) - pinned by
        // installWideItemsAreReadFromTheCatalogueAndTheServerWideDocument below; the host-free index pool keeps its constant
        IndexBuildService.Limits idx = IndexBuildService.Limits.standard();
        code.put(LaLimit.INDEX_THREADS, (long) idx.threads());
        code.put(LaLimit.INDEX_QUEUE, (long) idx.queue());
        code.put(LaLimit.TRAVERSAL_THREADS, (long) com.gamma.sql.SqlSandboxPolicy.traversalThreads());
        Method heavy = DraftAdmission.class.getDeclaredMethod("defaultHeavyLimit");
        heavy.setAccessible(true);
        if (System.getProperty("inspecto.la.draft.heavy") == null)
            code.put(LaLimit.DRAFTS_HEAVY_JOBS, ((Number) heavy.invoke(null)).longValue());

        List<String> drift = new ArrayList<>();
        code.forEach((l, v) -> {
            if (!v.equals(l.defaultValue())) drift.add(l.path() + ": catalogue " + l.defaultValue() + ", code " + v);
        });
        assertTrue(drift.isEmpty(), "catalogue defaults that are not today's value: " + drift);
    }

    /**
     * LA-CONFIG-LIMITS-1 P4: the install-wide items are read at their sites from the server-wide document, else the catalogue
     * default - so with no document every site answers the catalogue default, and a stated value reaches the site.
     */
    @Test
    void installWideItemsAreReadFromTheCatalogueAndTheServerWideDocument(@TempDir Path dir) throws Exception {
        Path prior = LaLimits.installDocPath();
        try {
            LaLimits.installDocPath(null);
            GraphRunService.Limits std = GraphRunService.Limits.standard();
            assertEquals(LaLimit.GRAPH_RUN_CEILING_NODES.defaultValue(), (long) std.ceilings().maxNodes());
            assertEquals(LaLimit.GRAPH_RUN_CEILING_EDGES.defaultValue(), (long) std.ceilings().maxEdges());
            assertEquals(LaLimit.GRAPH_RUN_CEILING_TIMEOUT_MS.defaultValue(), std.ceilings().timeoutMs());
            assertEquals(LaLimit.GRAPH_RUN_THREADS.defaultValue(), (long) std.threads());
            assertEquals(LaLimit.GRAPH_RUN_QUEUE.defaultValue(), (long) std.queue());
            assertEquals(IndexBuildService.Limits.standard(), IndexRoutes.installLimits());
            assertEquals(BucketFunction.Sizing.DEFAULT, IndexRoutes.installSizing());

            Path doc = dir.resolve(LaLimits.INSTALL_FILE);
            LaLimits.installDocPath(doc);
            Map<LaLimit, Number> set = new EnumMap<>(LaLimit.class);
            set.put(LaLimit.GRAPH_RUN_CEILING_NODES, 1_234L);
            set.put(LaLimit.GRAPH_RUN_CEILING_TIMEOUT_MS, 9_000L);
            set.put(LaLimit.GRAPH_RUN_THREADS, 5L);
            set.put(LaLimit.INDEX_THREADS, 3L);
            set.put(LaLimit.INDEX_QUEUE, 7L);
            set.put(LaLimit.INDEX_EDGES_PER_BUCKET, 2_000L);
            set.put(LaLimit.INDEX_BUILD_MEMORY_PCT, 50L);
            LaLimits.writeInstall(doc, set);
            std = GraphRunService.Limits.standard();
            assertEquals(1_234, std.ceilings().maxNodes());
            assertEquals(9_000, std.ceilings().timeoutMs());
            assertEquals(5, std.threads());
            assertEquals(LaLimit.GRAPH_RUN_CEILING_EDGES.defaultValue(), (long) std.ceilings().maxEdges(), "an unstated item keeps its default");
            assertEquals(3, IndexRoutes.installLimits().threads());
            assertEquals(7, IndexRoutes.installLimits().queue());
            assertEquals(new BucketFunction.Sizing(2_000L, 16, 1_024), IndexRoutes.installSizing());
            assertEquals(IndexBuildService.memoryLimit(50, com.gamma.util.DuckDbUtil.physicalMemoryBytes()), IndexRoutes.installHost().memoryLimit());
        } finally {
            LaLimits.installDocPath(prior);
        }
    }
}
