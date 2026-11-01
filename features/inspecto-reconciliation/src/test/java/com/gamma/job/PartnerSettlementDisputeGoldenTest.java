package com.gamma.job;

import com.gamma.alert.AlertRule;
import com.gamma.pipeline.ComponentRegistry;
import com.gamma.pipeline.ComponentStore;
import com.gamma.query.DatasetMeasureProbe;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code PACK-PARTNER-SETTLEMENT-1} slice S5, the data half: the shipped {@code ps_dispute} Job turns every planted
 * {@code amount_mismatch} and {@code fx_difference} into one dispute line keyed to its partner and cycle, the position carries the
 * disputed amount beside the net, and the two shipped Alert Rules, run by the production grouped Measure probe, raise one key per
 * line and one per partner cycle whose dispute window is about to close. The Case half (grouping, the authored workflow, Impact) is
 * {@code PartnerSettlementDisputeCaseTest} in {@code inspecto-case-management}.
 */
class PartnerSettlementDisputeGoldenTest {

    private static final Map<String, String> AS_OF = Map.of("as_of", PartnerSettlementCorpus.AS_OF.toString());
    /** Hand-written, not derived: the six partner cycles the ten planted dispute lines fall in. */
    private static final Map<String, Integer> GROUPS = Map.of(
            "P1|2026-05-01|2026-05-31", 2, "P1|2026-06-01|2026-06-30", 3, "P1|2026-07-01|2026-07-31", 1,
            "P3|2026-06-16|2026-06-30", 2, "P3|2026-07-01|2026-07-15", 1, "P2|2026-06-01|2026-06-30", 1);

    private static Path tmp;
    private static PartnerSettlementCorpus corpus;
    private static PartnerSettlementSpace base;

