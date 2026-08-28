package com.gamma.inspector;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-batch registry of PARKED branch sinks (Phase 4 S4b, D-13): {@code graphWriteAndTrace}'s park
 * hook records each disabled sink it materialised a park table for, and {@code BatchProcessor.process}
 * drains the batch's entries exactly once to decide parked-finalisation over the normal commit.
 *
 * <p>Same lifecycle idiom as {@code UnpackOrigins}/{@code AcquisitionLedgers.stashChecksum}: written
 * and consumed within one batch's processing on the same thread-of-work; keyed by batchId so parallel
 * batches never mix. A crash loses the map and loses nothing with it — the batch is then neither
 * committed nor parked-manifested, its originals are still in the inbox, and the next cycle re-runs
 * (committed branches idempotently, the parked branch re-parks). Threading an extra field through
 * {@code IngestOutcome}'s eight construction sites for a graph-lane-only concern was the rejected
 * alternative.
 */
final class ParkedBranches {

    private static final Map<String, Map<String, Path>> PARKED = new ConcurrentHashMap<>();

    private ParkedBranches() {}

    /** Record that {@code batchId} parked {@code nodeId}'s rows at {@code parkTable}. */
    static void record(String batchId, String nodeId, Path parkTable) {
        PARKED.computeIfAbsent(batchId, k -> new LinkedHashMap<>()).put(nodeId, parkTable);
    }

    /** Drain {@code batchId}'s parked branches — returned exactly once, empty every other call. */
    static Map<String, Path> drain(String batchId) {
        Map<String, Path> out = PARKED.remove(batchId);
        return out != null ? out : Map.of();
    }
}
