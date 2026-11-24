package com.gamma.telecom.asn1;

import com.gamma.etl.DecodeProfile;
import com.gamma.etl.RecordSink;
import com.gamma.parse.ParseResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Task 3: multi-event routing ({@code event_roots}), batch/file header context ({@code $header.*}),
 * leaf {@code V}-decoder overrides ({@code field_decoders}), and {@link DecodeProfile} overlay/validation.
 */
class EventRouterAndHeaderTest {

    private static final String GRAMMAR = """
            TEST-TAP DEFINITIONS IMPLICIT TAGS ::= BEGIN

            TransferBatch ::= [APPLICATION 1] SEQUENCE {
                batchControlInfo [0] BatchControlInfo OPTIONAL,
                callEventDetails [1] SEQUENCE OF CallEventDetail
            }

            BatchControlInfo ::= SEQUENCE {
                sender    [0] IA5String,
                recipient [1] IA5String
            }

            CallEventDetail ::= CHOICE {
                mobileOriginatedCall [0] MobileOriginatedCall,
                gprsCall             [1] GprsCall
            }

            MobileOriginatedCall ::= SEQUENCE {
                callingNumber [0] OCTET STRING,
                duration      [1] INTEGER
            }

            GprsCall ::= SEQUENCE {
                imsi       [0] OCTET STRING,
                dataVolume [1] INTEGER
            }

            END
            """;

    private static final String MOC_SCHEMA = """
            partitionKey: CALLING_NUMBER
            raw:
              name: tap_moc_cdr
              format: CSV
              fields[4]{name,selector,type}:
                CALLING_NUMBER,callingNumber,VARCHAR
                DURATION,duration,VARCHAR
                SENDER,$header.batchControlInfo.sender,VARCHAR
                RECIPIENT,$header.batchControlInfo.recipient,VARCHAR
            mapping:
              canonicalName: tap_moc_cdr
              rawName: tap_moc_cdr
              rules[1]{targetColumn,sourceExpression,transformType}:
                CALLING_NUMBER,CALLING_NUMBER,DIRECT
            """;

    private static final String GPRS_SCHEMA = """
            partitionKey: IMSI
            raw:
              name: tap_gprs_cdr
              format: CSV
              fields[4]{name,selector,type}:
                IMSI,imsi,VARCHAR
                DATA_VOLUME,dataVolume,VARCHAR
                SENDER,$header.batchControlInfo.sender,VARCHAR
                RECIPIENT,$header.batchControlInfo.recipient,VARCHAR
            mapping:
              canonicalName: tap_gprs_cdr
              rawName: tap_gprs_cdr
              rules[1]{targetColumn,sourceExpression,transformType}:
                IMSI,IMSI,DIRECT
            """;

    // TransferBatch (61 24)
    //   batchControlInfo (A0 0A): sender="OP1" (80 03 4F5031), recipient="OP2" (81 03 4F5032)
    //   callEventDetails (A1 16):
    //     mobileOriginatedCall (A0 08): callingNumber=TBCD 214365->"123456" (80 03 214365), duration=42 (81 01 2A)
    //     gprsCall             (A1 0A): imsi=TBCD 10325476->"01234567" (80 04 10325476), dataVolume=1024 (81 02 0400)
    private static final byte[] BATCH_WITH_HEADER = HexFormat.of().parseHex(
            "6124"
                    + "A00A" + "80034F5031" + "81034F5032"
                    + "A116"
                    + "A008" + "8003214365" + "81012A"
                    + "A10A" + "800410325476" + "81020400"
    );

    // Same CallEventDetails without batchControlInfo
    private static final byte[] BATCH_WITHOUT_HEADER = HexFormat.of().parseHex(
            "6118"
                    + "A116"
                    + "A008" + "8003214365" + "81012A"
                    + "A10A" + "800410325476" + "81020400"
    );

    private final Asn1ParserPlugin plugin = new Asn1ParserPlugin();

