package com.gamma.consignment;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Materialises the {@link DerivedTable}s a processor asked for and returns the registry rows describing
 * the files produced.
 *
 * <p><b>A pure writer</b>: it does <em>not</em> register anything. The caller does, so the ordering rule
 * — register only after the data is revealed — lives in one place. Same split as {@code SummaryWriter}.
 *
 * <p><b>Every registry field is filled, and each one earns its place:</b>
 * <ul>
 *   <li>{@code tableName} = {@code <name>}{@value #DERIVED_SUFFIX} — namespaced so a derived table can
 *       never collide with the sync tier's own table of the same name, mirroring the summary tier's
 *       {@code __summary};</li>
 *   <li>{@code producer} = the step that asked, so the row is attributable rather than anonymous;</li>
 *   <li>{@code partitionKey} — 🔴 what the {@code compact} maintenance task merges on and what
 *       {@code ConsignmentSelector} prunes with. Empty for a flat table, which is exactly the case where
 *       neither can help;</li>
 *   <li>{@code schemaFingerprint} — derived from the produced relation, never authored: the schema of a
 *       derived table <em>is</em> its SQL applied to the base, so asking anyone to restate it would invite
 *       drift;</li>
 *   <li>{@code state} = {@code LIVE}. Nothing else is correct at write time — {@code SUPERSEDED} arrives
 *       when the Consignment is reprocessed (keyed on the Consignment, so a derived table is swept with
 *       its base and needs no lineage edge), {@code COMPACTED_AWAY} when {@code compact} merges it.</li>
 * </ul>
 */
public final class DerivedTableWriter {

    /** Suffix on the registry's {@code table_name} for derived tables — the sibling of {@code __summary}. */
    public static final String DERIVED_SUFFIX = "__derived";

    private DerivedTableWriter() {}

    /**
     * Write every requested table under {@code derivedRoot}, returning the rows to register.
     *
     * @param reader       the framework's own reader — its sandbox carries the Consignment's views, which
     *                     is what the author's SQL names; nothing is retained on it
     * @param derivedRoot  the derived-table tree root, e.g. {@code <dataDir>/_derived}
     * @param consignmentId the unit of work, which names each file and owns each registry row
     * @param tables       requests already validated by {@link GuardedDerivedTableEmitter}
     * @param producer     what asked for them (the processor id), so the rows are attributable
     */
    public static List<ConsignmentOutput> write(ConsignmentReader reader, String derivedRoot,
                                                String consignmentId, List<DerivedTable> tables,
                                                String producer) throws Exception {
        if (tables == null || tables.isEmpty()) return List.of();
        // ⚠ The reader, not a bare Connection: the author's SQL names the Consignment's own lazy VIEWS,
        // which exist only on this sandbox. A fresh connection would resolve none of them.
        if (!(reader instanceof SandboxConsignmentReader sandboxed))
            throw new IllegalArgumentException("derived tables need the framework's own reader, got: "
                    + (reader == null ? "null" : reader.getClass().getName()));
        Connection conn = sandboxed.frameworkConnection();
        if (!GuardedDerivedTableEmitter.SAFE_NAME.matcher(consignmentId).matches())
            throw new IllegalArgumentException("unsafe consignment id: " + consignmentId);

        List<ConsignmentOutput> out = new ArrayList<>();
        String writtenAt = Instant.now().toString();
        for (DerivedTable t : tables) out.addAll(writeOne(conn, derivedRoot, consignmentId, t, writtenAt, producer));
        return out;
    }

    private static List<ConsignmentOutput> writeOne(Connection conn, String derivedRoot, String consignmentId,
                                                    DerivedTable table, String writtenAt, String producer)
            throws Exception {
        // A scratch relation first: the SELECT is evaluated ONCE, and every later step (schema, count,
        // partition list, COPY) reads that result rather than re-running author SQL with its own cost and
        // its own chance of a different answer.
        String scratch = "derived_" + UUID.randomUUID().toString().replace("-", "");
        try (Statement st = conn.createStatement()) {
            st.execute("CREATE TEMP TABLE " + scratch + " AS " + table.sql());
        }
        try {
            String fingerprint = fingerprint(conn, scratch);
            Path dir = Paths.get(derivedRoot, table.name());
            Files.createDirectories(dir);

            List<ConsignmentOutput> rows = new ArrayList<>();
            if (table.partitionBy() == null) {
                Written w = copyTo(conn, "SELECT * FROM " + scratch, dir, consignmentId + ".parquet");
                rows.add(row(consignmentId, table, "", w, writtenAt, producer, fingerprint,
                        count(conn, "SELECT count(*) FROM " + scratch)));
            } else {
                String col = '"' + table.partitionBy() + '"';
                for (String value : partitionValues(conn, scratch, col)) {
                    // The partition value reaches a PATH, so it is jailed the same way a name is: a value
                    // outside the safe set is refused rather than escaped or silently skipped.
                    if (!GuardedDerivedTableEmitter.SAFE_NAME.matcher(value).matches())
                        throw new IllegalArgumentException("derived table '" + table.name() + "': partition value '"
                                + value + "' is not a safe directory name (column " + table.partitionBy() + ")");
                    Path pdir = dir.resolve(table.partitionBy() + "=" + value);
                    Files.createDirectories(pdir);
                    String where = " WHERE " + col + " = '" + value.replace("'", "''") + "'";
                    Written w = copyTo(conn, "SELECT * FROM " + scratch + where, pdir, consignmentId + ".parquet");
                    rows.add(row(consignmentId, table, value, w, writtenAt, producer, fingerprint,
                            count(conn, "SELECT count(*) FROM " + scratch + where)));
                }
            }
            return rows;
        } finally {
            try (Statement st = conn.createStatement()) {
                st.execute("DROP TABLE IF EXISTS " + scratch);
            } catch (Exception ignored) {
                // best-effort: the connection is a per-run sandbox and is about to be closed anyway
            }
        }
    }

    /** COPY to a staged name, then ATOMIC_MOVE — a reader never sees a half-written Parquet file. */
    private static Written copyTo(Connection conn, String select, Path dir, String fileName) throws Exception {
        Path staged = dir.resolve(fileName + "." + UUID.randomUUID().toString().substring(0, 8) + ".tmp");
        try (Statement st = conn.createStatement()) {
            st.execute("COPY (" + select + ") TO '" + sqlPath(staged) + "' (FORMAT PARQUET)");
        }
        Path finalPath = dir.resolve(fileName);
        Files.move(staged, finalPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        return new Written(finalPath.toString(), Files.size(finalPath));
    }

    /** The produced relation's shape, as {@code name:TYPE|…} — DuckDB is the authority, nothing restates it. */
    private static String fingerprint(Connection conn, String scratch) throws Exception {
        StringBuilder sb = new StringBuilder();
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT column_name, column_type FROM (DESCRIBE " + scratch + ")")) {
            while (rs.next()) {
                if (!sb.isEmpty()) sb.append('|');
                sb.append(rs.getString(1)).append(':').append(rs.getString(2));
            }
        }
        return sb.toString();
    }

    private static List<String> partitionValues(Connection conn, String scratch, String col) throws Exception {
        List<String> out = new ArrayList<>();
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT DISTINCT CAST(" + col + " AS VARCHAR) FROM " + scratch
                     + " WHERE " + col + " IS NOT NULL ORDER BY 1")) {
            while (rs.next()) out.add(rs.getString(1));
        }
        return out;
    }

    private static long count(Connection conn, String select) throws Exception {
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(select)) {
            return rs.next() ? rs.getLong(1) : 0L;
        }
    }

    private static ConsignmentOutput row(String consignmentId, DerivedTable table, String partition, Written w,
                                         String writtenAt, String producer, String fingerprint, long rows) {
        return new ConsignmentOutput(consignmentId, null, table.name() + DERIVED_SUFFIX, partition,
                null, w.path(), rows, w.bytes(), writtenAt, 0, ConsignmentOutput.State.LIVE,
                fingerprint, null, producer);
    }

    private static String sqlPath(Path p) {
        return p.toAbsolutePath().toString().replace('\\', '/').replace("'", "''");
    }

    private record Written(String path, long bytes) {}
}
