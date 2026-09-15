package com.gamma.etl;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.*;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * Writes a materialized table to partitioned output, excluding the internal
 * {@code __src_id} column, and reveals each partition file under a stable name
 * via a two-step atomic rename.
 *
 * <p>The {@code __src_id} column is dropped from the written rows with
 * {@code SELECT * EXCLUDE (__src_id)} so the output schema is unchanged.
 *
 * <p>Extracted from {@link DataTransformer}, where this COPY/rename logic lived
 * before batching required it to be reusable and lineage-aware.
 */
public final class PartitionWriter {

    private PartitionWriter() {}

    private static final List<String> DEFAULT_PARTITION_COLS = List.of("year", "month", "day");

    /**
     * Reveal the staged partition files in parallel only when there are at least
     * this many. Below the threshold a sequential loop is faster (no fork/join
     * setup) and keeps low-cardinality output byte-for-byte as before; above it,
     * the per-file rename dance is what dominates write cost, so we fan it out.
     */
    private static final int REVEAL_PARALLEL_THRESHOLD = 16;

    /**
     * Backward-compatible overload — partitions by {@code (year, month, day)}.
     *
     * @param conn         worker DuckDB connection containing {@code table}
     * @param table        table to write (must contain partition cols + {@code __src_id})
     * @param databaseDir  output root (already resolved to include any table sub-dir)
     * @param outputFormat {@code "CSV"} or {@code "PARQUET"}
     * @param compression  parquet compression (ignored for CSV; may be {@code null})
     * @param baseName     output file stem; final files are {@code <baseName>_out.<ext>}
     * @return one {@link PartitionOutput} per revealed partition file
     */
    public static List<PartitionOutput> write(Connection conn, String table,
                                              String databaseDir, String outputFormat,
                                              String compression, String baseName)
            throws Exception {
        return write(conn, table, databaseDir, outputFormat, compression, baseName,
                DEFAULT_PARTITION_COLS);
    }

    /**
     * Write {@code table} to Hive-partitioned output using the supplied partition columns.
     *
     * @param partitionColumns ordered list of column names to partition by (e.g.
     *                         {@code ["event_type","year","month","day"]}); must be
     *                         non-empty and present in {@code table}
     */
    public static List<PartitionOutput> write(Connection conn, String table,
                                              String databaseDir, String outputFormat,
                                              String compression, String baseName,
                                              List<String> partitionColumns)
            throws Exception {
        // CSV/plugin ingest path: the materialized table carries the internal __src_id lineage tag and
        // __event_time (§3.1's coerced event time, used for write-time bounds), neither of which belongs in
        // written output. Both are filtered to what the relation actually has: this overload is a general
        // entry point, also called on relations that never went through DataTransformer, and DuckDB's
        // EXCLUDE is a binder error — not a no-op — when it names an absent column.
        return write(conn, table, databaseDir, outputFormat, compression, baseName, partitionColumns,
                internalColumnsPresent(conn, table, "__src_id", TransformCompiler.EVENT_TIME_COL));
    }

