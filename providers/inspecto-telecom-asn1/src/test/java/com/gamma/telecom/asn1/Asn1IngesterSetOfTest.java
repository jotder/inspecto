package com.gamma.telecom.asn1;

import com.gamma.asn.schema.NamedNode;
import com.gamma.etl.RecordSink;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Task 4 end-to-end ingester tests: {@code Asn1RecordIngester.emitRecords} with
 * {@code group_by} + {@code aggs} -> {@code join} ({@code left} vs {@code inner}),
 * {@code ChildSpec} {@code fold} ({@code delim} / {@code json}), and leaf
 * {@code rename} / {@code prefix} / {@code postfix}.
 */
class Asn1IngesterSetOfTest {

    private static final class CollectingSink implements RecordSink {
        final Map<String, List<String>> columns = new LinkedHashMap<>();
        final Map<String, List<Object[]>> rows = new LinkedHashMap<>();

        @Override
        public void define(String segmentKey, List<String> cols) {
            columns.put(segmentKey, List.copyOf(cols));
            rows.put(segmentKey, new ArrayList<>());
        }

        @Override
        public void emit(String segmentKey, Object... values) {
            rows.get(segmentKey).add(values);
        }
    }

    private static NamedNode leaf(String name, String value) {
        return new NamedNode(name, name, "Primitive", value, List.of());
    }

    private static NamedNode seq(String name, List<NamedNode> children) {
        return new NamedNode(name, name, "SEQUENCE", null, children);
    }

    private static NamedNode serviceData(String ratingGroup, String downlink) {
        return seq("ServiceData", List.of(
                leaf("ratingGroup", ratingGroup),
                leaf("downlink", downlink)
        ));
    }

    private static NamedNode ggsnRecord(String imsi, List<NamedNode> serviceDataItems) {
        return seq("GGSNRecord", List.of(
                leaf("imsi", imsi),
                seq("listOfServiceData", serviceDataItems)
        ));
    }

    private static Map<String, Object> rawSchema(List<Map<String, Object>> fields) {
        return Map.of("raw", Map.of("fields", fields));
    }

    @Test
    void groupByAggsJoinLeftAndInnerEmitPlainDecimalsAndHandleEmptyLists() throws Exception {
        Map<String, Map<String, Object>> segments = new LinkedHashMap<>();
        segments.put("GGSNRecord", rawSchema(List.of(
                Map.of("name", "imsi", "rename", "IMSI")
        )));
        segments.put("ServiceDataLeft", rawSchema(List.of(
                Map.of("name", "IMSI", "selector", "$parent.imsi"),
                Map.of("name", "RATING_GROUP", "selector", "ratingGroup"),
                Map.of("name", "DOWNLINK_SUM", "selector", "downlink_sum", "prefix", "SVC_")
        )));
        Map<String, Object> ic = Map.of(
                "children", Map.of(
                        "GGSNRecord", Map.of(
                                "listOfServiceData", Map.of(
                                        "segment", "ServiceDataLeft",
                                        "group_by", "ratingGroup",
                                        "aggs", Map.of("downlink", "sum"),
                                        "join_mode", "left"
                                )
                        )
                )
        );

        List<NamedNode> records = List.of(
                ggsnRecord("001010123456789", List.of(
                        serviceData("100", "4000"),
                        serviceData("200", "2500"),
                        serviceData("100", "6000")
                )),
                ggsnRecord("001010999999999", List.of())
        );

        CollectingSink leftSink = new CollectingSink();
        Asn1RecordIngester.emitRecords(records.iterator(), ic, segments, leftSink);

        assertEquals(List.of("IMSI", "EVENT_TYPE"), leftSink.columns.get("GGSNRecord"));
        assertEquals(List.of("IMSI", "RATING_GROUP", "SVC_DOWNLINK_SUM", "EVENT_TYPE"),
                leftSink.columns.get("ServiceDataLeft"));

        List<Object[]> leftRows = leftSink.rows.get("ServiceDataLeft");
        assertEquals(3, leftRows.size());
        // Must be plain "10000", never "1E+4"
        assertArrayEquals(new Object[]{"001010123456789", "100", "10000", "ServiceDataLeft"}, leftRows.get(0));
        assertArrayEquals(new Object[]{"001010123456789", "200", "2500", "ServiceDataLeft"}, leftRows.get(1));
        // Empty list with left join emits 1 row with parent IMSI + NULL child/group columns
        assertArrayEquals(new Object[]{"001010999999999", null, null, "ServiceDataLeft"}, leftRows.get(2));

        // Now verify inner join on empty list emits 0 rows
        Map<String, Object> innerIc = Map.of(
                "children", Map.of(
                        "GGSNRecord", Map.of(
                                "listOfServiceData", Map.of(
                                        "segment", "ServiceDataLeft",
                                        "group_by", "ratingGroup",
                                        "aggs", Map.of("downlink", "sum"),
                                        "join_mode", "inner"
                                )
                        )
                )
        );
        CollectingSink innerSink = new CollectingSink();
        Asn1RecordIngester.emitRecords(List.of(ggsnRecord("001010999999999", List.of())).iterator(),
                innerIc, segments, innerSink);
        assertEquals(0, innerSink.rows.get("ServiceDataLeft").size());
    }

