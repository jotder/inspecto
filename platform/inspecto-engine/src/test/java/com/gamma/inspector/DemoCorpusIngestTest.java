package com.gamma.inspector;

import com.gamma.etl.PipelineConfig;
import com.gamma.event.EventLog;
import com.gamma.pipeline.ComponentStore;
import com.gamma.pipeline.SpaceConfigRoot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.MDC;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The domain-shaped demos in {@code spaces/demo} ingest EXACTLY what their sample says they should
 * ({@code DEMO-CORPUS-FORMAT-COVERAGE-1}). Each demo's generator states an independent expectation —
 * N records split a:b:c by a known field — and this pins it on the REAL ingest path
 * ({@code CollectorProcessor.run}), over the committed config and the committed sample bytes.
 *
 * <p><b>Why the real ingest and not the workbench test run.</b> A hand-authored demo exists so that a
 * wrong count is visible; the fixture sweeps read row counts with nothing to compare them against. The
 * expectation has to be asserted somewhere that runs every build, or it is only a comment in a
 * generator. (Building these demos found {@code TESTRUN-SEED-IS-MAPPED-OUTPUT-1}: the test run seeded its
 * walk with the WRITTEN, already-mapped rows. Fixed 2026-09-23 — it seeds with the raw parsed rows, and
 * {@code ControlApiPipelineTestRunDemoTest} pins the test run against this real ingest.)
 *
 * <p>The committed config is staged VERBATIM into a temp Space ({@code <tmp>/config/<pipeline dir>/}, its
 * COMMITTED schemas beside it, because a satellite ref resolves beside its config —
 * {@code SCHEMA-FILE-RESOLVES-AGAINST-CWD-1}). Its {@code data/…} paths then resolve under the temp Space
 * ({@code DATA-DIRS-RESOLVE-AGAINST-CWD-1}), so the demo's own data dirs are never touched.
 */
class DemoCorpusIngestTest {

    private static final Path REPO = Path.of("..", "..").toAbsolutePath().normalize();

    /** in_recharges (fixed-width): 16 lines → header + trailer dropped → accepted 10 · rejected 4. */
    @Test
    void inRechargesDropsHeaderAndTrailerAndRoutesTenFour(@TempDir Path dir) throws Exception {
        PipelineConfig cfg = stage(dir, "config/recharge/in_recharges_pipeline.toon");
        seed(cfg, "in_recharges/RCH_20260801.dat");

        CollectorProcessor.run(cfg);

        Path accepted = dir.resolve("data/in_recharges/accepted");
        Path rejected = dir.resolve("data/in_recharges/rejected");
        assertEquals(10, count(accepted, "true"), "accepted rows");
        assertEquals(4, count(rejected, "true"), "rejected rows");
        assertEquals(0, count(accepted, "STATUS <> 'OK'"), "only OK lands in accepted");
        assertEquals(0, count(rejected, "REJECT_REASON IS NULL"), "every reject carries its reason");
        assertEquals(0, count(accepted, "REJECT_REASON IS NOT NULL"), "an accepted recharge has no reason");
        assertEquals("177.50", scalar(accepted, "CAST(SUM(AMOUNT) AS DECIMAL(12,2))::VARCHAR"),
                "the implied two decimals are undone (17750 minor units = 177.50)");
        assertEquals(0, count(accepted, "RECHARGE_TS IS NULL"), "every timestamp parses");
    }

