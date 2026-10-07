package com.gamma.audit;

import java.util.List;

/**
 * Append-only sink + query surface for {@link Event}s — the "Event Engine" storage seam of the
 * Operational Intelligence Platform. Two implementations sit behind it: {@code InMemoryEventStore}
 * (a bounded ring; the lean default and the live-tail buffer) and {@code ParquetEventStore} (durable
 * rolling Hive-partitioned Parquet, queried via DuckDB {@code read_parquet}). The Control API and
 * {@code EventLog} depend only on this interface, so the backend is a deployment choice
 * ({@code -Devents.backend=memory|parquet}).
 *
 * <h3>Contract</h3>
 * <ul>
 *   <li><b>Append-only.</b> There is intentionally no update or delete — events are immutable facts.
 *       Retention is the implementation's concern (dropping old ring entries / old Parquet files).</li>
 *   <li>{@link #query(EventQuery)} returns matching events <b>newest-first</b>, honoring the query's
 *       {@code limit}/{@code offset}.</li>
 *   <li>{@link #recent(int)} is the live-tail fast path: the newest {@code limit} events, newest-first.</li>
 *   <li>Implementations must be thread-safe: events arrive from many threads (ingest workers, the log
 *       appender, the bus).</li>
 * </ul>
 *
 * @since 4.0.0
 */
@com.gamma.api.PublicApi(since = "4.0.0")
public interface EventStore extends AutoCloseable {

    /** Append one immutable event. Never blocks on durability — see {@link #flush()}. */
    void append(Event event);

    /**
     * Audit retention (COMPLY-3): drop the durable events whose UTC day is strictly <em>before</em>
     * {@code before}, whole day-partitions at a time — retention on this store is a file delete by
     * partition, never a row-level {@code DELETE}. Returns the number of day-partitions removed (or that
     * <em>would</em> be removed when {@code dryRun}), or {@code -1} when this backend keeps nothing durable
     * to prune (the in-memory store self-caps and forgets on restart). Deliberately does <b>not</b> touch
     * anything inside the window: a purged Incident's history, purge record included, stays retained for the
     * full window (the MNT-14 G3 stance).
     *
     * @since 5.x
     */
    default int prune(java.time.LocalDate before, boolean dryRun) { return -1; }

    /** Matching events, newest-first, paged per {@code query}. */
    List<Event> query(EventQuery query);

    /** The newest {@code limit} events, newest-first (the live-tail fast path). */
    List<Event> recent(int limit);

    /**
     * One keyset page for cursor pagination (api-contract-design §7): the newest {@code limit} events
     * strictly <em>older</em> than the {@code (afterTs, afterId)} position, ordered
     * {@code ts DESC, eventId DESC} (a total order, so a cursor resumes unambiguously even when several
     * events share a timestamp). {@code afterTs == null} means "from the top". The default derives the
     * page from {@link #query(EventQuery)} bounded by {@link EventQuery#MAX_LIMIT}; the bundled stores
     * override it with exact implementations (ring walk / SQL keyset predicate).
     *
     * @since 4.0.0
     */
    default List<Event> page(int limit, Long afterTs, String afterId) {
        return query(EventQuery.recent(EventQuery.MAX_LIMIT)).stream()
                .sorted(KEYSET_ORDER)
                .filter(e -> afterKey(e, afterTs, afterId))
                .limit(Math.max(0, limit))
                .toList();
    }

    /**
     * Total retained events — the {@code metadata.pagination.total} companion of {@link #page}. The
     * default counts through {@link #query(EventQuery)} and is therefore capped at
     * {@link EventQuery#MAX_LIMIT}; the bundled stores override it with exact counts.
     *
     * @since 4.0.0
     */
    default long count() {
        return query(EventQuery.recent(EventQuery.MAX_LIMIT)).size();
    }

    /** The {@link #page} sort: {@code ts DESC, eventId DESC}. */
    java.util.Comparator<Event> KEYSET_ORDER =
            java.util.Comparator.comparingLong(Event::ts)
                    .thenComparing(e -> e.eventId() == null ? "" : e.eventId())
                    .reversed();

    /** {@code true} when {@code e} sorts strictly after (older than) the {@code (afterTs, afterId)} position. */
    static boolean afterKey(Event e, Long afterTs, String afterId) {
        if (afterTs == null) return true;
        String id = e.eventId() == null ? "" : e.eventId();
        return e.ts() < afterTs
                || (e.ts() == afterTs && id.compareTo(afterId == null ? "" : afterId) < 0);
    }

