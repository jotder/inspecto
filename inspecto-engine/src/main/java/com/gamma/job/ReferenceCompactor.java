package com.gamma.job;

import com.gamma.util.DuckDbUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * The {@code reference_compact} maintenance task (Reference Phase-2 P3): bound the read amplification of
 * an append-only versioned Reference store by rewriting it to just the rows its read views can still
 * return. The P1/P2 write path reveals one file per batch per partition dir
 * ({@code <base>__v_<batchId>_out.parquet}), so a frequently-refreshed reference accumulates files
 * indefinitely even though {@link com.gamma.enrich.EnrichmentEngine}'s current view only ever surfaces the
 * latest version per {@code __key_hash}. Compaction makes that derived view the physical truth — its
 * output <em>is</em> the "cache" the fast path reads between refreshes.
 *
 * <h3>Params</h3>
 * {@code dir} (required — the store root, i.e. the producer pipeline's {@code dirs.database}) ·
 * {@code history_days} (default {@code 0}).
 *
 * <p>{@code history_days} selects what survives:
 * <ul>
 *   <li><b>{@code 0}</b> (default) — only the winning version per key, and tombstoned keys are
 *       <b>dropped outright</b> (the current view already hides them, and a later re-delivery simply
 *       upserts again). The right setting for {@code load: upsert}, which has no history worth keeping.</li>
 *   <li><b>{@code > 0}</b> — the winners plus every version whose {@code __valid_from} falls inside the
 *       horizon, so {@code load: scd2} as-of reads inside that window keep working while older history is
 *       deliberately forgotten.</li>
 *   <li><b>negative</b> — <b>keep-forever</b>: no version is dropped, files are merely merged. This is the
 *       D4 default for {@code load: scd2}: as-of stays answerable over the whole history, and compaction
 *       still earns its keep, because read amplification is a function of <em>file count</em>, not row
 *       count — one file per batch per partition is the cost being paid down.</li>
 * </ul>
 *
 * <h3>Why the winner is computed store-wide, not per directory</h3>
 * A dimension row whose <em>partition</em> value changed has its versions in <b>different</b> partition
 * dirs, so a per-dir "keep the latest here" would resurrect a superseded version. The retained set is
 * therefore derived once over the whole store, and each partition dir is then rewritten to its own slice
 * of that set (a dir left with no retained rows loses its files entirely).
 *
 * <h3>Safety model</h3>
 * Identical to {@link PartitionCompactor} — and for the same reason (there is no lock between jobs and
 * ingest): every intermediate ({@code *.refcompact.tmp}, hidden originals {@code *.parquet.refcompacting},
 * the {@code .refcompact-journal} sentinel) is invisible to the readers' {@code *.parquet} glob, the merged
 * file appears with a single {@code ATOMIC_MOVE}, and a crash is repaired from the journal by {@link #heal}
 * on the next run — either finishing the swap or restoring the hidden originals, never losing or
 * duplicating rows. Unlike {@code compact} there is no age cutoff: a concurrent batch commit only ever
 * creates a <em>new</em> uniquely-named file, which is simply not in this run's candidate set and survives
 * untouched (it wins the next current-view derivation on its own merits).
 */
public final class ReferenceCompactor {

    private static final Logger log = LoggerFactory.getLogger(ReferenceCompactor.class);
    private static final String JOURNAL = ".refcompact-journal";
    private static final String HIDDEN_SUFFIX = ".refcompacting";
    private static final String TMP_SUFFIX = ".refcompact.tmp";
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** What one compaction pass did. {@code rowsRetained} is the size of the compacted store. */
    public record Result(int dirsCompacted, int filesMerged, long rowsRetained) {
        static final Result NOTHING = new Result(0, 0, 0L);
        public String describe(Path root) {
            return "reference_compact: " + filesMerged + " file(s) → " + dirsCompacted
                    + " partition dir(s), " + rowsRetained + " row(s) retained under " + root;
        }
    }

    private ReferenceCompactor() {}

    /** {@code reference_compact} maintenance-task adapter (see class doc for the params). */
    static JobResult run(JobConfig cfg) throws Exception {
        Path root = Path.of(cfg.require("dir"));
        long historyDays = Long.parseLong(cfg.opt("history_days", "0"));
        long t0 = System.nanoTime();
        Result r = compact(root, historyDays);
        return JobResult.ok(r.describe(root), (System.nanoTime() - t0) / 1_000_000L);
    }

    /**
     * Compact the versioned Reference store rooted at {@code root}, keeping the winning version per key
     * plus (when {@code historyDays > 0}) every version inside that horizon. Idempotent: a second pass
     * over an already-compacted store finds nothing to merge.
     *
     * @param root        the store root ({@code dirs.database} of the {@code produces: reference} pipeline)
     * @param historyDays {@code 0} = current versions only, tombstones dropped; {@code > 0} = also keep
     *                    versions newer than the horizon (scd2 as-of stays answerable inside it);
     *                    negative = keep every version, merging files only (scd2 keep-forever, D4)
     */
    public static Result compact(Path root, long historyDays) throws Exception {
        if (!Files.isDirectory(root)) return Result.NOTHING;
        // Heal EVERY directory before looking for work: a dir a crashed run left mid-swap has all of its
        // files hidden, so it has no live *.parquet — healing only the dirs that still look populated would
        // leave those versions invisible AND out of the store-wide retained set, silently losing rows.
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path dir : (Iterable<Path>) walk.filter(Files::isDirectory)::iterator) heal(dir);
        }
        List<Path> dirs = partitionDirs(root);
        if (dirs.isEmpty()) return Result.NOTHING;

        DuckDbUtil.loadDriver();
        try (Connection conn = DriverManager.getConnection("jdbc:duckdb:")) {

            long retained = stageRetained(conn, root, historyDays);
            int dirsCompacted = 0, filesMerged = 0;
            for (Path dir : dirs) {
                List<Path> candidates = candidates(dir);
                if (candidates.isEmpty()) continue;
                filesMerged += compactDir(conn, root, dir, candidates);
                dirsCompacted++;
            }
            return new Result(dirsCompacted, filesMerged, retained);
        }
    }

    /**
     * Materialise the retained row set over the <b>whole</b> store into {@code __retained} and return its
     * size. The window mirrors {@code EnrichmentEngine.versionedView}'s exactly — same partition key, same
     * ordering — so "compacted store" and "current view" cannot drift apart.
     */
    private static long stageRetained(Connection conn, Path root, long historyDays) throws Exception {
        String keep;
        if (historyDays < 0) keep = "TRUE";                       // keep-forever: merge files, drop nothing
        else if (historyDays > 0) keep = "__refc_rn = 1 OR __valid_from >= TIMESTAMP '"
                + LocalDateTime.ofInstant(Instant.now().minus(Duration.ofDays(historyDays)),
                                          ZoneId.systemDefault()).format(TS) + "'";
        else keep = "__refc_rn = 1 AND __op != 'delete'";         // winners only; a winning tombstone goes
        String sql = "CREATE TABLE __retained AS WITH __v AS (SELECT *, "
                + "row_number() OVER (PARTITION BY __key_hash ORDER BY __valid_from DESC) AS __refc_rn "
                + "FROM read_parquet('" + glob(root) + "', hive_partitioning=true, hive_types_autocast=0)) "
                + "SELECT * FROM __v WHERE " + keep;
        try (Statement st = conn.createStatement()) {
            st.execute(sql);
            try (ResultSet rs = st.executeQuery("SELECT count(*) FROM __retained")) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    /**
     * Rewrite {@code dir} to its slice of the retained set: journal → write the slice to a glob-invisible
     * temp → hide the originals → reveal → clean. A slice of zero rows reveals nothing and simply drops
     * the directory's files (every version there was superseded from another partition).
     */
    private static int compactDir(Connection conn, Path root, Path dir, List<Path> candidates) throws Exception {
        String target = "compacted_" + System.currentTimeMillis() + "_out.parquet";
        Path tmp = dir.resolve(target + TMP_SUFFIX);
        Path journal = dir.resolve(JOURNAL);

        List<String> partCols = new ArrayList<>();
        String where = partitionPredicate(root, dir, partCols);

        // 1. Journal first: target + originals, so heal() can always finish or undo this directory.
        List<String> lines = new ArrayList<>();
        lines.add(target);
        candidates.forEach(p -> lines.add(p.getFileName().toString()));
        Files.write(journal, lines);

        try {
            // 2. Write this dir's slice, minus the row-number helper and the path-encoded partition
            //    columns (hive partition values live in the directory name, never in the file).
            String exclude = "__refc_rn" + partCols.stream().map(c -> ", " + quote(c)).reduce("", String::concat);
            long rows;
            try (Statement st = conn.createStatement()) {
                st.execute("COPY (SELECT * EXCLUDE (" + exclude + ") FROM __retained WHERE " + where
                        + ") TO '" + sqlPath(tmp) + "' (FORMAT PARQUET)");
                try (ResultSet rs = st.executeQuery(
                        "SELECT count(*) FROM read_parquet('" + sqlPath(tmp) + "')")) {
                    rs.next();
                    rows = rs.getLong(1);
                }
            }

            // 3. Hide the originals (atomic per file; readers' *.parquet globs no longer see them) …
            for (Path p : candidates)
                Files.move(p, sibling(p, HIDDEN_SUFFIX), StandardCopyOption.ATOMIC_MOVE);
            // 4. … reveal the compacted slice in one atomic rename (nothing to reveal when it is empty) …
            if (rows > 0) Files.move(tmp, dir.resolve(target), StandardCopyOption.ATOMIC_MOVE);
            else Files.deleteIfExists(tmp);
            // 5. … then the hidden originals and the journal can go.
            for (Path p : candidates) Files.deleteIfExists(sibling(p, HIDDEN_SUFFIX));
            Files.deleteIfExists(journal);
            return candidates.size();
        } catch (Exception e) {
            heal(dir);   // undo/finish from the journal so the directory is never left half-swapped
            throw e;
        }
    }

    /**
     * The {@code WHERE} restricting the retained set to {@code dir}'s Hive partition, collecting the
     * partition column names into {@code partCols}. An unpartitioned store (files straight under the
     * root) yields {@code TRUE} — {@link #partitionDirs} guarantees the two layouts never mix.
     */
    private static String partitionPredicate(Path root, Path dir, List<String> partCols) {
        StringBuilder where = new StringBuilder();
        for (Path seg : root.relativize(dir)) {
            String s = seg.toString();
            int eq = s.indexOf('=');
            if (eq <= 0) continue;
            String col = s.substring(0, eq), val = s.substring(eq + 1);
            partCols.add(col);
            if (!where.isEmpty()) where.append(" AND ");
            where.append(quote(col)).append(" = '").append(val.replace("'", "''")).append('\'');
        }
        return where.isEmpty() ? "TRUE" : where.toString();
    }

    /**
     * The directories holding this store's live files: every dir with direct-child {@code *.parquet}.
     * Hive-partitioned leaves ({@code k=v/…}) win when present — a root-level file alongside them is an
     * unpartitioned stray whose {@code TRUE} predicate would duplicate the whole retained set into it, so
     * it is skipped with a warning rather than silently corrupting the store.
     */
    private static List<Path> partitionDirs(Path root) throws IOException {
        List<Path> all = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path dir : (Iterable<Path>) walk.filter(Files::isDirectory)::iterator)
                if (!candidates(dir).isEmpty()) all.add(dir);
        }
        boolean partitioned = all.stream().anyMatch(d -> isPartitioned(root, d));
        if (!partitioned) return all;
        List<Path> out = new ArrayList<>();
        for (Path d : all) {
            if (isPartitioned(root, d)) out.add(d);
            else log.warn("reference_compact: skipping unpartitioned files in {} — the store is "
                    + "Hive-partitioned, so these are strays", d);
        }
        return out;
    }

    private static boolean isPartitioned(Path root, Path dir) {
        for (Path seg : root.relativize(dir)) if (seg.toString().indexOf('=') > 0) return true;
        return false;
    }

    /** The compactable files in {@code dir}: direct-child {@code *.parquet} (no age cutoff — see class doc). */
    private static List<Path> candidates(Path dir) throws IOException {
        List<Path> out = new ArrayList<>();
        try (Stream<Path> files = Files.list(dir)) {
            for (Path p : (Iterable<Path>) files::iterator)
                if (Files.isRegularFile(p) && p.getFileName().toString().endsWith(".parquet")) out.add(p);
        }
        return out;
    }

    /**
     * Recover {@code dir} from an interrupted compaction using its journal: if the compacted slice was
     * already revealed, finish deleting the hidden originals; otherwise restore them and discard the temp.
     * No journal (the normal case) — only stray temps to sweep.
     */
    private static void heal(Path dir) throws IOException {
        Path journal = dir.resolve(JOURNAL);
        if (Files.exists(journal)) {
            List<String> lines = Files.readAllLines(journal);
            String target = lines.isEmpty() ? null : lines.get(0);
            boolean revealed = target != null && Files.exists(dir.resolve(target));
            for (String name : lines.subList(lines.isEmpty() ? 0 : 1, lines.size())) {
                Path hidden = dir.resolve(name + HIDDEN_SUFFIX);
                if (revealed) Files.deleteIfExists(hidden);                              // finish the swap
                else if (Files.exists(hidden))                                           // undo it
                    Files.move(hidden, dir.resolve(name), StandardCopyOption.REPLACE_EXISTING);
            }
            if (target != null) Files.deleteIfExists(dir.resolve(target + TMP_SUFFIX));
            Files.deleteIfExists(journal);
            log.warn("reference_compact: healed interrupted compaction in {} ({})", dir,
                    revealed ? "finished swap" : "restored originals");
        } else {
            try (Stream<Path> files = Files.list(dir)) {
                for (Path p : (Iterable<Path>) files::iterator)
                    if (p.getFileName().toString().endsWith(TMP_SUFFIX)) Files.deleteIfExists(p);
            }
        }
    }

    private static Path sibling(Path p, String suffix) {
        return p.resolveSibling(p.getFileName().toString() + suffix);
    }

    private static String glob(Path root) {
        return sqlPath(root) + "/**/*.parquet";
    }

    private static String sqlPath(Path p) {
        return p.toAbsolutePath().toString().replace('\\', '/').replace("'", "''");
    }

    private static String quote(String ident) {
        return "\"" + ident.replace("\"", "\"\"") + "\"";
    }
}