    /**
     * gl_journal (xlsx): 13 journal lines → ASSET 5 · LIABILITY 3 · EXPENSE 3 · REVENUE 2, and the
     * Posting Date cells — real Excel dates — land as DATEs through a plain {@code keep}, with no
     * serial arithmetic in the mapping ({@code EXCEL-DATES-ARRIVE-AS-SERIALS-1}).
     */
    @Test
    void glJournalSplitsFiveThreeThreeTwoByAccountClass(@TempDir Path dir) throws Exception {
        try (Connection c = DriverManager.getConnection("jdbc:duckdb:")) {
            org.junit.jupiter.api.Assumptions.assumeTrue(com.gamma.etl.ExcelExtension.tryLoad(c),
                    "DuckDB 'excel' extension unavailable on this box — set -D" + com.gamma.etl.ExcelExtension.DIR_PROPERTY);
        }
        PipelineConfig cfg = stage(dir, "config/ledger/gl_journal_pipeline.toon");
        seed(cfg, "gl_journal/JOURNAL_20260831.xlsx");

        CollectorProcessor.run(cfg);

        Path db = Path.of(cfg.dirs().database());
        assertEquals(List.of("acct_class=ASSET", "acct_class=EXPENSE", "acct_class=LIABILITY", "acct_class=REVENUE"),
                subdirs(db).stream().filter(d -> d.startsWith("acct_class=")).toList(),
                "exactly 4 partitions — no TOTALS / NULL-class row");
        assertEquals(5, count(db.resolve("acct_class=ASSET"), "true"), "ASSET lines");
        assertEquals(3, count(db.resolve("acct_class=LIABILITY"), "true"), "LIABILITY lines");
        assertEquals(3, count(db.resolve("acct_class=EXPENSE"), "true"), "EXPENSE lines");
        assertEquals(2, count(db.resolve("acct_class=REVENUE"), "true"), "REVENUE lines");
        assertEquals("DATE", scalar(db, "typeof(POSTING_DATE)"), "the date cell lands as a DATE");
        assertEquals("2026-08-03|2026-08-28", scalar(db, "MIN(POSTING_DATE)::VARCHAR || '|' || MAX(POSTING_DATE)::VARCHAR"));
        assertEquals("0.00", scalar(db, "CAST(SUM(AMOUNT) AS DECIMAL(18,2))::VARCHAR"), "the journal balances");
    }

    /**
     * {@code PARTITION-KEY-VALIDATION-GAPS-1} (c) — the shipped {@code excel_example} (in {@code spaces/default})
     * used {@code partitionKey: CATEGORY}, a DATE partition over a text column: no value parses, so every row
     * landed under {@code __HIVE_DEFAULT_PARTITION__}. It now partitions on the category as text.
     */
    @Test
    void excelExamplePartitionsByCategoryNotUnderTheHiveDefault(@TempDir Path dir) throws Exception {
        try (Connection c = DriverManager.getConnection("jdbc:duckdb:")) {   // else the sample is quarantined, nothing written
            org.junit.jupiter.api.Assumptions.assumeTrue(com.gamma.etl.ExcelExtension.tryLoad(c),
                    "DuckDB 'excel' extension unavailable on this box — set -D" + com.gamma.etl.ExcelExtension.DIR_PROPERTY);
        }
        PipelineConfig cfg = stage(dir, "default", "config/excel_example/excel_example_pipeline.toon");
        seed(cfg, "default", "excel_example/INVENTORY_20260820.xlsx");

        CollectorProcessor.run(cfg);

        Path db = Path.of(cfg.dirs().database());
        List<String> parts = subdirs(db).stream().filter(p -> !p.startsWith(".")).toList();   // not .staging
        assertEquals(List.of("item_category=Electronics", "item_category=Hardware", "item_category=Misc"), parts,
                "one folder per category, none the Hive default");
        assertEquals(5, count(db, "true"), "the five inventory rows of A1:D6");
        assertEquals(0, count(db, "CATEGORY IS NULL"), "the mapped CATEGORY column is written, not renamed");
    }

