package com.gamma.control;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * The deterministic generator of the {@code telco-fraud} Space Template's SYNTHETIC golden corpus
 * ({@code ASSURE-PACK-TELCO-FRAUD-1}). Fixed seed, fixed day, no real data. Every number is fictional: the home
 * network is country code {@code 999} (reserved in E.164, never assigned), foreign callers use the spare
 * codes {@code 287} / {@code 289}, and the high-risk ranges are the international network codes an IRSF list
 * names ({@code 882}, {@code 883}, {@code 8818}) with made-up subscriber digits.
 *
 * <p>Number formats: subscriber numbers ({@code a_number} of MO, {@code b_number} of MT) are bare E.164 digits;
 * a dialled number may be {@code +<E.164>}, {@code 00<E.164>}, national {@code 0<NSN>} or bare E.164 — the
 * detection Jobs normalise all four to one format.
 *
 * <p>Background traffic (200 subscribers) comes from the seeded {@link Random} and stays far under every
 * threshold. Each typology gets planted offenders ({@link #offenders}) — including one JUST above the
 * threshold — and planted look-alikes ({@link #lookAlikes}) that must stay silent: one exactly AT the
 * threshold (the rules are {@code gt}) and the realistic benign patterns. {@code TelcoFraudTemplateGoldenTest}
 * asserts the committed samples are byte-identical to this output; regenerate with the environment variable
 * {@code TELCO_FRAUD_REGENERATE=true}.
 */
final class TelcoFraudCorpus {

    static final long SEED = 20260930L;
    static final String CDR_FILE = "cdr/CDR_20260701.csv";
    static final String EVENTS_FILE = "subscriber_events/SUBSCRIBER_EVENTS_20260701.csv";
    static final String PAYMENTS_FILE = "payments/PAYMENTS_20260701.csv";

    private static final LocalDateTime DAY = LocalDateTime.of(2026, 7, 1, 0, 0);
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final Random rnd = new Random(SEED);
    private final StringBuilder cdr = new StringBuilder("record_id,start_ts,direction,service,a_number,b_number,duration_s,charge,roaming,cell_id\n");
    private final StringBuilder events = new StringBuilder("event_id,event_ts,event_type,msisdn,dealer_id,id_doc\n");
    private final StringBuilder payments = new StringBuilder("txn_id,txn_ts,txn_type,msisdn,amount,voucher_serial,ref_txn_id,agent_id\n");
    private int cdrSeq, eventSeq, txnSeq;

    /** Alert Rule name → the keys it must raise (exactly these). */
    final Map<String, Set<String>> offenders = new LinkedHashMap<>();
    /** Alert Rule name → planted look-alike keys that must NOT be raised by any rule. */
    final Map<String, Set<String>> lookAlikes = new LinkedHashMap<>();
    /** Alert Rule name → keys that SHOULD alert but are exempted by a trust assumption — pinned, not endorsed. */
    final Map<String, Set<String>> knownRiskExempted = new LinkedHashMap<>();

    static String home(int i) { return f("99970%06d", i); }

    private static String f(String fmt, Object... args) { return String.format(Locale.ROOT, fmt, args); }

    private static String ts(LocalDateTime t) { return TS.format(t); }

    private LocalDateTime at(int hour, int minute) { return DAY.plusHours(hour).plusMinutes(minute); }

    private LocalDateTime randomTime() { return DAY.plusSeconds(rnd.nextInt(86_000)); }

    private void call(LocalDateTime t, String dir, String service, String a, String b, int seconds, double charge,
                      boolean roaming, String cell) {
        cdr.append(f("R%07d,%s,%s,%s,%s,%s,%d,%.2f,%s,%s", ++cdrSeq, ts(t), dir, service, a, b, seconds,
                charge, roaming ? "Y" : "N", cell)).append('\n');
    }

    private void event(LocalDateTime t, String type, String msisdn, String dealer, String doc) {
        events.append(f("E%07d,%s,%s,%s,%s,%s", ++eventSeq, ts(t), type, msisdn, dealer, doc)).append('\n');
    }

    private String txn(LocalDateTime t, String type, String msisdn, double amount, String serial, String ref) {
        return txn(t, type, msisdn, amount, serial, ref, "");
    }

    private String txn(LocalDateTime t, String type, String msisdn, double amount, String serial, String ref, String agent) {
        String id = f("T%07d", ++txnSeq);
        payments.append(f("%s,%s,%s,%s,%.2f,%s,%s,%s", id, ts(t), type, msisdn, amount, serial, ref, agent)).append('\n');
        return id;
    }

    private void plant(String rule, Set<String> keys, Set<String> alikes) {
        offenders.put(rule, keys);
        lookAlikes.put(rule, alikes);
    }

    private static Set<String> homes(int... ids) {
        Set<String> s = new LinkedHashSet<>();
        for (int i : ids) s.add(home(i));
        return s;
    }

    private static Set<String> keys(String... k) { return new LinkedHashSet<>(List.of(k)); }

    /** A national-format on-net number (normalises to {@code 99971…}). */
    private String domestic() { return f("071%06d", rnd.nextInt(1_000_000)); }

    /** {@code n} MO voice calls of {@code secs} each from {@code a} to {@code b + c}. */
    private void calls(int n, int hour, String a, String bPrefix, long bBase, int secs, double charge, boolean roaming, String cell) {
        for (int c = 0; c < n; c++) call(at(hour, c % 60), "MO", "VOICE", a, bPrefix + (bBase + c), secs, charge, roaming, cell);
    }

    /** {@code n} roaming MO calls, all to five home numbers (few targets, so no SIM-box look). */
    private void roam(int n, int hour, String a, double charge, String cell) {
        for (int c = 0; c < n; c++) call(at(hour, c % 60), "MO", "VOICE", a, home(1 + c % 5), 120, charge, true, cell);
    }

    TelcoFraudCorpus generate() {
        // ---- background: 200 subscribers, activated by 5 busy dealers, each with ordinary traffic
        for (int i = 1; i <= 200; i++) {
            String m = home(i);
            event(randomTime(), "ACTIVATION", m, f("D%02d", 1 + (i - 1) / 40), f("DOC-%06d", i));
            int cells = 1 + rnd.nextInt(3);
            for (int c = 0, n = 3 + rnd.nextInt(6); c < n; c++) {
                int secs = 20 + rnd.nextInt(580);
                call(randomTime(), "MO", "VOICE", m, domestic(), secs, secs / 600.0, false, "C" + (100 + (i + c % cells) % 50));
            }
            if (rnd.nextBoolean()) call(randomTime(), "MO", "SMS", m, domestic(), 0, 0.05, false, "C" + (100 + i % 50));
            for (int c = 0, n = 1 + rnd.nextInt(3); c < n; c++)   // every background line also RECEIVES calls
                call(randomTime(), "MT", "VOICE", home(1 + rnd.nextInt(200)), m, 30 + rnd.nextInt(270), 0, false, "C" + (100 + i % 50));
            txn(randomTime(), "VOUCHER", m, 10, f("V%08d", i), "");
            txn(randomTime(), "PAYMENT", m, 20 + rnd.nextInt(60), "", "");
        }

        // ---- 1 IRSF (list 882,883,8818; gt 3600 s) — every dialled format normalises to one
        calls(10, 2, home(1001), "+882", 70001000, 600, 9, false, "C200");
        calls(10, 2, home(1002), "00882", 70002000, 600, 9, false, "C200");
        calls(10, 2, home(1003), "+8818", 7003000, 600, 9, false, "C200");   // a 4-digit list entry
        calls(10, 2, home(1004), "883", 70004000, 600, 9, false, "C200");    // bare E.164
        calls(6, 2, home(1005), "+883", 70005000, 600, 9, false, "C200");    // JUST above: 3601
        call(at(2, 30), "MO", "VOICE", home(1005), "+88370005999", 1, 0.1, false, "C200");
        calls(6, 3, home(1011), "+882", 70011000, 600, 9, false, "C201");    // AT the threshold: 3600
        calls(2, 3, home(1012), "+883", 70012000, 300, 4.5, false, "C201");  // a little to a listed range
        calls(14, 4, home(1013), "+8811", 7013000, 600, 3, false, "C202");   // an unlisted neighbour of 8818
        calls(14, 4, home(1014), "0882", 7014000, 600, 3, false, "C202");    // NATIONAL 0882… is on-net, not 882
        calls(14, 4, home(1015), "+287", 70015000, 600, 3, false, "C202");   // an ordinary international range
        plant("fraud_irsf", homes(1001, 1002, 1003, 1004, 1005), homes(1011, 1012, 1013, 1014, 1015));

        // ---- 2 Wangiri (gt 3 targets calling back within 24 h of a <=3 s ring)
        Set<String> wangiri = new LinkedHashSet<>(), wangiriAlikes = new LinkedHashSet<>();
        wangiri(1, 40, 5, 10, 1, wangiri);
        wangiri(2, 40, 5, 10, 1, wangiri);
        wangiri(3, 40, 5, 10, 1, wangiri);
        wangiri(4, 30, 4, 10, 1, wangiri);          // JUST above: 4 callbacks
        wangiri(5, 30, 3, 10, 1, wangiriAlikes);    // AT the threshold: 3 callbacks
        wangiri(6, 40, 0, 10, 1, wangiriAlikes);    // flash-call OTP sender: rings, nobody calls back
        wangiri(7, 20, 10, -30, 1, wangiriAlikes);  // the "callbacks" came BEFORE the ring
        wangiri(8, 20, 10, 10, 120, wangiriAlikes); // a call centre: real conversations, not rings
        wangiri(9, 20, 10, 30 * 60, 1, wangiriAlikes); // callbacks 30 h later, outside callback_hours
        plant("fraud_wangiri", wangiri, wangiriAlikes);

        // ---- 3 SIM-box (gt 50 distinct targets; never receives, never texts, one cell; exempt_msisdns)
        for (int i : new int[] {1101, 1102, 1103}) calls(80, 8, home(i), "072", 1000000L + i * 100, 90, 0.15, false, "C400");
        calls(51, 8, home(1104), "072", 1110400, 90, 0.15, false, "C400");   // JUST above: 51
        for (int i : new int[] {1111, 1112}) {                                 // as busy, but receives calls
            calls(80, 9, home(i), "073", 1000000L + i * 100, 90, 0.15, false, "C410");
            for (int c = 0; c < 5; c++) call(at(10, c), "MT", "VOICE", home(c + 1), home(i), 60, 0, false, "C410");
        }
        for (int c = 0; c < 80; c++)                                           // as busy, but moves across 4 cells
            call(at(9, c % 60), "MO", "VOICE", home(1113), "073" + (1111300 + c), 90, 0.15, false, "C40" + (c % 4));
        calls(50, 9, home(1114), "073", 1111400, 90, 0.15, false, "C411");   // AT the threshold: 50
        calls(80, 9, home(1119), "073", 1111900, 90, 0.15, false, "C412");   // a registered PBX / dialler line
        plant("fraud_simbox", homes(1101, 1102, 1103, 1104), homes(1111, 1112, 1113, 1114, 1119));

        // ---- 4 Premium-rate (list 999900,999909 in E.164; gt 100)
        for (int i : new int[] {1201, 1202, 1203, 1204}) calls(30, 11, home(i), "0900", 1000000, 60, 5, false, "C500");
        calls(20, 11, home(1205), "+999909", 1000000, 60, 5, false, "C500"); // JUST above: 100.01
        call(at(11, 40), "MO", "VOICE", home(1205), "00999909100099", 1, 0.01, false, "C500");
        calls(20, 12, home(1211), "0909", 1000000, 60, 5, false, "C501");    // AT the threshold: 100
        for (int i : new int[] {1212, 1213}) calls(10, 12, home(i), "0909", 1000000, 60, 5, false, "C501");
        plant("fraud_premium_rate", homes(1201, 1202, 1203, 1204, 1205), homes(1211, 1212, 1213));

        // ---- 5 Roaming high usage (gt 500)
        for (int i : new int[] {1301, 1302, 1303}) roam(60, 13, home(i), 12, "RX1");
        roam(50, 13, home(1304), 10, "RX1");   // JUST above: 500.01
        call(at(13, 55), "MO", "VOICE", home(1304), home(2), 1, 0.01, true, "RX1");
        roam(50, 14, home(1311), 10, "RX2");   // AT the threshold: 500
        roam(45, 14, home(1312), 10, "RX2");   // a business traveller: 450
        roam(20, 14, home(1313), 10, "RX2");
        plant("fraud_roaming", homes(1301, 1302, 1303, 1304), homes(1311, 1312, 1313));

        // ---- 6 SIM-swap (payments within 24 h after the LATEST swap, each counted once; gt 200)
        for (int i : new int[] {1401, 1402, 1403}) { event(at(9, 0), "SIM_SWAP", home(i), "", ""); txn(at(10, 0), "PAYMENT", home(i), 300, "", ""); }
        event(at(9, 0), "SIM_SWAP", home(1404), "", "");  txn(at(10, 0), "PAYMENT", home(1404), 200.01, "", "");  // JUST above
        event(at(9, 0), "SIM_SWAP", home(1411), "", "");                                                           // swap, no payment
        event(at(9, 0), "SIM_SWAP", home(1412), "", "");  txn(at(10, 0), "PAYMENT", home(1412), 200, "", "");     // AT the threshold
        txn(at(10, 0), "PAYMENT", home(1413), 300, "", "");                                                        // payment, no swap
        event(at(9, 0), "SIM_SWAP", home(1414), "", "");  txn(at(40, 0), "PAYMENT", home(1414), 300, "", "");     // 31 h after
        event(at(9, 0), "SIM_SWAP", home(1415), "", "");  txn(at(8, 0), "PAYMENT", home(1415), 300, "", "");      // BEFORE the swap
        event(at(8, 0), "SIM_SWAP", home(1416), "", "");  event(at(9, 0), "SIM_SWAP", home(1416), "", "");        // two swaps,
        txn(at(10, 0), "PAYMENT", home(1416), 150, "", "");                                                        // ONE payment of 150
        plant("fraud_sim_swap", homes(1401, 1402, 1403, 1404), homes(1411, 1412, 1413, 1414, 1415, 1416));

        // ---- 7 Subscription / identity (gt 5 lines on one document; exempt_doc_prefixes CORP-); every line is active
        int line = 1501;
        line = document("DOC-X0001", 8, line);
        line = document("DOC-X0002", 8, line);
        line = document("DOC-X0003", 6, line);      // JUST above: 6
        line = document("DOC-F0001", 4, line);      // a family
        line = document("DOC-F0002", 5, line);      // AT the threshold: 5
        line = document("CORP-000001", 20, line);    // a corporate account: many lines, one registration
        // ⚠ KNOWN RISK, pinned: the exemption is a PREFIX on a field the dealer enters, so a made-up CORP- document
        // is exempted too. Any change to that is deliberate (runbook, OKF).
        document("CORP-FAKE", 12, line);
        knownRiskExempted.put("fraud_identity", keys("CORP-FAKE"));
        plant("fraud_identity", keys("DOC-X0001", "DOC-X0002", "DOC-X0003"), keys("DOC-F0001", "DOC-F0002", "CORP-000001"));

        // ---- 8 Dealer activations (gt 20 activations with no originated traffic up to 48 h after the window)
        line = 2001;
        line = dealer("D98", 30, line, 15, false);
        line = dealer("D99", 30, line, 15, false);
        line = dealer("D97", 21, line, 15, false);   // JUST above: 21
        line = dealer("D91", 15, line, 16, false);   // a small dealer, some idle lines
        line = dealer("D90", 20, line, 16, false);   // AT the threshold: 20
        dealer("D92", 25, line, 23, true);            // late-day activations; the lines call next morning
        // (the 6 background / identity dealers D01-D06 are look-alikes too: many activations, all active)
        plant("fraud_dealer", keys("D98", "D99", "D97"), keys("D90", "D91", "D92", "D01", "D02", "D03", "D04", "D05", "D06"));

        // ---- 9 Voucher / EVD (gt 1 redemption per serial)
        Set<String> serials = new LinkedHashSet<>(), serialAlikes = new LinkedHashSet<>();
        int redeemer = 1701;
        for (int v = 1; v <= 4; v++) {
            String serial = f("V9%07d", v);
            serials.add(serial);
            for (int r = 0; r < (v == 4 ? 2 : 3); r++) txn(at(17, v * 3 + r), "VOUCHER", home(redeemer++), 10, serial, ""); // v4: JUST above, 2
        }
        for (int i : new int[] {1721, 1722})        // a heavy top-up user: 10 DISTINCT vouchers, each AT the threshold (1)
            for (int v = 0; v < 10; v++) {
                String serial = f("V8%03d%04d", i - 1700, v);
                serialAlikes.add(serial);
                txn(at(18, v), "VOUCHER", home(i), 10, serial, "");
            }
        plant("fraud_voucher", serials, serialAlikes);

        // ---- 10 Payment reversal (gt 3 reversals)
        for (int i : new int[] {1801, 1802, 1803}) reversals(i, 6, 6);
        reversals(1804, 5, 4);                       // JUST above: 4
        reversals(1811, 4, 3);                       // AT the threshold: 3
        for (int i : new int[] {1812, 1813}) reversals(i, 3, 2);
        for (int i : new int[] {1814, 1815}) reversals(i, 20, 0);
        plant("fraud_reversal", homes(1801, 1802, 1803, 1804), homes(1811, 1812, 1813, 1814, 1815));
        // ---- 11 Recharge fraud (card testing: gt 8 PAYMENT top-ups under small_amount = 5 per line)
        for (int i : new int[] {1901, 1902, 1903, 1904}) topups(i, 12, 2, "PAYMENT");
        topups(1905, 9, 2, "PAYMENT");              // JUST above: 9
        topups(1911, 8, 2, "PAYMENT");              // AT the threshold: 8
        topups(1912, 3, 2, "PAYMENT");              // a few small top-ups
        topups(1913, 20, 6, "PAYMENT");             // a heavy bill-payer: many top-ups, none under 5
        topups(1914, 12, 2, "VOUCHER");             // small VOUCHER redemptions (distinct serials), not card top-ups
        plant("fraud_recharge", homes(1901, 1902, 1903, 1904, 1905), homes(1911, 1912, 1913, 1914));

        // ---- 12 Data-charging bypass (gt 7200 s of DATA sessions rated at 0 outside zero_rated_apns)
        for (int i : new int[] {2601, 2602, 2603, 2604}) data(i, 4, 3600, 0, "internet.apn");
        data(2605, 2, 3600, 0, "internet.apn");
        data(2605, 1, 1, 0, "internet.apn");                    // JUST above: 7201
        data(2611, 2, 3600, 0, "internet.apn");                 // AT the threshold: 7200
        data(2612, 20, 3600, 3.6, "internet.apn");              // 20 h of data, properly charged
        data(2613, 10, 3600, 0, "zero.operator.apn");           // a zero-rated operator portal
        data(2614, 1, 3600, 0, "internet.apn");                 // half unrated, half charged
        data(2614, 1, 3600, 3.6, "internet.apn");
        data(2615, 6, 100, 0, "internet.apn");                  // a little unrated noise: 600
        plant("fraud_data_bypass", homes(2601, 2602, 2603, 2604, 2605), homes(2611, 2612, 2613, 2614, 2615));

        // ---- 13 Internal fraud (gt 5 SIM swaps per staff user in a day; exempt_staff HELPDESK)
        int swapLine = 2701;
        for (String staff : new String[] {"S101", "S102", "S103"}) swapLine = swaps(staff, 12, swapLine);
        swapLine = swaps("S104", 6, swapLine);                  // JUST above: 6
        swapLine = swaps("S111", 5, swapLine);                  // AT the threshold: 5
        swapLine = swaps("S112", 2, swapLine);
        swaps("HELPDESK", 40, swapLine);                        // the registered swap desk: many swaps by design
        plant("fraud_internal", keys("S101", "S102", "S103", "S104"), keys("S111", "S112", "HELPDESK"));

        // ---- 14 Manual adjustment fraud (gt 500 of ADJUSTMENT credits per staff agent in a day; exempt_agents BILLING-DESK)
        int adjLine = 2801;
        for (String agent : new String[] {"A201", "A202", "A203"}) adjLine = adjustments(agent, 6, 150, adjLine);
        adjLine = adjustments("A204", 1, 500.01, adjLine);              // JUST above: 500.01
        adjLine = adjustments("A211", 4, 125, adjLine);                 // AT the threshold: 500
        adjLine = adjustments("A212", 3, 40, adjLine);                  // a few small goodwill credits
        adjustments("BILLING-DESK", 30, 200, adjLine);                  // the billing-correction desk: large credits by design
        plant("fraud_adjustment", keys("A201", "A202", "A203", "A204"), keys("A211", "A212", "BILLING-DESK"));
        return this;
    }

    /** {@code n} top-ups of {@code amount} on line {@code i}; a VOUCHER top-up carries its own distinct serial. */
    private void topups(int i, int n, double amount, String type) {
        for (int c = 0; c < n; c++)
            txn(at(20, c), type, home(i), amount, "VOUCHER".equals(type) ? f("V7%03d%04d", i - 1900, c) : "", "");
    }

    /** {@code n} one-hour-ish DATA sessions of {@code secs} on line {@code i}, each rated {@code charge}. */
    private void data(int i, int n, int secs, double charge, String apn) {
        for (int c = 0; c < n; c++) call(at(21, c), "MO", "DATA", home(i), apn, secs, charge, false, "C800");
    }

    /** {@code n} ADJUSTMENT credits of {@code amount} by {@code agent} on fresh lines from {@code line}; returns the next free line. */
    private int adjustments(String agent, int n, double amount, int line) {
        for (int c = 0; c < n; c++, line++) txn(at(13, c), "ADJUSTMENT", home(line), amount, "", "", agent);
        return line;
    }

    /** {@code n} SIM swaps by {@code staff} on fresh lines starting at {@code line}; returns the next free line. */
    private int swaps(String staff, int n, int line) {
        for (int c = 0; c < n; c++, line++) event(at(11, c), "SIM_SWAP", home(line), staff, "");
        return line;
    }

    /**
     * A foreign number {@code +289…w} rings {@code targets} background lines for {@code ringSecs}; the first
     * {@code callbacks} of them call it back (dialled as {@code 00289…}) {@code callbackMinutes} after the ring.
     */
    private void wangiri(int w, int targets, int callbacks, int callbackMinutes, int ringSecs, Set<String> into) {
        String caller = f("28900000%03d", w);
        into.add(caller);
        for (int t = 1; t <= targets; t++) {
            String target = home(1 + (w * 20 + t) % 200);
            LocalDateTime ring = at(5, 0).plusMinutes(w * 45L + t);
            call(ring, "MT", "VOICE", "+" + caller, target, ringSecs, 0, false, "C300");
            if (t <= callbacks) call(ring.plusMinutes(callbackMinutes), "MO", "VOICE", target, "00" + caller, 30, 2.5, false, "C300");
        }
    }

    private int document(String doc, int lines, int line) {
        for (int l = 0; l < lines; l++, line++) {
            event(at(15, rnd.nextInt(60)), "ACTIVATION", home(line), "D06", doc);
            call(at(16, rnd.nextInt(60)), "MO", "VOICE", home(line), domestic(), 60, 0.1, false, "C600");
        }
        return line;
    }

    private int dealer(String dealer, int lines, int line, int hour, boolean callNextMorning) {
        for (int l = 0; l < lines; l++, line++) {
            event(at(hour, 30 + l), "ACTIVATION", home(line), dealer, f("DOC-%06d", line));
            if (callNextMorning) call(at(33, l), "MO", "VOICE", home(line), domestic(), 60, 0.1, false, "C700");
        }
        return line;
    }

    private void reversals(int i, int paid, int reversed) {
        for (int p = 0; p < paid; p++) {
            String id = txn(at(19, p * 2), "PAYMENT", home(i), 50, "", "");
            if (p < reversed) txn(at(19, p * 2 + 1), "REVERSAL", home(i), 50, "", id);
        }
    }

    /** Sample path (relative to {@code data/samples}) → file content. */
    Map<String, String> files() {
        Map<String, String> out = new LinkedHashMap<>();
        out.put(CDR_FILE, cdr.toString());
        out.put(EVENTS_FILE, events.toString());
        out.put(PAYMENTS_FILE, payments.toString());
        return out;
    }
}
