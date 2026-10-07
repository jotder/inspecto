package com.gamma.acquire;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.gamma.event.EventLog;

/**
 * Per-space memory of which sequence gaps have already been reported, so a <em>persistent</em> gap fires its
 * {@code SEQUENCE_GAP} event once rather than on every poll cycle (the poll loop re-runs
 * {@link GapDetector} each cycle, and a hole stays a hole until the file lands).
 *
 * <p>Same shape as {@link StabilityGate#shared()} / {@link AcquisitionLedgers#shared()}: a <b>per-space</b>
 * {@link #shared()} instance holds per-{@code sourceId} state, since each poll cycle is a fresh static run.
 * State is the set of currently-open missing keys per source; {@link #newGaps} returns the keys newly missing
 * this cycle and prunes any that have since been filled — so a gap that reappears after being filled fires
 * again (it is genuinely a new hole).
 *
 * <p>🔴 <b>The space dimension was added 2026-09-10 (SPACE-UNKEYED-STATICS-1) and this javadoc already
 * claimed it</b> — it asserted parity with those two siblings, which key on {@link EventLog#currentSpaceId()},
 * while this class held a single process-wide instance keyed on a bare {@code sourceId}. Two spaces using the
 * same collector id therefore SUPPRESSED each other's {@code SEQUENCE_GAP} events: a missed gap, which is the
 * opposite of what this tracker exists to guarantee.
 */
public final class GapTracker {

    /** Per-space trackers, keyed by space id — see the class javadoc on why this is not one instance. */
    private static final Map<String, GapTracker> SHARED = new ConcurrentHashMap<>();

    /** The tracker the poll loop shares <em>for the calling thread's space</em>. */
    public static GapTracker shared() {
        return SHARED.computeIfAbsent(EventLog.currentSpaceId(), k -> new GapTracker());
    }

    /** Drop the tracker for {@code spaceId} (on space deletion), releasing its remembered open gaps. */
    public static void forgetSpace(String spaceId) {
        if (spaceId != null) SHARED.remove(spaceId);
    }

    private final Map<String, Set<String>> openGaps = new ConcurrentHashMap<>();

    /**
     * Reconcile {@code currentMissing} for {@code sourceId} against what was already reported: return the keys
     * that are <b>newly</b> missing (to be emitted now), and update the remembered set to exactly
     * {@code currentMissing} (so filled keys are forgotten and would re-fire if they ever go missing again).
     */
    public List<String> newGaps(String sourceId, Collection<String> currentMissing) {
        Set<String> current = new HashSet<>(currentMissing);
        Set<String> prior = openGaps.put(sourceId, current);
        List<String> fresh = new ArrayList<>();
        for (String key : currentMissing)
            if (prior == null || !prior.contains(key)) fresh.add(key);
        return fresh;
    }

    /** Forget all remembered gaps (test hook / pipeline reset). */
    public void reset() { openGaps.clear(); }

    /** Forget the remembered gaps for one source. */
    public void reset(String sourceId) { openGaps.remove(sourceId); }
}
