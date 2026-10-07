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
 * The ASN.1 half of {@link DemoCorpusIngestTest} (MODULE-REORG-1 P7): the {@code spaces/demo} msc_cdr demos and
 * the {@code spaces/default} asn1_example ingest EXACTLY what their sample says they should, on the REAL ingest
 * path ({@code CollectorProcessor.run}). Split out because the ASN.1 parser now lives in this module, not the
 * engine; the format-neutral demos (in_recharges, gl_journal, excel, orders) stay in the engine's class.
 * Staging and assertion helpers are deliberately copied, not shared: a shared test-jar would be a new module edge
 * for ~60 lines.
 */
class DemoAsn1CorpusIngestTest {

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
        // DATE-PARTITION-ON-TEXT-SHIPPED-1: EVENT_TIME is declared TIMESTAMP, so every record is cut into a
        // real day folder — none under the Hive default.
        for (String seg : List.of("moCallRecord", "mtCallRecord", "moSMSRecord"))
            assertEquals(List.of("year=2026/month=08/day=01"), leafPartitions(db.resolve(seg)), seg + " day folders");
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
     * Decode Profile (trust design slice C4): the committed msc_cdr demo split into a per-vendor profile
     * ({@code config/vendors/demo_msc/demo_msc.decode.toon} holding the grammar file, root_type, strictness
     * and all three segments, their files beside the profile) and a Pipeline that keeps only
     * {@code profile_file}. It must preview and ingest exactly as the inline original — same tree, the same
     * rows in every segment.
     */
    @Test
    @SuppressWarnings("unchecked")
    void mscCdrSplitIntoADecodeProfilePreviewsAndIngestsIdenticallyToInline(@TempDir Path dir) throws Exception {
        Path inlineDir = Files.createDirectories(dir.resolve("inline"));
        Path profiledDir = Files.createDirectories(dir.resolve("profiled"));
        PipelineConfig inline = stage(inlineDir, "config/msc/msc_cdr_pipeline.toon");

        stage(profiledDir, "config/msc/msc_cdr_pipeline.toon");
        Path toon = profiledDir.resolve("config/msc/msc_cdr_pipeline.toon");
        Path vendor = Files.createDirectories(profiledDir.resolve("config/vendors/demo_msc"));
        Map<String, Object> raw = com.gamma.config.io.ConfigCodec.toMap(Files.readString(toon));
        Map<String, Object> parsing = (Map<String, Object>) raw.get("parsing");
        Map<String, Object> asn1 = (Map<String, Object>) parsing.get("asn1");
        Files.writeString(vendor.resolve("demo_msc.asn"), String.valueOf(asn1.get("grammar")));
        for (Object schema : ((Map<String, Object>) asn1.get("segments")).values())
            Files.move(toon.resolveSibling(String.valueOf(schema)), vendor.resolve(String.valueOf(schema)));
        Map<String, Object> profile = new java.util.LinkedHashMap<>();
        profile.put("grammar_file", "demo_msc.asn");
        profile.put("root_type", asn1.get("root_type"));
        profile.put("strictness", asn1.get("strictness"));
        profile.put("segments", asn1.get("segments"));
        Files.writeString(vendor.resolve("demo_msc.decode.toon"),
                com.gamma.config.io.ConfigCodec.toToon(Map.of("asn1", profile)));
        parsing.put("asn1", Map.of("profile_file", "../vendors/demo_msc/demo_msc.decode.toon"));
        Files.writeString(toon, com.gamma.config.io.ConfigCodec.toToon(raw));
        PipelineConfig profiled = PipelineConfig.load(toon.toString());
        assertEquals(vendor.resolve("demo_msc.asn").toAbsolutePath().normalize(), profiled.schemas().ingesterGrammar(),
                "the profile's grammar resolves beside the profile");

        byte[] sample = Files.readAllBytes(REPO.resolve("spaces/demo/data/samples/msc_cdr/MSC01_20260801_0800.ber"));
        Asn1ParserPlugin plugin = new Asn1ParserPlugin();
        ParseResult viaText = plugin.preview(sample, Map.of("asn1", Map.of(
                "grammar", inline.schemas().ingesterConfig().get("grammar_text"),
                "root_type", inline.schemas().ingesterConfig().get("root_type"))));
        ParseResult viaProfile = plugin.preview(sample,
                Map.of("asn1", Map.of("profile_file", "../vendors/demo_msc/demo_msc.decode.toon")), toon.getParent());
        assertEquals(13, ((ParseResult.Tree) viaText).recordCount());
        assertEquals(viaText, viaProfile, "the preview tree is identical");

        seed(inline, "msc_cdr/MSC01_20260801_0800.ber");
        seed(profiled, "msc_cdr/MSC01_20260801_0800.ber");
        CollectorProcessor.run(inline);
        CollectorProcessor.run(profiled);
        Path inlineDb = Path.of(inline.dirs().database());
        Path profiledDb = Path.of(profiled.dirs().database());
        assertEquals(subdirs(inlineDb), subdirs(profiledDb));
        for (String seg : List.of("moCallRecord", "mtCallRecord", "moSMSRecord")) {
            List<String> a = rows(inlineDb.resolve(seg));
            assertFalse(a.isEmpty(), seg);
            assertEquals(a, rows(profiledDb.resolve(seg)), seg + " rows are identical");
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

    /**
     * {@code DATE-PARTITION-ON-TEXT-SHIPPED-1} — the shipped {@code asn1_example} used {@code partitionKey: IMSI},
     * a DATE partition over a text identifier, so every record landed under {@code __HIVE_DEFAULT_PARTITION__}.
     * Its grammar carries no time field, so it now partitions on the IMSI as text.
     */
    @Test
    void asn1ExamplePartitionsByImsiNotUnderTheHiveDefault(@TempDir Path dir) throws Exception {
        PipelineConfig cfg = stage(dir, "default", "config/asn1_example/asn1_example_pipeline.toon");
        seed(cfg, "default", "asn1_example/CDR_20260801.ber");

        CollectorProcessor.run(cfg);

        Path seg = Path.of(cfg.dirs().database()).resolve("moCallRecord");
        assertEquals(List.of("served_imsi=42", "served_imsi=77", "served_imsi=91"),
                subdirs(seg).stream().filter(p -> !p.startsWith(".")).toList(),   // not .staging
                "one folder per IMSI of the sample, none the Hive default");
        assertEquals(0, count(seg, "IMSI IS NULL"), "the mapped IMSI column is written, not renamed");
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
