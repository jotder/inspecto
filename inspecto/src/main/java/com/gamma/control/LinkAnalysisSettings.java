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
 * {@link EntityTypes Entity Types} (LA-17, design §4.1). Persisted as
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
                                   List<EntityTypes.EntityType> entityTypes) {

    public static final String FILE = "link-analysis.toon";
    static final LinkAnalysisSettings EMPTY = new LinkAnalysisSettings(null, null, null, null, null, null, null);

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
                    entityTypes(m.get("entity_types")));
        } catch (Exception e) {
            return EMPTY;
        }
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
