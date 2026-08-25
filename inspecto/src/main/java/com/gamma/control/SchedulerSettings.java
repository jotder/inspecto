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
record SchedulerSettings(int maxConcurrentConsignments, Integer pollSeconds, Integer acquirePollSeconds,
                         Integer intakeMaxFilesPerCycle, Integer intakeMinFilesPerCycle, Boolean intakeAdaptive) {

    static final String FILE = "scheduler.toon";
    static final SchedulerSettings EMPTY = new SchedulerSettings(0);

    /** Cap-only settings. Cadence is a per-space concern; the intake globals a server-wide one. */
    SchedulerSettings(int maxConcurrentConsignments) {
        this(maxConcurrentConsignments, null, null, null, null, null);
    }

    /** Space-tier settings (cap + cadences; the intake globals never live in a space document). */
    SchedulerSettings(int maxConcurrentConsignments, Integer pollSeconds, Integer acquirePollSeconds) {
        this(maxConcurrentConsignments, pollSeconds, acquirePollSeconds, null, null, null);
    }

    /** Write to {@code scheduler.toon} at {@code path} (canonical TOON, crash-safe). Cadence keys are
     *  written only when stated — an absent key means "inherit the {@code -D} bootstrap default", and
     *  that must stay distinguishable from any stated value. */
    void write(Path path) throws IOException {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("max_concurrent_consignments", maxConcurrentConsignments);
        if (pollSeconds != null) m.put("poll_seconds", pollSeconds);
        if (acquirePollSeconds != null) m.put("acquire_poll_seconds", acquirePollSeconds);
        if (intakeMaxFilesPerCycle != null) m.put("intake_max_files_per_cycle", intakeMaxFilesPerCycle);
        if (intakeMinFilesPerCycle != null) m.put("intake_min_files_per_cycle", intakeMinFilesPerCycle);
        if (intakeAdaptive != null) m.put("intake_adaptive", intakeAdaptive);
        AtomicFiles.write(path, JToon.encode(m).getBytes(StandardCharsets.UTF_8), ".scheduler-");
    }

    /** Read {@code scheduler.toon} at {@code path}; missing/unreadable → {@link #EMPTY} (unbounded). */
    static SchedulerSettings read(Path path) {
        if (path == null || !Files.exists(path)) return EMPTY;
        try {
            Map<String, Object> m = ToonHelper.load(path.toString());
            int v = Integer.parseInt(ToonHelper.opt(m, "max_concurrent_consignments", "0").trim());
            return new SchedulerSettings(Math.max(0, v),
                    optInt(m, "poll_seconds", 1), optInt(m, "acquire_poll_seconds", 1),
                    optInt(m, "intake_max_files_per_cycle", 0), optInt(m, "intake_min_files_per_cycle", 1),
                    optBool(m, "intake_adaptive"));
        } catch (Exception e) {
            return EMPTY;
        }
    }

    private static Integer optInt(Map<String, Object> m, String key, int floor) {
        String raw = ToonHelper.opt(m, key, "");
        if (raw.isBlank()) return null;
        try {
            int v = Integer.parseInt(raw.trim());
            return v >= floor ? v : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Boolean optBool(Map<String, Object> m, String key) {
        String raw = ToonHelper.opt(m, key, "").trim();
        if ("true".equalsIgnoreCase(raw)) return Boolean.TRUE;
        if ("false".equalsIgnoreCase(raw)) return Boolean.FALSE;
        return null;
    }

    /** Whether the file exists at all — provenance for the settings routes (file vs default). */
    static boolean present(Path path) {
        return path != null && Files.exists(path);
    }
}
