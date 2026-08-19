package com.gamma.etl;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the unified {@code parsing:} block (docs/parsing-options-reference.md §5/§8.5):
 * {@code parsing.delimited} aliases the legacy {@code processing.csv_settings},
 * {@code parsing.plugin} aliases {@code processing.ingester}/{@code segments}/{@code ingester_config},
 * and a config with no {@code parsing:} block parses exactly as before.
 */
class UnifiedParsingBlockTest {

    private static final String SCHEMA = """
            partitionKey: TXN_DATE
            raw:
              name: t
              format: CSV
              fields[1]{name,selector,type}:
                ID,"0",VARCHAR
            mapping:
              canonicalName: t
              rawName: t
              rules[1]{targetColumn,sourceExpression,transformType}:
                ID,ID,DIRECT
            """;

    @Test
    void parsingDelimitedAliasesCsvSettings(@TempDir Path dir) throws Exception {
        // Legacy spelling …
        PipelineConfig legacy = load(dir, "old", """
                  csv_settings:
                    delimiter: "|"
                    has_header: false
                    skip_header_lines: 2
                    engine: java
                """, "");
        // … and the unified block must produce the identical CsvSettings.
        PipelineConfig unified = load(dir, "new", "", """
                parsing:
                  frontend: delimited
                  delimited:
                    delimiter: "|"
                    has_header: false
                    skip_header_lines: 2
                    engine: java
                """);
        assertEquals(legacy.csv(), unified.csv(), "parsing.delimited == csv_settings, key for key");
        assertNull(unified.fixedWidth());
        assertNull(unified.json());
        assertNull(unified.textRegex());
    }

    @Test
    void parsingKeysOverrideLegacyCsvSettings(@TempDir Path dir) throws Exception {
        PipelineConfig cfg = load(dir, "ovr", """
                  csv_settings:
                    delimiter: ","
                    skip_header_lines: 5
                """, """
                parsing:
                  frontend: delimited
                  delimited:
                    delimiter: "|"
                """);
        assertEquals("|", cfg.csv().delimiter(), "parsing.delimited wins over csv_settings");
        assertEquals(5, cfg.csv().skipHeaderLines(), "untouched legacy keys survive the overlay");
    }

    @Test
    void sharedEncodingAndCompressionOptionsApply(@TempDir Path dir) throws Exception {
        PipelineConfig cfg = load(dir, "enc", "", """
                parsing:
                  frontend: delimited
                  encoding: latin-1
                  compression: gzip
                """);
        assertEquals("latin-1", cfg.csv().encoding());
        assertEquals("gzip", cfg.csv().inputCompression());
    }

    // ── 5.2 dialect chars: quote / escape / comment ─────────────────────────────

    @Test
    void quoteEscapeCommentAreReadFromBothSpellings(@TempDir Path dir) throws Exception {
        PipelineConfig unified = load(dir, "qec", "", """
                parsing:
                  frontend: delimited
                  delimited:
                    quote: "'"
                    escape: "~"
                    comment: ";"
                """);
        assertEquals("'", unified.csv().quote());
        assertEquals("~", unified.csv().escape());
        assertEquals(";", unified.csv().comment());

        PipelineConfig legacy = load(dir, "qecl", """
                  csv_settings:
                    quote: "'"
                """, "");
        assertEquals("'", legacy.csv().quote(), "legacy csv_settings spelling reads the same key");
    }

    @Test
    void dialectCharsDefaultToNullWhenAbsent(@TempDir Path dir) throws Exception {
        PipelineConfig cfg = load(dir, "qed", "", """
                parsing:
                  frontend: delimited
                  delimited:
                    delimiter: "|"
                """);
        assertNull(cfg.csv().quote());
        assertNull(cfg.csv().escape());
        assertNull(cfg.csv().comment());
    }