    @Test
    void childSpecFoldDelimAndJsonWithDisambiguation() throws Exception {
        Map<String, Map<String, Object>> segments = new LinkedHashMap<>();
        segments.put("GGSNRecord", rawSchema(List.of(
                Map.of("name", "imsi", "postfix", "_ID")
        )));
        segments.put("ServiceFold", rawSchema(List.of(
                Map.of("name", "IMSI", "selector", "$parent.imsi"),
                Map.of("name", "SUMMARY", "selector", "SUMMARY")
        )));

        Map<String, Object> delimIc = Map.of(
                "children", Map.of(
                        "GGSNRecord", Map.of(
                                "listOfServiceData", Map.of(
                                        "segment", "ServiceFold",
                                        "group_by", "ratingGroup",
                                        "aggs", Map.of("downlink", "sum"),
                                        "mode", "delim",
                                        "item_delim", "|",
                                        "field_delim", ":",
                                        "target_column", "SUMMARY"
                                )
                        )
                )
        );

        List<NamedNode> records = List.of(
                ggsnRecord("001010123456789", List.of(
                        serviceData("100", "4000"),
                        serviceData("200", "2500"),
                        serviceData("100", "6000")
                )),
                ggsnRecord("001010999999999", List.of())
        );

        CollectingSink delimSink = new CollectingSink();
        Asn1RecordIngester.emitRecords(records.iterator(), delimIc, segments, delimSink);
        assertEquals(List.of("imsi_ID", "EVENT_TYPE"), delimSink.columns.get("GGSNRecord"));
        assertEquals(2, delimSink.rows.get("ServiceFold").size());
        assertArrayEquals(new Object[]{"001010123456789", "100:10000|200:2500", "ServiceFold"},
                delimSink.rows.get("ServiceFold").get(0));
        assertArrayEquals(new Object[]{"001010999999999", "", "ServiceFold"},
                delimSink.rows.get("ServiceFold").get(1));

        // Verify json mode via ChildSpec fold
        Map<String, Object> jsonIc = Map.of(
                "children", Map.of(
                        "GGSNRecord", Map.of(
                                "listOfServiceData", Map.of(
                                        "segment", "ServiceFold",
                                        "group_by", "ratingGroup",
                                        "aggs", Map.of("downlink", "sum"),
                                        "mode", "json",
                                        "target_column", "SUMMARY"
                                )
                        )
                )
        );
        CollectingSink jsonSink = new CollectingSink();
        Asn1RecordIngester.emitRecords(records.iterator(), jsonIc, segments, jsonSink);
        assertEquals(2, jsonSink.rows.get("ServiceFold").size());
        assertArrayEquals(new Object[]{
                        "001010123456789",
                        "[{\"ratingGroup\":\"100\",\"downlink_sum\":10000},{\"ratingGroup\":\"200\",\"downlink_sum\":2500}]",
                        "ServiceFold"},
                jsonSink.rows.get("ServiceFold").get(0));
        assertArrayEquals(new Object[]{"001010999999999", "[]", "ServiceFold"},
                jsonSink.rows.get("ServiceFold").get(1));

        // ChildSpec fold without segment must be rejected cleanly
        Map<String, Object> missingSegmentIc = Map.of(
                "children", Map.of(
                        "GGSNRecord", Map.of(
                                "listOfServiceData", Map.of(
                                        "mode", "json",
                                        "target_column", "SUMMARY"
                                )
                        )
                )
        );
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                Asn1RecordIngester.emitRecords(records.iterator(), missingSegmentIc, segments, new CollectingSink()));
        assertTrue(ex.getMessage().startsWith("ASN1-CHILD-UNKNOWN-SEGMENT"), ex.getMessage());
    }
}
