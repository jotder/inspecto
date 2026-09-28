package com.gamma.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.etl.PartitionWriter;
import com.gamma.sql.SqlViews;
import com.gamma.util.DuckDbUtil;
import com.gamma.util.JsonAttributes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Durable, append-only event store backed by <b>rolling Hive-partitioned Parquet</b>, queried by
 * DuckDB — the answer to "can rolling Parquet be the database?" for the immutable EVENT layer
 * (see {@code docs/superpowers/specs/2026-06-13-operational-intelligence-roadmap.md} §0).
 *
 * <h3>Why this works (and where it doesn't)</h3>
 * Events are append-only and never modified, which is exactly Parquet's sweet spot. We do <b>not</b>
 * write one file per event (the small-files anti-pattern); instead events are buffered in memory and
 * <b>flushed in batches</b> to Parquet, partitioned by {@code level/year/month/day}. Reads glob the
 * directory through {@code read_parquet(..., hive_partitioning=true)} — the same idiom the enrichment
 * engine and KPI oracle already use ({@link SqlViews}). Retention is a file delete, not a SQL DELETE.
 * In-place updates are impossible here — fine for immutable events, which is why mutable operational
 * objects (Phase 2+) use a table store instead.
 *
 * <h3>Live tail</h3>
 * The newest events have not been flushed yet, so {@link #recent(int)} serves them from an in-memory
 * ring, and {@link #query(EventQuery)} merges the unflushed buffer with the on-disk Parquet so a search
 * never misses the most recent facts.
 *
 * <h3>Crash durability — the write-ahead journal (operator, 2026-09-26)</h3>
 * Every buffered event is ALSO appended as one JSON line to {@value #JOURNAL} in the store's root, and forced
 * to disk for {@link EventType#AUDIT} / {@link EventType#ACCESS_DENIED}. A successful flush truncates the
 * journal; opening a store replays a leftover journal into Parquet first. So a hard kill (no {@link #close()},
 * no shutdown hook) loses no buffered event — before this, up to 10 s or 1 000 events, audit rows included,
 * vanished (found 2026-09-26: a demo build stopped by kill came back with none of its audit trail). It adds no
 * Parquet files. A torn last line (a kill mid-write) is skipped. The journal is not a {@code .parquet} file, so
 * the {@code read_parquet} glob never sees it.
 * <h3>Threading</h3>
 * A single DuckDB {@link Connection} (not thread-safe) is shared for both the flush {@code COPY} and
 * the {@code read_parquet} queries, so every public method is {@code synchronized}. Event volume is
 * modest relative to ingest, and flushes are batched, so the single lock is not a bottleneck.
 *
 * @since 4.0.0
 */
@com.gamma.api.PublicApi(since = "4.0.0")
public final class ParquetEventStore implements EventStore {

    private static final Logger log = LoggerFactory.getLogger(ParquetEventStore.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    /** Scratch table the buffer is staged into before each partitioned {@code COPY}. */
    private static final String BUF_TABLE = "evt_buf";

    /** The write-ahead journal's file name, in the store root (see class doc). */
    public static final String JOURNAL = "pending.jsonl";

    /** Flush when this many events are buffered. */
    public static final int DEFAULT_FLUSH_THRESHOLD = 1000;
    /** Flush when the oldest buffered event is older than this, even below the size threshold. */
    public static final long DEFAULT_ROLL_MILLIS = 10_000L;

    private final Path root;
    private final int flushThreshold;
    private final long rollMillis;
    private final Connection conn;

    /** Unflushed events, oldest-first (write order). */
    private final Deque<Event> buffer = new ArrayDeque<>();
    /** Live-tail ring, newest-first; retained across flushes for {@link #recent(int)}. */
    private final InMemoryEventStore tail;
    /** Wall-clock when the current buffer started filling (for time-roll). */
    private long bufferOpenedAt;
    /** Monotonic flush sequence — keeps each flush's output filenames unique (no overwrite). */
    private long flushSeq;

    /** Consecutive failed flushes; reset by the first success. Drives the retain-and-retry above. */
    private int flushFailures;

    /**
     * Hard ceiling on retained-but-undrained events. Past this the buffer is dropped rather than
     * grown without bound — the trade is bounded memory over a complete audit trail, taken only
     * after the flush has failed repeatedly and loudly.
     */
    private static final int MAX_RETAINED = 50_000;
    private boolean closed;
    /** The open journal (append); null when it could not be opened — the store then degrades to buffer-only. */
    private FileChannel journal;

    /** Open a store under {@code dir} with default flush/roll/tail settings. */
    public static ParquetEventStore open(Path dir) {
        return new ParquetEventStore(dir, DEFAULT_FLUSH_THRESHOLD, DEFAULT_ROLL_MILLIS,
                InMemoryEventStore.DEFAULT_CAPACITY);
    }

    public ParquetEventStore(Path dir, int flushThreshold, long rollMillis, int tailCapacity) {
        this.root = dir.toAbsolutePath();
        this.flushThreshold = Math.max(1, flushThreshold);
        this.rollMillis = Math.max(0, rollMillis);
        this.tail = new InMemoryEventStore(tailCapacity);
        try {
            Files.createDirectories(root);
            DuckDbUtil.loadDriver();
            this.conn = DuckDbUtil.openInMemory(null);   // in-memory scratch + reader; no Space context: capped, spills under java.io.tmpdir
            try (Statement st = conn.createStatement()) {
                st.execute("CREATE TABLE " + BUF_TABLE + " ("
                        + "event_id VARCHAR, ts_ms BIGINT, type VARCHAR, source VARCHAR, "
                        + "pipeline VARCHAR, correlation_id VARCHAR, message VARCHAR, attributes VARCHAR, "
                        + "payload VARCHAR, "
                        + "level VARCHAR, year VARCHAR, month VARCHAR, day VARCHAR)");
            }
        } catch (Exception e) {
            throw new IllegalStateException("Could not initialise Parquet event store at " + root, e);
        }
        replayJournal();
        try {
            journal = FileChannel.open(root.resolve(JOURNAL), StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                    StandardOpenOption.APPEND);
        } catch (IOException e) {
            log.warn("Event journal {} could not be opened; buffered events will not survive a crash: {}",
                    root.resolve(JOURNAL), e.getMessage());
        }
    }

    /** Replay a journal a crashed predecessor left behind: its events go to Parquet now, then it is removed. */
    private void replayJournal() {
        Path file = root.resolve(JOURNAL);
        if (!Files.exists(file)) return;
        try {
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            int torn = 0;
            List<Event> replay = new ArrayList<>();
            for (String line : lines) {
                if (line.isBlank()) continue;
                try {
                    Map<?, ?> m = JSON.readValue(line, Map.class);
                    Event e = new Event(str(m.get("eventId")), ((Number) m.get("ts")).longValue(),
                            EventLevel.parse(str(m.get("level"))), str(m.get("type")), str(m.get("source")),
                            str(m.get("pipeline")), str(m.get("correlationId")), str(m.get("message")),
                            JsonAttributes.fromJson(str(m.get("attributes"))),
                            JsonAttributes.fromPayloadJson(str(m.get("payload"))));
                    replay.add(e);
                } catch (Exception bad) {
                    torn++;   // a kill mid-write tears the last line; everything before it is whole
                }
            }
            if (torn > 0) log.warn("Event journal {}: skipped {} unreadable line(s)", file, torn);
            // A journal whose events DID reach Parquet (a flush landed, then its truncate failed or the kill came
            // between the two) must not write them twice: a duplicated audit row is a forked chain
            // (ASSURE-AUDIT-CHAIN-1). Skip every event whose id is already on disk.
            java.util.Set<String> flushed = flushedIds(replay);
            for (Event e : replay) {
                if (flushed.contains(e.eventId())) continue;
                tail.append(e);
                buffer.addLast(e);
            }
            if (!flushed.isEmpty())
                log.info("Event journal {}: {} event(s) were already flushed; not written again", file, flushed.size());
            if (!buffer.isEmpty()) {
                log.info("Event journal {}: replaying {} event(s) a previous run did not flush", file, buffer.size());
                flushLocked();
            }
            if (buffer.isEmpty()) Files.deleteIfExists(file);   // kept if the replay flush failed: retried next open
        } catch (IOException e) {
            log.warn("Event journal {} could not be replayed: {}", file, e.getMessage());
        }
    }

    /** The ids among {@code events} already present in the Parquet files. Throws when they cannot be read, so a
     *  replay is retried on the next open rather than risking a duplicate. */
    private java.util.Set<String> flushedIds(List<Event> events) throws IOException {
        java.util.Set<String> found = new java.util.HashSet<>();
        if (events.isEmpty() || !hasParquet()) return found;
        String reader = SqlViews.reader("PARQUET", root + "/**/*.parquet", true);
        for (int from = 0; from < events.size(); from += 500) {
            List<Event> chunk = events.subList(from, Math.min(events.size(), from + 500));
            String sql = "SELECT DISTINCT event_id FROM " + reader + " WHERE event_id IN ("
                    + String.join(",", java.util.Collections.nCopies(chunk.size(), "?")) + ")";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                for (int i = 0; i < chunk.size(); i++) ps.setString(i + 1, chunk.get(i).eventId());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) found.add(rs.getString(1));
                }
            } catch (SQLException e) {
                throw new IOException("could not check the journal against the flushed events: " + e.getMessage(), e);
            }
        }
        return found;
    }

    private static String str(Object o) {
        return o == null ? null : o.toString();
    }

    /** One JSON line per event; forced to disk for the audit trail's own types. Never throws to the caller. */
    private void journalLocked(Event e) {
        if (journal == null) return;
        try {
            Map<String, Object> m = new java.util.LinkedHashMap<>();
            m.put("eventId", e.eventId());
            m.put("ts", e.ts());
            m.put("level", e.level().name());
            m.put("type", e.type());
            m.put("source", e.source());
            m.put("pipeline", e.pipeline());
            m.put("correlationId", e.correlationId());
            m.put("message", e.message());
            m.put("attributes", JSON.writeValueAsString(e.attributes()));
            m.put("payload", JSON.writeValueAsString(e.payload()));
            journal.write(ByteBuffer.wrap((JSON.writeValueAsString(m) + '\n').getBytes(StandardCharsets.UTF_8)));
            if (EventType.AUDIT.equals(e.type()) || EventType.ACCESS_DENIED.equals(e.type())) journal.force(false);
        } catch (IOException ex) {
            log.warn("Event journal write failed; this event survives only a clean shutdown: {}", ex.getMessage());
        }
    }

    /** The buffer is empty again (flushed or dropped): nothing is pending, so the journal is emptied. */
    private void truncateJournal() {
        if (journal == null) return;
        try {
            journal.truncate(0);
        } catch (IOException e) {
            log.warn("Event journal could not be truncated; a restart may replay flushed events: {}", e.getMessage());
        }
    }

    // ── append + flush ──────────────────────────────────────────────────────────

    @Override
    public synchronized void append(Event event) {
        if (event == null || closed) return;
        tail.append(event);
        if (buffer.isEmpty()) bufferOpenedAt = System.currentTimeMillis();
        buffer.addLast(event);
        journalLocked(event);
        boolean full = buffer.size() >= flushThreshold;
        boolean stale = rollMillis > 0 && System.currentTimeMillis() - bufferOpenedAt >= rollMillis;
        if (full || stale) flushLocked();
    }

    @Override
    public synchronized void flush() {
        flushLocked();
    }

    /** Write the buffer to a fresh partitioned Parquet file set, then clear it. Caller holds the lock. */
    private void flushLocked() {
        if (buffer.isEmpty()) return;
        try {
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO " + BUF_TABLE + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
                for (Event e : buffer) {
                    LocalDate d = Instant.ofEpochMilli(e.ts()).atZone(ZoneOffset.UTC).toLocalDate();
                    int i = 1;
                    ps.setString(i++, e.eventId());
                    ps.setLong(i++, e.ts());
                    ps.setString(i++, e.type());
                    ps.setString(i++, e.source());
                    ps.setString(i++, e.pipeline());
                    ps.setString(i++, e.correlationId());
                    ps.setString(i++, e.message());
                    ps.setString(i++, JSON.writeValueAsString(e.attributes()));
                    ps.setString(i++, JSON.writeValueAsString(e.payload()));
                    ps.setString(i++, e.level().name());
                    ps.setString(i++, String.format("%04d", d.getYear()));
                    ps.setString(i++, String.format("%02d", d.getMonthValue()));
                    ps.setString(i, String.format("%02d", d.getDayOfMonth()));
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            String baseName = "events_" + System.currentTimeMillis() + "_" + (flushSeq++);
            PartitionWriter.write(conn, BUF_TABLE, root.toString(), "PARQUET", "zstd", baseName,
                    List.of("level", "year", "month", "day"), List.of());
            flushFailures = 0;
        } catch (Exception e) {
            // Never propagate to the caller (often the log appender). But do NOT drop the batch on the
            // first failure: this store holds AUDIT events, and a transient fault (disk full, a locked
            // file) would otherwise destroy the only durable copy — the live-tail ring is a bounded
            // in-memory ring, not a durability guarantee. Retain and retry on the next append/flush,
            // and only give up once the buffer would grow without bound.
            flushFailures++;
            boolean giveUp = buffer.size() >= MAX_RETAINED;
            log.warn("Event flush to Parquet failed ({} consecutive), {} {} buffered event(s): {}",
                    flushFailures, giveUp ? "dropping" : "retaining", buffer.size(), e.getMessage());
            clearBufferTable();
            if (!giveUp) return;   // keep `buffer` for the next attempt
            log.error("Event buffer reached {} undrained events; dropping them to bound memory."
                    + " Durable event/audit history for this window is lost.", buffer.size());
            buffer.clear();
            truncateJournal();
            flushFailures = 0;
            return;
        }
        buffer.clear();
        clearBufferTable();
        truncateJournal();
    }

    /**
     * Drop the staging rows. Always safe to call: a retained buffer is re-inserted from scratch on the
     * next attempt, so leaving rows behind here would double-write every event that did land.
     */
    private void clearBufferTable() {
        try (Statement st = conn.createStatement()) {
            st.execute("DELETE FROM " + BUF_TABLE);
        } catch (SQLException ignore) { /* best effort */ }
    }

    // ── reads ─────────────────────────────────────────────────────────────────────

    @Override
    public synchronized List<Event> recent(int limit) {
        return tail.recent(limit);
    }

    @Override
    public synchronized List<Event> query(EventQuery q) {
        List<Event> merged = new ArrayList<>();
        // 1) unflushed buffer (newest facts) — filter in memory
        for (Event e : buffer) if (q.matches(e)) merged.add(e);
        // 2) on-disk Parquet — filter + page in SQL
        merged.addAll(queryParquet(q));
        merged.sort(Comparator.comparingLong(Event::ts).reversed());
        int from = Math.min(q.offset(), merged.size());
        int to = Math.min(from + q.limit(), merged.size());
        signalUnreadable();
        return new ArrayList<>(merged.subList(from, to));
    }

    private List<Event> queryParquet(EventQuery q) {
        List<String> conds = new ArrayList<>();
        List<Object> params = new ArrayList<>();
        if (q.fromMs() != null) { conds.add("ts_ms >= ?"); params.add(q.fromMs()); }
        if (q.toMs() != null) { conds.add("ts_ms <= ?"); params.add(q.toMs()); }
        if (q.minLevel() != null) conds.add("level IN (" + levelInList(q.minLevel()) + ")");
        if (q.type() != null) { conds.add("type = ?"); params.add(q.type()); }
        if (q.pipeline() != null) { conds.add("lower(pipeline) = lower(?)"); params.add(q.pipeline()); }
        if (q.correlationId() != null) { conds.add("correlation_id = ?"); params.add(q.correlationId()); }
        if (q.textContains() != null) {
            conds.add("(lower(message) LIKE ? OR lower(source) LIKE ?)");
            String like = "%" + q.textContains().toLowerCase(java.util.Locale.ROOT) + "%";
            params.add(like); params.add(like);
        }
        String where = conds.isEmpty() ? "" : " WHERE " + String.join(" AND ", conds);
        params.add(q.offset() + q.limit());      // fetch enough to page after merge with buffer (per file, when split)
        return readEvents(parquetFiles(), where + " ORDER BY ts_ms DESC LIMIT ?", params);
    }

    @Override
    public synchronized List<Event> page(int limit, Long afterTs, String afterId) {
        int n = Math.max(0, limit);
        List<Event> merged = new ArrayList<>();
        // 1) unflushed buffer (newest facts) — keyset-filter in memory
        for (Event e : buffer) if (EventStore.afterKey(e, afterTs, afterId)) merged.add(e);
        // 2) on-disk Parquet — keyset predicate + order in SQL
        merged.addAll(pageParquet(n, afterTs, afterId));
        merged.sort(KEYSET_ORDER);
        signalUnreadable();
        return new ArrayList<>(merged.subList(0, Math.min(n, merged.size())));
    }

    /** One SQL keyset page over the Parquet files: strictly older than {@code (afterTs, afterId)}, newest-first. */
    private List<Event> pageParquet(int limit, Long afterTs, String afterId) {
        String where = afterTs == null ? ""
                : " WHERE (ts_ms < ? OR (ts_ms = ? AND event_id < ?))";
        List<Object> params = new ArrayList<>();
        if (afterTs != null) {
            params.add(afterTs);
            params.add(afterTs);
            params.add(afterId == null ? "" : afterId);
        }
        params.add(limit);
        return readEvents(parquetFiles(), where + " ORDER BY ts_ms DESC, event_id DESC LIMIT ?", params);
    }

    @Override
    public synchronized long count() {
        long total = buffer.size();
        for (Long n : readFiles(parquetFiles(), "SELECT COUNT(*)", "", List.of(), rs -> rs.getLong(1))) total += n;
        signalUnreadable();
        return total;
    }

    // -- the audit chain (ASSURE-AUDIT-CHAIN-1) --

    /** The chain seq of a stored row, from its attributes JSON; NULL for an unchained row. */
    private static final String SEQ_SQL = "TRY_CAST(json_extract_string(attributes, '$." + AuditAttrs.AUDIT_SEQ
            + "') AS BIGINT)";
    private static final String CHAINED_SQL = "type IN ('" + EventType.AUDIT + "', '" + EventType.ACCESS_DENIED
            + "') AND " + SEQ_SQL + " IS NOT NULL";

    @Override
    public synchronized Event chainHead() {
        Event best = null;
        for (Event e : buffer)
            if (AuditChain.chained(e) && AuditChain.seq(e) > 0
                    && (best == null || AuditChain.seq(e) > AuditChain.seq(best))) best = e;
        // Only the file(s) holding the highest indexed seq can hold the head.
        for (Event d : chainRead(idx -> {
            long top = Long.MIN_VALUE;
            for (SeqRange r : idx.values()) if (r.chained() > 0) top = Math.max(top, r.maxSeq());
            List<Path> at = new ArrayList<>();
            for (var e : idx.entrySet()) if (e.getValue().chained() > 0 && e.getValue().maxSeq() == top) at.add(e.getKey());
            return at;
        }, "WHERE " + CHAINED_SQL + " ORDER BY " + SEQ_SQL + " DESC LIMIT 1", List.of()))
            if (best == null || AuditChain.seq(d) > AuditChain.seq(best)) best = d;
        signalUnreadable();
        return best;
    }

    @Override
    public synchronized List<Event> chainPage(long fromSeq, int limit) {
        int n = Math.max(0, limit);
        List<Event> merged = new ArrayList<>();
        for (Event e : buffer) if (AuditChain.chained(e) && AuditChain.seq(e) >= fromSeq) merged.add(e);
        if (n > 0)
            merged.addAll(chainRead(idx -> pageFiles(idx, fromSeq, n), "WHERE " + CHAINED_SQL + " AND " + SEQ_SQL
                    + " >= ? ORDER BY " + SEQ_SQL + ", event_id LIMIT ?", List.of(fromSeq, (long) n)));
        merged.sort(CHAIN_ORDER);
        signalUnreadable();
        return new ArrayList<>(merged.subList(0, Math.min(n, merged.size())));
    }

    /**
     * The files that can hold the {@code n} lowest chained rows with {@code seq >= fromSeq}. Files are taken in
     * ascending {@code minSeq}; a file lying wholly at or above {@code fromSeq} guarantees its {@code chained} rows
     * are in range, so once the guaranteed rows reach {@code n}, the answer's seqs are all {@code <= bound} (the
     * highest maxSeq among those files) and every file with {@code minSeq > bound} holds only later rows. Every
     * other file whose range reaches {@code fromSeq} is read — so two rows claiming one seq, in any two files,
     * are both read (both ranges contain it) and verify still sees the duplicate.
     */
    private static List<Path> pageFiles(Map<Path, SeqRange> idx, long fromSeq, int n) {
        List<Map.Entry<Path, SeqRange>> reach = new ArrayList<>();
        for (var e : idx.entrySet()) if (e.getValue().chained() > 0 && e.getValue().maxSeq() >= fromSeq) reach.add(e);
        reach.sort(Comparator.comparingLong(e -> e.getValue().minSeq()));
        long counted = 0;
        long bound = Long.MAX_VALUE;
        long high = Long.MIN_VALUE;
        for (var e : reach) {
            SeqRange r = e.getValue();
            if (r.minSeq() < fromSeq) continue;   // straddles fromSeq: read, but its in-range count is unknown
            counted += r.chained();
            high = Math.max(high, r.maxSeq());
            if (counted >= n) {
                bound = high;
                break;
            }
        }
        List<Path> out = new ArrayList<>();
        for (var e : reach) if (e.getValue().minSeq() <= bound) out.add(e.getKey());
        return out;
    }

    @Override
    public synchronized long unlinkedSince(long fromTs) {
        long n = 0;
        for (Event e : buffer)
            if (AuditChain.TYPES.contains(e.type()) && e.ts() >= fromTs && AuditChain.unlinked(e)) n++;
        n += chainRead(idx -> {
            List<Path> with = new ArrayList<>();
            for (var e : idx.entrySet())
                if (e.getValue().unlinked() > 0 && e.getValue().maxTs() >= fromTs) with.add(e.getKey());
            return with;
        }, "WHERE " + UNLINKED_SQL + " AND ts_ms >= ?", List.of(fromTs)).size();
        signalUnreadable();
        return n;
    }

    @Override
    public synchronized java.util.Set<String> presentIds(java.util.Collection<String> ids) {
        java.util.Set<String> found = new java.util.HashSet<>();
        for (Event e : buffer) if (ids.contains(e.eventId())) found.add(e.eventId());
        List<Event> probe = new ArrayList<>();
        for (String id : ids) probe.add(new Event(id, 0, null, null, null, null, null, null, null, null));
        try {
            found.addAll(flushedIds(probe));
        } catch (IOException e) {
            throw new IllegalStateException(e.getMessage(), e);
        }
        return found;
    }

    /** The Parquet files this store can currently not read, relative to its root — every read (search, page,
     *  count, chain) keeps it current: a file joins when a read of it fails and leaves when one succeeds or it
     *  is gone. A verify must not pass over them silently. */
    @Override
    public synchronized List<String> unreadableUnits() {
        List<String> out = new ArrayList<>();
        for (Path f : unreadable) out.add(rel(f));
        return out;
    }

    // -- the per-file seq index (ASSURE-AUDIT-CHAIN-RESIDUALS-1 (3)) --

    /** An unlinked audit-type row: no seq, or marked {@link AuditAttrs#AUDIT_UNLINKED}. */
    private static final String UNLINKED_SQL = "type IN ('" + EventType.AUDIT + "', '" + EventType.ACCESS_DENIED
            + "') AND (" + SEQ_SQL + " IS NULL OR json_extract_string(attributes, '$." + AuditAttrs.AUDIT_UNLINKED
            + "') = 'true')";

    /**
     * What one Parquet file holds of the chain — its chained-row count and seq range, its unlinked audit rows and
     * newest timestamp — valid while the file keeps this size and modification time.
     */
    private record SeqRange(long size, long mtime, long chained, long minSeq, long maxSeq, long unlinked, long maxTs) {}

    /**
     * The per-file index the chain reads choose their files by. The seq lives in the attributes JSON, so without
     * it every chain page ({@code /audit/verify} reads a thousand rows per page) scanned every file of the store.
     * It is computed FROM the files themselves — at the first chain read that meets a file, in one grouped query
     * over every file not yet indexed, the unreadable ones split off per file — and held in memory only: nothing on
     * disk can make it lie, a restart rebuilds it with one scan (what the head lookup cost anyway), and a file that
     * was written before this index existed is indexed exactly like a new one. A file whose size or modification
     * time changed is indexed again; a file that is gone is dropped.
     */
    private final Map<Path, SeqRange> seqIndex = new java.util.HashMap<>();

    /** The index over every readable Parquet file (refreshed first). Throws when files exist but none is readable. */
    private Map<Path, SeqRange> refreshIndex() {
        List<Path> files = parquetFiles();
        seqIndex.keySet().retainAll(new java.util.HashSet<>(files));
        Map<String, Path> byName = new java.util.LinkedHashMap<>();
        Map<Path, long[]> stamps = new java.util.HashMap<>();
        for (Path f : files) {
            long size;
            long mtime;
            try {
                size = Files.size(f);
                mtime = Files.getLastModifiedTime(f).toMillis();
            } catch (IOException gone) {
                seqIndex.remove(f);
                continue;   // removed between the listing and now
            }
            SeqRange r = seqIndex.get(f);
            if (r != null && r.size() == size && r.mtime() == mtime) continue;
            seqIndex.remove(f);
            byName.put(f.toString().replace('\\', '/'), f);
            stamps.put(f, new long[]{size, mtime});
        }
        if (!byName.isEmpty()) {
            List<Path> stale = new ArrayList<>(byName.values());
            List<Object[]> rows = readFiles(stale, "SELECT filename, COUNT(*) FILTER (WHERE " + CHAINED_SQL + "), MIN("
                    + SEQ_SQL + ") FILTER (WHERE " + CHAINED_SQL + "), MAX(" + SEQ_SQL + ") FILTER (WHERE " + CHAINED_SQL
                    + "), COUNT(*) FILTER (WHERE " + UNLINKED_SQL + "), MAX(ts_ms)", "GROUP BY filename", List.of(),
                    rs -> new Object[]{rs.getString(1), rs.getLong(2), rs.getLong(3), rs.getLong(4), rs.getLong(5),
                            rs.getLong(6)});
            for (Path f : stale)   // a readable file with no rows at all has no group: it holds nothing
                if (!unreadable.contains(f)) seqIndex.put(f, new SeqRange(stamps.get(f)[0], stamps.get(f)[1], 0, 0, 0, 0,
                        Long.MIN_VALUE));
            for (Object[] r : rows) {
                Path f = byName.get(String.valueOf(r[0]).replace('\\', '/'));
                if (f == null || unreadable.contains(f)) continue;
                seqIndex.put(f, new SeqRange(stamps.get(f)[0], stamps.get(f)[1], (long) r[1], (long) r[2], (long) r[3],
                        (long) r[4], (long) r[5]));
            }
        }
        if (!files.isEmpty() && unreadable.containsAll(files))
            throw new IllegalStateException("audit chain read failed under " + root + ": no file readable");
        return seqIndex;
    }

    /**
     * A chain read over only the files {@code choose} picks from the index ({@link #readEvents}: an unreadable file
     * is skipped and reported, so one corrupt file planted in the directory cannot blind the whole chain and,
     * through an unreadable head, unlink every later audit row). A chosen file that fails to read leaves the index
     * and the choice is made again over the rest, so a file that went bad after it was indexed never silently
     * narrows the answer. The rows are the UNION of each file's answer to {@code tail}: callers re-sort and
     * re-limit. Throws only when no file at all could be read, never answering "empty" for "unreadable".
     */
    /** Test seam: how many files the last chain read chose. */
    int lastChainFiles;

    private List<Event> chainRead(java.util.function.Function<Map<Path, SeqRange>, List<Path>> choose, String tail,
                                  List<Object> params) {
        while (true) {
            List<Path> files = choose.apply(refreshIndex());
            lastChainFiles = files.size();
            List<Event> out = readEvents(files, tail, params);
            boolean lost = false;
            for (Path f : files) if (unreadable.contains(f) && seqIndex.remove(f) != null) lost = true;
            if (!lost) return out;
        }
    }

    // -- reads that survive one unreadable file (ASSURE-AUDIT-CHAIN-RESIDUALS-1 (4)) --

    /** The signal type of the warning a newly unreadable Parquet file raises. */
    public static final String UNREADABLE_SIGNAL = "events.file_unreadable";

    /** Files a read failed on, in path order; see {@link #unreadableUnits}. */
    private final java.util.TreeSet<Path> unreadable = new java.util.TreeSet<>();
    /** Files that joined {@link #unreadable} during the current read — each raises one warning Signal. */
    private final List<Path> newlyUnreadable = new ArrayList<>();

    /** One mapped result row. */
    @FunctionalInterface
    private interface Row<T> {
        T map(ResultSet rs) throws SQLException;
    }

    private static final String EVENT_COLUMNS =
            "SELECT event_id, ts_ms, level, type, source, pipeline, correlation_id, message, attributes, payload";

    private List<Event> readEvents(List<Path> files, String tail, List<Object> params) {
        return readFiles(files, EVENT_COLUMNS, tail, params, rs -> new Event(rs.getString("event_id"),
                rs.getLong("ts_ms"), EventLevel.parse(rs.getString("level")), rs.getString("type"),
                rs.getString("source"), rs.getString("pipeline"), rs.getString("correlation_id"),
                rs.getString("message"), JsonAttributes.fromJson(rs.getString("attributes")),
                JsonAttributes.fromPayloadJson(rs.getString("payload"))));
    }

    /**
     * {@code select FROM <files> tail} over the given files. The files not known to be unreadable go in ONE query;
     * when that fails, each is read on its own. A known-unreadable file is retried on its own every time (it may
     * have been repaired). A file that fails is recorded ({@link #unreadableUnits}, an ERROR log, one warning
     * Signal) and SKIPPED — the rest still answer, where one corrupt file used to fail the whole search into an
     * empty result that looked complete. With a per-file split, the rows are the UNION of each file's answer:
     * callers re-sort and re-limit (every caller does).
     */
    private <T> List<T> readFiles(List<Path> files, String select, String tail, List<Object> params, Row<T> row) {
        List<T> out = new ArrayList<>();
        List<Path> good = new ArrayList<>();
        List<Path> retry = new ArrayList<>();
        for (Path f : files) (unreadable.contains(f) ? retry : good).add(f);
        if (!good.isEmpty()) {
            try {
                readInto(out, reader(good), select, tail, params, row);
            } catch (SQLException together) {
                out.clear();
                retry.addAll(0, good);
            }
        }
        for (Path f : retry) {
            List<T> one = new ArrayList<>();
            try {
                readInto(one, reader(List.of(f)), select, tail, params, row);
                unreadable.remove(f);
                out.addAll(one);
            } catch (SQLException bad) {
                if (unreadable.add(f)) {
                    newlyUnreadable.add(f);
                    log.error("Event store file {} could not be read; reads skip it until it is readable again: {}",
                            f, bad.getMessage());
                }
            }
        }
        return out;
    }

    private static String reader(List<Path> files) {
        List<String> names = new ArrayList<>();
        for (Path f : files) names.add(f.toString());
        return SqlViews.reader("PARQUET", names, true);
    }

    private <T> void readInto(List<T> out, String reader, String select, String tail, List<Object> params, Row<T> row)
            throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(select + " FROM " + reader + " " + tail)) {
            int i = 1;
            for (Object p : params) {
                if (p instanceof Long l) ps.setLong(i++, l);
                else if (p instanceof Integer n) ps.setInt(i++, n);
                else ps.setString(i++, String.valueOf(p));
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(row.map(rs));
            }
        }
    }

    /** Every Parquet file under the root, in path order; forgets the unreadable ones that are gone. */
    private List<Path> parquetFiles() {
        if (!Files.isDirectory(root)) return List.of();
        try (Stream<Path> w = Files.walk(root)) {
            List<Path> files = w.filter(p -> p.getFileName().toString().endsWith(".parquet")).sorted().toList();
            unreadable.retainAll(new java.util.HashSet<>(files));
            return files;
        } catch (IOException e) {
            throw new IllegalStateException("event store listing failed under " + root + ": " + e.getMessage(), e);
        }
    }

    private String rel(Path f) {
        return root.relativize(f).toString().replace('\\', '/');
    }

    /**
     * One WARN {@link EventType#SIGNAL} per file that became unreadable during this read — so a search that
     * answered without it does not pass for a complete one on any surface that watches Signals. Appended after
     * the read has merged its rows, so it never shows up in the answer it describes.
     */
    private void signalUnreadable() {
        if (newlyUnreadable.isEmpty()) return;
        List<Path> now = List.copyOf(newlyUnreadable);
        newlyUnreadable.clear();
        for (Path f : now)
            append(Event.builder(EventType.SIGNAL).level(EventLevel.WARN).source("event-store")
                    .message("Event store file " + rel(f) + " could not be read; every read skips it until it is"
                            + " readable again, so results exclude its rows")
                    .attr("signalType", UNREADABLE_SIGNAL).attr("severity", "WARN").attr("file", rel(f)).build());
    }

    // -- the single chain writer per directory --

    /** The lock file that makes one open store the ONLY chain writer for its directory. */
    static final String WRITER_LOCK = ".chain-writer.lock";
    private FileChannel lockChannel;
    private java.nio.channels.FileLock writerLock;
    /** The random token written into the lock file when it was locked — what "the same file" is checked by. */
    private String writerToken = "";
    /** Set once the lock file was found removed or replaced: this store never links again. */
    private boolean writerLost;

    /**
     * Claim this directory's chain-writer lock (an OS file lock on {@value #WRITER_LOCK}). Two stores — two
     * EventLogs, two processes — linking onto one directory would each recover the same head and fork the chain,
     * so the second is REFUSED: this throws, and the EventLog stores that row marked unlinked, loudly. A reader
     * that never links never takes the lock. A hard kill releases it with the process.
     */
    @Override
    public synchronized void claimChainWriter() {
        if (writerLost)
            throw new IllegalStateException("this store lost the audit chain writer lock on " + root + "; it links nothing more");
        Path lockPath = root.resolve(WRITER_LOCK);
        if (writerLock != null) {
            // The lock guards a FILE, not a path: the file can be deleted while locked (Windows and POSIX both
            // allow it) and a second writer then locks a NEW file at the same path. So before every link, the
            // path must still name the file this store locked — its token re-read through a fresh open.
            String now;
            try {
                now = Files.readString(lockPath, StandardCharsets.UTF_8);
            } catch (IOException gone) {
                now = null;
            }
            if (!writerToken.equals(now)) {
                writerLost = true;
                releaseWriter();
                log.error("Audit chain writer lock {} was removed or replaced while held; this store stops linking "
                        + "(its audit rows are stored marked unlinked)", lockPath);
                throw new IllegalStateException("the audit chain writer lock on " + root + " was removed or replaced");
            }
            return;
        }
        try {
            if (lockChannel == null)
                lockChannel = FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            // Lock a byte FAR past the token, so the token itself stays readable through other handles (Windows
            // locks are mandatory over their range).
            java.nio.channels.FileLock l = lockChannel.tryLock(1L << 40, 1, false);
            if (l == null) throw new IllegalStateException("another process holds the audit chain writer lock on " + root);
            writerLock = l;
            writerToken = java.util.UUID.randomUUID().toString();
            lockChannel.truncate(0);
            lockChannel.write(ByteBuffer.wrap(writerToken.getBytes(StandardCharsets.UTF_8)), 0);
            lockChannel.force(true);
        } catch (java.nio.channels.OverlappingFileLockException same) {
            throw new IllegalStateException("another event store in this process is the audit chain writer for " + root);
        } catch (IOException e) {
            throw new IllegalStateException("could not claim the audit chain writer lock on " + root + ": " + e.getMessage(), e);
        }
    }

    private void releaseWriter() {
        try {
            if (writerLock != null) writerLock.release();
            if (lockChannel != null) lockChannel.close();
        } catch (IOException ignore) {
            // the process exit releases it anyway
        }
        writerLock = null;
        lockChannel = null;
    }

    /** Test seam: behave as a hard kill would — nothing flushed, the journal left as it is, the OS lock released
     *  (a killed process holds no lock). The store is unusable afterwards. */
    synchronized void simulateCrash() {
        closed = true;
        if (journal != null) {
            try { journal.close(); } catch (IOException ignore) { /* as a kill */ }
        }
        releaseWriter();
    }

    /** Comma-separated quoted level names at or above {@code min} — values are enum names, so safe to inline. */
    private static String levelInList(EventLevel min) {
        List<String> names = new ArrayList<>();
        for (EventLevel l : EventLevel.values()) if (l.atLeast(min)) names.add("'" + l.name() + "'");
        return String.join(", ", names);
    }

    /** {@code true} when at least one Parquet file exists under the root (else read_parquet would error). */
    private boolean hasParquet() {
        if (!Files.isDirectory(root)) return false;
        try (Stream<Path> w = Files.walk(root)) {
            return w.anyMatch(p -> p.getFileName().toString().endsWith(".parquet"));
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Delete every {@code level=X/year=YYYY/month=MM/day=DD} partition whose day is before {@code before}
     * (UTC — the same clock {@link #flushLocked()} partitions by). The buffer is flushed first so an aged
     * event still in memory is judged on disk like every other, and a partition directory is only ever
     * removed whole — the reader's {@code read_parquet} glob simply stops seeing it. Empty {@code month=}
     * / {@code year=} parents left behind are removed too, so a long-lived store does not accrete
     * hundreds of empty directories.
     */
    @Override
    public synchronized int prune(LocalDate before, boolean dryRun) {
        flushLocked();
        if (!Files.isDirectory(root)) return 0;
        int removed = 0;
        try (Stream<Path> days = Files.walk(root, 4)) {
            for (Path day : days.filter(ParquetEventStore::isDayPartition).toList()) {
                LocalDate d = partitionDate(day);
                if (d == null || !d.isBefore(before)) continue;
                removed++;
                if (dryRun) continue;
                deleteTree(day);
                removeIfEmpty(day.getParent());               // month=
                removeIfEmpty(day.getParent().getParent());   // year=
                removeIfEmpty(day.getParent().getParent().getParent());   // level=
            }
        } catch (IOException e) {
            throw new IllegalStateException("event prune failed under " + root + ": " + e.getMessage(), e);
        }
        return removed;
    }

    /** {@code <root>/level=X/year=YYYY/month=MM/day=DD} — depth 4 under the root with the Hive prefixes. */
    private static boolean isDayPartition(Path p) {
        return Files.isDirectory(p) && p.getFileName().toString().startsWith("day=")
                && p.getParent() != null && p.getParent().getFileName().toString().startsWith("month=")
                && p.getParent().getParent() != null
                && p.getParent().getParent().getFileName().toString().startsWith("year=");
    }

    /** The partition's UTC day, or {@code null} for a directory that only looks like one. */
    private static LocalDate partitionDate(Path day) {
        try {
            int d = Integer.parseInt(day.getFileName().toString().substring("day=".length()));
            int m = Integer.parseInt(day.getParent().getFileName().toString().substring("month=".length()));
            int y = Integer.parseInt(day.getParent().getParent().getFileName().toString().substring("year=".length()));
            return LocalDate.of(y, m, d);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static void deleteTree(Path dir) throws IOException {
        try (Stream<Path> w = Files.walk(dir)) {
            for (Path p : w.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p);
        }
    }

    private static void removeIfEmpty(Path dir) throws IOException {
        if (dir == null || !Files.isDirectory(dir)) return;
        try (Stream<Path> s = Files.list(dir)) {
            if (s.findAny().isEmpty()) Files.deleteIfExists(dir);
        }
    }

    @Override
    public synchronized void close() {
        if (closed) return;
        flushLocked();
        closed = true;
        if (journal != null) {
            try { journal.close(); } catch (IOException e) { log.warn("Error closing event journal: {}", e.getMessage()); }
        }
        releaseWriter();
        try { conn.close(); } catch (SQLException e) { log.warn("Error closing event store: {}", e.getMessage()); }
    }
}
