package com.gamma.util;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * How many Consignments this process is ingesting right now - admitted or waiting for an execution slot
 * ({@code LA-CONFIG-LIMITS-1} CL-12). The ingest path ({@code CollectorProcessor}) holds a {@link #begin() scope} around each
 * Consignment, from before it asks the {@code ConcurrencyBroker} for a slot until the slot is released; a Link Analysis index
 * build reads {@link #active()} and waits while it is true, so an 80 %-of-RAM build and ingest's DuckDB connections never run
 * at the same time.
 *
 * <p>It lives here, below both the engine and Link Analysis, because the Link Analysis modules do not depend on the engine.
 * Process-wide by design: ingest and an index build compete for the same host memory whichever Space they belong to.
 */
public final class IngestActivity {

    private static final AtomicInteger IN_FLIGHT = new AtomicInteger();

    private IngestActivity() {}

    /** One Consignment's ingest; {@link #close()} ends it (idempotent). */
    public static final class Scope implements AutoCloseable {
        private final AtomicBoolean open = new AtomicBoolean(true);

        private Scope() {}

        @Override
        public void close() {
            if (open.compareAndSet(true, false)) IN_FLIGHT.decrementAndGet();
        }
    }

    /** Marks one Consignment's ingest as begun; close the scope when it ends. */
    public static Scope begin() {
        IN_FLIGHT.incrementAndGet();
        return new Scope();
    }

    /** Consignments being ingested (or waiting for a slot to be) right now. */
    public static int inFlight() {
        return IN_FLIGHT.get();
    }

    /** Whether any Consignment is being ingested right now. */
    public static boolean active() {
        return IN_FLIGHT.get() > 0;
    }
}
