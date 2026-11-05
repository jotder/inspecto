package com.gamma.control;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.etl.EditionFeatures;
import com.gamma.event.EventLog;
import com.gamma.onboard.ImportFollowOn;
import com.gamma.pipeline.ComponentRegistry;
import com.gamma.pipeline.ComponentStore;
import com.gamma.service.CollectorService;
import com.gamma.service.SpaceContext;
import com.gamma.spi.auth.ApiException;
import com.gamma.spi.auth.ErrorCodes;
import com.gamma.spi.http.ApiContext;
import com.gamma.util.AtomicFiles;
import com.gamma.util.DuckDbUtil;
import com.gamma.util.SqlIdent;
import com.sun.net.httpserver.HttpExchange;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * <b>Storage hygiene of an import Dataset</b> ({@code LA-DATASET-COMPACT-1}, plan {@code la-data-arrival-plan.md} P4,
 * D-5, D-7…D-10). Every arrival lands as its own Parquet file under {@code <dataRoot>/<id>/database}; after weeks of daily files
 * reads slow down.
 * <ul>
 *   <li><b>Compaction</b> (every edition, D-5, D-7): once the Dataset holds more than {@link #THRESHOLD} files, the import's
 *       own follow-on work (inside the person's run, before the index build) merges them into ONE file, ordered by arrival;
 *       {@code POST /datasets/{id}/compact} (<i>Compact now</i>) does it at any count.</li>
 *   <li><b>The merge map</b> (D-8) {@code <dataRoot>/<id>/compaction.json}: the merged file and, per arrival it holds, the
 *       arrival's own output name, when it was received, its row segment in the merged file and the period it covers - so
 *       every arrival keeps its period and stays <i>kept</i> ({@link #merged}). Trusted only while its merged file exists:
 *       a <i>Replace everything</i> sets the whole folder aside, and the map with it falls silent.</li>
 *   <li><b>Retention</b> (Standard and up, D-9): the Dataset entry's {@code retention {days | arrivals}}, applied at
 *       compaction time: <i>keep N days</i> by the event date (the When column), else by when the file was received;
 *       <i>keep N arrivals</i> drops the oldest arrivals' rows. Set with {@code PUT /datasets/{id}/storage}.</li>
 * </ul>
 * The swap is crash-safe the way a pending map makes it: the merged file is written aside, a {@code .pending} map names it
 * and its inputs, the merged file moves in, the inputs go, the map is committed; {@link #heal} finishes or drops a pending
 * map. Between the move and the deletes a reader may count both briefly. The link index's next build is a full one (D-10:
 * its inputs were rewritten - the index's own advice).
 * <p>{@code LA-DATASET-COMPACT-2}: a commit of the import Pipeline outside Add data (a feed's scheduled run) compacts too,
 * before the Space's Jobs see it ({@link #afterCommit}); and every compaction tells the installed applications that the
 * files were rewritten ({@link ImportFollowOn#filesRewritten}), so the link index stops answering for removed rows at once.
 */
final class DatasetCompaction {

    private DatasetCompaction() {}

    /** More files than this, and the import compacts on its own (D-7). */
    static final int THRESHOLD = 20;
    static final String MAP = "compaction.json";
    static final String RETENTION = "retention";
    /** Arrivals that retention removed, remembered at most this many (newest kept). */
    static final int REMOVED_KEPT = 500;
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmssSSS").withZone(ZoneOffset.UTC);

    static void register(ApiContext api) {
        // ⚠ String LITERALS on purpose (CapabilityManifestTest's scanner).
        api.get("/datasets/([^/]+)/storage", ApiContext.withCapability("canUploadData", (e, m) -> storage(api, e, ApiContext.name(m))));
        api.put("/datasets/([^/]+)/storage", ApiContext.withCapability("canAuthorWorkbench",
                (e, m) -> setRetention(api, e, ApiContext.name(m), api.body(e))));
        api.post("/datasets/([^/]+)/compact", ApiContext.withCapability("canUploadData", (e, m) -> compactNow(api, e, ApiContext.name(m))));
        ATTACHED.add(api);
        api.onClose(() -> ATTACHED.remove(api));
        CollectorService.commitStep(DatasetCompaction::afterCommit);
    }

    // ── a run outside Add data (LA-DATASET-COMPACT-2) ──────────────────────────────────────────────────────────

    private static final Logger log = LoggerFactory.getLogger(DatasetCompaction.class);
    /** The control planes whose Spaces' commits are looked at. */
    private static final java.util.Set<ApiContext> ATTACHED = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /**
     * A commit of an import Pipeline that did not pass through Add data - the scheduler's run of a watched folder or SFTP
     * feed (Standard) - compacts the way the import's own follow-on work does (D-7, retention in force included), on the
     * publishing thread, so the Space's Jobs (the link index build) are handed the commit only after it. Skipped while the
     * Dataset is claimed in {@link OnboardImport#IN_FLIGHT}: an import, <i>Compact now</i> or a delete holds it, and an
     * import compacts in its own follow-on. Never throws.
     */
    static void afterCommit(CollectorService service, com.gamma.etl.ConsignmentEvent event) {
        if (!"SUCCESS".equals(event.status()) || event.dryRun()) return;
        String id = event.pipeline();   // an import's Pipeline is named as its Dataset (lower case, as the event says it)
        for (ApiContext api : ATTACHED)
            for (SpaceContext ctx : HostContext.of(api).spaces().all())
                if (ctx.service() == service) afterCommit(api, ctx.id().value(), service, id);
    }

    private static void afterCommit(ApiContext api, String space, CollectorService service, String id) {
        String prev = MDC.get(EventLog.SPACE_MDC_KEY);
        MDC.put(EventLog.SPACE_MDC_KEY, space);   // the Space's roots, as a request of that Space reads them
        String key = null;
        try {
            Path writeRoot = api.writeRoot(), dataRoot = api.dataRoot();
            if (writeRoot == null || dataRoot == null) return;
            Map<String, Object> content = new ComponentStore(writeRoot.resolve("registry")).get("dataset", id)
                    .map(ComponentRegistry.Component::content).orElse(null);
            if (content == null || !OnboardImport.isOurs(writeRoot, id, content, service)) return;
            String claim = writeRoot + "|" + id;
            if (!OnboardImport.IN_FLIGHT.add(claim)) return;
            key = claim;
            Map<String, Object> o = afterImport(writeRoot, dataRoot, id, content, "scheduler");
            if (!"NOT_NEEDED".equals(o.get("result"))) log.info("Dataset '{}' after its run: {}", id, o.get("message"));
        } catch (RuntimeException e) {
            log.warn("Dataset '{}' could not be compacted after its run: {}", id, e.toString());
        } finally {
            if (key != null) OnboardImport.IN_FLIGHT.remove(key);
            if (prev == null) MDC.remove(EventLog.SPACE_MDC_KEY);
            else MDC.put(EventLog.SPACE_MDC_KEY, prev);
        }
    }

    // ── the merge map ──────────────────────────────────────────────────────────────────────────────────────────

    /**
     * One arrival inside the merged file: its own output name, when received, its rows and the period it covers. A segment
     * with {@code rows < 0} is an arrival whose rows are in the merged file at no known place (its file was rewritten since,
     * e.g. by <i>Replace the period</i>): kept, period unknown. {@code holds} names the arrivals such a block carries.
     */
    record Segment(String file, String received, long firstRow, long rows, String coversFrom, String coversTo, List<String> holds) {
        Segment {
            holds = holds == null ? List.of() : List.copyOf(holds);
        }
    }

    static Path dir(Path dataRoot, String id) {
        return dataRoot.resolve(id);
    }

    static Path database(Path dataRoot, String id) {
        return dir(dataRoot, id).resolve("database");
    }

    /**
     * The arrivals the current merged file holds, by their own output file name; empty when there is no map, it cannot be
     * read, or its merged file is gone (a Replace everything set it aside). For {@code DatasetArrivals}: an arrival whose
     * own file is gone but is named here is still kept, with this period.
     */
    static Map<String, Segment> merged(Path dataRoot, String id) {
        Map<String, Object> map = readMap(dir(dataRoot, id).resolve(MAP));
        Path file = mergedFile(map, database(dataRoot, id));
        if (file == null) return Map.of();
        boolean exact = unchanged(map, file);
        Map<String, Segment> out = new LinkedHashMap<>();
        for (Segment s : segments(map)) {
            // rewritten since (Replace the period removed some of its days): every arrival is still in it, at no known row
            out.put(s.file(), exact ? s : unknown(s.file(), s.received()));
            for (String h : s.holds()) out.putIfAbsent(h, unknown(h, s.received()));
        }
        return out;
    }

    private static Segment unknown(String file, String received) {
        return new Segment(file, received, -1, -1, null, null, List.of());
    }

    /** The map's merged file when it is there, else null (a Replace everything set it aside with the folder). */
    private static Path mergedFile(Map<String, Object> map, Path db) {
        if (map == null || !(map.get("merged") instanceof String merged)) return null;
        Path f = db.resolve(merged).normalize();
        return f.startsWith(db.normalize()) && Files.isRegularFile(f, LinkOption.NOFOLLOW_LINKS) ? f : null;
    }

    /** Whether the merged file is the one the map describes (size and time): its row segments hold only then. */
    private static boolean unchanged(Map<String, Object> map, Path file) {
        try {
            return map.get("mergedBytes") instanceof Number b && b.longValue() == Files.size(file)
                    && map.get("mergedModified") instanceof Number t && t.longValue() == Files.getLastModifiedTime(file).toMillis();
        } catch (IOException gone) {
            return false;
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Segment> segments(Map<String, Object> map) {
        List<Segment> out = new ArrayList<>();
        if (map.get("arrivals") instanceof List<?> list)
            for (Object o : list)
                if (o instanceof Map<?, ?> m)
                    out.add(new Segment(str(m.get("file")), str(m.get("received")), num(m.get("firstRow")), num(m.get("rows")),
                            str(m.get("coversFrom")), str(m.get("coversTo")),
                            m.get("holds") instanceof List<?> h ? h.stream().map(String::valueOf).toList() : List.of()));
        return out;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> readMap(Path file) {
        if (!Files.isRegularFile(file)) return null;
        try {
            return JSON.readValue(file.toFile(), Map.class);
        } catch (IOException unreadable) {
            return null;
        }
    }

    // ── compaction ─────────────────────────────────────────────────────────────────────────────────────────────

    /** What a compaction did: {@code COMPACTED}, {@code NOT_NEEDED} or {@code FAILED}, in words. */
    static Map<String, Object> outcome(String result, int filesBefore, int filesAfter, long rowsRemoved, String message) {
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("result", result);
        o.put("filesBefore", filesBefore);
        o.put("filesAfter", filesAfter);
        o.put("rowsRemoved", rowsRemoved);
        o.put("message", message);
        return o;
    }

    /**
     * The import's own follow-on step (D-7): compact once more than {@link #THRESHOLD} files lie there. Never throws: a
     * compaction that fails leaves the files as they were and says so (the import itself succeeded).
     */
    static Map<String, Object> afterImport(Path writeRoot, Path dataRoot, String id, Map<String, Object> content, String actor) {
        try {
            return compact(writeRoot, dataRoot, id, content, false, actor);
        } catch (Exception failed) {
            return outcome("FAILED", -1, -1, 0, "the files were not compacted: " + failed.getMessage());
        }
    }

    /**
     * {@link #merge}, then - unless nothing was merged - each installed application's {@link ImportFollowOn#filesRewritten}
     * (LA-DATASET-COMPACT-2): Link Analysis forgets what it remembered of the old files, so a graph read right after a
     * retention compaction no longer serves the removed rows from the link index. A failed merge is told too: it may have
     * moved files before it failed.
     */
    static Map<String, Object> compact(Path writeRoot, Path dataRoot, String id, Map<String, Object> content, boolean force,
                                       String actor) throws Exception {
        Map<String, Object> o = null;
        try {
            o = merge(dataRoot, id, content, force);
            return o;
        } finally {
            if (o == null || !"NOT_NEEDED".equals(o.get("result"))) {
                ImportFollowOn.Context ctx = new ImportFollowOn.Context(writeRoot, dataRoot, id, content, actor);
                for (ImportFollowOn f : OnboardImport.followOns())
                    try {
                        f.filesRewritten(ctx);
                    } catch (RuntimeException e) {
                        log.warn("'{}' could not be told that Dataset '{}' was compacted: {}", f.key(), id, e.toString());
                    }
            }
        }
    }

    /**
     * Merge every Parquet file of the Dataset into one, ordered by arrival, applying the retention policy in force. Below
     * the threshold (and not {@code force}) nothing happens. The caller holds the import's claim.
     */
    private static Map<String, Object> merge(Path dataRoot, String id, Map<String, Object> content, boolean force) throws Exception {
        Path db = database(dataRoot, id).toAbsolutePath().normalize();
        heal(dataRoot, id);
        List<Path> files = parquetFiles(db);
        // a file a reader still holds was merged already: merging again would put its rows in twice, for good
        if (Files.exists(dir(dataRoot, id).resolve(MAP + ".pending")))
            return outcome("NOT_NEEDED", files.size(), files.size(), 0,
                    "old files merged before are still in use; the Dataset is compacted again once they are removed");
        if (!force && files.size() <= THRESHOLD)
            return outcome("NOT_NEEDED", files.size(), files.size(), 0, files.size() + " files - compacted after " + THRESHOLD);
        Retention retention = retentionInForce(content);
        if (files.isEmpty() || (files.size() == 1 && retention == null))
            return outcome("NOT_NEEDED", files.size(), files.size(), 0, "nothing to merge");

        // the arrivals in order: those inside the current merged file first (its map's order), then each other file
        Map<String, Object> map = readMap(dir(dataRoot, id).resolve(MAP));
        Path merged = mergedFile(map, db);
        List<Input> inputs = new ArrayList<>();
        if (merged != null && files.contains(merged)) {
            if (unchanged(map, merged))
                for (Segment s : segments(map)) inputs.add(new Input(s.file(), s.received(), merged, s.firstRow(), s.rows(), s.holds()));
            else {
                // rewritten since: its segments no longer hold, so it is carried as ONE block naming every arrival it holds
                List<String> holds = new ArrayList<>();
                for (Segment s : segments(map)) {
                    holds.add(s.file());
                    holds.addAll(s.holds());
                }
                inputs.add(new Input(db.relativize(merged).toString().replace('\\', '/'), str(map.get("at")), merged, -1, -1, holds));
            }
        }
        List<Path> loose = new ArrayList<>(files);
        if (merged != null) loose.remove(merged);
        loose.sort(Comparator.comparing((Path p) -> modified(p)).thenComparing(Path::toString));
        for (Path p : loose) inputs.add(new Input(db.relativize(p).toString().replace('\\', '/'), modified(p), p, -1, -1, List.of()));

        String declared = content.get(DatasetRoutes.EVENT_DATE_KEY) instanceof String s && !s.isBlank() ? s : null;
        Path temp = dir(dataRoot, id).resolve("temp");
        Files.createDirectories(temp);
        String name = "compacted_" + STAMP.format(Instant.now()) + ".parquet";
        Path written = temp.resolve(name);
        List<Map<String, Object>> arrivals = new ArrayList<>();
        List<Map<String, Object>> removed = new ArrayList<>();
        List<Input> keep = new ArrayList<>(inputs);
        List<Input> dropped = new ArrayList<>();
        long rowsBefore = 0, rowsAfter = 0;
        DuckDbUtil.loadDriver();
        try (Connection duck = DuckDbUtil.openInMemory(DuckDbUtil.spillDirUnder(dataRoot), List.of(dir(dataRoot, id).toAbsolutePath().normalize()));
             Statement st = duck.createStatement()) {
            // the When column only when the files have it (an edited entry may name one they do not)
            String eventDate = declared != null && columns(st, union(inputs)).contains(declared) ? declared : null;
            rowsBefore = count(st, union(inputs));
            String rowFilter = null;
            if (retention != null) {
                // by arrival (keep N arrivals, or keep N days without a When column): whole arrivals go
                if (retention.arrivals() != null && keep.size() > retention.arrivals()) {
                    dropped.addAll(keep.subList(0, keep.size() - retention.arrivals()));
                    keep = new ArrayList<>(keep.subList(keep.size() - retention.arrivals(), keep.size()));
                }
                if (retention.days() != null) {
                    LocalDateTime cutoff = LocalDateTime.now(ZoneOffset.UTC).minusDays(retention.days());
                    if (eventDate != null) {
                        // a row whose date is missing or cannot be read is never dropped: only a date older than the cutoff
                        String when = "TRY_CAST(" + SqlIdent.q(eventDate) + " AS TIMESTAMP)";
                        rowFilter = "(" + when + " IS NULL OR " + when + " >= TIMESTAMP " + SqlIdent.sqlStr(cutoff.toString().replace('T', ' ')) + ")";
                    } else {
                        String cut = cutoff.toInstant(ZoneOffset.UTC).toString();
                        List<Input> old = keep.stream().filter(i -> i.received() != null && !i.received().isEmpty() && i.received().compareTo(cut) < 0).toList();
                        dropped.addAll(old);
                        keep.removeAll(old);
                    }
                }
            }
            String kept = rowFilter == null ? union(keep) : "SELECT * FROM (" + union(keep) + ") WHERE " + rowFilter;
            Map<Integer, long[]> rows = new HashMap<>();
            Map<Integer, String[]> period = new HashMap<>();
            if (!keep.isEmpty()) {
                String ec = eventDate == null ? "NULL" : SqlIdent.q(eventDate);
                try (ResultSet rs = st.executeQuery("SELECT __seq, count(*), CAST(min(" + ec + ") AS VARCHAR), CAST(max(" + ec
                        + ") AS VARCHAR) FROM (" + kept + ") GROUP BY __seq")) {
                    while (rs.next()) {
                        rows.put(rs.getInt(1), new long[] {rs.getLong(2)});
                        period.put(rs.getInt(1), new String[] {rs.getString(3), rs.getString(4)});
                    }
                }
                st.execute("COPY (SELECT * EXCLUDE (__seq, __rn) FROM (" + kept + ") ORDER BY __seq, __rn) TO "
                        + SqlIdent.pathStr(written) + " (FORMAT PARQUET, COMPRESSION snappy)");
            }
            long first = 0;
            String now = Instant.now().toString();
            for (int i = 0; i < keep.size(); i++) {
                Input in = keep.get(i);
                long n = rows.getOrDefault(i, new long[] {0})[0];
                if (n == 0) {
                    removed.add(removedEntry(in.file(), now, retention != null ? "retention" : "empty"));
                    continue;
                }
                Map<String, Object> a = new LinkedHashMap<>();
                a.put("file", in.file());
                a.put("received", in.received());
                a.put("firstRow", first);
                a.put("rows", n);
                a.put("coversFrom", period.get(i)[0]);
                a.put("coversTo", period.get(i)[1]);
                if (!in.holds().isEmpty()) a.put("holds", in.holds());
                arrivals.add(a);
                first += n;
            }
            rowsAfter = first;
        }
        for (Input d : dropped) removed.add(removedEntry(d.file(), Instant.now().toString(), "retention"));
        if (map != null && map.get("removed") instanceof List<?> before) {   // older removals first, then this one's
            List<Map<String, Object>> all = new ArrayList<>();
            for (Object o : before) if (o instanceof Map<?, ?> m) all.add(new LinkedHashMap<>(castMap(m)));
            all.addAll(removed);
            removed = all;
        }
        while (removed.size() > REMOVED_KEPT) removed.remove(0);

        // ── the swap: pending map, merged file in, inputs out, map committed ──
        Map<String, Object> next = new LinkedHashMap<>();
        next.put("merged", arrivals.isEmpty() ? null : name);
        if (!arrivals.isEmpty()) {   // the file the map describes: a later rewrite (Replace the period) is noticed
            next.put("mergedBytes", Files.size(written));
            next.put("mergedModified", Files.getLastModifiedTime(written).toMillis());
        }
        next.put("at", Instant.now().toString());
        next.put("arrivals", arrivals);
        next.put("removed", removed);
        if (retention != null) next.put(RETENTION, retention.asMap());
        Map<String, Object> pending = new LinkedHashMap<>();
        pending.put("merged", arrivals.isEmpty() ? null : name);
        List<String> gone = new ArrayList<>();
        for (Path p : files) gone.add(db.relativize(p).toString().replace('\\', '/'));
        pending.put("inputs", gone);
        pending.put("map", next);
        Path pendingFile = dir(dataRoot, id).resolve(MAP + ".pending");
        AtomicFiles.write(pendingFile, JSON.writeValueAsBytes(pending), ".cmp-");
        if (arrivals.isEmpty()) Files.deleteIfExists(written);
        else Files.move(written, db.resolve(name), StandardCopyOption.ATOMIC_MOVE);
        int left = finish(dataRoot, id, pending, pendingFile);
        int after = parquetFiles(db).size();
        return outcome("COMPACTED", files.size(), after, rowsBefore - rowsAfter,
                files.size() + " files merged into " + (after - left) + (rowsBefore > rowsAfter ? "; " + (rowsBefore - rowsAfter)
                        + " rows removed by the retention policy" : "")
                        + (left > 0 ? "; " + left + " old file(s) could not be removed yet (in use) and are counted twice until "
                        + "the next compaction removes them" : ""));
    }

    /** One arrival to carry into the merged file: from its own file ({@code from < 0}) or a segment of the merged one. */
    private record Input(String file, String received, Path source, long from, long rows, List<String> holds) {}

    /** {@code UNION ALL BY NAME} of the inputs, each row tagged with its arrival ({@code __seq}) and its order ({@code __rn}). */
    private static String union(List<Input> inputs) {
        if (inputs.isEmpty()) return "SELECT NULL AS __seq, NULL AS __rn WHERE false";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < inputs.size(); i++) {
            Input in = inputs.get(i);
            if (i > 0) sb.append(" UNION ALL BY NAME ");
            sb.append("SELECT * EXCLUDE (file_row_number), ").append(i).append(" AS __seq, file_row_number AS __rn FROM read_parquet(")
                    .append(SqlIdent.pathStr(in.source())).append(", file_row_number = true)");
            List<String> where = new ArrayList<>();
            if (in.from() >= 0) where.add("file_row_number >= " + in.from() + " AND file_row_number < " + (in.from() + in.rows()));
            if (!where.isEmpty()) sb.append(" WHERE ").append(String.join(" AND ", where));
        }
        return sb.toString();
    }

    /** The column names {@code sql} answers with. */
    private static java.util.Set<String> columns(Statement st, String sql) throws SQLException {
        java.util.Set<String> out = new java.util.HashSet<>();
        try (ResultSet rs = st.executeQuery("DESCRIBE " + sql)) {
            while (rs.next()) out.add(rs.getString(1));
        }
        return out;
    }

    private static long count(Statement st, String sql) throws SQLException {
        try (ResultSet rs = st.executeQuery("SELECT count(*) FROM (" + sql + ")")) {
            return rs.next() ? rs.getLong(1) : 0;
        }
    }

    /** Finish a swap a crash interrupted, or drop one whose merged file never moved in. */
    @SuppressWarnings("unchecked")
    static void heal(Path dataRoot, String id) throws IOException {
        Path pendingFile = dir(dataRoot, id).resolve(MAP + ".pending");
        Map<String, Object> pending = readMap(pendingFile);
        if (pending == null) {
            Files.deleteIfExists(pendingFile);
            return;
        }
        Object merged = pending.get("merged");
        if (merged == null || Files.isRegularFile(database(dataRoot, id).resolve(String.valueOf(merged))))
            finish(dataRoot, id, pending, pendingFile);
        else Files.deleteIfExists(pendingFile);
    }

    /**
     * Delete the inputs (only inside the Dataset's folder, never the merged file) and commit the map. An input that cannot
     * be deleted (held open by a reader) stays named in the pending file, so the next {@link #heal} retries it; answers how
     * many are left.
     */
    private static int finish(Path dataRoot, String id, Map<String, Object> pending, Path pendingFile) throws IOException {
        Path db = database(dataRoot, id).toAbsolutePath().normalize();
        Object merged = pending.get("merged");
        List<String> left = new ArrayList<>();
        if (pending.get("inputs") instanceof List<?> inputs)
            for (Object o : inputs) {
                Path p = db.resolve(String.valueOf(o)).normalize();
                if (!p.startsWith(db) || p.getFileName().toString().equals(merged)) continue;
                try {
                    Files.deleteIfExists(p);
                } catch (IOException inUse) {
                    left.add(String.valueOf(o));
                }
            }
        AtomicFiles.write(dir(dataRoot, id).resolve(MAP), JSON.writeValueAsBytes(pending.get("map")), ".cmp-");
        if (left.isEmpty()) Files.deleteIfExists(pendingFile);
        else {
            Map<String, Object> retry = new LinkedHashMap<>(pending);
            retry.put("inputs", left);
            AtomicFiles.write(pendingFile, JSON.writeValueAsBytes(retry), ".cmp-");
        }
        return left.size();
    }

    /** The Dataset's Parquet files (never a hidden one or a link), sorted. */
    static List<Path> parquetFiles(Path db) throws IOException {
        if (!Files.isDirectory(db)) return List.of();
        try (Stream<Path> s = Files.walk(db, 8)) {
            return s.filter(p -> Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS))
                    .filter(p -> !p.getFileName().toString().startsWith("."))
                    .filter(p -> p.getFileName().toString().endsWith(".parquet"))
                    .map(p -> p.toAbsolutePath().normalize()).sorted().toList();
        }
    }

    private static String modified(Path p) {
        try {
            return Files.getLastModifiedTime(p).toInstant().toString();
        } catch (IOException gone) {
            return "";
        }
    }

    private static Map<String, Object> removedEntry(String file, String at, String reason) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("file", file);
        m.put("at", at);
        m.put("reason", reason);
        return m;
    }

    // ── retention (D-9, Standard and up) ───────────────────────────────────────────────────────────────────────

    /** Keep {@code days} days, or {@code arrivals} arrivals (exactly one is set). */
    record Retention(Integer days, Integer arrivals) {
        Map<String, Object> asMap() {
            return days != null ? Map.of("days", days) : Map.of("arrivals", arrivals);
        }
    }

    /** The entry's retention, or null; ignored (null) where the edition has no retention, so Basic never drops rows. */
    static Retention retentionInForce(Map<String, Object> content) {
        if (!EditionFeatures.present(OnboardImport.AUTOMATION_FEATURE)) return null;
        return retentionOf(content);
    }

    static Retention retentionOf(Map<String, Object> content) {
        if (!(content.get(RETENTION) instanceof Map<?, ?> r)) return null;
        Integer days = r.get("days") instanceof Number n ? n.intValue() : null;
        Integer arrivals = r.get("arrivals") instanceof Number n ? n.intValue() : null;
        if ((days == null) == (arrivals == null)) return null;
        if ((days != null && days < 1) || (arrivals != null && arrivals < 1)) return null;
        return new Retention(days, arrivals);
    }

    // ── routes ─────────────────────────────────────────────────────────────────────────────────────────────────

    /** {@code GET /datasets/{id}/storage}: the files, the last compaction and the retention policy. */
    static Object storage(ApiContext api, HttpExchange ex, String id) throws IOException {
        Path writeRoot = OnboardRoutes.requireSelfImport(api, "Dataset storage");
        Path dataRoot = OnboardRoutes.requireDataRoot(api);
        Map<String, Object> content = OnboardImport.importDataset(writeRoot, ex, id, HostContext.of(api).service());
        return describe(dataRoot, id, content);
    }

    static Map<String, Object> describe(Path dataRoot, String id, Map<String, Object> content) throws IOException {
        List<Path> files = parquetFiles(database(dataRoot, id).toAbsolutePath().normalize());
        long bytes = 0;
        for (Path p : files) bytes += Files.size(p);
        Map<String, Object> map = readMap(dir(dataRoot, id).resolve(MAP));
        Map<String, Segment> merged = merged(dataRoot, id);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("dataset", id);
        out.put("files", files.size());
        out.put("bytes", bytes);
        out.put("threshold", THRESHOLD);
        out.put("compactedAt", map == null ? null : map.get("at"));
        int arrivals = 0;   // a carried block counts the arrivals it holds, never its own old file name
        if (!merged.isEmpty() && map != null) for (Segment s : segments(map)) arrivals += s.holds().isEmpty() ? 1 : s.holds().size();
        out.put("compactedArrivals", arrivals);
        out.put("removedArrivals", map != null && map.get("removed") instanceof List<?> r ? r.size() : 0);
        Retention r = retentionOf(content);
        out.put(RETENTION, r == null ? null : r.asMap());
        out.put("retentionAvailable", EditionFeatures.present(OnboardImport.AUTOMATION_FEATURE));
        return out;
    }

    /** {@code POST /datasets/{id}/compact}: <i>Compact now</i>, whatever the count; claimed like an import. */
    static Object compactNow(ApiContext api, HttpExchange ex, String id) throws IOException {
        Path writeRoot = OnboardRoutes.requireSelfImport(api, "Compact now");
        Path dataRoot = OnboardRoutes.requireDataRoot(api);
        Map<String, Object> content = OnboardImport.importDataset(writeRoot, ex, id, HostContext.of(api).service());
        String key = writeRoot + "|" + id;
        if (!OnboardImport.IN_FLIGHT.add(key))
            throw new ApiException(409, ErrorCodes.CONFLICT, "'" + id + "' is being imported or deleted right now; compact it when that has finished");
        try {
            Map<String, Object> o = compact(writeRoot, dataRoot, id, content, true, ApiContext.actor(ex));
            Map<String, Object> out = describe(dataRoot, id, content);
            out.put("compaction", o);
            return out;
        } catch (IOException | RuntimeException passOn) {
            throw passOn;
        } catch (Exception failed) {
            throw new ApiException(500, ErrorCodes.INTERNAL, "the files could not be compacted: " + failed.getMessage());
        } finally {
            OnboardImport.IN_FLIGHT.remove(key);
        }
    }

    /**
     * {@code PUT /datasets/{id}/storage {retention: {days: N} | {arrivals: N} | null}}: the retention policy, a Dataset
     * change (Standard and up, {@code canAuthorWorkbench}, an approval policy over Datasets refuses). It takes effect at
     * the next compaction.
     */
    static Object setRetention(ApiContext api, HttpExchange ex, String id, Map<String, Object> body) throws IOException {
        Path writeRoot = OnboardRoutes.requireSelfImport(api, "Retention");
        Path dataRoot = OnboardRoutes.requireDataRoot(api);
        Map<String, Object> current = OnboardImport.importDataset(writeRoot, ex, id, HostContext.of(api).service());
        if (!EditionFeatures.present(OnboardImport.AUTOMATION_FEATURE))
            throw new ApiException(503, ErrorCodes.CAPABILITY_UNAVAILABLE, "A retention policy: "
                    + EditionFeatures.refusal(OnboardImport.AUTOMATION_FEATURE) + "; every file stays, compacted");
        if (body == null || !body.containsKey(RETENTION))
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "state retention: {days: N}, {arrivals: N} or null");
        Object raw = body.get(RETENTION);
        Retention r = null;
        if (raw != null) {
            if (!(raw instanceof Map<?, ?> m) || m.size() != 1
                    || !(m.get("days") instanceof Number || m.get("arrivals") instanceof Number))
                throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "retention is {days: N} or {arrivals: N}");
            Object n = m.get("days") != null ? m.get("days") : m.get("arrivals");
            if (!(n instanceof Integer || n instanceof Long) || ((Number) n).longValue() < 1 || ((Number) n).longValue() > 100_000)
                throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "retention keeps a whole number from 1 to 100000");
            int v = ((Number) n).intValue();
            r = m.get("days") != null ? new Retention(v, null) : new Retention(null, v);
        }
        PendingChanges.holdRefusing(api, List.of("dataset"), "a retention policy is a Dataset change");
        ComponentStore store = new ComponentStore(writeRoot.resolve("registry"));
        Map<String, Object> content = new LinkedHashMap<>(current);
        content.remove("name");
        if (r == null) content.remove(RETENTION);
        else content.put(RETENTION, r.asMap());
        try {
            ComponentRoutes.validateKind(api, "dataset", id, content);
        } catch (IllegalArgumentException bad) {
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, bad.getMessage());
        }
        ComponentRegistry.Component written = store.write("dataset", id, content);
        return describe(dataRoot, id, written.content());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Map<?, ?> m) {
        return (Map<String, Object>) m;
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static long num(Object o) {
        return o instanceof Number n ? n.longValue() : 0L;
    }
}
