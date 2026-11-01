package com.gamma.job;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.alert.AlertRule;
import com.gamma.control.ControlApi;
import com.gamma.pipeline.ComponentRegistry;
import com.gamma.pipeline.ComponentStore;
import com.gamma.query.DatasetMeasureProbe;
import com.gamma.service.SpaceManager;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code PACK-PARTNER-SETTLEMENT-1} slice S6: the shipped {@code ps_aging} and {@code ps_accrual} Jobs, the payment-due Alert Rule
 * {@code ps_payment_due}, the three KPIs and the {@code settlement_overview} dashboard, over the S4 corpus. Aging and accruals equal
 * an independent {@code BigDecimal} oracle built from the corpus (the S4 movement oracle and the re-rated usage), the late-statement
 * boundary moves an accrual from {@code unbilled} to {@code statement_late} on the day the {@code missing_statement} finding starts,
 * and every dashboard tile renders through the routes the SPA uses.
 */
class PartnerSettlementAgingGoldenTest {

    private static final LocalDate AS_OF_DAY = PartnerSettlementCorpus.AS_OF;
    private static final Map<String, String> AS_OF = Map.of("as_of", AS_OF_DAY.toString());
    private static final List<String> AGING_COLS = List.of("agreement_id", "partner_id", "direction", "netting_group", "currency", "statement_date",
            "payment_due", "age_days", "age_bucket", "invoiced", "settled_applied", "open_balance", "open_disputed", "disputed_lines", "payment_alert",
            "aged_late_amount", "kpi_open_disputed", "kpi_aged_late");
    private static final List<String> ACCRUAL_COLS = List.of("agreement_id", "partner_id", "direction", "currency", "statement_due", "status",
            "expected_amount", "invoiced_amount", "accrued_unbilled", "disputed_amount", "dispute_provision", "accrual_total", "kpi_accrued_unbilled");

    private static Path tmp;
    private static PartnerSettlementCorpus corpus;
    private static PartnerSettlementSpace base;

    @BeforeAll
    static void buildTheBaseSpace() throws Exception {
        PartnerSettlementSpace.arm();
        tmp = Files.createTempDirectory("ps-s6-golden");
        corpus = PartnerSettlementCorpus.generateWithPositions();
        base = PartnerSettlementSpace.create(tmp.resolve("base"));
        base.ingest(corpus.files());
        base.runChain(AS_OF);
    }

