package com.gamma.control;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;

/**
 * The deterministic generator of the {@code telco-bypass} Space Template's SYNTHETIC test corpus
 * ({@code TELCO-BYPASS-SIMBOX-1}, slice B1). Fixed seed, one fixed day, no real data: every id is fictional
 * (home subscribers {@code 99971xxxxxx}, home targets {@code 99972xxxxxx}, another national operator
 * {@code 99981xxxxxx}, foreign callers {@code 28700xxxxxx}, cells {@code Cnnnn}, IMEIs {@code 990000nnnnnnnnn},
 * IMSIs under the test network {@code 00101}).
 *
 * <p>Two populations, DISJOINT by construction (each archetype owns a number block):
 * <ul>
 *   <li><b>Planted</b> SIM-box-like SIMs ({@link #boxLike}): a classic box (P1, 60 SIMs on one cell and one IMEI),
 *       an IMEI-spoofing box (P3, a different IMEI on every call) and, per primary clause of the {@code box_like}
 *       predicate, one SIM just INSIDE it (the Job must flag it).</li>
 *   <li><b>Look-alikes</b> that must NOT trip the same predicate: a call centre (L1), an alarm line (L4), couriers
 *       (L5), a new heavy user (L6), a dense building (L7), a visiting roamer (L8), one SIM exactly AT each clause's
 *       threshold (the clauses are strict, so AT is silent), a slow-roll box under the volume floor (P2), a sequential
 *       dialler (P4), a cloned SIM (P5) and a shape no signal covers (P6).</li>
 *   <li><b>Pinned, not endorsed</b> ({@link #ambiguous}): a registered PBX (L2) and an unregistered dialler (L3)
 *       look exactly like a box from CDRs alone - the plan's irreducible case (7.9). Slice B2's allow list removes L2.</li>
 * </ul>
 * Background traffic (200 ordinary subscribers, 10 busy ones) stays far from every clause.
 */
final class BypassCorpus {

    static final long SEED = 20261020L;
    static final LocalDate DAY = LocalDate.of(2026, 7, 1);

    static final String HEADER = "record_id,start_ts,direction,service,a_number,b_number,duration_s,roaming,cell_id,"
            + "answered,release_cause,imei,imsi";
    static final int REQUIRED_COLUMNS = 9;

    private static final char NL = (char) 10;
    private static final DateTimeFormatter TS =DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** One feed row; the last four columns are the OPTIONAL ones (blank when a feed does not carry them). */
    record Call(String id, LocalDateTime ts, String direction, String service, String a, String b, int dur,
                String roaming, String cell, String answered, String cause, String imei, String imsi) {
        /** The row with all thirteen columns; blank optional ones when {@code optional} is false. */
        String csv(boolean optional) {
            return String.join(",", id, TS.format(ts), direction, service, a, b, String.valueOf(dur), roaming, cell,
                    optional ? answered : "", optional ? cause : "", optional ? imei : "", optional ? imsi : "");
        }

        /** The row with {@code answered} blank and the release cause kept (a feed that carries only the cause). */
        String csvCauseOnly() {
            return String.join(",", id, TS.format(ts), direction, service, a, b, String.valueOf(dur), roaming, cell,
                    "", cause, imei, imsi);
        }

        /** The row with only the nine REQUIRED columns (a feed that does not carry the optional ones at all). */
        String csvRequired() {
            return String.join(",", id, TS.format(ts), direction, service, a, b, String.valueOf(dur), roaming, cell);
        }
    }

    record Cell(String id, String site, double lat, double lon) {}

    record Profile(String msisdn, String plan, String dealer, LocalDate activatedOn, String corporate) {}

    private final Random rnd = new Random(SEED);
    private final List<Call> calls = new ArrayList<>();
    private final Map<String, Cell> cells = new LinkedHashMap<>();
    private final List<Profile> profiles = new ArrayList<>();
    private int seq;

