package com.gamma.etl;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SP1 (link-analysis roadmap, M02 / R-02): does the REAL {@link PartitionWriter} give a bucketed, sorted,
 * row-group-tuned daily fact layout, so a one-key lookup reads ONE file and row groups are sorted and
 * non-overlapping? The fast tests run a small skewed corpus and assert on Parquet METADATA (row-group
 * min/max, file counts, EXPLAIN file counts), never on timing. The measurement is gated on
 * {@code -Dbench.run=true} (rows via {@code -Dbench.rows=}, default 2,000,000). Findings: roadmap SP1 block.
 *
 * <p>Corpus: a daily CDR-shaped fact keyed by {@code a} (caller), skewed - 3 hub callers carry about 30% of
 * the rows, the rest is a long tail. {@code bucket = md5_number_lower(a) % N}, the same definition the Index
 * uses ({@code BucketFunction}, inspecto-la-storage).
 */
class PartitionWriterLayoutSpikeTest {

    private static final int BUCKETS = 8;

    // ---------------------------------------------------------------- fixtures

    private static Connection open() throws Exception {
        return DriverManager.getConnection("jdbc:duckdb:");
    }

    /** Skewed corpus, deterministic. Column order puts the sort key {@code a} first for readable metadata. */
    private static void corpus(Statement s, long rows, int tailKeys) throws Exception {
        s.execute("CREATE TABLE raw AS SELECT "
                + " CASE WHEN hash(i) % 100 < 30 THEN 'hub' || (i % 3) ELSE 'k' || lpad(CAST(hash(i * 7 + 1) % " + tailKeys
                + " AS VARCHAR), 7, '0') END AS a,"
                + " 'b' || lpad(CAST(hash(i * 13 + 5) % 5000000 AS VARCHAR), 7, '0') AS b,"
                + " TIMESTAMP '2026-10-01 00:00:00' + to_seconds(CAST(hash(i * 3 + 2) % 86400 AS BIGINT)) AS ts,"
                + " CAST(hash(i * 5 + 3) % 3600 AS INTEGER) AS dur"
                + " FROM range(" + rows + ") t(i) ORDER BY hash(i * 11 + 4)");   // shuffled = arrival order
        s.execute("CREATE TABLE fact AS SELECT CAST(md5_number_lower(a) % " + BUCKETS + " AS INTEGER) AS bucket, * FROM raw");
    }

    private static long scalar(Statement s, String sql) throws Exception {
        try (ResultSet rs = s.executeQuery(sql)) { rs.next(); return rs.getLong(1); }
    }

    private static String glob(Path dir) { return dir.toString().replace('\\', '/') + "/**/*.parquet"; }

    // ---------------------------------------------------------------- layout probes (Parquet metadata)

    /** Layout facts of one written tree. {@code overlaps}: adjacent row-group pairs in one file whose key ranges overlap. */
    record Layout(long files, long maxFilesInOneBucket, long rowGroups, long overlaps, long outOfOrderRows,
                  long rowsOnDisk, long maxFileBytes) {}

    private static Layout layout(Statement s, Path dir) throws Exception {
        String g = glob(dir);
        long files = scalar(s, "SELECT count(*) FROM glob('" + g + "')");
        long perBucket = scalar(s, "SELECT coalesce(max(c),0) FROM (SELECT count(*) c FROM glob('" + g + "') GROUP BY regexp_extract(file, 'bucket=(\\d+)', 1))");
        long rgs = scalar(s, "SELECT count(*) FROM parquet_metadata('" + g + "') WHERE path_in_schema = 'a'");
        // Adjacent row groups of one file overlap when the next group's min is below the previous group's max.
        long overlaps = scalar(s, "SELECT count(*) FROM (SELECT file_name, stats_min_value mn, lag(stats_max_value) OVER (PARTITION BY file_name ORDER BY row_group_id) pmx"
                + " FROM parquet_metadata('" + g + "') WHERE path_in_schema = 'a') WHERE pmx IS NOT NULL AND mn < pmx");
        // Row order inside a file: rows whose key is below the previous row's key (file_row_number gives physical order).
        long ooo = scalar(s, "SELECT count(*) FROM (SELECT a, lag(a) OVER (PARTITION BY filename ORDER BY file_row_number) pa"
                + " FROM read_parquet('" + g + "', filename = true, file_row_number = true)) WHERE pa IS NOT NULL AND a < pa");
        long rows = scalar(s, "SELECT count(*) FROM read_parquet('" + g + "')");
        long maxBytes = 0;
        try (ResultSet rs = s.executeQuery("SELECT file FROM glob('" + g + "')")) {
            while (rs.next()) maxBytes = Math.max(maxBytes, Files.size(Path.of(rs.getString(1))));
        }
        return new Layout(files, perBucket, rgs, overlaps, ooo, rows, maxBytes);
    }