    @BeforeAll
    static void buildTheBaseSpace() throws Exception {
        PartnerSettlementSpace.arm();
        tmp = Files.createTempDirectory("ps-s5-golden");
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

    @Test
    void everyPlantedMismatchAndFxDifferenceIsExactlyOneDisputeLineWithTheOracleAmount() throws Exception {
        Set<String> planted = new TreeSet<>(corpus.plantedFindings.get("amount_mismatch"));
        planted.addAll(corpus.plantedFindings.get("fx_difference"));
        assertEquals(10, planted.size(), "9 amount mismatches and 1 FX difference");
        PartnerSettlementPackGoldenTest.Oracle o = new PartnerSettlementPackGoldenTest.Oracle(corpus);

        Map<String, Integer> groups = new TreeMap<>();
        Set<String> lines = new TreeSet<>();
        for (Map<String, Object> r : base.read("settlement_dispute", null, "item_key")) {
            String key = (String) r.get("item_key");
            assertTrue(lines.add(key), "one line per finding: " + key);
            assertTrue(Set.of("amount_mismatch", "fx_difference").contains((String) r.get("reason")), r.toString());
            // the oracle's amounts, not the Job's: |billed - expected| in the settlement currency
            BigDecimal expected = o.expected.containsKey(key) ? o.expected.get(key).amount() : BigDecimal.ZERO;
            BigDecimal billed = o.billed.containsKey(key) ? o.billed.get(key).billed() : BigDecimal.ZERO;
            assertEquals(0, billed.subtract(expected).abs().compareTo((BigDecimal) r.get("disputed_amount")), key + ": " + r);
            assertEquals(0, ((BigDecimal) r.get("variance")).abs().compareTo((BigDecimal) r.get("disputed_amount")), key);
            String partner = corpus.agreements.stream().filter(a -> a.id().equals(r.get("agreement_id"))).findFirst().orElseThrow().partner();
            assertEquals(partner + "|" + r.get("period_start") + "|" + r.get("period_end"), r.get("dispute_key"));
            assertNotNull(r.get("currency"), key);
            assertNotNull(r.get("dispute_window_end"), key + ": every dispute line has a contractual window");
            groups.merge((String) r.get("dispute_key"), 1, Integer::sum);
        }
        assertEquals(planted, lines, "exactly the planted variance lines: no cut-off pair, no data-quality finding, no look-alike");
        assertEquals(new TreeMap<>(GROUPS), groups, "one dispute per partner and cycle");
    }

    @Test
    void theWindowEndIsTheCalendarsAndTheAlertFlagIsTheInclusiveLeadTime() throws Exception {
        Map<String, LocalDate> windowEnd = new TreeMap<>();
        for (Map<String, Object> c : base.read("settlement_calendar", null, "agreement_id, period_start"))
            windowEnd.put(c.get("agreement_id") + "|" + c.get("period_start") + "|" + c.get("period_end"), (LocalDate) c.get("dispute_window_end"));
        for (Map<String, Object> r : base.read("settlement_dispute", null, "item_key")) {
            LocalDate end = windowEnd.get(r.get("agreement_id") + "|" + r.get("period_start") + "|" + r.get("period_end"));
            assertEquals(end, r.get("dispute_window_end"), r.toString());
            long days = ChronoUnit.DAYS.between(PartnerSettlementCorpus.AS_OF, end);
            assertEquals(days, r.get("days_to_window_end"));
            assertEquals(days >= 0 && days <= 5 ? 1L : 0L, r.get("window_alert"), r.toString());
        }

        // the boundary, on D1's June dispute: 5 days before its window end alerts, 6 does not, the last day does, the day after does not
        LocalDate end = (LocalDate) base.read("settlement_dispute", "dispute_key = 'P2|2026-06-01|2026-06-30'", "item_key").get(0).get("dispute_window_end");
        Map<LocalDate, Long> want = Map.of(end.minusDays(5), 1L, end.minusDays(6), 0L, end, 1L, end.plusDays(1), 0L);
        int i = 0;
        for (Map.Entry<LocalDate, Long> e : want.entrySet()) {
            PartnerSettlementSpace s = base.copyTo(tmp.resolve("window-" + i++));
            s.run(List.of("ps_dispute"), Map.of("as_of", e.getKey().toString()));
            assertEquals(e.getValue(), s.read("settlement_dispute", "dispute_key = 'P2|2026-06-01|2026-06-30'", "item_key").get(0).get("window_alert"),
                    "as of " + e.getKey() + ", window end " + end);
            Set<String> alerting = raised(s, "ps_dispute_window", "dispute_key");
            assertEquals(e.getValue() == 1L, alerting.contains("P2|2026-06-01|2026-06-30"), "the shipped Alert Rule as of " + e.getKey() + ": " + alerting);
        }
    }

    /**
     * S7: every dispute line carries the evidence the {@code settlement-dispute-pack} templates file, and every {@code rows.<column>}
     * a shipped template reads is a column the Job writes. The run and rate ids are the corpus's own latest run of the line's day
     * (the stale May run must lose), the agreement version is the corpus agreement in force, the statement id the restated one.
     */
    @Test
    void everyDisputeLineCarriesTheEvidenceTheDisputePackFiles() throws Exception {
        List<Map<String, Object>> rows = base.read("settlement_dispute", null, "item_key");
        Set<String> columns = rows.get(0).keySet();
        Path templates = Path.of("../../spaces/_templates/partner-settlement/config/regulatory-report-templates");
        for (String id : List.of("settlement-dispute-pack", "settlement-dispute-pack-csv")) {
            java.util.regex.Matcher m = java.util.regex.Pattern.compile(",rows\\.([a-z_]+),").matcher(Files.readString(templates.resolve(id + ".toon")));
            int read = 0;
            while (m.find()) {
                read++;
                assertTrue(columns.contains(m.group(1)), id + " reads rows." + m.group(1) + ", which ps_dispute does not write: " + columns);
            }
            assertEquals(30, read, id + ": the line fields");
        }
        for (Map<String, Object> r : rows) {
            String key = (String) r.get("item_key");
            String[] k = key.split("\\|");
            LocalDate day = LocalDate.parse(k[4]);
            List<PartnerSettlementCorpus.Rerated> mine = corpus.rerated.stream().filter(x -> x.agreement().equals(k[0])
                    && x.direction().equals(k[1]) && x.service().equals(k[2]) && x.dest().equals(k[3]) && x.day().equals(day)).toList();
            PartnerSettlementCorpus.Rerated latest = mine.stream().max(Comparator.comparing(PartnerSettlementCorpus.Rerated::ratedAt)).orElseThrow();
            assertEquals(latest.runId(), r.get("rating_run_id"), key + ": the latest re-rating run");
            assertEquals(String.join(",", new TreeSet<>(mine.stream().filter(x -> x.runId().equals(latest.runId()))
                    .map(PartnerSettlementCorpus.Rerated::rateId).toList())), r.get("rate_ids"), key);
            PartnerSettlementCorpus.Agreement a = corpus.agreements.stream()
                    .filter(x -> x.id().equals(k[0]) && x.covers(day)).findFirst().orElseThrow();
            assertEquals(a.from(), r.get("agreement_version"), key);
            assertEquals(a.tolType(), r.get("tolerance_type"), key);
            assertEquals(0, a.tolerance().compareTo((BigDecimal) r.get("tolerance")), key);
            assertNotNull(r.get("tolerance_amount"), key);
            assertNotNull(r.get("our_fx_rate"), key);
            assertNotNull(r.get("statement_id"), key + ": every corpus dispute line is on a statement");
        }
        Map<String, Object> fx = rows.stream().filter(r -> "fx_difference".equals(r.get("reason"))).findFirst().orElseThrow();
        assertEquals("EUR", fx.get("invoiced_currency"), fx.toString());
        assertEquals(0, new BigDecimal("0.93").compareTo((BigDecimal) fx.get("partner_fx_rate")), "the partner's FX row beside ours: " + fx);
        assertEquals(0, new BigDecimal("0.91").compareTo((BigDecimal) fx.get("our_fx_rate")), fx.toString());
        Map<String, Object> july = rows.stream().filter(r -> String.valueOf(r.get("item_key")).startsWith("AG-VOICE-A1|")
                && String.valueOf(r.get("item_key")).endsWith("2026-07-15")).findFirst().orElseThrow();
        assertEquals(2L, ((Number) july.get("statement_version")).longValue(), "the restated July statement: " + july);
    }

    @Test
    void theShippedAlertRulesRaiseOneKeyPerLineAndNothingForAQuietWindow() throws Exception {
        Set<String> want = new TreeSet<>();
        for (Map<String, Object> r : base.read("settlement_dispute", null, "item_key")) want.add(r.get("dispute_key") + "#" + r.get("item_key"));
        assertEquals(10, want.size());
        assertEquals(want, raised(base, "ps_dispute_line", "dispute_key", "item_key"), "one Incident key per dispute line, carrying its dispute");
        // as of the corpus day every window is long closed: the reminder is silent
        assertEquals(Set.of(), raised(base, "ps_dispute_window", "dispute_key"));
    }

    @Test
    void thePositionCarriesTheDisputedAmountBesideTheNetNeverInIt() throws Exception {
        Map<String, BigDecimal> amount = new TreeMap<>();
        Map<String, Long> count = new TreeMap<>();
        for (Map<String, Object> r : base.read("settlement_dispute", null, "item_key")) {
            String k = r.get("netting_group") + "|" + r.get("cycle_month") + "|" + r.get("currency");
            amount.merge(k, (BigDecimal) r.get("disputed_amount"), BigDecimal::add);
            count.merge(k, 1L, Long::sum);
        }
        BigDecimal total = BigDecimal.ZERO;
        for (Map<String, Object> p : base.read("settlement_position", null, "netting_group, cycle_month, currency")) {
            String k = p.get("netting_group") + "|" + p.get("cycle_month") + "|" + p.get("currency");
            assertEquals(0, amount.getOrDefault(k, BigDecimal.ZERO).compareTo((BigDecimal) p.get("disputed_amount")), k);
            assertEquals(count.getOrDefault(k, 0L), p.get("disputed_lines"), k);
            total = total.add((BigDecimal) p.get("disputed_amount"));
        }
        assertEquals(0, amount.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add).compareTo(total), "no dispute line falls outside a position");
        assertTrue(base.read("settlement_position", "net <> payable - receivable", "netting_group").isEmpty(), "the dispute is never netted away");

        // hand-worked: NG-XB June holds exactly D1's one dispute line, 06-04 (planted one step beyond its absolute tolerance)
        Map<String, Object> xb = base.read("settlement_position", "netting_group = 'NG-XB' AND cycle_month = DATE '2026-06-01'", "currency").get(0);
        assertEquals(1L, xb.get("disputed_lines"));
        assertEquals(base.read("settlement_dispute", "item_key = 'AG-VOICE-D1|we_pay|VOICE|NATIONAL|2026-06-04'", "item_key").get(0).get("disputed_amount"),
                xb.get("disputed_amount"));
    }

