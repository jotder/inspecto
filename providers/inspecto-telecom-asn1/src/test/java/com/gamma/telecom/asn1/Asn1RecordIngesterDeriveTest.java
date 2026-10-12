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
 * Parsing D9 slice 1 end to end: {@code derive} over the tracked SYNTHETIC corpus case
 * {@code providers/asn-parser/corpus-synthetic/repeated_child} (invented values only, DATA-GOV-1).
 */
class Asn1RecordIngesterDeriveTest {

    private static final Path CASE = Path.of("..", "asn-parser", "corpus-synthetic", "repeated_child");

    private static final String SCHEMA = """
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

    private static final String DERIVE = """
            derive:
              Record:
                MSISDN:
                  select: ids.idEntry
                  where:
                    idType: 0
                  take: idValue
                IMSI:
                  select: ids.idEntry
                  where:
                    idType: 1
                  take: idValue
                OTHER:
                  select: ids.idEntry
                  where:
                    idType: 9
                  take: idValue
                UP_SUM:
                  reduce: usage.usage
                  op: sum
                  of: up
                UP_MIN:
                  reduce: usage.usage
                  op: min
                  of: up
                DOWN_MAX:
                  reduce: usage.usage
                  op: max
                  of: down
                N_USAGE:
                  reduce: usage.usage
                  op: count
                GROUPS:
                  reduce: usage.usage
                  op: join
                  of: group
            """;

    private static byte[] corpusBytes() throws Exception {
        StringBuilder hex = new StringBuilder();
        for (String line : Files.readAllLines(CASE.resolve("data.hex"), StandardCharsets.UTF_8))
            if (!line.startsWith("#")) hex.append(line);
        return HexFormat.of().parseHex(hex.toString().replaceAll("\\s", ""));
    }

    private static List<Object[]> run(Path dir, String extra, CapturingSink sink) throws Exception {
        PipelineConfig cfg = PipelineConfig.load(writePipeline(dir, extra).toString());
        File dat = dir.resolve("cdr.ber").toFile();
        Files.write(dat.toPath(), corpusBytes());
        new Asn1RecordIngester().ingest(dat, sink, 0, cfg);
        return sink.emitted;
    }

    @Test
    void derivedColumnsAppendAfterRawFieldsBeforeEventTypeAndAreStrings(@TempDir Path dir) throws Exception {
        CapturingSink sink = new CapturingSink();
        List<Object[]> rows = run(dir, DERIVE, sink);

        assertEquals(List.of("ID", "MSISDN", "IMSI", "OTHER", "UP_SUM", "UP_MIN", "DOWN_MAX",
                "N_USAGE", "GROUPS", "EVENT_TYPE"), sink.defined.get("Record"));
        assertEquals(2, rows.size(), "row cardinality unchanged: one row per record");
        // record 1: 3 usage elements (up 10,5,100 · down 20,7,1 · group 1,1,2)
        assertArrayEquals(new Object[]{"5", "1234", "ab", null, "115", "5", "20", "3", "1,1,2", "Record"},
                rows.get(0));
        // record 2: ids absent, usage empty: NULL everywhere, never 0
        assertArrayEquals(new Object[]{"6", null, null, null, null, null, null, null, null, "Record"},
                rows.get(1));
    }

    @Test
    void aProfileWithoutDeriveIsByteIdenticalToBefore(@TempDir Path dir) throws Exception {
        CapturingSink sink = new CapturingSink();
        List<Object[]> rows = run(dir, "", sink);
        assertEquals(List.of("ID", "EVENT_TYPE"), sink.defined.get("Record"));
        assertArrayEquals(new Object[]{"5", "Record"}, rows.get(0));
        assertArrayEquals(new Object[]{"6", "Record"}, rows.get(1));
    }

    @Test
    void aListOverMaxListElementsFailsTheFile(@TempDir Path dir) {
        IOException e = assertThrows(IOException.class,
                () -> run(dir, DERIVE + "max_list_elements: 2",
                        new CapturingSink()));
        assertTrue(e.getMessage().startsWith("ASN1-DERIVE-LIST-TOO-LARGE"), e.getMessage());
    }

    @Test
    void aColumnClashingWithARawFieldIsRefusedAtDeclaration(@TempDir Path dir) {
        String clash = "derive:\n  Record:\n    ID:\n      reduce: usage.usage\n      op: count\n";
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> run(dir, clash, new CapturingSink()));
        assertTrue(e.getMessage().startsWith("ASN1-DERIVE-COLUMN-CLASH"), e.getMessage());
    }

    @Test
    void aDeriveKeyThatIsNotASegmentIsRefused(@TempDir Path dir) {
        String bad = "derive:\n  Nope:\n    C:\n      reduce: usage.usage\n      op: count\n";
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> run(dir, bad, new CapturingSink()));
        assertTrue(e.getMessage().startsWith("ASN1-DERIVE-UNKNOWN-SEGMENT"), e.getMessage());
    }

    // helpers

    private static Path writePipeline(Path dir, String extraIngesterConfig) throws Exception {
        String d = dir.toString().replace('\\', '/');
        Path grammar = dir.resolve("cdr.asn");
        Files.copy(CASE.resolve("grammar.asn"), grammar, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        Path schema = dir.resolve("rec_schema.toon");
        Files.writeString(schema, SCHEMA, StandardCharsets.UTF_8);
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
                  ingester_config:
                    grammar: %s
                    root_type: Record
                    %s
                """).formatted(d, d, d, d, d, d, d, schema.toString().replace('\\', '/'),
                grammar.toString().replace('\\', '/'),
                extraIngesterConfig.stripTrailing().replace("\n", "\n    ")), StandardCharsets.UTF_8);
        return pipe;
    }

    private static final class CapturingSink implements RecordSink {
        final Map<String, List<String>> defined = new LinkedHashMap<>();
        final List<Object[]> emitted = new ArrayList<>();
        @Override public void define(String segmentKey, List<String> columns) { defined.put(segmentKey, columns); }
        @Override public void emit(String segmentKey, Object... values) { emitted.add(values); }
    }
}
