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
 * <p><b>Policy.</b> Runtime {@code -D} defaults, not a per-pipeline key: {@code -Dingest.retry.max}
 * (default 5; {@code 0} disables bounding and keeps the pre-X1 behaviour), {@code -Dingest.retry.backoff.initialMs}
 * (60000), {@code -Dingest.retry.backoff.maxMs} (3600000); the delay is
 * {@code min(initial × 2^(n−1), max) ± 10%}. A per-pipeline {@code processing.retry} block is a filed
 * follow-up — a config key rides two committed contracts, which is its own change.
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
    record Policy(int maxAttempts, long initialMs, long maxMs) {
        static Policy current() {
            return new Policy(
                    Integer.getInteger("ingest.retry.max", 5),
                    Long.getLong("ingest.retry.backoff.initialMs", 60_000L),
                    Long.getLong("ingest.retry.backoff.maxMs", 3_600_000L));
        }
        boolean bounded() { return maxAttempts > 0; }
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
        Policy policy = Policy.current();
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
        Path root = root(cfg);
        if (root == null) return;
        for (Consignment.Member m : batch.members()) {
            try {
                Files.deleteIfExists(sidecar(root, m.file(), cfg));
            } catch (IOException | RuntimeException e) {
                log.debug("Could not clear retry record for {}: {}", m.file().getName(), e.getMessage());
            }
        }
    }

    /**
     * The candidates whose backoff has elapsed. Applied on the RUN path only: a file waiting out its
     * backoff is still honestly <em>pending</em>, so the read-only count keeps reporting it. An
     * unreadable sidecar admits the file (fail-open here is the safe direction — the worst case is one
     * early retry, never a silently withheld file).
     */
    public static List<File> due(PipelineConfig cfg, List<File> candidates) {
        if (candidates.isEmpty() || !Policy.current().bounded()) return candidates;
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

    // ── layout ──────────────────────────────────────────────────────────────

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
        return root.resolve(rel.toString() + ".retry.json");
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
