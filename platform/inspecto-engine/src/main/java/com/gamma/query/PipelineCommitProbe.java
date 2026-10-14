package com.gamma.query;

import com.gamma.audit.Event;
import com.gamma.audit.EventQuery;
import com.gamma.audit.EventStore;
import com.gamma.audit.EventType;
import com.gamma.signal.Signal;
import com.gamma.signal.SignalType;

import java.util.Map;
import java.util.OptionalLong;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * The commit clock behind a freshness Alert Rule on a polled Collector (LA-DATA-TRUST-1 live drive, 2026-10-14):
 * {@code Pipeline name} → epoch millis of its last successful, non-dry-run {@code pipeline.commit}, empty when none is known.
 *
 * <p>A Collector that polls an inbox commits Files and emits {@code pipeline.commit}; only a Job-run Pipeline emits
 * {@code dataset.write}, so {@link DatasetFreshnessProbe} alone reads {@code unknown} for the daily-feed shape forever.
 * Live from the bus, with one bounded cold-start scan of the event store per Pipeline (a miss is cached, like
 * {@link DatasetFreshnessProbe}: a later commit arrives on the bus and only ever raises the answer).
 */
public final class PipelineCommitProbe implements Function<String, OptionalLong> {

    static final int SCAN_LIMIT = 10_000;

    private final Map<String, Long> last = new ConcurrentHashMap<>();
    private final EventStore store;

    public PipelineCommitProbe(EventStore store) {
        this.store = store;
    }

    public Consumer<Event> subscriber() {
        return event -> {
            String pipeline = pipelineOf(event);
            if (pipeline != null) last.merge(pipeline, event.ts(), Math::max);
        };
    }

    @Override
    public OptionalLong apply(String pipeline) {
        if (pipeline == null || pipeline.isBlank()) return OptionalLong.empty();
        String id = pipeline.trim();
        Long known = last.get(id);
        if (known == null) {
            last.merge(id, scan(id), Math::max);
            known = last.get(id);
        }
        return known < 0 ? OptionalLong.empty() : OptionalLong.of(known);
    }

    private long scan(String id) {
        if (store == null) return -1;
        try {
            EventQuery q = EventQuery.builder().type(EventType.SIGNAL).textContains(id).limit(SCAN_LIMIT).build();
            long newest = -1;
            for (Event e : store.query(q)) if (id.equals(pipelineOf(e))) newest = Math.max(newest, e.ts());
            return newest;
        } catch (RuntimeException e) {
            return -1;   // never break a sweep: unknown is silent
        }
    }

    private static String pipelineOf(Event event) {
        if (event == null || !EventType.SIGNAL.equals(event.type())) return null;
        if (!SignalType.PIPELINE_COMMIT.equals(event.attributes().get(Signal.ATTR_TYPE))) return null;
        Map<String, Object> p = event.payload();
        if (!"SUCCESS".equals(p.get("status")) || Boolean.TRUE.equals(p.get("dryRun"))) return null;
        return p.get("pipeline") instanceof String s && !s.isBlank() ? s.trim() : null;
    }
}