    /** Files and row groups ONE key can live in, by metadata: groups whose [min,max] range contains the key. */
    private static long[] filesAndGroupsFor(Statement s, Path dir, String key, int bucket) throws Exception {
        String g = glob(dir);
        String inBucket = " AND regexp_matches(file_name, 'bucket=" + bucket + "[/\\\\]')";
        long files = scalar(s, "SELECT count(DISTINCT file_name) FROM parquet_metadata('" + g + "') WHERE path_in_schema = 'a'"
                + " AND stats_min_value <= '" + key + "' AND stats_max_value >= '" + key + "'" + inBucket);
        long groups = scalar(s, "SELECT count(*) FROM parquet_metadata('" + g + "') WHERE path_in_schema = 'a'"
                + " AND stats_min_value <= '" + key + "' AND stats_max_value >= '" + key + "'" + inBucket);
        return new long[]{files, groups};
    }

    /** The scan line(s) of EXPLAIN ANALYZE for the one-key lookup, as DuckDB prints them. */
    private static String explainLookup(Statement s, Path dir, String key, int bucket) throws Exception {
        StringBuilder sb = new StringBuilder();
        try (ResultSet rs = s.executeQuery("EXPLAIN ANALYZE SELECT b, ts FROM read_parquet('" + glob(dir)
                + "', hive_partitioning = true) WHERE bucket = " + bucket + " AND a = '" + key + "'")) {
            while (rs.next()) sb.append(rs.getString(2)).append('\n');
        }
        return sb.toString();
    }

    /** "Scanning Files: x/y" if the plan prints it, else the lines that mention files. */
    private static String filesScanned(String plan) {
        var m = java.util.regex.Pattern.compile("(?i)(Scanning Files|Files Scanned)[^\\n]*").matcher(plan);
        StringBuilder sb = new StringBuilder();
        while (m.find()) sb.append(m.group().trim()).append(" | ");
        return sb.length() == 0 ? "(no file-count line in plan)" : sb.toString();
    }

    private static int bucketOf(Statement s, String key) throws Exception {
        return (int) scalar(s, "SELECT CAST(md5_number_lower('" + key + "') % " + BUCKETS + " AS INTEGER)");
    }

    // ---------------------------------------------------------------- the variants

    /** What the product does today: {@code SELECT * FROM <table>} + COPY ... PARTITION_BY, no sort, no row-group size. */
    private static List<PartitionOutput> realWriter(Connection c, String table, Path out) throws Exception {
        return PartitionWriter.write(c, table, out.toString(), "PARQUET", "zstd", "d", List.of("bucket"));
    }

    /** The recommended shape: sort in the COPY source, name ROW_GROUP_SIZE; this is what a projection-level ORDER BY would emit. */
    private static void sortedCopy(Statement s, Path out, int rowGroup, String extra) throws Exception {
        s.execute("COPY (SELECT * FROM fact ORDER BY bucket, a, ts) TO '" + out.toString().replace('\\', '/')
                + "' (FORMAT parquet, PARTITION_BY (bucket), OVERWRITE_OR_IGNORE 1, COMPRESSION zstd, ROW_GROUP_SIZE " + rowGroup + extra + ")");
    }

    /** Two-step shape: one COPY per bucket (WHERE bucket = b ORDER BY a, ts) into a hive-style directory. */
    private static void perBucketCopy(Statement s, Path out, int rowGroup) throws Exception {
        for (int b = 0; b < BUCKETS; b++) {
            Path d = out.resolve("bucket=" + b);
            Files.createDirectories(d);
            s.execute("COPY (SELECT * EXCLUDE (bucket) FROM fact WHERE bucket = " + b + " ORDER BY a, ts) TO '"
                    + d.resolve("d_out.parquet").toString().replace(java.io.File.separatorChar, '/')
                    + "' (FORMAT parquet, COMPRESSION zstd, ROW_GROUP_SIZE " + rowGroup + ")");
        }
    }

