package com.gamma.predictive;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** The {@code segmentation-model} structure: defaults, the label table, and every fail-closed refusal. */
class SegmentationModelTest {

    private static Map<String, Object> with(String key, Object value) {
        Map<String, Object> m = new LinkedHashMap<>(RfmCorpus.model());
        if (value == null) m.remove(key); else m.put(key, value);
        return m;
    }

    private static void refused(Map<String, Object> content, String expected) {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> SegmentationModel.fromMap("rfm", content));
        assertTrue(e.getMessage().contains(expected), "expected '" + expected + "' in: " + e.getMessage());
    }

    @Test
    void theCorpusModelParsesWithItsDefaults() {
        SegmentationModel m = SegmentationModel.fromMap("rfm", RfmCorpus.model());
        assertEquals("count", m.frequency(), "frequency defaults to count");
        assertEquals("Other", m.otherLabel());
        assertEquals(2, m.bins());
        assertEquals(90, m.window());
        assertEquals("customer_segments_rfm", m.segmentsDataset());
        assertEquals("customer_segments_rfm_latest", m.latestDataset());
        assertEquals(List.of("customer_id", "order_date", "amount"), List.copyOf(m.referencedColumns()));
        assertEquals(5, SegmentationModel.fromMap("rfm", with("bins", null)).bins(), "bins defaults to 5");
        assertNull(SegmentationModel.fromMap("rfm", with("window", null)).window(), "no window = open history");
    }

    @Test
    void theFirstMatchingRowNamesTheSegmentElseOtherLabel() {
        Map<String, Object> c = with("bins", 5);
        c.put("segments", List.of(Map.of("label", "Top", "recency", "4-5", "frequency", "4-5", "monetary", "*"),
                Map.of("label", "Recent", "recency", "5", "frequency", "*", "monetary", "*")));
        c.put("otherLabel", "Rest");
        SegmentationModel m = SegmentationModel.fromMap("rfm", c);
        assertEquals("Top", m.labelOf(5, 5, 1), "both rows match: the first wins");
        assertEquals("Recent", m.labelOf(5, 3, 1));
        assertEquals("Top", m.labelOf(4, 4, 3), "ranges are inclusive at both ends");
        assertEquals("Rest", m.labelOf(3, 5, 5));
        assertEquals(new SegmentationModel.Range(1, 5), m.segments().get(0).monetary(), "* is 1..bins");
    }

    @Test
    void everyMalformedModelIsRefusedNamingTheField() {
        refused(with("colour", "red"), "unknown key(s) [colour]");
        refused(with("entityType", null), "segmentation-model.entityType is required");
        refused(with("key", "customer id"), "segmentation-model.key 'customer id' must match");
        refused(with("monetary", null), "segmentation-model.monetary is required");
        refused(with("monetary", "avg(amount)"), "monetary must be sum(field)");
        refused(with("monetary", "sum(amount; drop)"), "measure field 'amount; drop' must match");
        refused(with("frequency", "sum(amount)"), "frequency must be count or countDistinct(field)");
        refused(with("bins", 1), "segmentation-model.bins must be a whole number in 2..10");
        refused(with("bins", 11), "segmentation-model.bins must be a whole number in 2..10");
        refused(with("bins", 2.5), "segmentation-model.bins must be a whole number in 2..10");
        refused(with("window", 0), "segmentation-model.window must be a whole number in 1..3660");
        refused(with("maxEntities", 3_000_000), "maxEntities must be a whole number in 1..2000000");
        refused(with("segments", List.of()), "segments must be a non-empty list");
        refused(with("segments", List.of(Map.of("label", "A", "recency", "3", "frequency", "*", "monetary", "*"))),
                "segments[0].recency range '3' must lie inside 1..2");
        refused(with("segments", List.of(Map.of("label", "A", "recency", "2-1", "frequency", "*", "monetary", "*"))),
                "segments[0].recency range '2-1' must lie inside 1..2 with low <= high");
        refused(with("segments", List.of(Map.of("label", "A", "recency", "high", "frequency", "*", "monetary", "*"))),
                "segments[0].recency must be *, n or a-b");
        refused(with("segments", List.of(Map.of("label", "A", "recency", "*", "frequency", "*", "monetary", "*"),
                Map.of("label", "A", "recency", "*", "frequency", "*", "monetary", "*"))), "segments[1].label 'A' is not unique");
        refused(with("segments", List.of(Map.of("label", "Other", "recency", "*", "frequency", "*", "monetary", "*"))),
                "segments[0].label 'Other' is not unique (otherLabel counts too)");
        refused(with("segments", List.of(Map.of("label", "A", "recency", "*", "frequency", "*", "monetary", "*",
                "weight", 1))), "segments[0] has unknown key(s) [weight]");
        IllegalArgumentException id = assertThrows(IllegalArgumentException.class,
                () -> SegmentationModel.fromMap("rfm-x", RfmCorpus.model()));
        assertTrue(id.getMessage().contains("segmentation-model id must match"), id.getMessage());
    }
}