    /** Archetype name to its SIMs. */
    final Map<String, Set<String>> archetypes = new LinkedHashMap<>();
    /** SIMs whose {@code box_like} must be TRUE: P1, P3 and every clause's JUST-INSIDE fixture. */
    final Set<String> boxLike = new TreeSet<>();
    /** Pinned, not endorsed: box-like from CDRs alone, so {@code box_like} is TRUE (L2 registered PBX, L3 dialler). */
    final Set<String> ambiguous = new TreeSet<>();
    /** The one row deliberately outside the day (midnight of the next day): the window excludes it. */
    final Set<String> outsideWindow = new TreeSet<>();
    /** SIMs with fewer MO voice calls than {@code min_events}: they get NO row in {@code tb_features}. */
    final Set<String> unscored = new TreeSet<>();

    // fixture clause names, in the order the golden and the runbook list them
    static final List<String> CLAUSES = List.of("inbound", "b_diversity", "short_calls", "voice_only", "static_cell");

    static String sim(int block, int i) { return String.format(Locale.ROOT, "99971%02d%04d", block, i); }

    static String atClause(String clause) { return "AT_" + clause; }

    static String insideClause(String clause) { return "INSIDE_" + clause; }

    BypassCorpus generate() {
        cells.put("CLN1", new Cell("CLN1", "SCLN1", 10.0, 20.0));
        cells.put("CLN2", new Cell("CLN2", "SCLN2", 17.2, 20.0));   // ~800 km north of CLN1
        planted();
        lookAlikes();
        clauseFixtures();
        background();
        calls.sort(Comparator.comparing(Call::ts).thenComparing(Call::id));
        return this;
    }

    // ------------------------------------------------------------------------------------------- planted

