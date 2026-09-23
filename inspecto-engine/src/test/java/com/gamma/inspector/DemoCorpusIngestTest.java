package com.gamma.inspector;

import com.gamma.etl.PipelineConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
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
 * generator. 🔴 And today the test run cannot reach either demo's sinks at all: it seeds the graph walk
 * with the ingest's WRITTEN (already-mapped) rows, so the map node re-applies the mapping to canonical
 * columns and refuses 422 on any expression over a raw column the mapping does not keep
 * ({@code AMOUNT_MINOR}, {@code EVENT_TIME}) — found building these demos, filed as
 * {@code TESTRUN-SEED-IS-MAPPED-OUTPUT-1}.
 *
 * <p>The committed config is staged into a temp root: every {@code spaces/demo/data/…} path is
 * re-pointed at the temp copy (the demo's own data dirs are never touched), while
 * {@code spaces/demo/config/…} schema paths are resolved against the repo so the COMMITTED schemas are
 * what runs. A path inside a TOON array row is quoted, because an absolute Windows path begins
 * {@code C:} and would otherwise parse as a key.
 */
class DemoCorpusIngestTest {

    private static final Path REPO = Path.of("..").toAbsolutePath().normalize();

    /** msc_cdr (ASN.1 BER): 13 records → moCallRecord 5 · mtCallRecord 4 · moSMSRecord 3, ssActionRecord junk. */
    @Test
    void mscCdrSplitsFiveFourThreeByChoiceAlternative(@TempDir Path dir) throws Exception {
        PipelineConfig cfg = stage(dir, "config/msc/msc_cdr_pipeline.toon");
        seed(cfg, "msc_cdr/MSC01_20260801_0800.ber");

        CollectorProcessor.run(cfg);

        Path db = Path.of(cfg.dirs().database());
        assertEquals(5, count(db.resolve("moCallRecord"), "true"), "moCallRecord rows");
        assertEquals(4, count(db.resolve("mtCallRecord"), "true"), "mtCallRecord rows");
        assertEquals(3, count(db.resolve("moSMSRecord"), "true"), "moSMSRecord rows");
        assertEquals(List.of("moCallRecord", "moSMSRecord", "mtCallRecord"), subdirs(db),
                "the ssActionRecord alternative is not a segment and must land nowhere");
        // one record per segment omits the OPTIONAL location → NULL, never a stringified subtree
        for (String seg : List.of("moCallRecord", "mtCallRecord", "moSMSRecord"))
            assertEquals(1, count(db.resolve(seg), "LAC IS NULL AND CELL_ID IS NULL"), seg + " rows without location");
        assertEquals(0, count(db.resolve("moCallRecord"), "EVENT_TS IS NULL"), "every answerTime parses");
        assertEquals(1, count(db.resolve("moCallRecord"), "DURATION_SEC = 2400"), "a multi-byte INTEGER decodes");
    }

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

    // ── harness ─────────────────────────────────────────────────────────────────────────────────

    private static PipelineConfig stage(Path dir, String pipeline) throws Exception {
        String data = dir.resolve("data").toString().replace('\\', '/') + "/";
        String config = REPO.resolve("spaces/demo/config").toString().replace('\\', '/') + "/";
        List<String> out = new ArrayList<>();
        for (String line : Files.readAllLines(REPO.resolve("spaces/demo").resolve(pipeline))) {
            String l = rewrite(line, "spaces/demo/data/", data);
            out.add(rewrite(l, "spaces/demo/config/", config));
        }
        Path toon = dir.resolve(Path.of(pipeline).getFileName());
        Files.write(toon, out);
        return PipelineConfig.load(toon.toString());
    }

    /** Replace {@code from} with {@code to}, quoting the path field when the line is a TOON array row. */
    private static String rewrite(String line, String from, String to) {
        if (!line.contains(from)) return line;
        String r = line.replace(from, to);
        if (line.stripLeading().matches("^[A-Za-z_][A-Za-z0-9_]*\\s*:.*")) return r;
        int at = r.indexOf(to);
        int end = r.indexOf(',', at);
        if (end < 0) end = r.length();
        return r.substring(0, at) + '"' + r.substring(at, end) + '"' + r.substring(end);
    }

    private static void seed(PipelineConfig cfg, String sample) throws Exception {
        Path inbox = Files.createDirectories(Path.of(cfg.dirs().poll()));
        Path src = REPO.resolve("spaces/demo/data/samples").resolve(sample);
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
}
