package com.gamma.event;

import com.gamma.metrics.MetricRegistry;
import com.gamma.util.CurrentSpace;
import org.slf4j.MDC;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Process-wide entry point for emitting {@link Event}s — the event-engine analogue of
 * {@link MetricRegistry#global()}. Any layer (ingest worker, scheduler, the SLF4J capture appender,
 * the batch-event bridge) records facts through {@link #global()} without threading a store through
 * constructors; the Control API reads them back through the same store via {@code CollectorService.events()}.
 *
 * <h3>Per-space routing</h3>
 * When one server hosts many {@code space}s, each owns its own {@code EventLog} instance ({@link #create()})
 * {@linkplain #register registered} under its space id. Code with a direct handle (a {@code CollectorService})
 * emits to its own instance; code without one (the capture appender, deep poll-path emitters) calls
 * {@link #current()}, which routes by the thread's {@link #SPACE_MDC_KEY} MDC and falls back to {@link #global()}.
 *
 * <h3>Store swap with no lost startup events</h3>
 * The global instance starts with a small {@link InMemoryEventStore} so the very first log lines at
 * JVM start are captured. {@code CollectorService} later {@linkplain #installStore(EventStore) installs}
 * the configured backend (e.g. {@code ParquetEventStore}); {@link #installStore} <b>drains</b> the
 * outgoing store's buffered events into the new one (oldest-first) so nothing emitted before the swap
 * is dropped.
 *
 * <h3>Must not log</h3>
 * {@link #emit} is on the SLF4J capture path, so it deliberately uses no SLF4J logger — a log call
 * here would recurse through the appender. All failures are swallowed (an observability sink must
 * never break the thing it observes). The appender additionally guards against re-entrancy.
 *
 * @since 4.0.0
 */
@com.gamma.api.PublicApi(since = "4.0.0")
public final class EventLog {

    private static final EventLog GLOBAL = new EventLog();

    /** Where {@link #current()} sends an emitter whose MDC names a Space that has been {@linkplain #unregister
     *  unregistered} (a late emitter outliving its Space's close): every emit is DROPPED, announced by one
     *  rate-limited stderr line — never written into {@link #GLOBAL}'s store or chain, or any other Space's. */
    private static final EventLog CLOSED_SPACE = new EventLog(true);
    private static final java.util.concurrent.atomic.AtomicLong lastDropNotice = new java.util.concurrent.atomic.AtomicLong();

    /** MDC key carrying the owning space id; lets the capture appender and {@link #current()} route a
     *  log/event to the right per-space log when one server hosts many spaces.
     *  ⛔ Declared BY REFERENCE to {@link CurrentSpace#SPACE_MDC_KEY} — that is the one definition, because
     *  modules below {@code inspecto-event} need the same key and cannot depend on this one (see
     *  {@link #currentSpaceId()}). Do not re-inline the literal here. */
    public static final String SPACE_MDC_KEY = CurrentSpace.SPACE_MDC_KEY;

    /** Id of the default space — the {@linkplain #global() global log}'s space, and {@code SpaceRoot.legacy().id()}.
     *  By reference to {@link CurrentSpace#DEFAULT_SPACE_ID} for the same reason. */
    public static final String DEFAULT_SPACE_ID = CurrentSpace.DEFAULT_SPACE_ID;

    /** The space id the calling thread is in (its {@link #SPACE_MDC_KEY} MDC), or {@link #DEFAULT_SPACE_ID} when
     *  none is set. The single source of truth for routing the per-space {@code MetricRegistry} label,
     *  {@code ConnectionRegistry}, {@code StabilityGate}, {@code AcquisitionLedgers}, {@code CircuitBreaker}
     *  and {@code GapTracker} to a space.
     *
     *  <p>⚠ This list is load-bearing, so keep it current: the last two joined on 2026-09-10
     *  (SPACE-UNKEYED-STATICS-1) after shipping as process-wide singletons whose own javadocs already
     *  claimed they used this routing. A registry that holds cross-cycle state and is NOT in this list is
     *  the bug to look for.
     *
     *  <p>⛔ <b>The implementation moved to {@link CurrentSpace#id()} on 2026-09-10 and this method is now a
     *  delegate.</b> It stays the entry point every existing caller uses, but the key, the default and the
     *  lookup are stated once, in {@code inspecto-util} — because {@code inspecto-event} <b>depends on</b>
     *  {@code inspecto-etl}, so the ETL-side registries that needed this value could not reach it here
     *  without a dependency cycle. */
    public static String currentSpaceId() {
        return CurrentSpace.id();
    }

    /** Per-space logs, keyed by space id. A hosted space {@linkplain #register registers} its own log here
     *  on start and {@linkplain #unregister removes} it on stop; {@link #current()} resolves through it. */
    private static final ConcurrentHashMap<String, EventLog> SPACES = new ConcurrentHashMap<>();

    /** The process-wide event log — the fallback when no space is in scope, and the {@code default} space's log. */
    public static EventLog global() {
        return GLOBAL;
    }

    /** A fresh, independent event log for a hosted space (its own store + subscribers). */
    public static EventLog create() {
        return new EventLog();
    }

    /** Register {@code log} as the event log for {@code spaceId}, so {@link #current()} and the capture
     *  appender route to it while a thread carries that space in its {@link #SPACE_MDC_KEY} MDC. */
    public static void register(String spaceId, EventLog log) {
        if (spaceId != null && log != null) {
            SPACES.put(spaceId, log);
            CLOSED.remove(spaceId);
        }
    }

    /** Space ids that were registered and then {@linkplain #unregister unregistered} (and not re-registered). */
    private static final java.util.Set<String> CLOSED = ConcurrentHashMap.newKeySet();

    /** Remove a previously {@linkplain #register registered} per-space log (on space teardown). */
    public static void unregister(String spaceId) {
        if (spaceId != null && SPACES.remove(spaceId) != null && !DEFAULT_SPACE_ID.equals(spaceId)) CLOSED.add(spaceId);
    }

    /**
     * Bound for the extent of a <b>contained</b> pass — the flat lane's dry-run parse
     * ({@code PipelineTestRun.dryIngest}, FLAT-DRYRUN-COUNTS-ZERO-1) and the builder's test run: while bound,
     * {@link #current()} returns the bound log instead of the space's, so every ambient emitter inside the
     * pass (the schema-drift Signal, the dedup-dropped event, the capture appender's log lines) lands in a
     * throwaway log rather than on the real ledger. One seam at the lookup, not a flag at each emitter — an
     * emitter added later is contained without knowing it exists. ⚠ A {@code ScopedValue} does not follow
     * work onto another thread; an emitter that hops threads inside the pass is not covered.
     */
    public static final ScopedValue<EventLog> CONTAINED = ScopedValue.newInstance();

    /** The event log for the calling thread's MDC {@link #SPACE_MDC_KEY}, or {@link #global()} when no space
     *  is in scope (or its log was never registered). An MDC naming a Space that has been {@linkplain #unregister
     *  unregistered} gets a log that DROPS the event (fail-closed) instead of the default Space's. Used by code that has no injected handle — the capture
     *  appender and the deep poll-path emitters. */
    public static EventLog current() {
        if (CONTAINED.isBound()) return CONTAINED.get();
        String spaceId = MDC.get(SPACE_MDC_KEY);
        if (spaceId != null) {
            EventLog log = SPACES.get(spaceId);
            if (log != null) return log;
            if (CLOSED.contains(spaceId)) return CLOSED_SPACE;
        }
        return GLOBAL;
    }

    private final AtomicReference<EventStore> store = new AtomicReference<>(new InMemoryEventStore());

    /**
     * Optional live subscribers, invoked on every {@link #emit} <em>after</em> the event is stored. The
     * service tier registers a bridge here that promotes selected domain events (e.g. {@code SEQUENCE_GAP})
     * to managed objects — keeping that policy out of the lean engine core that emits the event. Copy-on-write
     * so emit never blocks a (rare) registration, and each subscriber call is individually guarded so one
     * misbehaving listener can't break the sink (or the log appender it may sit behind).
     */
    private final CopyOnWriteArrayList<Consumer<Event>> subscribers = new CopyOnWriteArrayList<>();

    /** True only for {@link #CLOSED_SPACE}: {@link #emit} drops. */
    private final boolean discard;

    private EventLog() { this(false); }

    private EventLog(boolean discard) { this.discard = discard; }

    /** Register a live subscriber invoked after each {@link #emit}. Idempotent-safe to pair with {@link #removeSubscriber}. */
    public void addSubscriber(Consumer<Event> subscriber) {
        if (subscriber != null) subscribers.add(subscriber);
    }

    /** Remove a previously {@linkplain #addSubscriber registered} subscriber (e.g. on service shutdown). */
    public void removeSubscriber(Consumer<Event> subscriber) {
        if (subscriber != null) subscribers.remove(subscriber);
    }

    /** Number of live subscribers — read by the test-suite leak detector ({@code EventLogLeakDetector}). */
    public int subscriberCount() {
        return subscribers.size();
    }

    /** The current backing store (for the read API / tests). */
    public EventStore store() {
        return store.get();
    }

    /**
     * Install {@code next} as the backing store, draining the previous store's retained events into it
     * (oldest-first) so startup events survive the swap. No-op if {@code next} is {@code null} or the
     * current store.
     */
    public void installStore(EventStore next) {
        if (next == null) return;
        synchronized (chain) {
            EventStore prev = store.getAndSet(next);
            if (prev == next || prev == null) return;
            // The next store has its own chain head (a durable store's history). An audit row carried over was
            // linked onto the OUTGOING store's chain, so it is re-linked onto the incoming one — appended as it
            // was, it would claim a seq the incoming chain already holds (ASSURE-AUDIT-CHAIN-1).
            chain.reset();
            List<Event> carry;
            try {
                carry = prev.recent(Integer.MAX_VALUE);   // newest-first
            } catch (RuntimeException ignore) {
                return;   // best effort — never block the swap
            }
            for (int i = carry.size() - 1; i >= 0; i--) {         // re-append oldest-first
                Event e = carry.get(i);
                try {   // per event: one row that fails must not drop the rest of the carry-over
                    // A carried row the incoming store ALREADY holds (a Space restart re-installing onto its own
                    // directory) is not appended again — re-linked, it would sit in the chain twice.
                    if (AuditChain.chained(e)) {
                        java.util.Set<String> present;
                        try {
                            present = next.presentIds(List.of(e.eventId()));
                        } catch (RuntimeException unknown) {
                            // Fail-closed: the store cannot say whether it holds the row, so it is NOT appended
                            // (it may already be there — a duplicate is a forked chain). Announced, not silent.
                            next.append(Event.builder(EventType.LOG).level(EventLevel.ERROR)
                                    .source(EventLog.class.getName())
                                    .message("audit row " + e.eventId() + " not carried into the new store: it cannot"
                                            + " tell whether it already holds it: " + unknown.getMessage())
                                    .attr("uncarried_event_id", e.eventId()).build());
                            System.err.println("ERROR audit row " + e.eventId() + " not carried into the new store: "
                                    + unknown.getMessage());
                            continue;
                        }
                        if (!present.isEmpty()) continue;
                    }
                    next.append(AuditChain.chained(e) ? linkOrKeep(e, next) : e);
                } catch (RuntimeException ignore) {
                    // best effort — never block the swap
                }
            }
        }
    }

    /**
     * Detach {@code owned} when its owner closes: if it is still the backing store, a fresh bootstrap ring takes its
     * place WITHOUT draining — so the next {@link #installStore} (a later service on this log, e.g. the default
     * Space's {@link #global()}) cannot carry a closed store's history, audit rows included, into its own store and
     * chain. No-op if another store has been installed since.
     */
    public void releaseStore(EventStore owned) {
        if (owned == null) return;
        synchronized (chain) {
            if (store.compareAndSet(owned, new InMemoryEventStore())) chain.reset();
        }
    }

    /** This log's audit hash chain — one per log, so one per Space (see {@link AuditChain}). Its monitor is held
     *  across link + append, which is what makes the chain a single total order. */
    private final AuditChain chain = new AuditChain();

    /**
     * Link {@code e} onto {@code target}'s chain. When that fails (the head is unreadable, the row cannot be
     * hashed) the row is still stored — losing an audit row is worse — but NEVER silently: it is marked
     * {@link AuditAttrs#AUDIT_UNLINKED}, {@code inspecto_audit_unlinked_total} is incremented, and an ERROR event
     * says so. {@code /audit/verify} counts every marked or seq-less audit row and fails on it, so a forced
     * failure (a corrupt file planted in the store) cannot turn into a hole that verifies. Caller holds
     * {@link #chain}'s monitor. ⚠ No SLF4J here: this runs on the capture appender's path.
     */
    private Event linkOrKeep(Event e, EventStore target) {
        try {
            return chain.link(e, target);
        } catch (RuntimeException failed) {
            chain.reset();   // try again on the next audit row
            java.util.Map<String, String> attrs = new java.util.LinkedHashMap<>(e.attributes());
            attrs.put(AuditAttrs.AUDIT_UNLINKED, "true");
            try {
                MetricRegistry.global().inc("inspecto_audit_unlinked_total",
                        "Audit rows stored WITHOUT a hash-chain link (the chain head could not be read)", Map.of());
                target.append(Event.builder(EventType.LOG).level(EventLevel.ERROR).source(EventLog.class.getName())
                        .message("audit row " + e.eventId() + " stored UNLINKED from the audit hash chain: "
                                + failed.getMessage())
                        .attr("unlinked_event_id", e.eventId()).build());
            } catch (Throwable ignore) {
                // best effort: the marker on the row itself is what verify reads
            }
            System.err.println("ERROR audit row " + e.eventId() + " stored UNLINKED from the audit hash chain: "
                    + failed.getMessage());
            return new Event(e.eventId(), e.ts(), e.level(), e.type(), e.source(), e.pipeline(), e.correlationId(),
                    e.message(), attrs, e.payload());
        }
    }

    /** Append one event and bump the {@code inspecto_events_total{level,type}} counter. Never throws. */
    public void emit(Event event) {
        if (event == null) return;
        if (discard) {
            long now = System.currentTimeMillis(), last = lastDropNotice.get();
            if (now - last >= 60_000 && lastDropNotice.compareAndSet(last, now))
                System.err.println("ERROR event dropped: its Space MDC names a closed Space (type=" + event.type()
                        + "); further drops are reported at most once a minute");
            return;
        }
        // Single scrub seam: redact any secret in the message/attributes before it is ever persisted
        // or handed to a subscriber. Cheap (same reference) for the clean common case; never throws.
        event = SecretScrubber.scrub(event);
        try {
            if (AuditChain.chained(event)) {
                // ASSURE-AUDIT-CHAIN-1: linked AFTER the scrub (the hash covers what is stored) and appended under
                // the same monitor, so seq order is append order.
                synchronized (chain) {
                    EventStore target = store.get();
                    event = linkOrKeep(event, target);
                    target.append(event);
                }
            } else {
                store.get().append(event);
            }
            MetricRegistry.global().inc("inspecto_events_total", "Operational events recorded",
                    Map.of("level", event.level().name(), "type", event.type()));
        } catch (Throwable t) {
            // Swallow: an event sink must not disturb the caller (which may be the log appender).
        }
        // Notify live subscribers after the event is durably recorded. Each is guarded independently so a
        // subscriber fault (or its own re-entrant emit) can never break this sink.
        for (Consumer<Event> s : subscribers) {
            try {
                s.accept(event);
            } catch (Throwable ignore) {
                // best effort — a listener must never break the thing it observes
            }
        }
    }

    /** Convenience: build and emit a domain event from a populated builder. */
    public void emit(Event.Builder builder) {
        if (builder != null) emit(builder.build());
    }

    /** Flush the backing store's buffer to durable storage, if any. Never throws. */
    public void flush() {
        try { store.get().flush(); } catch (Throwable ignore) { /* best effort */ }
    }
}
