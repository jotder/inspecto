package com.gamma.ingester;

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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Unit tests for {@link Asn1RecordIngester} — the ASN.1 tree onto segment schemas. */
class Asn1RecordIngesterTest {

    /**
     * A union-style root, the shape the vendor corpus uses: the record types are tagged components
     * of a SEQUENCE, so a bound record's name is the matched alternative (moCallRecord/smsRecord)
     * — which is the segment key.
     */
    private static final String GRAMMAR = """
            CDR DEFINITIONS IMPLICIT TAGS ::= BEGIN
            CallEventRecord ::= SEQUENCE
            {
              moCallRecord [0] MoCall,
              smsRecord    [1] Sms
            }
            MoCall ::= SEQUENCE
            {
              imsi     [0] IA5String,
              duration [1] INTEGER,
              party    [2] Party OPTIONAL
            }
            Party ::= SEQUENCE { number [0] IA5String }
            Sms ::= SEQUENCE { imsi [0] IA5String }
            END
            """;

    private static final String MO_CALL_SCHEMA = """
            partitionKey: IMSI
            raw:
              name: mo_call
              format: CSV
              fields[3]{name,selector,type}:
                IMSI,imsi,VARCHAR
                DURATION,duration,VARCHAR
                B_NUMBER,party.number,VARCHAR
            mapping:
              canonicalName: mo_call
              rawName: mo_call
              rules[3]{targetColumn,sourceExpression,transformType}:
                IMSI,IMSI,DIRECT
                DURATION,DURATION,DIRECT
                B_NUMBER,B_NUMBER,DIRECT
            """;

    // A0 = moCallRecord, 0x0F content bytes: imsi="42" (4) + duration=7 (3) + party{number="9999"} (8)
    private static final String MO_CALL_HEX = "A0 0F 80 02 34 32 81 01 07 A2 06 80 04 39 39 39 39";
    // A0 = moCallRecord, 0x07 content bytes: imsi="77" (4) + duration=3 (3); OPTIONAL party absent
    private static final String MO_CALL_NO_PARTY_HEX = "A0 07 80 02 37 37 81 01 03";
    // A1 = smsRecord — a real record type, but not a declared segment here
    private static final String SMS_HEX = "A1 06 80 04 35 35 35 35";

    private static byte[] hex(String... parts) {
        StringBuilder all = new StringBuilder();
        for (String p : parts) all.append(p);
        return HexFormat.of().parseHex(all.toString().replace(" ", ""));
    }

    @Test
    void flattensRecordsOntoSegmentColumnsByDottedSelector(@TempDir Path dir) throws Exception {
        PipelineConfig cfg = PipelineConfig.load(writePipeline(dir).toString());
        File dat = write(dir, "cdr.ber", hex(MO_CALL_HEX, MO_CALL_NO_PARTY_HEX));

        CapturingSink sink = new CapturingSink();
        new Asn1RecordIngester().ingest(dat, sink, 0, cfg);

        assertEquals(List.of("IMSI", "DURATION", "B_NUMBER", "EVENT_TYPE"), sink.defined.get("moCallRecord"),
                "raw.fields columns plus the derived EVENT_TYPE");
        assertEquals(2, sink.emitted.size());
        // Nested path party.number resolves; INTEGER stringifies; EVENT_TYPE carries the segment key.
        assertArrayEquals(new Object[]{"42", "7", "9999", "moCallRecord"}, sink.emitted.get(0));
        // An absent OPTIONAL yields NULL rather than failing the record.
        assertArrayEquals(new Object[]{"77", "3", null, "moCallRecord"}, sink.emitted.get(1));
    }

    @Test
    void aRecordTypeThatIsNotADeclaredSegmentIsJunkNotAnError(@TempDir Path dir) throws Exception {
        PipelineConfig cfg = PipelineConfig.load(writePipeline(dir).toString());
        File dat = write(dir, "cdr.ber", hex(MO_CALL_HEX, SMS_HEX, MO_CALL_NO_PARTY_HEX));

        CapturingSink sink = new CapturingSink();
        new Asn1RecordIngester().ingest(dat, sink, 0, cfg);

        assertEquals(2, sink.emitted.size(), "only the declared segment's records emit");
        assertEquals(1, sink.junks, "smsRecord is a known type but an undeclared segment");
        assertEquals(0, sink.rejects);
    }

