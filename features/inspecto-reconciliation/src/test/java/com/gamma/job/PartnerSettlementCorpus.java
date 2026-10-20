package com.gamma.job;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * The deterministic SYNTHETIC golden corpus of the {@code partner-settlement} Space Template
 * ({@code PACK-PARTNER-SETTLEMENT-1}, slices S0 to S3). Hand-built and fully deterministic (no random draw), no real
 * data: four fictional partners in the made-up country codes {@code XA} / {@code XB}, agreements {@code AG-...},
 * statements {@code STM-...}, fictional amounts. Money is {@link BigDecimal} at four decimals, so every planted
 * difference is exact and a look-alike sits exactly AT a threshold.
 *
 * <p>Four agreements over three monthly cycles (May to July 2026), judged as of {@link #AS_OF}:
 * <ul>
 *   <li>{@code AG-VOICE-A1} (we pay, USD, percent tolerance 1.0 then 2.0 from 2026-07-16, monthly): the richest. A stale
 *       rating run for May that must lose to the later run, a re-sent May statement, a July statement restated after a
 *       control-total mismatch, an amount mismatch, an exactly-at-tolerance day, a cut-off difference (two offsetting
 *       days) next to a non-offsetting pair, the mid-cycle tolerance change, and one unrated day.</li>
 *   <li>{@code AG-SMS-C1} (they pay, rated in USD, invoiced in EUR, absolute tolerance 5.0, semi-monthly): an FX
 *       difference, three FX look-alikes, an exactly-at-tolerance day, one a step beyond it, and a balanced credit line.</li>
 *   <li>{@code AG-VOICE-D1} (we pay, USD, absolute tolerance 0.5, monthly): no July statement and it is overdue
 *       (missing_statement); an at-tolerance and a one-step-beyond day.</li>
 *   <li>{@code AG-VOICE-E1} (they pay, USD, monthly, statement due 25 days after the cycle): no July statement but it is
 *       not due yet, so silent.</li>
 * </ul>
 * Two statements name an unknown partner and an unknown agreement.
 */
final class PartnerSettlementCorpus {

    static final LocalDate AS_OF = LocalDate.of(2026, 8, 20);
    static final LocalDate FIRST = LocalDate.of(2026, 5, 1);
    static final LocalDate LAST = LocalDate.of(2026, 7, 31);

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    static BigDecimal mul(BigDecimal a, BigDecimal b) { return a.multiply(b).setScale(4, RoundingMode.HALF_UP); }

    static BigDecimal bd(String s) { return new BigDecimal(s).setScale(4, RoundingMode.UNNECESSARY); }

    record Agreement(String id, String partner, String kind, String direction, String currency, String rounding, String tolType,
                     BigDecimal tolerance, String cycle, int stmtDue, int dispute, int payment, String group, String fxType,
                     String fxRule, LocalDate from, LocalDate to) {
        boolean covers(LocalDate d) { return !d.isBefore(from) && (to == null || !d.isAfter(to)); }
        String csv() {
            return String.join(",", id, partner, kind, direction, currency, rounding, tolType, tolerance.toPlainString(), "UTC", "00:00",
                    cycle, "" + stmtDue, "" + dispute, "" + payment, group, fxType, fxRule, from.toString(), to == null ? "" : to.toString());
        }
    }

    record Rerated(String agreement, String direction, String service, String dest, LocalDate day, String rateId, String runId,
                   LocalDateTime ratedAt, BigDecimal units, BigDecimal amount, String currency, int records, String reason) {
        String csv() {
            return String.join(",", agreement, direction, service, dest, day.toString(), rateId, runId, TS.format(ratedAt),
                    units.toPlainString(), amount == null ? "" : amount.toPlainString(), currency, "" + records, reason == null ? "" : reason);
        }
    }

    record Line(String stmt, int version, int lineNo, String partner, String agreement, LocalDate pStart, LocalDate pEnd, LocalDate day,
                String direction, String service, String dest, BigDecimal units, BigDecimal amount, String currency, BigDecimal fxApplied,
                String kind, BigDecimal ctAmount, int ctLines, LocalDateTime receivedAt) {
        String csv() {
            return String.join(",", stmt, "" + version, "" + lineNo, partner, agreement, pStart.toString(), pEnd.toString(),
                    day == null ? "" : day.toString(), direction, service, dest, units.toPlainString(), amount.toPlainString(), currency,
                    fxApplied == null ? "" : fxApplied.toPlainString(), kind, ctAmount.toPlainString(), "" + ctLines, TS.format(receivedAt));
        }
    }

    /** One usage line before it is placed in a statement. */
    private record Usage(LocalDate day, String dest, BigDecimal units, BigDecimal amount, BigDecimal fxApplied) {}

    record Fx(LocalDate from, String fromCur, String toCur, String type, BigDecimal rate) {}

    final List<String> partners = new ArrayList<>();
    final List<Agreement> agreements = new ArrayList<>();
    final List<Fx> fx = new ArrayList<>();
    final List<String> holidays = new ArrayList<>();
    final List<Rerated> rerated = new ArrayList<>();
    /** statement file name to its lines (a re-sent statement is a second file). */
    final Map<String, List<Line>> statements = new LinkedHashMap<>();

    /** reason to the ITEM_KEYs {@code ps_findings} must emit, exactly these. */
    final Map<String, Set<String>> plantedFindings = new TreeMap<>();
    /** reason to the {@code statement|version} keys {@code ps_statement_findings} must emit, exactly these. */
    final Map<String, Set<String>> plantedStatementFindings = new TreeMap<>();
    /** Look-alikes a correct control must NOT flag (item keys), for the golden's silence assertion. */
    final Set<String> benign = new TreeSet<>();

    // ------------------------------------------------------------------------------------------------ the generator

    static PartnerSettlementCorpus generate() {
        PartnerSettlementCorpus c = new PartnerSettlementCorpus();
        c.build();
        return c;
    }

    private void build() {
        partners.add("P1,Aurora Test Carrier,interconnect,XA,active");
        partners.add("P2,Borealis Test Wholesale,interconnect,XB,active");
        partners.add("P3,Cobalt Test Messaging,interconnect,XA,active");
        partners.add("P4,Dune Test Virtual,MVNO,XB,active");

        LocalDate v2 = LocalDate.of(2026, 7, 16);
        agreements.add(new Agreement("AG-VOICE-A1", "P1", "interconnect_voice", "we_pay", "USD", "started_minute", "pct", bd("1"), "monthly", 10, 30, 30, "NG-XA", "contract", "usage_date", FIRST, v2.minusDays(1)));
        agreements.add(new Agreement("AG-VOICE-A1", "P1", "interconnect_voice", "we_pay", "USD", "started_minute", "pct", bd("2"), "monthly", 10, 30, 30, "NG-XA", "contract", "usage_date", v2, null));
        agreements.add(new Agreement("AG-SMS-C1", "P3", "sms_termination", "they_pay", "EUR", "per_message", "abs", bd("5"), "semi_monthly", 5, 20, 20, "NG-XA", "contract", "usage_date", LocalDate.of(2026, 6, 1), null));
        agreements.add(new Agreement("AG-VOICE-D1", "P2", "interconnect_voice", "we_pay", "USD", "started_minute", "abs", bd("0.5"), "monthly", 10, 30, 30, "NG-XB", "contract", "usage_date", FIRST, null));
        agreements.add(new Agreement("AG-VOICE-E1", "P4", "interconnect_voice", "they_pay", "USD", "per_second", "pct", bd("1"), "monthly", 25, 30, 30, "NG-XB", "contract", "usage_date", FIRST, null));

        // contract USD to EUR; the spot rows are decoys the agreement must not use
        for (String[] r : new String[][]{{"2026-06-01", "0.9000"}, {"2026-06-16", "0.9100"}, {"2026-07-01", "0.9200"}, {"2026-07-16", "0.9300"}}) {
            fx.add(new Fx(LocalDate.parse(r[0]), "USD", "EUR", "contract", bd(r[1])));
            fx.add(new Fx(LocalDate.parse(r[0]), "USD", "EUR", "spot", bd("0.8000")));
        }
        holidays.add("XA,2026-06-10,Test Founders Day");
        holidays.add("XA,2026-07-13,Test Harvest Day");
        holidays.add("XB,2026-06-15,Test Lantern Day");
        holidays.add("XB,2026-08-25,Test Market Day");

        buildA1();
        buildC1();
        buildD1();
        buildE1();
        buildUnknowns();
    }

    // ---- amounts the re-rater "prices" (the agreement's expected side) -------------------------------------------

    static BigDecimal a1Expected(String dest, LocalDate d) {
        int dom = d.getDayOfMonth();
        return dest.equals("MOBILE") ? bd("" + (200 + 10 * (dom % 5))) : bd("" + (100 + 5 * (dom % 4)));
    }

    static BigDecimal c1UsdExpected(LocalDate d) {
        if (d.equals(LocalDate.of(2026, 6, 18))) return bd("1000");
        if (d.equals(LocalDate.of(2026, 6, 21))) return bd("100");
        if (d.equals(LocalDate.of(2026, 6, 22))) return bd("500");
        return bd("" + (100 + 10 * (d.getDayOfMonth() % 4)));
    }

    /** The contract USD to EUR rate in force on a day (latest row on or before it); null before the first row. */
    BigDecimal contractRate(LocalDate d) {
        BigDecimal best = null;
        LocalDate bestDate = null;
        for (Fx f : fx)
            if (f.type().equals("contract") && !f.from().isAfter(d) && (bestDate == null || f.from().isAfter(bestDate))) { best = f.rate(); bestDate = f.from(); }
        return best;
    }

    static BigDecimal d1Expected(LocalDate d) { return bd("" + (50 + 2 * (d.getDayOfMonth() % 6))); }

    static BigDecimal e1Expected(LocalDate d) { return bd("" + (80 + 3 * (d.getDayOfMonth() % 3))); }

    private static final LocalDateTime RUN1_AT = LocalDateTime.of(2026, 6, 2, 8, 0);
    private static final LocalDateTime RUN2_AT = LocalDateTime.of(2026, 6, 5, 8, 0);

    private void rate(String agr, String dir, String svc, String dest, LocalDate d, String rateId, String run, LocalDateTime at,
                      BigDecimal amount, String cur, BigDecimal unitsPerMoney) {
        rerated.add(new Rerated(agr, dir, svc, dest, d, rateId, run, at, amount.multiply(unitsPerMoney).setScale(4, RoundingMode.HALF_UP), amount, cur, 7, null));
    }

    private static BigDecimal over(Map<String, BigDecimal> m, LocalDate d, String dest, BigDecimal normal) {
        BigDecimal o = m.get(d + "|" + dest);
        return o == null ? normal : o;
    }

    // ---- AG-VOICE-A1 ---------------------------------------------------------------------------------------------

    private void buildA1() {
        String a = "AG-VOICE-A1", dir = "we_pay";
        for (LocalDate d = FIRST; !d.isAfter(LAST); d = d.plusDays(1))
            for (String dest : List.of("MOBILE", "FIXED")) {
                BigDecimal e = a1Expected(dest, d);
                String rateId = d.isBefore(LocalDate.of(2026, 7, 16)) ? "A1-R1" : "A1-R2";
                if (d.equals(LocalDate.of(2026, 6, 25)) && dest.equals("MOBILE")) {
                    rerated.add(new Rerated(a, dir, "VOICE", dest, d, rateId, "RR-A1-2026-06", LocalDateTime.of(2026, 7, 2, 8, 0),
                            bd("1"), null, "USD", 3, "no_rate_found"));
                    continue;
                }
                if (d.getMonthValue() == 5) {   // May was re-rated: the first run is stale and wrong (90 percent), the second is right
                    rate(a, dir, "VOICE", dest, d, rateId, "RR-A1-2026-05-1", RUN1_AT, e.multiply(bd("0.9")).setScale(4, RoundingMode.HALF_UP), "USD", bd("20"));
                    rate(a, dir, "VOICE", dest, d, rateId, "RR-A1-2026-05-2", RUN2_AT, e, "USD", bd("20"));
                } else {
                    rate(a, dir, "VOICE", dest, d, rateId, "RR-A1-" + d.toString().substring(0, 7), LocalDateTime.of(d.getYear(), d.getMonthValue(), 1, 8, 0).plusMonths(1).plusDays(1), e, "USD", bd("20"));
                }
            }

        // what the partner billed: normally exactly our expected amount; planted differences below
        Map<String, BigDecimal> billed = new LinkedHashMap<>();
        billed.put("2026-05-10|MOBILE", bd("206"));         // +3 percent, tolerance 1 percent of 200: amount_mismatch
        billed.put("2026-05-12|FIXED", bd("101"));          // exactly +1 percent of 100: AT tolerance, silent
        billed.put("2026-05-20|FIXED", bd("101.01"));       // one cent beyond: amount_mismatch
        billed.put("2026-06-14|MOBILE", bd("180"));         // partner cut-off earlier: -60 ...
        billed.put("2026-06-15|MOBILE", bd("260"));         // ... and +60 on the next day: cutoff_difference on both
        billed.put("2026-06-20|MOBILE", bd("210"));         // +10 ...
        billed.put("2026-06-21|MOBILE", bd("206"));         // ... -4: does NOT net to tolerance, so two amount_mismatch
        billed.put("2026-06-25|MOBILE", bd("190"));         // billed, but our side is unrated: unrated_usage, no variance
        billed.put("2026-07-15|FIXED", bd("116.725"));      // +1.5 percent of 115 under the 1 percent version: amount_mismatch
        billed.put("2026-07-16|FIXED", bd("101.5"));        // +1.5 percent of 100 under the 2 percent version: silent
        for (int m = 5; m <= 7; m++) {
            LocalDate s = LocalDate.of(2026, m, 1), e = s.withDayOfMonth(s.lengthOfMonth());
            List<Usage> lines = new ArrayList<>();
            for (LocalDate d = s; !d.isAfter(e); d = d.plusDays(1))
                for (String dest : List.of("MOBILE", "FIXED"))
                    lines.add(new Usage(d, dest, mul(bd("20"), a1Expected(dest, d)), over(billed, d, dest, a1Expected(dest, d)), null));
            String id = "STM-A1-2026-0" + m;
            LocalDateTime rcv = LocalDateTime.of(2026, m + 1, 8, 9, 0);
            if (m == 5) {
                statement("A1-2026-05.csv", id, 1, "P1", a, s, e, dir, "VOICE", "USD", rcv, lines, bd("25"), "fee", null);
                // the same statement sent again under another file name: every line repeats
                statement("A1-2026-05-resend.csv", id, 1, "P1", a, s, e, dir, "VOICE", "USD", rcv.plusDays(2), lines, bd("25"), "fee", null);
            } else if (m == 6) {
                statement("A1-2026-06.csv", id, 1, "P1", a, s, e, dir, "VOICE", "USD", rcv, lines, bd("-10"), "credit", null);
            } else {
                // v1 is wrong (a bad 07-20 MOBILE line and a control total one unit over); v2 restates it
                List<Usage> bad = new ArrayList<>(lines);
                for (int i = 0; i < bad.size(); i++)
                    if (bad.get(i).day().equals(LocalDate.of(2026, 7, 20)) && bad.get(i).dest().equals("MOBILE"))
                        bad.set(i, new Usage(bad.get(i).day(), "MOBILE", bad.get(i).units(), bd("999"), null));
                statement("A1-2026-07-v1.csv", id, 1, "P1", a, s, e, dir, "VOICE", "USD", rcv, bad, null, null, bd("1"));
                statement("A1-2026-07-v2.csv", id, 2, "P1", a, s, e, dir, "VOICE", "USD", rcv.plusDays(5), lines, null, null, null);
            }
        }
        key("amount_mismatch", a, dir, "VOICE", "MOBILE", "2026-05-10");
        key("amount_mismatch", a, dir, "VOICE", "FIXED", "2026-05-20");
        key("amount_mismatch", a, dir, "VOICE", "MOBILE", "2026-06-20");
        key("amount_mismatch", a, dir, "VOICE", "MOBILE", "2026-06-21");
        key("amount_mismatch", a, dir, "VOICE", "FIXED", "2026-07-15");
        key("cutoff_difference", a, dir, "VOICE", "MOBILE", "2026-06-14");
        key("cutoff_difference", a, dir, "VOICE", "MOBILE", "2026-06-15");
        key("unrated_usage", a, dir, "VOICE", "MOBILE", "2026-06-25");
        benign.add(a + "|" + dir + "|VOICE|FIXED|2026-05-12");
        benign.add(a + "|" + dir + "|VOICE|FIXED|2026-07-16");
        benign.add(a + "|" + dir + "|VOICE|MOBILE|2026-07-20");   // v1 billed 999 here; the restated v2 is current
        plantedStatementFindings.computeIfAbsent("duplicate_statement", k -> new TreeSet<>()).add("STM-A1-2026-05|1");
        plantedStatementFindings.computeIfAbsent("control_total_mismatch", k -> new TreeSet<>()).add("STM-A1-2026-07|1");
        plantedStatementFindings.computeIfAbsent("restated_statement", k -> new TreeSet<>()).add("STM-A1-2026-07|2");
    }

    // ---- AG-SMS-C1 ----------------------------------------------------------------------------------------------

    private void buildC1() {
        String a = "AG-SMS-C1", dir = "they_pay";
        LocalDate start = LocalDate.of(2026, 6, 1);
        for (LocalDate d = start; !d.isAfter(LAST); d = d.plusDays(1))
            rate(a, dir, "SMS", "NATIONAL", d, "C1-R1", "RR-C1-" + d.toString().substring(0, 7), LocalDateTime.of(2026, d.getMonthValue() + 1, 1, 9, 0), c1UsdExpected(d), "USD", bd("10"));

        // [usd-converted expected, billed EUR, partner applied rate]; null = the clean day
        LocalDate[][] periods = {
                {LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 15)}, {LocalDate.of(2026, 6, 16), LocalDate.of(2026, 6, 30)},
                {LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 15)}, {LocalDate.of(2026, 7, 16), LocalDate.of(2026, 7, 31)}};
        int n = 0;
        for (LocalDate[] p : periods) {
            n++;
            List<Usage> lines = new ArrayList<>();
            for (LocalDate d = p[0]; !d.isAfter(p[1]); d = d.plusDays(1)) {
                BigDecimal usd = c1UsdExpected(d), rate = contractRate(d), applied = rate, billed = usd.multiply(rate).setScale(4, RoundingMode.HALF_UP);
                switch (d.toString()) {
                    case "2026-06-18" -> { applied = bd("0.93"); billed = usd.multiply(applied).setScale(4, RoundingMode.HALF_UP); }   // partner converted at its own rate
                    case "2026-06-19" -> { applied = bd("0.91"); billed = usd.multiply(applied).setScale(4, RoundingMode.HALF_UP); }   // same rate as ours: clean
                    case "2026-06-20" -> billed = billed.add(bd("5"));                                                                    // exactly AT the absolute tolerance
                    case "2026-06-21" -> { applied = bd("0.93"); billed = usd.multiply(applied).setScale(4, RoundingMode.HALF_UP); }   // different rate, +2 inside the 5 tolerance
                    case "2026-06-22" -> billed = billed.add(bd("50"));                                                                   // not explained by FX
                    case "2026-07-02" -> billed = billed.add(bd("5.0001"));                                                               // one step beyond tolerance
                    default -> { }
                }
                lines.add(new Usage(d, "NATIONAL", mul(bd("10"), usd), billed, applied));
            }
            LocalDateTime rcv = p[1].plusDays(3).atTime(10, 0);
            if (n == 4) statement("C1-2026-07B.csv", "STM-C1-2026-07B", 1, "P3", a, p[0], p[1], dir, "SMS", "EUR", rcv, lines, bd("-12.34"), "credit", null);
            else statement("C1-" + p[0] + ".csv", "STM-C1-" + p[0], 1, "P3", a, p[0], p[1], dir, "SMS", "EUR", rcv, lines, null, null, null);
        }
        key("fx_difference", a, dir, "SMS", "NATIONAL", "2026-06-18");
        key("amount_mismatch", a, dir, "SMS", "NATIONAL", "2026-06-22");
        key("amount_mismatch", a, dir, "SMS", "NATIONAL", "2026-07-02");
        for (String d : List.of("2026-06-19", "2026-06-20", "2026-06-21")) benign.add(a + "|" + dir + "|SMS|NATIONAL|" + d);
    }

    // ---- AG-VOICE-D1 / E1 ---------------------------------------------------------------------------------------

    private void buildD1() {
        String a = "AG-VOICE-D1", dir = "we_pay";
        for (LocalDate d = FIRST; !d.isAfter(LAST); d = d.plusDays(1))
            rate(a, dir, "VOICE", "NATIONAL", d, "D1-R1", "RR-D1-" + d.toString().substring(0, 7), LocalDateTime.of(2026, d.getMonthValue() + 1, 1, 9, 0), d1Expected(d), "USD", bd("20"));
        for (int m = 5; m <= 6; m++) {   // July is never sent
            LocalDate s = LocalDate.of(2026, m, 1), e = s.withDayOfMonth(s.lengthOfMonth());
            List<Usage> lines = new ArrayList<>();
            for (LocalDate d = s; !d.isAfter(e); d = d.plusDays(1)) {
                BigDecimal b = d1Expected(d);
                if (d.equals(LocalDate.of(2026, 6, 3))) b = b.add(bd("0.5"));        // AT the absolute tolerance
                if (d.equals(LocalDate.of(2026, 6, 4))) b = b.add(bd("0.5001"));     // one step beyond
                lines.add(new Usage(d, "NATIONAL", mul(bd("20"), d1Expected(d)), b, null));
            }
            statement("D1-2026-0" + m + ".csv", "STM-D1-2026-0" + m, 1, "P2", a, s, e, dir, "VOICE", "USD", e.plusDays(6).atTime(9, 0), lines, null, null, null);
        }
        key("amount_mismatch", a, dir, "VOICE", "NATIONAL", "2026-06-04");
        key("missing_statement", a, dir, null, null, null);
        benign.add(a + "|" + dir + "|VOICE|NATIONAL|2026-06-03");
    }

    private void buildE1() {
        String a = "AG-VOICE-E1", dir = "they_pay";
        for (LocalDate d = FIRST; !d.isAfter(LAST); d = d.plusDays(1))
            rate(a, dir, "VOICE", "NATIONAL", d, "E1-R1", "RR-E1-" + d.toString().substring(0, 7), LocalDateTime.of(2026, d.getMonthValue() + 1, 1, 9, 0), e1Expected(d), "USD", bd("20"));
        for (int m = 5; m <= 6; m++) {   // July's statement is not due until after AS_OF
            LocalDate s = LocalDate.of(2026, m, 1), e = s.withDayOfMonth(s.lengthOfMonth());
            List<Usage> lines = new ArrayList<>();
            for (LocalDate d = s; !d.isAfter(e); d = d.plusDays(1)) lines.add(new Usage(d, "NATIONAL", mul(bd("20"), e1Expected(d)), e1Expected(d), null));
            statement("E1-2026-0" + m + ".csv", "STM-E1-2026-0" + m, 1, "P4", a, s, e, dir, "VOICE", "USD", e.plusDays(20).atTime(9, 0), lines, null, null, null);
        }
        benign.add(a + "|" + dir + "|ALL-OF-JULY");
    }

    private void buildUnknowns() {
        LocalDate s = LocalDate.of(2026, 6, 1), e = LocalDate.of(2026, 6, 30);
        List<Usage> lines = List.of(new Usage(LocalDate.of(2026, 6, 2), "NATIONAL", bd("100"), bd("40"), null),
                new Usage(LocalDate.of(2026, 6, 3), "NATIONAL", bd("100"), bd("41"), null));
        statement("X9-2026-06.csv", "STM-X9-2026-06", 1, "P99", "AG-VOICE-X9", s, e, "we_pay", "VOICE", "USD", LocalDateTime.of(2026, 7, 9, 9, 0), lines, null, null, null);
        statement("NONE-2026-06.csv", "STM-NONE-2026-06", 1, "P2", "AG-VOICE-NONE", s, e, "we_pay", "VOICE", "USD", LocalDateTime.of(2026, 7, 9, 9, 0), lines, null, null, null);
        plantedStatementFindings.computeIfAbsent("unknown_partner", k -> new TreeSet<>()).add("STM-X9-2026-06|1");
        plantedStatementFindings.computeIfAbsent("unknown_agreement", k -> new TreeSet<>()).add("STM-NONE-2026-06|1");
    }

    private void key(String reason, String agr, String dir, String svc, String dest, String day) {
        String k = dir == null || svc == null ? agr + "|2026-07-01|2026-07-31" : agr + "|" + dir + "|" + svc + "|" + dest + "|" + day;
        plantedFindings.computeIfAbsent(reason, r -> new TreeSet<>()).add(k);
    }

    /**
     * Place usage lines (a null entry is a placeholder for the extra {@code extraKind} line) in one statement version. The control
     * totals are the true line count and sum, plus {@code ctSkew} to the amount when given.
     */
    private void statement(String file, String id, int version, String partner, String agreement, LocalDate s, LocalDate e, String dir,
                           String svc, String cur, LocalDateTime rcv, List<Usage> usage, BigDecimal extra, String extraKind, BigDecimal ctSkew) {
        List<Line> out = new ArrayList<>();
        BigDecimal sum = BigDecimal.ZERO.setScale(4);
        int no = 0;
        List<Usage> real = new ArrayList<>();
        for (Usage u : usage) if (u != null) real.add(u);
        for (Usage u : real) { sum = sum.add(u.amount()); no++; }
        int total = real.size() + (extra == null ? 0 : 1);
        if (extra != null) sum = sum.add(extra);
        BigDecimal ct = ctSkew == null ? sum : sum.add(ctSkew);
        no = 0;
        for (Usage u : real)
            out.add(new Line(id, version, ++no, partner, agreement, s, e, u.day(), dir, svc, u.dest(), u.units(), u.amount(), cur, u.fxApplied(), "usage", ct, total, rcv));
        if (extra != null)
            out.add(new Line(id, version, ++no, partner, agreement, s, e, null, dir, svc, "", BigDecimal.ZERO.setScale(4), extra, cur, null, extraKind, ct, total, rcv));
        statements.put(file, out);
    }

    // ------------------------------------------------------------------------------------------------------------ files

    private static final String NL = "\n";

    /** feed-relative file name to CSV text (header row included). */
    Map<String, String> files() {
        Map<String, String> f = new LinkedHashMap<>();
        f.put("partner/partner.csv", "partner_id,name,type,country,status" + NL + String.join(NL, partners) + NL);
        StringBuilder ag = new StringBuilder("agreement_id,partner_id,kind,direction,currency,rounding_rule,tolerance_type,tolerance,cutoff_zone,cutoff_time,cycle,"
                + "statement_due_days,dispute_window_days,payment_due_days,netting_group,fx_rate_type,fx_date_rule,valid_from,valid_to" + NL);
        for (Agreement a : agreements) ag.append(a.csv()).append(NL);
        f.put("settlement_agreement/settlement_agreement.csv", ag.toString());
        StringBuilder fxs = new StringBuilder("rate_date,from_currency,to_currency,rate_type,rate,rate_source" + NL);
        for (Fx x : fx) fxs.append(String.join(",", x.from().toString(), x.fromCur(), x.toCur(), x.type(), x.rate().toPlainString(), "finance-test")).append(NL);
        f.put("fx_rate/fx_rate.csv", fxs.toString());
        f.put("holiday/holiday.csv", "country,holiday_date,name" + NL + String.join(NL, holidays) + NL);
        StringBuilder rr = new StringBuilder("agreement_id,direction,service,destination_class,usage_date,rate_id,rating_run_id,rated_at,units,amount_expected,currency,record_count,reason" + NL);
        for (Rerated r : rerated) rr.append(r.csv()).append(NL);
        f.put("rerated_usage/rerated_usage.csv", rr.toString());
        for (Map.Entry<String, List<Line>> s : statements.entrySet()) {
            StringBuilder b = new StringBuilder("statement_id,statement_version,line_no,partner_id,agreement_id,period_start,period_end,usage_date,direction,service,"
                    + "destination_class,units,amount,currency,fx_rate_applied,line_kind,control_total_amount,control_total_lines,received_at" + NL);
            for (Line l : s.getValue()) b.append(l.csv()).append(NL);
            f.put("settlement_statement/" + s.getKey(), b.toString());
        }
        return f;
    }
}
