package com.gamma.la.api;

import com.gamma.spi.auth.ApiException;
import com.gamma.spi.auth.ErrorCodes;
import com.gamma.la.storage.IndexBuildService;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;
import java.util.function.LongSupplier;

/**
 * The per-Space {@link IndexBuildService}s of one API: one per write root, created lazily on first use - the same
 * lifecycle as {@link GraphRunServices} (an idle service is closed and its threads end; a Space that is gone is not
 * kept; one lock covers sweep, lookup, create and close, so nothing is created after {@link #close()} and nothing leaks
 * to a race). A finished build counts as activity for the retention TTL, so closing never shortens what can still be read.
 */
final class IndexBuildServices implements AutoCloseable {

    /** Idle time after which an unused service is closed: the finished-build retention of {@code Limits.standard()} (1 h). */
    static final long IDLE_TTL_MS = 3_600_000L;

    private static final class Entry {
        final IndexBuildService service;
        long lastUsed;

        Entry(IndexBuildService service, long now) {
            this.service = service;
            this.lastUsed = now;
        }
    }

    private final Function<Path, IndexBuildService> factory;
    private final LongSupplier clock;
    private final long idleTtlMs;
    private final Map<Path, Entry> entries = new LinkedHashMap<>();
    private boolean closed;

    IndexBuildServices(Function<Path, IndexBuildService> factory, LongSupplier clock, long idleTtlMs) {
        this.factory = factory;
        this.clock = clock;
        this.idleTtlMs = idleTtlMs;
    }

    /** The service of {@code writeRoot} (created if need be), marked used now. @throws ApiException 503 once closed */
    synchronized IndexBuildService get(Path writeRoot) {
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
            long lastActive = Math.max(e.lastUsed, e.service.lastActivityMillis());
            boolean idle = now - lastActive >= idleTtlMs && e.service.active() == 0;
            if (gone || idle) {
                e.service.close();
                it.remove();
            }
        }
    }
}
