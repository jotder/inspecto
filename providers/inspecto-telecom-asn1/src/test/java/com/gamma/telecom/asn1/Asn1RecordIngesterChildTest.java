package com.gamma.telecom.asn1;

import com.gamma.etl.PipelineConfig;
import com.gamma.etl.RecordSink;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Parsing D9 slice 2 end to end: {@code children} (explode) over the tracked SYNTHETIC corpus case
 * {@code providers/asn-parser/corpus-synthetic/repeated_child} (invented values only, DATA-GOV-1).
 */
class Asn1RecordIngesterChildTest {

    private static final Path CASE = Path.of("..", "asn-parser", "corpus-synthetic", "repeated_child");

    private static final String RECORD_SCHEMA = """
            partitionKey: ID
            raw:
              name: rec
              format: CSV
              fields[1]{name,selector,type}:
                ID,id,VARCHAR
            mapping:
              canonicalName: rec
              rawName: rec
              rules[1]{targetColumn,sourceExpression,transformType}:
                ID,ID,DIRECT
            """;

    private static String childSchema(String selectors) {
        return """
                partitionKey: PARENT_ID
                raw:
                  name: use
                  format: CSV
                  fields[5]{name,selector,type}:
                    %s
                mapping:
                  canonicalName: use
                  rawName: use
                  rules[1]{targetColumn,sourceExpression,transformType}:
                    PARENT_ID,PARENT_ID,DIRECT
                """.formatted(selectors);
    }

    private static final String GOOD_SELECTORS =
            "PARENT_ID,$parent.id,VARCHAR\n    IDX,$index,VARCHAR\n    GRP,group,VARCHAR\n    UP,up,VARCHAR\n    DOWN,down,VARCHAR";

    private static final String CHILDREN = "children:\n  Record:\n    usage.usage: Usage\n";

    @Test
    void eachListElementBecomesAChildRowCarryingTheParentAndTheIndex(@TempDir Path dir) throws Exception {
        CapturingSink sink = new CapturingSink();
        run(dir, CHILDREN, GOOD_SELECTORS, sink);

        assertEquals(List.of("ID", "EVENT_TYPE"), sink.defined.get("Record"));
        assertEquals(List.of("PARENT_ID", "IDX", "GRP", "UP", "DOWN", "EVENT_TYPE"), sink.defined.get("Usage"));
        // record 1 (id 5, three usage elements): its own row, then one child row per element; record 2 (empty list): its own row only
        assertEquals(5, sink.emitted.size());
        assertEquals("Record", sink.keys.get(0));
        assertArrayEquals(new Object[]{"5", "Record"}, sink.emitted.get(0));
        assertEquals(List.of("Usage", "Usage", "Usage"), sink.keys.subList(1, 4));
        assertArrayEquals(new Object[]{"5", "0", "1", "10", "20", "Usage"}, sink.emitted.get(1));
        assertArrayEquals(new Object[]{"5", "1", "1", "5", "7", "Usage"}, sink.emitted.get(2));
        assertArrayEquals(new Object[]{"5", "2", "2", "100", "1", "Usage"}, sink.emitted.get(3));
        assertEquals("Record", sink.keys.get(4));
        assertArrayEquals(new Object[]{"6", "Record"}, sink.emitted.get(4));
    }

    /**
     * A {@code derive} declared on the CHILD segment is declared after the child's raw columns (before EVENT_TYPE) and
     * evaluated per child element. The synthetic {@code Usage} element holds only scalars (no nested list), so the
     * select finds nothing and the derived value is null; the point is that the child row carries the extra column
     * in the declared position and the parent row is untouched.
     */
    @Test
    void aDeriveDeclaredOnAChildSegmentAddsItsColumnToEveryChildRow(@TempDir Path dir) throws Exception {
        CapturingSink sink = new CapturingSink();
        run(dir, CHILDREN + "derive:\n  Usage:\n    TAGGED:\n      select: tags.tag\n      where:\n        kind: 1\n      take: val\n",
                GOOD_SELECTORS, sink);

        assertEquals(List.of("PARENT_ID", "IDX", "GRP", "UP", "DOWN", "TAGGED", "EVENT_TYPE"), sink.defined.get("Usage"));
        assertEquals(List.of("ID", "EVENT_TYPE"), sink.defined.get("Record"), "the parent segment has no derive");
        assertEquals(5, sink.emitted.size());
        assertArrayEquals(new Object[]{"5", "0", "1", "10", "20", null, "Usage"}, sink.emitted.get(1));
        assertArrayEquals(new Object[]{"5", "2", "2", "100", "1", null, "Usage"}, sink.emitted.get(3));
        assertArrayEquals(new Object[]{"5", "Record"}, sink.emitted.get(0));
    }