    // ---------------------------------------------------------------- fast tests (small corpus, metadata assertions)

    @Test
    void realWriterTodayDoesNotSortAndRowGroupsOverlap(@TempDir Path tmp) throws Exception {
        try (Connection c = open(); Statement s = c.createStatement()) {
            corpus(s, 120_000, 2_000);
            s.execute("SET threads = 4");
            List<PartitionOutput> outs = realWriter(c, "fact", tmp.resolve("today"));
            Layout l = layout(s, tmp.resolve("today"));
            System.out.println("SP1 today: " + l);
            assertEquals(120_000, l.rowsOnDisk(), "the real writer must not lose rows");
            assertEquals(outs.size(), l.files());
            // R-02 baseline: unsorted input stays unsorted - the writer adds no ORDER BY of its own.
            assertTrue(l.outOfOrderRows() > 0, "today's writer does not sort within a bucket file");
        }
    }

    @Test
    void presortedTableThroughRealWriter(@TempDir Path tmp) throws Exception {
        try (Connection c = open(); Statement s = c.createStatement()) {
            corpus(s, 120_000, 2_000);
            s.execute("CREATE TABLE fact_sorted AS SELECT * FROM fact ORDER BY bucket, a, ts");
            s.execute("SET threads = 4");
            realWriter(c, "fact_sorted", tmp.resolve("pre4"));
            Layout multi = layout(s, tmp.resolve("pre4"));
            s.execute("SET threads = 1");
            realWriter(c, "fact_sorted", tmp.resolve("pre1"));
            Layout single = layout(s, tmp.resolve("pre1"));
            System.out.println("SP1 presorted table, real writer, threads=4: " + multi);
            System.out.println("SP1 presorted table, real writer, threads=1: " + single);
            assertEquals(120_000, multi.rowsOnDisk());
            assertEquals(120_000, single.rowsOnDisk());
            assertEquals(0, multi.overlaps() + single.overlaps(), "at this size (one row group per file) overlap is vacuous");
        }
    }

    /**
     * The shape that IS deterministic: one COPY per bucket (the two-step write). Measured at 3M rows on 12 cores, the
     * single-COPY {@code ORDER BY ... PARTITION_BY} variants left 2-20 out-of-order rows per run (see {@link #measure});
     * this one left 0 in every pass.
     */
    @Test
    void perBucketCopyGivesSortedNonOverlappingOneFilePerBucket(@TempDir Path tmp) throws Exception {
        try (Connection c = open(); Statement s = c.createStatement()) {
            corpus(s, 120_000, 2_000);
            perBucketCopy(s, tmp.resolve("per"), 2_000);
            Layout l = layout(s, tmp.resolve("per"));
            System.out.println("SP1 per-bucket COPY: " + l);
            assertEquals(120_000, l.rowsOnDisk());
            assertEquals(0, l.outOfOrderRows(), "rows sorted by key inside every bucket file");
            assertEquals(0, l.overlaps(), "row groups inside a file are sorted and non-overlapping");
            assertEquals(1, l.maxFilesInOneBucket());
            assertTrue(l.rowGroups() > l.files(), "ROW_GROUP_SIZE took effect: several row groups per file");
            int hubBucket = bucketOf(s, "hub0");
            long[] fg = filesAndGroupsFor(s, tmp.resolve("per"), "hub0", hubBucket);
            assertEquals(1, fg[0], "a hub key's value range lives in ONE file");
            // Row groups the hub's range spans = ceil(hub rows / row group) +/- 1 at the sorted boundaries: a bounded handful.
            long hubRows = scalar(s, "SELECT count(*) FROM fact WHERE a = 'hub0'");
            assertTrue(fg[1] <= hubRows / 2_000 + 2, "hub rows are contiguous: " + fg[1] + " row groups for " + hubRows + " rows");
            // a tail key lives in at most 2 row groups (one if it does not straddle a boundary)
            long[] tail = filesAndGroupsFor(s, tmp.resolve("per"), "k0000042", bucketOf(s, "k0000042"));
            assertTrue(tail[1] <= 2, "a tail key spans at most 2 row groups, got " + tail[1]);
            // the bucket predicate reaches ONE file: EXPLAIN prints "Scanning Files: 1/<buckets>"
            String plan = explainLookup(s, tmp.resolve("per"), "hub0", hubBucket);
            assertTrue(plan.replaceAll("\s+", " ").contains("Scanning Files: 1/" + BUCKETS), "plan: " + filesScanned(plan));
        }
    }

