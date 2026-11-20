package com.gamma.job;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.config.safety.PathJail;
import com.gamma.consignment.ConsignmentOutputStores;
import com.gamma.sql.SqlViews;
import com.gamma.util.AtomicFiles;
import com.gamma.util.DuckDbUtil;
import com.gamma.util.SqlIdent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * The arrival modes that take rows away from an import Dataset ({@code BASIC-ELT-PROFILE-1} B4; built as
 * {@code LA-ARRIVAL-MODES-1} in the control plane's {@code DatasetPeriod}, moved here beside {@link ArrivalCompactor},
 * whose merge map it keeps true):
 * <ul>
 *   <li><b><i>Replace the period</i></b> ({@link #replacePeriod}): after an arrival's rows were ADDED, the rows of every
 *       OTHER Parquet file of the Dataset ({@code <dataRoot>/<id>/database}) that fall on a day the arrival covers are
 *       removed, so a corrected week replaces exactly those days. Days are the calendar dates of the When column
 *       ({@code CAST(… AS DATE)}, the value as stored); a row with no date is never removed. A touched file is rewritten
 *       at its own path (a file left with no row is removed), the original kept in {@code <id>/database.period-<stamp>/}
 *       until the import has succeeded ({@link #drop}) or failed ({@link #restore}).</li>
 *   <li><b>The registry</b>: once the import has succeeded, every file whose rows were replaced is marked {@code REPLACED}
 *       in the Consignment output registry - by <i>Replace the period</i> ({@link #drop}) and by <i>Replace everything</i>
 *       ({@link #everythingReplaced}) - so a reprocess of its Consignment is refused instead of bringing the replaced rows
 *       back. A {@code REPLACED} file stays readable: it holds the rows the replacement kept.</li>
 * </ul>
 * <p><b>Crash safety</b> (the plan's data-loss risk): every rewrite is written aside first (a hidden {@code .period-…tmp}
 * beside the file), then the journal {@code <id>/period.pending} names each file, its rewrite, the arrival's own outputs and
 * the merge map's next segments, and only then are the files swapped - all of them under one {@link SnapshotSwapLock}, so a
 * reader sees the old files or the new ones, never some of each. {@link #heal} (run before every replace, compaction and
 * import run of the Dataset) finishes what a crash interrupted: a swap or a success heals FORWARD to the new state (the
 * arrival's rows were committed by the engine, so its period is replaced), a restore heals BACK to the old one (the
 * originals, the merge map, and the arrival's own rows gone) - never a mix. A swap whose rewrite was lost heals back.
 * The caller holds the Dataset's claim throughout.
 */
public final class ArrivalReplacer {

    private static final Logger log = LoggerFactory.getLogger(ArrivalReplacer.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * The journal of the replacement in progress, beside the store: {@code period.pending} for an import Dataset's
     * {@code database} folder (beside the merge map), {@code <store>.period.pending} for any other store folder.
     */
    public static final String JOURNAL = "period.pending";
    /** The merge map as it was before the swap, inside the aside folder. */
    static final String MAP_BEFORE = "compaction.json.before";
    static final String SWAP = "swap", SWAPPED = "swapped", RESTORING = "restoring";
    private static final Pattern STAMP = Pattern.compile("[A-Za-z0-9]+");

    /** What the replacement did: the days the arrival covers, the rows it removed, the files it rewrote or emptied. */
    public record Result(List<String> days, long rowsRemoved, int filesTouched) { }

    /** One touched file and its rewrite ({@code null}: it is left with no row and goes). */
    private record Entry(Path file, Path tmp) { }

    private final Path base, database, aside;
    /** The arrival's own outputs, inside {@code database}: what a {@link #restore} removes with the replacement. */
    private List<Path> covering = List.of();
    /** Dropped or restored: the other is then a no-op. */
    private boolean done;

    /** One <i>Replace the period</i> of Dataset {@code id}; {@code stamp} names its aside folder (letters and digits). */
    public ArrivalReplacer(Path dataRoot, String id, String stamp) {
        this(dataRoot.resolve(id).resolve("database"), stamp);
    }

    /**
     * One replacement in the store folder {@code store} (a Pipeline's {@code dirs.database}, flat local Parquet:
     * {@code output.load}, BASIC-ELT-PROFILE-1 O6); its journal and aside folder sit beside it.
     */
    public static ArrivalReplacer forStore(Path store, String stamp) {
        return new ArrivalReplacer(store, stamp);
    }

    private ArrivalReplacer(Path store, String stamp) {
        if (!STAMP.matcher(String.valueOf(stamp)).matches())
            throw new IllegalArgumentException("a replace stamp is letters and digits: " + stamp);
        this.database = store.toAbsolutePath().normalize();
        this.base = database.getParent();
        this.aside = base.resolve(database.getFileName() + ".period-" + stamp);
    }

    // ── replace the period ─────────────────────────────────────────────────────────────────────────────────────

    /**
     * Remove, from every Parquet file of the Dataset except {@code fresh} (the files this run wrote), the rows on a day
     * that {@code covering} (the arrival's own output) holds. {@code covering} empty → nothing is removed. Undone by
     * {@link #restore}, made final by {@link #drop}.
     */
    public Result replacePeriod(List<Path> covering, List<Path> fresh, String eventDate) throws IOException {
        List<Path> own = new ArrayList<>();
        for (Path c : covering) {
            Path abs = c.toAbsolutePath().normalize();
            if (PathJail.contains(database, abs)) own.add(abs);
        }
        this.covering = own;   // before anything can fail: a restore removes the arrival's rows whatever happened
        heal(database);
        if (covering.isEmpty()) return new Result(List.of(), 0, 0);
        String col = SqlIdent.q(eventDate);
        List<Entry> entries = new ArrayList<>();
        List<String> days = new ArrayList<>();
        long removed = 0;
        Path merged = null;
        List<ArrivalCompactor.Segment> next = null;
        try (Connection duck = open(); Statement st = duck.createStatement()) {
            try (ResultSet rs = st.executeQuery("SELECT DISTINCT CAST(CAST(" + col + " AS DATE) AS VARCHAR) d FROM "
                    + source(covering) + " WHERE " + col + " IS NOT NULL ORDER BY d")) {
                while (rs.next()) days.add(rs.getString(1));
            }
            if (days.isEmpty()) return new Result(days, 0, 0);
            String in = "CAST(" + col + " AS DATE) IN (" + String.join(", ", days.stream().map(d -> "DATE " + SqlIdent.sqlStr(d)).toList()) + ")";
            String keep = col + " IS NULL OR NOT (" + in + ")";
            Set<Path> freshSet = new HashSet<>();
            for (Path f : fresh) freshSet.add(f.toAbsolutePath().normalize());
            for (Path f : ArrivalCompactor.parquetFiles(database)) {
                if (freshSet.contains(f)) continue;
                long hit, all;
                try (ResultSet rs = st.executeQuery("SELECT count(*) FILTER (WHERE " + in + "), count(*) FROM " + source(List.of(f)))) {
                    rs.next();
                    hit = rs.getLong(1);
                    all = rs.getLong(2);
                }
                if (hit == 0) continue;
                removed += hit;
                boolean empties = hit == all;
                // LA-DATASET-COMPACT-2: the merged file of a compaction is rewritten in its row order and each arrival's
                // segment re-counted, so the merge map keeps every arrival at a known place (keep N arrivals counts them)
                List<ArrivalCompactor.Segment> segs = empties ? null : ArrivalCompactor.trustedSegments(base, f);
                List<ArrivalCompactor.Segment> recounted = new ArrayList<>();
                if (segs != null && !recount(st, f, segs, keep, col, recounted)) segs = null;
                if (empties) {
                    entries.add(new Entry(f, null));
                    continue;
                }
                // the rewrite is named so no Parquet glob reads it; listed before it is written, so a failed write goes too
                Path tmp = f.resolveSibling(".period-" + f.getFileName() + ".tmp");
                entries.add(new Entry(f, tmp));
                st.execute("COPY (" + (segs == null ? "SELECT * FROM " + source(List.of(f)) + " WHERE " + keep
                        : "SELECT * EXCLUDE (file_row_number) FROM " + numbered(f) + " WHERE " + keep + " ORDER BY file_row_number")
                        + ") TO " + SqlIdent.pathStr(tmp) + " (FORMAT PARQUET)");
                if (segs != null) {
                    merged = f;
                    next = recounted;
                }
            }
        } catch (SQLException failed) {
            discard(entries);
            throw new IOException("the period could not be replaced: " + failed.getMessage(), failed);
        } catch (IOException | RuntimeException failed) {
            discard(entries);
            throw failed;
        }
        if (entries.isEmpty()) return new Result(days, removed, 0);

        // the journal first: from here a failure is undone (restore) and a crash heals (heal); then the merge map as it
        // was, then the swap of every file at once
        swapAll(entries, own, merged, next);
        return new Result(days, removed, entries.size());
    }

    /**
     * The journal first: from here a failure is undone (restore) and a crash heals (heal); then the merge map as it was,
     * then the swap of every file at once.
     */
    private void swapAll(List<Entry> entries, List<Path> own, Path merged, List<ArrivalCompactor.Segment> next) throws IOException {
        Map<String, Object> j = journal(SWAP, database, aside, entries, own, merged, next);
        write(database, j);
        Files.createDirectories(aside);
        if (merged != null) Files.copy(base.resolve(ArrivalCompactor.MAP), aside.resolve(MAP_BEFORE), StandardCopyOption.REPLACE_EXISTING);
        swap(database, aside, entries);
        if (merged != null) ArrivalCompactor.segmentsRewritten(base, merged, next);
        j.put("phase", SWAPPED);
        write(database, j);
    }

    // ── replace everything ─────────────────────────────────────────────────────────────────────────────────────

    /**
     * <i>Replace everything</i> at the commit of an arrival ({@code output.load: replace}, O6): every Parquet file of the
     * store except {@code fresh} (the files this run wrote) goes, through the same journal, swap and heal as
     * {@link #replacePeriod}. {@code covering} (the arrival's own output) empty → nothing is removed: an arrival that wrote
     * no file never empties the store. Undone by {@link #restore}, made final by {@link #drop}. The result names no day.
     */
    public Result replaceEverything(List<Path> covering, List<Path> fresh) throws IOException {
        List<Path> own = new ArrayList<>();
        for (Path c : covering) {
            Path abs = c.toAbsolutePath().normalize();
            if (PathJail.contains(database, abs)) own.add(abs);
        }
        this.covering = own;
        heal(database);
        if (own.isEmpty()) return new Result(List.of(), 0, 0);
        Set<Path> freshSet = new HashSet<>(own);
        for (Path f : fresh) freshSet.add(f.toAbsolutePath().normalize());
        List<Entry> entries = new ArrayList<>();
        long removed = 0;
        try (Connection duck = open(); Statement st = duck.createStatement()) {
            for (Path f : ArrivalCompactor.parquetFiles(database)) {
                if (freshSet.contains(f)) continue;
                try (ResultSet rs = st.executeQuery("SELECT count(*) FROM " + source(List.of(f)))) {
                    rs.next();
                    removed += rs.getLong(1);
                }
                entries.add(new Entry(f, null));
            }
        } catch (SQLException failed) {
            throw new IOException("the store could not be replaced: " + failed.getMessage(), failed);
        }
        if (entries.isEmpty()) return new Result(List.of(), 0, 0);
        swapAll(entries, own, null, null);   // the merge map falls with its merged file (trusted only while it exists)
        return new Result(List.of(), removed, entries.size());
    }

    /**
     * Put every original back (the import failed after {@link #replacePeriod}), with the merge map, and remove the arrival's
     * own rows ({@code covering}): no day is left doubled. Crash-safe through the journal; a no-op after {@link #drop}.
     */
    public void restore() throws IOException {
        if (done) return;
        done = true;
        Map<String, Object> j = read(database);
        if (j == null || !aside.getFileName().toString().equals(j.get("aside"))) {
            if (covering.isEmpty()) return;
            if (j != null) {   // an older replace's journal its heal could not finish: left to it; only this arrival's rows go
                try (SnapshotSwapLock.Held lock = SnapshotSwapLock.write(database)) {
                    for (Path c : covering) Files.deleteIfExists(c);
                }
                return;
            }
            j = journal(RESTORING, database, aside, List.of(), covering, null, null);   // nothing was swapped: only the arrival's rows go
        }
        j.put("phase", RESTORING);
        write(database, j);
        back(database, j);
    }

    /**
     * The import succeeded: mark every file whose rows were replaced {@code REPLACED} in the registry, then discard the
     * originals. Never throws: a failure leaves the journal, and the next {@link #heal} finishes it. A no-op after
     * {@link #restore}.
     */
    public void drop() {
        if (done) return;
        done = true;
        try {
            Map<String, Object> j = read(database);
            if (j != null) finish(database, j);
        } catch (IOException | RuntimeException failed) {
            log.warn("Replace the period of '{}' succeeded, but its originals were not discarded yet (the next run does it): {}",
                    base.getFileName(), failed.toString());
        }
    }

    // ── replace everything ─────────────────────────────────────────────────────────────────────────────────────

    /**
     * <i>Replace everything</i> succeeded: mark every Parquet file it set aside ({@code setAside}, the old {@code database}
     * folder moved whole) {@code REPLACED} by its path in {@code database}, unless the run wrote that path again. Best
     * effort, like the registry itself.
     */
    public static void everythingReplaced(Path setAside, Path database) {
        try {
            Path from = setAside.toAbsolutePath().normalize(), to = database.toAbsolutePath().normalize();
            List<String> gone = new ArrayList<>();
            for (Path p : ArrivalCompactor.parquetFiles(from)) {
                Path original = to.resolve(from.relativize(p).toString());
                if (!Files.exists(original)) gone.add(original.toString());
            }
            ConsignmentOutputStores.markReplaced(gone);
        } catch (IOException | RuntimeException failed) {
            log.warn("the files Replace everything set aside in '{}' were not marked replaced: {}", database, failed.toString());
        }
    }

    // ── the heal ───────────────────────────────────────────────────────────────────────────────────────────────

    /** Finish a <i>Replace the period</i> of Dataset {@code id} that a crash interrupted (see the class javadoc). */
    public static void heal(Path dataRoot, String id) throws IOException {
        heal(dataRoot.resolve(id).resolve("database"));
    }

    /** Finish a replacement in the store folder {@code store} that a crash interrupted (see the class javadoc). */
    public static void heal(Path store) throws IOException {
        Path database = store.toAbsolutePath().normalize();
        Map<String, Object> j = read(database);
        if (j == null) return;
        String phase = String.valueOf(j.get("phase"));
        if (RESTORING.equals(phase)) {
            back(database, j);
            return;
        }
        if (SWAP.equals(phase)) {
            Path aside = aside(database, j);
            List<Entry> entries = entries(database, j);
            for (Entry e : entries)
                if (!(e.tmp() == null || Files.exists(e.tmp()) || Files.exists(kept(database, aside, e)) && Files.exists(e.file()))) {
                    log.warn("Replace the period of '{}' was interrupted and a rewrite is lost: the originals are put back",
                            name(database));
                    j.put("phase", RESTORING);
                    write(database, j);
                    back(database, j);
                    return;
                }
            swap(database, aside, entries);
            if (j.get("merged") instanceof String m)
                ArrivalCompactor.segmentsRewritten(database.getParent(), inside(database, m), segments(j));
            j.put("phase", SWAPPED);
            write(database, j);
        }
        finish(database, j);
    }

    // ── the steps both ways share ──────────────────────────────────────────────────────────────────────────────

    /** Each original aside, its rewrite in its place - all under the store's swap lock. Idempotent (the heal repeats it). */
    private static void swap(Path database, Path aside, List<Entry> entries) throws IOException {
        // the touched paths are gone between the two moves, for good when a file empties: wait for the readers that pinned them
        try (SnapshotSwapLock.Held lock = SnapshotSwapLock.write(database)) {
            for (Entry e : entries) {
                Path kept = kept(database, aside, e);
                if (!Files.exists(kept) && Files.exists(e.file())) {
                    Files.createDirectories(kept.getParent());
                    Files.move(e.file(), kept, StandardCopyOption.ATOMIC_MOVE);
                }
                if (e.tmp() != null && Files.exists(e.tmp()) && !Files.exists(e.file()))
                    Files.move(e.tmp(), e.file(), StandardCopyOption.ATOMIC_MOVE);
                checkpoint("swap");
            }
        }
    }

    /** Back to the old state: originals in place, rewrites gone, the merge map as it was, the arrival's own rows gone. */
    private static void back(Path database, Map<String, Object> j) throws IOException {
        Path base = database.getParent();
        Path aside = aside(database, j);
        List<Entry> entries = entries(database, j);
        try (SnapshotSwapLock.Held lock = SnapshotSwapLock.write(database)) {   // deletes files a reader may hold
            for (int i = entries.size() - 1; i >= 0; i--) {
                Entry e = entries.get(i);
                Path kept = kept(database, aside, e);
                if (Files.exists(kept)) {
                    Files.deleteIfExists(e.file());
                    Files.move(kept, e.file(), StandardCopyOption.ATOMIC_MOVE);
                }
                if (e.tmp() != null) Files.deleteIfExists(e.tmp());
                checkpoint("restore");
            }
            // only the arrival's own output: another file the run took is already marked processed, so its rows stay
            if (j.get("covering") instanceof List<?> own)
                for (Object o : own) Files.deleteIfExists(inside(database, String.valueOf(o)));
        }
        Path before = aside.resolve(MAP_BEFORE);
        if (Files.isRegularFile(before)) AtomicFiles.write(base.resolve(ArrivalCompactor.MAP), Files.readAllBytes(before), ".cmp-");
        deleteTree(aside);
        Files.deleteIfExists(journalFile(database));
    }

    /** Forward to the end: the replaced files marked in the registry (idempotent), the originals discarded, the journal gone. */
    private static void finish(Path database, Map<String, Object> j) throws IOException {
        List<String> replaced = new ArrayList<>();
        for (Entry e : entries(database, j)) replaced.add(e.file().toString());
        ConsignmentOutputStores.markReplaced(replaced);
        checkpoint("drop");
        deleteTree(aside(database, j));
        Files.deleteIfExists(journalFile(database));
    }

    // ── the journal ────────────────────────────────────────────────────────────────────────────────────────────

    private static Map<String, Object> journal(String phase, Path database, Path aside, List<Entry> entries, List<Path> covering,
                                               Path merged, List<ArrivalCompactor.Segment> next) {
        Map<String, Object> j = new LinkedHashMap<>();
        j.put("phase", phase);
        j.put("aside", aside.getFileName().toString());
        List<Map<String, Object>> files = new ArrayList<>();
        for (Entry e : entries) {
            Map<String, Object> f = new LinkedHashMap<>();
            f.put("file", rel(database, e.file()));
            f.put("tmp", e.tmp() == null ? null : rel(database, e.tmp()));
            files.add(f);
        }
        j.put("files", files);
        j.put("covering", covering.stream().map(c -> rel(database, c)).toList());
        j.put("merged", merged == null ? null : rel(database, merged));
        j.put("segments", next == null ? List.of() : next.stream().map(ArrivalCompactor::asMap).toList());
        return j;
    }

    /** The journal of {@code database}: {@link #JOURNAL} beside an import's {@code database}, else named by the store. */
    static Path journalFile(Path database) {
        String store = database.getFileName().toString();
        return database.resolveSibling("database".equals(store) ? JOURNAL : store + "." + JOURNAL);
    }

    /** The Dataset (an import's {@code <id>/database}) or the store folder, as a log line names it. */
    private static String name(Path database) {
        return "database".equals(database.getFileName().toString()) ? database.getParent().getFileName().toString()
                : database.getFileName().toString();
    }

    private static void write(Path database, Map<String, Object> j) throws IOException {
        AtomicFiles.write(journalFile(database), JSON.writeValueAsBytes(j), ".period-");
    }

    /** The journal, or null when there is none; an unreadable one is refused, never guessed at. */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> read(Path database) throws IOException {
        Path f = journalFile(database);
        if (!Files.isRegularFile(f)) return null;
        try {
            return new LinkedHashMap<>(JSON.readValue(f.toFile(), Map.class));
        } catch (IOException unreadable) {
            throw new IOException("the last replacement in '" + name(database) + "' cannot be finished: its journal "
                    + f.getFileName() + " is unreadable (" + unreadable.getMessage() + ")", unreadable);
        }
    }

    private static List<Entry> entries(Path database, Map<String, Object> j) {
        List<Entry> out = new ArrayList<>();
        if (j.get("files") instanceof List<?> files)
            for (Object o : files)
                if (o instanceof Map<?, ?> m && m.get("file") instanceof String f)
                    out.add(new Entry(inside(database, f), m.get("tmp") instanceof String t ? inside(database, t) : null));
        return out;
    }

    @SuppressWarnings("unchecked")
    private static List<ArrivalCompactor.Segment> segments(Map<String, Object> j) {
        Map<String, Object> m = new HashMap<>();
        m.put("arrivals", j.get("segments"));
        return ArrivalCompactor.segments(m);
    }

    /** The journal's aside folder: a sibling of {@code database} named {@code <store>.period-<stamp>}, never anywhere else. */
    private static Path aside(Path database, Map<String, Object> j) throws IOException {
        String name = String.valueOf(j.get("aside"));
        String prefix = database.getFileName() + ".period-";
        if (!name.startsWith(prefix) || !STAMP.matcher(name.substring(prefix.length())).matches())
            throw new IOException("the journal of '" + name(database) + "' names no aside folder");
        return database.resolveSibling(name);
    }

    private static Path kept(Path database, Path aside, Entry e) {
        return aside.resolve(database.relativize(e.file()).toString());
    }

    /** {@code rel} under {@code database}; a path that would leave it is refused. */
    private static Path inside(Path database, String rel) {
        Path p = database.resolve(rel).normalize();
        if (!p.startsWith(database)) throw new IllegalArgumentException("the journal names a file outside the Dataset: " + rel);
        return p;
    }

    private static String rel(Path database, Path p) {
        return database.relativize(p.toAbsolutePath().normalize()).toString().replace('\\', '/');
    }

    // ── helpers ────────────────────────────────────────────────────────────────────────────────────────────────

    private static void discard(List<Entry> entries) {
        for (Entry e : entries)
            if (e.tmp() != null)
                try {
                    Files.deleteIfExists(e.tmp());
                } catch (IOException ignored) {
                    // a hidden leftover: no read sees it
                }
    }

    private static void deleteTree(Path dir) {
        if (!Files.exists(dir)) return;
        try (Stream<Path> w = Files.walk(dir)) {
            for (Path p : w.sorted(Comparator.reverseOrder()).toList())
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // a leftover beside the store: no read sees it
                }
        } catch (IOException ignored) {
            // same
        }
    }

    /** Test seam: called at each step a crash could interrupt ({@code swap}, {@code restore}, {@code drop}); null in production. */
    static volatile java.util.function.Consumer<String> checkpointForTest;

    private static void checkpoint(String where) {
        java.util.function.Consumer<String> c = checkpointForTest;
        if (c != null) c.accept(where);
    }

    /**
     * Each arrival's rows of the merged file {@code f} that {@code keep} keeps, by its segment ({@code file_row_number}
     * ranges), into {@code next} (new first rows, in order; the period over {@code col}; an arrival left with no row drops
     * out). False when a kept row falls in no segment: the map is then left to its stale check.
     */
    private boolean recount(Statement st, Path f, List<ArrivalCompactor.Segment> segs, String keep, String col,
                            List<ArrivalCompactor.Segment> next) throws SQLException {
        StringBuilder seg = new StringBuilder("CASE");
        for (int i = 0; i < segs.size(); i++)
            seg.append(" WHEN file_row_number >= ").append(segs.get(i).firstRow()).append(" AND file_row_number < ")
                    .append(segs.get(i).firstRow() + segs.get(i).rows()).append(" THEN ").append(i);
        seg.append(" ELSE -1 END");
        Map<Integer, Object[]> by = new HashMap<>();
        try (ResultSet rs = st.executeQuery("SELECT " + seg + " AS s, count(*), CAST(min(" + col + ") AS VARCHAR), CAST(max(" + col
                + ") AS VARCHAR) FROM " + numbered(f) + " WHERE " + keep + " GROUP BY 1")) {
            while (rs.next()) by.put(rs.getInt(1), new Object[] {rs.getLong(2), rs.getString(3), rs.getString(4)});
        }
        if (by.containsKey(-1)) return false;
        long first = 0;
        for (int i = 0; i < segs.size(); i++) {
            ArrivalCompactor.Segment s = segs.get(i);
            Object[] r = by.get(i);
            if (r == null) continue;
            long n = (Long) r[0];
            next.add(new ArrivalCompactor.Segment(s.file(), s.received(), first, n, (String) r[1], (String) r[2], s.holds()));
            first += n;
        }
        return true;
    }

    /** {@code f} read with each row's place in it ({@code file_row_number}); jailed like {@link #source}. */
    private String numbered(Path f) {
        source(List.of(f));
        return "read_parquet(" + SqlIdent.pathStr(f) + ", file_row_number = true)";
    }

    private Connection open() throws SQLException {
        try {
            DuckDbUtil.loadDriver();
        } catch (ClassNotFoundException missing) {
            throw new SQLException("DuckDB is not available", missing);
        }
        return DuckDbUtil.openInMemory(null, List.of(base));
    }

    private String source(List<Path> files) {
        for (Path f : files)
            if (!PathJail.contains(base, f.toAbsolutePath().normalize()))
                throw new IllegalArgumentException("a file outside the Dataset: " + f.getFileName());
        return SqlViews.parquet(SqlViews.pathList(files.stream().map(Path::toString).toList()), true, SqlViews.Hive.OFF);
    }
}
