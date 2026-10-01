package com.gamma.risk;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Collection;
import java.util.Optional;
import java.util.ServiceLoader;

/**
 * The seam a Risk Score model's {@code watchList} feeds through (ASSURE-ENTITY-LISTS-1, WS-12 x WS-22): after a
 * {@code risk.score} run, every entity at or above {@code highThreshold} is added to a {@code watch} Entity List with
 * an expiry of at most 24 h. An expiring entry applies at once and is reviewed after (D-P5), so there is no
 * Pending Change.
 *
 * <p>The Entity List store (the Identity Fact log) lives in the optional {@code inspecto-entity-list} module, which
 * provides the one implementation through {@link ServiceLoader}. Without it (Personal) there is no provider: a model
 * naming a {@code watchList} is refused at save and fails its run. It is never silently left unfed.
 */
public interface WatchListFeed {

    /** Refuse (IllegalArgumentException) when {@code listId} is not a live {@code watch} Entity List of this Space. */
    void check(Path writeRoot, String listId) throws IOException;

    /**
     * Add {@code keys} (raw: the list normalises them with its sealed normaliser) to {@code listId}, expiring at
     * {@code expiresAt}. A key that is already a permanent member stays permanent. Returns the keys written.
     */
    int feed(Path writeRoot, Path dataDir, String listId, Collection<String> keys, Instant expiresAt, String actor,
             String reason) throws IOException;

    /** The installed provider, if any. */
    static Optional<WatchListFeed> installed() {
        return ServiceLoader.load(WatchListFeed.class, WatchListFeed.class.getClassLoader()).findFirst();
    }
}
