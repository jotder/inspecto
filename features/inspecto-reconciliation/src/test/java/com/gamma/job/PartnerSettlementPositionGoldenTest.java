package com.gamma.job;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code PACK-PARTNER-SETTLEMENT-1} slice S4 golden test: netting, positions and the roll-forward. The shipped template is created
 * through {@code POST /spaces}, the synthetic {@link PartnerSettlementCorpus#generateWithPositions()} corpus (the S3 corpus plus an
 * agreement invoiced in EUR and settled in USD, and finance payments and notes) is ingested through the template's own Pipelines, and
 * the nine chained Jobs run as authored. What the three S4 Jobs write is held to
 * <ul>
 *   <li>an INDEPENDENT plain-Java oracle, column for column, for every movement, position and roll-forward row;</li>
 *   <li>one SQL-level assertion per identity: payable minus receivable is the net, opening plus invoiced minus paid plus adjustments is
 *       the closing, an opening is the previous closing, and the closing is the sum of every movement;</li>
 *   <li>hand-worked amounts that do not come from the oracle;</li>
 *   <li>mutation checks: each one-clause change to a shipped statement must turn a named row red.</li>
 * </ul>
 */
class PartnerSettlementPositionGoldenTest {

    private static final Map<String, String> AS_OF = Map.of("as_of", PartnerSettlementCorpus.AS_OF.toString());

    private static final List<String> MOVEMENT_COLS = List.of("movement_id", "source", "document_id", "document_version", "agreement_id", "partner_id",
            "netting_group", "direction", "cycle_month", "movement_date", "period_start", "period_end", "movement_kind", "effect", "amount_orig",
            "currency_orig", "amount", "currency", "fx_rate", "fx_rate_date", "fx_rate_type", "fx_status", "signed_amount", "status");
    private static final List<String> POSITION_COLS = List.of("netting_group", "cycle_month", "currency", "payable", "receivable", "net", "net_direction",
            "orig_net", "statements", "line_count", "lines_unconverted");
    private static final List<String> ROLLFORWARD_COLS = List.of("netting_group", "cycle_month", "currency", "opening", "invoiced", "paid", "adjustments",
            "closing", "movements", "lines_unconverted");

    private static Path tmp;
    private static PartnerSettlementCorpus corpus;
    private static PartnerSettlementSpace base;
    private static int copies;

    @BeforeAll
    static void buildTheBaseSpace() throws Exception {
        PartnerSettlementSpace.arm();
        tmp = Files.createTempDirectory("ps-s4-golden");
        corpus = PartnerSettlementCorpus.generateWithPositions();
        base = build(tmp.resolve("base"), corpus.files());
    }

