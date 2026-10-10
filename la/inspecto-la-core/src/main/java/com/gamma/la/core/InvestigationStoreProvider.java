package com.gamma.la.core;

import java.io.IOException;

/**
 * The seam an optional module uses to supply the {@code investigations.backend=db} {@link InvestigationStore} (design
 * {@code docs/archived-documents/plans-archive/investigation-store-design.md}, D-IS7 / D-IS8). Registered through
 * {@code META-INF/services/com.gamma.la.core.InvestigationStoreProvider} and found by {@link InvestigationStores}; a bundle
 * without the module has no provider, and selecting {@code db} then answers 503 (never a silent fall-back to the filesystem:
 * a fall-back on one pod would fork the evidence).
 *
 * <p>An implementation caches what is expensive (the connection pool, the schema bootstrap) because {@link InvestigationStores#of}
 * is called once per request. It must not cache a FAILURE: a database that was down for one request must be usable on the next.
 */
public interface InvestigationStoreProvider {

    /** Where the database is. {@code user} / {@code password} are {@code null} when the URL carries them. */
    record Connection(String url, String user, String password) {
        /** The password is a secret: it never reaches a log line through {@code toString}. */
        @Override
        public String toString() {
            return "Connection[url=" + url.split("\\?", 2)[0] + "]";
        }
    }

    /** The backend this provider serves, as spelled in {@code investigations.backend} ({@code db}). */
    String backend();

    /**
     * The store of one Space. {@code spaceId} names the Space (one schema per Space, D-IS8).
     *
     * {@code maxSetBytes} is the Space's per-set size limit ({@link WorkingSetSizeLimit}), read per write; the store enforces it on
     * every write path that stores a set. {@code maxInvestigationBytes} is the Space's per-Investigation total set budget
     * ({@link InvestigationSetBudget}), likewise read per write.
     *
     * @throws IOException when the database cannot be reached or its schema cannot be created; the caller turns this into 503
     */
    InvestigationStore open(String spaceId, Connection connection, java.util.function.LongSupplier maxSetBytes,
                           java.util.function.LongSupplier maxInvestigationBytes) throws IOException;
}
