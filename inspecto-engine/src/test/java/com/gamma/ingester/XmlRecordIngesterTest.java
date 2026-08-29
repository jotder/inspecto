package com.gamma.ingester;

import com.gamma.etl.PipelineConfig;
import com.gamma.etl.RecordSink;
import com.gamma.parse.ParseResult;
import com.gamma.parse.Parsers;
import com.gamma.parse.XmlParserPlugin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The tree→segments bridge: {@link XmlRecordIngester} loading what
 * {@link XmlParserPlugin} previews. The load-bearing case is
 * {@link #previewLabelsAreTheSelectorsThatResolve()} — the bridge is only honest if a selector
 * authored against the preview tree resolves at ingest time.
 */
class XmlRecordIngesterTest {

    private static final String ORDER_SCHEMA = """
            partitionKey: EVENT_DATE
            raw:
              name: order
              format: CSV
              fields[4]{name,selector,type}:
                ORDER_ID,"@id",VARCHAR
                CUSTOMER,"customer.name",VARCHAR
                EVENT_DATE,"placed",DATE
                NOTE,"lines",VARCHAR
            mapping:
              canonicalName: order
              rawName: order
              rules[4]{targetColumn,sourceExpression,transformType}:
                ORDER_ID,ORDER_ID,DIRECT
                CUSTOMER,CUSTOMER,DIRECT
                EVENT_DATE,EVENT_DATE,DIRECT
                NOTE,NOTE,DIRECT
            """;

    private static final String DOC = """
            <feed>
              <order id="A1">
                <customer><name>Ada</name></customer>
                <placed>2026-04-03</placed>
                <lines><line>x</line><line>y</line></lines>
              </order>
              <order id="A2">
                <customer><name>Bo</name></customer>
                <placed>2026-04-04</placed>
              </order>
              <refund id="R9"><placed>2026-04-05</placed></refund>
            </feed>
            """;

    @Test
    void flattensRecordsOntoSegmentColumnsAndJunksUndeclaredElements(@TempDir Path dir) throws Exception {
        PipelineConfig cfg = PipelineConfig.load(writePipeline(dir, "order").toString());
        CapturingSink sink = new CapturingSink();

        new XmlRecordIngester().ingest(write(dir, DOC), sink, 0, cfg);

        assertEquals(List.of("ORDER_ID", "CUSTOMER", "EVENT_DATE", "NOTE", "EVENT_TYPE"),
                sink.defined.get("order"), "raw.fields plus the derived EVENT_TYPE");
        assertEquals(2, sink.emitted.size(), "two <order> records; <refund> is not a declared segment");
        assertArrayEquals(new Object[]{"A1", "Ada", "2026-04-03", null, "order"}, sink.emitted.get(0),
                "attribute, nested element and date resolve; the repeated <line> container does not");
        assertArrayEquals(new Object[]{"A2", "Bo", "2026-04-04", null, "order"}, sink.emitted.get(1));
        assertEquals(0, sink.junks, "with record_element=order the <refund> element is never a record");
    }

    /**
     * Blank {@code record_element} makes every direct child of the root a record, so one document
     * with several record kinds loads by declaring a segment per kind — and an undeclared kind is
     * junk, not a silent drop.
     */
    @Test
    void blankRecordElementSelectsSegmentByElementNameAndJunksTheRest(@TempDir Path dir) throws Exception {
        PipelineConfig cfg = PipelineConfig.load(writePipeline(dir, "").toString());
        CapturingSink sink = new CapturingSink();

        new XmlRecordIngester().ingest(write(dir, DOC), sink, 0, cfg);

        assertEquals(2, sink.emitted.size(), "the two <order> records match the only declared segment");
        assertEquals(1, sink.junks, "<refund> is a record here, and an undeclared one → junk");
    }

    /**
     * The bridge's whole premise: the labels an operator reads off the preview tree are the steps a
     * selector walks at ingest. Asserted against the plugin's own output, not a hand-written list —
     * a second walker that drifted would break this even while both halves looked right alone.
     */
    @Test
    void previewLabelsAreTheSelectorsThatResolve(@TempDir Path dir) throws Exception {
        ParseResult preview = new XmlParserPlugin().preview(DOC.getBytes(StandardCharsets.UTF_8),
                Map.of("ingester_config", Map.of("record_element", "order")));
        ParseResult.Node first = ((ParseResult.Tree) preview).nodes().getFirst();
        assertEquals(List.of("@id", "customer", "placed", "lines"),
                first.children().stream().map(ParseResult.Node::label).toList(),
                "preview labels — the selector vocabulary");

        PipelineConfig cfg = PipelineConfig.load(writePipeline(dir, "order").toString());
        CapturingSink sink = new CapturingSink();
        new XmlRecordIngester().ingest(write(dir, DOC), sink, 0, cfg);

        assertEquals("A1", sink.emitted.get(0)[0], "the '@id' label resolves as a selector");
        assertEquals("Ada", sink.emitted.get(0)[1], "the dotted 'customer.name' path walks the same tree");
    }

    @Test
    void malformedDocumentFailsTheFileRatherThanLoadingItsPrefix(@TempDir Path dir) throws Exception {
        PipelineConfig cfg = PipelineConfig.load(writePipeline(dir, "order").toString());
        File bad = write(dir, "<feed><order id=\"A1\"><placed>2026-04-03</placed></feed>");
        Exception e = assertThrows(Exception.class,
                () -> new XmlRecordIngester().ingest(bad, new CapturingSink(), 0, cfg));
        assertTrue(e.getMessage().contains("XML decode failed"), e.getMessage());
    }

    /**
     * A plugin ingester with no segments is refused at CONFIG LOAD, before this ingester is ever
     * constructed — so the failure an operator actually meets is the parser's, not a later one at
     * ingest time. (`XmlRecordIngester`'s own segment guard stays, matching
     * {@link Asn1RecordIngester}: the SPI is public and callable with a hand-built config.)
     */
    @Test
    void aPluginIngesterWithNoSegmentsIsRefusedAtConfigLoad(@TempDir Path dir) throws Exception {
        Path pipe = writePipeline(dir, "order", /*withSegment*/ false);
        Exception e = assertThrows(Exception.class, () -> PipelineConfig.load(pipe.toString()));
        assertTrue(e.getMessage().contains("segments"), e.getMessage());
    }

    /** The catalog flag operators see must follow the ingester actually existing. */
    @Test
    void xmlIsNowServedAsIngestable() {
        assertTrue(Parsers.get("xml").isPresent(), "xml parser is registered");
        assertTrue(Parsers.ingestable(Parsers.get("xml").orElseThrow()),
                "naming an ingesterClass is what makes it ingestable");
        assertEquals("com.gamma.ingester.XmlRecordIngester",
                Parsers.get("xml").orElseThrow().ingesterClass().orElseThrow());
    }

    // ── helpers ──────────────────────────────────────────────────────────────────

    private static File write(Path dir, String xml) throws Exception {
        Path f = dir.resolve("feed.xml");
        Files.writeString(f, xml, StandardCharsets.UTF_8);
        return f.toFile();
    }

    private static Path writePipeline(Path dir, String recordElement) throws Exception {
        return writePipeline(dir, recordElement, true);
    }

    private static Path writePipeline(Path dir, String recordElement, boolean withSegment) throws Exception {
        String d = dir.toString().replace('\\', '/');
        Path schema = dir.resolve("order_schema.toon");
        Files.writeString(schema, ORDER_SCHEMA, StandardCharsets.UTF_8);
        String segments = withSegment
                ? "    segments:\n      order: " + schema.toString().replace('\\', '/') + "\n"
                : "";
        Path pipe = dir.resolve("xml_pipeline.toon");
        Files.writeString(pipe, ("""
                name: XML_FEED
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
                  file_pattern: "glob:**/*.xml"
                parsing:
                  frontend: plugin
                  plugin:
                    ingester: com.gamma.ingester.XmlRecordIngester
                %s    ingester_config:
                      record_element: "%s"
                """).formatted(d, d, d, d, d, d, d, segments, recordElement), StandardCharsets.UTF_8);
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