    /**
     * File size cannot be capped by the COPY itself in a partitioned write: DuckDB (the pinned duckdb_jdbc) rejects
     * FILE_SIZE_BYTES together with PARTITION_BY. So the 128-512 MB target is met by the BUCKET COUNT (bytes / target),
     * not by a size option. Version-pinned: if a DuckDB upgrade lifts this, this test fails and the finding is stale.
     */
    @Test
    void fileSizeBytesCannotBeCombinedWithPartitionBy(@TempDir Path tmp) throws Exception {
        try (Connection c = open(); Statement s = c.createStatement()) {
            corpus(s, 20_000, 500);
            var e = assertThrows(java.sql.SQLException.class, () -> sortedCopy(s, tmp.resolve("split"), 2_000, ", FILE_SIZE_BYTES '100KB'"));
            assertTrue(e.getMessage().contains("FILE_SIZE_BYTES and PARTITION_BY"), e.getMessage());
        }
    }

    // ---------------------------------------------------------------- gated measurement

    @Test
    void measure(@TempDir Path tmp) throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean("bench.run"), "pass -Dbench.run=true to run the SP1 measurement");
        long rows = Long.getLong("bench.rows", 2_000_000L);
        int rg = Integer.getInteger("bench.rowgroup", 100_000);
        try (Connection c = open(); Statement s = c.createStatement()) {
            corpus(s, rows, (int) Math.max(1000, rows / 20));
            System.out.println("SP1 bench rows=" + rows + " buckets=" + BUCKETS + " rowGroup=" + rg
                    + " hubRows(hub0)=" + scalar(s, "SELECT count(*) FROM fact WHERE a = 'hub0'")
                    + " distinctKeys=" + scalar(s, "SELECT count(DISTINCT a) FROM fact") + " cores=" + Runtime.getRuntime().availableProcessors());
            s.execute("CREATE TABLE fact_sorted AS SELECT * FROM fact ORDER BY bucket, a, ts");
            String[] keys = {"hub0", "k0000042"};
            for (int pass = 1; pass <= 3; pass++) {
                Path base = tmp.resolve("p" + pass);
                long t0 = System.nanoTime(); realWriter(c, "fact", base.resolve("today")); long tToday = (System.nanoTime() - t0) / 1_000_000;
                t0 = System.nanoTime(); realWriter(c, "fact_sorted", base.resolve("pre")); long tPre = (System.nanoTime() - t0) / 1_000_000;
                t0 = System.nanoTime(); sortedCopy(s, base.resolve("sorted"), rg, ""); long tSorted = (System.nanoTime() - t0) / 1_000_000;
                s.execute("SET threads = 1");
                t0 = System.nanoTime(); sortedCopy(s, base.resolve("sorted_t1"), rg, ""); long tSortedT1 = (System.nanoTime() - t0) / 1_000_000;
                s.execute("SET threads = " + Runtime.getRuntime().availableProcessors());
                t0 = System.nanoTime(); perBucketCopy(s, base.resolve("perbucket"), rg); long tPer = (System.nanoTime() - t0) / 1_000_000;
                for (String v : new String[]{"today", "pre", "sorted", "sorted_t1", "perbucket"}) {
                    Layout l = layout(s, base.resolve(v));
                    StringBuilder k = new StringBuilder();
                    for (String key : keys) {
                        int b = bucketOf(s, key);
                        long[] fg = filesAndGroupsFor(s, base.resolve(v), key, b);
                        k.append(' ').append(key).append("->files=").append(fg[0]).append(",rowGroups=").append(fg[1])
                                .append(" [").append(filesScanned(explainLookup(s, base.resolve(v), key, b))).append(']');
                    }
                    System.out.println("SP1 pass" + pass + " " + v + " write_ms=" + (v.equals("today") ? tToday : v.equals("pre") ? tPre : v.equals("sorted") ? tSorted : v.equals("sorted_t1") ? tSortedT1 : tPer) + " " + l + k);
                }
            }
        }
    }
}
