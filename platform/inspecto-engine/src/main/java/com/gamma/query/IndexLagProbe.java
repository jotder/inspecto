package com.gamma.query;

import com.gamma.audit.Event;
import com.gamma.audit.EventType;
import com.gamma.signal.DatasetWriteSignal;
import com.gamma.signal.Signal;
import com.gamma.signal.SignalType;

import java.util.Map;
import java.util.OptionalLong;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.function.BiFunction;

/**
 * The clock behind an index-lag Alert Rule (LA-DATA-TRUST-1 S4): {@code dataset id} → epoch millis since which the Dataset
 * has held data the Link Analysis index does not cover yet, empty when nothing is waiting.
 *
 * <p>Both halves are Signals the platform already emits: {@code dataset.write} (data landed - the first one after the last
 * covering build starts the wait) and {@code la.index.build.completed} with a {@code BUILT} / {@code UP_TO_DATE} result
 * (the index covers everything published up to that moment, so the wait ends). A refused or failed build covers nothing.
 *
 * <p>⚠ In-memory, deliberately: after a restart nothing reads as waiting until the next publication, which fails toward
 * silence (never a false lag Alert for data that was indexed long ago). ⚠ A build that was already running when a File
 * committed and finishes after it counts as covering that File; the next commit re-triggers a build, so the error is one
 * File wide and self-healing. The measured age is the wait of the OLDEST uncovered publication, not the newest, so a feed
 * that keeps delivering cannot keep resetting the clock.
 */
public final class IndexLagProbe implements BiFunction<String, String, OptionalLong> {

    /** Dataset id → publication instant that started the current wait. */
    private final Map<String, Long> waitingSince = new ConcurrentHashMap<>();
    /** Pipeline name → instants of its successful commits (a polled Collector emits these and never {@code dataset.write}). */
    private final Map<String, CopyOnWriteArrayList<Long>> commits = new ConcurrentHashMap<>();
    /** Dataset id → instant of the newest covering build. */
    private final Map<String, Long> covered = new ConcurrentHashMap<>();
    private static final int MAX_COMMITS = 1000;

    /** The bus subscriber that keeps the clock current; ignores every Event that is neither of the two Signals. */
    public Consumer<Event> subscriber() {
        return event -> {
            if (event == null || !EventType.SIGNAL.equals(event.type())) return;
            String type = event.attributes().get(Signal.ATTR_TYPE);
            if (SignalType.PIPELINE_COMMIT.equals(type)) {
                String pipeline = text(event.payload().get("pipeline"));
                if (pipeline != null && "SUCCESS".equals(event.payload().get("status"))
                        && !Boolean.TRUE.equals(event.payload().get("dryRun"))) {
                    var list = commits.computeIfAbsent(pipeline, k -> new CopyOnWriteArrayList<>());
                    list.add(event.ts());
                    while (list.size() > MAX_COMMITS) list.remove(0);
                }
                return;
            }
            String dataset = text(event.payload().get("dataset"));
            if (dataset == null) return;
            if (DatasetWriteSignal.TYPE.equals(type)) {
                waitingSince.merge(dataset, event.ts(), Math::min);
            } else if (SignalType.LA_INDEX_BUILD_COMPLETED.equals(type) && covers(event.payload().get("result"))) {
                // everything published up to this build's end is covered; a later publication starts a new wait
                waitingSince.computeIfPresent(dataset, (d, since) -> since <= event.ts() ? null : since);
                covered.merge(dataset, event.ts(), Math::max);
            }
        };
    }

    /** The wait that started with a {@code dataset.write} for {@code datasetId}. */
    public OptionalLong apply(String datasetId) {
        Long since = datasetId == null ? null : waitingSince.get(datasetId.trim());
        return since == null ? OptionalLong.empty() : OptionalLong.of(since);
    }

    /**
     * The oldest wait for {@code datasetId}: its {@code dataset.write} wait, or the oldest successful commit of
     * {@code pipeline} (the Collector feeding it) that no covering build has followed. A polled Collector emits
     * {@code pipeline.commit} and no {@code dataset.write}, so without the second half no daily feed ever lags.
     */
    @Override
    public OptionalLong apply(String datasetId, String pipeline) {
        OptionalLong write = apply(datasetId);
        if (pipeline == null || pipeline.isBlank() || datasetId == null) return write;
        long cover = covered.getOrDefault(datasetId.trim(), Long.MIN_VALUE);
        var list = commits.get(pipeline.trim());
        OptionalLong oldest = list == null ? OptionalLong.empty()
                : list.stream().mapToLong(Long::longValue).filter(t -> t > cover).min();
        if (oldest.isEmpty()) return write;
        return write.isEmpty() ? oldest : OptionalLong.of(Math.min(write.getAsLong(), oldest.getAsLong()));
    }

    private static boolean covers(Object result) {
        return "BUILT".equals(result) || "UP_TO_DATE".equals(result);
    }

    private static String text(Object v) {
        return v instanceof String s && !s.isBlank() ? s.trim() : null;
    }
}
