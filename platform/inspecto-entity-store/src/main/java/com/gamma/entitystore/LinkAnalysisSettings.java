package com.gamma.entitystore;

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
                                   Integer seedByDistinctCap, GraphRun graphRun, Index index, Drafts drafts,
                                   Integer maxSetBytes) {

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

    /**
     * The edge/node index's per-Space knobs (LA separation D-3 step 4; {@code index} in {@code link-analysis.toon}):
     * {@code enabled} - whether reads may use an index (default OFF, D-3 Decision 8; nothing reads one yet, the flag lands
     * first); {@code maxDiskBytes} - the build refuses an estimate above it (0 or unstated = no limit);
     * {@code keepVersions} - published versions kept per index (default 2); {@code threads} / {@code queue} - the build
     * workers and waiting line. Every field is optional ({@code null} = the shipped default).
     */
    public record Index(Boolean enabled, Long maxDiskBytes, Integer keepVersions, Integer threads, Integer queue) {
        public static final Index NONE = new Index(null, null, null, null, null);
        public static final int DEFAULT_KEEP_VERSIONS = 2;
        public static final int DEFAULT_THREADS = 1;
        public static final int DEFAULT_QUEUE = 4;

        boolean isNone() {
            return enabled == null && maxDiskBytes == null && keepVersions == null && threads == null && queue == null;
        }

        /** Whether reads may use an index: only an explicit {@code true}. */
        public boolean enabledInForce() {
            return Boolean.TRUE.equals(enabled);
        }

        /** The disk budget in force in bytes; 0 = no limit. */
        public long maxDiskBytesInForce() {
            return maxDiskBytes != null ? maxDiskBytes : 0L;
        }

        public int keepVersionsInForce() {
            return keepVersions != null ? keepVersions : DEFAULT_KEEP_VERSIONS;
        }

        public int threadsInForce() {
            return threads != null ? threads : DEFAULT_THREADS;
        }

        public int queueInForce() {
            return queue != null ? queue : DEFAULT_QUEUE;
        }
    }

    /**
     * The Draft lifecycle's per-Space knobs (LA-DRAFT-PROMOTE-COST-1; {@code drafts} in {@code link-analysis.toon}):
     * {@code maxOpen} - the most open Drafts one Space holds (D21, default {@link #DEFAULT_MAX_OPEN});
     * {@code hibernateAfterMinutes} - idle minutes before a Draft hibernates (D7-Q5, default 60);
     * {@code expireAfterDays} - idle days before a Draft expires (D7-Q5, default 30). Every field is optional
     * ({@code null} = the shipped default); the expiry must stay longer than the hibernation.
     */
    public record Drafts(Integer maxOpen, Integer hibernateAfterMinutes, Integer expireAfterDays) {
        public static final Drafts NONE = new Drafts(null, null, null);
        public static final int DEFAULT_MAX_OPEN = 50;
        public static final int DEFAULT_HIBERNATE_AFTER_MINUTES = 60;
        public static final int DEFAULT_EXPIRE_AFTER_DAYS = 30;

        boolean isNone() {
            return maxOpen == null && hibernateAfterMinutes == null && expireAfterDays == null;
        }

        public int maxOpenInForce() {
            return maxOpen != null ? maxOpen : DEFAULT_MAX_OPEN;
        }

        public java.time.Duration hibernateAfterInForce() {
            return java.time.Duration.ofMinutes(hibernateAfterMinutes != null ? hibernateAfterMinutes : DEFAULT_HIBERNATE_AFTER_MINUTES);
        }

        public java.time.Duration expireAfterInForce() {
            return java.time.Duration.ofDays(expireAfterDays != null ? expireAfterDays : DEFAULT_EXPIRE_AFTER_DAYS);
        }

        /** Whether the expiry is longer than the hibernation (a Draft must sleep before it can die). */
        public boolean ordered() {
            return expireAfterInForce().compareTo(hibernateAfterInForce()) > 0;
        }
    }

    public static final String FILE = "link-analysis.toon";
    public static final LinkAnalysisSettings EMPTY = new LinkAnalysisSettings(null, null, null, null, null, null, null, null, null, null, null, null, null);

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

    /** The shipped default of {@code max_set_bytes}: 64 MiB of sealed Working Set text per step. */
    public static final int DEFAULT_MAX_SET_BYTES = 64 * 1024 * 1024;

    /** The per-set size limit in force, in bytes: the stated one, else {@link #DEFAULT_MAX_SET_BYTES}. */
    public int effectiveMaxSetBytes() {
        return maxSetBytes != null ? maxSetBytes : DEFAULT_MAX_SET_BYTES;
    }

    /** The graph-run knobs in force: the stated ones; a field never stated is {@code null} inside (the service's default). */
    public GraphRun effectiveGraphRun() {
        return graphRun != null ? graphRun : GraphRun.NONE;
    }

    /** The index knobs in force: the stated ones; a field never stated is {@code null} inside (use the {@code *InForce} accessors). */
    public Index effectiveIndex() {
        return index != null ? index : Index.NONE;
    }

    /** The Draft lifecycle knobs in force: the stated ones; a field never stated is {@code null} inside (use the {@code *InForce} accessors). */
    public Drafts effectiveDrafts() {
        return drafts != null ? drafts : Drafts.NONE;
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
    public void write(Path path) throws IOException {
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
        if (maxSetBytes != null) m.put("max_set_bytes", maxSetBytes);
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
        if (index != null && !index.isNone()) {
            Map<String, Object> x = new LinkedHashMap<>();
            if (index.enabled() != null) x.put("enabled", index.enabled());
            if (index.maxDiskBytes() != null) x.put("max_disk_bytes", index.maxDiskBytes());
            if (index.keepVersions() != null) x.put("keep_versions", index.keepVersions());
            if (index.threads() != null) x.put("threads", index.threads());
            if (index.queue() != null) x.put("queue", index.queue());
            m.put("index", x);
        }
        if (drafts != null && !drafts.isNone()) {
            Map<String, Object> x = new LinkedHashMap<>();
            if (drafts.maxOpen() != null) x.put("max_open", drafts.maxOpen());
            if (drafts.hibernateAfterMinutes() != null) x.put("hibernate_after_minutes", drafts.hibernateAfterMinutes());
            if (drafts.expireAfterDays() != null) x.put("expire_after_days", drafts.expireAfterDays());
            m.put("drafts", x);
        }
        AtomicFiles.write(path, JToon.encode(m).getBytes(StandardCharsets.UTF_8), ".link-analysis-");
    }

    /** The settings of the Space whose config root is {@code writeRoot}; {@link #EMPTY} when there is none. */
    public static LinkAnalysisSettings forRoot(Path writeRoot) {
        return writeRoot == null ? EMPTY : read(writeRoot.resolve(FILE));
    }

    /** Read {@code link-analysis.toon} at {@code path}; missing/unreadable → {@link #EMPTY} (inherit everything). */
    public static LinkAnalysisSettings read(Path path) {
        if (path == null || !Files.exists(path)) return EMPTY;
        try {
            Map<String, Object> m = ToonHelper.load(path.toString());
            return new LinkAnalysisSettings(optInt(m, "projection_node_cap"), optInt(m, "analysis_node_cap"),
                    optInt(m, "suspicion_node_cap"), maskingMode(ToonHelper.opt(m, "masking_mode", "")),
                    optInt(m, "four_eyes_budget_above"), optInt(m, "four_eyes_fan_out_above"),
                    entityTypes(m.get("entity_types")), optInt(m, "merged_distinct_cap"),
                    optInt(m, "seed_by_distinct_cap"), graphRun(m.get("graph_run")), index(m.get("index")), drafts(m.get("drafts")),
                    optInt(m, "max_set_bytes"));
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

    /**
     * A stored {@code drafts} block, else {@code null} = every knob inherits; a bad value inside costs only that knob.
     * ⚠ A stored pair whose expiry is not longer than its hibernation reads as {@code null} (all defaults): the write
     * route refuses it, so only a hand edit gets here, and a Draft must never expire before it can hibernate.
     * An empty TOON key arrives as {@code {}} (not a Map of values): it has no knob and reads as {@code null}.
     */
    @SuppressWarnings("unchecked")
    private static Drafts drafts(Object raw) {
        if (!(raw instanceof Map<?, ?>)) return null;
        Map<String, Object> g = (Map<String, Object>) raw;
        Drafts r = new Drafts(optInt(g, "max_open"), optInt(g, "hibernate_after_minutes"), optInt(g, "expire_after_days"));
        return r.isNone() || !r.ordered() ? null : r;
    }

    /** A stored {@code index} block, else {@code null} = every knob inherits; a bad value inside costs only that knob. */
    @SuppressWarnings("unchecked")
    private static Index index(Object raw) {
        if (!(raw instanceof Map<?, ?>)) return null;
        Map<String, Object> g = (Map<String, Object>) raw;
        String en = ToonHelper.opt(g, "enabled", "").trim().toLowerCase(Locale.ROOT);
        Boolean enabled = en.equals("true") ? Boolean.TRUE : en.equals("false") ? Boolean.FALSE : null;
        Long maxDisk = null;
        String d = ToonHelper.opt(g, "max_disk_bytes", "").trim();
        if (!d.isEmpty()) {
            try {
                long v = Long.parseLong(d);
                if (v >= 0) maxDisk = v;
            } catch (NumberFormatException ignored) {
                // unparseable = inherit
            }
        }
        Index r = new Index(enabled, maxDisk, optInt(g, "keep_versions"), optInt(g, "threads"), optInt(g, "queue"));
        return r.isNone() ? null : r;
    }

    /** A declared masking mode (case-insensitive), or {@code null} for anything else. */
    public static String maskingMode(String raw) {
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