    private void planted() {
        // P1: one box, 60 consecutive SIMs, ONE cell, ONE IMEI, 90 attempts each, ~15 minutes apart round the clock,
        // 20-60 s answered calls (every 7th attempt unanswered), targets walking +1 with a jump every tenth call.
        Set<String> p1 = archetype("P1");
        int walk = 100000;
        for (int s = 0; s < 60; s++) {
            String m = sim(1, s);
            p1.add(m);
            Dev d = new Dev(device(1), imsi(1, s));
            String cell = cell(1).id();
            for (int k = 0; k < 90; k++) {
                walk += (k % 10 == 9) ? 1000 + rnd.nextInt(500) : 1;
                boolean ok = k % 7 != 6;
                mo(m, d, cell, home(walk), at(evenOffset(k, 90)), ok ? 20 + rnd.nextInt(41) : 0);
            }
            profile(m, "PREPAID", "D01", LocalDate.of(2026, 6, 25), "N");
        }
        boxLike.addAll(p1);

        // P2: slow-roll, 200 SIMs rotated through ONE IMEI, 12 calls each - under the volume floor, so unscored here
        // (slice B3's box Incident catches it through the cluster stage).
        Set<String> p2 = archetype("P2");
        for (int s = 0; s < 200; s++) {
            String m = sim(2, s);
            p2.add(m);
            Dev d = new Dev(device(2), imsi(2, s));
            for (int k = 0; k < 12; k++)
                mo(m, d, cell(2).id(), spread(k + s * 12), at(evenOffset(k, 12)), 20 + rnd.nextInt(41));
            profile(m, "PREPAID", "D03", LocalDate.of(2026, 6, 20), "N");
        }
        unscored.addAll(p2);

        // P3: the IMEI-spoofing box: as P1 but every call carries a different IMEI. 25 SIMs activated together, 20 callers.
        Set<String> p3 = archetype("P3");
        int walk3 = 600000;
        int imeiSeq = 0;
        for (int s = 0; s < 25; s++) {
            String m = sim(3, s);
            profile(m, "PREPAID", "D02", LocalDate.of(2026, 6, 26), "N");
            if (s >= 20) continue;   // five SIMs of the batch never call
            p3.add(m);
            for (int k = 0; k < 90; k++) {
                walk3 += (k % 10 == 9) ? 1000 + rnd.nextInt(500) : 1;
                mo(m, new Dev(device(30000 + imeiSeq++), imsi(3, s)), cell(3).id(), home(walk3), at(evenOffset(k, 90)),
                        20 + rnd.nextInt(41));
            }
        }
        boxLike.addAll(p3);

        // P4: a sequential dialler on another national operator: walks a list in order, calls of 90-240 s, some inbound.
        String p4 = sim(4, 1);
        archetype("P4").add(p4);
        Dev d4 = new Dev(device(4), imsi(4, 1));
        for (int k = 0; k < 60; k++)
            mo(p4, d4, cell(4).id(), "99981" + String.format(Locale.ROOT, "%06d", 200000 + k), at(evenOffset(k, 60)), 90 + rnd.nextInt(151));
        for (int k = 0; k < 10; k++) mt(p4, d4, cell(4).id(), foreign(k % 3), at(evenOffset(k, 10) + 123), 60 + rnd.nextInt(100));

        // P5: a cloned SIM: alternates two cells ~800 km apart every 30 minutes, two long calls that overlap the next one.
        String p5 = sim(4, 2);
        archetype("P5").add(p5);
        Dev d5 = new Dev(device(5), imsi(4, 2));
        for (int k = 0; k < 25; k++)
            mo(p5, d5, k % 2 == 0 ? "CLN1" : "CLN2", spread(7000 + k), LocalDateTime.of(DAY, java.time.LocalTime.of(8, 0)).plusMinutes(30L * k),
                    (k == 5 || k == 12) ? 3000 : 120 + rnd.nextInt(181));
        // edges of the overlap and speed rules: a call that starts exactly when the longest earlier call ends is NOT an
        // overlap, and two calls in the same second on the two cells take the one-second floor of the implied speed
        mo(p5, d5, "CLN2", spread(7100), LocalDateTime.of(DAY, java.time.LocalTime.of(14, 50)), 100);
        mo(p5, d5, "CLN1", spread(7101), LocalDateTime.of(DAY, java.time.LocalTime.of(22, 0)), 0);
        mo(p5, d5, "CLN2", spread(7102), LocalDateTime.of(DAY, java.time.LocalTime.of(22, 0)), 100);

        // P4b: number walking at the edge of the step: +5 (counted), +6 (not), a redial of the same number (not), and a
        // pair that differs by 3 across a 6-digit prefix (not).
        String p4b = sim(4, 4);
        archetype("P4b").add(p4b);
        Dev d4b = new Dev(device(7), imsi(4, 4));
        long number = 99981500000L;
        List<Long> walk4b = new ArrayList<>();
        for (int k = 0; k < 40; k++) {
            walk4b.add(number);
            number += k % 3 == 0 ? 5 : k % 3 == 1 ? 6 : 0;
        }
        walk4b.add(99981999998L);
        walk4b.add(99982000001L);
        for (int k = 0; k < walk4b.size(); k++)
            mo(p4b, d4b, cell(4).id(), String.valueOf(walk4b.get(k)), at(evenOffset(k, walk4b.size())), 90 + rnd.nextInt(151));

        // P6: a novel shape no S1-S12 clause covers: three numbers, called over and over, at night, long calls.
        String p6 = sim(4, 3);
        archetype("P6").add(p6);
        Dev d6 = new Dev(device(6), imsi(4, 3));
        for (int k = 0; k < 40; k++)
            mo(p6, d6, cell(5).id(), home(900000 + k % 3), LocalDateTime.of(DAY, java.time.LocalTime.of(3, 0)).plusMinutes(3L * k),
                    200 + rnd.nextInt(201));
        // the edge of "night": 04:59:59 is night, 05:00:00 is not (the end hour is exclusive)
        mo(p6, d6, cell(5).id(), home(900002), at(4 * 3600 + 59 * 60 + 59), 200);
        mo(p6, d6, cell(5).id(), home(900002), at(5 * 3600), 200);
        // a foreign number that merely CONTAINS an own prefix is not an own-network target (the prefix must lead)
        mo(p6, d6, cell(5).id(), "28799972000001", at(6 * 3600), 200);
    }

