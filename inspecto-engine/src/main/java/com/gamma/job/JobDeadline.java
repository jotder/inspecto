package com.gamma.job;

import java.time.Duration;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * The generic Job Run deadline (platform-services R1, operator 2026-10-06), the Job-side twin of the Step
 * watchdog (D-7). Every Run gets a deadline: the Job Type's default ({@link JobTypeProvider#deadline()}),
 * overridden by the definition's {@code deadline_seconds:} key, both capped by
 * {@code -Djob.deadlineCeilingSeconds} (default 86400 = 24 h). On expiry the Run's thread is interrupted and the
 * Run is recorded {@code FAILED} with a deadline reason.
 *
 * <p>⚠ The body runs on the Run's own thread (so its MDC, pinned Safety Policy and commit fence stay in scope);
 * a body that ignores interrupts cannot be stopped. It is still recorded {@code FAILED} when it returns.
 */
public final class JobDeadline {

    /** The platform default for a Job Type that names none (operator 2026-10-06). */
    public static final Duration DEFAULT = Duration.ofMinutes(30);
    /** The default ceiling; {@code -Djob.deadlineCeilingSeconds} overrides it. */
    public static final long DEFAULT_CEILING_SECONDS = 86_400;
    public static final String CEILING_PROPERTY = "job.deadlineCeilingSeconds";

    /** Built-in types that legitimately run for hours (bulk reprocessing, compaction, export, index builds,
     *  publishing): their default is the ceiling, so the deadline cannot break a run that works today. */
    static final Set<String> LONG_RUNNING = Set.of("pipeline", "enrich", "maintenance", "recon.run",
            ConsignmentProcessJobType.TYPE_ID, ObjectStoreExportJobType.TYPE_ID, PostgresPublishJobType.TYPE_ID,
            LaIndexBuildJob.TYPE);

    private static final ScheduledExecutorService TIMER = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "job-deadline");
        t.setDaemon(true);
        return t;
    });

    private JobDeadline() {}

    /** A built-in type's default: the ceiling for {@link #LONG_RUNNING}, else {@link #DEFAULT}. */
    static Duration builtinDefault(String typeId) {
        return typeId != null && LONG_RUNNING.contains(typeId.toLowerCase(Locale.ROOT))
                ? Duration.ofSeconds(DEFAULT_CEILING_SECONDS) : DEFAULT;
    }

    static Duration ceiling() {
        String raw = System.getProperty(CEILING_PROPERTY);
        if (raw != null && !raw.isBlank()) {
            try {
                long v = Long.parseLong(raw.trim());
                if (v > 0) return Duration.ofSeconds(v);
            } catch (NumberFormatException ignored) { /* fall through to the default */ }
        }
        return Duration.ofSeconds(DEFAULT_CEILING_SECONDS);
    }

    /** Parses an authored {@code deadline_seconds:} value (decimals allowed); throws on a non-positive or bad one. */
    static Duration parse(String raw) {
        double v;
        try {
            v = Double.parseDouble(raw.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(JobConfig.DEADLINE_SECONDS + " must be a number of seconds, got '"
                    + raw + "'");
        }
        if (!(v > 0) || Double.isInfinite(v))
            throw new IllegalArgumentException(JobConfig.DEADLINE_SECONDS + " must be positive, got '" + raw + "'");
        return Duration.ofMillis(Math.max(1L, Math.round(Math.min(v, 1e12) * 1000)));
    }

    /** The effective deadline: the definition's override, else the type default; never above the ceiling. */
    static Duration resolve(Duration typeDefault, JobConfig cfg) {
        Duration d = typeDefault == null ? DEFAULT : typeDefault;
        String raw = cfg == null ? null : cfg.params().get(JobConfig.DEADLINE_SECONDS);
        if (raw != null && !raw.isBlank()) d = parse(raw);
        Duration cap = ceiling();
        return d.compareTo(cap) > 0 ? cap : d;
    }

    /** Arms a watch on the calling thread; {@link Watch#finish()} must be called when the body returns. */
    static Watch arm(Duration deadline) {
        return new Watch(Thread.currentThread(), deadline);
    }

    /** One armed Run deadline. */
    static final class Watch {
        private final Thread runner;
        private final Duration deadline;
        private final ScheduledFuture<?> timer;
        private boolean done;
        private boolean expired;

        private Watch(Thread runner, Duration deadline) {
            this.runner = runner;
            this.deadline = deadline;
            this.timer = TIMER.schedule(this::expire, deadline.toMillis(), TimeUnit.MILLISECONDS);
        }

        private synchronized void expire() {
            if (done) return;
            expired = true;
            runner.interrupt();
        }

        /** Disarms the watch; {@code true} when the deadline expired first. Clears the interrupt it raised. */
        synchronized boolean finish() {
            done = true;
            timer.cancel(false);
            if (expired) Thread.interrupted();
            return expired;
        }

        String reason() {
            return "deadline exceeded: the run was stopped after " + seconds(deadline)
                    + " s (job deadline; set deadline_seconds: to change it, ceiling " + seconds(ceiling()) + " s)";
        }

        private static String seconds(Duration d) {
            return d.toMillis() % 1000 == 0 ? String.valueOf(d.toSeconds()) : String.valueOf(d.toMillis() / 1000.0);
        }
    }
}
