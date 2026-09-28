package com.gamma.control;

import com.gamma.query.KpiDefinition;
import com.gamma.util.AtomicFiles;
import com.gamma.util.ToonHelper;
import dev.toonformat.jtoon.JToon;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The Space's default timezone ({@code ASSURE-KPI-DEFINITIONS-RESIDUALS-1} (3)): the IANA zone a KPI without its
 * own {@code timezone} is evaluated in. Persisted as {@code timezone.toon} in the space's config tree (crash-safe
 * TOON, mirroring {@link PipelineHistorySettings}); {@code GET|PUT /settings/timezone}.
 *
 * <p><b>{@code null} means UTC</b> ({@link KpiDefinition#UTC}). A stored value that is not a region zone reads as
 * {@code null} — the PUT refuses such a value (422) instead of storing it.
 *
 * <p>The filename is deliberately not a {@code *_pipeline.toon}-style suffix, so recursive config
 * discovery never mistakes it for a runnable config.
 */
record TimezoneSettings(ZoneId timezone) {

    static final String FILE = "timezone.toon";
    static final TimezoneSettings EMPTY = new TimezoneSettings(null);

    /** The zone in force: the stated one, else UTC. */
    ZoneId effective() {
        return timezone != null ? timezone : KpiDefinition.UTC;
    }

    /** Write to {@code timezone.toon} at {@code path}; an unset value writes no key. */
    void write(Path path) throws IOException {
        Map<String, Object> m = new LinkedHashMap<>();
        if (timezone != null) m.put("timezone", timezone.getId());
        AtomicFiles.write(path, JToon.encode(m).getBytes(StandardCharsets.UTF_8), ".timezone-");
    }

    /** The settings of the Space whose config root is {@code writeRoot}; {@link #EMPTY} when there is none. */
    static TimezoneSettings forRoot(Path writeRoot) {
        return writeRoot == null ? EMPTY : read(writeRoot.resolve(FILE));
    }

    /** Read {@code timezone.toon}; missing, unreadable or not a region zone → {@link #EMPTY} (UTC). */
    static TimezoneSettings read(Path path) {
        if (!Files.exists(path)) return EMPTY;
        try {
            Map<String, Object> m = ToonHelper.load(path.toString());
            ZoneId z = KpiDefinition.regionZone(ToonHelper.opt(m, "timezone", "").trim());
            return z == null ? EMPTY : new TimezoneSettings(z);
        } catch (Exception e) {
            return EMPTY;
        }
    }
}