    // ------------------------------------------------------------------------------------------- look-alikes

    private void lookAlikes() {
        // L1: a call centre: 100 outbound, 100 inbound from many callers, 2-10 minute calls, one cell.
        Set<String> l1 = archetype("L1");
        for (int s = 0; s < 4; s++) {
            String m = sim(5, s);
            l1.add(m);
            Dev d = new Dev(device(50 + s), imsi(5, s));
            for (int k = 0; k < 100; k++) {
                mo(m, d, cell(6).id(), spread(100 * s + k), at(evenOffset(k, 100)), 120 + rnd.nextInt(481));
                mt(m, d, cell(6).id(), foreign(rnd.nextInt(90)), at(evenOffset(k, 100) + 211), 120 + rnd.nextInt(481));
            }
            profile(m, "CORPORATE", "D06", LocalDate.of(2025, 11, 3), "Y");
        }

        // L2 (registered PBX, slice B2's allow list) and L3 (unregistered dialler): box-like from CDRs alone.
        pbx("L2", 6, "D07");
        pbx("L3", 7, "D07");

        // L4: an alarm / M2M line: 30 short calls to three numbers, one cell, no inbound.
        Set<String> l4 = archetype("L4");
        for (int s = 0; s < 5; s++) {
            String m = sim(8, s);
            l4.add(m);
            Dev d = new Dev(device(80 + s), imsi(8, s));
            for (int k = 0; k < 30; k++)
                mo(m, d, cell(7).id(), home(950000 + 10 * s + k % 3), at(evenOffset(k, 30)), 5 + rnd.nextInt(11));
        }

        // L5: couriers: 60 distinct targets, short calls, voice only, no inbound - but moving across 12 neighbouring cells.
        Set<String> l5 = archetype("L5");
        for (int s = 0; s < 10; s++) {
            String m = sim(9, s);
            l5.add(m);
            Dev d = new Dev(device(90 + s), imsi(9, s));
            for (int k = 0; k < 60; k++)
                mo(m, d, cell(1000 + 50 * s + k / 5).id(), spread(2000 + 60 * s + k), at(evenOffset(k, 60)), 20 + rnd.nextInt(41));
            profile(m, "PREPAID", "D08", LocalDate.of(2026, 3, 9), "N");
        }

        // L6: a new heavy user: long calls, SMS, data, inbound; activated three days before the day.
        Set<String> l6 = archetype("L6");
        for (int s = 0; s < 3; s++) {
            String m = sim(10, s);
            l6.add(m);
            Dev d = new Dev(device(110 + s), imsi(10, s));
            String c = cell(8).id();
            for (int k = 0; k < 60; k++) mo(m, d, c, spread(3000 + 60 * s + k), at(evenOffset(k, 60)), 60 + rnd.nextInt(841));
            for (int k = 0; k < 20; k++) sms(m, d, c, spread(4000 + 20 * s + k), at(evenOffset(k, 20) + 301));
            for (int k = 0; k < 10; k++) data(m, d, c, at(evenOffset(k, 10) + 407), 600 + rnd.nextInt(3000));
            for (int k = 0; k < 30; k++) mt(m, d, c, foreign(rnd.nextInt(40)), at(evenOffset(k, 30) + 509), 60 + rnd.nextInt(300));
            for (int k = 0; k < 5; k++)   // received SMS: not "SMS sent"
                calls.add(new Call(id(), at(evenOffset(k, 5) + 611), "MT", "SMS", foreign(60 + k), m, 0, "N", c, "Y", "16", d.imei, d.imsi));
            profile(m, "PREPAID", "D09", LocalDate.of(2026, 6, 28), "N");
        }

        // L7: a dense building: 80 SIMs on ONE cell with an ordinary service mix.
        Set<String> l7 = archetype("L7");
        for (int s = 0; s < 80; s++) {
            String m = sim(11, s);
            l7.add(m);
            Dev d = new Dev(device(200 + s), imsi(11, s));
            String c = cell(9).id();
            int n = 22 + rnd.nextInt(9);
            for (int k = 0; k < n; k++) mo(m, d, c, home(700000 + 1000 * s + rnd.nextInt(15) * 11), at(evenOffset(k, n)), 30 + rnd.nextInt(571));
            for (int k = 0; k < 5; k++) sms(m, d, c, spread(5000 + 5 * s + k), at(evenOffset(k, 5) + 601));
            for (int k = 0; k < 3; k++) data(m, d, c, at(evenOffset(k, 3) + 701), 300 + rnd.nextInt(1500));
            for (int k = 0; k < 10; k++) mt(m, d, c, foreign(rnd.nextInt(30)), at(evenOffset(k, 10) + 809), 30 + rnd.nextInt(300));
            profile(m, "PREPAID", "D04", LocalDate.of(2026, 6, 24), "N");
            if (s == 0) {
                // the window edges: midnight of the day is INSIDE it, midnight of the next day is outside
                mo(m, d, c, spread(7200), at(0), 40);
                mo(m, d, c, spread(7201), at(86400), 40);
                outsideWindow.add(calls.get(calls.size() - 1).id());
            }
        }

        // L8: a visiting roamer: box-like in every other respect, but every call is roaming = Y.
        String l8 = sim(12, 0);
        archetype("L8").add(l8);
        unscored.add(l8);
        Dev d8 = new Dev(device(300), imsi(12, 0));
        for (int k = 0; k < 60; k++) {
            calls.add(new Call(id(), at(evenOffset(k, 60)), "MO", "VOICE", l8, spread(6000 + k), 20 + rnd.nextInt(41), "Y", "R0001",
                    "Y", "16", d8.imei, d8.imsi));
        }
    }