    @Test
    void routesMultipleEventRootsPropagatesHeadersAndAppliesLeafOverrides(@TempDir Path dir) throws Exception {
        Map<String, Object> grammar = buildGrammarConfig(dir, Map.of(
                "event_roots", Map.of(
                        "callEventDetails.mobileOriginatedCall", "tap_moc_cdr",
                        "callEventDetails.gprsCall", "tap_gprs_cdr"
                ),
                "header_fields", List.of("batchControlInfo.sender", "batchControlInfo.recipient"),
                "field_decoders", Map.of(
                        "mobileOriginatedCall.callingNumber", "tbcd",
                        "gprsCall.imsi", "tbcd",
                        // Container/repeated paths must never trigger leaf overrides
                        "callEventDetails", "tbcd",
                        "mobileOriginatedCall", "tbcd"
                )
        ));

        ParseResult.Rows rows = plugin.previewRows(BATCH_WITH_HEADER, grammar, dir).orElseThrow();

        assertEquals(2, rows.segments().size());

        ParseResult.RowSet moc = rows.segments().get(0);
        assertEquals("tap_moc_cdr", moc.segment());
        assertEquals(1, moc.rowCount());
        assertEquals(List.of("CALLING_NUMBER", "DURATION", "SENDER", "RECIPIENT", "EVENT_TYPE"), moc.columns());
        Map<String, Object> mocRow = moc.rows().get(0);
        assertEquals("123456", mocRow.get("CALLING_NUMBER"));
        assertEquals("42", mocRow.get("DURATION"));
        assertEquals("OP1", mocRow.get("SENDER"));
        assertEquals("OP2", mocRow.get("RECIPIENT"));
        assertEquals("tap_moc_cdr", mocRow.get("EVENT_TYPE"));

        ParseResult.RowSet gprs = rows.segments().get(1);
        assertEquals("tap_gprs_cdr", gprs.segment());
        assertEquals(1, gprs.rowCount());
        Map<String, Object> gprsRow = gprs.rows().get(0);
        assertEquals("01234567", gprsRow.get("IMSI"));
        assertEquals("1024", gprsRow.get("DATA_VOLUME"));
        assertEquals("OP1", gprsRow.get("SENDER"));
        assertEquals("OP2", gprsRow.get("RECIPIENT"));
        assertEquals("tap_gprs_cdr", gprsRow.get("EVENT_TYPE"));
    }

    @Test
    void missingHeaderResolvesToNullWithoutFailing(@TempDir Path dir) throws Exception {
        Map<String, Object> grammar = buildGrammarConfig(dir, Map.of(
                "event_roots", Map.of(
                        "callEventDetails.mobileOriginatedCall", "tap_moc_cdr",
                        "callEventDetails.gprsCall", "tap_gprs_cdr"
                ),
                "header_fields", List.of("batchControlInfo.sender", "batchControlInfo.recipient"),
                "field_decoders", Map.of(
                        "mobileOriginatedCall.callingNumber", "tbcd",
                        "gprsCall.imsi", "tbcd"
                )
        ));

        ParseResult.Rows rows = plugin.previewRows(BATCH_WITHOUT_HEADER, grammar, dir).orElseThrow();

        assertEquals(2, rows.segments().size());
        Map<String, Object> mocRow = rows.segments().get(0).rows().get(0);
        assertEquals("123456", mocRow.get("CALLING_NUMBER"));
        assertNull(mocRow.get("SENDER"));
        assertNull(mocRow.get("RECIPIENT"));

        Map<String, Object> gprsRow = rows.segments().get(1).rows().get(0);
        assertEquals("01234567", gprsRow.get("IMSI"));
        assertNull(gprsRow.get("SENDER"));
        assertNull(gprsRow.get("RECIPIENT"));
    }

