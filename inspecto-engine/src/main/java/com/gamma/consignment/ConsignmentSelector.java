package com.gamma.consignment;

import com.gamma.api.PublicApi;
import com.gamma.sql.SqlViews;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * <b>Consignment addressing §7-A — the Selector.</b> Turns a store's read glob into the read expression a
 * caller should actually use: the files the glob matches <em>at this instant</em>, minus the ones the
 * catalog says are no longer readable, pinned to an explicit list rather than left as a glob string.
 *
 * <p><b>It filters the glob; it never replaces it as the source of truth for what exists.</b> §1 wanted
 * the catalog to <em>produce</em> the file list, but {@link DbConsignmentOutputStore} is contractually
 * optional — its own javadoc says a store that can legitimately be absent must never be the only record
 * that a file exists. An optional index cannot be an existence oracle. So the filesystem stays the
 * authority for what exists — this class only ever <em>enumerates and subtracts</em> — and the pinning
 * below is a snapshot of that enumeration, not a second source of existence:
 *
 * <pre>{@code resolve(glob) = enumerate(glob) MINUS paths the catalog marks SUPERSEDED / COMPACTED_AWAY,
 *              pinned to the enumerated instant}</pre>
 *
 * <p>A file with <b>no</b> catalog row stays in. That is decision D3 — unknown is a possible match, never an
 * exclusion — applied to the file list rather than only to the bounds, and it is what makes this safe to switch
 * on everywhere: a deployment whose registry is off, or predates the files on disk, reads exactly what it read
 * before. Nothing needs backfilling.
 *
 * <p><b>Fail-open when there is no registry, or enumeration itself fails.</b> Either way the caller gets the
 * plain glob expression it would have built itself — byte for byte, not merely equivalent — because there is
 * nothing this class can safely pin in that case.
 *
 * <p><b>Always pins once a registry exists — 2026-08-29, closing the torn-read gap this class's javadoc used
 * to describe as still-open.</b> Earlier, an empty exclusion set fell back to the caller's own live glob
 * string, on the reasoning that the common path should be "provably a no-op." That saved SQL size, but it
 * meant DuckDB re-expanded the glob against the live filesystem at actual scan time — so a file written by a
 * concurrent recompute between this class's enumeration and the query's own scan was silently pulled into a
 * read the catalog never approved, exactly the torn read the old javadoc named. Pinning the enumerated list
 * unconditionally closes that window: whatever this class saw at resolve time is exactly what the read scans,
 * whether or not anything needed excluding. ⚠ The tradeoff, accepted deliberately: the SQL always carries an
 * explicit file array now (no longer a glob string in the common case), and a list pinned across a long-lived
 * read can fail loudly if {@code retire_superseded} deletes one of its files out from under it mid-read,
 * where the old glob fallback would have silently re-scanned around it. A loud failure on an actually-deleted
 * file is judged the lesser risk against a silent, wrong answer from an unapproved file appearing mid-scan.
 */
@PublicApi(since = "4.0.0")
public final class ConsignmentSelector {

    private static final Logger log = LoggerFactory.getLogger(ConsignmentSelector.class);

    private ConsignmentSelector() {}

    /**
     * The read expression for {@code glob}: the files it matches right now, pinned to an explicit list with
     * anything the catalog marks unreadable already subtracted — or, when there is no registry to ask or
     * enumeration itself fails, the plain glob expression the caller would have built unfiltered.
     *
     * @param conn   the connection the read will run on — used only to enumerate the glob, through DuckDB's own
     *               {@code glob()} table function rather than a filesystem walk, so the file set this selects
     *               from is exactly the one the unfiltered read would have seen
     * @param format {@code "PARQUET"} or {@code "CSV"}, as {@link SqlViews#reader(String, String, boolean)}
     * @param glob   the glob the caller would otherwise have read
     * @param hive   whether to enable Hive partitioning, passed straight through
     */
    public static String resolve(Connection conn, String format, String glob, boolean hive) {
        return resolveWithFiles(conn, format, glob, hive).reader();
    }

    /**
     * What {@link #resolve} decided, plus the files it decided on.
     *
     * @param reader the SQL source the read should use
     * @param kept   the files the reader will actually scan, or {@code null} when the selector had nothing
     *               to say (no output registry, or the enumeration failed) and the glob is read UNFILTERED —
     *               the set of files is then genuinely unknown to the caller and must be recorded as
     *               unknown, never as empty
     */
    public record Resolution(String reader, List<String> kept) {}

    /**
     * As {@link #resolve}, exposing the kept file list — the seam cross-lane provenance (X2) reads: the
     * at-rest run records the Consignments whose files it actually scanned, which is exactly this list
     * mapped back through the output registry, not "every Consignment under the store".
     */
    public static Resolution resolveWithFiles(Connection conn, String format, String glob, boolean hive) {
        List<String> kept = select(conn, glob);
        return new Resolution(kept == null ? SqlViews.reader(format, glob, hive) : SqlViews.reader(format, kept, hive), kept);
    }

    /**
     * The SQL <b>source literal</b> for reading {@code root}'s {@code .ext} files: a bracketed, pinned list of
     * whatever currently matches minus anything the catalog marks unreadable, or the quoted
     * {@code root}{@code /**}{@code /*.ext} glob when there is no registry to ask or the walk itself fails. For
     * the callers that have no {@link Connection}: {@code DatasetRelation} hands the literal straight to
     * {@link SqlViews#readerOverLiteral}, so it decides the source without deciding the read options.
     *
     * <p>The glob is built here rather than accepted, so the pattern this enumerates and the pattern it falls
     * back to cannot drift apart.
     */
    public static String sourceLiteral(String root, String ext) {
        String glob = root.replace("\\", "/") + "/**/*." + ext;
        List<String> kept = select(root, ext);
        return kept == null ? "'" + glob + "'" : SqlViews.pathList(kept);
    }

