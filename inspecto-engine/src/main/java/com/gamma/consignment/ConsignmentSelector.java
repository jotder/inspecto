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
     * The files {@code glob} matches that the catalog has not marked unreadable, or {@code null} when the
     * caller should just use the glob — no registry, nothing excluded, or the enumeration failed.
     *
     * <p>{@code null} rather than "all the files", deliberately: it lets {@link #resolve} hand back the
     * caller's original expression unchanged instead of an equivalent-but-different list of every file, so the
     * common path is provably a no-op rather than a reimplementation of one.
     */
    static List<String> select(Connection conn, String glob) {
        DbConsignmentOutputStore store = ConsignmentOutputStores.shared();
        if (store == null) return null;

        List<String> unreadable = store.unreadablePaths();
        if (unreadable.isEmpty()) return null;

        Set<String> excluded = new HashSet<>();
        for (String path : unreadable) excluded.add(DbConsignmentOutputStore.norm(path));

        List<String> kept = new ArrayList<>();
        int removed = 0;
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT file FROM glob('" + glob.replace("\\", "/") + "')")) {
            while (rs.next()) {
                String file = rs.getString(1);
                if (file == null) continue;
                // Both sides through the same normaliser: the registry stores the writer's own spelling, which
                // may be relative, while glob() answers in DuckDB's. Comparing raw would match nothing and
                // report success — the silent failure markCompactedAway already had to defend against.
                if (excluded.contains(DbConsignmentOutputStore.norm(file))) removed++;
                else kept.add(file);
            }
        } catch (SQLException e) {
            log.warn("Consignment selector could not enumerate {} — reading it unfiltered: {}",
                    glob, e.getMessage());
            return null;
        }
        if (removed == 0) return null;

        log.debug("Consignment selector: {} of {} file(s) excluded under {}",
                removed, removed + kept.size(), glob);
        return kept;
    }
}
