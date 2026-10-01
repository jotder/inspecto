package com.gamma.control;

import com.gamma.config.spec.ConfigSpecs;
import com.gamma.util.AtomicFiles;
import com.gamma.util.ToonHelper;
import dev.toonformat.jtoon.JToon;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Per-space Link Analysis settings — the three node caps the Link Analysis studio applies before it
 * projects entities, before it runs the super-linear graph algorithms, and before it runs suspicion
 * score, which carries its OWN lower ceiling because its cost is quadratic while the other 26
 * algorithms are trivial at the shared cap (decision D-S3); plus the Investigation controls of LA-19:
 * the entity {@code maskingMode} (D-U6) and the two four-eyes thresholds (D-U7); plus the space's
 * {@link EntityTypes Entity Types} (LA-17, design §4.1); plus {@code mergedDistinctCap}, the distinct values per bound
 * column a merged {@code expand} may scan to find a group's member values (LA-17 merged traversal, operator
 * 2026-09-30; default {@link #DEFAULT_MERGED_DISTINCT_CAP}, refused above it, never sampled) and its {@code seedBy}
 * twin {@code seedByDistinctCap} (default {@link #DEFAULT_SEED_BY_DISTINCT_CAP}, same posture); plus the {@link GraphRun}
 * knobs of the server-side graph-run service (D-4). Persisted as
 * {@code link-analysis.toon} in the space's config tree (crash-safe TOON, mirroring {@link GeoSettings}
 * and {@link SchedulerSettings}); the keys are declared in {@link ConfigSpecs#linkAnalysisSettings()}.
 *
 * <p><b>{@code null} means "inherit the shipped default", never "unbounded"</b> — the
 * {@link SchedulerSettings} convention. The caps were measured on one host against one data shape, so a
 * space that stores nothing must keep tracking whatever the client ships, not fall off a cliff into an
 * unbounded run. A key is written only when stated, so an absent key stays distinguishable from a stated
 * value and a save that never mentioned a cap cannot seize its provenance from the default. The shipped
 * default of {@code maskingMode} is {@code typed}; of each four-eyes threshold, "no threshold".
 *
 * <p>Like {@code branding.toon}, the filename is deliberately not a {@code *_pipeline.toon}-style suffix,
 * so recursive config discovery never mistakes it for a runnable config. A missing or unreadable file
 * reads as {@link #EMPTY} (the {@code BrandingSettings} posture — settings never fail a boot). ⚠ A stored
 * masking mode that is not one of the declared modes also reads as {@code null} — i.e. the {@code typed}
 * default, the masking side, never {@code none}. Likewise a stored {@code entity_types} list that fails
 * {@link EntityTypes#parse} reads as {@code null} — the seeded {@link EntityTypes#DEFAULTS} — without costing
 * the other keys.
 */
public record LinkAnalysisSettings(Integer projectionNodeCap, Integer analysisNodeCap, Integer suspicionNodeCap,
                                   String maskingMode, Integer fourEyesBudgetAbove, Integer fourEyesFanOutAbove,
                                   List<EntityTypes.EntityType> entityTypes, Integer mergedDistinctCap,
                                   Integer seedByDistinctCap, GraphRun graphRun) {

    /**
     * The graph-run service's per-Space knobs (LA separation D-4 step 6; {@code graph_run} in {@code link-analysis.toon}):
     * the DEFAULT budget a run takes for a field its request leaves unstated, and the worker / waiting-queue sizes. Every
     * field is optional ({@code null} = the service's shipped default). A default above the hard server ceiling is
     * clamped by the service, and the clamp is echoed - the ceilings themselves are not a setting.
     */
    public record GraphRun(Integer maxNodes, Integer maxEdges, Integer timeoutMs, Integer threads, Integer queue,
                           Integer maxResultItems) {
        public static final GraphRun NONE = new GraphRun(null, null, null, null, null, null);

        boolean isNone() {
            return maxNodes == null && maxEdges == null && timeoutMs == null && threads == null && queue == null
                    && maxResultItems == null;
        }
    }


    public static final String FILE = "link-analysis.toon";
    static final LinkAnalysisSettings EMPTY = new LinkAnalysisSettings(null, null, null, null, null, null, null, null, null, null);

    /** The shipped default of {@code merged_distinct_cap}. */
    public static final int DEFAULT_MERGED_DISTINCT_CAP = 20_000;

    /** The merged-expand distinct-value cap in force: the stated one, else {@link #DEFAULT_MERGED_DISTINCT_CAP}. */
    public int effectiveMergedDistinctCap() {
        return mergedDistinctCap != null ? mergedDistinctCap : DEFAULT_MERGED_DISTINCT_CAP;
    }

    /** The shipped default of {@code seed_by_distinct_cap}. */
    public static final int DEFAULT_SEED_BY_DISTINCT_CAP = 20_000;

    /** The seedBy distinct-value cap in force: the stated one, else {@link #DEFAULT_SEED_BY_DISTINCT_CAP}. */
    public int effectiveSeedByDistinctCap() {
        return seedByDistinctCap != null ? seedByDistinctCap : DEFAULT_SEED_BY_DISTINCT_CAP;
    }

    /** The graph-run knobs in force: the stated ones; a field never stated is {@code null} inside (the service's default). */
    public GraphRun effectiveGraphRun() {
        return graphRun != null ? graphRun : GraphRun.NONE;
    }

    /** The masking mode in force: the stated one, else the declared default ({@code typed}). */
    public String effectiveMaskingMode() {
        return maskingMode != null ? maskingMode : ConfigSpecs.LINK_ANALYSIS_MASKING_MODES.get(0);
    }

    /** The Entity Types in force: the stated list, else {@link EntityTypes#DEFAULTS}. */
    public List<EntityTypes.EntityType> effectiveEntityTypes() {
        return entityTypes != null ? entityTypes : EntityTypes.DEFAULTS;
    }

    /** Write to {@code link-analysis.toon} at {@code path} (canonical TOON, crash-safe). */
    void write(Path path) throws IOException {
        Map<String, Object> m = new LinkedHashMap<>();
        if (projectionNodeCap != null) m.put("projection_node_cap", projectionNodeCap);
        if (analysisNodeCap != null) m.put("analysis_node_cap", analysisNodeCap);
        if (suspicionNodeCap != null) m.put("suspicion_node_cap", suspicionNodeCap);
        if (maskingMode != null) m.put("masking_mode", maskingMode);
        if (fourEyesBudgetAbove != null) m.put("four_eyes_budget_above", fourEyesBudgetAbove);
        if (fourEyesFanOutAbove != null) m.put("four_eyes_fan_out_above", fourEyesFanOutAbove);
        if (entityTypes != null) m.put("entity_types", EntityTypes.shape(entityTypes));
        if (mergedDistinctCap != null) m.put("merged_distinct_cap", mergedDistinctCap);
        if (seedByDistinctCap != null) m.put("seed_by_distinct_cap", seedByDistinctCap);
        if (graphRun != null && !graphRun.isNone()) {
            Map<String, Object> g = new LinkedHashMap<>();
            if (graphRun.maxNodes() != null) g.put("max_nodes", graphRun.maxNodes());
            if (graphRun.maxEdges() != null) g.put("max_edges", graphRun.maxEdges());
            if (graphRun.timeoutMs() != null) g.put("timeout_ms", graphRun.timeoutMs());
            if (graphRun.threads() != null) g.put("threads", graphRun.threads());
            if (graphRun.queue() != null) g.put("queue", graphRun.queue());
            if (graphRun.maxResultItems() != null) g.put("max_result_items", graphRun.maxResultItems());
            m.put("graph_run", g);
        }
        AtomicFiles.write(path, JToon.encode(m).getBytes(StandardCharsets.UTF_8), ".link-analysis-");
    }

    /** The settings of the Space whose config root is {@code writeRoot}; {@link #EMPTY} when there is none. */
    public static LinkAnalysisSettings forRoot(Path writeRoot) {
        return writeRoot == null ? EMPTY : read(writeRoot.resolve(FILE));
    }

    /** Read {@code link-analysis.toon} at {@code path}; missing/unreadable → {@link #EMPTY} (inherit everything). */
    static LinkAnalysisSettings read(Path path) {
        if (path == null || !Files.exists(path)) return EMPTY;
        try {
            Map<String, Object> m = ToonHelper.load(path.toString());
            return new LinkAnalysisSettings(optInt(m, "projection_node_cap"), optInt(m, "analysis_node_cap"),
                    optInt(m, "suspicion_node_cap"), maskingMode(ToonHelper.opt(m, "masking_mode", "")),
                    optInt(m, "four_eyes_budget_above"), optInt(m, "four_eyes_fan_out_above"),
                    entityTypes(m.get("entity_types")), optInt(m, "merged_distinct_cap"),
                    optInt(m, "seed_by_distinct_cap"), graphRun(m.get("graph_run")));
        } catch (Exception e) {
            return EMPTY;
        }
    }

    /** A stored {@code graph_run} block, else {@code null} = every knob inherits; a bad value inside costs only that knob. */
    @SuppressWarnings("unchecked")
    private static GraphRun graphRun(Object raw) {
        if (!(raw instanceof Map<?, ?>)) return null;
        Map<String, Object> g = (Map<String, Object>) raw;
        GraphRun r = new GraphRun(optInt(g, "max_nodes"), optInt(g, "max_edges"), optInt(g, "timeout_ms"),
                optInt(g, "threads"), optInt(g, "queue"), optInt(g, "max_result_items"));
        return r.isNone() ? null : r;
    }

    /** A declared masking mode (case-insensitive), or {@code null} for anything else. */
    static String maskingMode(String raw) {
        String v = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        return ConfigSpecs.LINK_ANALYSIS_MASKING_MODES.contains(v) ? v : null;
    }

    /** A stored list that parses and validates, else {@code null} = inherit {@link EntityTypes#DEFAULTS}. */
    private static List<EntityTypes.EntityType> entityTypes(Object raw) {
        if (raw == null) return null;
        try {
            return EntityTypes.parse(raw);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** An absent, blank, unparseable or out-of-floor value reads as {@code null} = inherit the default. */
    private static Integer optInt(Map<String, Object> m, String key) {
        String raw = ToonHelper.opt(m, key, "");
        if (raw.isBlank()) return null;
        try {
            int v = Integer.parseInt(raw.trim());
            return v >= 1 ? v : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
