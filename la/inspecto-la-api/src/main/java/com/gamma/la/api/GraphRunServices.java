package com.gamma.la.api;

import com.gamma.spi.auth.ApiException;
import com.gamma.spi.auth.ErrorCodes;
import com.gamma.la.core.GraphRunService;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;
import java.util.function.LongSupplier;

/**
 * The per-Space {@link GraphRunService}s of one API (LA-GRAPH-RUN-POOL-LIFECYCLE-1): one per write root, created lazily on
 * first use. Unlike a plain map, it does not keep a pool for the process lifetime:
 *
 * <ul>
 *   <li><b>Idle shutdown.</b> A service nobody asked for, and none of whose runs finished, in {@code idleTtlMs} and with no run in flight is closed (its
 *       worker threads end) and forgotten; the next use builds a fresh one. The sweep runs on access - no extra thread, no
 *       timer. The TTL is as long as a finished run's retention, so closing never shortens what an analyst can still read.</li>
 *   <li><b>A Space that is gone is not kept.</b> A service whose write root is no longer a directory is closed at the next
 *       sweep, however recently it was used (its runs could not be read anyway: the Investigation is gone with the Space).</li>
 *   <li><b>One lock</b> covers sweep, lookup, create and close, so a service can neither be created after {@link #close()} nor
 *       leak because two requests raced to create it.</li>
 * </ul>
 */
final class GraphRunServices implements AutoCloseable {

    /** Idle time after which an unused service is closed: the finished-run retention of {@code Limits.standard()} (1 h). */
    static final long IDLE_TTL_MS = 3_600_000L;

    private static final class Entry {
        final GraphRunService service;
        long lastUsed;

        Entry(GraphRunService service, long now) {
            this.service = service;
            this.lastUsed = now;
        }
    }

    private final Function<Path, GraphRunService> factory;
    private final LongSupplier clock;
    private final long idleTtlMs;
    private final Map<Path, Entry> entries = new LinkedHashMap<>();
    private boolean closed;

    GraphRunServices(Function<Path, GraphRunService> factory, LongSupplier clock, long idleTtlMs) {
        this.factory = factory;
        this.clock = clock;
        this.idleTtlMs = idleTtlMs;
    }

    /** The service of {@code writeRoot} (created if need be), marked used now. @throws ApiException 503 once closed */
    synchronized GraphRunService get(Path writeRoot) {
        if (closed) throw new ApiException(503, ErrorCodes.CAPABILITY_UNAVAILABLE, "the server is shutting down");
        long now = clock.getAsLong();
        sweep(writeRoot, now);
        Entry e = entries.get(writeRoot);
        if (e == null) {
            e = new Entry(factory.apply(writeRoot), now);
            entries.put(writeRoot, e);
        }
        e.lastUsed = now;
        return e.service;
    }

    /** How many services are open (a probe for tests). */
    synchronized int open() {
        return entries.size();
    }

    /** Closes every service and refuses any later {@link #get}. Idempotent. */
    @Override
    public synchronized void close() {
        closed = true;
        for (Entry e : new ArrayList<>(entries.values())) e.service.close();
        entries.clear();
    }

    /** Closes idle services and those whose Space is gone, except {@code keep} (the one being asked for). Caller holds the lock. */
    private void sweep(Path keep, long now) {
        for (Iterator<Map.Entry<Path, Entry>> it = entries.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<Path, Entry> me = it.next();
            if (me.getKey().equals(keep)) continue;
            Entry e = me.getValue();
            boolean gone = !Files.isDirectory(me.getKey());
            // A finished run is retained (and readable) for its TTL, so its finish time counts as use: closing never shortens retention.
            long lastActive = Math.max(e.lastUsed, e.service.lastActivityMillis());
            boolean idle = now - lastActive >= idleTtlMs && e.service.active() == 0;
            if (gone || idle) {
                e.service.close();
                it.remove();
            }
        }
    }
}