    private void pbx(String name, int block, String dealer) {
        Set<String> set = archetype(name);
        int walk = 400000 + block * 1000;
        for (int s = 0; s < 2; s++) {
            String m = sim(block, s);
            set.add(m);
            Dev d = new Dev(device(60 + 10 * block + s), imsi(block, s));
            for (int k = 0; k < 90; k++) {
                walk += (k % 10 == 9) ? 1000 + rnd.nextInt(500) : 1;
                mo(m, d, cell(10 + block).id(), home(walk), at(evenOffset(k, 90)), 20 + rnd.nextInt(41));
            }
            profile(m, "CORPORATE", dealer, LocalDate.of(2026, 5, 1), "Y");
        }
        ambiguous.addAll(set);
    }

    // ------------------------------------------------------------------------------------------- clause fixtures

    /**
     * One SIM exactly AT each clause's default threshold and one just INSIDE it (the clauses are strict: AT stays
     * silent, INSIDE is flagged), plus the volume floor (19 calls unscored, 20 scored and flagged). Every other
     * clause is met by a wide margin, so a single clause decides each SIM - which is what the mutation pass needs.
     */
    private void clauseFixtures() {
        // inbound_ratio < 0.05: 95 MO + 5 MT = 0.05 (AT); 96 MO + 4 MT = 0.04 (INSIDE)
        fixture("inbound", 1, 95, 95, 95, 0, 5, 0);
        fixture("inbound", 2, 96, 96, 96, 0, 4, 0);
        // b_diversity > 0.9: 90 distinct of 100 = 0.9 (AT); 91 of 100 (INSIDE)
        fixture("b_diversity", 1, 100, 90, 100, 0, 0, 0);
        fixture("b_diversity", 2, 100, 91, 100, 0, 0, 0);
        // short_call_ratio > 0.9: 90 of 100 answered calls at or under 60 s = 0.9 (AT); 91 (INSIDE)
        fixture("short_calls", 1, 100, 100, 90, 0, 0, 0);
        fixture("short_calls", 2, 100, 100, 91, 0, 0, 0);
        // voice_only_share > 0.99: 99 of 100 events are voice = 0.99 (AT); 199 of 200 = 0.995 (INSIDE)
        fixture("voice_only", 1, 99, 99, 99, 1, 0, 0);
        fixture("voice_only", 2, 199, 199, 199, 1, 0, 0);
        // top_cell_share > 0.9: 90 of 100 calls on the top cell = 0.9 (AT); 91 (INSIDE)
        fixture("static_cell", 1, 100, 100, 100, 0, 0, 10);
        fixture("static_cell", 2, 100, 100, 100, 0, 0, 9);
        // min_events: 19 calls get no row; 20 get a row and are flagged
        fixture("min_events", 1, 19, 19, 19, 0, 0, 0);
        fixture("min_events", 2, 20, 20, 20, 0, 0, 0);
    }

