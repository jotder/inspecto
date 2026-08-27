package com.gamma.ops;

import java.util.List;
import java.util.Optional;

/**
 * Persistence seam for {@link OperationalObject}s — the "Object Engine" of the Operational
 * Intelligence Platform. The counterpart to {@link com.gamma.event.EventStore}, but with the opposite
 * contract: events are append-only facts, objects are <b>mutable</b> records whose status changes over
 * their lifecycle, so this interface has a real {@link #update}.
 *
 * <p>Two implementations sit behind it: {@link InMemoryObjectStore} (the lean default, like the
 * in-memory event store) and {@code DbObjectStore} (durable, JDBC over the bundled DuckDB — or a
 * Postgres URL for a distributed deployment, exactly like {@link com.gamma.service.DbStatusStore}).
 * The backend is a deployment choice ({@code -Dobjects.backend=memory|db}); {@link ObjectService} and
 * the Control API depend only on this interface.
 *
 * <h3>Contract</h3>
 * <ul>
 *   <li>{@link #query(ObjectQuery)} returns matching objects <b>newest-first</b> (by {@code createdAt}),
 *       honoring the query's {@code limit}/{@code offset} — unless the query sets
 *       {@link ObjectQuery#oldestFirst()}, which reverses it. Both backends must agree on ordering as
 *       well as on rows: a retention sweep that reads one page depends on it (MNT-14 G1).</li>
 *   <li>Implementations must be thread-safe.</li>
 * </ul>
 *
 * @since 4.0.0
 */
@com.gamma.api.PublicApi(since = "4.0.0")
public interface ObjectStore extends AutoCloseable {

    /** Insert a new object and return it. Throws {@link IllegalStateException} if the id already exists. */
    OperationalObject create(OperationalObject obj);

    /** The object with this id, or empty. */
    Optional<OperationalObject> get(String id);

    /** Matching objects, newest-first, paged per {@code query}. */
    List<OperationalObject> query(ObjectQuery query);

    /**
     * Persist a mutated object (status/assignee/timestamps). Returns the stored object. Throws
     * {@link java.util.NoSuchElementException} if no object with {@code obj.id()} exists.
     */
    OperationalObject update(OperationalObject obj);

    /**
     * Physically remove an object. Throws {@link java.util.NoSuchElementException} if no object with
     * this id exists. Does not cascade to links/notes/attachments referencing the object — callers
     * that need cascading cleanup must do so themselves.
     *
     * <p><b>Reserved seam — no production caller and no route today.</b> Hard-delete is deliberately
     * not a supported operation on Incidents/Cases: they are only closed, merged, or split, and there
     * is no {@code DELETE /objects/{id}}. This method exists for the MNT-14 retention sweep, where
     * expiry of an archived object's retention window makes it purge-eligible. Do not build cascade
     * logic against it on the assumption that hard-delete is generally available.
     */
    void delete(String id);

    /** Release resources (e.g. the DuckDB connection). Idempotent; no-op for in-memory. */
    @Override
    default void close() {}
}