    /**
     * {@code DATE-PARTITION-ON-TEXT-SHIPPED-1} — the shipped {@code orders_by_region_feed} used
     * {@code partitionKey: REGION}, a DATE partition over the region code, so every row landed under
     * {@code __HIVE_DEFAULT_PARTITION__}. The feed is one row per region, so it now partitions on the region.
     * Its producer is a materialize job, so the Dataset and one snapshot in its shape are written here, and
     * the feed collects it through its own {@code connector: dataset}.
     */
    @Test
    void ordersByRegionFeedPartitionsByRegionNotUnderTheHiveDefault(@TempDir Path dir) throws Exception {
        PipelineConfig cfg = stage(dir, "config/orders/orders_by_region_feed_pipeline.toon");
        new ComponentStore(dir.resolve("config/registry"))
                .write("dataset", "orders_by_region", Map.of("physicalRef", "orders_by_region"));
        Path snapshots = Files.createDirectories(dir.resolve("data/orders_by_region"));
        String snapshot = snapshots.resolve("orders_by_region_20260801T000000.parquet").toString().replace('\\', '/');
        try (Connection c = DriverManager.getConnection("jdbc:duckdb:"); Statement s = c.createStatement()) {
            s.execute("COPY (SELECT * FROM (VALUES ('APAC', 120.5), ('EMEA', 300.0), ('NA', 75.25)) t(region, sum_gross))"
                    + " TO '" + snapshot + "' (FORMAT PARQUET)");
        }

        SpaceConfigRoot.register("demo-corpus-feed", dir.resolve("config"));
        SpaceConfigRoot.registerDataRoot("demo-corpus-feed", dir.resolve("data"));
        MDC.put(EventLog.SPACE_MDC_KEY, "demo-corpus-feed");
        try {
            CollectorProcessor.run(cfg);
        } finally {
            MDC.remove(EventLog.SPACE_MDC_KEY);
            SpaceConfigRoot.forget("demo-corpus-feed");
        }

        Path db = Path.of(cfg.dirs().database());
        List<String> parts = subdirs(db).stream().filter(p -> !p.startsWith(".")).toList();   // not .staging
        assertEquals(List.of("sales_region=APAC", "sales_region=EMEA", "sales_region=NA"), parts,
                "one folder per region, none the Hive default");
        assertEquals(3, count(db, "true"), "the three region rows");
        assertEquals(0, count(db, "REGION IS NULL"), "the mapped REGION column is written, not renamed");
    }

    // ── harness ─────────────────────────────────────────────────────────────────────────────────

    private static PipelineConfig stage(Path dir, String pipeline) throws Exception {
        return stage(dir, "demo", pipeline);
    }

    private static PipelineConfig stage(Path dir, String space, String pipeline) throws Exception {
        Path source = REPO.resolve("spaces/" + space).resolve(pipeline);
        Path toon = dir.resolve(pipeline);
        Files.createDirectories(toon.getParent());
        try (var siblings = Files.list(source.getParent())) {
            for (Path f : siblings.filter(Files::isRegularFile).toList())
                Files.copy(f, toon.getParent().resolve(f.getFileName()));
        }
        return PipelineConfig.load(toon.toString());
    }

    private static void seed(PipelineConfig cfg, String sample) throws Exception {
        seed(cfg, "demo", sample);
    }

    private static void seed(PipelineConfig cfg, String space, String sample) throws Exception {
        Path inbox = Files.createDirectories(Path.of(cfg.dirs().poll()));
        Path src = REPO.resolve("spaces/" + space + "/data/samples").resolve(sample);
        Files.copy(src, inbox.resolve(src.getFileName()));
    }

    private static long count(Path root, String where) throws Exception {
        return Long.parseLong(scalar(root, "COUNT(*) FILTER (WHERE " + where + ")::VARCHAR"));
    }

    private static String scalar(Path root, String expr) throws Exception {
        assertTrue(Files.isDirectory(root), "no output written under " + root);
        String glob = root.toString().replace('\\', '/') + "/**/*.parquet";
        try (Connection c = DriverManager.getConnection("jdbc:duckdb:");
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT " + expr + " FROM read_parquet('" + glob + "')")) {
            rs.next();
            return rs.getString(1);
        }
    }

    private static List<String> subdirs(Path root) throws Exception {
        try (Stream<Path> s = Files.list(root)) {
            return s.filter(Files::isDirectory).map(p -> p.getFileName().toString()).sorted().toList();
        }
    }

    /** Every Hive partition path under {@code root} that holds a data file, e.g. {@code year=2026/month=08/day=01}. */
    private static List<String> leafPartitions(Path root) throws Exception {
        try (Stream<Path> s = Files.walk(root)) {
            return s.filter(p -> p.getFileName().toString().endsWith(".parquet"))
                    .map(p -> root.relativize(p.getParent()).toString().replace('\\', '/'))
                    .distinct().sorted().toList();
        }
    }
}
