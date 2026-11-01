package com.gamma.regreporting;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code PACK-PARTNER-SETTLEMENT-1} slice S7, the template half: the shipped {@code settlement-dispute-pack} Report Templates
 * (JSON and CSV) parse, name the rate ids and re-rating run of every disputed line, and refuse (never export blank) a line
 * with no re-rating run. The rows are shaped like the {@code settlement_dispute} rows the S5 golden pins; that the shipped
 * Job really writes every column the templates read is {@code PartnerSettlementDisputeGoldenTest} in
 * {@code inspecto-reconciliation}, and the draft and four-eyes path over HTTP is {@link ControlApiRegulatoryReportRowsTest}.
 */
class PartnerSettlementDisputePackTest {

    static final Path TEMPLATES = Path.of("../../spaces/_templates/partner-settlement/config", ReportTemplate.SPACE_DIR);
    private static final ObjectMapper JSON = new ObjectMapper();

    static ReportTemplate shipped(String id) throws Exception {
        return ReportTemplate.parse(Files.readString(TEMPLATES.resolve(id + ".toon")), id, "space");
    }

    /** One dispute line as {@code ps_dispute} writes it (P1, June, an amount mismatch). */
    static Map<String, Object> line(String item, String rateIds, String run) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("dispute_key", "P1|2026-06-01|2026-06-30");
        r.put("item_key", item);
        r.put("partner_id", "P1");
        r.put("agreement_id", "AG-VOICE-A1");
        r.put("direction", "we_pay");
        r.put("service", "voice");
        r.put("destination_class", "mobile");
        r.put("usage_date", "2026-06-20");
        r.put("period_start", "2026-06-01");
        r.put("period_end", "2026-06-30");
        r.put("reason", "amount_mismatch");
        r.put("side", "both");
        r.put("expected_amount", new BigDecimal("100.0000"));
        r.put("billed_amount", new BigDecimal("110.0000"));
        r.put("variance", new BigDecimal("10.0000"));
        r.put("disputed_amount", new BigDecimal("10.0000"));
        r.put("currency", "USD");
        r.put("netting_group", "NG-XA");
        r.put("dispute_window_end", "2026-08-29");
        r.put("rate_ids", rateIds);
        r.put("rating_run_id", run);
        r.put("agreement_version", "2026-01-01");
        r.put("tolerance_type", "pct");
        r.put("tolerance", new BigDecimal("1.0000"));
        r.put("tolerance_amount", new BigDecimal("1.0000"));
        r.put("rated_currency", "USD");
        r.put("our_fx_rate", new BigDecimal("1.00000000"));
        r.put("our_fx_rate_date", null);
        r.put("invoiced_currency", "USD");
        r.put("partner_fx_rate", null);
        r.put("statement_id", "ST-P1-2026-06");
        r.put("statement_version", 1L);
        return r;
    }

    static Map<String, Object> context(List<Map<String, Object>> rows) {
        Map<String, Object> ctx = new LinkedHashMap<>();
        ctx.put("report", Map.of("id", "rr-20261101120000-abcdef", "createdAt", "2026-11-01T12:00:00Z", "author", "desk-1"));
        ctx.put("subject", Map.of("id", "case-1", "title", "Partner settlement dispute: one partner and cycle", "status", "DISPUTED",
                "impact", Map.of("suspected", new BigDecimal("14.0000"), "currency", "USD")));
        ctx.put("incidents", List.of(
                Map.of("id", "inc-1", "title", "ps_dispute_line 06-20", "status", "IDENTIFIED", "severity", "CRITICAL", "createdAt", "2026-08-20T06:00:00Z"),
                Map.of("id", "inc-2", "title", "ps_dispute_line 06-21", "status", "IDENTIFIED", "severity", "CRITICAL", "createdAt", "2026-08-20T06:00:01Z")));
        ctx.put("evidence", List.of());
        ctx.put("rows", rows);
        ctx.put("input", new HashMap<>(Map.of("narrative", "June voice lines billed above the agreed rate")));
        return ctx;
    }

    @Test
    void theShippedTemplatesParseAndReadTheDisputeRowsByDisputeKey() throws Exception {
        for (String id : List.of("settlement-dispute-pack", "settlement-dispute-pack-csv")) {
            ReportTemplate t = shipped(id);
            assertEquals(new ReportTemplate.Rows("settlement_dispute", "dispute_key"), t.rows(), id);
            assertTrue(t.fields().stream().anyMatch(f -> f.source().equals("rows.rating_run_id") && f.required()), id);
            assertTrue(t.fields().stream().anyMatch(f -> f.source().equals("rows.rate_ids") && f.required()), id);
        }
    }

    @Test
    void theJsonPackNamesTheRateAndRunOfEveryDisputedLineAndTheIncidentTimeline() throws Exception {
        String content = ReportRenderer.render(shipped("settlement-dispute-pack"), context(List.of(
                line("AG-VOICE-A1|we_pay|voice|mobile|2026-06-20", "R-A1-MOB", "run-2026-06-b"),
                line("AG-VOICE-A1|we_pay|voice|mobile|2026-06-21", "R-A1-MOB,R-A1-MOB-PEAK", "run-2026-06-b")))).content();
        JsonNode pack = JSON.readTree(content).get("settlementDisputePack");
        assertEquals("inspecto-settlement-dispute-pack/1", pack.get("schema").asText());
        JsonNode lines = pack.get("line");
        assertEquals(2, lines.size(), content);
        assertEquals("R-A1-MOB,R-A1-MOB-PEAK", lines.get(1).get("rateIds").asText());
        for (JsonNode l : lines) {
            assertEquals("run-2026-06-b", l.get("ratingRunId").asText(), l.toString());
            assertEquals("2026-01-01", l.get("agreementVersion").asText());
            assertEquals("ST-P1-2026-06", l.get("statementId").asText());
            assertEquals("10.0000", l.get("variance").asText());
        }
        assertEquals(2, pack.get("incident").size(), "the Incident timeline");
        assertEquals("2026-08-20T06:00:00Z", pack.get("incident").get(0).get("openedAt").asText());
    }

    @Test
    void theCsvPackNamesTheRateAndRunToo() throws Exception {
        String content = ReportRenderer.render(shipped("settlement-dispute-pack-csv"), context(List.of(
                line("AG-VOICE-A1|we_pay|voice|mobile|2026-06-20", "R-A1-MOB", "run-2026-06-b")))).content();
        String[] lines = content.split("\r?\n");
        assertEquals(2, lines.length, content);
        assertTrue(lines[0].contains("line/ratingRunId") || lines[0].contains("ratingRunId"), lines[0]);
        assertTrue(lines[1].contains("R-A1-MOB") && lines[1].contains("run-2026-06-b"), lines[1]);
    }

    @Test
    void aLineWithNoReRatingRunIsRefusedNotExportedBlank() throws Exception {
        for (String id : List.of("settlement-dispute-pack", "settlement-dispute-pack-csv")) {
            ReportRenderer.Refused e = assertThrows(ReportRenderer.Refused.class, () -> ReportRenderer.render(shipped(id), context(List.of(
                    line("AG-VOICE-A1|we_pay|voice|mobile|2026-06-20", "R-A1-MOB", "run-2026-06-b"),
                    line("AG-VOICE-A1|we_pay|voice|mobile|2026-06-21", null, null)))));
            assertTrue(e.problems().contains("required field 'line/ratingRunId' is empty in rows item 2"), e.problems().toString());
            assertTrue(e.problems().contains("required field 'line/rateIds' is empty in rows item 2"), e.problems().toString());
            assertThrows(ReportRenderer.Refused.class, () -> ReportRenderer.render(shipped(id), context(List.of())),
                    id + ": a Case with no dispute rows files nothing");
        }
    }
}