    /**
     * @param calls    MO voice calls
     * @param distinct distinct targets among them
     * @param shortN   calls of at most 60 s (the rest last exactly 61 s); one short call lasts exactly 60 s
     * @param smsN     MO SMS
     * @param mtN      MT voice calls from distinct callers
     * @param offCell  calls served by other, neighbouring cells (the rest by the SIM's own cell)
     */
    private void fixture(String clause, int variant, int calls, int distinct, int shortN, int smsN, int mtN, int offCell) {
        boolean inside = variant == 2;
        String name = (inside ? "INSIDE_" : "AT_") + clause;
        String m = sim(13, CLAUSE_INDEX.indexOf(clause) * 2 + variant);
        archetype(name).add(m);
        if (inside) boxLike.add(m);
        if ("min_events".equals(clause) && !inside) unscored.add(m);
        Dev d = new Dev(device(400 + CLAUSE_INDEX.indexOf(clause) * 2 + variant), imsi(13, CLAUSE_INDEX.indexOf(clause) * 2 + variant));
        int base = CLAUSE_INDEX.indexOf(clause) * 2 + variant;
        String home = cell(500 + 20 * base).id();
        int[] targets = new int[calls];
        for (int k = 0; k < calls; k++) targets[k] = k < distinct ? 300000 + 35000 * base + 170 * k + rnd.nextInt(100)
                : targets[k - distinct];
        for (int k = 0; k < calls; k++) {
            int dur = k < shortN ? (k == 0 ? 60 : 20 + rnd.nextInt(41)) : 61;
            // the off-cell calls are the LAST ones, each on its own neighbouring cell
            String c = k >= calls - offCell ? cell(500 + 20 * base + 1 + (k - (calls - offCell))).id() : home;
            mo(m, d, c, home(targets[k]), at(evenOffset(k, calls)), dur);
        }
        for (int k = 0; k < smsN; k++) sms(m, d, home, spread(8000 + k), at(evenOffset(k, Math.max(smsN, 1)) + 77));
        for (int k = 0; k < mtN; k++) mt(m, d, home, foreign(k), at(evenOffset(k, mtN) + 91), 90);
    }

    private static final List<String> CLAUSE_INDEX = List.of("inbound", "b_diversity", "short_calls", "voice_only", "static_cell", "min_events");

    // ------------------------------------------------------------------------------------------- background

