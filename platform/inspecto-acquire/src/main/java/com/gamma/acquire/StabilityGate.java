package com.gamma.acquire;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

import com.gamma.audit.EventLog;

/**
 * Engine-side readiness gate (Data Acquisition roadmap Phase B): holds a discovered file back until it has
 * stopped changing, so a half-written file is never ingested — the requirement's stated "biggest production
 * problem".
 *
 * <p>For each candidate the gate first asks the connector {@link CollectorConnector#readiness}. A connector that
 * knows natively (S3 object finalized, SFTP rename-on-complete, a local {@code ready_marker}) answers
 * {@link CollectorConnector.Readiness#READY}/{@link CollectorConnector.Readiness#NOT_READY} and short-circuits.
 * When it answers {@link CollectorConnector.Readiness#UNKNOWN}, the gate applies size/mtime <em>stabilization</em>:
 * a file is released only once it has been <b>quiescent</b> — unmodified for at least {@code windowMillis} —
 * and observed at the same size on at least {@code sizeChecks} consecutive poll cycles.
 *
 * <h3>Idempotent under repeated evaluation</h3>
 * The release decision is gated on wall-clock quiescence ({@code now - mtime >= window}), so it crosses the
 * threshold only as real time advances — never merely because {@link #filter} was called again. That lets the
 * read-only {@code countPending} scan and the real poll cycle both evaluate the same files without one
 * "using up" the other's progress: a file still being written has a recent mtime and stays held no matter how
 * often it is polled. Cross-cycle observations live in a per-source map keyed by
 * {@code sourceId + relativePath}; the {@link #shared()} instance keeps them across the static poll cycles
 * (the same process-wide-per-pipeline-state shape as {@code IngestProgress}). {@code shared()} is one gate
 * <em>per space</em> ({@link EventLog#currentSpaceId()}) so two spaces' sightings never collide.
 *
 * <h3>I/O discipline</h3>
 * The gate stats a file (size + mtime) only when stability gating is enabled <em>and</em> the connector
 * returned {@code UNKNOWN} — i.e. exactly the candidates that need it. When the connector's listing already
 * carried size + mtime (a remote LIST), those are reused and no extra round-trip is made. {@link Clock} and
 * {@link Probe} are injectable so the policy is unit-tested deterministically without sleeping.
 */
public final class StabilityGate {

    /** Time source (millis since epoch); injectable so tests drive quiescence without sleeping. */
    @FunctionalInterface
    public interface Clock {
        long nowMillis();
    }

    /** Size/mtime probe for an {@code UNKNOWN} candidate; injectable for deterministic tests. */
    @FunctionalInterface
    public interface Probe {
        FileStat stat(RemoteFile file) throws AcquisitionException;
    }

    /** A point-in-time size + mtime observation; {@link #NONE} means the file is no longer there. */
    public record FileStat(boolean exists, long size, long mtimeMillis) {
        public static final FileStat NONE = new FileStat(false, -1L, 0L);
    }

    /**
     * Outcome of one {@link #filter} pass: files ready to ingest now, files still held, and — of the ready
     * set — those that crossed waiting→ready on this pass (the {@code FILE_STABLE} transition the engine
     * emits an event for; connector-native {@code READY} files are <em>not</em> counted as transitions).
     */
    public record StabilityResult(List<RemoteFile> ready, List<RemoteFile> waiting, List<RemoteFile> newlyStable) {
        public StabilityResult {
            ready = List.copyOf(ready);
            waiting = List.copyOf(waiting);
            newlyStable = List.copyOf(newlyStable);
        }
    }

    /** One file's last observation: its size, mtime, and how many consecutive cycles it has held that size. */
    private record Sighting(long size, long mtimeMillis, int count) {}

    /** One gate per space ({@link EventLog#currentSpaceId()}); the default space's gate replaces the old singleton. */
    private static final ConcurrentHashMap<String, StabilityGate> SHARED = new ConcurrentHashMap<>();

    /** The gate the static poll cycles share for the calling thread's space, so observations survive across cycles. */
    public static StabilityGate shared() {
        return SHARED.computeIfAbsent(EventLog.currentSpaceId(),
                k -> new StabilityGate(System::currentTimeMillis, StabilityGate::localStat));
    }

    /** Drop the gate for {@code spaceId} (on space deletion), releasing its retained sightings. */
    public static void forget(String spaceId) {
        if (spaceId != null) SHARED.remove(spaceId);
    }

    private final Clock clock;
    private final Probe probe;
    private final ConcurrentHashMap<String, Sighting> sightings = new ConcurrentHashMap<>();

    public StabilityGate(Clock clock, Probe probe) {
        this.clock = clock;
        this.probe = probe;
    }

    /**
     * Partition {@code files} into ready / waiting using the connector's readiness plus size/mtime
     * stabilization for {@code UNKNOWN} files.
     *
     * @param sourceId    identifies this source for the per-file observation key (so pipelines don't collide)
     * @param windowMillis quiescence window: a file is releasable only once unmodified for this long
     * @param sizeChecks   minimum consecutive cycles a file must be seen at the same size (>= 1)
     */
    public StabilityResult filter(String sourceId, List<RemoteFile> files, CollectorConnector connector,
                                  long windowMillis, int sizeChecks) throws AcquisitionException {
        List<RemoteFile> ready = new ArrayList<>();
        List<RemoteFile> waiting = new ArrayList<>();
        List<RemoteFile> newlyStable = new ArrayList<>();
        int checks = Math.max(1, sizeChecks);

        for (RemoteFile f : files) {
            CollectorConnector.Readiness r = connector.readiness(f);
            if (r == CollectorConnector.Readiness.READY) {        // connector-native: short-circuit
                ready.add(f);
                continue;
            }
            if (r == CollectorConnector.Readiness.NOT_READY) {
                waiting.add(f);
                continue;
            }
            // UNKNOWN → engine size/mtime stabilization.
            String key = sourceId + '\0' + f.relativePath();
            FileStat s = probe.stat(f);
            if (!s.exists()) {                                 // vanished between discover and gate
                sightings.remove(key);
                continue;
            }
            long now = clock.nowMillis();
            Sighting prev = sightings.get(key);
            int count = (prev != null && prev.size() == s.size()) ? prev.count() + 1 : 1;
            boolean quiescent = (now - s.mtimeMillis()) >= windowMillis;
            if (quiescent && count >= checks) {
                sightings.remove(key);
                ready.add(f);
                newlyStable.add(f);
            } else {
                sightings.put(key, new Sighting(s.size(), s.mtimeMillis(), count));
                waiting.add(f);
            }
        }
        return new StabilityResult(ready, waiting, newlyStable);
    }

    /** Drop all retained observations — for tests, and for a source that is unregistered. */
    public void clear() {
        sightings.clear();
    }

    /** Default probe: reuse listing metadata when present, else {@code stat()} the local file. */
    private static FileStat localStat(RemoteFile f) throws AcquisitionException {
        if (f.hasSize() && f.lastModified() != null)             // remote listing already carried attrs
            return new FileStat(true, f.size(), f.lastModified().toEpochMilli());
        if (!f.isLocal()) return FileStat.NONE;                  // can't probe a not-yet-fetched remote here
        Path p = f.localPath();
        try {
            if (!Files.exists(p)) return FileStat.NONE;
            return new FileStat(true, Files.size(p), Files.getLastModifiedTime(p).toMillis());
        } catch (IOException e) {
            throw new AcquisitionException("Cannot stat " + f.relativePath() + " for stability check", e);
        }
    }
}
