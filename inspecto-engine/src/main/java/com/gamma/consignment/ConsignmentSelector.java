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
 * caller should actually use: the same glob, minus the files the catalog says are no longer readable.
 *
 * <p><b>It filters the glob; it never replaces it.</b> That is the whole design, and it is a correction to the
 * plan it implements. §1 wanted the catalog to <em>produce</em> the file list, but
 * {@link DbConsignmentOutputStore} is contractually optional — its own javadoc says a store that can
 * legitimately be absent must never be the only record that a file exists. An optional index cannot be an
 * existence oracle. So the filesystem stays the authority for what exists, and the catalog only ever
 * <em>subtracts</em>:
 *
 * <pre>{@code resolve(glob) = glob  MINUS  paths the catalog marks SUPERSEDED / COMPACTED_AWAY}</pre>
 *
 * <p>A file with <b>no</b> catalog row stays in. That is decision D3 — unknown is a possible match, never an
 * exclusion — applied to the file list rather than only to the bounds, and it is what makes this safe to switch
 * on everywhere: a deployment whose registry is off, or predates the files on disk, reads exactly what it read
 * before. Nothing needs backfilling.
 *
 * <p><b>Fail-open at every step.</b> No registry, nothing excluded, or any failure enumerating the glob, and
 * the caller gets the plain glob expression it would have built itself — byte for byte, not merely equivalent.
 * The SQL only changes shape when there is genuinely a file to leave out, which keeps the blast radius of this
 * change confined to the case it exists to fix.
 *
 * <p><b>What it does not do.</b> This is not generation pinning and must not be sold as it: the glob is
 * evaluated at resolve time, so a file revealed by a concurrent writer a moment later is simply not in the
 * list, and a reader holding this list across a recompute has a stale one rather than a consistent one.
 * Subtraction fixes <em>stale inclusion</em>. Torn multi-file reads are a separate, still-open defect (§7-A).
 */
@PublicApi(since = "5.0.0")
public final class ConsignmentSelector {

    private static final Logger log = LoggerFactory.getLogger(ConsignmentSelector.class);

    private ConsignmentSelector() {}

    /**
     * The read expression for {@code glob}, with unreadable files subtracted when the catalog knows of any.
     *
     * @param conn   the connection the read will run on — used only to enumerate the glob, through DuckDB's own
     *               {@code glob()} table function rather than a filesystem walk, so the file set this selects
     *               from is exactly the one the unfiltered read would have seen
     * @param format {@code "PARQUET"} or {@code "CSV"}, as {@link SqlViews#reader(String, String, boolean)}
     * @param glob   the glob the caller would otherwise have read
     * @param hive   whether to enable Hive partitioning, passed straight through
     */
    public static String resolve(Connection conn, String format, String glob, boolean hive) {
        List<String> kept = select(conn, glob);
        return kept == null ? SqlViews.reader(format, glob, hive) : SqlViews.reader(format, kept, hive);
    }

    /**
     * The SQL <b>source literal</b> for reading {@code root}'s {@code .ext} files — the quoted
     * {@code root}{@code /**}{@code /*.ext} glob when the catalog has nothing to exclude, else a bracketed list
     * of the survivors. For the callers that have no {@link Connection}: {@code DatasetRelation} builds its own
     * bare {@code read_parquet(<literal>)} with deliberately no other options, so it cannot go through
     * {@link SqlViews#reader}.
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
     * The files {@code glob} matches that the catalog has not marked unreadable, or {@code null} when the
     * caller should just use the glob — no registry, nothing excluded, or the enumeration failed.
     *
     * <p>{@code null} rather than "all the files", deliberately: it lets {@link #resolve} hand back the
     * caller's original expression unchanged instead of an equivalent-but-different list of every file, so the
     * common path is provably a no-op rather than a reimplementation of one.
     */
    static List<String> select(Connection conn, String glob) {
        Set<String> excluded = excluded();
        if (excluded == null) return null;

        List<String> kept = new ArrayList<>();
        int removed = 0;
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT file FROM glob('" + glob.replace("\\", "/") + "')")) {
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
     * The normalised set of paths the catalog says are unreadable, or {@code null} when there is nothing to do —
     * no registry, or a registry with no dead rows. Both enumerators bail on {@code null} before touching a
     * filesystem or a connection, so the default path costs one indexed query and nothing else.
     *
     * <p>Normalised on both sides because the registry stores the writer's own spelling, which may be relative,
     * while an enumerator answers in its own. Comparing raw would match nothing and report success — the silent
     * failure {@code markCompactedAway} already had to defend against.
     */
    private static Set<String> excluded() {
        DbConsignmentOutputStore store = ConsignmentOutputStores.shared();
        if (store == null) return null;
        List<String> unreadable = store.unreadablePaths();
        if (unreadable.isEmpty()) return null;
        Set<String> normalised = new HashSet<>();
        for (String path : unreadable) normalised.add(DbConsignmentOutputStore.norm(path));
        return normalised;
    }

    /** {@code kept} when anything was actually removed, else {@code null} so the caller keeps its own glob. */
    private static List<String> report(int removed, List<String> kept, String what) {
        if (removed == 0) return null;
        log.debug("Consignment selector: {} of {} file(s) excluded under {}", removed, removed + kept.size(), what);
        return kept;
    }
}