    private void background() {
        Set<String> ordinary = archetype("BACKGROUND");
        for (int s = 0; s < 200; s++) {
            String m = sim(14, s);
            ordinary.add(m);
            Dev d = new Dev(device(1000 + s), imsi(14, s));
            String c = cell(30 + s % 30).id();
            int n = 2 + rnd.nextInt(5);
            for (int k = 0; k < n; k++) mo(m, d, c, spread(10000 + 50 * s + k), at(rnd.nextInt(86000)), 20 + rnd.nextInt(380));
            for (int k = 0, t = rnd.nextInt(4); k < t; k++) mt(m, d, c, foreign(rnd.nextInt(20)), at(rnd.nextInt(86000)), 20 + rnd.nextInt(300));
            for (int k = 0, t = rnd.nextInt(3); k < t; k++) sms(m, d, c, spread(20000 + 3 * s + k), at(rnd.nextInt(86000)));
            if (rnd.nextInt(3) == 0) data(m, d, c, at(rnd.nextInt(86000)), 100 + rnd.nextInt(2000));
            profile(m, rnd.nextBoolean() ? "PREPAID" : "POSTPAID", "D" + (20 + s % 20), LocalDate.of(2025, 1 + s % 12, 1 + s % 27), "N");
            unscored.add(m);
        }
        Set<String> busy = archetype("BUSY");
        for (int s = 0; s < 10; s++) {
            String m = sim(15, s);
            busy.add(m);
            Dev d = new Dev(device(1500 + s), imsi(15, s));
            int n = 22 + rnd.nextInt(17);
            for (int k = 0; k < n; k++) {
                int target = k % 4 == 3 ? 30000 + 100 * s : 31000 + 1000 * s + 13 * k;   // a quarter are repeats
                mo(m, d, cell(70 + 3 * s + k % 3).id(), home(target), at(evenOffset(k, n) + 33), 30 + rnd.nextInt(871));
            }
            for (int k = 0; k < 8; k++) sms(m, d, cell(70 + 3 * s).id(), spread(30000 + 9 * s + k), at(evenOffset(k, 8) + 99));
            for (int k = 0; k < 2; k++) data(m, d, cell(70 + 3 * s).id(), at(evenOffset(k, 2) + 55), 500 + rnd.nextInt(2000));
            for (int k = 0, t = 8 + rnd.nextInt(8); k < t; k++) mt(m, d, cell(70 + 3 * s).id(), foreign(rnd.nextInt(5)), at(evenOffset(k, t) + 143), 40 + rnd.nextInt(400));
            profile(m, "POSTPAID", "D" + (40 + s), LocalDate.of(2025, 6, 1 + s), "N");
        }
    }

    // ------------------------------------------------------------------------------------------- feed rows

    /** A device: the IMEI and IMSI a SIM reports on its rows. */
    private record Dev(String imei, String imsi) {}

    private Set<String> archetype(String name) { return archetypes.computeIfAbsent(name, k -> new TreeSet<>()); }

    private static String device(int n) { return String.format(Locale.ROOT, "990000%09d", n); }

    private static String imsi(int block, int i) { return String.format(Locale.ROOT, "00101%03d%07d", block, i); }

    private static String home(int n) {
        if (n < 0 || n >= 1_000_000) throw new IllegalArgumentException("target out of the 6-digit block: " + n);
        return String.format(Locale.ROOT, "99972%06d", n);
    }

    private static String foreign(int n) { return String.format(Locale.ROOT, "28700%06d", n); }

    /** A well-spread home target: consecutive values differ by far more than any sequential-dialling step. */
    private String spread(int n) { return home(n * 17 + rnd.nextInt(10)); }

    private String id() { return String.format(Locale.ROOT, "V%07d", ++seq); }

    private static LocalDateTime at(long secondOfDay) { return DAY.atStartOfDay().plusSeconds(secondOfDay); }

    /** The k-th of n events spread evenly over the day, with a small seeded jitter. */
    private long evenOffset(int k, int n) { return (long) (k * 86400.0 / n) + 7 + (k * 13 % 11); }

    private Cell cell(int i) {
        String id = String.format(Locale.ROOT, "C%04d", i);
        return cells.computeIfAbsent(id, k -> new Cell(k, String.format(Locale.ROOT, "S%04d", i / 3), (1050 + 2 * (i % 50)) / 100.0, (2050 + 2 * (i / 50)) / 100.0));
    }

    private void mo(String m, Dev d, String cell, String target, LocalDateTime ts, int dur) {
        String cause = dur > 0 ? "16" : (seq % 3 == 0 ? "17" : seq % 3 == 1 ? "19" : "21");
        calls.add(new Call(id(), ts, "MO", "VOICE", m, target, dur, "N", cell, dur > 0 ? "Y" : "N", cause, d.imei, d.imsi));
    }

