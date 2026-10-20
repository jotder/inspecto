package com.gamma.control;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * The deterministic generator of the {@code telco-rerating} Space Template's SYNTHETIC test corpus
 * ({@code PACK-RERATING-1}, slice R1). Fixed seed, one fixed day, no real data: every plan, subscriber and id is
 * fictional, every rate and charge is a made-up number held as an exact decimal.
 *
 * <p>Two populations, disjoint by construction (each archetype owns a PLAN):
 * <ul>
 *   <li><b>Planted</b> defects, recorded in {@link #planted} as {@code ITEM_KEY -> REASON|FINDING|AMOUNT}: a stale
 *       tariff version (and the instant-of-change boundary), a charge that is wrong although the tariff has two rows
 *       with the SAME rate, charges against an overlap with DIFFERENT rates (inside the range, at its edges, outside
 *       it), precedence by an explicit {@code PRIORITY} (and a tie), a gap in the tariff, a plan with no tariff, a
 *       row with no rate, and a charge one cent either side of the tolerance. Tariff-table defects are recorded in
 *       {@link #plantedTariff}.</li>
 *   <li><b>Benign look-alikes</b> that must stay silent ({@link #benign}): a call that STARTS before a rate change and
 *       runs past it, a one-cent difference, a zero-rate plan, a row whose interval is inverted (never in force), two
 *       versions that meet exactly at the change instant, and the calls an overlap with the same rate makes
 *       "ambiguous" in the starter control.</li>
 * </ul>
 * Everything else is background traffic, charged exactly right.
 */
final class RerateCorpus {

    static final long SEED = 20261021L;
    static final LocalDate DAY = LocalDate.of(2026, 7, 1);
    static final LocalDateTime CHANGE = DAY.atTime(12, 0);

    static final String TARIFF_HEADER = "TARIFF_VERSION_ID,PLAN,SERVICE,RATE_PER_UNIT,EFFECTIVE_FROM,EFFECTIVE_TO,PRIORITY";
    static final String USAGE_HEADER = "XDR_ID,EVENT_TS,EVENT_DATE,SUBSCRIBER_ID,PLAN,SERVICE,USAGE_UNITS,CHARGE";
    static final String TARIFF_FILE = "tariff_version/TARIFF_VERSION_20260701.csv";
    static final String USAGE_FILE = "rated_usage/RATED_USAGE_20260701.csv";

    private static final char NL = (char) 10;
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** One tariff row; {@code rate} null = blank, {@code to} null = open, {@code priority} null = blank. */
    record Tariff(String id, String plan, String service, String rate, LocalDateTime from, LocalDateTime to, Integer priority) {
        String csv(boolean withPriority) {
            String base = String.join(",", id, plan, service, rate == null ? "" : rate, (from == null ? "" : TS.format(from)), to == null ? "" : TS.format(to));
            return withPriority ? base + "," + (priority == null ? "" : priority.toString()) : base;
        }
    }

    record Usage(String id, LocalDateTime ts, String subscriber, String plan, String service, long units, BigDecimal charge) {
        String csv() {
            return String.join(",", id, TS.format(ts), ts.toLocalDate().toString(), subscriber, plan, service,
                    String.valueOf(units), charge.setScale(4, RoundingMode.UNNECESSARY).toPlainString());
        }
    }

    private final Random rnd = new Random(SEED);
    private final List<Tariff> tariffs = new ArrayList<>();
    private final List<Usage> usage = new ArrayList<>();

    /** Archetype name to the ITEM_KEYs it owns. */
    final Map<String, Set<String>> archetypes = new TreeMap<>();
    /** ITEM_KEY of every planted call defect to {@code REASON|FINDING|AMOUNT} (the amount is the leakage or the over-billing, blank if none). */
    final Map<String, String> planted = new TreeMap<>();
    /** Planted tariff-table defects: {@code REASON|PLAN|SERVICE|TARIFF_VERSION_ID|OTHER_TARIFF_VERSION_ID} (blank other = none). */
    final Set<String> plantedTariff = new TreeSet<>();
    /** The look-alike calls a correct control must NOT report. */
    final Set<String> benign = new TreeSet<>();
    /** The two rows deliberately outside the day (one second before it, midnight after it): the window excludes them. */
    final Set<String> outsideWindow = new TreeSet<>();

    List<Tariff> tariffs() { return tariffs; }

    List<Usage> usage() { return usage; }

    /** The two feed files; {@code withPriority} false drops the optional PRIORITY column (a feed that does not carry it). */
    Map<String, String> files(boolean withPriority) {
        StringBuilder t = new StringBuilder(withPriority ? TARIFF_HEADER : TARIFF_HEADER.substring(0, TARIFF_HEADER.lastIndexOf(','))).append(NL);
        for (Tariff x : tariffs) t.append(x.csv(withPriority)).append(NL);
        StringBuilder u = new StringBuilder(USAGE_HEADER).append(NL);
        for (Usage x : usage) u.append(x.csv()).append(NL);
        Map<String, String> files = new LinkedHashMap<>();
        files.put(TARIFF_FILE, t.toString());
        files.put(USAGE_FILE, u.toString());
        return files;
    }

    RerateCorpus generate() {
        tariffs0();
        defects();
        background();
        usage.sort(Comparator.comparing(Usage::ts).thenComparing(Usage::id));
        return this;
    }

    // ------------------------------------------------------------------------------------------- the tariff table

    private static LocalDateTime at(String s) { return LocalDateTime.parse(s.replace(' ', 'T')); }

    private void rate(String id, String plan, String service, String rate, String from, String to, Integer priority) {
        tariffs.add(new Tariff(id, plan, service, rate, from == null ? null : at(from), to == null ? null : at(to), priority));
    }

    private void tariffs0() {
        // PLAN_A: VOICE changes rate at noon on the day; the two versions meet exactly at the change (no gap, no overlap)
        rate("A-V-1", "PLAN_A", "VOICE", "0.0020", "2026-01-01 00:00:00", "2026-07-01 12:00:00", null);
        rate("A-V-2", "PLAN_A", "VOICE", "0.0025", "2026-07-01 12:00:00", null, null);
        rate("A-S-1", "PLAN_A", "SMS", "0.0500", "2026-01-01 00:00:00", null, null);
        rate("A-D-1", "PLAN_A", "DATA", "0.0100", "2026-01-01 00:00:00", null, null);
        // PLAN_B: a duplicate DATA row with the SAME rate (the starter control flags every call on it)
        rate("B-V-1", "PLAN_B", "VOICE", "0.0015", "2026-01-01 00:00:00", null, null);
        rate("B-S-1", "PLAN_B", "SMS", "0.0400", "2026-01-01 00:00:00", null, null);
        rate("B-D-1", "PLAN_B", "DATA", "0.0080", "2026-01-01 00:00:00", null, null);
        rate("B-D-1B", "PLAN_B", "DATA", "0.0080", "2026-06-15 00:00:00", null, null);
        // PLAN_C: an overlap with DIFFERENT rates and no priority
        rate("C-V-1", "PLAN_C", "VOICE", "0.0020", "2026-01-01 00:00:00", null, null);
        rate("C-V-1X", "PLAN_C", "VOICE", "0.0030", "2026-06-20 00:00:00", null, null);
        rate("C-S-1", "PLAN_C", "SMS", "0.0500", "2026-01-01 00:00:00", null, null);
        rate("C-D-1", "PLAN_C", "DATA", "0.0100", "2026-01-01 00:00:00", null, null);
        // PLAN_D: an overlap resolved by an explicit PRIORITY (the higher number wins)
        rate("D-S-1", "PLAN_D", "SMS", "0.0500", "2026-01-01 00:00:00", null, 1);
        rate("D-S-2", "PLAN_D", "SMS", "0.0400", "2026-06-25 00:00:00", null, 5);
        rate("D-V-1", "PLAN_D", "VOICE", "0.0020", "2026-01-01 00:00:00", null, null);
        rate("D-D-1", "PLAN_D", "DATA", "0.0100", "2026-01-01 00:00:00", null, null);
        // PLAN_E: a PRIORITY tie (no winner), and an explicit priority against a blank one (the explicit one wins)
        rate("E-S-1", "PLAN_E", "SMS", "0.0500", "2026-01-01 00:00:00", null, 3);
        rate("E-S-2", "PLAN_E", "SMS", "0.0400", "2026-06-25 00:00:00", null, 3);
        rate("E-V-1", "PLAN_E", "VOICE", "0.0020", "2026-01-01 00:00:00", null, 7);
        rate("E-V-2", "PLAN_E", "VOICE", "0.0030", "2026-06-25 00:00:00", null, null);
        // PLAN_S: precedence where the winner has the HIGHER rate (an explicit priority is not "the cheaper row")
        rate("S-S-1", "PLAN_S", "SMS", "0.0600", "2026-01-01 00:00:00", null, 9);
        rate("S-S-2", "PLAN_S", "SMS", "0.0400", "2026-06-25 00:00:00", null, 2);
        // PLAN_F: a GAP on the day, 06:00 to 18:00 (the first version ends, the next starts later)
        rate("F-S-1", "PLAN_F", "SMS", "0.0500", "2026-01-01 00:00:00", "2026-07-01 06:00:00", null);
        rate("F-S-2", "PLAN_F", "SMS", "0.0500", "2026-07-01 18:00:00", null, null);
        // PLAN_G: a negative rate; PLAN_H: a zero rate (a free plan, legitimate)
        rate("G-D-1", "PLAN_G", "DATA", "-0.0100", "2026-01-01 00:00:00", null, null);
        rate("H-D-1", "PLAN_H", "DATA", "0.0000", "2026-01-01 00:00:00", null, null);
        // PLAN_I: an inverted interval (ends before it starts), never in force, beside a good open row
        rate("I-V-1", "PLAN_I", "VOICE", "0.0020", "2026-01-01 00:00:00", null, null);
        rate("I-V-X", "PLAN_I", "VOICE", "0.0090", "2026-07-05 00:00:00", "2026-07-01 00:00:00", null);
        // PLAN_L..R: tariff-table shapes with no calls on them. L: two closed rows overlapping (same rate); M: an open row and a closed one
        // (different rates); N: a closed row and an open one (same rate); O: an open row that outlives a closed one, then a later row (overlaps, NO gap);
        // P: a closed row and an inverted one after it (no gap: the inverted row is never in force); Q: a zero-length interval; R: three
        // versions, the first two meeting exactly, then a gap to the third
        rate("L-V-1", "PLAN_L", "VOICE", "0.0020", "2026-01-01 00:00:00", "2026-07-10 00:00:00", null);
        rate("L-V-2", "PLAN_L", "VOICE", "0.0020", "2026-06-20 00:00:00", "2026-08-01 00:00:00", null);
        rate("M-V-1", "PLAN_M", "VOICE", "0.0020", "2026-01-01 00:00:00", null, null);
        rate("M-V-2", "PLAN_M", "VOICE", "0.0030", "2026-06-20 00:00:00", "2026-07-20 00:00:00", null);
        rate("N-V-1", "PLAN_N", "VOICE", "0.0020", "2026-01-01 00:00:00", "2026-07-10 00:00:00", null);
        rate("N-V-2", "PLAN_N", "VOICE", "0.0020", "2026-06-20 00:00:00", null, null);
        rate("O-V-1", "PLAN_O", "VOICE", "0.0020", "2026-01-01 00:00:00", "2026-03-01 00:00:00", null);
        rate("O-V-2", "PLAN_O", "VOICE", "0.0020", "2026-02-01 00:00:00", null, null);
        rate("O-V-3", "PLAN_O", "VOICE", "0.0020", "2026-05-01 00:00:00", null, null);
        rate("P-V-1", "PLAN_P", "VOICE", "0.0020", "2026-01-01 00:00:00", "2026-03-01 00:00:00", null);
        rate("P-V-X", "PLAN_P", "VOICE", "0.0020", "2026-06-01 00:00:00", "2026-05-01 00:00:00", null);
        rate("Q-V-1", "PLAN_Q", "VOICE", "0.0020", "2026-03-01 00:00:00", "2026-03-01 00:00:00", null);
        rate("R-V-1", "PLAN_R", "VOICE", "0.0020", "2026-01-01 00:00:00", "2026-02-01 00:00:00", null);
        rate("R-V-2", "PLAN_R", "VOICE", "0.0020", "2026-02-01 00:00:00", "2026-03-01 00:00:00", null);
        rate("R-V-3", "PLAN_R", "VOICE", "0.0020", "2026-05-01 00:00:00", null, null);
        // PLAN_T: a row with no start (never in force, reported on its own)
        rate("T-V-1", "PLAN_T", "VOICE", "0.0020", null, null, null);
        // PLAN_J: a single clean row for the tolerance boundary; PLAN_K: a row with no rate; PLAN_Z has no row at all
        rate("J-V-1", "PLAN_J", "VOICE", "0.0020", "2026-01-01 00:00:00", null, null);
        rate("K-V-1", "PLAN_K", "VOICE", null, "2026-01-01 00:00:00", null, null);
    }

    // ------------------------------------------------------------------------------------------- the calls

    private int seq;

    private static BigDecimal money(String s) { return new BigDecimal(s); }

    /** The exact expected charge for {@code units} at {@code rate}: two decimals, half away from zero. */
    static BigDecimal expected(long units, String rate) {
        return BigDecimal.valueOf(units).multiply(new BigDecimal(rate)).setScale(2, RoundingMode.HALF_UP);
    }

    /** Add one call; {@code outcome} is {@code REASON|FINDING|AMOUNT} for a planted defect, null for a silent one. */
    private String call(String archetype, String idSuffix, String ts, String plan, String service, long units, String charge, String outcome, boolean look) {
        String id = "X" + archetype + "-" + idSuffix;
        usage.add(new Usage(id, at(ts), String.format(Locale.ROOT, "SUB%04d", 1 + (seq++ * 7) % 40), plan, service, units, money(charge)));
        archetypes.computeIfAbsent(archetype, k -> new TreeSet<>()).add(id);
        if (outcome != null) planted.put(id, outcome);
        if (look) benign.add(id);
        return id;
    }

    private static final String UNDER = "rate_mismatch|leakage|";
    private static final String OVER = "rate_mismatch|overbilled|";
    private static final String RANGE = "ambiguous_tariff|data_quality|";
    private static final String NOTARIFF = "no_tariff|data_quality|";

    private void defects() {
        // A: the tariff change inside the day
        String[] stale = {"13:10:00", "14:20:00", "15:30:00", "16:40:00", "17:50:00"};
        for (int i = 0; i < stale.length; i++)
            call("A_STALE", String.valueOf(i + 1), "2026-07-01 " + stale[i], "PLAN_A", "VOICE", 600, "1.2000", UNDER + "0.30", false);   // expected 1.50 at the new rate
        call("A_AT_CHANGE_NEW", "1", "2026-07-01 12:00:00", "PLAN_A", "VOICE", 400, "1.0000", null, true);              // FROM is inclusive: the new rate
        call("A_AT_CHANGE_STALE", "1", "2026-07-01 12:00:00", "PLAN_A", "VOICE", 400, "0.8000", UNDER + "0.20", false);
        call("A_JUST_BEFORE", "1", "2026-07-01 11:59:59", "PLAN_A", "VOICE", 400, "0.8000", null, true);                // the old rate is right a second earlier
        call("A_SPANNING", "1", "2026-07-01 11:59:30", "PLAN_A", "VOICE", 300, "0.6000", null, true);                   // starts before the change, runs past it: rated at the START
        call("A_EARLY_NEW", "1", "2026-07-01 11:00:00", "PLAN_A", "VOICE", 400, "1.0000", OVER + "0.20", false);        // the new rate billed an hour early

        // B: two rows with the SAME rate: every call is judged against the one agreed amount
        long[] bUnits = {50, 60, 70, 80, 90, 100, 110, 120, 130, 140, 150, 170};
        for (int i = 0; i < bUnits.length; i++) {
            String hh = String.format(Locale.ROOT, "%02d:%02d:00", 8 + i, 5 * i);
            call("B_AGREE", String.valueOf(i + 1), "2026-07-01 " + hh, "PLAN_B", "DATA", bUnits[i], expected(bUnits[i], "0.0080").toPlainString() + "00", null, true);
        }
        call("B_AGREE_LEAK", "1", "2026-07-01 20:00:00", "PLAN_B", "DATA", 500, "3.0000", UNDER + "1.00", false);       // 4.00 expected, 3.00 charged

        // C: two rows with DIFFERENT rates and no priority: the expected charge is the range [2.00, 3.00]
        String[][] c = {{"1", "2.0000", RANGE}, {"2", "3.0000", RANGE}, {"3", "2.5000", RANGE}, {"4", "1.9800", RANGE},
                {"5", "1.9700", UNDER + "0.03"}, {"6", "3.0200", RANGE}, {"7", "3.0300", OVER + "0.03"}, {"8", "0.5000", UNDER + "1.50"}};
        for (String[] r : c) call("C_RANGE", r[0], "2026-07-01 09:" + String.format(Locale.ROOT, "%02d", Integer.parseInt(r[0]) * 5) + ":00", "PLAN_C", "VOICE", 1000, r[1], r[2], false);

        // C again: a REVERSAL (negative units, negative charge) is re-rated like any call: the range is [-3.00, -2.00]
        call("C_REVERSAL", "1", "2026-07-01 09:50:00", "PLAN_C", "VOICE", -1000, "-2.5000", RANGE, false);

        // D: an explicit PRIORITY decides (the winner charges 4.00 for 100 events)
        call("D_PRECEDENCE", "1", "2026-07-01 10:00:00", "PLAN_D", "SMS", 100, "4.0000", null, true);
        call("D_PRECEDENCE", "2", "2026-07-01 10:01:00", "PLAN_D", "SMS", 100, "5.0000", OVER + "1.00", false);          // billed at the lower-priority row's rate
        call("D_PRECEDENCE", "3", "2026-07-01 10:02:00", "PLAN_D", "SMS", 100, "3.0000", UNDER + "1.00", false);
        call("D_PRECEDENCE", "4", "2026-07-01 10:03:00", "PLAN_D", "SMS", 100, "4.0200", null, true);                   // exactly AT the tolerance

        // S: the winner is the dearer row (6.00 for 100 events); billing the cheaper row is a shortfall of 2.00
        call("S_DEARER_WINNER", "1", "2026-07-01 10:30:00", "PLAN_S", "SMS", 100, "6.0000", null, true);
        call("S_DEARER_WINNER", "2", "2026-07-01 10:31:00", "PLAN_S", "SMS", 100, "4.0000", UNDER + "2.00", false);

        // E: a priority TIE has no winner (range [4.00, 5.00]); an explicit priority beats a blank one
        call("E_TIE", "1", "2026-07-01 10:10:00", "PLAN_E", "SMS", 100, "4.5000", RANGE, false);
        call("E_TIE", "2", "2026-07-01 10:11:00", "PLAN_E", "SMS", 100, "3.0000", UNDER + "1.00", false);
        call("E_BLANK_PRIORITY", "1", "2026-07-01 10:20:00", "PLAN_E", "VOICE", 1000, "2.0000", null, true);
        call("E_BLANK_PRIORITY", "2", "2026-07-01 10:21:00", "PLAN_E", "VOICE", 1000, "3.0000", OVER + "1.00", false);
        call("E_BLANK_PRIORITY", "3", "2026-07-01 10:22:00", "PLAN_E", "VOICE", 1234, "2.4700", null, true);          // 1234 x 0.0020 = 2.468: rounded to two decimals, 2.47

        // F: the gap, 06:00 (first second without a row) to 18:00 (first second with the next row)
        call("F_GAP", "1", "2026-07-01 06:00:00", "PLAN_F", "SMS", 10, "0.5000", NOTARIFF, false);
        call("F_GAP", "2", "2026-07-01 10:00:00", "PLAN_F", "SMS", 10, "0.5000", NOTARIFF, false);
        call("F_GAP", "3", "2026-07-01 12:00:00", "PLAN_F", "SMS", 10, "0.5000", NOTARIFF, false);
        call("F_GAP", "4", "2026-07-01 17:59:59", "PLAN_F", "SMS", 10, "0.5000", NOTARIFF, false);
        call("F_EDGE", "1", "2026-07-01 05:59:59", "PLAN_F", "SMS", 10, "0.5000", null, true);
        call("F_EDGE", "2", "2026-07-01 18:00:00", "PLAN_F", "SMS", 10, "0.5000", null, true);

        // Z: no tariff row for the plan at all; K: its only row has no rate
        for (int i = 1; i <= 3; i++) call("Z_NO_TARIFF", String.valueOf(i), "2026-07-01 11:" + String.format(Locale.ROOT, "%02d", i) + ":00", "PLAN_Z", "VOICE", 100, "0.2000", NOTARIFF, false);
        call("K_NO_RATE", "1", "2026-07-01 11:10:00", "PLAN_K", "VOICE", 100, "0.2000", NOTARIFF, false);

        call("T_NO_START", "1", "2026-07-01 11:11:00", "PLAN_T", "VOICE", 100, "0.2000", NOTARIFF, false);

        // H: a zero rate charged zero is right; I: the inverted row is never in force
        for (int i = 1; i <= 3; i++) call("H_ZERO", String.valueOf(i), "2026-07-01 13:" + String.format(Locale.ROOT, "%02d", i) + ":00", "PLAN_H", "DATA", 100, "0.0000", null, true);
        for (int i = 1; i <= 4; i++) call("I_INVERTED", String.valueOf(i), "2026-07-01 14:" + String.format(Locale.ROOT, "%02d", i) + ":00", "PLAN_I", "VOICE", 500, "1.0000", null, true);

        // J: the tolerance (0.02, strict): a cent either way, AT it, and just outside it
        String[][] j = {{"1", "2.0100", null}, {"2", "1.9900", null}, {"3", "2.0200", null}, {"4", "1.9800", null},
                {"5", "1.9700", UNDER + "0.03"}, {"6", "2.0300", OVER + "0.03"}, {"7", "4.0000", OVER + "2.00"}, {"8", "0.0000", UNDER + "2.00"}};
        for (String[] r : j) call("J_TOLERANCE", r[0], "2026-07-01 15:0" + r[0] + ":00", "PLAN_J", "VOICE", 1000, r[1], r[2], r[2] == null);

        // the window's own edges: the first second and the last second of the day belong to it
        call("EDGE_START", "1", "2026-07-01 00:00:00", "PLAN_A", "SMS", 10, "0.5000", null, true);
        call("EDGE_END", "1", "2026-07-01 23:59:59", "PLAN_A", "SMS", 10, "0.5000", null, true);
        // the window: one second before the day and midnight after it are not this window's calls
        outsideWindow.add(call("OUT", "BEFORE", "2026-06-30 23:59:59", "PLAN_A", "SMS", 10, "0.5000", null, false));
        outsideWindow.add(call("OUT", "AFTER", "2026-07-02 00:00:00", "PLAN_A", "SMS", 10, "0.1000", null, false));      // would be a leak: it is not in the window

        // the tariff table's own defects
        plantedTariff.add("overlap_same_rate|PLAN_B|DATA|B-D-1|B-D-1B");
        plantedTariff.add("overlap_different_rate|PLAN_C|VOICE|C-V-1|C-V-1X");
        plantedTariff.add("overlap_different_rate|PLAN_D|SMS|D-S-1|D-S-2");
        plantedTariff.add("overlap_different_rate|PLAN_E|SMS|E-S-1|E-S-2");
        plantedTariff.add("overlap_different_rate|PLAN_S|SMS|S-S-1|S-S-2");
        plantedTariff.add("overlap_different_rate|PLAN_E|VOICE|E-V-1|E-V-2");
        plantedTariff.add("gap|PLAN_F|SMS|F-S-2|F-S-1");
        plantedTariff.add("negative_rate|PLAN_G|DATA|G-D-1|");
        plantedTariff.add("missing_rate|PLAN_K|VOICE|K-V-1|");
        plantedTariff.add("missing_start|PLAN_T|VOICE|T-V-1|");
        plantedTariff.add("overlap_same_rate|PLAN_L|VOICE|L-V-1|L-V-2");
        plantedTariff.add("overlap_different_rate|PLAN_M|VOICE|M-V-1|M-V-2");
        plantedTariff.add("overlap_same_rate|PLAN_N|VOICE|N-V-1|N-V-2");
        plantedTariff.add("overlap_same_rate|PLAN_O|VOICE|O-V-1|O-V-2");
        plantedTariff.add("overlap_same_rate|PLAN_O|VOICE|O-V-2|O-V-3");
        plantedTariff.add("inverted_interval|PLAN_P|VOICE|P-V-X|");
        plantedTariff.add("inverted_interval|PLAN_Q|VOICE|Q-V-1|");
        plantedTariff.add("gap|PLAN_R|VOICE|R-V-3|R-V-2");
        plantedTariff.add("inverted_interval|PLAN_I|VOICE|I-V-X|");
    }

    // ------------------------------------------------------------------------------------------- background

    /** Four hundred ordinary calls on plans whose tariff is a single clean row at the call's start, all charged exactly right. */
    private void background() {
        String[][] pool = {{"PLAN_A", "SMS", "0.0500"}, {"PLAN_A", "DATA", "0.0100"}, {"PLAN_B", "VOICE", "0.0015"}, {"PLAN_B", "SMS", "0.0400"},
                {"PLAN_C", "SMS", "0.0500"}, {"PLAN_C", "DATA", "0.0100"}, {"PLAN_D", "VOICE", "0.0020"}, {"PLAN_D", "DATA", "0.0100"}};
        for (int i = 0; i < 400; i++) {
            String[] p = pool[rnd.nextInt(pool.length)];
            long units = switch (p[1]) { case "VOICE" -> 20 + rnd.nextInt(880); case "SMS" -> 1 + rnd.nextInt(5); default -> 1 + rnd.nextInt(200); };
            LocalDateTime ts = DAY.atStartOfDay().plusSeconds(rnd.nextInt(86400));
            call("BG", String.format(Locale.ROOT, "%04d", i), TS.format(ts), p[0], p[1], units, expected(units, p[2]).toPlainString() + "00", null, false);
        }
        // PLAN_A VOICE background, kept clear of the change instant: the old rate before 11:30, the new one after 12:30
        for (int i = 0; i < 60; i++) {
            boolean early = i % 2 == 0;
            LocalDateTime ts = early ? DAY.atStartOfDay().plusSeconds(rnd.nextInt(11 * 3600 + 1800)) : CHANGE.plusSeconds(1800 + rnd.nextInt(11 * 3600 + 1800));
            long units = 20 + rnd.nextInt(880);
            call("BGA", String.format(Locale.ROOT, "%04d", i), TS.format(ts), "PLAN_A", "VOICE", units, expected(units, early ? "0.0020" : "0.0025").toPlainString() + "00", null, false);
        }
    }

    @Override
    public String toString() { return usage.size() + " usage rows, " + tariffs.size() + " tariff rows"; }
}