    /** Fail-closed: a value the engine cannot honor must never load looking honored. */
    @Test
    void multiCharDialectCharFailsLoad(@TempDir Path dir) {
        Exception e = assertThrows(IllegalArgumentException.class, () -> load(dir, "qex", "", """
                parsing:
                  frontend: delimited
                  delimited:
                    quote: "ab"
                """));
        assertTrue(e.getMessage().contains("quote"), e.getMessage());
        assertTrue(e.getMessage().contains("single character"), e.getMessage());
    }

    @Test
    void noParsingBlockIsBehaviourPreserving(@TempDir Path dir) throws Exception {
        PipelineConfig cfg = load(dir, "leg", """
                  csv_settings:
                    delimiter: ","
                    engine: auto
                """, "");
        assertEquals(",", cfg.csv().delimiter());
        assertTrue(cfg.csv().hasHeader(), "delimited default has_header=true untouched");
        assertNull(cfg.json());
        assertNull(cfg.textRegex());
        assertNull(cfg.fixedWidth());
    }

    @Test
    void parsingPluginAliasesProcessingIngester(@TempDir Path dir) throws Exception {
        Path seg = dir.resolve("seg_main.toon");
        Files.writeString(seg, SCHEMA, StandardCharsets.UTF_8);
        PipelineConfig cfg = load(dir, "plg", "", """
                parsing:
                  frontend: plugin
                  plugin:
                    ingester: com.gamma.ingester.FixedWidthRecordIngester
                    segments:
                      MAIN: %s
                    ingester_config:
                      record_length: 24
                """.formatted(seg.toString().replace('\\', '/')));
        assertEquals("com.gamma.ingester.FixedWidthRecordIngester", cfg.schemas().ingesterClass());
        assertEquals(java.util.Set.of("MAIN"), cfg.schemas().segments().keySet());
        assertEquals("24", String.valueOf(cfg.schemas().ingesterConfig().get("record_length")));
    }

    @Test
    void pluginFrontendWithoutIngesterFailsLoad(@TempDir Path dir) {
        Exception e = assertThrows(IllegalArgumentException.class,
                () -> load(dir, "nip", "", "parsing:\n  frontend: plugin\n"));
        assertTrue(e.getMessage().contains("plugin"), e.getMessage());
    }

    // ── frontend: asn1 (first-class, definition-surface P3c) ───────────────────

    /** `frontend: asn1` is sugar for the plugin wiring: the asn1: block synthesizes the
     *  Asn1RecordIngester binding, with the grammar carried INLINE as grammar_text. */
    @Test
    void asn1FrontendSynthesizesThePluginWiring(@TempDir Path dir) throws Exception {
        Path seg = dir.resolve("seg_mo.toon");
        Files.writeString(seg, SCHEMA, StandardCharsets.UTF_8);
        PipelineConfig cfg = load(dir, "a1ok", "", """
                parsing:
                  frontend: asn1
                  asn1:
                    grammar: "CDR DEFINITIONS ::= BEGIN Record ::= SEQUENCE { id [0] IA5String } END"
                    root_type: Record
                    strictness: DER
                    file_header_length: 50
                    segments:
                      Record: %s
                """.formatted(seg.toString().replace('\\', '/')));
        assertEquals("com.gamma.ingester.Asn1RecordIngester", cfg.schemas().ingesterClass());
        assertEquals(java.util.Set.of("Record"), cfg.schemas().segments().keySet());
        assertTrue(String.valueOf(cfg.schemas().ingesterConfig().get("grammar_text")).contains("DEFINITIONS"),
                "the grammar travels inline as grammar_text, never as the path-jailed grammar key");
        assertNull(cfg.schemas().ingesterConfig().get("grammar"));
        assertEquals("Record", cfg.schemas().ingesterConfig().get("root_type"));
        assertEquals("DER", cfg.schemas().ingesterConfig().get("strictness"));
        assertEquals("50", String.valueOf(cfg.schemas().ingesterConfig().get("file_header_length")));
    }

