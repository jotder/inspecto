package com.gamma.query;

import com.gamma.event.Event;
import com.gamma.event.EventLog;
import com.gamma.event.EventQuery;
import com.gamma.event.EventStore;
import com.gamma.event.EventType;
import com.gamma.signal.DatasetWriteSignal;
import com.gamma.signal.Signal;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.OptionalLong;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * The clock behind {@code DUCKLE-C1} Dataset freshness: {@code dataset id} → epoch millis of that
 * Dataset's <b>last publication</b>, empty when it has never published.
 *
 * <h3>Why {@code dataset.write} is the right — and the only — substrate</h3>
 * A Dataset in this system is a READ ({@code {view: …}} or {@code {physicalRef: …}} over at-rest data,
 * see {@link DatasetRelation}). It carries no producing pipeline, no schedule, and
 * {@code CatalogOverlay.overlayFor} maps {@code NodeKind.DATASET} to {@code OperationalOverlay.NONE},
 * so a Dataset node has no {@code latestRunTime} either. The one moment the platform knows a Dataset's
 * data became visible is {@link DatasetWriteSignal} — and since {@code DATASET-PUBLISH-ON-FAILURE-1}
 * (2026-09-15) it is emitted only after the whole chain completes, which is exactly the row's
 * requirement that <b>a failed or partial run does not count as a refresh</b>. Reading it here inherits
 * that guarantee instead of re-deriving it.
 *
 * <h3>Two sources, one answer</h3>
 * <ol>
 *   <li><b>Live</b> — {@link #subscriber()} records every {@code dataset.write} as it is emitted. This
 *       is the steady-state path and costs nothing.</li>
 *   <li><b>Cold start</b> — on the first miss for a Dataset, one bounded scan of the event store
 *       recovers the last publication from before this process started.</li>
 * </ol>
 *
 * <p>The cold-start answer is cached <b>including a miss</b>, so the scan runs at most once per Dataset
 * per process. Caching a miss is safe precisely because of source (1): any publication after the scan
 * arrives on the bus, so a cached "never published" can only ever be corrected upwards, never left
 * stale. ⚠ The reverse — re-scanning on every sweep — would put a store query on a once-a-minute timer
 * for every Dataset that has genuinely never published, which is the common case for a newly authored
 * freshness rule.
 *
 * <p>⚠ A scan that reaches its row budget without finding the Dataset answers <b>empty</b>, which the
 * evaluator reads as {@code unknown} — not as {@code fresh}. Under-reporting freshness is the safe
 * direction: {@code unknown} is silent, whereas a fabricated recent timestamp would report a dead
 * Dataset green.
 *
 * <h3>The durable floor — why retention cannot make a breach invisible (duckle S2)</h3>
 * The event store is not a durable record of the LAST publication: {@code event_prune} drops whole day
 * partitions past the audit-retention window. A Dataset that stopped publishing longer ago than the
 * window loses its only {@code dataset.write} to the prune, and after a restart the cold-start scan
 * finds nothing — "never published" reads as {@code unknown}, and {@code unknown} never fires. The
 * stalest Dataset in the space would go silent, exactly when its rule matters most.
 *
 * <p>So an optional {@link #FLOOR_FILE} keeps one line per Dataset — its newest known publication —
 * rewritten whenever that value advances (from the bus or from a scan, so a space upgraded onto this
 * recovers its floor on the first sweep). The cold-start answer is the MAX of the scan and the floor.
 * ⛔ It is chosen over a prune floor (keeping the partition that holds each Dataset's last publication)
 * because that would hold whole days of unrelated audit past the retention window, indefinitely, for
 * precisely the Dataset that never publishes again — making the stated window false — and would teach
 * both event-store implementations what a Signal is. ⚠ It is a <b>fact</b> (a timestamp), not a stale
 * flag: staleness is still computed at read time from it and the clock, as {@code AlertService} requires.
 */
public final class DatasetFreshnessProbe implements Function<String, OptionalLong> {

    /**
     * How far back the cold-start scan reaches, in event rows. Generous because it runs at most once
     * per Dataset per process, and narrowed first by {@link EventQuery#textContains} on the Signal's
     * compact {@code dataset:<id>} source.
     */
    static final int SCAN_LIMIT = 10_000;

    /** The durable last-publication floor's file name (one {@code <dataset>\t<epoch ms>} line per Dataset). */
    public static final String FLOOR_FILE = "dataset-publications.tsv";

    /** Last publication per dataset id; {@code -1} = scanned and not found (a cached miss). */
    private final Map<String, Long> lastWrite = new ConcurrentHashMap<>();
    private final EventStore store;
    /** The durable floor, or {@code null} when none is kept (a non-durable event store keeps none). */
    private final Path floorFile;
    /** The floor as last read or written — what {@link #floorFile} holds. */
    private final Map<String, Long> floor = new ConcurrentHashMap<>();

    /** Reads the ambient space's event store. */
    public DatasetFreshnessProbe() {
        this(EventLog.current().store());
    }

    /** Explicit store — the unit-test seam, and the way a caller that already holds one avoids the MDC. */
    public DatasetFreshnessProbe(EventStore store) {
        this(store, null);
    }

    /**
     * Explicit store plus the durable last-publication floor (see the class doc). A {@code null}
     * {@code floorFile} keeps no floor; an unreadable one starts empty — the floor can only ever raise
     * an answer, so losing it degrades to the scan-only behaviour, never to a fabricated timestamp.
     */
    public DatasetFreshnessProbe(EventStore store, Path floorFile) {
        this.store = store;
        this.floorFile = floorFile;
        if (floorFile != null) loadFloor();
    }

    /**
     * The bus subscriber that keeps the clock current. Register it on the same {@link EventLog} the
     * writers emit to. Ignores everything that is not a {@code dataset.write} Signal.
     */
    public Consumer<Event> subscriber() {
        return event -> {
            String dataset = datasetOf(event);
            if (dataset == null) return;
            // merge, not put: subscribers are not ordered, and a publication that is older than one
            // already recorded must never move the clock backwards.
            lastWrite.merge(dataset, event.ts(), Math::max);
            raiseFloor(dataset, event.ts());
        };
    }

    @Override
    public OptionalLong apply(String datasetId) {
        if (datasetId == null || datasetId.isBlank()) return OptionalLong.empty();
        String id = datasetId.trim();
        Long known = lastWrite.get(id);
        if (known != null) return known < 0 ? OptionalLong.empty() : OptionalLong.of(known);
        long found = Math.max(scan(id), floor.getOrDefault(id, -1L));
        lastWrite.merge(id, found, Math::max);   // caches the miss (-1) too — see the class doc
        if (found >= 0) raiseFloor(id, found);   // a scan hit seeds the floor before a prune can take it
        return found < 0 ? OptionalLong.empty() : OptionalLong.of(found);
    }

    /** Persist {@code ts} as {@code dataset}'s floor when it advances it; never throws (it runs on emit). */
    private void raiseFloor(String dataset, long ts) {
        if (floorFile == null || dataset.indexOf('\t') >= 0 || dataset.indexOf('\n') >= 0) return;
        synchronized (floor) {
            if (floor.getOrDefault(dataset, -1L) >= ts) return;
            floor.put(dataset, ts);
            StringBuilder out = new StringBuilder();
            for (var e : new java.util.TreeMap<>(floor).entrySet())
                out.append(e.getKey()).append('\t').append(e.getValue()).append('\n');
            try {
                com.gamma.util.AtomicFiles.write(floorFile, out.toString().getBytes(StandardCharsets.UTF_8),
                        ".pub-");
            } catch (IOException | RuntimeException e) {
                // The in-memory floor still answers for this process; only the restart guarantee is
                // lost, and only until the next publication rewrites the file.
            }
        }
    }

    private void loadFloor() {
        try {
            if (!Files.isRegularFile(floorFile)) return;
            for (String line : Files.readAllLines(floorFile, StandardCharsets.UTF_8)) {
                int tab = line.indexOf('\t');
                if (tab <= 0) continue;
                try {
                    floor.merge(line.substring(0, tab), Long.parseLong(line.substring(tab + 1).trim()), Math::max);
                } catch (NumberFormatException skip) {
                    // a malformed line is ignored: the floor may only raise an answer, never invent one
                }
            }
        } catch (IOException | RuntimeException e) {
            floor.clear();
        }
    }

    /** The newest {@code dataset.write} for {@code id} in the store, or {@code -1} when there is none. */
    private long scan(String id) {
        if (store == null) return -1;
        try {
            // The Signal's Event.source() is the compact "dataset:<id>", which textContains matches —
            // but it is a SUBSTRING match, so "dataset:sales" would also hit "dataset:sales_daily".
            // It narrows the scan; datasetOf below is what decides, on the exact payload value.
            EventQuery q = EventQuery.builder()
                    .type(EventType.SIGNAL)
                    .textContains("dataset:" + id)
                    .limit(SCAN_LIMIT)
                    .build();
            long newest = -1;
            for (Event e : store.query(q))
                if (id.equals(datasetOf(e))) newest = Math.max(newest, e.ts());
            return newest;
        } catch (RuntimeException e) {
            // The clock must never break evaluation: an unreadable store answers "unknown", which is
            // silent, rather than propagating out of a once-a-minute sweep.
            return -1;
        }
    }

    /** The Dataset id this event announces a publication for, or {@code null} if it announces none. */
    private static String datasetOf(Event event) {
        if (event == null || !EventType.SIGNAL.equals(event.type())) return null;
        if (!DatasetWriteSignal.TYPE.equals(event.attributes().get(Signal.ATTR_TYPE))) return null;
        Object dataset = event.payload().get("dataset");
        if (!(dataset instanceof String s) || s.isBlank()) return null;
        return s.trim();
    }
}