    private void mt(String m, Dev d, String cell, String caller, LocalDateTime ts, int dur) {
        calls.add(new Call(id(), ts, "MT", "VOICE", caller, m, dur, "N", cell, "Y", "16", d.imei, d.imsi));
    }

    private void sms(String m, Dev d, String cell, String target, LocalDateTime ts) {
        calls.add(new Call(id(), ts, "MO", "SMS", m, target, 0, "N", cell, "Y", "16", d.imei, d.imsi));
    }

    private void data(String m, Dev d, String cell, LocalDateTime ts, int secs) {
        calls.add(new Call(id(), ts, "MO", "DATA", m, "internet", secs, "N", cell, "Y", "16", d.imei, d.imsi));
    }

    private void profile(String m, String plan, String dealer, LocalDate activated, String corporate) {
        profiles.add(new Profile(m, plan, dealer, activated, corporate));
    }

    // ------------------------------------------------------------------------------------------- outputs

    List<Call> calls() { return List.copyOf(calls); }

    Map<String, Cell> cells() { return Map.copyOf(cells); }

    List<Profile> profiles() { return List.copyOf(profiles); }

    /**
     * The CSV files the template's Pipelines ingest, keyed by path under the feed's inbox. {@code optionalColumns}
     * false keeps the full thirteen-column header but leaves {@code answered, release_cause, imei, imsi} BLANK;
     * {@link #filesRequiredOnly()} drops those columns from the file altogether.
     */
    Map<String, String> files(boolean optionalColumns) {
        StringBuilder cdr = new StringBuilder(HEADER).append(NL);
        for (Call c : calls) cdr.append(c.csv(optionalColumns)).append(NL);
        return withCdr(cdr.toString());
    }

    /** A feed with the release cause but no {@code answered} flag: the answer rate is read from the cause alone. */
    Map<String, String> filesCauseOnly() {
        StringBuilder cdr = new StringBuilder(HEADER).append(NL);
        for (Call c : calls) cdr.append(c.csvCauseOnly()).append(NL);
        return withCdr(cdr.toString());
    }

    /** A feed that does not carry the optional columns at all: a nine-column header and nine-field rows. */
    Map<String, String> filesRequiredOnly() {
        StringBuilder cdr = new StringBuilder(HEADER.substring(0, HEADER.indexOf(",answered"))).append(NL);
        for (Call c : calls) cdr.append(c.csvRequired()).append(NL);
        return withCdr(cdr.toString());
    }

    private Map<String, String> withCdr(String cdr) {
        Map<String, String> out = new LinkedHashMap<>();
        out.put("voice_cdr/VOICE_CDR_20260701.csv", cdr);
        out.put("cell_site/CELL_SITE_20260701.csv", cellSite());
        out.put("number_plan/NUMBER_PLAN_20260701.csv", numberPlan());
        out.put("subscriber_profile/SUBSCRIBER_PROFILE_20260701.csv", profile());
        return out;
    }

    String cellSite() {
        StringBuilder b = new StringBuilder("cell_id,site_id,lat,lon").append(NL);
        for (Cell c : new java.util.TreeMap<>(cells).values())
            b.append(c.id()).append(',').append(c.site()).append(',').append(c.lat()).append(',').append(c.lon()).append(NL);
        return b.toString();
    }

    static String numberPlan() {
        return "prefix,operator,own\n99971,HOME,Y\n99972,HOME,Y\n99981,OTHERNET,N\n287,FOREIGN,N\n";
    }

    String profile() {
        StringBuilder b = new StringBuilder("msisdn,plan,dealer_id,activated_on,corporate_flag\n");
        for (Profile p : profiles) b.append(String.join(",", p.msisdn(), p.plan(), p.dealer(), p.activatedOn().toString(), p.corporate())).append('\n');
        return b.toString();
    }

    /** Every SIM that owns at least one archetype slot. */
    Set<String> allPlanted() {
        Set<String> out = new LinkedHashSet<>();
        archetypes.values().forEach(out::addAll);
        return out;
    }
}