    @Test
    void decodeProfileOverlaysAndValidatesRoutingAndDecoders(@TempDir Path dir) throws Exception {
        Path profileFile = dir.resolve("vendor_profile.toon");
        Files.writeString(profileFile, """
                asn1:
                  root_type: TransferBatch
                  event_roots:
                    callEventDetails.mobileOriginatedCall: tap_moc_cdr
                  header_fields[2]: batchControlInfo.sender, batchControlInfo.recipient
                  field_decoders:
                    mobileOriginatedCall.callingNumber: tbcd
                """, StandardCharsets.UTF_8);

        Map<String, Object> referring = new LinkedHashMap<>();
        referring.put("profile_file", "vendor_profile.toon");
        DecodeProfile.Resolved resolved = DecodeProfile.overlay(referring, dir);

        assertEquals(Map.of("callEventDetails.mobileOriginatedCall", "tap_moc_cdr"),
                DecodeProfile.eventRoots(resolved.asn1()));
        assertEquals(List.of("batchControlInfo.sender", "batchControlInfo.recipient"),
                DecodeProfile.headerFields(resolved.asn1()));
        assertEquals(Map.of("mobileOriginatedCall.callingNumber", "tbcd"),
                DecodeProfile.fieldDecoders(resolved.asn1()));

        Map<String, Object> badRoots = new LinkedHashMap<>();
        badRoots.put("event_roots", Map.of("moc", "undeclared_seg"));
        badRoots.put("segments", Map.of("tap_moc_cdr", "moc_schema.toon"));
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> DecodeProfile.validateRoutingAndDecoders(badRoots));
        assertTrue(ex.getMessage().contains("undeclared_seg"));
    }

    @Test
    void ingesterEmitsRoutedRowsDirectlyWithCollectingSink(@TempDir Path dir) throws Exception {
        Map<String, Object> mocSchema = Map.of(
                "raw", Map.of("fields", List.of(
                        Map.of("name", "CALLING_NUMBER", "selector", "callingNumber"),
                        Map.of("name", "SENDER", "selector", "$header.batchControlInfo.sender")
                ))
        );
        Map<String, Object> gprsSchema = Map.of(
                "raw", Map.of("fields", List.of(
                        Map.of("name", "IMSI", "selector", "imsi"),
                        Map.of("name", "SENDER", "selector", "$header.batchControlInfo.sender")
                ))
        );
        Map<String, Map<String, Object>> segments = new LinkedHashMap<>();
        segments.put("tap_moc_cdr", mocSchema);
        segments.put("tap_gprs_cdr", gprsSchema);

        Map<String, Object> ic = new LinkedHashMap<>();
        ic.put("event_roots", Map.of(
                "callEventDetails.mobileOriginatedCall", "tap_moc_cdr",
                "callEventDetails.gprsCall", "tap_gprs_cdr"
        ));
        ic.put("header_fields", List.of("batchControlInfo.sender"));
        ic.put("field_decoders", Map.of(
                "mobileOriginatedCall.callingNumber", "tbcd",
                "gprsCall.imsi", "tbcd"
        ));

        var decoder = com.gamma.asn.facade.Asn1Decoder.compile(GRAMMAR, "TransferBatch");
        try (var src = com.gamma.asn.core.ByteSource.of(BATCH_WITH_HEADER)) {
            List<com.gamma.asn.schema.NamedNode> nodes = Asn1RecordIngester.decodeWithOverrides(
                    decoder, src, com.gamma.asn.core.Framing.none(),
                    com.gamma.asn.core.Strictness.BER, com.gamma.asn.core.RecoveryPolicy.STOP_FILE,
                    err -> {}, 1024 * 1024, DecodeProfile.fieldDecoders(ic)).toList();

            Map<String, List<Object[]>> emitted = new LinkedHashMap<>();
            RecordSink sink = new RecordSink() {
                @Override
                public void define(String segmentKey, List<String> columns) {
                    emitted.put(segmentKey, new ArrayList<>());
                }

                @Override
                public void emit(String segmentKey, Object... values) {
                    emitted.get(segmentKey).add(values);
                }
            };

            Asn1RecordIngester.emitRecords(nodes.iterator(), ic, segments, sink, true);

            assertEquals(1, emitted.get("tap_moc_cdr").size());
            assertEquals("123456", emitted.get("tap_moc_cdr").get(0)[0]);
            assertEquals("OP1", emitted.get("tap_moc_cdr").get(0)[1]);
            assertEquals("tap_moc_cdr", emitted.get("tap_moc_cdr").get(0)[2]);

            assertEquals(1, emitted.get("tap_gprs_cdr").size());
            assertEquals("01234567", emitted.get("tap_gprs_cdr").get(0)[0]);
            assertEquals("OP1", emitted.get("tap_gprs_cdr").get(0)[1]);
            assertEquals("tap_gprs_cdr", emitted.get("tap_gprs_cdr").get(0)[2]);
        }
    }

    private static Map<String, Object> buildGrammarConfig(Path dir, Map<String, Object> extra) throws Exception {
        Files.writeString(dir.resolve("moc_schema.toon"), MOC_SCHEMA, StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("gprs_schema.toon"), GPRS_SCHEMA, StandardCharsets.UTF_8);
        Map<String, Object> segments = new LinkedHashMap<>();
        segments.put("tap_moc_cdr", "moc_schema.toon");
        segments.put("tap_gprs_cdr", "gprs_schema.toon");

        Map<String, Object> asn1 = new LinkedHashMap<>();
        asn1.put("grammar", GRAMMAR);
        asn1.put("root_type", "TransferBatch");
        asn1.put("segments", segments);
        asn1.putAll(extra);

        Map<String, Object> grammar = new LinkedHashMap<>();
        grammar.put("asn1", asn1);
        return grammar;
    }
}