    /**
     * The audit chain's head: the {@linkplain AuditChain#chained chained} record with the highest
     * {@linkplain AuditChain#seq seq}, or {@code null} when none is stored (ASSURE-AUDIT-CHAIN-1). Must THROW
     * rather than answer {@code null} when the store cannot be read — a {@code null} restarts the chain at
     * genesis and forks it. The default walks {@link #page} newest-first: a chain's timestamps never go back
     * (see {@link AuditChain}), so the first chained record met is the head, bar same-millisecond ties.
     *
     * @since 5.x
     */
    default Event chainHead() {
        Long afterTs = null;
        String afterId = null;
        Event best = null;
        while (true) {
            List<Event> page = page(1000, afterTs, afterId);
            if (page.isEmpty()) return best;
            for (Event e : page) {
                if (best != null && e.ts() < best.ts()) return best;
                if (AuditChain.chained(e) && AuditChain.seq(e) > 0
                        && (best == null || AuditChain.seq(e) > AuditChain.seq(best))) best = e;
            }
            Event last = page.get(page.size() - 1);
            afterTs = last.ts();
            afterId = last.eventId();
        }
    }

    /**
     * Up to {@code limit} chained records with {@code seq >= fromSeq}, ordered by seq then eventId (so two
     * records claiming one seq sit side by side). The unit {@code /audit/verify} streams the chain in, so a
     * range is never loaded whole. The default scans the whole store through {@link #page} per call — correct
     * for any backend, linear in its size; the bundled Parquet and in-memory stores override it.
     *
     * @since 5.x
     */
    default List<Event> chainPage(long fromSeq, int limit) {
        java.util.PriorityQueue<Event> keep = new java.util.PriorityQueue<>(CHAIN_ORDER.reversed());
        Long afterTs = null;
        String afterId = null;
        while (true) {
            List<Event> page = page(1000, afterTs, afterId);
            if (page.isEmpty()) break;
            for (Event e : page) {
                if (!AuditChain.chained(e) || AuditChain.seq(e) < fromSeq) continue;
                keep.add(e);
                if (keep.size() > limit) keep.poll();
            }
            Event last = page.get(page.size() - 1);
            afterTs = last.ts();
            afterId = last.eventId();
        }
        List<Event> out = new java.util.ArrayList<>(keep);
        out.sort(CHAIN_ORDER);
        return out;
    }

    /**
     * How many audit-type rows ({@link AuditChain#TYPES}) at or after {@code fromTs} are NOT on the chain — no
     * seq, or marked {@link AuditAttrs#AUDIT_UNLINKED}. Every one is a hole {@code /audit/verify} must report.
     * Default: a keyset walk from the newest row back to {@code fromTs}.
     *
     * @since 5.x
     */
    default long unlinkedSince(long fromTs) {
        long n = 0;
        Long afterTs = null;
        String afterId = null;
        while (true) {
            List<Event> page = page(1000, afterTs, afterId);
            if (page.isEmpty()) return n;
            for (Event e : page) {
                if (e.ts() < fromTs) return n;
                if (AuditChain.TYPES.contains(e.type()) && AuditChain.unlinked(e)) n++;
            }
            Event last = page.get(page.size() - 1);
            afterTs = last.ts();
            afterId = last.eventId();
        }
    }

    /** The ids among {@code ids} this store already holds. Default: a keyset walk of the whole store. @since 5.x */
    default java.util.Set<String> presentIds(java.util.Collection<String> ids) {
        java.util.Set<String> want = new java.util.HashSet<>(ids);
        java.util.Set<String> found = new java.util.HashSet<>();
        Long afterTs = null;
        String afterId = null;
        while (!want.isEmpty()) {
            List<Event> page = page(1000, afterTs, afterId);
            if (page.isEmpty()) break;
            for (Event e : page) if (want.remove(e.eventId())) found.add(e.eventId());
            Event last = page.get(page.size() - 1);
            afterTs = last.ts();
            afterId = last.eventId();
        }
        return found;
    }

    /** Storage units (files) this store could not read on its last chain read — a verify must not pass over
     *  them silently. Empty for stores that have no such units. @since 5.x */
    default List<String> unreadableUnits() {
        return List.of();
    }

    /** Drop any cached knowledge of where chained rows live, so the next chain reads recompute it from storage —
     *  what {@code /audit/verify} does first, never trusting a cache an attacker could steer. Default: nothing
     *  cached. @since 5.x */
    default void rebuildChainIndex() {}

    /** Claim the right to LINK onto this store's audit chain; throws when another writer holds it (see
     *  {@code ParquetEventStore}). Default: nothing to claim. @since 5.x */
    default void claimChainWriter() {}

    /** The {@link #chainPage} order: seq ascending, then eventId. */
    java.util.Comparator<Event> CHAIN_ORDER =
            java.util.Comparator.comparingLong(AuditChain::seq)
                    .thenComparing(e -> e.eventId() == null ? "" : e.eventId());

    /**
     * Force any buffered events to durable storage. No-op for purely in-memory stores; for
     * {@code ParquetEventStore} this flushes the in-memory buffer to a Parquet file.
     */
    default void flush() {}

    /** Flush and release resources (e.g. the DuckDB connection). Idempotent. */
    @Override
    default void close() {}
}