    /** An empty grammar is preview-only TLV inspection — an ingest config must carry the module. */
    @Test
    void asn1FrontendWithoutGrammarTextFailsLoad(@TempDir Path dir) {
        Exception e = assertThrows(IllegalArgumentException.class, () -> load(dir, "a1ng", "", """
                parsing:
                  frontend: asn1
                  asn1:
                    root_type: Record
                """));
        assertTrue(e.getMessage().contains("asn1.grammar"), e.getMessage());
    }

    @Test
    void asn1FrontendWithoutSegmentsFailsLoad(@TempDir Path dir) {
        Exception e = assertThrows(IllegalArgumentException.class, () -> load(dir, "a1ns", "", """
                parsing:
                  frontend: asn1
                  asn1:
                    grammar: "X DEFINITIONS ::= BEGIN Y ::= IA5String END"
                    root_type: Y
                """));
        assertTrue(e.getMessage().contains("asn1.segments"), e.getMessage());
    }

    /** Which of the two ingester bindings would win is undefined — carrying both is refused. */
    @Test
    void asn1FrontendAlongsideAnExplicitPluginBlockIsRefused(@TempDir Path dir) {
        Exception e = assertThrows(IllegalArgumentException.class, () -> load(dir, "a1px", "", """
                parsing:
                  frontend: asn1
                  asn1:
                    grammar: "X DEFINITIONS ::= BEGIN Y ::= IA5String END"
                    root_type: Y
                  plugin:
                    ingester: com.gamma.ingester.FixedWidthRecordIngester
                """));
        assertTrue(e.getMessage().contains("synthesizes its own plugin ingester"), e.getMessage());
    }

    @Test
    void unknownFrontendInCsvSettingsAlsoRejected(@TempDir Path dir) {
        Exception e = assertThrows(IllegalArgumentException.class,
                () -> load(dir, "ufc", "  csv_settings:\n    frontend: florble\n", ""));
        assertTrue(e.getMessage().contains("Unknown parsing.frontend"), e.getMessage());
    }

    // ── helpers ──────────────────────────────────────────────────────────────────

    private static String fwd(Path p) { return p.toString().replace('\\', '/'); }

    /**
     * Build + load a pipeline; {@code procExtra} is appended inside {@code processing:} (already
     * indented two spaces), {@code topExtra} at the top level (e.g. the {@code parsing:} block).
     */
    private static PipelineConfig load(Path dir, String tag, String procExtra, String topExtra)
            throws Exception {
        Path schema = dir.resolve("schema_" + tag + ".toon");
        Files.writeString(schema, SCHEMA, StandardCharsets.UTF_8);
        String d = fwd(dir);
        String pipe =
                "name: UP_" + tag + "\n" +
                "version: 1\n" +
                "dirs:\n" +
                "  poll: " + d + "/inbox\n" +
                "  database: " + d + "/db\n" +
                "  backup: " + d + "/backup\n" +
                "  temp: " + d + "/temp\n" +
                "  errors: " + d + "/errors\n" +
                "  quarantine: " + d + "/quarantine\n" +
                "  status_dir: " + d + "/status\n" +
                "output:\n" +
                "  format: CSV\n" +
                "processing:\n" +
                "  threads: 1\n" +
                "  file_pattern: \"glob:**/*.csv\"\n" +
                ("plg".equals(tag) || "nip".equals(tag) || tag.startsWith("a1")
                        ? "" : "  schema_file: " + fwd(schema) + "\n") +
                procExtra +
                topExtra;
        Path p = dir.resolve("pipe_" + tag + ".toon");
        Files.writeString(p, pipe, StandardCharsets.UTF_8);
        return PipelineConfig.load(p.toString());
    }

    // ── reusable Grammar components (parsing.grammar) ──────────────────────────

    /**
     * `parsing.grammar: grammar/<id>` — what a Grammar-bound parser node lowers to — resolves to the
     * registry file ComponentStore writes, and its options drive the parse.
     */
    @Test
    void grammarRegistryRefResolvesToTheComponentFile(@TempDir Path dir) throws Exception {
        writeGrammarComponent(dir, "pipe_delimited", "delimiter: \"|\"\nskip_header_lines: 3\n");

        PipelineConfig cfg = load(dir, "gref", "", """
                parsing:
                  frontend: delimited
                  grammar: grammar/pipe_delimited
                """);

        assertEquals("|", cfg.csv().delimiter(), "the component's options drive the parse");
        assertEquals(3, cfg.csv().skipHeaderLines());
    }

