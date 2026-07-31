package com.gamma.asn.facade;

import com.gamma.asn.core.ByteSource;
import com.gamma.asn.core.Framing;
import com.gamma.asn.core.RecoveryPolicy;
import com.gamma.asn.core.Strictness;
import com.gamma.asn.schema.NamedNode;
import com.gamma.asn.transform.FunctionRegistry;
import com.gamma.asn.transform.LegacyTransformEngine;
import com.gamma.asn.transform.TxConfig;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Asn1DecoderTest {

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
        for (String r : records) {
            all.append(r);
        }
        return HexFormat.of().parseHex(all.toString().replace(" ", ""));
    }

    @Test
    void decodesEachBackToBackRecordInOrder() {
        Asn1Decoder decoder = Asn1Decoder.compile(GRAMMAR, "Record");
        byte[] bytes = hex(RECORD_1_HEX, RECORD_2_HEX);

        List<NamedNode> records;
        try (ByteSource src = ByteSource.of(bytes)) {
            records = decoder.decode(src).toList();
        }

        assertEquals(2, records.size());
        assertEquals("42", records.get(0).child("id").value());
        assertEquals("hi", records.get(0).child("name").value());
        assertEquals("7", records.get(1).child("id").value());
        assertNull(records.get(1).child("name")); // OPTIONAL, absent
    }

    @Test
    void recordMapperFlattensToTheLegacyMapShape() {
        Asn1Decoder decoder = Asn1Decoder.compile(GRAMMAR, "Record");
        byte[] bytes = hex(RECORD_1_HEX);

        Map<String, Object> map;
        try (ByteSource src = ByteSource.of(bytes)) {
            NamedNode node = decoder.decode(src).toList().getFirst();
            map = RecordMapper.toMap(node);
        }

        assertEquals(BigInteger.valueOf(42), map.get("id")); // INTEGER -> BigInteger
        assertEquals("hi", map.get("name"));
        @SuppressWarnings("unchecked")
        Map<String, Object> events = (Map<String, Object>) map.get("events");
        // SEQUENCE OF Event (CHOICE) -> one key per chosen alternative name, holding a list
        assertEquals(List.of(BigInteger.valueOf(5)), events.get("start"));
    }

    @Test
    void decodeToRowsCarriesThroughLegacyTransformEngine() {
        Asn1Decoder decoder = Asn1Decoder.compile(GRAMMAR, "Record");
        byte[] bytes = hex(RECORD_1_HEX, RECORD_2_HEX);

        // An empty tx config: every field passes through unchanged (no @rename/@transform entries).
        LegacyTransformEngine engine = new LegacyTransformEngine(
                TxConfig.fromText("{}"), FunctionRegistry.of(Map.of()));

        List<Map<String, Object>> rows;
        try (ByteSource src = ByteSource.of(bytes)) {
            rows = decoder.decodeToRows(src, Framing.none(), Strictness.BER,
                    RecoveryPolicy.STOP_FILE, error -> { }, "Record", engine);
        }

        assertEquals(2, rows.size()); // one row per input record
        assertEquals(BigInteger.valueOf(42), rows.get(0).get("id"));
        assertEquals("hi", rows.get(0).get("name"));
        assertEquals(BigInteger.valueOf(7), rows.get(1).get("id"));
        // "events" is a list of scalars (not a list of records), so the legacy engine's
        // auto-join — which only joins list-of-Map sub-records — faithfully drops it, same
        // as the legacy transformer2 it was ported from.
        assertTrue(!rows.get(0).containsKey("events"));
    }
}
