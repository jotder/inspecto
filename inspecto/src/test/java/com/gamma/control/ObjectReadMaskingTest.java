package com.gamma.control;

import com.gamma.pipeline.ComponentStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** TELCO-FRAUD-MASKED-DASHBOARD-1 (c): an Alert / Incident read masks the breached key for a caller who cannot reveal. */
class ObjectReadMaskingTest {

    private static final String RAW = "99970001401";

    private static ObjectReadMasking masking(Path root, String classification) throws Exception {
        new ComponentStore(root.resolve("registry")).write("dataset", "calls", Map.of("columns", List.of(
                Map.of("name", "msisdn", "classification", classification),
                Map.of("name", "region", "classification", "NONE"))));
        return ObjectReadMasking.of(root);
    }

    private static Map<String, Object> incident() {
        Map<String, String> attrs = new LinkedHashMap<>();
        attrs.put("rule", "r1");
        attrs.put("dataset", "calls");
        attrs.put("alertKey", "r1|msisdn=" + RAW + ",region=EU");
        attrs.put("key", "msisdn=" + RAW + ", region=EU");
        attrs.put("key.msisdn", RAW);
        attrs.put("key.region", "EU");
        attrs.put("value", "7.0");
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", "INC-1");
        row.put("title", "Sum is above 5 for msisdn=" + RAW + ", region=EU");
        row.put("description", "x " + RAW);
        row.put("attributes", attrs);
        return row;
    }

    @Test
    @SuppressWarnings("unchecked")
    void aClassifiedKeyIsMaskedEverywhereItStandsAndAnUnclassifiedOneIsNot(@TempDir Path root) throws Exception {
        Map<String, Object> out = masking(root, "MSISDN").maskObject(incident());
        Map<String, String> a = (Map<String, String>) out.get("attributes");
        assertTrue(a.get("key.msisdn").startsWith("masked:"), a.toString());
        assertEquals("EU", a.get("key.region"));
        assertEquals("7.0", a.get("value"));
        assertFalse(out.toString().contains(RAW), out.toString());
        assertTrue(out.get("title").toString().contains(a.get("key.msisdn")));
        assertTrue(out.get("title").toString().contains("region=EU"));
        assertEquals(Boolean.TRUE, out.get("keyMasked"));
        assertTrue(incident().toString().contains(RAW), "the input row is not mutated");
    }

    @Test
    void nothingIsMaskedWhenTheColumnIsNotClassified(@TempDir Path root) throws Exception {
        Map<String, Object> row = incident();
        assertSame(row, masking(root, "NONE").maskObject(row));
    }

    @Test
    void anAlertMessageIsMaskedForItsClassifiedColumnOnly(@TempDir Path root) throws Exception {
        ObjectReadMasking m = masking(root, "MSISDN");
        String msg = m.alertMessage("CRITICAL: Sum of x on calls for msisdn=" + RAW + ", region=EU is 7, above the threshold of 5", "calls");
        assertFalse(msg.contains(RAW), msg);
        assertTrue(msg.contains("masked:") && msg.contains("region=EU is 7, above"), msg);
        assertEquals("CRITICAL: no key here", m.alertMessage("CRITICAL: no key here", "calls"));
    }
}
