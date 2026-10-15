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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code LA-CONFIG-LIMITS-1} P1 ships TODAY's values: every {@link LaLimit} default equals the code constant it will
 * replace, read here from that constant (reflection, since most are private), so the catalogue cannot drift from the
 * code until the phase that rewires the site moves both together. And there is exactly one
 * {@code browser_algorithms.<id>} item per {@link Algorithm}.
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

    @Test
    void everyDefaultIsTheCodeConstantItReplaces() throws Exception {
        Map<LaLimit, Long> code = new EnumMap<>(LaLimit.class);
        code.put(LaLimit.INVESTIGATION_EXPAND_BUDGET_DEFAULT, constant(InvestigationRoutes.class, "DEFAULT_EXPAND_BUDGET"));
        code.put(LaLimit.INVESTIGATION_EXPAND_BUDGET_MAX, constant(InvestigationRoutes.class, "MAX_EXPAND_BUDGET"));
        code.put(LaLimit.INVESTIGATION_MAX_FRONTIER, constant(InvestigationRoutes.class, "MAX_FRONTIER"));
        code.put(LaLimit.INVESTIGATION_ONLY_EXEMPT_MAX, constant(InvestigationRoutes.class, "ONLY_EXEMPT_MAX"));
        code.put(LaLimit.INVESTIGATION_LOG_PAGE_DEFAULT, constant(InvestigationRoutes.class, "LOG_DEFAULT"));
        code.put(LaLimit.INVESTIGATION_LOG_PAGE_MAX, constant(InvestigationRoutes.class, "LOG_MAX"));
        code.put(LaLimit.INVESTIGATION_LIST_PAGE_DEFAULT, constant(InvestigationRoutes.class, "LIST_DEFAULT"));
        code.put(LaLimit.INVESTIGATION_LIST_PAGE_MAX, constant(InvestigationRoutes.class, "LIST_MAX"));
        code.put(LaLimit.INPUT_ID_LENGTH, constant(InvestigationRoutes.class, "MAX_ID_LENGTH"));
        code.put(LaLimit.INPUT_MAX_IDS, constant(InvestigationRoutes.class, "MAX_IDS"));
        code.put(LaLimit.INPUT_NOTE_LENGTH, constant(InvestigationRoutes.class, "MAX_NOTE_LENGTH"));
        code.put(LaLimit.INPUT_PURPOSE_LENGTH, constant(InvestigationRoutes.class, "MAX_PURPOSE_LENGTH"));
        code.put(LaLimit.INPUT_REVEAL_IDS, constant(InvestigationRoutes.class, "MAX_REVEAL"));
        code.put(LaLimit.INPUT_LINK_KINDS, constant(InvestigationRoutes.class, "MAX_LINK_KINDS"));
        code.put(LaLimit.INPUT_LIST_MEMBERS, constant(InvestigationRoutes.class, "MAX_LIST_MEMBERS"));
        code.put(LaLimit.CONNECT_MAX_SEEDS, constant(ConnectSeeds.class, "MAX_SEEDS"));
        code.put(LaLimit.CONNECT_MAX_HOPS, constant(ConnectSeeds.class, "MAX_HOPS"));
        code.put(LaLimit.CONNECT_DEFAULT_HOPS, constant(ConnectSeeds.class, "MAX_HOPS"));   // the hop default IS the ceiling today
        code.put(LaLimit.TRAVERSAL_DEPTH_DEFAULT, constant(InvRoutes.class, "DEFAULT_DEPTH"));
        code.put(LaLimit.TRAVERSAL_DEPTH_MAX, constant(InvRoutes.class, "MAX_DEPTH"));
        code.put(LaLimit.TRAVERSAL_TIMEOUT_S, constant(InvRoutes.class, "TRAVERSAL_TIMEOUT_SECONDS"));
        code.put(LaLimit.TRAVERSAL_PATHS_DEFAULT, constant(InvRoutes.class, "DEFAULT_PATHS"));
        code.put(LaLimit.TRAVERSAL_PATHS_MAX, constant(InvRoutes.class, "MAX_PATHS"));
        code.put(LaLimit.TRAVERSAL_PAIRS_DEFAULT, constant(InvRoutes.class, "DEFAULT_PAIRS"));
        code.put(LaLimit.TRAVERSAL_PAIRS_MAX, constant(InvRoutes.class, "MAX_PAIRS"));
        code.put(LaLimit.TRAVERSAL_EDGE_YIELD_DEFAULT, constant(InvRoutes.class, "DEFAULT_EDGE_YIELD"));
        code.put(LaLimit.TRAVERSAL_EDGE_YIELD_MAX, constant(InvRoutes.class, "MAX_EDGE_YIELD"));
        code.put(LaLimit.TRAVERSAL_ROWS_DEFAULT, constant(InvRoutes.class, "DEFAULT_LIMIT"));
        code.put(LaLimit.TRAVERSAL_ROWS_MAX, constant(InvRoutes.class, "MAX_LIMIT"));
        code.put(LaLimit.INPUT_PROJECTION_MAPPINGS, constant(InvRoutes.class, "MAX_MAPPINGS"));
        code.put(LaLimit.NEIGHBOURS_PAGE_DEFAULT, constant(InvRoutes.class, "NEIGHBOUR_PAGE_DEFAULT"));
        code.put(LaLimit.NEIGHBOURS_PAGE_MAX, constant(InvRoutes.class, "NEIGHBOUR_PAGE_MAX"));
        code.put(LaLimit.SNAPSHOTS_PAGE_DEFAULT, constant(InvRoutes.class, "SNAPSHOT_LIST_DEFAULT"));
        code.put(LaLimit.SNAPSHOTS_PAGE_MAX, constant(InvRoutes.class, "SNAPSHOT_LIST_MAX"));
        code.put(LaLimit.FILES_PAGE_DEFAULT, constant(DatasetFilesRoutes.class, "DEFAULT_LIMIT"));
        code.put(LaLimit.FILES_PAGE_MAX, constant(DatasetFilesRoutes.class, "MAX_LIMIT"));
        code.put(LaLimit.WORKING_SET_PAGE_DEFAULT, constant(WorkingSetRoutes.class, "DEFAULT_LIMIT"));
        code.put(LaLimit.WORKING_SET_PAGE_MAX, constant(WorkingSetRoutes.class, "MAX_LIMIT"));
        code.put(LaLimit.PATTERN_MAX_LEGS, constant(PatternRoutes.class, "MAX_LEGS"));
        code.put(LaLimit.PATTERN_MATCHES_DEFAULT, constant(PatternRoutes.class, "DEFAULT_MATCHES"));
        code.put(LaLimit.PATTERN_MATCHES_MAX, constant(PatternRoutes.class, "MAX_MATCHES"));
        code.put(LaLimit.PATTERN_TIMEOUT_S, constant(PatternRoutes.class, "TIMEOUT_SECONDS"));
        code.put(LaLimit.PATTERN_TEMPORAL_MAX_ROWS, constant(PatternRoutes.class, "TEMPORAL_MAX_ROWS"));
        code.put(LaLimit.PATTERN_TEMPORAL_LIMIT_DEFAULT, constant(PatternRoutes.class, "TEMPORAL_DEFAULT_LIMIT"));
        code.put(LaLimit.PATTERN_TEMPORAL_LIMIT_MAX, constant(PatternRoutes.class, "TEMPORAL_MAX_LIMIT"));
        code.put(LaLimit.MEASURES_MAX_ENTITIES, (long) ValueMeasures.MAX_ENTITIES);
        code.put(LaLimit.MEASURES_TIMEOUT_S, (long) ValueMeasures.TIMEOUT_SECONDS);
        code.put(LaLimit.MEASURES_MAX_WINDOW_DAYS, (long) ValueMeasures.MAX_WINDOW_DAYS);
        code.put(LaLimit.COMPARISON_MAX_ENTITIES, constant(WindowComparison.class, "MAX_ENTITIES"));
        code.put(LaLimit.COMPARISON_MAX_ROWS, constant(WindowComparison.class, "MAX_ROWS"));
        code.put(LaLimit.COMPARISON_LIST_CAP, constant(WindowComparison.class, "LIST_CAP"));
        code.put(LaLimit.REFERENCE_MAX_KEYS, constant(ReferenceRows.class, "MAX_KEYS"));
        code.put(LaLimit.REFERENCE_MAX_ROWS, constant(ReferenceRows.class, "MAX_ROWS"));
        code.put(LaLimit.CASE_HISTORY_PAGE_MAX, constant(CaseHistory.class, "MAX_LIMIT"));
        code.put(LaLimit.CASE_HISTORY_MAX_KEY_LENGTH, constant(CaseHistory.class, "MAX_KEY"));
        code.put(LaLimit.CASE_HISTORY_MAX_LINKED_INVESTIGATIONS, constant(CaseHistory.class, "MAX_LINKED_INVESTIGATIONS"));
        code.put(LaLimit.CASE_HISTORY_MAX_ATTACHMENTS, constant(CaseHistory.class, "MAX_ATTACHMENTS"));
        code.put(LaLimit.DOSSIER_MAX_STEPS, constant(DossierRoutes.class, "MAX_STEPS"));
        code.put(LaLimit.DOSSIER_MAX_SNAPSHOTS, constant(DossierRoutes.class, "MAX_SNAPSHOTS"));
        code.put(LaLimit.IDENTITY_IMPORT_DEFAULT, constant(EntityIdentityRoutes.class, "IMPORT_DEFAULT_LIMIT"));
        code.put(LaLimit.IDENTITY_IMPORT_MAX, constant(EntityIdentityRoutes.class, "IMPORT_MAX_LIMIT"));
        code.put(LaLimit.IDENTITY_IMPORT_TIMEOUT_S, constant(EntityIdentityRoutes.class, "IMPORT_TIMEOUT_SECONDS"));
        code.put(LaLimit.INPUT_IDENTITY_KEY_LENGTH, constant(EntityIdentityRoutes.class, "MAX_KEY_LENGTH"));
        code.put(LaLimit.INPUT_REFERENCES, constant(InvestigationReferenceRoutes.class, "MAX"));
        code.put(LaLimit.INPUT_REFERENCE_ID_LENGTH, constant(InvestigationReferenceRoutes.class, "ID_MAX"));
        code.put(LaLimit.INPUT_REFERENCE_URL_LENGTH, constant(InvestigationReferenceRoutes.class, "URL_MAX"));
        code.put(LaLimit.INPUT_REFERENCE_LABEL_LENGTH, constant(InvestigationReferenceRoutes.class, "LABEL_MAX"));
        code.put(LaLimit.INPUT_DRAFT_LABEL_LENGTH, constant(DraftRoutes.class, "LABEL_MAX"));
        code.put(LaLimit.INPUT_EMBED_ACTIVE_GRANTS, constant(EmbedGrants.class, "MAX_ACTIVE"));
        code.put(LaLimit.INPUT_RECURRING_EXCLUDES, (long) InvestigationTime.MAX_RULES);
        code.put(LaLimit.INPUT_EXCLUDES, (long) InvestigationTime.MAX_EXCLUDES);
        code.put(LaLimit.DISPLAY_GEO_POINT_CAP, constant(GeoRoutes.class, "DEFAULT_LIMIT"));
        code.put(LaLimit.INDEX_TRAVERSAL_DEPTH, (long) IndexedTraversal.MAX_DEPTH);
        code.put(LaLimit.INDEX_FRONTIER_CAP, (long) IndexedTraversal.FRONTIER_CAP);
        code.put(LaLimit.INDEX_MAX_DELTAS, (long) IndexPlan.MAX_DELTAS);
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