    /** Inline keys still win over the referenced component — the "extractable, overridable" contract. */
    @Test
    void inlineKeysOverrideTheReferencedGrammar(@TempDir Path dir) throws Exception {
        writeGrammarComponent(dir, "pipe_delimited", "delimiter: \"|\"\nskip_header_lines: 3\n");

        PipelineConfig cfg = load(dir, "govr", "", """
                parsing:
                  frontend: delimited
                  grammar: grammar/pipe_delimited
                  delimited:
                    delimiter: ";"
                """);

        assertEquals(";", cfg.csv().delimiter(), "the inline override wins");
        assertEquals(3, cfg.csv().skipHeaderLines(), "…and the component supplies the rest");
    }

    /** parsing.grammar (design-of-record) wins over the legacy processing.grammar spelling. */
    @Test
    void parsingGrammarWinsOverLegacyProcessingGrammar(@TempDir Path dir) throws Exception {
        writeGrammarComponent(dir, "winner", "delimiter: \"|\"\n");
        writeGrammarComponent(dir, "loser", "delimiter: \"#\"\n");

        PipelineConfig cfg = load(dir, "gwin", "  grammar: registry/grammars/loser.toon\n", """
                parsing:
                  frontend: delimited
                  grammar: grammar/winner
                """);

        assertEquals("|", cfg.csv().delimiter());
    }

    /**
     * A Grammar component is an EXTRACTED `parsing:` block, so the block shape resolves too —
     * extraction is a move, not a transform. (The flat shape above is the legacy spelling and both
     * must keep working.)
     */
    @Test
    void aParsingShapedGrammarComponentResolves(@TempDir Path dir) throws Exception {
        writeGrammarComponent(dir, "block_form", """
                frontend: delimited
                delimited:
                  delimiter: "|"
                  skip_header_lines: 3
                """);

        PipelineConfig cfg = load(dir, "gblock", "", """
                parsing:
                  grammar: grammar/block_form
                """);

        assertEquals("|", cfg.csv().delimiter());
        assertEquals(3, cfg.csv().skipHeaderLines());
    }

    /** Inline still wins over a block-shaped component, exactly as over the flat one. */
    @Test
    void inlineKeysOverrideAParsingShapedGrammarComponent(@TempDir Path dir) throws Exception {
        writeGrammarComponent(dir, "block_form", """
                frontend: delimited
                delimited:
                  delimiter: "|"
                  skip_header_lines: 3
                """);

        PipelineConfig cfg = load(dir, "gblockovr", "", """
                parsing:
                  grammar: grammar/block_form
                  delimited:
                    delimiter: ";"
                """);

        assertEquals(";", cfg.csv().delimiter(), "the inline override wins");
        assertEquals(3, cfg.csv().skipHeaderLines(), "…and the component supplies the rest");
    }

    /** A ref naming no component fails loudly at load — never a silent default-delimiter parse. */
    @Test
    void unknownGrammarRefFailsLoudly(@TempDir Path dir) {
        Exception e = assertThrows(Exception.class, () -> load(dir, "gmiss", "", """
                parsing:
                  frontend: delimited
                  grammar: grammar/nope
                """));
        assertTrue(String.valueOf(e.getMessage()).contains("Grammar file not found"),
                "actual: " + e.getMessage());
    }

    /** Write a grammar component where ComponentStore puts it: <configDir>/registry/grammars/<id>.toon */
    private static void writeGrammarComponent(Path dir, String id, String body) throws Exception {
        Path g = dir.resolve("registry").resolve("grammars");
        Files.createDirectories(g);
        Files.writeString(g.resolve(id + ".toon"), "name: " + id + "\n" + body, StandardCharsets.UTF_8);
    }
}