    @Test
    void aSelectorNamingAContainerYieldsNullNotAStringifiedSubtree(@TempDir Path dir) throws Exception {
        // B_NUMBER's selector is "party" — a sub-record, not a leaf.
        PipelineConfig cfg = PipelineConfig.load(
                writePipeline(dir, MO_CALL_SCHEMA.replace("B_NUMBER,party.number", "B_NUMBER,party")).toString());
        File dat = write(dir, "cdr.ber", hex(MO_CALL_HEX));

        CapturingSink sink = new CapturingSink();
        new Asn1RecordIngester().ingest(dat, sink, 0, cfg);

        assertEquals(1, sink.emitted.size());
        assertNull(sink.emitted.get(0)[2], "a container is not a column");
        assertEquals("42", sink.emitted.get(0)[0], "sibling leaves are unaffected");
    }

    @Test
    void aCorruptRecordFailsTheFileRatherThanIngestingThePrefix(@TempDir Path dir) throws Exception {
        PipelineConfig cfg = PipelineConfig.load(writePipeline(dir).toString());
        // A good record, then a tag matching moCallRecord whose length runs past end of file.
        File dat = write(dir, "cdr.ber", hex(MO_CALL_HEX, "A0 7F 80 02 34 32"));

        CapturingSink sink = new CapturingSink();
        IOException e = assertThrows(IOException.class,
                () -> new Asn1RecordIngester().ingest(dat, sink, 0, cfg));
        assertTrue(e.getMessage().contains("ASN.1 decode failed"), e.getMessage());
    }

    @Test
    void missingGrammarOrRootTypeIsAConfigError(@TempDir Path dir) throws Exception {
        PipelineConfig noRoot = PipelineConfig.load(
                writePipeline(dir, MO_CALL_SCHEMA, "grammar: %s".formatted(grammarPath(dir))).toString());
        File dat = write(dir, "cdr.ber", hex(MO_CALL_HEX));
        Exception e = assertThrows(IllegalArgumentException.class,
                () -> new Asn1RecordIngester().ingest(dat, new CapturingSink(), 0, noRoot));
        assertTrue(e.getMessage().contains("root_type"), e.getMessage());
    }

    @Test
    void anUnreadableGrammarPathIsAConfigError(@TempDir Path dir) throws Exception {
        PipelineConfig cfg = PipelineConfig.load(writePipeline(dir, MO_CALL_SCHEMA,
                "grammar: %s/nope.asn\n    root_type: CallEventRecord".formatted(
                        dir.toString().replace('\\', '/'))).toString());
        File dat = write(dir, "cdr.ber", hex(MO_CALL_HEX));
        Exception e = assertThrows(IllegalArgumentException.class,
                () -> new Asn1RecordIngester().ingest(dat, new CapturingSink(), 0, cfg));
        assertTrue(e.getMessage().contains("not readable"), e.getMessage());
    }

    // ── helpers ──────────────────────────────────────────────────────────────────

    private static File write(Path dir, String name, byte[] bytes) throws Exception {
        File f = dir.resolve(name).toFile();
        Files.write(f.toPath(), bytes);
        return f;
    }

    private static String grammarPath(Path dir) throws Exception {
        Path g = dir.resolve("cdr.asn");
        Files.writeString(g, GRAMMAR, StandardCharsets.UTF_8);
        return g.toString().replace('\\', '/');
    }

    private static Path writePipeline(Path dir) throws Exception {
        return writePipeline(dir, MO_CALL_SCHEMA);
    }

    private static Path writePipeline(Path dir, String schemaToon) throws Exception {
        return writePipeline(dir, schemaToon,
                "grammar: %s\n    root_type: CallEventRecord".formatted(grammarPath(dir)));
    }

    private static Path writePipeline(Path dir, String schemaToon, String ingesterConfigBody) throws Exception {
        String d = dir.toString().replace('\\', '/');
        grammarPath(dir);
        Path schema = dir.resolve("mo_call_schema.toon");
        Files.writeString(schema, schemaToon, StandardCharsets.UTF_8);
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
                  ingester: com.gamma.ingester.Asn1RecordIngester
                  segments:
                    moCallRecord: %s
                  ingester_config:
                    %s
                """).formatted(d, d, d, d, d, d, d,
                schema.toString().replace('\\', '/'), ingesterConfigBody), StandardCharsets.UTF_8);
        return pipe;
    }

    /** A {@link RecordSink} that records every call for assertions. */
    private static final class CapturingSink implements RecordSink {
        final Map<String, List<String>> defined = new LinkedHashMap<>();
        final List<Object[]> emitted = new ArrayList<>();
        int rejects, junks;
        @Override public void define(String segmentKey, List<String> columns) { defined.put(segmentKey, columns); }
        @Override public void emit(String segmentKey, Object... values) { emitted.add(values); }
        @Override public void reject(String segmentKey) { rejects++; }
        @Override public void junk() { junks++; }
    }
}
