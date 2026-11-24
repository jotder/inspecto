package com.gamma.etl;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Task 4: {@code SET_OF}/{@code SEQUENCE_OF} shaping ({@code group_by} + per-member aggregations,
 * {@code fold} delimited/JSON, {@code join_mode} {@code left}/{@code inner}, {@code mean}, and
 * {@code rename}/{@code prefix}/{@code postfix} collision checks).
 */
class SetOfGroupByFoldJoinTest {

    private static Map<String, Object> m(Object... kv) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) out.put((String) kv[i], kv[i + 1]);
        return out;
    }

    private static final Map<String, Object> GGSN_RECORD = m(
            "imsi", "001010123456789",
            "listOfServiceData", List.of(
                    m("ratingGroup", "100", "downlink", "4000", "uplink", "500"),
                    m("ratingGroup", "200", "downlink", "2500", "uplink", "300"),
                    m("ratingGroup", "100", "downlink", "6000", "uplink", "700")
            ),
            "emptyServiceData", List.of()
    );

    @Test
    void groupByRatingGroupAndSumDownlinkProducesTwoSubRecords() {
        ChildSpec spec = ChildSpec.parse(
                m("GGSNRecord", m("listOfServiceData", m(
                        "segment", "ServiceDataRow",
                        "group_by", "ratingGroup",
                        "aggs", m("downlink", "sum"),
                        "join_mode", "left"
                ))),
                Set.of("GGSNRecord", "ServiceDataRow"),
                null);

        ChildSpec.Child child = spec.of("GGSNRecord").get(0);
        assertEquals(List.of("ratingGroup"), child.groupBy());
        assertEquals(Map.of("downlink", "sum"), child.aggs());
        assertEquals("left", child.joinMode());

        @SuppressWarnings("unchecked")
        List<?> rawList = (List<?>) GGSN_RECORD.get("listOfServiceData");
        List<Map<String, Object>> grouped = DeriveSpec.groupAndAggregate(
                rawList, child.groupBy(), child.aggs(), new DeriveSpec.Stats());

        assertEquals(2, grouped.size());
        assertEquals("100", grouped.get(0).get("ratingGroup"));
        assertEquals(0, new BigDecimal("10000").compareTo((BigDecimal) grouped.get(0).get("downlink_sum")));
        assertEquals("200", grouped.get(1).get("ratingGroup"));
        assertEquals(0, new BigDecimal("2500").compareTo((BigDecimal) grouped.get(1).get("downlink_sum")));
    }

    @Test
    void foldDelimitedAndJsonWithGroupByAndEmptyLists() throws IOException {
        DeriveSpec spec = DeriveSpec.parse(
                m("GGSNRecord", m(
                        "SVC_DELIM", m(
                                "fold", "listOfServiceData",
                                "mode", "delim",
                                "item_delim", "|",
                                "field_delim", ":",
                                "group_by", "ratingGroup",
                                "aggs", m("downlink", "sum")
                        ),
                        "SVC_JSON", m(
                                "fold", "listOfServiceData",
                                "mode", "json",
                                "group_by", "ratingGroup",
                                "aggs", m("downlink", "sum")
                        ),
                        "EMPTY_DELIM", m(
                                "fold", "emptyServiceData",
                                "mode", "delim",
                                "item_delim", "|",
                                "field_delim", ":"
                        ),
                        "EMPTY_JSON", m(
                                "fold", "emptyServiceData",
                                "mode", "json"
                        )
                )),
                Set.of("GGSNRecord"),
                null,
                null);

        Object[] vals = spec.evaluate("GGSNRecord", GGSN_RECORD, new DeriveSpec.Stats());
        assertEquals("100:10000|200:2500", vals[0]);
        assertEquals("[{\"ratingGroup\":\"100\",\"downlink_sum\":10000},{\"ratingGroup\":\"200\",\"downlink_sum\":2500}]", vals[1]);
        assertEquals("", vals[2]);
        assertEquals("[]", vals[3]);
    }

    @Test
    void reduceAndAggMeanMinMaxCountKeep() throws IOException {
        DeriveSpec derive = DeriveSpec.parse(
                m("GGSNRecord", m("AVG_DL", m("reduce", "listOfServiceData", "op", "mean", "of", "downlink"))),
                Set.of("GGSNRecord"),
                null,
                null);
        // (4000 + 2500 + 6000) / 3 = 12500 / 3 = 4166.666666666667
        String avg = (String) derive.evaluate("GGSNRecord", GGSN_RECORD, new DeriveSpec.Stats())[0];
        assertTrue(avg.startsWith("4166.666666"), avg);

        @SuppressWarnings("unchecked")
        List<?> rawList = (List<?>) GGSN_RECORD.get("listOfServiceData");
        List<Map<String, Object>> grouped = DeriveSpec.groupAndAggregate(
                rawList,
                List.of("ratingGroup"),
                Map.of("downlink", "mean", "uplink", "keep", "ratingGroup", "count"),
                new DeriveSpec.Stats());
        assertEquals(0, new BigDecimal("5000").compareTo((BigDecimal) grouped.get(0).get("downlink_mean")));
        assertEquals("500", grouped.get(0).get("uplink"));
        assertEquals(2L, grouped.get(0).get("ratingGroup_count"));
    }

    @Test
    void joinModeLeftVsInnerAndValidation() {
        ChildSpec leftAndInner = ChildSpec.parse(
                m("GGSNRecord", m(
                        "listOfServiceData", m("segment", "SvcLeft", "join_mode", "left"),
                        "emptyServiceData", m("segment", "SvcInner", "join_mode", "inner")
                )),
                Set.of("GGSNRecord", "SvcLeft", "SvcInner"),
                null);

        assertEquals("left", leftAndInner.of("GGSNRecord").get(0).joinMode());
        assertEquals("inner", leftAndInner.of("GGSNRecord").get(1).joinMode());

        IllegalArgumentException badJoin = assertThrows(IllegalArgumentException.class, () ->
                ChildSpec.parse(
                        m("GGSNRecord", m("listOfServiceData", m("segment", "SvcLeft", "join_mode", "outer"))),
                        Set.of("GGSNRecord", "SvcLeft"),
                        null));
        assertTrue(badJoin.getMessage().startsWith("ASN1-CHILD-BAD-JOIN-MODE"), badJoin.getMessage());

        IllegalArgumentException badAgg = assertThrows(IllegalArgumentException.class, () ->
                ChildSpec.parse(
                        m("GGSNRecord", m("listOfServiceData", m("segment", "SvcLeft", "aggs", m("downlink", "median")))),
                        Set.of("GGSNRecord", "SvcLeft"),
                        null));
        assertTrue(badAgg.getMessage().startsWith("ASN1-CHILD-BAD-AGGS"), badAgg.getMessage());
    }

    @Test
    void renamePrefixPostfixAndCheckClash() throws IOException {
        DeriveSpec ok = DeriveSpec.parse(
                m("GGSNRecord", m(
                        "DL", m("reduce", "listOfServiceData", "op", "sum", "of", "downlink",
                                "rename", "TOTAL_DL", "prefix", "SVC_", "postfix", "_BYTES")
                )),
                Set.of("GGSNRecord"),
                null,
                null);
        assertEquals(List.of("SVC_TOTAL_DL_BYTES"), ok.columns("GGSNRecord"));
        assertEquals("12500", ok.evaluate("GGSNRecord", GGSN_RECORD, new DeriveSpec.Stats())[0]);

        IllegalArgumentException rawClash = assertThrows(IllegalArgumentException.class,
                () -> ok.checkClash("GGSNRecord", List.of("IMSI", "SVC_TOTAL_DL_BYTES")));
        assertTrue(rawClash.getMessage().startsWith("ASN1-DERIVE-COLUMN-CLASH"), rawClash.getMessage());

        IllegalArgumentException dupRawClash = assertThrows(IllegalArgumentException.class,
                () -> ok.checkClash("GGSNRecord", List.of("IMSI", "IMSI")));
        assertTrue(dupRawClash.getMessage().startsWith("ASN1-DERIVE-COLUMN-CLASH"), dupRawClash.getMessage());

        IllegalArgumentException deriveClash = assertThrows(IllegalArgumentException.class, () ->
                DeriveSpec.parse(
                        m("GGSNRecord", m(
                                "A", m("reduce", "listOfServiceData", "op", "count", "rename", "X_B"),
                                "B", m("reduce", "listOfServiceData", "op", "count", "prefix", "X_")
                        )),
                        Set.of("GGSNRecord"),
                        null,
                        null));
        assertTrue(deriveClash.getMessage().startsWith("ASN1-DERIVE-COLUMN-CLASH"), deriveClash.getMessage());
    }
}