    /**
     * Which of {@code candidates} {@code table} actually has, in the order given — so the caller can name the
     * internal columns it wants stripped without asserting they are all present.
     *
     * <p>Deliberately not applied to the full overload below: a caller that names an exclusion explicitly
     * should still get a hard error for a typo, and only the internal tags this class chooses itself are
     * filtered.
     */
    private static List<String> internalColumnsPresent(Connection conn, String table, String... candidates)
            throws Exception {
        Set<String> present = new HashSet<>();
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT * FROM " + table + " LIMIT 0")) {
            ResultSetMetaData md = rs.getMetaData();
            for (int i = 1; i <= md.getColumnCount(); i++) present.add(md.getColumnName(i));
        }
        return Arrays.stream(candidates).filter(present::contains).toList();
    }

    /**
     * B4 ({@code output.filename_column} / {@code sinks[].filename_column}): like
     * {@link #write(Connection, String, String, String, String, String, List)} but when
     * {@code filenameColumn} is set, the internal {@code __src_id} tag is <b>translated</b> into a
     * VARCHAR column of source filenames (via {@code srcIdToFile}, the same map the lineage ledger
     * uses) instead of only being excluded. Null/blank {@code filenameColumn} ⇒ byte-identical to the
     * plain overload. Fails when the relation carries no {@code __src_id} — a declared lineage column
     * that silently wrote NULLs would look like it worked.
     */
    public static List<PartitionOutput> write(Connection conn, String table,
                                              String databaseDir, String outputFormat,
                                              String compression, String baseName,
                                              List<String> partitionColumns,
                                              String filenameColumn,
                                              java.util.Map<Integer, String> srcIdToFile)
            throws Exception {
        List<String> exclude = internalColumnsPresent(conn, table, "__src_id", TransformCompiler.EVENT_TIME_COL);
        if (filenameColumn == null || filenameColumn.isBlank())
            return write(conn, table, databaseDir, outputFormat, compression, baseName,
                    partitionColumns, exclude);
        if (!exclude.contains("__src_id"))
            throw new IllegalStateException("filename_column '" + filenameColumn
                    + "' requires per-row source lineage (__src_id), which relation '" + table
                    + "' does not carry");
        StringBuilder proj = new StringBuilder("SELECT * EXCLUDE (")
                .append(String.join(", ", exclude)).append("), CASE \"__src_id\"");
        for (var e : srcIdToFile.entrySet())
            proj.append(" WHEN ").append(e.getKey())
                .append(" THEN '").append(e.getValue().replace("'", "''")).append('\'');
        proj.append(" ELSE NULL END AS \"").append(filenameColumn).append("\" FROM ").append(table);
        return writeProjected(conn, proj.toString(), databaseDir, outputFormat, compression,
                baseName, partitionColumns);
    }

    /**
     * Full overload: write {@code table} partitioned by {@code partitionColumns},
     * excluding {@code excludeColumns} from the written rows. Pass an empty list to
     * write every column — e.g. the enrichment engine, whose output has no
     * {@code __src_id} to strip.
     */
    public static List<PartitionOutput> write(Connection conn, String table,
                                              String databaseDir, String outputFormat,
                                              String compression, String baseName,
                                              List<String> partitionColumns,
                                              List<String> excludeColumns)
            throws Exception {
        String projection = (excludeColumns == null || excludeColumns.isEmpty())
                ? "SELECT * FROM " + table
                : "SELECT * EXCLUDE (" + String.join(", ", excludeColumns) + ") FROM " + table;
        return writeProjected(conn, projection, databaseDir, outputFormat, compression,
                baseName, partitionColumns);
    }

    /**
     * The write core: COPY {@code projection} to staging, then reveal atomically. E1
     * (delimited-grammar-properties plan Part II): an EMPTY {@code partitionColumns} writes one
     * unpartitioned file — same staging + atomic-reveal machinery, no {@code PARTITION_BY} — so
     * "no key declared → flat store" holds on every lane and the {@code year=1900} sentinel is
     * retired for new writes.
     */
    private static List<PartitionOutput> writeProjected(Connection conn, String projection,
                                                        String databaseDir, String outputFormat,
                                                        String compression, String baseName,
                                                        List<String> partitionColumns)
            throws Exception {

        OutputFormat fmt = OutputFormat.resolve(outputFormat);
        String  outputFileName = baseName + "_out" + fmt.extension();
        boolean partitioned = partitionColumns != null && !partitionColumns.isEmpty();

        // Scale-out §5.4 bullet 1. An object-store target has no staging directory and no reveal: see
        // writeToObjectStore. Dispatch on the SAME predicate the path jail uses, so "is this a URI" has
        // one definition in the product rather than a second spelling here.
        if (com.gamma.config.safety.PathJail.isUri(databaseDir))
            return writeToObjectStore(conn, projection, databaseDir, fmt, compression,
                    baseName, partitionColumns, partitioned);

        new File(databaseDir).mkdirs();
        String workerTag   = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        Path   stagingPath = Paths.get(databaseDir, ".staging", workerTag);
        Files.createDirectories(stagingPath);
        String stagingDir  = stagingPath.toString().replace("\\", "/");

        List<PartitionOutput> outputs;

        try (Statement stmt = conn.createStatement()) {
            StringBuilder copyOpts = new StringBuilder("FORMAT ").append(fmt.copyToken());
            if (partitioned)
                copyOpts.append(", PARTITION_BY (").append(String.join(", ", partitionColumns))
                        .append("), OVERWRITE_OR_IGNORE 1");
            if (fmt.supportsCompression() && compression != null && !compression.isBlank())
                copyOpts.append(", COMPRESSION ").append(compression);

            // Partitioned: COPY to the staging DIRECTORY; unpartitioned: one staged FILE.
            String target = partitioned ? stagingDir : stagingDir + "/" + outputFileName;
            stmt.execute(String.format("COPY (%s) TO '%s' (%s)", projection, target, copyOpts));

            // Collect the staged partition files in one walk, then reveal each under its
            // stable name. The reveal (a cross-dir rename into place + an atomic same-dir
            // rename) is what dominates write cost when the partition fan-out is large, so
            // for many files we fan it out across the common pool; each file targets a
            // distinct partition directory, so the renames don't contend. Output order is
            // irrelevant (callers key by partition), so parallel collection is safe.
            List<Path> stagedFiles;
            try (Stream<Path> staged = Files.walk(stagingPath)) {
                stagedFiles = staged.filter(Files::isRegularFile)
                        .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
            }
            final Path stagingRoot = stagingPath;
            Stream<Path> revealStream = stagedFiles.size() >= REVEAL_PARALLEL_THRESHOLD
                    ? stagedFiles.parallelStream() : stagedFiles.stream();
            outputs = revealStream
                    .map(src -> reveal(src, stagingRoot, databaseDir, outputFileName))
                    .collect(java.util.stream.Collectors.toCollection(ArrayList::new));

            try (Stream<Path> cleanup = Files.walk(stagingPath)) {
                cleanup.sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
            }
        }
        return outputs;
    }

    /**
     * Write to an object-store target ({@code s3://…}), where the staging-and-reveal dance does not
     * exist and must not be imitated.
     *
     * <p><b>Why this is not the other lane with a different path string.</b> Measured 2026-09-14 against
     * MinIO with {@code duckdb_jdbc} 1.5.2.1 — the shape is genuinely different in three ways, and each
     * one is load-bearing:
     * <ol>
     *   <li><b>There is no reveal.</b> A partitioned {@code COPY} writes straight to its final Hive-style
     *       keys. An object store has no atomic rename to reveal with, and it does not need one: a PUT is
     *       already all-or-nothing, and <em>cross-node</em> visibility is the DuckLake catalog commit
     *       (plan §5.4's invariant), not a directory operation.</li>
     *   <li><b>The outputs must be discovered, not collected.</b> There is no staging tree to walk, so the
     *       written files come back from {@code glob()} and their sizes from {@code parquet_metadata()}.
     *       ⚠ {@code total_compressed_size} is the sum of the column chunks, <b>not</b> the object's byte
     *       length — it understates by the footer and header. Reported as-is rather than with a second
     *       round trip per file; the registry rows use it for relative weight, not for billing.</li>
     *   <li>🔴 <b>A repeat write ACCUMULATES; it does not replace.</b> Locally, re-running a batch
     *       overwrites {@code <baseName>_out.<ext>} in place and is idempotent. Here two runs leave two
     *       objects unless the names collide exactly. {@code FILENAME_PATTERN} pins the stem, but DuckDB
     *       appends its own index, so the name is {@code <baseName>_out0.parquet} — close to the local
     *       lane's, deliberately not claimed to be identical.</li>
     * </ol>
     *
     * <p>⛔ This is reachable only by a caller that already holds a connection configured for the store
     * (endpoint and credentials are session settings). It is NOT reachable from a pipeline config today:
     * {@code dirs.database} refuses a URI at the 422 write gate and in the jail. Wiring those together is
     * the open credentials decision (BACKLOG §1), deliberately not pre-empted here.
     */
    private static List<PartitionOutput> writeToObjectStore(Connection conn, String projection,
                                                            String databaseDir, OutputFormat fmt,
                                                            String compression, String baseName,
                                                            List<String> partitionColumns,
                                                            boolean partitioned) throws Exception {
        String root = databaseDir.endsWith("/") ? databaseDir.substring(0, databaseDir.length() - 1)
                                                : databaseDir;
        String stem = baseName + "_out";

        StringBuilder copyOpts = new StringBuilder("FORMAT ").append(fmt.copyToken());
        if (partitioned)
            copyOpts.append(", PARTITION_BY (").append(String.join(", ", partitionColumns))
                    .append("), OVERWRITE_OR_IGNORE 1, FILENAME_PATTERN ").append(sqlStr(stem));
        if (fmt.supportsCompression() && compression != null && !compression.isBlank())
            copyOpts.append(", COMPRESSION ").append(compression);

        // Unpartitioned: COPY names the single object outright, so no pattern and no discovery needed.
        String target = partitioned ? root : root + "/" + stem + fmt.extension();

        // AIRGAP-S3-EXTENSIONS-1: name the extension this lane needs, so an air-gapped install FAILS with
        // the remedy instead of at the COPY with a DuckDB error. Both the COPY and the glob() discovery
        // below go through httpfs; on a developer box DuckDB autoloads it, which is exactly why the gap
        // was invisible — and why `package.ps1` staging it is not enough on its own.
        // ⛔ A STAGED extension is not a LOADED one: an extension that arrives only by autoload has no
        // call site to grep for, and its staged file is dead weight on any host without network.
        DuckDbExtension.ensureLoaded(conn, "httpfs", "writing to an object-store dirs.database (" + root + ")");

        try (Statement stmt = conn.createStatement()) {
            stmt.execute(String.format("COPY (%s) TO %s (%s)", projection, sqlStr(target), copyOpts));

            if (!partitioned)
                return List.of(new PartitionOutput("", target, objectBytes(stmt, target, fmt)));

            List<PartitionOutput> outputs = new ArrayList<>();
            String glob = root + "/**/" + stem + "*" + fmt.extension();
            try (ResultSet rs = stmt.executeQuery("SELECT file FROM glob(" + sqlStr(glob) + ") ORDER BY file")) {
                while (rs.next()) outputs.add(new PartitionOutput(
                        partitionOf(rs.getString(1), root), rs.getString(1), -1L));
            }
            // One metadata pass over the whole glob rather than one per file.
            if (fmt.copyToken().equalsIgnoreCase("PARQUET")) applySizes(stmt, glob, outputs);
            return outputs;
        }
    }

    /**
     * The partition segment of an object key — everything between the root and the file name.
     *
     * <p>⚠ Returns {@code ""} for an object written directly under the root, matching the local lane's
     * contract for an unpartitioned (E1) write rather than inventing a second spelling for "no partition".
     */
    private static String partitionOf(String file, String root) {
        String rest = file.startsWith(root + "/") ? file.substring(root.length() + 1) : file;
        int lastSlash = rest.lastIndexOf('/');
        return lastSlash < 0 ? "" : rest.substring(0, lastSlash);
    }

    /**
     * Fill in per-file sizes from Parquet metadata, leaving {@code -1} where the store answered nothing.
     *
     * <p>⚠ {@code -1} means <b>not measured</b>, which is this repo's existing convention, and is
     * deliberately not {@code 0} — a zero byte count reads as an empty file and would be a silent lie
     * about a partition that holds rows.
     */
    private static void applySizes(Statement stmt, String glob, List<PartitionOutput> outputs)
            throws java.sql.SQLException {
        java.util.Map<String, Long> bytes = new java.util.HashMap<>();
        try (ResultSet rs = stmt.executeQuery(
                "SELECT file_name, sum(total_compressed_size) FROM parquet_metadata("
                        + sqlStr(glob) + ") GROUP BY 1")) {
            while (rs.next()) bytes.put(rs.getString(1), rs.getLong(2));
        }
        outputs.replaceAll(o -> new PartitionOutput(
                o.partition(), o.outputFile(), bytes.getOrDefault(o.outputFile(), -1L)));
    }

    /** Byte count for one written object, or {@code -1} when it is not a format we can ask about. */
    private static long objectBytes(Statement stmt, String file, OutputFormat fmt)
            throws java.sql.SQLException {
        if (!fmt.copyToken().equalsIgnoreCase("PARQUET")) return -1L;
        try (ResultSet rs = stmt.executeQuery(
                "SELECT sum(total_compressed_size) FROM parquet_metadata(" + sqlStr(file) + ")")) {
            return rs.next() ? rs.getLong(1) : -1L;
        }
    }

    /** A single-quoted SQL literal. Object keys are config-derived, so the quote doubling is not optional. */
    private static String sqlStr(String s) {
        return "'" + s.replace("'", "''") + "'";
    }

    /**
     * Reveal one staged partition file under its stable {@code <baseName>_out.<ext>}
     * name: move it into the final partition directory as a unique temp, then
     * atomically rename within that directory (a same-dir rename is atomic on every
     * platform, unlike a cross-dir one). The temp name embeds the staged file name so
     * concurrent reveals into the same partition directory never collide on the temp.
     */
    private static PartitionOutput reveal(Path src, Path stagingRoot,
                                          String databaseDir, String outputFileName) {
        Path rel      = stagingRoot.relativize(src);
        Path dstFinal = Paths.get(databaseDir).resolve(rel).resolveSibling(outputFileName);
        Path dstTemp  = dstFinal.resolveSibling(outputFileName + "." + src.getFileName() + ".tmp");
        try {
            Files.createDirectories(dstFinal.getParent());
            Files.move(src, dstTemp, StandardCopyOption.REPLACE_EXISTING);
            Files.move(dstTemp, dstFinal,
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
            // An unpartitioned (E1) staged file sits at the staging root — no partition path.
            String partition = rel.getParent() == null ? "" : rel.getParent().toString().replace("\\", "/");
            return new PartitionOutput(partition, dstFinal.toString(), Files.size(dstFinal));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
