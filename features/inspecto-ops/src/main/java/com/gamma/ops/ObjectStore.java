package com.gamma.ops;

import java.util.List;
import java.util.Optional;

/**
 * Persistence seam for {@link OperationalObject}s — the "Object Engine" of the Operational
 * Intelligence Platform. The counterpart to {@link com.gamma.audit.EventStore}, but with the opposite
 * contract: events are append-only facts, objects are <b>mutable</b> records whose status changes over
 * their lifecycle, so this interface has a real {@link #update}.
 *
 * <p>Two implementations sit behind it: {@link InMemoryObjectStore} (the explicit memory opt-in, like the
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

    /** Insert a new object (stored at version 0) and return it. Throws {@link IllegalStateException} if the id already exists. */
    OperationalObject create(OperationalObject obj);

    /** The object with this id, or empty. */
    Optional<OperationalObject> get(String id);

    /** Matching objects, newest-first, paged per {@code query}. */
    List<OperationalObject> query(ObjectQuery query);

    /**
     * Objects of {@code type} whose attribute bag holds EVERY entry of {@code attributes} exactly, oldest
     * first, at most {@code limit}. The identity lookup behind minting a Case member from a Link Analysis
     * Entity (LA-CASE-CREATE-IN-PLACE-1): the same Entity minted twice must find the first object rather
     * than open a second.
     *
     * <p>⛔ <b>Fails closed</b>: a store that cannot answer throws, rather than returning an empty list the way
     * {@link #query} does. An empty answer here means "mint a new one", so a swallowed error would silently
     * duplicate the object it exists to find.
     */
    List<OperationalObject> findByAttributes(com.gamma.workflow.ObjectType type, java.util.Map<String, String> attributes,
                                             int limit);

    /**
     * Objects of any of {@code types} whose ENCODED attribute bag (the compact JSON {@link com.gamma.util.JsonAttributes}
     * writes) contains {@code fragment}, ignoring case — newest first, at most {@code limit}. A <b>prefilter</b> for the
     * Entity "case history" lookup ({@code GET /objects/by-entity}): it only narrows, the caller re-checks every row
     * exactly, so a false positive costs a decode and never a wrong answer.
     *
     * <p>{@link DbObjectStore} answers from a derived side table of the entity-reference attribute values
     * ({@code key.*}, {@code entityKey}): candidates are objects whose reference VALUE contains the fragment. The default
     * (in-memory, test doubles) scans the whole encoded bag via {@link #query}. Either way {@code limit} caps the
     * CANDIDATES returned. Like {@link #findByAttributes} it fails closed (throws).
     */
    default List<OperationalObject> findByAttributeFragment(java.util.Collection<com.gamma.workflow.ObjectType> types,
                                                           String fragment, int limit) {
        String needle = fragment.toLowerCase(java.util.Locale.ROOT);
        List<OperationalObject> out = new java.util.ArrayList<>();
        for (com.gamma.workflow.ObjectType t : types)
            for (OperationalObject o : query(ObjectQuery.builder().objectType(t).limit(ObjectQuery.MAX_LIMIT).build()))
                if (com.gamma.util.JsonAttributes.toJson(o.attributes()).toLowerCase(java.util.Locale.ROOT).contains(needle))
                    out.add(o);
        // createdAt DESC, id DESC before the cap — the same window DbObjectStore's ORDER BY ... LIMIT scans.
        out.sort(java.util.Comparator.comparingLong(OperationalObject::createdAt)
                .thenComparing(OperationalObject::id).reversed());
        return out.size() > limit ? new java.util.ArrayList<>(out.subList(0, limit)) : out;
    }

    /**
     * One {@code (status, priority)} group of a type's objects, aggregated by the store for
     * {@link ObjectService#analytics} (ANALYTICS-CORE-1 S8). The values are the RAW stored ones — upper-casing,
     * the blank-to-NONE rule and the workflow's terminal test stay with the caller, so they keep Java's semantics.
     * {@code firstSeen} is the position of the group's first object in the oldest-first walk ({@code createdAt},
     * then {@code id}), so the caller rebuilds the walk's first-seen key order exactly; {@code cycleCount} /
     * {@code cycleSumMs} cover the group's objects with {@code closedAt > 0 && closedAt >= createdAt}.
     */
    record RollupGroup(String status, String priority, long count, long firstSeen, long cycleCount, long cycleSumMs) {}

    /**
     * The {@link RollupGroup}s of every object of {@code type}, ordered by {@code firstSeen} — or empty when this
     * store cannot aggregate itself (the in-memory store: {@link ObjectService#analytics} then walks the objects).
     * A store that answers here must also answer {@link #scanAttributes}.
     */
    default java.util.Optional<List<RollupGroup>> rollupGroups(com.gamma.workflow.ObjectType type) {
        return java.util.Optional.empty();
    }

    /**
     * Feed {@code sink} the {@code createdAt} and encoded attribute bag (the compact JSON
     * {@link com.gamma.util.JsonAttributes} writes) of every object of {@code type}, oldest first ({@code createdAt},
     * then {@code id}) — a narrow two-column scan instead of paging whole objects. The sink must not call back into
     * a store. {@code false} when this store does not offer the scan.
     */
    default boolean scanAttributes(com.gamma.workflow.ObjectType type, java.util.function.ObjLongConsumer<String> sink) {
        return false;
    }

    /**
     * Persist a mutated object (status/assignee/timestamps) <b>optimistically</b>: it is written only if the
     * stored row is still at {@code obj.version()} (the version the caller read), and the stored version is
     * bumped by one. Returns the stored object, at its new version. The check and the write are one atomic step
     * in every implementation.
     *
     * @throws java.util.NoSuchElementException   if no object with {@code obj.id()} exists
     * @throws ObjectVersionConflictException     if the stored row is at a different version — nothing is written
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
