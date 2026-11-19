package com.gamma.job;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.consignment.ConsignmentOutputStores;
import com.gamma.etl.EditionFeatures;
import com.gamma.onboard.ImportFollowOn;
import com.gamma.pipeline.ComponentRegistry;
import com.gamma.pipeline.ComponentStore;
import com.gamma.pipeline.SpaceConfigRoot;
import com.gamma.util.AtomicFiles;
import com.gamma.util.DuckDbUtil;
import com.gamma.util.SqlIdent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
 * The {@code compact} maintenance task's {@code mode: arrivals} ({@code BASIC-ELT-PROFILE-1} B6; built as
 * {@code LA-DATASET-COMPACT-1} in the control plane's {@code DatasetCompaction}, moved here): every Parquet file of an
 * import Dataset ({@code <dataRoot>/<id>/database}, one file per arrival) merged into ONE file, ordered by arrival.
 * {@link PartitionCompactor} (the default {@code mode: partitions}) merges per partition directory by age and keeps no
 * arrival order; this mode keeps each arrival findable:
 * <ul>
 *   <li><b>The merge map</b> {@code <dataRoot>/<id>/compaction.json} (same file and format as before B6): the merged file
 *       and, per arrival it holds, the arrival's own output name, when it was received, its row segment in the merged file
 *       and the period it covers - so every arrival keeps its period and stays <i>kept</i> ({@link #merged}). Trusted only
 *       while its merged file exists: a <i>Replace everything</i> sets the whole folder aside, and the map with it falls
 *       silent.</li>
 *   <li><b>Retention</b> (Standard and up): the Dataset entry's {@code retention {days | arrivals}}, applied at compaction
 *       time: <i>keep N days</i> by the event date (the When column), else by when the file was received; <i>keep N
 *       arrivals</i> drops the oldest arrivals' rows. Ignored where the edition has no retention ({@link #retentionInForce}).</li>
 *   <li><b>The registry</b>: every file merged away is marked {@code COMPACTED_AWAY} in the Consignment output registry,
 *       as {@link PartitionCompactor} does, so a reprocess of an arrival whose file was merged is refused instead of
 *       putting its rows in twice (they are in the merged file).</li>
 * </ul>
 * Two triggers, one implementation: the import's commit step and <i>Compact now</i> call {@link #compact} (control plane
 * {@code DatasetCompaction}; the step only past {@link #THRESHOLD} files), and a maintenance Job
 * {@code {task: compact, mode: arrivals, dataset: <id>}} calls {@link #run} (at any count, as <i>Compact now</i>).
 * <p>The swap is crash-safe the way a pending map makes it: the merged file is written aside, a {@code .pending} map names
 * it and its inputs, the merged file moves in, the inputs go, the map is committed; {@link #heal} finishes or drops a
 * pending map. Between the move and the deletes a reader may count both briefly. Every compaction that changed the files
 * tells each installed application ({@link ImportFollowOn#filesRewritten}).
 */
public final class ArrivalCompactor {

    private ArrivalCompactor() {}

    private static final Logger log = LoggerFactory.getLogger(ArrivalCompactor.class);

    /** More files than this, and an import compacts on its own (D-7). */
    public static final int THRESHOLD = 20;
    public static final String MAP = "compaction.json";
    /** The Dataset entry's retention policy key. */
    public static final String RETENTION = "retention";
    /** The Dataset entry's When column key (the event date). */
    public static final String EVENT_DATE_KEY = "eventDate";
    /** Arrivals that retention removed, remembered at most this many (newest kept). */
    static final int REMOVED_KEPT = 500;
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmssSSS").withZone(ZoneOffset.UTC);

    // ── who else may be changing a Dataset's files ─────────────────────────────────────────────────────────────

    /**
     * The claim a Job takes on an import Dataset before it compacts, so it never works under an import, a <i>Replace the
     * period</i>, a delete or another compaction. The control plane installs its own (the import's claim); without one
     * (an engine with no control plane, where nothing else changes an import's files) every claim is granted.
     */
    @FunctionalInterface
    public interface Claims {
        /** Claim Dataset {@code id} of the calling thread's Space; {@code null} when someone else holds it. Run the answer to release it. */
        Runnable claim(String id);
    }

    private static final Claims UNCLAIMED = id -> () -> {};
    private static volatile Claims claims = UNCLAIMED;

    /** Install the claims a Job takes ({@code null}: none). */
    public static void claims(Claims c) {
        claims = c == null ? UNCLAIMED : c;
    }

    /** Forget {@code c} if it is still the one installed (its control plane closed). */
    public static void forget(Claims c) {
        if (claims == c) claims = UNCLAIMED;
    }

    // ── the merge map ──────────────────────────────────────────────────────────────────────────────────────────

    /**
     * One arrival inside the merged file: its own output name, when received, its rows and the period it covers. A segment
     * with {@code rows < 0} is an arrival whose rows are in the merged file at no known place (its file was rewritten since,
     * e.g. by <i>Replace the period</i>): kept, period unknown. {@code holds} names the arrivals such a block carries.
     */
    public record Segment(String file, String received, long firstRow, long rows, String coversFrom, String coversTo, List<String> holds) {
        public Segment {
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
     * read, or its merged file is gone (a Replace everything set it aside). For the arrivals list: an arrival whose own file
     * is gone but is named here is still kept, with this period.
     */
    public static Map<String, Segment> merged(Path dataRoot, String id) {
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

    /**
     * {@code LA-DATASET-COMPACT-2}: the arrivals' segments of the merged file, when {@code file} IS the map's merged file and
     * unchanged since, so its row segments hold (null otherwise). <i>Replace the period</i> re-counts them as it rewrites the
     * file ({@link #segmentsRewritten}), so <i>keep N arrivals</i> still counts every arrival after it.
     */
    public static List<Segment> trustedSegments(Path base, Path file) {
        Map<String, Object> map = readMap(base.resolve(MAP));
        Path merged = mergedFile(map, base.resolve("database"));
        if (merged == null || !merged.toAbsolutePath().normalize().equals(file.toAbsolutePath().normalize()) || !unchanged(map, merged))
            return null;
        List<Segment> s = segments(map);
        return s.isEmpty() || s.stream().anyMatch(x -> x.rows() < 0) ? null : s;
    }

    /**
     * Commit the map after the merged file {@code file} was rewritten with {@code next} (each arrival with a row left, its new
     * segment, in order; an arrival left with none drops out, so the arrivals answer reads it as replaced - not as a
     * retention removal). Answers the map's bytes before, for an undo ({@link #restoreMap}).
     */
    public static byte[] segmentsRewritten(Path base, Path file, List<Segment> next) throws IOException {
        Path mapFile = base.resolve(MAP);
        byte[] before = Files.readAllBytes(mapFile);
        Map<String, Object> map = new LinkedHashMap<>(readMap(mapFile));
        List<Map<String, Object>> arrivals = new ArrayList<>();
        for (Segment s : next) {
            Map<String, Object> a = new LinkedHashMap<>();
            a.put("file", s.file());
            a.put("received", s.received());
            a.put("firstRow", s.firstRow());
            a.put("rows", s.rows());
            a.put("coversFrom", s.coversFrom());
            a.put("coversTo", s.coversTo());
            if (!s.holds().isEmpty()) a.put("holds", s.holds());
            arrivals.add(a);
        }
        map.put("arrivals", arrivals);
        map.put("mergedBytes", Files.size(file));
        map.put("mergedModified", Files.getLastModifiedTime(file).toMillis());
        AtomicFiles.write(mapFile, JSON.writeValueAsBytes(map), ".cmp-");
        return before;
    }

    /** Put back the map {@link #segmentsRewritten} replaced (the import failed after it). */
    public static void restoreMap(Path base, byte[] before) throws IOException {
        AtomicFiles.write(base.resolve(MAP), before, ".cmp-");
    }

    /** The last compaction's time ({@code at}) and the arrivals retention removed so far, from the map; nulls and 0 when none. */
    public static Map<String, Object> summary(Path dataRoot, String id) {
        Map<String, Object> map = readMap(dir(dataRoot, id).resolve(MAP));
        int arrivals = 0;   // a carried block counts the arrivals it holds, never its own old file name
        if (map != null && !merged(dataRoot, id).isEmpty()) for (Segment s : segments(map)) arrivals += s.holds().isEmpty() ? 1 : s.holds().size();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("compactedAt", map == null ? null : map.get("at"));
        out.put("compactedArrivals", arrivals);
        out.put("removedArrivals", map != null && map.get("removed") instanceof List<?> r ? r.size() : 0);
        return out;
    }

    // ── compaction ─────────────────────────────────────────────────────────────────────────────────────────────

    /** What a compaction did: {@code COMPACTED}, {@code NOT_NEEDED} or {@code FAILED}, in words. */
    public static Map<String, Object> outcome(String result, int filesBefore, int filesAfter, long rowsRemoved, String message) {
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("result", result);
        o.put("filesBefore", filesBefore);
        o.put("filesAfter", filesAfter);
        o.put("rowsRemoved", rowsRemoved);
        o.put("message", message);
        return o;
    }

    /**
     * {@link #merge} under the Dataset entry {@code entry} (its retention in force and its When column), then - unless
     * nothing was merged - each of {@code followOns}' {@link ImportFollowOn#filesRewritten} with {@code context}
     * (LA-DATASET-COMPACT-2): Link Analysis forgets what it remembered of the old files, so a graph read right after a
     * retention compaction no longer serves the removed rows from the link index. A failed merge is told too: it may have
     * moved files before it failed. Below {@link #THRESHOLD} files and not {@code force}, nothing happens. The caller holds
     * the Dataset's claim.
     */
    public static Map<String, Object> compact(Path dataRoot, String id, Map<String, Object> entry, boolean force,
                                              List<ImportFollowOn> followOns, ImportFollowOn.Context context) throws Exception {
        Map<String, Object> o = null;
        try {
            o = merge(dataRoot, id, entry, force);
            return o;
        } finally {
            if (o == null || !"NOT_NEEDED".equals(o.get("result")))
                for (ImportFollowOn f : followOns)
                    try {
                        f.filesRewritten(context);
                    } catch (RuntimeException e) {
                        log.warn("'{}' could not be told that Dataset '{}' was compacted: {}", f.key(), id, e.toString());
                    }
        }
    }

    /**
     * The maintenance Job {@code {task: compact, mode: arrivals, dataset: <id>}}: the import Dataset {@code dataset} of the
     * Job's Space compacted at any count, as <i>Compact now</i> does, under the Dataset's claim ({@link Claims}). A Dataset
     * someone else holds is left for the next run.
     */
    static JobResult run(JobConfig cfg, String dataDir) throws Exception {
        long t0 = System.nanoTime();
        String id = cfg.require("dataset").trim();
        // ⛔ The Job's own data root and the Space's registry, as MaterializeTask reads them - never -Dassist.write.root
        if (dataDir == null || dataDir.isBlank())
            throw new IllegalStateException("compact mode arrivals needs a data root (-Ddata.dir / space dataDir)");
        Path writeRoot = SpaceConfigRoot.requireCurrent("compact mode arrivals");
        Path dataRoot = Path.of(dataDir);
        Map<String, Object> entry = new ComponentStore(writeRoot.resolve("registry")).get("dataset", id)
                .map(ComponentRegistry.Component::content)
                .orElseThrow(() -> new IllegalArgumentException("compact mode arrivals: no Dataset '" + id + "'"));
        if (!"import".equals(String.valueOf(entry.get("origin"))))
            throw new IllegalArgumentException("compact mode arrivals: Dataset '" + id + "' was not written by an import (Add data), "
                    + "so it has no arrivals to merge; compact its store with mode partitions");
        Runnable release = claims.claim(id);
        if (release == null)
            return JobResult.ok("compact: Dataset '" + id + "' is being imported, compacted or deleted right now; "
                    + "it is compacted at the next run", (System.nanoTime() - t0) / 1_000_000L);
        try {
            List<ImportFollowOn> followOns = new ArrayList<>();
            com.gamma.module.EntitledServices.load(ImportFollowOn.class).forEach(followOns::add);
            Map<String, Object> o = compact(dataRoot, id, entry, true, followOns,
                    new ImportFollowOn.Context(writeRoot, dataRoot, id, entry, "scheduler"));
            return JobResult.ok("compact: Dataset '" + id + "' " + o.get("result") + " - " + o.get("message"),
                    (System.nanoTime() - t0) / 1_000_000L);
        } finally {
            release.run();
        }
    }

    /**
     * Merge every Parquet file of the Dataset into one, ordered by arrival, applying the retention policy in force. Below
     * the threshold (and not {@code force}) nothing happens. The caller holds the Dataset's claim.
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

        String declared = content.get(EVENT_DATE_KEY) instanceof String s && !s.isBlank() ? s : null;
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

        // ── the swap: pending map, merged file in, inputs out (marked compacted away), map committed ──
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
    public static void heal(Path dataRoot, String id) throws IOException {
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
     * Mark the inputs compacted away in the Consignment output registry and delete them (only inside the Dataset's folder,
     * never the merged file), then commit the map. The merged file is in by now, so the inputs' rows are in it (or gone by
     * retention): a reprocess of one must be refused even while its old file is still on disk. An input that cannot be
     * deleted (held open by a reader) stays named in the pending file, so the next {@link #heal} retries it; answers how many
     * are left.
     */
    private static int finish(Path dataRoot, String id, Map<String, Object> pending, Path pendingFile) throws IOException {
        Path db = database(dataRoot, id).toAbsolutePath().normalize();
        Object merged = pending.get("merged");
        List<String> names = new ArrayList<>();
        List<Path> inputs = new ArrayList<>();
        if (pending.get("inputs") instanceof List<?> named)
            for (Object o : named) {
                Path p = db.resolve(String.valueOf(o)).normalize();
                if (!p.startsWith(db) || p.getFileName().toString().equals(merged)) continue;
                names.add(String.valueOf(o));
                inputs.add(p);
            }
        // the registry first: a crash after it leaves the pending map, so the next heal marks (idempotent) and deletes again
        ConsignmentOutputStores.markCompactedAway(inputs.stream().map(Path::toString).toList());
        List<String> left = new ArrayList<>();
        for (int i = 0; i < inputs.size(); i++)
            try {
                Files.deleteIfExists(inputs.get(i));
            } catch (IOException inUse) {
                left.add(names.get(i));
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
    public static List<Path> parquetFiles(Path db) throws IOException {
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
    public record Retention(Integer days, Integer arrivals) {
        public Map<String, Object> asMap() {
            return days != null ? Map.of("days", days) : Map.of("arrivals", arrivals);
        }
    }

    /** The entry's retention, or null; ignored (null) where the edition has no retention, so Basic never drops rows. */
    public static Retention retentionInForce(Map<String, Object> content) {
        if (!EditionFeatures.present(EditionFeatures.PIPELINE_AUTOMATION)) return null;
        return retentionOf(content);
    }

    public static Retention retentionOf(Map<String, Object> content) {
        if (!(content.get(RETENTION) instanceof Map<?, ?> r)) return null;
        Integer days = r.get("days") instanceof Number n ? n.intValue() : null;
        Integer arrivals = r.get("arrivals") instanceof Number n ? n.intValue() : null;
        if ((days == null) == (arrivals == null)) return null;
        if ((days != null && days < 1) || (arrivals != null && arrivals < 1)) return null;
        return new Retention(days, arrivals);
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