    @AfterAll
    static void cleanUp() throws IOException {
        PartnerSettlementSpace.disarm();
        if (tmp != null) try (Stream<Path> w = Files.walk(tmp)) { w.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete()); }
    }

    private static PartnerSettlementSpace build(Path root, Map<String, String> files) throws Exception {
        PartnerSettlementSpace s = PartnerSettlementSpace.create(root);
        s.ingest(files);
        s.runChain(AS_OF);
        return s;
    }

    // ------------------------------------------------------------------------------------------- the three Datasets

    @Test
    void everyMovementEqualsAnIndependentOracleColumnForColumn() throws Exception {
        Oracle o = new Oracle(corpus);
        assertSame("movements", o.movementRows(), rows(base, "settlement_movement", MOVEMENT_COLS, 1));
        Map<String, Long> status = new TreeMap<>();
        for (Map<String, Object> r : base.read("settlement_movement", null, "movement_id")) status.merge(r.get("status") + "/" + r.get("fx_status"), 1L, Long::sum);
        assertTrue(o.movements.size() > 400, "the corpus is not trivially small: " + o.movements.size());
        // F1's EUR lines and its EUR payment are converted; every other line is already in the settlement currency; two records settle nothing known
        assertEquals(Set.of("ok/ok", "ok/same_currency", "unknown_agreement/unknown_agreement"), status.keySet());
        assertEquals(2L, status.get("unknown_agreement/unknown_agreement"), "the unknown agreement and the agreement under another partner");
        assertEquals(64L, status.get("ok/ok"), "F1: 61 EUR usage lines, a fee, a credit and the EUR payment");
    }

    @Test
    void everyPositionEqualsTheOracleAndTheNettingIdentityHolds() throws Exception {
        Oracle o = new Oracle(corpus);
        assertSame("positions", o.positionRows(), rows(base, "settlement_position", POSITION_COLS, 3));
        assertTrue(base.read("settlement_position", "net <> payable - receivable", "netting_group").isEmpty(), "net = payable - receivable on every row");
        assertTrue(base.read("settlement_position", "net_direction <> CASE WHEN net > 0 THEN 'we_pay' WHEN net < 0 THEN 'they_pay' ELSE 'even' END", "netting_group").isEmpty());
        assertTrue(base.read("settlement_position", "payable < 0 OR receivable < 0", "netting_group").isEmpty(), "a credit larger than its side is not in this corpus");

        // hand-worked, not from the oracle: NG-XB in June nets D1 and F1 (we pay) against E1 (they pay), in USD; F1 is billed in EUR
        Map<String, Object> xb = base.read("settlement_position", "netting_group = 'NG-XB' AND cycle_month = DATE '2026-06-01'", "currency").get(0);
        assertEquals("USD", xb.get("currency"));
        assertTrue(((BigDecimal) xb.get("payable")).signum() > 0 && ((BigDecimal) xb.get("receivable")).signum() > 0, "both directions in one group");
        assertEquals(((BigDecimal) xb.get("payable")).subtract((BigDecimal) xb.get("receivable")), xb.get("net"));
        assertTrue(((String) xb.get("orig_net")).startsWith("EUR:") && ((String) xb.get("orig_net")).contains(",USD:"), "both currencies shown: " + xb.get("orig_net"));
        // F1 June: 30 usage lines and a -4.0000 EUR credit, converted at the cycle-end rate 1.1000; the credit is -4.4000 USD
        BigDecimal f1June = BigDecimal.ZERO;
        for (int d = 1; d <= 30; d++) f1June = f1June.add(new BigDecimal("" + (40 + 2 * (d % 5))).multiply(new BigDecimal("1.1")));
        BigDecimal expectedF1 = f1June.subtract(new BigDecimal("4.4")).setScale(4);
        BigDecimal viaMovements = BigDecimal.ZERO.setScale(4);
        for (Map<String, Object> r : base.read("settlement_movement", "agreement_id = 'AG-VOICE-F1' AND cycle_month = DATE '2026-06-01' AND source = 'statement'", "movement_id"))
            viaMovements = viaMovements.add((BigDecimal) r.get("amount"));
        assertEquals(expectedF1, viaMovements);
        // C1 is paid to us in EUR: a receivable only, in its own currency group
        Map<String, Object> c1 = base.read("settlement_position", "netting_group = 'NG-XA' AND currency = 'EUR' AND cycle_month = DATE '2026-06-01'", "currency").get(0);
        assertEquals("they_pay", c1.get("net_direction"));
        assertEquals(BigDecimal.ZERO.setScale(4), c1.get("payable"));
        // a cycle with no statement has no position, but August (no statement anywhere) must not either
        assertTrue(base.read("settlement_position", "netting_group = 'NG-XB' AND cycle_month >= DATE '2026-07-01'", "currency").isEmpty());
        assertTrue(base.read("settlement_position", "cycle_month >= DATE '2026-08-01'", "currency").isEmpty());

        // S3 stays tied to S4: the usage the position counts is exactly what the compare called the billed amount
        Map<String, BigDecimal> billed = new TreeMap<>(), usage = new TreeMap<>();
        for (Map<String, Object> r : base.read("settlement_billed", null, "agreement_id, usage_date, destination_class"))
            billed.merge(r.get("agreement_id") + "|" + ((LocalDate) r.get("period_end")).withDayOfMonth(1), (BigDecimal) r.get("amount_billed"), BigDecimal::add);
        for (Map<String, Object> r : base.read("settlement_movement", "movement_kind = 'usage' AND status = 'ok'", "movement_id"))
            usage.merge(r.get("agreement_id") + "|" + r.get("cycle_month"), (BigDecimal) r.get("amount"), BigDecimal::add);
        assertEquals(billed, usage, "settlement_billed.amount_billed and the usage movements are the same money");
    }

    @Test
    void everyRollForwardRowEqualsTheOracleClosesAndCarries() throws Exception {
        Oracle o = new Oracle(corpus);
        assertSame("roll-forward", o.rollForwardRows(), rows(base, "settlement_rollforward", ROLLFORWARD_COLS, 3));
        assertTrue(base.read("settlement_rollforward", "opening + invoiced - paid + adjustments <> closing", "netting_group").isEmpty(),
                "opening + invoiced - paid + adjustments = closing on every row");

        // an opening is the previous closing, the first opening is zero, and no month is skipped between the first and the as-of month
        Map<String, List<Map<String, Object>>> series = new TreeMap<>();
        for (Map<String, Object> r : base.read("settlement_rollforward", null, "netting_group, currency, cycle_month"))
            series.computeIfAbsent(r.get("netting_group") + "|" + r.get("currency"), k -> new ArrayList<>()).add(r);
        assertEquals(Set.of("NG-XA|EUR", "NG-XA|USD", "NG-XB|USD"), series.keySet(), "netting happens inside one currency");
        for (Map.Entry<String, List<Map<String, Object>>> e : series.entrySet()) {
            List<Map<String, Object>> rs = e.getValue();
            assertEquals(BigDecimal.ZERO.setScale(4), rs.get(0).get("opening"), e.getKey() + " starts from nothing");
            for (int i = 1; i < rs.size(); i++) {
                assertEquals(rs.get(i - 1).get("closing"), rs.get(i).get("opening"), e.getKey() + " opening " + rs.get(i).get("cycle_month"));
                assertEquals(((LocalDate) rs.get(i - 1).get("cycle_month")).plusMonths(1), rs.get(i).get("cycle_month"), e.getKey() + " has a gap");
            }
            assertEquals(LocalDate.of(2026, 8, 1), rs.get(rs.size() - 1).get("cycle_month"), e.getKey() + " runs to the as-of month");
        }
        // the closing is every movement summed, per group and currency: nothing leaks between the two Datasets
        Map<String, BigDecimal> sum = new TreeMap<>();
        for (Map<String, Object> r : base.read("settlement_movement", "status = 'ok'", "movement_id"))
            sum.merge(r.get("netting_group") + "|" + r.get("currency"), (BigDecimal) r.get("signed_amount"), BigDecimal::add);
        for (Map.Entry<String, List<Map<String, Object>>> e : series.entrySet())
            assertEquals(sum.get(e.getKey()), e.getValue().get(e.getValue().size() - 1).get("closing"), e.getKey() + " closes to the sum of its movements");

        // hand-worked amounts: the payment sent twice counts once, the corrected payment is the later record, a note moves the balance
        assertEquals(new BigDecimal("10000.0000"), roll("NG-XA", "USD", "2026-06-01").get("paid"), "PAY-A1-01 is in two files and is paid once");
        assertEquals(new BigDecimal("-10.0000"), roll("NG-XA", "USD", "2026-06-01").get("adjustments"), "the June statement credit line");
        assertEquals(new BigDecimal("2500.0000"), roll("NG-XA", "USD", "2026-07-01").get("paid"));
        assertEquals(new BigDecimal("-150.0000"), roll("NG-XA", "USD", "2026-07-01").get("adjustments"), "a credit note reduces what we owe");
        assertEquals(new BigDecimal("430.0000"), roll("NG-XB", "USD", "2026-06-01").get("paid"), "we paid F1 EUR 800 (880.0000 USD at 1.1000) and E1 paid us 450, the corrected record");
        assertEquals(new BigDecimal("-1000.0000"), roll("NG-XA", "EUR", "2026-07-01").get("paid"), "C1 paid us EUR 1000");
        Map<String, Object> aug = roll("NG-XA", "USD", "2026-08-01");
        assertEquals(new BigDecimal("25.5000"), aug.get("adjustments"), "a debit note in a month with no statement");
        assertEquals(BigDecimal.ZERO.setScale(4), aug.get("invoiced"));
        assertEquals(1L, aug.get("movements"));
        // a month with no movement carries the balance forward
        Map<String, Object> jul = roll("NG-XB", "USD", "2026-07-01"), jun = roll("NG-XB", "USD", "2026-06-01");
        assertEquals(0L, jul.get("movements"));
        assertEquals(jun.get("closing"), jul.get("closing"));
        assertEquals(jun.get("closing"), roll("NG-XB", "USD", "2026-08-01").get("closing"));
        // the two records that settle nothing known are visible in the movements and in no balance
        Set<String> unknown = new TreeSet<>();
        for (Map<String, Object> r : base.read("settlement_movement", "status = 'unknown_agreement'", "movement_id")) unknown.add((String) r.get("movement_id"));
        assertEquals(Set.of("PAY|PAY-NONE-01", "PAY|PAY-D1-BAD"), unknown);
    }

    private Map<String, Object> roll(String group, String currency, String month) throws Exception {
        List<Map<String, Object>> r = base.read("settlement_rollforward", "netting_group = '" + group + "' AND currency = '" + currency + "' AND cycle_month = DATE '" + month + "'", "currency");
        assertEquals(1, r.size(), group + "|" + currency + "|" + month);
        return r.get(0);
    }

    @Test
    void aRestatedStatementMovesTheClosingBalanceAndTheRollForwardStillCloses() throws Exception {
        // the same corpus without the restated July statement: the superseded v1 (a 999.0000 line where the right amount is 200.0000) is current
        Map<String, String> files = new LinkedHashMap<>(corpus.files());
        assertNotNull(files.remove("settlement_statement/A1-2026-07-v2.csv"));
        PartnerSettlementSpace v1 = build(tmp.resolve("v1only"), files);
        BigDecimal delta = new BigDecimal("999.0000").subtract(PartnerSettlementCorpus.a1Expected("MOBILE", LocalDate.of(2026, 7, 20)));
        assertEquals(new BigDecimal("799.0000"), delta);
        for (String month : List.of("2026-07-01", "2026-08-01")) {
            BigDecimal before = (BigDecimal) rollOf(v1, "NG-XA", "USD", month).get("closing");
            BigDecimal after = (BigDecimal) roll("NG-XA", "USD", month).get("closing");
            assertEquals(delta, before.subtract(after), "the restated statement lowers the " + month + " closing by the wrong line");
        }
        assertEquals(delta, ((BigDecimal) rollOf(v1, "NG-XA", "USD", "2026-07-01").get("invoiced")).subtract((BigDecimal) roll("NG-XA", "USD", "2026-07-01").get("invoiced")));
        // the position moves with it, and May and June do not move
        assertEquals(delta, ((BigDecimal) v1.read("settlement_position", "netting_group = 'NG-XA' AND currency = 'USD' AND cycle_month = DATE '2026-07-01'", "currency").get(0).get("payable"))
                .subtract((BigDecimal) base.read("settlement_position", "netting_group = 'NG-XA' AND currency = 'USD' AND cycle_month = DATE '2026-07-01'", "currency").get(0).get("payable")));
        assertEquals(rollOf(v1, "NG-XA", "USD", "2026-06-01").get("closing"), roll("NG-XA", "USD", "2026-06-01").get("closing"));
        // and it still closes to zero residual, in both
        assertTrue(v1.read("settlement_rollforward", "opening + invoiced - paid + adjustments <> closing", "netting_group").isEmpty());
        assertTrue(base.read("settlement_rollforward", "opening + invoiced - paid + adjustments <> closing", "netting_group").isEmpty());
        assertSame("roll-forward without v2", new Oracle(corpusWithout("settlement_statement/A1-2026-07-v2.csv")).rollForwardRows(), rows(v1, "settlement_rollforward", ROLLFORWARD_COLS, 3));
    }

    private static PartnerSettlementCorpus corpusWithout(String file) {
        PartnerSettlementCorpus c = PartnerSettlementCorpus.generateWithPositions();
        assertNotNull(c.statements.remove(file.substring(file.indexOf('/') + 1)));
        return c;
    }

    private static Map<String, Object> rollOf(PartnerSettlementSpace s, String group, String currency, String month) throws Exception {
        List<Map<String, Object>> r = s.read("settlement_rollforward", "netting_group = '" + group + "' AND currency = '" + currency + "' AND cycle_month = DATE '" + month + "'", "currency");
        assertEquals(1, r.size());
        return r.get(0);
    }

    @Test
    void theCompareHalfIsUnchangedByTheS4Additions() throws Exception {
        // the S4 corpus adds an agreement, FX rows and payments; none of them may raise or hide an S2 or S3 finding
        Map<String, Set<String>> found = new TreeMap<>(), stmt = new TreeMap<>();
        for (Map<String, Object> r : base.read("settlement_findings", null, "reason, item_key")) found.computeIfAbsent((String) r.get("reason"), k -> new TreeSet<>()).add((String) r.get("item_key"));
        for (Map<String, Object> r : base.read("settlement_statement_findings", null, "reason, item_key")) stmt.computeIfAbsent((String) r.get("reason"), k -> new TreeSet<>()).add((String) r.get("item_key"));
        PartnerSettlementCorpus s3 = PartnerSettlementCorpus.generate();
        assertEquals(s3.plantedFindings, found);
        assertEquals(s3.plantedStatementFindings, stmt);
        assertTrue(base.read("settlement_findings", "agreement_id = 'AG-VOICE-F1'", "item_key").isEmpty(), "F1 is clean: the EUR billing converts to exactly our USD");
    }

    @Test
    void aSecondRunOverTheSameWindowDuplicatesNothing() throws Exception {
        PartnerSettlementSpace again = base.copyTo(tmp.resolve("rerun-" + copies++));
        again.run(PartnerSettlementSpace.POSITION_CHAIN, AS_OF);
        assertEquals(rows(base, "settlement_movement", MOVEMENT_COLS, 1), rows(again, "settlement_movement", MOVEMENT_COLS, 1));
        assertEquals(rows(base, "settlement_position", POSITION_COLS, 3), rows(again, "settlement_position", POSITION_COLS, 3));
        assertEquals(rows(base, "settlement_rollforward", ROLLFORWARD_COLS, 3), rows(again, "settlement_rollforward", ROLLFORWARD_COLS, 3));
    }

    @Test
    void aLineWithNoFxRateIsCountedAndNamedNeverSummedOrDropped() throws Exception {
        PartnerSettlementCorpus c = PartnerSettlementCorpus.generateWithPositions();
        c.fx.clear();
        PartnerSettlementSpace s = build(tmp.resolve("nofx"), c.files());
        Oracle o = new Oracle(c);
        assertSame("movements without rates", o.movementRows(), rows(s, "settlement_movement", MOVEMENT_COLS, 1));
        assertSame("positions without rates", o.positionRows(), rows(s, "settlement_position", POSITION_COLS, 3));
        assertSame("roll-forward without rates", o.rollForwardRows(), rows(s, "settlement_rollforward", ROLLFORWARD_COLS, 3));
        // all of F1s 61 usage lines, its fee and credit, and its EUR payment are named, with no amount invented
        List<Map<String, Object>> missing = s.read("settlement_movement", "status = 'fx_missing'", "movement_id");
        assertEquals(64, missing.size());
        for (Map<String, Object> r : missing) assertTrue(r.get("amount") == null && r.get("signed_amount") == null && "missing".equals(r.get("fx_status")), r.toString());
        Map<String, Object> june = s.read("settlement_position", "netting_group = 'NG-XB' AND cycle_month = DATE '2026-06-01'", "currency").get(0);
        assertEquals(31L, june.get("lines_unconverted"), "30 usage lines and the credit");
        assertEquals(1L, ((BigDecimal) june.get("payable")).compareTo(BigDecimal.ZERO), "D1's own lines still count");
        assertEquals("USD:" + ((BigDecimal) june.get("net")).toPlainString(), june.get("orig_net"), "only the converted lines are summed, in their own currency too");
        assertTrue(s.read("settlement_rollforward", "opening + invoiced - paid + adjustments <> closing", "netting_group").isEmpty());
        assertEquals(32L, s.read("settlement_rollforward", "netting_group = 'NG-XB' AND cycle_month = DATE '2026-06-01'", "currency").get(0).get("lines_unconverted"),
                "the roll-forward also counts the unconverted EUR payment of the month");
    }

    @Test
    void anAgreementWithNoNettingGroupIsStillCountedNotZeroed() throws Exception {
        Map<String, String> files = new LinkedHashMap<>(corpus.files());
        String ag = files.get("settlement_agreement/settlement_agreement.csv");
        String blank = ag.replace(",NG-XB,", ",,");
        assertNotEquals(ag, blank);
        files.put("settlement_agreement/settlement_agreement.csv", blank);
        PartnerSettlementCorpus c = PartnerSettlementCorpus.generateWithPositions();
        c.agreements.replaceAll(a -> a.group().equals("NG-XB") ? new PartnerSettlementCorpus.Agreement(a.id(), a.partner(), a.kind(), a.direction(), a.currency(), a.rounding(),
                a.tolType(), a.tolerance(), a.cycle(), a.stmtDue(), a.dispute(), a.payment(), "", a.fxType(), a.fxRule(), a.from(), a.to()) : a);
        assertEquals(blank, c.files().get("settlement_agreement/settlement_agreement.csv"));
        PartnerSettlementSpace s = build(tmp.resolve("nogroup"), c.files());
        Oracle o = new Oracle(c);
        assertSame("positions, blank group", o.positionRows(), rows(s, "settlement_position", POSITION_COLS, 3));
        assertSame("roll-forward, blank group", o.rollForwardRows(), rows(s, "settlement_rollforward", ROLLFORWARD_COLS, 3));
        assertTrue(s.read("settlement_rollforward", "netting_group IS NULL AND movements > 0 AND invoiced <> 0", "currency").size() > 0, "the blank group keeps its money");
    }

    // ------------------------------------------------------------------------------------------- mutation checks

    /** A copy of the base Space with one clause of one shipped Job changed, the three S4 Jobs re-run. */
    private PartnerSettlementSpace mutant(String job, String from, String to) throws Exception {
        PartnerSettlementSpace m = base.copyTo(tmp.resolve("mutant-" + copies++));
        Path f = m.config().resolve("jobs").resolve(job + "_job.toon");
        String text = Files.readString(f);
        assertTrue(text.contains(from), "the mutation target is not in " + job + ": " + from);
        Files.writeString(f, text.replace(from, to));
        m.run(PartnerSettlementSpace.POSITION_CHAIN, AS_OF);
        return m;
    }

    private static Map<String, Object> movement(PartnerSettlementSpace s, String id) throws Exception {
        List<Map<String, Object>> r = s.read("settlement_movement", "movement_id = '" + id + "'", "movement_id");
        assertEquals(1, r.size(), id);
        return r.get(0);
    }

    @Test
    void ignoringTheRateTypeTakesTheDecoySpotRateAndTurnsAnF1LineRed() throws Exception {
        PartnerSettlementSpace m = mutant("ps_movement", "AND f.rate_type = b.fx_rate_type ", "");
        Map<String, Object> line = movement(m, "STM|STM-F1-2026-06|1|1");
        assertEquals(new BigDecimal("2.00000000"), line.get("fx_rate"), "the spot decoy of 2026-06-20 is taken");
        assertNotEquals(movement(base, "STM|STM-F1-2026-06|1|1").get("amount"), line.get("amount"));
        assertNotEquals(new Oracle(corpus).movementRows(), rows(m, "settlement_movement", MOVEMENT_COLS, 1));
    }

    @Test
    void usingTheUsageDayAgainstAPeriodEndAgreementTurnsAnEarlyJuneLineRed() throws Exception {
        PartnerSettlementSpace m = mutant("ps_movement", "WHEN movement_kind = 'usage' AND fx_date_rule <> 'period_end' THEN movement_date", "WHEN movement_kind = 'usage' THEN movement_date");
        // 2026-06-05 converts at the 06-01 rate 1.2000, not the cycle-end 1.1000; the fee and a May line do not move
        assertEquals(new BigDecimal("1.20000000"), movement(m, "STM|STM-F1-2026-06|1|5").get("fx_rate"));
        assertEquals(new BigDecimal("1.10000000"), movement(base, "STM|STM-F1-2026-06|1|5").get("fx_rate"));
        assertEquals(movement(base, "STM|STM-F1-2026-05|1|5").get("amount"), movement(m, "STM|STM-F1-2026-05|1|5").get("amount"));
    }

    @Test
    void flippingTheSideSignKeepsThePositionAndNegatesEveryBalance() throws Exception {
        PartnerSettlementSpace m = mutant("ps_movement", "WHEN 'we_pay' THEN 1 ELSE -1 END AS DECIMAL(18,4)) AS signed_amount", "WHEN 'we_pay' THEN -1 ELSE 1 END AS DECIMAL(18,4)) AS signed_amount");
        assertEquals(rows(base, "settlement_position", POSITION_COLS, 3), rows(m, "settlement_position", POSITION_COLS, 3), "a position reads the direction, not the sign");
        assertEquals(((BigDecimal) roll("NG-XA", "USD", "2026-05-01").get("closing")).negate(), rollOf(m, "NG-XA", "USD", "2026-05-01").get("closing"));
        assertTrue(m.read("settlement_rollforward", "opening + invoiced - paid + adjustments <> closing", "netting_group").isEmpty(), "the identity cannot see a wrong sign: only the oracle can");
    }

    @Test
    void aPaymentThatRaisesWhatWeOweTurnsTheJuneBalanceRed() throws Exception {
        PartnerSettlementSpace m = mutant("ps_movement", "CASE kind WHEN 'debit_note' THEN 1 ELSE -1 END, amount, currency FROM pay", "CASE kind WHEN 'debit_note' THEN 1 ELSE 1 END, amount, currency FROM pay");
        assertEquals(new BigDecimal("-10000.0000"), rollOf(m, "NG-XA", "USD", "2026-06-01").get("paid"));
        assertEquals(new BigDecimal("10000.0000"), roll("NG-XA", "USD", "2026-06-01").get("paid"));
    }

    @Test
    void theEarlierRecordOfACorrectedPaymentTurnsTheE1MonthRed() throws Exception {
        PartnerSettlementSpace m = mutant("ps_movement", "ORDER BY recorded_at DESC) = 1", "ORDER BY recorded_at ASC) = 1");
        // E1's first record said 500, the corrected one 450: F1's 880.0000 paid less E1's received amount
        assertEquals(new BigDecimal("380.0000"), rollOf(m, "NG-XB", "USD", "2026-06-01").get("paid"));
        assertEquals(new BigDecimal("430.0000"), roll("NG-XB", "USD", "2026-06-01").get("paid"));
    }

    @Test
    void addingInsteadOfSubtractingTheReceivableTurnsTheNetRed() throws Exception {
        PartnerSettlementSpace m = mutant("ps_position", "CAST(g.payable - g.receivable AS DECIMAL(18,4)) AS net", "CAST(g.payable + g.receivable AS DECIMAL(18,4)) AS net");
        Map<String, Object> xb = m.read("settlement_position", "netting_group = 'NG-XB' AND cycle_month = DATE '2026-06-01'", "currency").get(0);
        assertEquals(((BigDecimal) xb.get("payable")).add((BigDecimal) xb.get("receivable")), xb.get("net"));
        assertFalse(m.read("settlement_position", "net <> payable - receivable", "netting_group").isEmpty(), "the SQL identity catches it too");
    }

    @Test
    void notCarryingTheOpeningTurnsJuneRedAndBreaksTheChain() throws Exception {
        PartnerSettlementSpace m = mutant("ps_rollforward", "lag(closing) OVER (PARTITION BY netting_group, currency ORDER BY cycle_month)", "CAST(NULL AS DECIMAL(18,4))");
        assertEquals(BigDecimal.ZERO.setScale(4), rollOf(m, "NG-XA", "USD", "2026-06-01").get("opening"));
        assertNotEquals(BigDecimal.ZERO.setScale(4), roll("NG-XA", "USD", "2026-06-01").get("opening"));
        assertFalse(m.read("settlement_rollforward", "opening + invoiced - paid + adjustments <> closing", "netting_group").isEmpty(), "the closing identity breaks too");
    }

    @Test
    void endingTheSeriesAtTheLastMovementDropsTheQuietMonthsOfXB() throws Exception {
        PartnerSettlementSpace m = mutant("ps_rollforward", "greatest(max(cycle_month), CAST(date_trunc('month', CAST($as_of AS DATE)) AS DATE))", "max(cycle_month)");
        assertTrue(m.read("settlement_rollforward", "netting_group = 'NG-XB' AND cycle_month >= DATE '2026-07-01'", "currency").isEmpty(), "NG-XB has no movement after June");
        assertEquals(1, base.read("settlement_rollforward", "netting_group = 'NG-XB' AND cycle_month = DATE '2026-07-01'", "currency").size());
    }

    @Test
    void countingACreditLineAsInvoicedMovesTheSplitButNotTheClosing() throws Exception {
        PartnerSettlementSpace m = mutant("ps_movement", "WHEN line_kind IN ('usage', 'fee', 'tax') THEN 'invoiced'", "WHEN line_kind IN ('usage', 'fee', 'tax', 'credit') THEN 'invoiced'");
        Map<String, Object> june = rollOf(m, "NG-XA", "USD", "2026-06-01"), want = roll("NG-XA", "USD", "2026-06-01");
        assertEquals(BigDecimal.ZERO.setScale(4), june.get("adjustments"));
        assertEquals(new BigDecimal("-10.0000"), want.get("adjustments"));
        assertEquals(want.get("closing"), june.get("closing"), "the closing cannot tell: the oracle and the hand-worked split can");
    }

    @Test
    void takingTheCycleEndInsteadOfThePaymentDayLosesTheEurPayment() throws Exception {
        PartnerSettlementSpace m = mutant("ps_movement", "WHEN source = 'payment' THEN movement_date", "WHEN source = 'payment' THEN period_end");
        assertEquals("fx_missing", movement(m, "PAY|PAY-F1-01").get("status"));
        assertEquals("ok", movement(base, "PAY|PAY-F1-01").get("status"));
        assertEquals(new BigDecimal("880.0000"), movement(base, "PAY|PAY-F1-01").get("amount"), "800 EUR at the contract rate in force on 2026-06-25, 1.1000");
        assertEquals(new BigDecimal("1.10000000"), movement(base, "PAY|PAY-F1-01").get("fx_rate"));
        assertEquals(LocalDate.of(2026, 6, 16), movement(base, "PAY|PAY-F1-01").get("fx_rate_date"));
    }

    @Test
    void notMatchingThePartnerLetsAnotherPartnersPaymentSettleTheAgreement() throws Exception {
        PartnerSettlementSpace m = mutant("ps_movement", "AND a.partner_id = u.partner_id ", "");
        assertEquals("ok", movement(m, "PAY|PAY-D1-BAD").get("status"));
        assertEquals("unknown_agreement", movement(base, "PAY|PAY-D1-BAD").get("status"));
        assertEquals("unknown_agreement", movement(m, "PAY|PAY-NONE-01").get("status"), "an agreement that does not exist stays unknown");
    }

    // ------------------------------------------------------------------------------------------- plumbing

    private static String fmt(Object o) {
        if (o == null) return "~";
        if (o instanceof BigDecimal b) return b.toPlainString();
        return String.valueOf(o);
    }

    /** A Dataset's rows as {@code key -> all columns joined}, the key being the first {@code keyCols} columns. */
    private static Map<String, String> rows(PartnerSettlementSpace s, String dataset, List<String> cols, int keyCols) throws Exception {
        Map<String, String> out = new TreeMap<>();
        for (Map<String, Object> r : s.read(dataset, null, String.join(", ", cols.subList(0, keyCols)))) {
            StringBuilder key = new StringBuilder(), all = new StringBuilder();
            for (int i = 0; i < cols.size(); i++) {
                String v = fmt(r.get(cols.get(i)));
                if (i < keyCols) key.append(i > 0 ? "|" : "").append(v);
                all.append(i > 0 ? "|" : "").append(v);
            }
            assertNull(out.put(key.toString(), all.toString()), dataset + ": a duplicate key " + key);
        }
        return out;
    }

    private static void assertSame(String what, Map<String, String> want, Map<String, String> got) {
        if (want.equals(got)) return;
        StringBuilder diff = new StringBuilder(what + ": want " + want.size() + " rows, got " + got.size() + "\n");
        int shown = 0;
        for (String k : new TreeSet<>(union(want.keySet(), got.keySet()))) {
            if (!java.util.Objects.equals(want.get(k), got.get(k)) && shown++ < 6) diff.append("  ").append(k).append("\n    want ").append(want.get(k)).append("\n    got  ").append(got.get(k)).append("\n");
        }
        fail(diff.toString());
    }

    private static Set<String> union(Set<String> a, Set<String> b) {
        Set<String> u = new TreeSet<>(a);
        u.addAll(b);
        return u;
    }

    // ------------------------------------------------------------------------------------------- the independent oracle

    /** Movements, positions and the roll-forward derived in plain Java from the corpus structures: no SQL, no shared code with the Jobs. */
    static final class Oracle {
        record Mv(String id, String source, String doc, Integer version, String agreement, String partner, String group, String direction, LocalDate cycle,
                  LocalDate date, LocalDate pStart, LocalDate pEnd, String kind, String effect, BigDecimal orig, String curOrig, BigDecimal amount,
                  String cur, BigDecimal fxRate, LocalDate fxRateDate, String fxType, String fxStatus, BigDecimal signed, String status) {
            String row() {
                return String.join("|", fmt(id), fmt(source), fmt(doc), fmt(version), fmt(agreement), fmt(partner), fmt(group), fmt(direction), fmt(cycle),
                        fmt(date), fmt(pStart), fmt(pEnd), fmt(kind), fmt(effect), fmt(orig), fmt(curOrig), fmt(amount), fmt(cur), fmt(fxRate), fmt(fxRateDate),
                        fmt(fxType), fmt(fxStatus), fmt(signed), fmt(status));
            }
        }

        final PartnerSettlementCorpus c;
        final Map<String, Mv> movements = new TreeMap<>();

        Oracle(PartnerSettlementCorpus c) {
            this.c = c;
            for (PartnerSettlementCorpus.Line l : currentStatementLines()) statementMovement(l);
            for (PartnerSettlementCorpus.Payment p : currentPayments()) paymentMovement(p);
        }

        private List<PartnerSettlementCorpus.Line> currentStatementLines() {
            Map<String, PartnerSettlementCorpus.Line> dedup = new LinkedHashMap<>();
            for (List<PartnerSettlementCorpus.Line> ls : c.statements.values())
                for (PartnerSettlementCorpus.Line l : ls) dedup.putIfAbsent(l.stmt() + "|" + l.version() + "|" + l.lineNo(), l);
            dedup.values().removeIf(l -> c.partners.stream().noneMatch(p -> p.startsWith(l.partner() + ","))
                    || c.agreements.stream().noneMatch(a -> a.id().equals(l.agreement()) && a.partner().equals(l.partner())));
            Map<String, Integer> top = new LinkedHashMap<>();
            for (PartnerSettlementCorpus.Line l : dedup.values()) top.merge(l.agreement() + "|" + l.pStart() + "|" + l.pEnd(), l.version(), Math::max);
            List<PartnerSettlementCorpus.Line> out = new ArrayList<>();
            for (PartnerSettlementCorpus.Line l : dedup.values()) if (l.version() == top.get(l.agreement() + "|" + l.pStart() + "|" + l.pEnd())) out.add(l);
            return out;
        }

        private List<PartnerSettlementCorpus.Payment> currentPayments() {
            Map<String, PartnerSettlementCorpus.Payment> latest = new TreeMap<>();
            for (List<PartnerSettlementCorpus.Payment> ps : c.payments.values())
                for (PartnerSettlementCorpus.Payment p : ps) latest.merge(p.id(), p, (a, b) -> b.recordedAt().isAfter(a.recordedAt()) ? b : a);
            return new ArrayList<>(latest.values());
        }

        /** The version of an agreement in force for a day: the latest starting on or before it, for the right partner. */
        PartnerSettlementCorpus.Agreement version(String agreement, String partner, LocalDate day) {
            PartnerSettlementCorpus.Agreement best = null;
            for (PartnerSettlementCorpus.Agreement a : c.agreements)
                if (a.id().equals(agreement) && a.partner().equals(partner) && !a.from().isAfter(day) && (best == null || a.from().isAfter(best.from()))) best = a;
            return best;
        }

        PartnerSettlementCorpus.Fx rateRow(String from, String to, String type, LocalDate day) {
            PartnerSettlementCorpus.Fx best = null;
            for (PartnerSettlementCorpus.Fx f : c.fx)
                if (f.fromCur().equals(from) && f.toCur().equals(to) && f.type().equals(type) && !f.from().isAfter(day) && (best == null || f.from().isAfter(best.from()))) best = f;
            return best;
        }

        private void statementMovement(PartnerSettlementCorpus.Line l) {
            LocalDate date = l.day() == null ? l.pEnd() : l.day();
            PartnerSettlementCorpus.Agreement a = version(l.agreement(), l.partner(), date);
            String kind = l.kind();
            LocalDate fxDate = a != null && kind.equals("usage") && !a.fxRule().equals("period_end") ? date : l.pEnd();
            add("STM|" + l.stmt() + "|" + l.version() + "|" + l.lineNo(), "statement", l.stmt(), l.version(), l.agreement(), l.partner(), l.direction(),
                    l.pEnd().withDayOfMonth(1), date, l.pStart(), l.pEnd(), kind, List.of("usage", "fee", "tax").contains(kind) ? "invoiced" : "adjustment", 1,
                    l.amount(), l.currency(), a, fxDate);
        }

        private void paymentMovement(PartnerSettlementCorpus.Payment p) {
            PartnerSettlementCorpus.Agreement a = version(p.agreement(), p.partner(), p.date());
            add("PAY|" + p.id(), "payment", p.id(), null, p.agreement(), p.partner(), p.direction(), p.date().withDayOfMonth(1), p.date(), null, null, p.kind(),
                    p.kind().equals("payment") ? "paid" : "adjustment", p.kind().equals("debit_note") ? 1 : -1, p.amount(), p.currency(), a, p.date());
        }

        private void add(String id, String source, String doc, Integer version, String agreement, String partner, String direction, LocalDate cycle, LocalDate date,
                         LocalDate ps, LocalDate pe, String kind, String effect, int kindSign, BigDecimal orig, String curOrig,
                         PartnerSettlementCorpus.Agreement a, LocalDate fxDate) {
            String group = null, cur = null, fxType = null, fxStatus = "unknown_agreement", status = "unknown_agreement";
            BigDecimal rate = null, amount = null, signed = null;
            LocalDate rateDate = null;
            if (a != null) {
                group = a.group().isEmpty() ? null : a.group();   // a blank CSV field is a NULL
                cur = a.currency();
                fxType = a.fxType();
                if (curOrig.equals(cur)) {
                    fxStatus = "same_currency";
                    status = "ok";
                    rate = BigDecimal.ONE.setScale(8);
                    amount = orig.setScale(4, RoundingMode.HALF_UP);
                } else {
                    PartnerSettlementCorpus.Fx row = rateRow(curOrig, cur, fxType, fxDate);
                    if (row == null) {
                        fxStatus = "missing";
                        status = "fx_missing";
                    } else {
                        fxStatus = "ok";
                        status = "ok";
                        rate = row.rate().setScale(8, RoundingMode.HALF_UP);
                        rateDate = row.from();
                        amount = orig.multiply(row.rate()).setScale(4, RoundingMode.HALF_UP);
                    }
                }
                if (amount != null) signed = amount.multiply(BigDecimal.valueOf(kindSign * (direction.equals("we_pay") ? 1 : -1))).setScale(4, RoundingMode.HALF_UP);
            }
            assertNull(movements.put(id, new Mv(id, source, doc, version, agreement, partner, group, direction, cycle, date, ps, pe, kind, effect, orig, curOrig,
                    amount, cur, rate, rateDate, fxType, fxStatus, signed, status)), "a duplicate movement " + id);
        }

        Map<String, String> movementRows() {
            Map<String, String> out = new TreeMap<>();
            for (Mv m : movements.values()) out.put(m.id(), m.row());
            return out;
        }

        private List<Mv> counted(boolean statementsOnly) {
            List<Mv> out = new ArrayList<>();
            for (Mv m : movements.values())
                if ((m.status().equals("ok") || m.status().equals("fx_missing")) && (!statementsOnly || m.source().equals("statement"))) out.add(m);
            return out;
        }

        Map<String, String> positionRows() {
            Map<String, List<Mv>> groups = new TreeMap<>();
            for (Mv m : counted(true)) groups.computeIfAbsent(fmt(m.group()) + "|" + m.cycle() + "|" + m.cur(), k -> new ArrayList<>()).add(m);
            Map<String, String> out = new TreeMap<>();
            for (Map.Entry<String, List<Mv>> e : groups.entrySet()) {
                BigDecimal payable = BigDecimal.ZERO.setScale(4), receivable = BigDecimal.ZERO.setScale(4);
                Set<String> docs = new TreeSet<>();
                long unconverted = 0;
                Map<String, BigDecimal> orig = new TreeMap<>();
                for (Mv m : e.getValue()) {
                    docs.add(m.doc());
                    if (m.status().equals("fx_missing")) { unconverted++; continue; }
                    if (m.direction().equals("we_pay")) payable = payable.add(m.amount()); else receivable = receivable.add(m.amount());
                    orig.merge(m.curOrig(), m.direction().equals("we_pay") ? m.orig() : m.orig().negate(), BigDecimal::add);
                }
                List<String> parts = new ArrayList<>();
                for (Map.Entry<String, BigDecimal> n : orig.entrySet()) parts.add(n.getKey() + ":" + n.getValue().setScale(4).toPlainString());
                BigDecimal net = payable.subtract(receivable);
                Mv first = e.getValue().get(0);
                out.put(e.getKey(), String.join("|", fmt(first.group()), fmt(first.cycle()), fmt(first.cur()), fmt(payable), fmt(receivable), fmt(net),
                        net.signum() > 0 ? "we_pay" : net.signum() < 0 ? "they_pay" : "even", parts.isEmpty() ? "~" : String.join(",", parts),
                        "" + docs.size(), "" + e.getValue().size(), "" + unconverted));
            }
            return out;
        }

        Map<String, String> rollForwardRows() {
            Map<String, Map<LocalDate, List<Mv>>> series = new TreeMap<>();
            for (Mv m : counted(false)) series.computeIfAbsent(fmt(m.group()) + "|" + m.cur(), k -> new TreeMap<>()).computeIfAbsent(m.cycle(), k -> new ArrayList<>()).add(m);
            LocalDate asOfMonth = PartnerSettlementCorpus.AS_OF.withDayOfMonth(1);
            Map<String, String> out = new TreeMap<>();
            for (Map.Entry<String, Map<LocalDate, List<Mv>>> s : series.entrySet()) {
                TreeMap<LocalDate, List<Mv>> months = (TreeMap<LocalDate, List<Mv>>) s.getValue();
                LocalDate hi = months.lastKey().isAfter(asOfMonth) ? months.lastKey() : asOfMonth;
                BigDecimal closing = BigDecimal.ZERO.setScale(4);
                for (LocalDate month = months.firstKey(); !month.isAfter(hi); month = month.plusMonths(1)) {
                    BigDecimal invoiced = BigDecimal.ZERO.setScale(4), paid = BigDecimal.ZERO.setScale(4), adj = BigDecimal.ZERO.setScale(4);
                    long unconverted = 0;
                    List<Mv> ms = months.getOrDefault(month, List.of());
                    for (Mv m : ms) {
                        if (m.status().equals("fx_missing")) { unconverted++; continue; }
                        switch (m.effect()) {
                            case "invoiced" -> invoiced = invoiced.add(m.signed());
                            case "paid" -> paid = paid.subtract(m.signed());
                            default -> adj = adj.add(m.signed());
                        }
                    }
                    BigDecimal opening = closing;
                    closing = opening.add(invoiced).subtract(paid).add(adj);
                    String[] g = s.getKey().split("\\|");
                    out.put(g[0] + "|" + month + "|" + g[1], String.join("|", g[0], month.toString(), g[1], fmt(opening), fmt(invoiced), fmt(paid), fmt(adj), fmt(closing),
                            "" + ms.size(), "" + unconverted));
                }
            }
            return out;
        }
    }
}
