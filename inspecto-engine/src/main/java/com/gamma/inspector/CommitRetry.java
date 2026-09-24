package com.gamma.inspector;

import com.gamma.etl.Consignment;
import com.gamma.etl.PipelineConfig;
import com.gamma.etl.QuarantineManager;
import com.gamma.signal.PipelineConsignmentSignal;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Bounds the ingest lane's only retry mechanism (execution residuals X1 — the recovery architecture,
 * consignment-grain by operator decision 2026-09-02).
 *
 * <p><b>What retries today, and why it needed bounding.</b> A Consignment that fails at COMMIT (or whose
 * ingest throws a framework fault) leaves its files in the inbox, so the next poll cycle re-encounters
 * and re-ingests them — that IS the retry, and it is idempotent by the commit model. But it was
 * unbounded: no attempt cap, no backoff, no exhaustion, no handoff. A poison Consignment failed every
 * cycle forever, emitting a {@code pipeline.batch.failed} Signal each time. The plan worried poison
 * would drop silently; here poison never STOPPED. This class adds exactly what was missing and nothing
 * else: a durable per-file attempt record, exponential backoff with jitter, and on exhaustion a
 * quarantine under {@link #REASON_RETRY_EXHAUSTED} plus a CRITICAL {@code pipeline.batch.retry_exhausted}
 * Signal. Poison now stops, loudly.
 *
 * <p><b>Where the record lives.</b> One small JSON sidecar per member file under
 * {@code <status_dir>/retries/}, mirrored by poll-relative path exactly like the markers and the quarantine
 * tree. Not a database table: the catch sites live in this module while the status DB lives in the
 * control plane, the status directory is configured wherever an audit is written (no new default-on
 * store to reason about), and a file beside the other per-file records survives a restart by construction
 * ("zero lost retries across restarts"). No {@code status_dir} ⇒ no record ⇒ today's unbounded behaviour.
 *
 * <p><b>Grain.</b> The failure is per Consignment, the record per FILE — deliberately. {@code batchId} is
 * minted per cycle and the planner may regroup members, so the file path is the one identity that is
 * stable across re-encounters; every member of a failed Consignment gets the same increment, so the
 * semantics stay consignment-grain. Members the strategy already quarantined (moved out of the inbox)
 * are skipped — their fate is decided.
 *
 * <p><b>Policy.</b> Runtime {@code -D} defaults: {@code -Dingest.retry.max}
 * (default 5; {@code 0} disables bounding and keeps the pre-X1 behaviour), {@code -Dingest.retry.backoff.initialMs}
 * (60000), {@code -Dingest.retry.backoff.maxMs} (3600000); the delay is
 * {@code min(initial × 2^(n−1), max) ± 10%}. Since 2026-09-25 a pipeline's own {@code processing.retry}
 * block ({@code max_attempts}, {@code initial_backoff}, {@code max_backoff}) overrides each global it states
 * — see {@link #policy}.
 *
 * <p>Every method here is best-effort and never throws: recovery bookkeeping must not mask the failure it
 * is recording, and a read-only pending count must not be broken by an unreadable sidecar.
 */
public final class CommitRetry {

    private static final Logger log = LoggerFactory.getLogger(CommitRetry.class);
    private static final Gson GSON = new GsonBuilder().create();

    /** Quarantine reason for a file whose Consignment exhausted its COMMIT retries. */
    public static final String REASON_RETRY_EXHAUSTED = "retry_exhausted";
    /** The Signal type emitted once per exhausted Consignment. */
    public static final String SIGNAL_TYPE = "pipeline.batch.retry_exhausted";

    private CommitRetry() {}

    /** The durable per-file record. Public fields for Gson, like {@link com.gamma.etl.ConsignmentManifest}. */
    public static final class Record {
        public int attempts;
        public String firstFailedAt;
        public String lastFailedAt;
        public String nextRetryAt;   // ISO instant; absent/blank ⇒ due now
        public String lastError;
    }

    /** The resolved policy — read per call so a {@code -D} set in a test (or by an operator restart) applies. */
    public record Policy(int maxAttempts, long initialMs, long maxMs) {
        static Policy current() {
            return new Policy(
                    Integer.getInteger("ingest.retry.max", 5),
                    Long.getLong("ingest.retry.backoff.initialMs", 60_000L),
                    Long.getLong("ingest.retry.backoff.maxMs", 3_600_000L));
        }
        public boolean bounded() { return maxAttempts > 0; }
        long delayMs(int attempt) {
            double base = Math.min((double) initialMs * Math.pow(2, Math.max(0, attempt - 1)), (double) maxMs);
            double jitter = 1.0 + (ThreadLocalRandom.current().nextDouble() * 0.2 - 0.1);
            return Math.max(0L, Math.round(base * jitter));
        }
    }

    /**
     * A Consignment failed after ingest (COMMIT/park failure, or a thrown ingest): bump every remaining
     * member's attempt record; a member that reached the cap is quarantined under
     * {@link #REASON_RETRY_EXHAUSTED}, and one CRITICAL Signal names the Consignment and its files.
     */
    public static void recordFailure(Consignment batch, PipelineConfig cfg, String error) {
        Policy policy = policy(cfg);
        if (!policy.bounded()) return;
        Path root = root(cfg);
        if (root == null) return;
        List<String> exhausted = new ArrayList<>();
        int attemptsAtExhaustion = 0;
        for (Consignment.Member m : batch.members()) {
            File f = m.file();
            if (!f.exists()) continue;   // the strategy already decided this member's fate
            try {
                Path side = sidecar(root, f, cfg);
                Record r = read(side);
                String now = Instant.now().toString();
                if (r == null) { r = new Record(); r.firstFailedAt = now; }
                r.attempts++;
                r.lastFailedAt = now;
                r.lastError = error;
                if (r.attempts >= policy.maxAttempts) {
                    QuarantineManager.quarantine(f, REASON_RETRY_EXHAUSTED, false, cfg);
                    Files.deleteIfExists(side);
                    exhausted.add(f.getName());
                    attemptsAtExhaustion = r.attempts;
                } else {
                    r.nextRetryAt = Instant.now().plusMillis(policy.delayMs(r.attempts)).toString();
                    Files.createDirectories(side.getParent());
                    Files.writeString(side, GSON.toJson(r), StandardCharsets.UTF_8);
                }
            } catch (IOException | RuntimeException e) {
                log.warn("Could not record COMMIT retry for {} of Consignment {}: {}",
                        f.getName(), batch.batchId(), e.getMessage());
            }
        }
        if (!exhausted.isEmpty()) {
            log.error("Consignment {} exhausted {} COMMIT attempt(s); {} file(s) quarantined under {}: {}",
                    batch.batchId(), attemptsAtExhaustion, exhausted.size(), REASON_RETRY_EXHAUSTED, exhausted);
            PipelineConsignmentSignal.emitRetryExhausted(cfg.identity().pipelineName(), batch.batchId(),
                    attemptsAtExhaustion, exhausted, error);
        }
    }

    /** The Consignment committed (or parked): its members' attempt records are spent. */
    public static void clear(Consignment batch, PipelineConfig cfg) {
        for (Consignment.Member m : batch.members()) clear(m.file(), cfg);
    }

    /** One FILE's attempt record is spent — the per-file variant the operator affordance needs. */
    public static void clear(File file, PipelineConfig cfg) {
        Path root = root(cfg);
        if (root == null) return;
        try {
            Files.deleteIfExists(sidecar(root, file, cfg));
        } catch (IOException | RuntimeException e) {
            log.debug("Could not clear retry record for {}: {}", file.getName(), e.getMessage());
        }
    }

    /**
     * The candidates whose backoff has elapsed. Applied on the RUN path only: a file waiting out its
     * backoff is still honestly <em>pending</em>, so the read-only count keeps reporting it. An
     * unreadable sidecar admits the file (fail-open here is the safe direction — the worst case is one
     * early retry, never a silently withheld file).
     */
    public static List<File> due(PipelineConfig cfg, List<File> candidates) {
        if (candidates.isEmpty() || !policy(cfg).bounded()) return candidates;
        Path root = root(cfg);
        if (root == null) return candidates;
        Instant now = Instant.now();
        List<File> out = new ArrayList<>(candidates.size());
        int waiting = 0;
        for (File f : candidates) {
            Record r = null;
            try {
                r = read(sidecar(root, f, cfg));
            } catch (RuntimeException e) {
                log.debug("Unreadable retry record for {} — admitting it: {}", f.getName(), e.getMessage());
            }
            if (r != null && r.nextRetryAt != null && !r.nextRetryAt.isBlank()) {
                try {
                    if (Instant.parse(r.nextRetryAt).isAfter(now)) { waiting++; continue; }
                } catch (RuntimeException ignored) { /* malformed ⇒ due */ }
            }
            out.add(f);
        }
        if (waiting > 0)
            log.info("COMMIT retry backoff: {} file(s) of {} not yet due this cycle for {}", waiting,
                    candidates.size(), cfg.identity().pipelineName());
        return out;
    }

    /** The attempt record for {@code file}, or {@code null} when none — the test/inspection seam. */
    public static Record recordFor(File file, PipelineConfig cfg) {
        Path root = root(cfg);
        return root == null ? null : read(sidecar(root, file, cfg));
    }

    // ── operator affordance (X1 deferrals, decisions 2026-09-25) ────────────────

    /** Quarantine reason for a file whose retries an operator CANCELLED (Q1: cancel decides its fate now). */
    public static final String REASON_RETRY_CANCELLED = "retry_cancelled";

    /** One pending retry, addressed by its poll-relative path ({@code /}-separated). */
    public record Pending(String file, int attempts, String firstFailedAt, String lastFailedAt,
                          String nextRetryAt, boolean due, String lastError, boolean inInbox, boolean readable) {}

    /**
     * A pipeline's retry queue. {@code keepsRetryState=false} means NO {@code status_dir}: nothing is ever
     * recorded and a failed Consignment is retried every cycle without bound — the opposite of an empty queue.
     * {@code error} is set (and the list partial) when the retry directory could not be walked.
     */
    public record Listing(boolean keepsRetryState, Policy policy, int total, boolean truncated,
                          List<Pending> retries, String error) {}

    /** What an operator act did. Only {@link #RESCHEDULED} and {@link #CANCELLED} changed anything;
     *  {@link #BUSY} is the caller's (the pipeline was mid-cycle, so nothing was attempted). */
    public enum Result { RESCHEDULED, CANCELLED, NO_RETRY_STATE, NO_RECORD, NOT_IN_INBOX, ALREADY_QUARANTINED, BUSY, FAILED }

    /** The report of one act — {@code record} is the (post-act) attempt record when there was one. */
    public record Outcome(Result result, Record record, String quarantineReason, String detail) {
        public boolean acted() { return result == Result.RESCHEDULED || result == Result.CANCELLED; }
    }

    /**
     * The retry policy in force for {@code cfg}: its {@code processing.retry} block, each key stated there
     * overriding its {@code -Dingest.retry.*} global, every unstated key inheriting it. No block ⇒ the globals
     * whole — the pre-block behaviour.
     */
    public static Policy policy(PipelineConfig cfg) {
        Policy global = Policy.current();
        PipelineConfig.CommitRetryPolicy own = cfg.commitRetry();
        if (own == null) return global;
        return new Policy(
                own.maxAttempts() != null ? own.maxAttempts() : global.maxAttempts(),
                own.initialBackoffMs() != null ? own.initialBackoffMs() : global.initialMs(),
                own.maxBackoffMs() != null ? own.maxBackoffMs() : global.maxMs());
    }

    /**
     * {@code rel} resolved under the poll root, or {@code null} when it is blank, absolute, or escapes the
     * root — the path jail for a caller-supplied file key.
     */
    public static File inboxFile(PipelineConfig cfg, String rel) {
        if (rel == null || rel.isBlank() || rel.startsWith("/") || rel.startsWith("\\")) return null;
        try {
            if (Paths.get(rel).isAbsolute()) return null;
            Path poll = Paths.get(cfg.dirs().poll()).toAbsolutePath().normalize();
            Path f = poll.resolve(rel).normalize();
            return f.startsWith(poll) && !f.equals(poll) ? f.toFile() : null;
        } catch (RuntimeException invalid) {
            return null;
        }
    }

    /** The retry queue of {@code cfg}, sorted by file, at most {@code limit} entries ({@code total} is true). */
    public static Listing list(PipelineConfig cfg, int limit) {
        Policy policy = policy(cfg);
        Path root = root(cfg);
        if (root == null) return new Listing(false, policy, 0, false, List.of(), null);
        List<Path> sides = new ArrayList<>();
        String error = null;
        if (Files.isDirectory(root)) {
            try (var walk = Files.walk(root)) {
                walk.filter(p -> p.getFileName().toString().endsWith(SUFFIX) && Files.isRegularFile(p))
                        .sorted().forEach(sides::add);
            } catch (IOException | RuntimeException e) {
                error = "could not list the retry records: " + e.getMessage();
                log.warn("Could not list COMMIT retry records for {}: {}", cfg.identity().pipelineName(), e.getMessage());
            }
        }
        Path poll = Paths.get(cfg.dirs().poll()).toAbsolutePath().normalize();
        Instant now = Instant.now();
        int shown = Math.min(sides.size(), Math.max(0, limit));
        List<Pending> out = new ArrayList<>(shown);
        for (Path side : sides.subList(0, shown)) {
            String rel = root.relativize(side).toString().replace('\\', '/');
            rel = rel.substring(0, rel.length() - SUFFIX.length());
            boolean inInbox = Files.exists(poll.resolve(rel));
            Record r;
            try {
                r = read(side);
            } catch (RuntimeException unreadable) {
                out.add(new Pending(rel, 0, null, null, null, true, String.valueOf(unreadable.getMessage()), inInbox, false));
                continue;
            }
            if (r == null) continue;   // spent between the walk and the read
            out.add(new Pending(rel, r.attempts, r.firstFailedAt, r.lastFailedAt, r.nextRetryAt,
                    isDue(r, now), r.lastError, inInbox, true));
        }
        return new Listing(true, policy, sides.size(), shown < sides.size(), List.copyOf(out), error);
    }

    /**
     * Retry {@code file} on the next cycle: its backoff is cleared, its attempt count is KEPT (Q2) — so the
     * cap still bites and a poison file cannot be made immortal by pressing "retry now".
     */
    public static Outcome retryNow(PipelineConfig cfg, File file) {
        Path root = root(cfg);
        if (root == null) return noRetryState();
        Outcome gone = notActionable(cfg, file);
        if (gone != null) return gone;
        try {
            Path side = sidecar(root, file, cfg);
            Record r = read(side);
            if (r == null) return noRecord();
            r.nextRetryAt = null;
            Files.writeString(side, GSON.toJson(r), StandardCharsets.UTF_8);
            return new Outcome(Result.RESCHEDULED, r, null, null);
        } catch (IOException | RuntimeException e) {
            return failed(file, e);
        }
    }

    /**
     * Cancel {@code file}'s retries: quarantine it NOW under {@link #REASON_RETRY_CANCELLED} and spend its
     * record (Q1). ⛔ Never "drop the record" alone — with no record the file is retried without bound.
     */
    public static Outcome cancel(PipelineConfig cfg, File file) {
        Path root = root(cfg);
        if (root == null) return noRetryState();
        Outcome gone = notActionable(cfg, file);
        if (gone != null) return gone;
        try {
            Record r = read(sidecar(root, file, cfg));
            if (r == null) return noRecord();
            QuarantineManager.quarantine(file, REASON_RETRY_CANCELLED, false, cfg);
            clear(file, cfg);
            log.warn("COMMIT retries of {} cancelled after {} attempt(s); quarantined under {}",
                    file.getName(), r.attempts, REASON_RETRY_CANCELLED);
            return new Outcome(Result.CANCELLED, r, REASON_RETRY_CANCELLED, null);
        } catch (IOException | RuntimeException e) {
            return failed(file, e);
        }
    }

    private static Outcome noRetryState() {
        return new Outcome(Result.NO_RETRY_STATE, null, null,
                "this pipeline keeps no retry state (no dirs.status_dir): nothing is recorded, so a failed "
                        + "Consignment is retried every cycle without bound");
    }

    private static Outcome noRecord() {
        return new Outcome(Result.NO_RECORD, null, null, "no retry is pending for this file");
    }

    /** A file no longer in the inbox: quarantined (its fate is decided — say so), or simply gone. */
    private static Outcome notActionable(PipelineConfig cfg, File file) {
        if (file.exists()) return null;
        String reason = quarantinedUnder(cfg, file);
        if (reason != null)
            return new Outcome(Result.ALREADY_QUARANTINED, null, reason,
                    "already quarantined under '" + reason + "': its fate is decided and it is not retried");
        return new Outcome(Result.NOT_IN_INBOX, null, null, "the file is not in the inbox");
    }

    /** The reason directory {@code file} sits under in the quarantine tree, or {@code null}. */
    private static String quarantinedUnder(PipelineConfig cfg, File file) {
        try {
            String q = cfg.dirs().quarantine();
            if (q == null || q.isBlank()) return null;
            Path poll = Paths.get(cfg.dirs().poll()).toAbsolutePath().normalize();
            Path relParent = poll.relativize(file.toPath().toAbsolutePath().normalize().getParent());
            Path base = Paths.get(q).toAbsolutePath().resolve(relParent);
            if (!Files.isDirectory(base)) return null;
            try (var reasons = Files.list(base)) {
                return reasons.filter(d -> Files.isRegularFile(d.resolve(file.getName())))
                        .map(d -> d.getFileName().toString()).sorted().findFirst().orElse(null);
            }
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private static Outcome failed(File file, Exception e) {
        log.warn("Could not act on the COMMIT retry record for {}: {}", file.getName(), e.getMessage());
        return new Outcome(Result.FAILED, null, null, "could not act on the retry record: " + e.getMessage());
    }

    private static boolean isDue(Record r, Instant now) {
        if (r.nextRetryAt == null || r.nextRetryAt.isBlank()) return true;
        try {
            return !Instant.parse(r.nextRetryAt).isAfter(now);
        } catch (RuntimeException malformed) {
            return true;
        }
    }

    // ── layout ──────────────────────────────────────────────────────────────

    private static final String SUFFIX = ".retry.json";

    /** {@code <status_dir>/retries}, or {@code null} when the pipeline writes no audit at all. */
    private static Path root(PipelineConfig cfg) {
        String manifests = cfg.dirs().manifestsDir();
        if (manifests == null || manifests.isBlank()) return null;
        return Paths.get(manifests).toAbsolutePath().resolveSibling("retries");
    }

    /** Mirrored by poll-relative path like the markers, so two inboxes' {@code feed.csv} never collide. */
    private static Path sidecar(Path root, File file, PipelineConfig cfg) {
        Path poll = Paths.get(cfg.dirs().poll()).toAbsolutePath().normalize();
        Path rel  = poll.relativize(file.toPath().toAbsolutePath().normalize());
        return root.resolve(rel.toString() + SUFFIX);
    }

    private static Record read(Path side) {
        try {
            if (!Files.exists(side)) return null;
            return GSON.fromJson(Files.readString(side, StandardCharsets.UTF_8), Record.class);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
