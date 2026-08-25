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
 * One tier of the Consignment concurrency hierarchy (scheduler-system-config plan Part B), persisted
 * as {@code scheduler.toon} — at the spaces container root (or {@code -Dsystem.config.dir}) for the
 * <b>server-wide</b> cap, and in a space's config tree for that <b>space's</b> cap. {@code 0} means
 * unbounded ({@code ConcurrencyBroker.UNBOUNDED}); a missing or unreadable file reads as {@link #EMPTY}
 * (the {@code BrandingSettings} posture — settings never fail a boot).
 *
 * <p>Like {@code branding.toon}, the filename is deliberately not a {@code *_pipeline.toon}-style
 * suffix, so recursive config discovery never mistakes it for a runnable config.
 */
record SchedulerSettings(int maxConcurrentConsignments, Integer pollSeconds, Integer acquirePollSeconds) {

    static final String FILE = "scheduler.toon";
    static final SchedulerSettings EMPTY = new SchedulerSettings(0);

    /** Cap-only settings (the server-wide tier — cadence is a per-space concern). */
    SchedulerSettings(int maxConcurrentConsignments) {
        this(maxConcurrentConsignments, null, null);
    }

    /** Write to {@code scheduler.toon} at {@code path} (canonical TOON, crash-safe). Cadence keys are
     *  written only when stated — an absent key means "inherit the {@code -D} bootstrap default", and
     *  that must stay distinguishable from any stated value. */
    void write(Path path) throws IOException {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("max_concurrent_consignments", maxConcurrentConsignments);
        if (pollSeconds != null) m.put("poll_seconds", pollSeconds);
        if (acquirePollSeconds != null) m.put("acquire_poll_seconds", acquirePollSeconds);
        AtomicFiles.write(path, JToon.encode(m).getBytes(StandardCharsets.UTF_8), ".scheduler-");
    }

    /** Read {@code scheduler.toon} at {@code path}; missing/unreadable → {@link #EMPTY} (unbounded). */
    static SchedulerSettings read(Path path) {
        if (path == null || !Files.exists(path)) return EMPTY;
        try {
            Map<String, Object> m = ToonHelper.load(path.toString());
            int v = Integer.parseInt(ToonHelper.opt(m, "max_concurrent_consignments", "0").trim());
            return new SchedulerSettings(Math.max(0, v), optInt(m, "poll_seconds"), optInt(m, "acquire_poll_seconds"));
        } catch (Exception e) {
            return EMPTY;
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

    /** Whether the file exists at all — provenance for the settings routes (file vs default). */
    static boolean present(Path path) {
        return path != null && Files.exists(path);
    }
}
