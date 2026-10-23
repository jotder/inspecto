package com.gamma.entitylist;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Collection;
import java.util.Optional;
import java.util.ServiceLoader;

/**
 * The seam a Risk Score or Anomaly Model's {@code watchList} feeds through (ASSURE-ENTITY-LISTS-1, WS-12 x WS-22): after a
 * {@code risk.score} run, every entity at or above {@code highThreshold} is added to a {@code watch} Entity List with
 * an expiry of at most 24 h. An expiring entry applies at once and is reviewed after (D-P5), so there is no
 * Pending Change.
 *
 * <p>The Entity List store (the Identity Fact log) lives in the optional {@code inspecto-entity-list} module, which
 * provides the one implementation through {@link ServiceLoader}. Without it (Basic) there is no provider: a model
 * naming a {@code watchList} is refused at save and fails its run. It is never silently left unfed.
 */
public interface WatchListFeed {

    /** The purpose of a list a score feeds ({@code watchList}). */
    String WATCH = "watch";
    /** The purpose of a list whose members a score leaves out ({@code exclusionList}, ANOMALY-DETECTION-1 §9). */
    String EXCLUSION = "exclusion";

    /**
     * Refuse (IllegalArgumentException) when {@code listId} is not a live Entity List of this Space with
     * {@code purpose} ({@link #WATCH} or {@link #EXCLUSION}). Returns its live-membership test as of now: the
     * predicate takes a RAW key (normalised by the list's sealed normaliser) and is true for a live member or range
     * entry — how an Anomaly Model's {@code exclusionList} removes known heavy users / test SIMs before its scores
     * are written. A watch-list caller just checks and ignores it.
     */
    java.util.function.Predicate<String> check(Path writeRoot, String listId, String purpose) throws IOException;

    /**
     * Add {@code keys} (raw: the list normalises them with its sealed normaliser) to {@code listId}, expiring at
     * {@code expiresAt}. A key that is already a permanent member stays permanent. Returns the keys written.
     */
    int feed(Path writeRoot, Path dataDir, String listId, Collection<String> keys, Instant expiresAt, String actor,
             String reason) throws IOException;

    /**
     * A score run's feed, the one path every score kind takes (ANALYTICS-CORE-1 M7): add {@code highKeys} to
     * {@code listId}, expiring {@code ttlHours} after {@code now}, as actor {@code job:<jobType>:<modelId>}. With no
     * provider installed (Basic) the run fails naming the model, never left silently unfed; no keys writes nothing.
     *
     * @param kind the model's component kind, as the refusal names it ({@code risk-score}, {@code anomaly-model})
     */
    static int feedScored(Optional<WatchListFeed> provider, String kind, String modelId, String jobType, String listId,
                          int ttlHours, Path writeRoot, Path dataDir, java.util.List<String> highKeys, Instant now,
                          String reason) throws IOException {
        WatchListFeed feed = provider.orElseThrow(() -> new IllegalStateException(kind + " '" + modelId
                + "' feeds watch list '" + listId + "', but Entity Lists are not installed in this edition"));
        if (highKeys.isEmpty()) return 0;
        return feed.feed(writeRoot, dataDir, listId, highKeys, now.plus(java.time.Duration.ofHours(ttlHours)),
                "job:" + jobType + ":" + modelId, reason);
    }

    /** The installed provider, if any. */
    static Optional<WatchListFeed> installed() {
        return ServiceLoader.load(WatchListFeed.class, WatchListFeed.class.getClassLoader()).findFirst();
    }
}
