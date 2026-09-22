package com.gamma.control;

import com.gamma.util.AtomicFiles;
import com.gamma.util.ToonHelper;
import dev.toonformat.jtoon.JToon;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Per-space Link Analysis tuning limits — the three node caps the Link Analysis studio applies before it
 * projects entities, before it runs the super-linear graph algorithms, and before it runs suspicion
 * score, which carries its OWN lower ceiling because its cost is quadratic while the other 26
 * algorithms are trivial at the shared cap (decision D-S3). Persisted as
 * {@code link-analysis.toon} in the space's config tree (crash-safe TOON, mirroring {@link GeoSettings}
 * and {@link SchedulerSettings}).
 *
 * <p><b>{@code null} means "inherit the shipped default", never "unbounded"</b> — the
 * {@link SchedulerSettings} convention. The caps were measured on one host against one data shape, so a
 * space that stores nothing must keep tracking whatever the client ships, not fall off a cliff into an
 * unbounded run. A key is written only when stated, so an absent key stays distinguishable from a stated
 * value and a save that never mentioned a cap cannot seize its provenance from the default.
 *
 * <p>Like {@code branding.toon}, the filename is deliberately not a {@code *_pipeline.toon}-style suffix,
 * so recursive config discovery never mistakes it for a runnable config. A missing or unreadable file
 * reads as {@link #EMPTY} (the {@code BrandingSettings} posture — settings never fail a boot).
 */
record LinkAnalysisSettings(Integer projectionNodeCap, Integer analysisNodeCap, Integer suspicionNodeCap) {

    static final String FILE = "link-analysis.toon";
    static final LinkAnalysisSettings EMPTY = new LinkAnalysisSettings(null, null, null);

    /** Write to {@code link-analysis.toon} at {@code path} (canonical TOON, crash-safe). */
    void write(Path path) throws IOException {
        Map<String, Object> m = new LinkedHashMap<>();
        if (projectionNodeCap != null) m.put("projection_node_cap", projectionNodeCap);
        if (analysisNodeCap != null) m.put("analysis_node_cap", analysisNodeCap);
        if (suspicionNodeCap != null) m.put("suspicion_node_cap", suspicionNodeCap);
        AtomicFiles.write(path, JToon.encode(m).getBytes(StandardCharsets.UTF_8), ".link-analysis-");
    }

    /** Read {@code link-analysis.toon} at {@code path}; missing/unreadable → {@link #EMPTY} (inherit both). */
    static LinkAnalysisSettings read(Path path) {
        if (path == null || !Files.exists(path)) return EMPTY;
        try {
            Map<String, Object> m = ToonHelper.load(path.toString());
            return new LinkAnalysisSettings(optInt(m, "projection_node_cap"), optInt(m, "analysis_node_cap"),
                    optInt(m, "suspicion_node_cap"));
        } catch (Exception e) {
            return EMPTY;
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
