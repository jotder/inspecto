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
 * Per-space Pipeline config-history retention ({@code PIPELINE-CONFIG-HISTORY-1}): how many versions
 * {@link PipelineHistory} keeps per Pipeline. Persisted as {@code pipeline-history.toon} in the space's
 * config tree (crash-safe TOON, mirroring {@link GeoSettings}); {@code GET|PUT /settings/pipeline-history}.
 *
 * <p><b>{@code null} means "the shipped default"</b> ({@value #DEFAULT_KEEP}, a default the operator can
 * change — not a measured limit), the {@link LinkAnalysisSettings} convention. ⚠ A stored value outside
 * {@code 1..}{@value #MAX_KEEP} reads as the default: a hand-edited {@code keep: 0} must never prune every
 * version on the next save. The PUT refuses such a value (422) instead of storing it.
 *
 * <p>The filename is deliberately not a {@code *_pipeline.toon}-style suffix, so recursive config
 * discovery never mistakes it for a runnable config.
 */
record PipelineHistorySettings(Integer keep) {

    static final String FILE = "pipeline-history.toon";
    /** Versions kept per Pipeline when the Space states nothing — the editor's in-session undo cap. */
    static final int DEFAULT_KEEP = 50;
    /** Sanity ceiling: each version is a whole config file, and a list route returns all kept versions. */
    static final int MAX_KEEP = 1000;
    static final PipelineHistorySettings EMPTY = new PipelineHistorySettings(null);

    /** The retention in force: the stated value, else {@link #DEFAULT_KEEP}. */
    int effectiveKeep() {
        return keep != null ? keep : DEFAULT_KEEP;
    }

    /** Write to {@code pipeline-history.toon} at {@code path}; an unset value writes no key. */
    void write(Path path) throws IOException {
        Map<String, Object> m = new LinkedHashMap<>();
        if (keep != null) m.put("keep", keep);
        AtomicFiles.write(path, JToon.encode(m).getBytes(StandardCharsets.UTF_8), ".pipeline-history-");
    }

    /** The settings of the Space whose config root is {@code writeRoot}; {@link #EMPTY} when there is none. */
    static PipelineHistorySettings forRoot(Path writeRoot) {
        return writeRoot == null ? EMPTY : read(writeRoot.resolve(FILE));
    }

    /** Read {@code pipeline-history.toon}; missing, unreadable or out of range → {@link #EMPTY} (the default). */
    static PipelineHistorySettings read(Path path) {
        if (!Files.exists(path)) return EMPTY;
        try {
            Map<String, Object> m = ToonHelper.load(path.toString());
            String raw = ToonHelper.opt(m, "keep", "");
            if (raw.isBlank()) return EMPTY;
            int v = Integer.parseInt(raw.trim());
            return v >= 1 && v <= MAX_KEEP ? new PipelineHistorySettings(v) : EMPTY;
        } catch (Exception e) {
            return EMPTY;
        }
    }
}
