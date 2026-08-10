package com.gamma.sql;

/**
 * Builds DuckDB table-function expressions that read a Parquet/CSV dataset — the view-registration
 * idiom shared by the Stage-2 {@code EnrichmentEngine} (the production path) and the M6
 * {@link SqlOracle} (the {@code kpi-to-sql} validation path). Extracted at v3.6.0 so both callers
 * register inputs identically; there is exactly one place that decides how a partitioned dataset is
 * read, so the oracle validates against the same shape production runs against.
 *
 * <p>{@code hive_types_autocast=0} keeps Hive partition values as {@code VARCHAR} so zero-padded
 * month/day segments (e.g. {@code "04"}) survive rather than being coerced to integers.
 *
 * @since 3.6.0
 */
public final class SqlViews {

    private SqlViews() {}

    /** The file extension for a format: {@code "parquet"} or (default) {@code "csv"}. */
    public static String ext(String format) {
        return "PARQUET".equals(format) ? "parquet" : "csv";
    }

    /**
     * The canonical read root of a store directory: its {@code database/} subtree when one exists —
     * a pipeline-shaped store reads its <em>mapped output</em>, so the sibling {@code backup/},
     * {@code quarantine/} and any nested trees never leak into recursive reads — else the directory
     * itself (a flat snapshot store). The read-side half of the store-layout contract
     * (BACKLOG §1, decided 2026-07-18); the write-side half is {@code PipelineJobRunner}'s
     * top-level-sink rule.
     *
     * @param storeDir the store directory (either slash style)
     * @return the directory to glob under, forward-slashed
     */
    public static String storeReadRoot(String storeDir) {
        String dir = storeDir.replace("\\", "/");
        return java.nio.file.Files.isDirectory(java.nio.file.Path.of(dir, "database"))
                ? dir + "/database" : dir;
    }

    /**
     * A DuckDB table-function reading {@code pathOrGlob} in the given {@code format}.
     *
     * @param format     {@code "PARQUET"} or {@code "CSV"}
     * @param pathOrGlob the file or glob to read (back-slashes are normalised to forward slashes; an
     *                   embedded {@code '} is doubled, as {@link #pathList} already did — a store under a
     *                   directory like {@code O'Brien} otherwise renders a broken SQL literal)
     * @param hive       when {@code true}, enable {@code hive_partitioning} (with VARCHAR partition
     *                   values via {@code hive_types_autocast=0})
     * @return a {@code read_parquet(...)} / {@code read_csv(...)} expression
     * @throws IllegalArgumentException for an unsupported format
     */
    public static String reader(String format, String pathOrGlob, boolean hive) {
        return over(format, "'" + pathOrGlob.replace("\\", "/").replace("'", "''") + "'", hive);
    }

    /**
     * The same reader over an <b>explicit file list</b> rather than a glob — what {@code ConsignmentSelector}
     * emits once the catalog has a file to exclude (consignment addressing §7-A). Every other option matches
     * {@link #reader(String, String, boolean)} exactly, so a selected read and a globbed read differ only in
     * which files they name.
     *
     * <p>An <b>empty</b> list renders as a glob that cannot match anything rather than as
     * {@code read_parquet([])}. A store with no readable files is not a new state — a glob over an empty store
     * already fails with DuckDB's "No files found" — so an empty selection is made to fail the same way rather
     * than introducing a second, differently-shaped error for callers to learn.
     */
    public static String reader(String format, java.util.List<String> paths, boolean hive) {
        return over(format, pathList(paths), hive);
    }

    /**
     * A file list as a DuckDB SQL literal — {@code ['a.parquet', 'b.parquet']} — or the quoted
     * {@link #NO_FILES_GLOB} when there is nothing in it. The one renderer for a selected read, so a caller
     * that builds its own {@code read_parquet(...)} (there is one: {@code DatasetRelation}, which deliberately
     * passes no other options) stays byte-compatible with {@link #reader(String, java.util.List, boolean)}.
     */
    public static String pathList(java.util.List<String> paths) {
        if (paths == null || paths.isEmpty()) return "'" + NO_FILES_GLOB + "'";
        StringBuilder list = new StringBuilder("[");
        for (int i = 0; i < paths.size(); i++) {
            if (i > 0) list.append(", ");
            list.append('\'').append(paths.get(i).replace("\\", "/").replace("'", "''")).append('\'');
        }
        return list.append(']').toString();
    }

    /** A pattern no output file can match — the empty-selection rendering (see {@link #pathList}). */
    private static final String NO_FILES_GLOB = "__no_readable_files__/*";

    /** Both overloads' shared body. {@code source} is already a SQL literal — one quoted path, or a bracketed
     *  list of them — so the read options below are written once and cannot drift between a globbed read and a
     *  selected one. */
    private static String over(String format, String source, boolean hive) {
        return switch (format) {
            // union_by_name: a store's files may gain additive columns over time (e.g. a Decision
            // Rule's tag consequence adds __tags from some batch onward) — align by name, not position.
            case "PARQUET" -> "read_parquet(" + source + ", union_by_name=true"
                    + (hive ? ", hive_partitioning=true, hive_types_autocast=0" : "") + ")";
            case "CSV" -> "read_csv(" + source + ", header=true, all_varchar=true, union_by_name=true"
                    + (hive ? ", hive_partitioning=true, hive_types_autocast=0" : "") + ")";
            default -> throw new IllegalArgumentException("Unsupported format: " + format);
        };
    }
}