    @Test
    void aProfileWithoutChildrenGivesTheSameRowsAsBefore(@TempDir Path dir) throws Exception {
        CapturingSink sink = new CapturingSink();
        run(dir, "", GOOD_SELECTORS, sink);
        assertEquals(List.of("Record", "Record"), sink.keys);
        assertEquals(List.of("ID", "EVENT_TYPE"), sink.defined.get("Record"));
    }

    @Test
    void moreChildRowsThanTheCapFailsTheFileNeverTruncates(@TempDir Path dir) {
        IOException e = assertThrows(IOException.class,
                () -> run(dir, CHILDREN + "max_child_rows_per_record: 2\n", GOOD_SELECTORS, new CapturingSink()));
        assertTrue(e.getMessage().startsWith("ASN1-CHILD-TOO-MANY-ROWS"), e.getMessage());
        // exactly at the cap is fine
        assertDoesNotFail(dir.resolve("ok"), CHILDREN + "max_child_rows_per_record: 3\n");
    }

    @Test
    void aChildSegmentThatIsNotDeclaredIsRefused(@TempDir Path dir) {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> run(dir, "children:\n  Record:\n    usage.usage: Nope\n", GOOD_SELECTORS, new CapturingSink()));
        assertTrue(e.getMessage().startsWith("ASN1-CHILD-UNKNOWN-SEGMENT"), e.getMessage());
    }

    @Test
    void aReservedSelectorOutsideTheTwoSupportedOnesIsRefused(@TempDir Path dir) {
        String bad = GOOD_SELECTORS.replace("$index", "$other");
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> run(dir, CHILDREN, bad, new CapturingSink()));
        assertTrue(e.getMessage().startsWith("ASN1-CHILD-BAD-SELECTOR"), e.getMessage());
    }

    // helpers

    private static void assertDoesNotFail(Path dir, String children) {
        try {
            Files.createDirectories(dir);
            run(dir, children, GOOD_SELECTORS, new CapturingSink());
        } catch (Exception e) {
            throw new AssertionError("a list exactly at the cap must ingest: " + e.getMessage(), e);
        }
    }

    private static byte[] corpusBytes() throws Exception {
        StringBuilder hex = new StringBuilder();
        for (String line : Files.readAllLines(CASE.resolve("data.hex"), StandardCharsets.UTF_8))
            if (!line.startsWith("#")) hex.append(line);
        return HexFormat.of().parseHex(hex.toString().replaceAll("\\s", ""));
    }

    private static void run(Path dir, String extra, String childSelectors, CapturingSink sink) throws Exception {
        PipelineConfig cfg = PipelineConfig.load(writePipeline(dir, extra, childSelectors).toString());
        File dat = dir.resolve("cdr.ber").toFile();
        Files.write(dat.toPath(), corpusBytes());
        new Asn1RecordIngester().ingest(dat, sink, 0, cfg);
    }

    private static Path writePipeline(Path dir, String extraIngesterConfig, String childSelectors) throws Exception {
        String d = dir.toString().replace('\\', '/');
        Path grammar = dir.resolve("cdr.asn");
        Files.copy(CASE.resolve("grammar.asn"), grammar, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        Path schema = dir.resolve("rec_schema.toon");
        Files.writeString(schema, RECORD_SCHEMA, StandardCharsets.UTF_8);
        Path child = dir.resolve("use_schema.toon");
        Files.writeString(child, childSchema(childSelectors), StandardCharsets.UTF_8);
        Path pipe = dir.resolve("cdr_pipeline.toon");
        Files.writeString(pipe, ("""
                name: CDR_ETL
                version: 1
                dirs:
                  poll: %s/inbox
                  database: %s/db
                  backup: %s/backup
                  temp: %s/temp
                  errors: %s/errors
                  quarantine: %s/quarantine
                  status_dir: %s/status
                output:
                  format: CSV
                processing:
                  threads: 1
                  file_pattern: "glob:**/*.ber"
                  ingester: com.gamma.telecom.asn1.Asn1RecordIngester
                  segments:
                    Record: %s
                    Usage: %s
                  ingester_config:
                    grammar: %s
                    root_type: Record
                    %s
                """).formatted(d, d, d, d, d, d, d, schema.toString().replace('\\', '/'),
                child.toString().replace('\\', '/'), grammar.toString().replace('\\', '/'),
                extraIngesterConfig.stripTrailing().replace("\n", "\n    ")), StandardCharsets.UTF_8);
        return pipe;
    }

    private static final class CapturingSink implements RecordSink {
        final Map<String, List<String>> defined = new LinkedHashMap<>();
        final List<String> keys = new ArrayList<>();
        final List<Object[]> emitted = new ArrayList<>();
        @Override public void define(String segmentKey, List<String> columns) { defined.put(segmentKey, columns); }
        @Override public void emit(String segmentKey, Object... values) { keys.add(segmentKey); emitted.add(values); }
    }
}
