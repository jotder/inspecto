package com.gamma.la.api;

import com.gamma.entitystore.LaLimit;
import com.gamma.la.core.Algorithm;
import com.gamma.la.core.GraphRunService;
import com.gamma.la.core.InvestigationTime;
import com.gamma.la.storage.BucketFunction;
import com.gamma.la.storage.IndexPlan;
import com.gamma.la.storage.IndexedTraversal;
import com.gamma.la.storage.SqlGraphEngine;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code LA-CONFIG-LIMITS-1}: an item whose code site still holds a constant has its catalogue default pinned to that
 * constant (reflection, since most are private), so the two cannot drift until the phase that rewires the site moves both
 * together. P3 rewired the Space analysis limits: their sites read {@code LaLimits} (the constant is GONE, asserted here,
 * and the real-HTTP tests prove each site honours a Space-stated value), and their defaults are the decided ones (CL-10,
 * CL-11, CL-15). And there is exactly one {@code browser_algorithms.<id>} item per {@link Algorithm}.
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

    @Test
    void oneBrowserAlgorithmItemPerAlgorithm() {
        Set<String> items = Arrays.stream(LaLimit.values()).map(LaLimit::path).filter(p -> p.startsWith("browser_algorithms."))
                .map(p -> p.substring("browser_algorithms.".length())).collect(Collectors.toCollection(TreeSet::new));
        Set<String> algorithms = Arrays.stream(Algorithm.values()).map(Algorithm::id).collect(Collectors.toCollection(TreeSet::new));
        assertEquals(algorithms, items);
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
        code.put(LaLimit.DISPLAY_GEO_POINT_CAP, constant(GeoRoutes.class, "DEFAULT_LIMIT"));
        code.put(LaLimit.INDEX_EDGES_PER_BUCKET, BucketFunction.EDGES_PER_BUCKET);
        code.put(LaLimit.INDEX_MIN_BUCKETS, (long) BucketFunction.MIN_BUCKETS);
        code.put(LaLimit.INDEX_MAX_BUCKETS, (long) BucketFunction.MAX_BUCKETS);
        GraphRunService.Limits std = GraphRunService.Limits.standard();
        code.put(LaLimit.GRAPH_RUN_MAX_NODES, (long) std.defaults().maxNodes());
        code.put(LaLimit.GRAPH_RUN_MAX_EDGES, (long) std.defaults().maxEdges());
        code.put(LaLimit.GRAPH_RUN_TIMEOUT_MS, std.defaults().timeoutMs());
        code.put(LaLimit.GRAPH_RUN_CEILING_NODES, (long) std.ceilings().maxNodes());
        code.put(LaLimit.GRAPH_RUN_CEILING_EDGES, (long) std.ceilings().maxEdges());
        code.put(LaLimit.GRAPH_RUN_CEILING_TIMEOUT_MS, std.ceilings().timeoutMs());
        code.put(LaLimit.GRAPH_RUN_THREADS, (long) std.threads());
        code.put(LaLimit.GRAPH_RUN_QUEUE, (long) std.queue());
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
}