    @AfterAll
    static void cleanUp() throws IOException {
        PartnerSettlementSpace.disarm();
        if (tmp != null) try (Stream<Path> w = Files.walk(tmp)) { w.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete()); }
    }

    // ------------------------------------------------------------------------------------------------------------- aging

    @Test
    void agingEqualsTheOracleCycleByCycle() throws Exception {
        Map<String, String> want = agingOracle(base, AS_OF_DAY, 30, 60, 90, 5);
        assertEquals(13, want.size(), "thirteen statement cycles received by the corpus day");
        assertSame("aging", want, rows(base, "settlement_aging", "aging_key", AGING_COLS));
        // hand-worked: A1's records up to the corpus day are 10000 + 150 (credit note) + 2500 - 25.5 (debit note) = 12624.5; May
        // (10183.0100) takes them first, June gets the remaining 2441.4900
        Map<String, Object> june = base.read("settlement_aging", "aging_key = 'AG-VOICE-A1|2026-06-01|2026-06-30'", "aging_key").get(0);
        assertEquals(new BigDecimal("2441.4900"), june.get("settled_applied"));
        assertEquals(new BigDecimal("0.0000"), base.read("settlement_aging", "aging_key = 'AG-VOICE-A1|2026-05-01|2026-05-31'", "aging_key").get(0).get("open_balance"));
        // D1's only record names another partner (unknown_agreement): it settles nothing, so May is open and aged 75 days
        Map<String, Object> d1 = base.read("settlement_aging", "aging_key = 'AG-VOICE-D1|2026-05-01|2026-05-31'", "aging_key").get(0);
        assertEquals(new BigDecimal("1702.0000"), d1.get("open_balance"));
        assertEquals("61-90", d1.get("age_bucket"));
        // every statement cycle is aged once, and the money adds up: what was applied never exceeds what finance recorded
        assertTrue(base.read("settlement_aging", "open_balance <> invoiced - settled_applied OR settled_applied < 0", "aging_key").isEmpty());
    }

    @Test
    void theBucketBoundariesAreInclusiveAndConfigurable() throws Exception {
        Map<String, Object> row = base.read("settlement_aging", "agreement_id = 'AG-VOICE-D1'", "period_start").get(0);
        LocalDate stmt = (LocalDate) row.get("statement_date");
        String key = (String) row.get("aging_key");
        Map<Integer, String> want = Map.of(30, "0-30", 31, "31-60", 60, "31-60", 61, "61-90", 90, "61-90", 91, "90+");
        int i = 0;
        PartnerSettlementSpace s = base.copyTo(tmp.resolve("buckets"));
        for (Map.Entry<Integer, String> e : new TreeMap<>(want).entrySet()) {
            s.run(List.of("ps_aging"), Map.of("as_of", stmt.plusDays(e.getKey()).toString()));
            assertEquals(e.getValue(), s.read("settlement_aging", "aging_key = '" + key + "'", "aging_key").get(0).get("age_bucket"), "age " + e.getKey());
            i++;
        }
        assertEquals(6, i);
        // the boundaries are Job parameters: 10 / 20 / 45 relabel the same row
        s.run(List.of("ps_aging"), Map.of("as_of", stmt.plusDays(21).toString(), "aging_bucket_1", "10", "aging_bucket_2", "20", "aging_bucket_3", "45"));
        assertEquals("21-45", s.read("settlement_aging", "aging_key = '" + key + "'", "aging_key").get(0).get("age_bucket"));
        assertEquals(agingOracle(s, stmt.plusDays(21), 10, 20, 45, 5), rows(s, "settlement_aging", "aging_key", AGING_COLS));
    }

    @Test
    void thePaymentDueAlertRuleRaisesExactlyTheOpenCyclesAtOrPastTheirLeadTime() throws Exception {
        Set<String> want = new TreeSet<>();
        for (Map<String, Object> r : base.read("settlement_aging", null, "aging_key")) {
            boolean open = ((BigDecimal) r.get("open_balance")).signum() > 0;
            if (open && ChronoUnit.DAYS.between(AS_OF_DAY, (LocalDate) r.get("payment_due")) <= 5) want.add((String) r.get("aging_key"));
        }
        assertEquals(10, want.size(), "ten open cycles at or past their lead time");
        Set<String> got = raised(base, "ps_payment_due", "aging_key");
        assertEquals(want, got);
        // the corpus holds both sides of the boundary: C1's late-July cycle is due in exactly 5 days (raised), E1's June cycle in
        // exactly 6 (silent), and A1's fully paid May is silent though long past its due date
        assertTrue(got.contains("AG-SMS-C1|2026-07-16|2026-07-31"));
        assertFalse(got.contains("AG-VOICE-E1|2026-06-01|2026-06-30"));
        assertFalse(got.contains("AG-VOICE-A1|2026-05-01|2026-05-31"));
        // and the lead time is the Job's parameter: at 6 days E1's June joins
        PartnerSettlementSpace s = base.copyTo(tmp.resolve("due"));
        s.run(List.of("ps_aging"), Map.of("as_of", AS_OF_DAY.toString(), "payment_alert_days", "6"));
        assertTrue(raised(s, "ps_payment_due", "aging_key").contains("AG-VOICE-E1|2026-06-01|2026-06-30"));
        // a payment recorded after the as-of day is not counted: as of the day before C1's 07-10 payment, its first cycle is fully open
        s.run(List.of("ps_aging"), Map.of("as_of", "2026-07-09"));
        assertEquals(new BigDecimal("1566.0000"), s.read("settlement_aging", "aging_key = 'AG-SMS-C1|2026-06-01|2026-06-15'", "aging_key").get(0).get("open_balance"));
    }

    @Test
    void applyingPaymentsNewestCycleFirstIsRed() throws Exception {
        PartnerSettlementSpace m = base.copyTo(tmp.resolve("lifo"));
        Path job = m.config().resolve("jobs").resolve("ps_aging_job.toon");
        String toon = Files.readString(job);
        String fifo = "ORDER BY c.period_end, c.period_start ROWS";
        assertTrue(toon.contains(fifo));
        Files.writeString(job, toon.replace(fifo, "ORDER BY c.period_end DESC, c.period_start DESC ROWS"));
        m.run(List.of("ps_aging"), AS_OF);
        assertNotEquals(agingOracle(m, AS_OF_DAY, 30, 60, 90, 5), rows(m, "settlement_aging", "aging_key", AGING_COLS), "the oracle applies the oldest cycle first");
    }

    /** The mutation pass: each mutant of a shipped Job turns a named row red. */
    @Test
    void eachMutantTurnsANamedRowRed() throws Exception {
        // a payment recorded after the as-of day counted: C1's first cycle as of 07-09 loses the 07-10 payment's 1000
        PartnerSettlementSpace m = mutant("late-payment", "ps_aging", "(source = 'statement' OR movement_date <= CAST($as_of AS DATE))", "(source = 'statement' OR TRUE)");
        m.run(List.of("ps_aging"), Map.of("as_of", "2026-07-09"));
        assertEquals(new BigDecimal("566.0000"), m.read("settlement_aging", "aging_key = 'AG-SMS-C1|2026-06-01|2026-06-15'", "aging_key").get(0).get("open_balance"));

        // the first bucket boundary exclusive: D1's May statement aged exactly 30 days leaves 0-30
        m = mutant("bucket", "ps_aging", "WHEN age_days <= b1 THEN '0-'", "WHEN age_days < b1 THEN '0-'");
        LocalDate stmt = (LocalDate) base.read("settlement_aging", "aging_key = 'AG-VOICE-D1|2026-05-01|2026-05-31'", "aging_key").get(0).get("statement_date");
        m.run(List.of("ps_aging"), Map.of("as_of", stmt.plusDays(30).toString()));
        assertEquals("31-60", m.read("settlement_aging", "aging_key = 'AG-VOICE-D1|2026-05-01|2026-05-31'", "aging_key").get(0).get("age_bucket"));

        // the payment lead time exclusive: C1's late-July cycle, due in exactly 5 days, goes silent
        m = mutant("alert", "ps_aging", "days_to_payment_due <= CAST($payment_alert_days AS INTEGER)", "days_to_payment_due < CAST($payment_alert_days AS INTEGER)");
        m.run(List.of("ps_aging"), AS_OF);
        assertFalse(raised(m, "ps_payment_due", "aging_key").contains("AG-SMS-C1|2026-07-16|2026-07-31"));

        // the KPI currency filter dropped: C1's EUR disputes (70.0000 + 5.0001) join the USD tile
        m = mutant("kpi", "ps_aging", "CASE WHEN currency = $kpi_currency THEN open_disputed", "CASE WHEN TRUE THEN open_disputed");
        m.run(List.of("ps_aging"), AS_OF);
        assertEquals(new BigDecimal("288.2352"), sum(m, "settlement_aging", "kpi_open_disputed"));

        // the statement-due day counted late: E1's July accrual reads statement_late on its due date
        LocalDate due = (LocalDate) base.read("settlement_accrual", "accrual_key = 'AG-VOICE-E1|2026-07-01|2026-07-31'", "accrual_key").get(0).get("statement_due");
        m = mutant("status", "ps_accrual", "WHEN CAST($as_of AS DATE) <= e.statement_due THEN 'unbilled'", "WHEN CAST($as_of AS DATE) < e.statement_due THEN 'unbilled'");
        m.run(List.of("ps_accrual"), Map.of("as_of", due.toString()));
        assertEquals("statement_late", m.read("settlement_accrual", "accrual_key = 'AG-VOICE-E1|2026-07-01|2026-07-31'", "accrual_key").get(0).get("status"));
    }

    /** A copy of the base Space with one literal of a shipped Job replaced (the literal must be there). */
    private static PartnerSettlementSpace mutant(String name, String job, String from, String to) throws Exception {
        PartnerSettlementSpace m = base.copyTo(tmp.resolve("mutant-" + name));
        Path file = m.config().resolve("jobs").resolve(job + "_job.toon");
        String toon = Files.readString(file);
        assertTrue(toon.contains(from), job + " no longer holds " + from);
        Files.writeString(file, toon.replace(from, to));
        return m;
    }

    // ---------------------------------------------------------------------------------------------------------- accruals

    @Test
    void accrualsEqualTheOracleAndALateStatementIsNamed() throws Exception {
        assertSame("accrual", accrualOracle(base, AS_OF_DAY, BigDecimal.valueOf(100)), rows(base, "settlement_accrual", "accrual_key", ACCRUAL_COLS));
        Map<String, String> status = new TreeMap<>();
        for (Map<String, Object> r : base.read("settlement_accrual", "status <> 'billed'", "accrual_key")) status.put((String) r.get("accrual_key"), (String) r.get("status"));
        assertEquals(Map.of("AG-VOICE-D1|2026-07-01|2026-07-31", "statement_late", "AG-VOICE-E1|2026-07-01|2026-07-31", "unbilled"), status,
                "D1's July statement is overdue, E1's is not due yet");
        // the statement-late accrual is exactly what ps_expected priced for the missing_statement finding
        BigDecimal d1 = BigDecimal.ZERO;
        for (Map<String, Object> e : base.read("settlement_expected", "agreement_id = 'AG-VOICE-D1' AND period_start = DATE '2026-07-01'", "usage_date"))
            d1 = d1.add((BigDecimal) e.get("amount"));
        assertEquals(0, d1.compareTo((BigDecimal) base.read("settlement_accrual", "accrual_key = 'AG-VOICE-D1|2026-07-01|2026-07-31'", "accrual_key").get(0).get("accrued_unbilled")));
        assertTrue(d1.signum() > 0);
    }

    @Test
    void aLateStatementIsAnAccrualUpToItsDueDateAndAFindingAfter() throws Exception {
        String key = "AG-VOICE-E1|2026-07-01|2026-07-31";
        LocalDate due = (LocalDate) base.read("settlement_accrual", "accrual_key = '" + key + "'", "accrual_key").get(0).get("statement_due");
        assertTrue(due.isAfter(AS_OF_DAY), "E1's July statement is not due on the corpus day");
        assertFalse(due.isAfter(LocalDate.now()), "the boundary day must not be in the future");
        for (Map.Entry<LocalDate, String> e : Map.of(due, "unbilled", due.plusDays(1), "statement_late").entrySet()) {
            PartnerSettlementSpace s = base.copyTo(tmp.resolve("late-" + e.getValue()));
            s.runChain(Map.of("as_of", e.getKey().toString()));
            Map<String, Object> a = s.read("settlement_accrual", "accrual_key = '" + key + "'", "accrual_key").get(0);
            assertEquals(e.getValue(), a.get("status"), "as of " + e.getKey());
            assertTrue(((BigDecimal) a.get("accrued_unbilled")).signum() > 0, "accrued either way: " + a);
            boolean finding = !s.read("settlement_findings", "reason = 'missing_statement' AND item_key = '" + key + "'", "item_key").isEmpty();
            assertEquals("statement_late".equals(e.getValue()), finding, "the missing_statement finding as of " + e.getKey());
        }
    }

    @Test
    void theDisputeProvisionFollowsTheFinancePolicy() throws Exception {
        PartnerSettlementSpace s = base.copyTo(tmp.resolve("provision"));
        s.run(List.of("ps_accrual"), Map.of("as_of", AS_OF_DAY.toString(), "provision_pct", "50"));
        assertSame("accrual at 50 %", accrualOracle(s, AS_OF_DAY, BigDecimal.valueOf(50)), rows(s, "settlement_accrual", "accrual_key", ACCRUAL_COLS));
        assertFalse(s.read("settlement_accrual", "dispute_provision > 0", "accrual_key").isEmpty());
    }

    // --------------------------------------------------------------------------------------------------------- dashboard

    /** Every tile on {@code settlement_overview} renders after the golden run, through the routes the SPA uses (the {@code mobile-money} assertion). */
    @Test
    void everyDashboardTileRendersAfterTheGoldenRun() throws Exception {
        Map<String, BigDecimal> kpiWant = new TreeMap<>();
        kpiWant.put("ps_open_disputed", sum(base, "settlement_aging", "kpi_open_disputed"));
        kpiWant.put("ps_aged_over_60", sum(base, "settlement_aging", "kpi_aged_late"));
        kpiWant.put("ps_accrued_unbilled", sum(base, "settlement_accrual", "kpi_accrued_unbilled"));
        // hand-worked, USD only (C1's EUR stays off the tiles): disputes 7.0100 + 204.0000 + 1.7250 + 0.5001; aged over 60 days D1 May
        // 1702 + E1 May 2123 + F1 May 835; unbilled D1 July 1702 (late) + E1 July 2573 (not due)
        assertEquals(Map.of("ps_open_disputed", new BigDecimal("213.2351"), "ps_aged_over_60", new BigDecimal("4660.0000"),
                "ps_accrued_unbilled", new BigDecimal("4275.0000")), kpiWant);
        ObjectMapper json = new ObjectMapper();
        SpaceManager spaces = SpaceManager.discover(base.root);
        ControlApi api = new ControlApi(spaces, 0);
        try {
            api.start();
            String url = "http://localhost:" + api.port() + "/api/v1/spaces/settle";
            JsonNode dash = body(call(url + "/components/dashboard/settlement_overview", null)).get("content");
            Map<String, BigDecimal> kpiGot = new TreeMap<>();
            Set<String> barsShown = new TreeSet<>();
            int tiles = 0;
            for (JsonNode tile : dash.get("tiles")) {
                tiles++;
                String id = tile.get("widgetId").asText();
                JsonNode w = body(call(url + "/components/widget/" + id, null)).get("content");
                if ("kpi".equals(w.get("vizType").asText())) {
                    String kpi = w.get("options").get("kpi").get("kpiId").asText();
                    JsonNode v = body(call(url + "/kpis/" + kpi + "/value?asOf=" + AS_OF_DAY, null));
                    assertTrue(v.get("value").asDouble() > 0, id + ": the KPI tile has a value: " + v);
                    kpiGot.put(kpi, v.get("value").decimalValue().setScale(4, RoundingMode.HALF_UP));
                    continue;
                }
                assertEquals("bar", w.get("vizType").asText(), id);
                String dataset = w.get("datasetId").asText();
                String x = w.get("controls").get("x").get(0).get("field").asText();
                JsonNode y = w.get("controls").get("y").get(0);
                String agg = y.get("agg").asText(), field = y.get("field").asText();
                String q = json.writeValueAsString(Map.of("dataset", dataset, "groupBy", List.of(x), "measures", List.of(Map.of("agg", agg, "field", field))));
                Map<String, BigDecimal> bars = new TreeMap<>();
                for (JsonNode r : body(call(url + "/bi/query", q)).get("rows"))
                    bars.put(r.get(x).asText(), r.get(agg + "_" + field).decimalValue().setScale(4, RoundingMode.HALF_UP));
                Map<String, BigDecimal> want = new TreeMap<>();
                for (Map<String, Object> r : base.read(dataset, null, x)) want.merge(String.valueOf(r.get(x)), (BigDecimal) r.get(field), BigDecimal::add);
                want.replaceAll((k, v) -> v.setScale(4, RoundingMode.HALF_UP));
                assertEquals(want, bars, id + ": every bar is the Dataset's sum");
                assertTrue(bars.values().stream().anyMatch(b -> b.signum() > 0), id + ": the widget shows something: " + bars);
                barsShown.add(id);
            }
            assertEquals(6, tiles, "three KPI tiles and three Widgets");
            assertEquals(kpiWant.keySet(), kpiGot.keySet());
            for (String k : kpiWant.keySet()) assertEquals(0, kpiWant.get(k).compareTo(kpiGot.get(k)), k + ": " + kpiGot);
            assertEquals(Set.of("ps_open_by_age_bucket", "ps_disputed_by_partner", "ps_accrual_by_agreement"), barsShown);
        } finally {
            api.close();
            spaces.close();
        }
    }

    // ------------------------------------------------------------------------------------------------------------ oracles

    /**
     * The aging, from the corpus: the S4 movement oracle's current statement lines summed per agreement cycle in the agreement's
     * direction, finance's records up to {@code asOf} applied oldest cycle first, the received date of the current statement version,
     * the calendar's payment due date, and the dispute lines of the cycle.
     */
    private static Map<String, String> agingOracle(PartnerSettlementSpace s, LocalDate asOf, int b1, int b2, int b3, int alertDays) throws Exception {
        PartnerSettlementPositionGoldenTest.Oracle mo = new PartnerSettlementPositionGoldenTest.Oracle(corpus);
        PartnerSettlementPackGoldenTest.Oracle so = new PartnerSettlementPackGoldenTest.Oracle(corpus);
        Map<String, LocalDate> received = new TreeMap<>();
        for (PartnerSettlementCorpus.Line l : so.current)
            received.merge(l.agreement() + "|" + l.pStart() + "|" + l.pEnd(), l.receivedAt().toLocalDate(), (a, b) -> a.isAfter(b) ? a : b);
        record Cyc(String key, String agr, String partner, String dir, String group, String cur, LocalDate pStart, LocalDate pEnd) {}
        Map<Cyc, BigDecimal> invoiced = new TreeMap<>(Comparator.comparing(Cyc::agr).thenComparing(Cyc::dir).thenComparing(Cyc::cur)
                .thenComparing(Cyc::pEnd).thenComparing(Cyc::pStart));
        Map<String, BigDecimal> pool = new TreeMap<>();
        for (PartnerSettlementPositionGoldenTest.Oracle.Mv m : mo.movements.values()) {
            if (!Set.of("ok", "fx_missing").contains(m.status())) continue;
            BigDecimal signed = m.signed() == null ? BigDecimal.ZERO : m.signed();
            BigDecimal dir = "we_pay".equals(m.direction()) ? BigDecimal.ONE : BigDecimal.ONE.negate();
            if ("statement".equals(m.source())) {
                String k = m.agreement() + "|" + m.pStart() + "|" + m.pEnd();
                if (received.get(k).isAfter(asOf)) continue;
                invoiced.merge(new Cyc(k, m.agreement(), m.partner(), m.direction(), m.group(), m.cur(), m.pStart(), m.pEnd()), signed.multiply(dir), BigDecimal::add);
            } else if (!m.date().isAfter(asOf)) {
                pool.merge(m.agreement() + "|" + m.direction() + "|" + m.cur(), signed.multiply(dir).negate(), BigDecimal::add);
            }
        }
        Map<String, LocalDate> due = new HashMap<>();
        for (Map<String, Object> c : s.read("settlement_calendar", null, "agreement_id, period_start"))
            due.put(c.get("agreement_id") + "|" + c.get("period_start") + "|" + c.get("period_end"), (LocalDate) c.get("payment_due"));
        Map<String, BigDecimal> disputed = new HashMap<>();
        Map<String, Long> lines = new HashMap<>();
        for (Map<String, Object> d : s.read("settlement_dispute", null, "item_key")) {
            String k = d.get("agreement_id") + "|" + d.get("period_start") + "|" + d.get("period_end");
            disputed.merge(k, (BigDecimal) d.get("disputed_amount"), BigDecimal::add);
            lines.merge(k, 1L, Long::sum);
        }
        Map<String, BigDecimal> appliedBefore = new HashMap<>();
        Map<String, String> out = new TreeMap<>();
        for (Map.Entry<Cyc, BigDecimal> e : invoiced.entrySet()) {
            Cyc c = e.getKey();
            BigDecimal inv = e.getValue().setScale(4, RoundingMode.HALF_UP);
            String series = c.agr() + "|" + c.dir() + "|" + c.cur();
            BigDecimal before = appliedBefore.getOrDefault(series, BigDecimal.ZERO);
            BigDecimal left = pool.getOrDefault(series, BigDecimal.ZERO).subtract(before).max(BigDecimal.ZERO);
            BigDecimal applied = inv.max(BigDecimal.ZERO).min(left);
            appliedBefore.put(series, before.add(inv.max(BigDecimal.ZERO)));
            BigDecimal open = inv.subtract(applied);
            LocalDate stmt = received.get(c.key());
            long age = ChronoUnit.DAYS.between(stmt, asOf);
            String bucket = age <= b1 ? "0-" + b1 : age <= b2 ? (b1 + 1) + "-" + b2 : age <= b3 ? (b2 + 1) + "-" + b3 : b3 + "+";
            LocalDate payDue = due.get(c.key());
            BigDecimal disp = disputed.getOrDefault(c.key(), BigDecimal.ZERO);
            boolean usd = "USD".equals(c.cur());
            BigDecimal late = age > b2 ? open : BigDecimal.ZERO;
            int alert = open.signum() > 0 && ChronoUnit.DAYS.between(asOf, payDue) <= alertDays ? 1 : 0;
            out.put(c.key(), String.join("|", c.agr(), c.partner(), c.dir(), fmt(c.group()), c.cur(), fmt(stmt), fmt(payDue), fmt(age), bucket,
                    fmt(inv), fmt(applied), fmt(open), fmt(disp), fmt(lines.getOrDefault(c.key(), 0L)), fmt(alert), fmt(late),
                    fmt(usd ? disp : BigDecimal.ZERO), fmt(usd ? late : BigDecimal.ZERO)));
        }
        return out;
    }

    /**
     * The accruals, from the corpus: the latest re-rating run of every closed cycle priced into the settlement currency (whether or not
     * its statement is due), the current statement's invoiced total from the S4 movement oracle, and the dispute lines of the cycle.
     */
    private static Map<String, String> accrualOracle(PartnerSettlementSpace s, LocalDate asOf, BigDecimal pct) throws Exception {
        PartnerSettlementPackGoldenTest.Oracle so = new PartnerSettlementPackGoldenTest.Oracle(corpus);
        PartnerSettlementPositionGoldenTest.Oracle mo = new PartnerSettlementPositionGoldenTest.Oracle(corpus);
        Map<String, String[]> pick = new HashMap<>();
        for (PartnerSettlementCorpus.Rerated r : corpus.rerated) {
            String period = so.period(r.agreement(), r.day());
            if (period == null) continue;
            String k = r.agreement() + "|" + period, at = r.ratedAt().toString();
            String[] best = pick.get(k);
            if (best == null || at.compareTo(best[0]) > 0 || (at.equals(best[0]) && r.runId().compareTo(best[1]) > 0)) pick.put(k, new String[]{at, r.runId()});
        }
        Map<String, BigDecimal> perKey = new TreeMap<>();
        Map<String, PartnerSettlementCorpus.Rerated> any = new HashMap<>();
        for (PartnerSettlementCorpus.Rerated r : corpus.rerated) {
            String period = so.period(r.agreement(), r.day());
            if (period == null || !pick.get(r.agreement() + "|" + period)[1].equals(r.runId()) || r.amount() == null || r.reason() != null) continue;
            String key = r.agreement() + "|" + r.direction() + "|" + r.service() + "|" + r.dest() + "|" + r.day();
            perKey.merge(key, r.amount(), BigDecimal::add);
            any.put(key, r);
        }
        record Acc(String agr, String partner, String dir, String cur, String period) {}
        Map<String, Acc> cyc = new TreeMap<>();
        Map<String, BigDecimal> expected = new TreeMap<>();
        for (Map.Entry<String, BigDecimal> e : perKey.entrySet()) {
            PartnerSettlementCorpus.Rerated r = any.get(e.getKey());
            PartnerSettlementCorpus.Agreement a = so.terms(r.agreement(), r.day());
            String period = so.period(r.agreement(), r.day());
            String k = r.agreement() + "|" + period;
            cyc.put(k, new Acc(r.agreement(), a.partner(), r.direction(), a.currency(), period));
            BigDecimal conv = so.convert(e.getValue(), r.currency(), a, r.day(), period);
            expected.merge(k, conv == null ? BigDecimal.ZERO : conv, BigDecimal::add);
        }
        Map<String, BigDecimal> invoiced = new TreeMap<>();
        for (PartnerSettlementPositionGoldenTest.Oracle.Mv m : mo.movements.values()) {
            if (!"statement".equals(m.source()) || !Set.of("ok", "fx_missing").contains(m.status())) continue;
            BigDecimal dir = "we_pay".equals(m.direction()) ? BigDecimal.ONE : BigDecimal.ONE.negate();
            invoiced.merge(m.agreement() + "|" + m.pStart() + "|" + m.pEnd(), m.signed() == null ? BigDecimal.ZERO : m.signed().multiply(dir), BigDecimal::add);
        }
        Map<String, BigDecimal> disputed = new HashMap<>();
        for (Map<String, Object> d : s.read("settlement_dispute", null, "item_key"))
            disputed.merge(d.get("agreement_id") + "|" + d.get("period_start") + "|" + d.get("period_end"), (BigDecimal) d.get("disputed_amount"), BigDecimal::add);
        Map<String, String> out = new TreeMap<>();
        for (Map.Entry<String, Acc> e : cyc.entrySet()) {
            Acc a = e.getValue();
            LocalDate stmtDue = LocalDate.parse(so.calendar.get(e.getKey()).split("\\|")[0]);
            BigDecimal inv = invoiced.get(e.getKey());
            String status = inv != null ? "billed" : asOf.isAfter(stmtDue) ? "statement_late" : "unbilled";
            BigDecimal exp = expected.get(e.getKey()).setScale(4, RoundingMode.HALF_UP);
            BigDecimal accrued = inv == null ? exp : BigDecimal.ZERO;
            BigDecimal disp = disputed.getOrDefault(e.getKey(), BigDecimal.ZERO);
            BigDecimal prov = disp.multiply(pct).divide(BigDecimal.valueOf(100), 4, RoundingMode.HALF_UP);
            out.put(e.getKey(), String.join("|", a.agr(), a.partner(), a.dir(), a.cur(), fmt(stmtDue), status, fmt(exp), fmt(inv), fmt(accrued),
                    fmt(disp), fmt(prov), fmt(accrued.add(prov)), fmt("USD".equals(a.cur()) ? accrued : BigDecimal.ZERO)));
        }
        return out;
    }

    // ------------------------------------------------------------------------------------------------------------ helpers

    private static String fmt(Object o) {
        if (o == null) return "null";
        if (o instanceof BigDecimal b) return b.setScale(4, RoundingMode.HALF_UP).toPlainString();
        return o.toString();
    }

    private static Map<String, String> rows(PartnerSettlementSpace s, String dataset, String key, List<String> cols) throws Exception {
        Map<String, String> out = new TreeMap<>();
        for (Map<String, Object> r : s.read(dataset, null, key)) {
            List<String> v = new ArrayList<>();
            for (String c : cols) v.add(fmt(r.get(c)));
            assertNull(out.put((String) r.get(key), String.join("|", v)), "one row per " + key + ": " + r.get(key));
        }
        return out;
    }

    private static void assertSame(String what, Map<String, String> want, Map<String, String> got) {
        assertEquals(want.keySet(), got.keySet(), what + ": the keys");
        for (String k : want.keySet()) assertEquals(want.get(k), got.get(k), what + " " + k);
    }

    private static BigDecimal sum(PartnerSettlementSpace s, String dataset, String col) throws Exception {
        BigDecimal t = BigDecimal.ZERO;
        for (Map<String, Object> r : s.read(dataset, null, col)) t = t.add((BigDecimal) r.get(col));
        return t.setScale(4, RoundingMode.HALF_UP);
    }

    /** The keys the shipped Alert Rule {@code name} raises over the Space's data, by the production grouped Measure probe. */
    private static Set<String> raised(PartnerSettlementSpace s, String name, String by) {
        AlertRule rule = null;
        for (ComponentRegistry.Component c : new ComponentStore(s.config().resolve("registry")).list("alert-rule"))
            if (name.equals(c.content().get("name"))) rule = AlertRule.fromMap(c.content());
        assertNotNull(rule, name);
        assertEquals(List.of(by), rule.by(), name);
        DatasetMeasureProbe probe = new DatasetMeasureProbe(s::config, s::data);
        DatasetMeasureProbe.Breaches b = probe.breaches(rule.dataset(), rule.measure(), rule.by(), rule.comparator(), rule.threshold(), rule.stormCap())
                .orElseThrow(() -> new AssertionError(name + ": the probe failed"));
        Set<String> out = new TreeSet<>();
        for (DatasetMeasureProbe.Breach br : b.keys()) out.add(String.valueOf(br.key().get(by)));
        return out;
    }

    private static HttpResponse<String> call(String url, String postBody) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url)).header("Authorization", "Bearer admin");
        if (postBody != null) b.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(postBody));
        return HttpClient.newHttpClient().send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static JsonNode body(HttpResponse<String> r) throws Exception {
        assertEquals(200, r.statusCode(), r.body());
        JsonNode n = new ObjectMapper().readTree(r.body());
        return n.has("data") ? n.get("data") : n;
    }
}
