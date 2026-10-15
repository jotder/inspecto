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
 * twin {@code seedByDistinctCap} (default {@link #DEFAULT_SEED_BY_DISTINCT_CAP}, same posture); plus {@code hubThreshold},
 * the supernode threshold an {@code expand} flags candidates above (default {@link #DEFAULT_HUB_THRESHOLD}); plus the {@link GraphRun}
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
                                   Integer maxSetBytes, Long maxInvestigationBytes, Integer hubThreshold, Embed embed) {

    /** The fifteen-knob form that predates {@code embed}: the embed knobs inherit their shipped defaults. */
    public LinkAnalysisSettings(Integer projectionNodeCap, Integer analysisNodeCap, Integer suspicionNodeCap,
                                String maskingMode, Integer fourEyesBudgetAbove, Integer fourEyesFanOutAbove,
                                List<EntityTypes.EntityType> entityTypes, Integer mergedDistinctCap,
                                Integer seedByDistinctCap, GraphRun graphRun, Index index, Drafts drafts,
                                Integer maxSetBytes, Long maxInvestigationBytes, Integer hubThreshold) {
        this(projectionNodeCap, analysisNodeCap, suspicionNodeCap, maskingMode, fourEyesBudgetAbove, fourEyesFanOutAbove,
                entityTypes, mergedDistinctCap, seedByDistinctCap, graphRun, index, drafts, maxSetBytes,
                maxInvestigationBytes, hubThreshold, null);
    }

    /**
     * The embedded-Investigation link knobs (LA-EMBED-VIEW-1, operator 2026-10-12 D4; {@code embed} in {@code link-analysis.toon}):
     * {@code defaultTtlHours} - the lifetime of a link whose mint states none (default {@link #DEFAULT_TTL_HOURS}, 72 h);
     * {@code maxTtlHours} - the longest a link may live, a larger request is refused with 422 (default and CEILING
     * {@link #DEFAULT_MAX_TTL_HOURS}, 30 d; a Space admin may set it from 1 up to the validation ceiling {@link #CEILING_TTL_HOURS}, 365 d); {@code refuseClassifications} - column
     * classifications (the strictest of the Investigation's bound source/target columns, strictest-wins) for which minting is
     * refused with 422 (default none). Every field is optional ({@code null} = the shipped default).
     */
    public record Embed(Integer defaultTtlHours, Integer maxTtlHours, List<String> refuseClassifications, List<String> frameAncestors) {
        public static final Embed NONE = new Embed(null, null, null, null);
        public static final int DEFAULT_TTL_HOURS = 72;
        public static final int DEFAULT_MAX_TTL_HOURS = 30 * 24;
        /** The hard validation ceiling of {@code max_ttl_hours} (365 d): a Space may raise its cap above the 30 d default, never past this. */
        public static final int CEILING_TTL_HOURS = 365 * 24;

        public Embed(Integer defaultTtlHours, Integer maxTtlHours, List<String> refuseClassifications) {
            this(defaultTtlHours, maxTtlHours, refuseClassifications, null);
        }

        public Embed {
            refuseClassifications = refuseClassifications == null ? null : List.copyOf(refuseClassifications);
            frameAncestors = frameAncestors == null ? null : List.copyOf(frameAncestors);
        }

        boolean isNone() {
            return defaultTtlHours == null && maxTtlHours == null && refuseClassifications == null && frameAncestors == null;
        }

        /** The origins that may frame the embed shell (LA-EMBED-FRAME-HEADERS-1), normalised; empty = nobody (link-only). */
        public List<String> frameAncestorsInForce() {
            return frameAncestors != null ? frameAncestors : List.of();
        }

        /**
         * One frame-ancestor origin in its canonical form, or {@code null} when it is not an origin this host will put in a CSP:
         * {@code https://host[:port]} (plain {@code http} only for localhost / 127.0.0.1), no wildcard, path, query, userinfo, space or
         * quote - anything that could widen the header or smuggle a second directive.
         */
        public static String normaliseOrigin(Object raw) {
            if (!(raw instanceof String s)) return null;
            String v = s.strip().toLowerCase(java.util.Locale.ROOT);
            java.util.regex.Matcher m = ORIGIN.matcher(v);
            if (!m.matches()) return null;
            boolean local = m.group(2).equals("localhost") || m.group(2).equals("127.0.0.1");
            return m.group(1).equals("http") && !local ? null : v;
        }

        private static final java.util.regex.Pattern ORIGIN =
                java.util.regex.Pattern.compile("(https?)://((?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.)*[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?)(?::[0-9]{1,5})?");

        /** The longest a link may live, in hours: the stated cap, else {@link #DEFAULT_MAX_TTL_HOURS}. */
        public int maxTtlHoursInForce() {
            return (int) LaLimit.EMBED_MAX_TTL_HOURS.resolve(maxTtlHours);
        }

        /** The lifetime of a link whose mint states none: the stated default, else 72 h, never above the cap in force. */
        public int defaultTtlHoursInForce() {
            return Math.min((int) LaLimit.EMBED_DEFAULT_TTL_HOURS.resolve(defaultTtlHours), maxTtlHoursInForce());
        }

        /** The classifications minting is refused for, upper-case; empty = none. */
        public List<String> refuseClassificationsInForce() {
            return refuseClassifications != null ? refuseClassifications : List.of();
        }

        /** Whether {@code hours} is a lifetime the Space allows: 1 up to the cap in force. */
        public boolean allows(long hours) {
            return hours >= 1 && hours <= maxTtlHoursInForce();
        }
    }

    /**
     * The graph-run service's per-Space knobs (LA separation D-4 step 6; {@code graph_run} in {@code link-analysis.toon}):
     * the DEFAULT budget a run takes for a field its request leaves unstated. Every field is optional ({@code null} = the
     * service's shipped default). A default above the hard server ceiling is clamped by the service, and the clamp is
     * echoed. The ceilings and the worker / waiting-queue sizes are install-wide ({@code LA-CONFIG-LIMITS-1}:
     * {@code graph_run.ceiling_*}, {@code graph_run.threads}, {@code graph_run.queue} in {@code la-limits.toon}), not a Space knob.
     * {@code propagatedRiskWeights} is the Space's default per-hop weight list of {@code propagatedRisk} (1..6 values in
     * [0,1]) used when a run states no {@code weights}; {@code null} = {@link #DEFAULT_PROPAGATED_RISK_WEIGHTS}.
     */
    public record GraphRun(Integer maxNodes, Integer maxEdges, Integer timeoutMs,
                           Integer maxResultItems, List<Double> propagatedRiskWeights) {
        public static final GraphRun NONE = new GraphRun(null, null, null, null, null);
        /** The shipped per-hop weights of {@code propagatedRisk} (the algorithm's own default). */
        public static final List<Double> DEFAULT_PROPAGATED_RISK_WEIGHTS = List.of(1.0, 0.6, 0.35, 0.15);

        public GraphRun {
            propagatedRiskWeights = propagatedRiskWeights == null ? null : List.copyOf(propagatedRiskWeights);
        }

        boolean isNone() {
            return maxNodes == null && maxEdges == null && timeoutMs == null
                    && maxResultItems == null && propagatedRiskWeights == null;
        }

        /** Shipped result-list default; mirrors {@code GraphResultJson}. */
        public static final int DEFAULT_MAX_RESULT_ITEMS = 10_000;
        public static final int CEILING_MAX_RESULT_ITEMS = 1_000_000;

        /** The stated list length cap applied: a stated value above the hard ceiling is clamped, as the run service does. */
        public int maxResultItemsInForce() {
            return Math.min((int) LaLimit.GRAPH_RUN_MAX_RESULT_ITEMS.resolve(maxResultItems), CEILING_MAX_RESULT_ITEMS);
        }

        /** The per-hop weights in force: the stated list, else {@link #DEFAULT_PROPAGATED_RISK_WEIGHTS}. */
        public List<Double> propagatedRiskWeightsInForce() {
            return propagatedRiskWeights != null ? propagatedRiskWeights : DEFAULT_PROPAGATED_RISK_WEIGHTS;
        }

        /** Whether {@code w} is a valid weight list: 1..6 finite values, each in [0,1]. */
        public static boolean validWeights(List<Double> w) {
            if (w == null || w.isEmpty() || w.size() > 6) return false;
            for (Double d : w) if (d == null || !Double.isFinite(d) || d < 0 || d > 1) return false;
            return true;
        }
    }

    /**
     * The edge/node index's per-Space knobs (LA separation D-3 step 4; {@code index} in {@code link-analysis.toon}):
     * {@code enabled} - whether reads may use an index (default ON since 2026-10-10; an explicit {@code false} disables; a read
     * with no fresh published index still falls back to the flat Dataset); {@code maxDiskBytes} - the build refuses an estimate above it (0 or unstated = no limit);
     * {@code keepVersions} - published versions kept per index (default 2); {@code maxAgeHours} - the oldest an index may be and still serve a Dataset whose input files cannot be fingerprinted (default 24). Every field is optional ({@code null} = the shipped default).
     * The build workers, waiting line, memory and bucket sizing are install-wide ({@code LA-CONFIG-LIMITS-1}: {@code index.threads},
     * {@code index.queue}, {@code index.build_memory_pct}, {@code index.*_bucket*} in {@code la-limits.toon}), not a Space knob.
     */
    public record Index(Boolean enabled, Long maxDiskBytes, Integer keepVersions, Integer maxAgeHours) {
        public static final Index NONE = new Index(null, null, null, null);
        public static final int DEFAULT_MAX_AGE_HOURS = 24;
        public static final int DEFAULT_KEEP_VERSIONS = 2;

        boolean isNone() {
            return enabled == null && maxDiskBytes == null && keepVersions == null && maxAgeHours == null;
        }

        /** Whether reads may use an index: ON unless explicitly {@code false} (operator 2026-10-10; a read still needs a fresh published index). */
        public boolean enabledInForce() {
            return !Boolean.FALSE.equals(enabled);
        }

        /** The disk budget in force in bytes; 0 = no limit. */
        public long maxDiskBytesInForce() {
            return LaLimit.INDEX_MAX_DISK_BYTES.resolve(maxDiskBytes);
        }

        public int keepVersionsInForce() {
            return (int) LaLimit.INDEX_KEEP_VERSIONS.resolve(keepVersions);
        }

        /** How old (hours since the build) an index may be and still answer a read of a Dataset whose input files cannot be fingerprinted (LA-INDEX-MAX-AGE-1). */
        public int maxAgeHoursInForce() {
            return (int) LaLimit.INDEX_MAX_AGE_HOURS.resolve(maxAgeHours);
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
            return (int) LaLimit.DRAFTS_MAX_OPEN.resolve(maxOpen);
        }

        public java.time.Duration hibernateAfterInForce() {
            return java.time.Duration.ofMinutes(LaLimit.DRAFTS_HIBERNATE_AFTER_MINUTES.resolve(hibernateAfterMinutes));
        }

        public java.time.Duration expireAfterInForce() {
            return java.time.Duration.ofDays(LaLimit.DRAFTS_EXPIRE_AFTER_DAYS.resolve(expireAfterDays));
        }

        /** Whether the expiry is longer than the hibernation (a Draft must sleep before it can die). */
        public boolean ordered() {
            return expireAfterInForce().compareTo(hibernateAfterInForce()) > 0;
        }
    }

    public static final String FILE = "link-analysis.toon";
    public static final LinkAnalysisSettings EMPTY = new LinkAnalysisSettings(null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);

    /** The shipped default of {@code merged_distinct_cap}. */
    public static final int DEFAULT_MERGED_DISTINCT_CAP = 20_000;

    /** The merged-expand distinct-value cap in force: the stated one, else {@link #DEFAULT_MERGED_DISTINCT_CAP}. */
    public int effectiveMergedDistinctCap() {
        return (int) LaLimit.MERGED_DISTINCT_CAP.resolve(mergedDistinctCap);
    }

    /** The shipped default of {@code seed_by_distinct_cap}. */
    public static final int DEFAULT_SEED_BY_DISTINCT_CAP = 20_000;

    /** The seedBy distinct-value cap in force: the stated one, else {@link #DEFAULT_SEED_BY_DISTINCT_CAP}. */
    public int effectiveSeedByDistinctCap() {
        return (int) LaLimit.SEED_BY_DISTINCT_CAP.resolve(seedByDistinctCap);
    }

    /** The shipped default of {@code max_set_bytes}: 64 MiB of sealed Working Set text per step. */
    public static final int DEFAULT_MAX_SET_BYTES = 64 * 1024 * 1024;

    /** The per-set size limit in force, in bytes: the stated one, else {@link #DEFAULT_MAX_SET_BYTES}. */
    public int effectiveMaxSetBytes() {
        return (int) LaLimit.MAX_SET_BYTES.resolve(maxSetBytes);
    }

    /** The shipped default of {@code max_investigation_bytes}: 4 GiB of sealed Working Set text per Investigation (main + live Drafts). */
    public static final long DEFAULT_MAX_INVESTIGATION_BYTES = 4L * 1024 * 1024 * 1024;

    /** The per-Investigation total set budget in force, in bytes: the stated one, else {@link #DEFAULT_MAX_INVESTIGATION_BYTES}. */
    public long effectiveMaxInvestigationBytes() {
        return LaLimit.MAX_INVESTIGATION_BYTES.resolve(maxInvestigationBytes);
    }

    /** The shipped default of {@code hub_threshold}: an expand candidate with more distinct contacts than this is flagged. */
    public static final int DEFAULT_HUB_THRESHOLD = 500;

    /**
     * The supernode threshold in force (an expand's {@code hubThreshold} when the op leaves it unstated): the stated one,
     * else {@link #DEFAULT_HUB_THRESHOLD}. A candidate whose in-window distinct-contact degree exceeds it is admitted,
     * flagged {@code highConnectivity}, and not expanded further unless the analyst overrides.
     */
    public int effectiveHubThreshold() {
        return (int) LaLimit.HUB_THRESHOLD.resolve(hubThreshold);
    }

    /** The graph-run knobs in force: the stated ones; a field never stated is {@code null} inside (the service's default). */
    public GraphRun effectiveGraphRun() {
        return graphRun != null ? graphRun : GraphRun.NONE;
    }

    /** The index knobs in force: the stated ones; a field never stated is {@code null} inside (use the {@code *InForce} accessors). */
    public Index effectiveIndex() {
        return index != null ? index : Index.NONE;
    }

    /** The embed-link knobs in force: the stated ones; a field never stated is {@code null} inside (use the {@code *InForce} accessors). */
    public Embed effectiveEmbed() {
        return embed != null ? embed : Embed.NONE;
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

    /** The top-level keys {@link #write} models; a block's own sub-keys are listed beside its {@code block(...)} call. */
    private static final List<String> MODELLED = List.of("projection_node_cap", "analysis_node_cap", "suspicion_node_cap",
            "masking_mode", "four_eyes_budget_above", "four_eyes_fan_out_above", "entity_types", "merged_distinct_cap",
            "seed_by_distinct_cap", "max_set_bytes", "max_investigation_bytes", "hub_threshold", "graph_run", "index", "drafts", "embed");

    /**
     * Write to {@code link-analysis.toon} at {@code path} (canonical TOON, crash-safe) as a read-modify-write over the
     * raw TOON map ({@code LA-CONFIG-LIMITS-1}): the keys this record models are replaced (absent = cleared, the
     * replace semantics of {@code PUT /settings/link-analysis}), and EVERY other key the document holds - a
     * {@link LaLimit} item written by {@code PUT /settings/link-analysis/limits}, a key a newer build wrote - is kept,
     * top-level and inside a modelled block alike. A document that does not parse is replaced, as before.
     */
    public void write(Path path) throws IOException {
        synchronized (LaLimits.WRITE_LOCK) {
            writeLocked(path);
        }
    }

    private void writeLocked(Path path) throws IOException {
        Map<String, Object> existing;
        try {
            existing = LaLimits.loadRaw(path);
        } catch (IOException unreadable) {
            existing = new LinkedHashMap<>();
        }
        Map<String, Object> m = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : existing.entrySet())
            if (!MODELLED.contains(e.getKey())) m.put(e.getKey(), e.getValue());
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
        if (maxInvestigationBytes != null) m.put("max_investigation_bytes", maxInvestigationBytes);
        if (hubThreshold != null) m.put("hub_threshold", hubThreshold);
        {
            Map<String, Object> g = new LinkedHashMap<>();
            GraphRun graphRun = this.graphRun != null ? this.graphRun : GraphRun.NONE;
            if (graphRun.maxNodes() != null) g.put("max_nodes", graphRun.maxNodes());
            if (graphRun.maxEdges() != null) g.put("max_edges", graphRun.maxEdges());
            if (graphRun.timeoutMs() != null) g.put("timeout_ms", graphRun.timeoutMs());
            if (graphRun.maxResultItems() != null) g.put("max_result_items", graphRun.maxResultItems());
            if (graphRun.propagatedRiskWeights() != null) g.put("propagated_risk_weights", graphRun.propagatedRiskWeights());
            block(m, existing, "graph_run", g, "max_nodes", "max_edges", "timeout_ms", "max_result_items",
                    "propagated_risk_weights");
        }
        {
            Map<String, Object> x = new LinkedHashMap<>();
            Index index = this.index != null ? this.index : Index.NONE;
            if (index.enabled() != null) x.put("enabled", index.enabled());
            if (index.maxDiskBytes() != null) x.put("max_disk_bytes", index.maxDiskBytes());
            if (index.keepVersions() != null) x.put("keep_versions", index.keepVersions());
            if (index.maxAgeHours() != null) x.put("max_age_hours", index.maxAgeHours());
            block(m, existing, "index", x, "enabled", "max_disk_bytes", "keep_versions", "max_age_hours");
        }
        {
            Map<String, Object> x = new LinkedHashMap<>();
            Drafts drafts = this.drafts != null ? this.drafts : Drafts.NONE;
            if (drafts.maxOpen() != null) x.put("max_open", drafts.maxOpen());
            if (drafts.hibernateAfterMinutes() != null) x.put("hibernate_after_minutes", drafts.hibernateAfterMinutes());
            if (drafts.expireAfterDays() != null) x.put("expire_after_days", drafts.expireAfterDays());
            block(m, existing, "drafts", x, "max_open", "hibernate_after_minutes", "expire_after_days");
        }
        {
            Map<String, Object> x = new LinkedHashMap<>();
            Embed embed = this.embed != null ? this.embed : Embed.NONE;
            if (embed.defaultTtlHours() != null) x.put("default_ttl_hours", embed.defaultTtlHours());
            if (embed.maxTtlHours() != null) x.put("max_ttl_hours", embed.maxTtlHours());
            if (embed.refuseClassifications() != null) x.put("refuse_classifications", embed.refuseClassifications());
            if (embed.frameAncestors() != null) x.put("frame_ancestors", embed.frameAncestors());
            block(m, existing, "embed", x, "default_ttl_hours", "max_ttl_hours", "refuse_classifications", "frame_ancestors");
        }
        AtomicFiles.write(path, JToon.encode(m).getBytes(StandardCharsets.UTF_8), ".link-analysis-");
    }

    /** Put block {@code name}: its {@code stated} modelled keys, then every key of the stored block this record does not model; omitted when both are empty. */
    @SuppressWarnings("unchecked")
    private static void block(Map<String, Object> m, Map<String, Object> existing, String name, Map<String, Object> stated,
                              String... modelled) {
        if (existing.get(name) instanceof Map<?, ?> stored)
            for (Map.Entry<String, Object> e : ((Map<String, Object>) stored).entrySet())
                if (!List.of(modelled).contains(e.getKey())) stated.put(e.getKey(), e.getValue());
        if (!stated.isEmpty()) m.put(name, stated);
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
                    entityTypes(m.get("entity_types"), (int) LaLimits.read(m, Map.of()).get(LaLimit.INPUT_ENTITY_TYPES)),
                    optInt(m, "merged_distinct_cap"),
                    optInt(m, "seed_by_distinct_cap"), graphRun(m.get("graph_run")), index(m.get("index")), drafts(m.get("drafts")),
                    optInt(m, "max_set_bytes"), optLong(m, "max_investigation_bytes"), optInt(m, "hub_threshold"), embed(m.get("embed")));
        } catch (Exception e) {
            return EMPTY;
        }
    }

    /**
     * A stored {@code embed} block, else {@code null} = every knob inherits; a bad value inside costs only that knob.
     * ⚠ A stored cap above {@link Embed#CEILING_TTL_HOURS} reads as {@code null} (the 30 d default): a hand edit cannot go past the validation ceiling. A stored
     * default above the cap in force is clamped by {@link Embed#defaultTtlHoursInForce}.
     */
    @SuppressWarnings("unchecked")
    private static Embed embed(Object raw) {
        if (!(raw instanceof Map<?, ?>)) return null;
        Map<String, Object> g = (Map<String, Object>) raw;
        Integer max = optInt(g, "max_ttl_hours");
        if (max != null && max > Embed.CEILING_TTL_HOURS) max = null;
        List<String> refuse = null;
        if (g.get("refuse_classifications") instanceof List<?> l) {
            List<String> out = new java.util.ArrayList<>();
            for (Object o : l) {
                String c = com.gamma.util.ColumnClassification.normalise(o);
                if (c != null) out.add(c);
            }
            refuse = out;
        }
        List<String> frames = null;
        if (g.get("frame_ancestors") instanceof List<?> l) {   // a bad entry is DROPPED (fewer origins = stricter), never widened
            frames = new java.util.ArrayList<>();
            for (Object o : l) {
                String origin = Embed.normaliseOrigin(o);
                if (origin != null && !frames.contains(origin)) frames.add(origin);
            }
        }
        Embed r = new Embed(optInt(g, "default_ttl_hours"), max, refuse, frames);
        return r.isNone() ? null : r;
    }

    /** A stored {@code graph_run} block, else {@code null} = every knob inherits; a bad value inside costs only that knob. */
    @SuppressWarnings("unchecked")
    private static GraphRun graphRun(Object raw) {
        if (!(raw instanceof Map<?, ?>)) return null;
        Map<String, Object> g = (Map<String, Object>) raw;
        GraphRun r = new GraphRun(optInt(g, "max_nodes"), optInt(g, "max_edges"), optInt(g, "timeout_ms"), optInt(g, "max_result_items"),
                weights(g.get("propagated_risk_weights")));
        return r.isNone() ? null : r;
    }

    /** A stored weight list that is valid, else {@code null} = inherit the shipped default. */
    private static List<Double> weights(Object raw) {
        if (!(raw instanceof List<?> l)) return null;
        List<Double> out = new java.util.ArrayList<>();
        try {
            for (Object o : l) out.add(Double.parseDouble(String.valueOf(o).trim()));
        } catch (NumberFormatException e) {
            return null;
        }
        return GraphRun.validWeights(out) ? out : null;
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
        Index r = new Index(enabled, maxDisk, optInt(g, "keep_versions"), optInt(g, "max_age_hours"));
        return r.isNone() ? null : r;
    }

    /** A declared masking mode (case-insensitive), or {@code null} for anything else. */
    public static String maskingMode(String raw) {
        String v = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        return ConfigSpecs.LINK_ANALYSIS_MASKING_MODES.contains(v) ? v : null;
    }

    /** A stored list that parses and validates, else {@code null} = inherit {@link EntityTypes#DEFAULTS}. */
    private static List<EntityTypes.EntityType> entityTypes(Object raw, int maxTypes) {
        if (raw == null) return null;
        try {
            return EntityTypes.parse(raw, maxTypes);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** An absent, blank, unparseable or out-of-floor value reads as {@code null} = inherit the default. */
    private static Long optLong(Map<String, Object> m, String key) {
        String raw = ToonHelper.opt(m, key, "");
        if (raw.isBlank()) return null;
        try {
            long v = Long.parseLong(raw.trim());
            return v >= 1 ? v : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

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