    @Test
    void droppingTheFxDifferenceFromTheDisputeLosesTheP3JuneLine() throws Exception {
        PartnerSettlementSpace m = base.copyTo(tmp.resolve("no-fx"));
        Path job = m.config().resolve("jobs").resolve("ps_dispute_job.toon");
        String toon = Files.readString(job);
        assertTrue(toon.contains("reason IN ('amount_mismatch', 'fx_difference')"));
        Files.writeString(job, toon.replace("reason IN ('amount_mismatch', 'fx_difference')", "reason IN ('amount_mismatch')"));
        m.run(List.of("ps_dispute"), AS_OF);
        assertEquals(1, m.read("settlement_dispute", "dispute_key = 'P3|2026-06-16|2026-06-30'", "item_key").size(), "the FX line is red: only 06-22 is left");
    }

    /** The keys the shipped Alert Rule {@code name} raises over the Space's data, by the production grouped Measure probe. */
    private static Set<String> raised(PartnerSettlementSpace s, String name, String... by) {
        AlertRule rule = null;
        for (ComponentRegistry.Component c : new ComponentStore(s.config().resolve("registry")).list("alert-rule"))
            if (name.equals(c.content().get("name"))) rule = AlertRule.fromMap(c.content());
        assertNotNull(rule, name);
        assertEquals(List.of(by), rule.by(), name);
        DatasetMeasureProbe probe = new DatasetMeasureProbe(s::config, s::data);
        DatasetMeasureProbe.Breaches b = probe.breaches(rule.dataset(), rule.measure(), rule.by(), rule.comparator(), rule.threshold(), rule.stormCap())
                .orElseThrow(() -> new AssertionError(name + ": the probe failed"));
        Set<String> out = new TreeSet<>();
        for (DatasetMeasureProbe.Breach br : b.keys()) {
            StringBuilder k = new StringBuilder();
            for (String col : by) k.append(k.length() == 0 ? "" : "#").append(br.key().get(col));
            out.add(k.toString());
        }
        return out;
    }
}
