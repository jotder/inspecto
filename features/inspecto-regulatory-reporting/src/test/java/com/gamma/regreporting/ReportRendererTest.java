package com.gamma.regreporting;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** {@link ReportRenderer}: the three formats, repeating groups, escaping, and every refusal (never truncation). */
@SuppressWarnings("unchecked")
class ReportRendererTest {

    private static Map<String, Object> context() {
        Map<String, Object> ctx = new LinkedHashMap<>();
        ctx.put("report", Map.of("id", "rr-20261009120000-abcdef", "createdAt", "2026-10-09T12:00:00Z", "author", "maker-1"));
        Map<String, Object> subject = new LinkedHashMap<>();
        subject.put("id", "case-1");
        subject.put("objectType", "CASE");
        subject.put("title", "Structuring <suspected> & more");
        subject.put("severity", "high");
        subject.put("impact", Map.of("suspected", new BigDecimal("1250.50"), "currency", "EUR"));
        ctx.put("subject", subject);
        ctx.put("incidents", List.of(Map.of("id", "inc-1", "title", "Three deposits", "status", "RESOLVED"),
                Map.of("id", "inc-2", "title", "=HYPERLINK(\"x\")", "status", "IDENTIFIED")));
        ctx.put("evidence", List.of(Map.of("name", "statement.pdf", "uri", "https://dms/1", "contentType", "application/pdf")));
        ctx.put("input", new HashMap<>(Map.of("reportingEntity", "Example Bank", "narrative", "Customer split cash, deposits")));
        return ctx;
    }

    private static ReportTemplate sample() {
        return ReportTemplate.load(null).get("sample-sar");
    }

    private static ReportTemplate as(String format) throws Exception {
        String toon = """
                id: tt
                title: T
                format: %s
                delivery:
                  kind: file-drop
                  dir: d
                fields[5]{name,source,required,maxLength}:
                  reportId,report.id,true,0
                  subject,subject.title,true,0
                  incidentIds,incidents.id,false,0
                  inc/id,incidents.id,false,0
                  inc/title,incidents.title,false,0
                """.formatted(format);
        return ReportTemplate.parse(toon, "tt", "space");
    }

    @Test
    void theSampleRendersAsEscapedXmlWithRepeatingGroups() throws Exception {
        ReportRenderer.Rendered r = ReportRenderer.render(sample(), context());
        String x = r.content();
        assertTrue(x.startsWith("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<suspiciousActivityReport>"), x);
        assertTrue(x.contains("<schema>inspecto-sample-sar/1</schema>"), x);
        assertTrue(x.contains("<subjectTitle>Structuring &lt;suspected&gt; &amp; more</subjectTitle>"), x);
        assertTrue(x.contains("<amountSuspected>1250.50</amountSuspected>"), x);
        assertTrue(x.contains("<incident>\n    <id>inc-1</id>\n    <title>Three deposits</title>\n    <status>RESOLVED</status>\n  </incident>"), x);
        assertTrue(x.contains("<incident>\n    <id>inc-2</id>"), x);
        assertTrue(x.contains("<evidence>\n    <name>statement.pdf</name>"), x);
        assertFalse(x.contains("<activityPeriod>"), "an optional empty field is omitted");
        assertEquals("application/xml", r.mediaType());
        assertEquals(64, r.sha256().length());
        assertEquals(r.sha256(), ReportRenderer.render(sample(), context()).sha256(), "rendering is deterministic");
    }

    @Test
    void jsonCarriesListsAndGroupsAsArrays() throws Exception {
        JsonNode doc = new ObjectMapper().readTree(ReportRenderer.render(as("json"), context()).content());
        assertEquals("rr-20261009120000-abcdef", doc.get("reportId").asText());
        assertEquals(2, doc.get("incidentIds").size());
        assertEquals("inc-2", doc.get("inc").get(1).get("id").asText());
    }

    @Test
    void csvIsOneHeaderAndOneRowWithFormulasNeutralised() throws Exception {
        String csv = ReportRenderer.render(as("csv"), context()).content();
        String[] lines = csv.split("\r\n");
        assertEquals("reportId,subject,incidentIds,inc/id,inc/title", lines[0]);
        assertTrue(lines[1].startsWith("rr-20261009120000-abcdef,Structuring <suspected> & more,inc-1; inc-2,inc-1; inc-2,"), lines[1]);
        assertEquals("\"'=HYPERLINK(\"\"x\"\")\"", ReportRenderer.csvCell("=HYPERLINK(\"x\")"));
        assertEquals("\"a,b\"", ReportRenderer.csvCell("a,b"));
        assertEquals("'+1", ReportRenderer.csvCell("+1"));
    }

    @Test
    void aMissingRequiredFieldRefusesNamingIt() throws Exception {
        Map<String, Object> ctx = context();
        ((Map<String, Object>) ctx.get("input")).remove("narrative");
        ReportRenderer.Refused e = assertThrows(ReportRenderer.Refused.class, () -> ReportRenderer.render(sample(), ctx));
        assertTrue(e.getMessage().contains("required field 'narrative'"), e.getMessage());
    }

    @Test
    void aValueOverItsMaxLengthRefusesAndIsNeverTruncated() {
        Map<String, Object> ctx = context();
        ((Map<String, Object>) ctx.get("input")).put("reportingEntity", "x".repeat(257));
        ReportRenderer.Refused e = assertThrows(ReportRenderer.Refused.class, () -> ReportRenderer.render(sample(), ctx));
        assertTrue(e.getMessage().contains("'reportingEntity' is 257 characters; its maxLength is 256"), e.getMessage());
    }

    @Test
    void aSourceThatLandsOnAnObjectRefuses() throws Exception {
        String toon = """
                id: tt
                title: T
                format: json
                delivery:
                  kind: file-drop
                  dir: d
                fields[1]{name,source,required,maxLength}:
                  impact,subject.impact,false,0
                """;
        ReportRenderer.Refused e = assertThrows(ReportRenderer.Refused.class,
                () -> ReportRenderer.render(ReportTemplate.parse(toon, "tt", "space"), context()));
        assertTrue(e.getMessage().contains("resolves to an object"), e.getMessage());
    }

    @Test
    void aControlCharacterXmlCannotCarryRefuses() {
        Map<String, Object> ctx = context();
        ((Map<String, Object>) ctx.get("input")).put("narrative", "bell\u0007");
        ReportRenderer.Refused e = assertThrows(ReportRenderer.Refused.class, () -> ReportRenderer.render(sample(), ctx));
        assertTrue(e.getMessage().contains("U+0007"), e.getMessage());
    }

    @Test
    void theSourceFingerprintIgnoresTheReportAndInputsButSeesTheCase() {
        Map<String, Object> a = context();
        Map<String, Object> b = context();
        b.put("report", Map.of("id", "rr-other"));
        b.put("input", Map.of("narrative", "different"));
        assertEquals(ReportRenderer.sourceFingerprint(a), ReportRenderer.sourceFingerprint(b));
        ((Map<String, Object>) b.get("subject")).put("title", "edited since");
        assertNotEquals(ReportRenderer.sourceFingerprint(a), ReportRenderer.sourceFingerprint(b));
    }
}
