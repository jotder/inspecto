package com.gamma.parse;

import org.junit.jupiter.api.Test;

import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Asn1ParserPluginTest {

    private final Asn1ParserPlugin asn1 = new Asn1ParserPlugin();

    private static final String GRAMMAR = """
            TEST DEFINITIONS IMPLICIT TAGS ::= BEGIN
            Record ::= [APPLICATION 1] SEQUENCE
            {
              id [0] INTEGER,
              name [1] IA5String OPTIONAL,
              events [2] SEQUENCE OF Event
            }
            Event ::= CHOICE { start [3] INTEGER, stop [4] INTEGER }
            END
            """;

    // 61 0C { 80 01 2A, 81 02 68 69, A2 03 { 83 01 05 } } == id=42, name="hi", events=[start=5]
    private static final String RECORD_1_HEX = "61 0C 80 01 2A 81 02 68 69 A2 03 83 01 05";
    // 61 08 { 80 01 07, A2 03 { 84 01 09 } }             == id=7, name absent, events=[stop=9]
    private static final String RECORD_2_HEX = "61 08 80 01 07 A2 03 84 01 09";

    private static byte[] hex(String... records) {
        StringBuilder all = new StringBuilder();
        for (String r : records) all.append(r);
        return HexFormat.of().parseHex(all.toString().replace(" ", ""));
    }

    private static Map<String, Object> grammar(Object... kv) {
        java.util.Map<String, Object> asn1 = new java.util.LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) asn1.put((String) kv[i], kv[i + 1]);
        return Map.of("asn1", asn1);
    }

    @Test
    void decodesRecordsIntoATreeWithNestedFields() throws Exception {
        ParseResult.Tree t = assertInstanceOf(ParseResult.Tree.class,
                asn1.preview(hex(RECORD_1_HEX, RECORD_2_HEX), grammar("grammar", GRAMMAR, "root_type", "Record")));
        assertEquals(2, t.recordCount());
        ParseResult.Node rec = t.nodes().get(0);
        assertEquals(List.of("id", "name", "events"), rec.children().stream().map(ParseResult.Node::label).toList());
        assertEquals("42", rec.children().get(0).value());
        assertEquals("hi", rec.children().get(1).value());
        ParseResult.Node events = rec.children().get(2);
        ParseResult.Node start = events.children().get(0);
        assertEquals("start", start.label());
        assertEquals("5", start.value());
        // OPTIONAL name absent in record 2.
        ParseResult.Node rec2 = t.nodes().get(1);
        assertEquals(List.of("id", "events"), rec2.children().stream().map(ParseResult.Node::label).toList());
    }

    @Test
    void missingGrammarIsACallerError() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> asn1.preview(hex(RECORD_1_HEX), grammar("root_type", "Record")));
        assertTrue(e.getMessage().contains("asn1.grammar"));
    }

    @Test
    void missingRootTypeIsACallerError() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> asn1.preview(hex(RECORD_1_HEX), grammar("grammar", GRAMMAR)));
        assertTrue(e.getMessage().contains("asn1.root_type"));
    }

    @Test
    void invalidGrammarTextIsACallerError() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> asn1.preview(hex(RECORD_1_HEX), grammar("grammar", "not asn.1 at all", "root_type", "Record")));
        assertTrue(e.getMessage().contains("invalid ASN.1 grammar"));
    }

    @Test
    void unparseableSampleIsACallerError() {
        // Tag APPLICATION 1 constructed (matches Record), length byte declares 127 content bytes
        // but only 3 remain — BerReader.read fails, the failure is recorded (never silently
        // dropped) and, with no records surviving, preview() surfaces it as a caller error.
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> asn1.preview(hex("61 7F 80 01 2A"), grammar("grammar", GRAMMAR, "root_type", "Record")));
        assertTrue(e.getMessage().contains("did not decode"));
    }

    @Test
    void unknownStrictnessIsACallerError() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> asn1.preview(hex(RECORD_1_HEX),
                        grammar("grammar", GRAMMAR, "root_type", "Record", "strictness", "NOPE")));
        assertTrue(e.getMessage().contains("strictness"));
    }

    @Test
    void recordCountCountsAllMatchesWhileNodesAreCapped() throws Exception {
        String[] many = new String[60];
        java.util.Arrays.fill(many, RECORD_2_HEX);
        ParseResult.Tree capped = (ParseResult.Tree) asn1.preview(hex(many),
                grammar("grammar", GRAMMAR, "root_type", "Record", "max_records", 10));
        assertEquals(60, capped.recordCount());
        assertEquals(10, capped.nodes().size());
        // Default cap is DEFAULT_RECORDS (50).
        ParseResult.Tree dflt = (ParseResult.Tree) asn1.preview(hex(many),
                grammar("grammar", GRAMMAR, "root_type", "Record"));
        assertEquals(Asn1ParserPlugin.DEFAULT_RECORDS, dflt.nodes().size());
    }

    @Test
    void aFileHeaderIsSkippedBeforeTheFirstRecord() throws Exception {
        // 5 junk bytes of file header, then two ordinary records.
        ParseResult.Tree t = (ParseResult.Tree) asn1.preview(hex("AA BB CC DD EE", RECORD_1_HEX, RECORD_2_HEX),
                grammar("grammar", GRAMMAR, "root_type", "Record", "file_header_length", 5));
        assertEquals(2, t.recordCount());
        assertEquals("42", t.nodes().get(0).children().get(0).value());
    }

    @Test
    void aPerRecordHeaderIsSkippedAndRecordsStayBerDelimited() throws Exception {
        // 4-byte header before each record (the Huawei layout), records still delimited by their
        // own BER length. Header bytes deliberately avoid 0x00/0xFF — those are padding.
        ParseResult.Tree t = (ParseResult.Tree) asn1.preview(
                hex("01 02 03 04", RECORD_1_HEX, "05 06 07 08", RECORD_2_HEX),
                grammar("grammar", GRAMMAR, "root_type", "Record", "record_header_length", 4));
        assertEquals(2, t.recordCount());
        assertEquals("42", t.nodes().get(0).children().get(0).value());
        assertEquals("7", t.nodes().get(1).children().get(0).value());
    }

    @Test
    void zeroAndFfPaddingBetweenRecordsIsSkippedUnconditionally() throws Exception {
        // Legacy ASN1Utils.readTag skips both before every record tag; no grammar field asks for it.
        ParseResult.Tree t = (ParseResult.Tree) asn1.preview(
                hex(RECORD_1_HEX, "00 00 FF FF 00", RECORD_2_HEX, "FF FF"),
                grammar("grammar", GRAMMAR, "root_type", "Record"));
        assertEquals(2, t.recordCount());
        assertEquals("7", t.nodes().get(1).children().get(0).value());
    }

    @Test
    void aNegativeFramingLengthIsACallerError() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> asn1.preview(hex(RECORD_1_HEX),
                        grammar("grammar", GRAMMAR, "root_type", "Record", "file_header_length", -1)));
        assertTrue(e.getMessage().contains("file_header_length"));
    }

    @Test
    void leafValuesHaveNoChildrenAndNullIsOnlyForContainers() throws Exception {
        ParseResult.Tree t = (ParseResult.Tree) asn1.preview(hex(RECORD_1_HEX),
                grammar("grammar", GRAMMAR, "root_type", "Record"));
        ParseResult.Node id = t.nodes().get(0).children().get(0);
        assertTrue(id.children().isEmpty());
        ParseResult.Node events = t.nodes().get(0).children().get(2);
        assertNull(events.value());
    }
}
