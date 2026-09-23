package com.gamma.inspector;

import com.gamma.etl.PipelineConfig;
import com.gamma.event.EventLog;
import com.gamma.parse.Asn1ParserPlugin;
import com.gamma.parse.ParseResult;
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
import java.util.ArrayList;
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

    /**
     * Operator decision 2026-09-23: a stored ASN.1 module is a path-jailed {@code .asn} FILE. The committed
     * msc_cdr pipeline with its inline grammar moved into a {@code .asn} file ({@code asn1.grammar_file})
     * must PREVIEW and INGEST exactly as the inline original does — same tree, same rows, every segment.
     */
    @Test
    void mscCdrWithItsGrammarInAnAsnFilePreviewsAndIngestsIdenticallyToInline(@TempDir Path dir) throws Exception {
        Path inlineDir = Files.createDirectories(dir.resolve("inline"));
        Path fileDir = Files.createDirectories(dir.resolve("file"));
        PipelineConfig inline = stage(inlineDir, "config/msc/msc_cdr_pipeline.toon");
        PipelineConfig file = stageWithGrammarFile(fileDir, "config/msc/msc_cdr_pipeline.toon");
        assertNull(file.schemas().ingesterConfig().get("grammar_text"), "the file run must not carry the text");
        assertEquals("msc_cdr.asn", file.schemas().ingesterConfig().get("grammar"),
                "the authored sibling name is what the config keeps (and a save writes back)");
        assertEquals(fileDir.resolve("config/msc/msc_cdr.asn").toAbsolutePath().normalize(),
                file.schemas().ingesterGrammar(), "resolved beside the pipeline, never against the CWD");

        // preview: the same sample through POST /parsers/asn1/preview's plugin, text vs file. A preview has
        // no config file, so its relative ref resolves from the bound Space's config root.
        byte[] sample = Files.readAllBytes(REPO.resolve("spaces/demo/data/samples/msc_cdr/MSC01_20260801_0800.ber"));
        Asn1ParserPlugin plugin = new Asn1ParserPlugin();
        Map<String, Object> inlineIc = inline.schemas().ingesterConfig();
        Map<String, Object> fileIc = file.schemas().ingesterConfig();
        ParseResult viaText = plugin.preview(sample, Map.of("asn1", Map.of(
                "grammar", inlineIc.get("grammar_text"), "root_type", inlineIc.get("root_type"))));
        ParseResult viaFile;
        SpaceConfigRoot.register("demo-corpus-test", fileDir.resolve("config"));
        MDC.put(EventLog.SPACE_MDC_KEY, "demo-corpus-test");
        try {
            viaFile = plugin.preview(sample, Map.of("asn1", Map.of(
                    "grammar_file", "msc/msc_cdr.asn", "root_type", fileIc.get("root_type"))));
        } finally {
            MDC.remove(EventLog.SPACE_MDC_KEY);
            SpaceConfigRoot.forget("demo-corpus-test");
        }
        assertEquals(13, ((ParseResult.Tree) viaText).recordCount());
        assertEquals(viaText, viaFile, "the preview tree is identical");

        // ingest: the real path, both configs, every segment's rows
        seed(inline, "msc_cdr/MSC01_20260801_0800.ber");
        seed(file, "msc_cdr/MSC01_20260801_0800.ber");
        CollectorProcessor.run(inline);
        CollectorProcessor.run(file);
        Path inlineDb = Path.of(inline.dirs().database());
        Path fileDb = Path.of(file.dirs().database());
        assertEquals(subdirs(inlineDb), subdirs(fileDb));
        for (String seg : List.of("moCallRecord", "mtCallRecord", "moSMSRecord")) {
            List<String> a = rows(inlineDb.resolve(seg));
            assertFalse(a.isEmpty(), seg);
            assertEquals(a, rows(fileDb.resolve(seg)), seg + " rows are identical");
        }
    }

    /**
     * {@link #stage}, then move the inline {@code asn1.grammar} text into {@code msc_cdr.asn} BESIDE the
     * pipeline, referenced by its bare sibling name — the spelling a satellite ref resolves from
     * ({@code SCHEMA-FILE-RESOLVES-AGAINST-CWD-1}).
     */
    private static PipelineConfig stageWithGrammarFile(Path dir, String pipeline) throws Exception {
        stage(dir, pipeline);   // writes the verbatim copy into the temp Space
        Path toon = dir.resolve(pipeline);
        List<String> out = new ArrayList<>();
        boolean moved = false;
        for (String line : Files.readAllLines(toon)) {
            String t = line.stripLeading();
            if (!moved && t.startsWith("grammar: \"") && t.endsWith("\"")) {
                Path asn = toon.resolveSibling("msc_cdr.asn");
                Files.writeString(asn, t.substring("grammar: \"".length(), t.length() - 1));
                out.add(line.substring(0, line.length() - t.length()) + "grammar_file: msc_cdr.asn");
                moved = true;
            } else {
                out.add(line);
            }
        }
        assertTrue(moved, "the committed msc_cdr pipeline carries its grammar inline");
        Files.write(toon, out);
        return PipelineConfig.load(toon.toString());
    }

    /** Every row of a Parquet tree, rendered and sorted — the content, independent of file layout. */
    private static List<String> rows(Path root) throws Exception {
        String glob = root.toString().replace('\\', '/') + "/**/*.parquet";
        List<String> out = new ArrayList<>();
        try (Connection c = DriverManager.getConnection("jdbc:duckdb:");
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT * FROM read_parquet('" + glob + "')")) {
            int n = rs.getMetaData().getColumnCount();
            while (rs.next()) {
                StringBuilder b = new StringBuilder();
                for (int i = 1; i <= n; i++) b.append(rs.getMetaData().getColumnName(i)).append('=')
                        .append(rs.getString(i)).append('|');
                out.add(b.toString());
            }
        }
        out.sort(null);
        return out;
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

    /**
     * {@code PARTITION-KEY-VALIDATION-GAPS-1} (c) — the shipped {@code excel_example} (in {@code spaces/default})
     * used {@code partitionKey: CATEGORY}, a DATE partition over a text column: no value parses, so every row
     * landed under {@code __HIVE_DEFAULT_PARTITION__}. It now partitions on the category as text.
     */
    @Test
    void excelExamplePartitionsByCategoryNotUnderTheHiveDefault(@TempDir Path dir) throws Exception {
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
}