    /**
     * The files {@code glob} currently matches, with anything the catalog marks unreadable already
     * subtracted — pinned at this call, so a file appearing after this point never reaches the read this
     * builds. {@code null} only when the caller should just use the glob unfiltered: no registry to ask, or
     * enumeration itself failed.
     */
    static List<String> select(Connection conn, String glob) {
        Set<String> excluded = excluded();
        if (excluded == null) return null;

        List<String> kept = new ArrayList<>();
        int removed = 0;
        try (Statement st = conn.createStatement();
             // Escape the quote as well as normalising separators: this catch fails OPEN (reads
             // unfiltered), so a configured root containing a single quote would silently let a
             // superseded file back into a read — the exact staleness this class exists to prevent.
             ResultSet rs = st.executeQuery(
                     "SELECT file FROM glob('" + glob.replace("\\", "/").replace("'", "''") + "')")) {
            while (rs.next()) {
                String file = rs.getString(1);
                if (file == null) continue;
                if (excluded.contains(DbConsignmentOutputStore.norm(file))) removed++;
                else kept.add(file);
            }
        } catch (SQLException e) {
            log.warn("Consignment selector could not enumerate {} — reading it unfiltered: {}",
                    glob, e.getMessage());
            return null;
        }
        return report(removed, kept, glob);
    }

    /**
     * {@link #select(Connection, String)} without a connection: walks {@code root} for {@code .ext} files.
     *
     * <p><b>Why a second enumerator exists.</b> The connection form asks DuckDB to expand the very glob the
     * read will use, so the two can never disagree about what exists — that is the better mechanism and stays
     * the default where a connection is in scope. But only one of the seven readers in the product takes a
     * {@link Connection}; the rest are config→SQL string builders, and threading one into
     * {@code DatasetRelation} would change a public signature with call sites in three modules. A walk is not
     * new coupling for those callers — {@link SqlViews#storeReadRoot} already stats the filesystem on the same
     * line — but it is an approximation of DuckDB's glob, so it is kept to exactly the shape all seven use.
     *
     * <p><b>Hidden segments are skipped</b>, which is not cosmetic: {@code PartitionCompactor}'s safety model
     * depends on its intermediates being invisible to readers' globs, and a walk that picked up a
     * {@code .staging/} tree DuckDB would not have matched would make data appear in reads that never used to
     * be there. Extension filtering already excludes {@code *.compact.tmp} and {@code *.parquet.compacting};
     * this covers the directory half.
     */
    static List<String> select(String root, String ext) {
        Set<String> excluded = excluded();
        if (excluded == null) return null;

        java.nio.file.Path base = java.nio.file.Path.of(root);
        if (!java.nio.file.Files.isDirectory(base)) return null;

        List<String> kept = new ArrayList<>();
        int removed = 0;
        try (java.util.stream.Stream<java.nio.file.Path> walk = java.nio.file.Files.walk(base)) {
            for (java.nio.file.Path p : walk.filter(java.nio.file.Files::isRegularFile).toList()) {
                if (!p.getFileName().toString().endsWith("." + ext) || hasHiddenSegment(base, p)) continue;
                String file = p.toString().replace("\\", "/");
                if (excluded.contains(DbConsignmentOutputStore.norm(file))) removed++;
                else kept.add(file);
            }
        } catch (java.io.IOException e) {
            log.warn("Consignment selector could not walk {} — reading it unfiltered: {}", root, e.getMessage());
            return null;
        }
        return report(removed, kept, root);
    }

    /** Whether any segment of {@code file} below {@code base} starts with a dot — see the walk's javadoc. */
    private static boolean hasHiddenSegment(java.nio.file.Path base, java.nio.file.Path file) {
        for (java.nio.file.Path seg : base.relativize(file))
            if (seg.toString().startsWith(".")) return true;
        return false;
    }

    /**
     * The normalised set of paths the catalog says are unreadable — possibly empty — or {@code null} only
     * when there is no registry to ask at all. Both enumerators bail on {@code null} before touching a
     * filesystem or a connection; an <b>empty</b> set still triggers enumeration, because pinning what exists
     * right now is the point even when nothing needs excluding (2026-08-29 — see the class javadoc).
     *
     * <p>Normalised on both sides because the registry stores the writer's own spelling, which may be relative,
     * while an enumerator answers in its own. Comparing raw would match nothing and report success — the silent
     * failure {@code markCompactedAway} already had to defend against.
     */
    private static Set<String> excluded() {
        DbConsignmentOutputStore store = ConsignmentOutputStores.shared();
        if (store == null) return null;
        Set<String> normalised = new HashSet<>();
        for (String path : store.unreadablePaths()) normalised.add(DbConsignmentOutputStore.norm(path));
        return normalised;
    }

    /**
     * {@code kept}, always — the pin — logging only when something was actually excluded, since an empty
     * exclusion is the ordinary case and not worth a line every read.
     */
    private static List<String> report(int removed, List<String> kept, String what) {
        if (removed > 0)
            log.debug("Consignment selector: {} of {} file(s) excluded under {}", removed, removed + kept.size(), what);
        return kept;
    }
}
