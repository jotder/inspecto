package com.gamma.control;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * The deterministic generator of the {@code telco-fraud} Space Template's SYNTHETIC golden corpus
 * ({@code ASSURE-PACK-TELCO-FRAUD-1}). Fixed seed, fixed day, no real data: every number is in the fictional
 * {@code 9997…} home range, and every planted case is recorded here with the Alert Rule that must name it.
 *
 * <p>Background traffic (200 subscribers) is drawn from the seeded {@link Random} but kept far under every
 * threshold. Each typology then gets planted offenders ({@link #offenders}) and planted look-alikes
 * ({@link #lookAlikes}) — traffic that resembles the fraud but must stay silent.
 * {@code TelcoFraudTemplateGoldenTest} asserts the committed samples are byte-identical to this output; to
 * regenerate them run that test with the environment variable {@code TELCO_FRAUD_REGENERATE=true}.
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
    private final StringBuilder payments = new StringBuilder("txn_id,txn_ts,txn_type,msisdn,amount,voucher_serial,ref_txn_id\n");
    private int cdrSeq, eventSeq, txnSeq;

    /** Alert Rule name → the keys it must raise (exactly these). */
    final Map<String, Set<String>> offenders = new LinkedHashMap<>();
    /** Alert Rule name → planted look-alike keys that must NOT be raised by any rule. */
    final Map<String, Set<String>> lookAlikes = new LinkedHashMap<>();

    static String home(int i) { return String.format(java.util.Locale.ROOT, "99970%06d", i); }

    private static String ts(LocalDateTime t) { return TS.format(t); }

    private LocalDateTime at(int hour, int minute) { return DAY.plusHours(hour).plusMinutes(minute); }

    private LocalDateTime randomTime() { return DAY.plusSeconds(rnd.nextInt(86_000)); }

    private void call(LocalDateTime t, String dir, String service, String a, String b, int seconds, double charge,
                      boolean roaming, String cell) {
        cdr.append(String.format(java.util.Locale.ROOT, "R%07d,%s,%s,%s,%s,%s,%d,%.2f,%s,%s%n", ++cdrSeq, ts(t), dir, service, a, b, seconds,
                charge, roaming ? "Y" : "N", cell).replace("\r\n", "\n"));
    }

    private void event(LocalDateTime t, String type, String msisdn, String dealer, String doc) {
        events.append(String.format(java.util.Locale.ROOT, "E%07d,%s,%s,%s,%s,%s%n", ++eventSeq, ts(t), type, msisdn, dealer, doc).replace("\r\n", "\n"));
    }

    private String txn(LocalDateTime t, String type, String msisdn, double amount, String serial, String ref) {
        String id = String.format(java.util.Locale.ROOT, "T%07d", ++txnSeq);
        payments.append(String.format(java.util.Locale.ROOT, "%s,%s,%s,%s,%.2f,%s,%s%n", id, ts(t), type, msisdn, amount, serial, ref).replace("\r\n", "\n"));
        return id;
    }

    private void plant(String rule, Set<String> keys, Set<String> alikes) {
        offenders.put(rule, keys);
        lookAlikes.put(rule, alikes);
    }

    private static Set<String> homes(int from, int to) {
        Set<String> s = new LinkedHashSet<>();
        for (int i = from; i <= to; i++) s.add(home(i));
        return s;
    }

    private String domestic() { return String.format(java.util.Locale.ROOT, "99971%06d", rnd.nextInt(1_000_000)); }

    TelcoFraudCorpus generate() {
        // ---- background: 200 subscribers, activated by 5 busy dealers, each with ordinary traffic
        for (int i = 1; i <= 200; i++) {
            String m = home(i);
            event(randomTime(), "ACTIVATION", m, String.format(java.util.Locale.ROOT, "D%02d", 1 + (i - 1) / 40), String.format(java.util.Locale.ROOT, "DOC-%06d", i));
            int cells = 1 + rnd.nextInt(3);
            for (int c = 0, n = 3 + rnd.nextInt(6); c < n; c++) {
                int secs = 20 + rnd.nextInt(580);
                call(randomTime(), "MO", "VOICE", m, domestic(), secs, secs / 600.0, false, "C" + (100 + (i + c % cells) % 50));
            }
            if (rnd.nextBoolean()) call(randomTime(), "MO", "SMS", m, domestic(), 0, 0.05, false, "C" + (100 + i % 50));
            // every background line also RECEIVES ordinary calls
            for (int c = 0, n = 1 + rnd.nextInt(3); c < n; c++)
                call(randomTime(), "MT", "VOICE", home(1 + rnd.nextInt(200)), m, 30 + rnd.nextInt(270), 0, false, "C" + (100 + i % 50));
            txn(randomTime(), "VOUCHER", m, 10, String.format(java.util.Locale.ROOT, "V%08d", i), "");
            txn(randomTime(), "PAYMENT", m, 20 + rnd.nextInt(60), "", "");
        }

        // ---- 1 IRSF: 10 x 600 s to 882 (6000 s > 3600)
        for (int i = 1001; i <= 1004; i++)
            for (int c = 0; c < 10; c++) call(at(2, c * 11), "MO", "VOICE", home(i), "882" + (7000000 + i * 10 + c), 600, 9.0, false, "C200");
        //   look-alikes: a little to a listed range (2 x 300 s), or a lot to an unlisted international range
        for (int i = 1011; i <= 1013; i++)
            for (int c = 0; c < 2; c++) call(at(3, c * 7), "MO", "VOICE", home(i), "883" + (5000000 + i * 10 + c), 300, 4.5, false, "C201");
        for (int i = 1014; i <= 1015; i++)
            for (int c = 0; c < 14; c++) call(at(4, c * 11), "MO", "VOICE", home(i), "441" + (2000000 + i * 10 + c), 600, 3.0, false, "C202");
        plant("fraud_irsf", homes(1001, 1004), homes(1011, 1015));

        // ---- 2 Wangiri: a foreign number rings 40 subscribers for 1 s each
        Set<String> wangiri = new LinkedHashSet<>(), wangiriAlikes = new LinkedHashSet<>();
        for (int w = 1; w <= 3; w++) {
            String caller = String.format(java.util.Locale.ROOT, "23277000%03d", w);
            wangiri.add(caller);
            for (int t = 1; t <= 40; t++) call(at(5, t), "MT", "VOICE", caller, home(t + w * 40 % 160), 1, 0, false, "C300");
        }
        //   look-alikes: a foreign call centre (40 targets, real conversations) and a foreign number with few short calls
        for (int w = 1; w <= 2; w++) {
            String centre = String.format(java.util.Locale.ROOT, "23288000%03d", w);
            wangiriAlikes.add(centre);
            for (int t = 1; t <= 40; t++) call(at(6, t), "MT", "VOICE", centre, home(t + 20 * w), 120, 0, false, "C301");
            String few = String.format(java.util.Locale.ROOT, "23299000%03d", w);
            wangiriAlikes.add(few);
            for (int t = 1; t <= 10; t++) call(at(7, t), "MT", "VOICE", few, home(t + 100), 1, 0, false, "C302");
        }
        plant("fraud_wangiri", wangiri, wangiriAlikes);

        // ---- 3 SIM-box: 80 distinct targets from one cell, never receives, never texts
        for (int i = 1101; i <= 1103; i++)
            for (int c = 0; c < 80; c++) call(at(8, c % 60), "MO", "VOICE", home(i), "99972" + (100000 + i * 100 + c), 90, 0.15, false, "C400");
        //   look-alikes: as busy but receives calls (1111-1112) / as busy but moves across 4 cells (1113)
        for (int i = 1111; i <= 1113; i++) {
            for (int c = 0; c < 80; c++)
                call(at(9, c % 60), "MO", "VOICE", home(i), "99973" + (100000 + i * 100 + c), 90, 0.15, false,
                        i == 1113 ? "C40" + (c % 4) : "C410");
            if (i < 1113) for (int c = 0; c < 5; c++) call(at(10, c), "MT", "VOICE", home(c + 1), home(i), 60, 0, false, "C410");
        }
        plant("fraud_simbox", homes(1101, 1103), homes(1111, 1113));

        // ---- 4 Premium-rate: 30 x 5.00 to 900 (150 > 100)
        for (int i = 1201; i <= 1204; i++)
            for (int c = 0; c < 30; c++) call(at(11, c), "MO", "VOICE", home(i), "900" + (1000000 + c), 60, 5.0, false, "C500");
        for (int i = 1211; i <= 1213; i++)   // look-alikes: an occasional premium-rate user (50)
            for (int c = 0; c < 10; c++) call(at(12, c), "MO", "VOICE", home(i), "909" + (1000000 + c), 60, 5.0, false, "C501");
        plant("fraud_premium_rate", homes(1201, 1204), homes(1211, 1213));

        // ---- 5 Roaming high usage: 60 x 12.00 abroad (720 > 500)
        for (int i = 1301; i <= 1303; i++)
            for (int c = 0; c < 60; c++) call(at(13, c), "MO", "VOICE", home(i), home(1 + c % 5), 120, 12.0, true, "RX1");
        for (int i = 1311; i <= 1313; i++)   // look-alikes: an ordinary traveller (200)
            for (int c = 0; c < 20; c++) call(at(14, c), "MO", "VOICE", home(i), home(1 + c % 5), 120, 10.0, true, "RX2");
        plant("fraud_roaming", homes(1301, 1303), homes(1311, 1313));

        // ---- 6 SIM-swap: swap at 09:00, a 300 payment an hour later (within 24 h, > 200)
        for (int i = 1401; i <= 1403; i++) {
            event(at(9, 0), "SIM_SWAP", home(i), "", "");
            txn(at(10, 0), "PAYMENT", home(i), 300, "", "");
        }
        event(at(9, 0), "SIM_SWAP", home(1411), "", "");               // swap, no payment
        event(at(9, 30), "SIM_SWAP", home(1412), "", "");              // swap, no payment
        txn(at(10, 0), "PAYMENT", home(1413), 300, "", "");            // payment, no swap
        event(at(9, 0), "SIM_SWAP", home(1414), "", "");               // payment 31 h after the swap
        txn(at(40, 0), "PAYMENT", home(1414), 300, "", "");
        event(at(9, 0), "SIM_SWAP", home(1415), "", "");               // payment BEFORE the swap
        txn(at(8, 0), "PAYMENT", home(1415), 300, "", "");
        plant("fraud_sim_swap", homes(1401, 1403), homes(1411, 1415));

        // ---- 7 Subscription / identity: 8 lines on one document (> 5); each line makes one call, so it is active
        Set<String> docs = new LinkedHashSet<>(), docAlikes = new LinkedHashSet<>();
        int line = 1501;
        for (int d = 1; d <= 2; d++, line += 8) {
            String doc = String.format(java.util.Locale.ROOT, "DOC-X%04d", d);
            docs.add(doc);
            for (int l = 0; l < 8; l++) activeLine(home(line + l), "D06", doc);
        }
        line = 1521;
        for (int d = 1; d <= 2; d++, line += 4) {   // look-alikes: a family of 4 lines on one document
            String doc = String.format(java.util.Locale.ROOT, "DOC-F%04d", d);
            docAlikes.add(doc);
            for (int l = 0; l < 4; l++) activeLine(home(line + l), "D06", doc);
        }
        plant("fraud_identity", docs, docAlikes);

        // ---- 8 Dealer activations: 30 activations that never make a call (> 20)
        line = 1601;
        for (String dealer : List.of("D98", "D99"))
            for (int l = 0; l < 30; l++, line++) event(at(15, l), "ACTIVATION", home(line), dealer, String.format(java.util.Locale.ROOT, "DOC-%06d", line));
        for (int l = 0; l < 15; l++, line++)       // look-alike: a small dealer with some idle lines (15)
            event(at(16, l), "ACTIVATION", home(line), "D91", String.format(java.util.Locale.ROOT, "DOC-%06d", line));
        // (the 5 background dealers are the other look-alike: 40 activations each, all active)
        plant("fraud_dealer", new LinkedHashSet<>(List.of("D98", "D99")),
                new LinkedHashSet<>(List.of("D91", "D01", "D02", "D03", "D04", "D05", "D06")));

        // ---- 9 Voucher / EVD: one serial redeemed by 3 different lines (> 1)
        Set<String> serials = new LinkedHashSet<>(), serialAlikes = new LinkedHashSet<>();
        int redeemer = 1701;
        for (int v = 1; v <= 3; v++) {
            String serial = String.format(java.util.Locale.ROOT, "V9%07d", v);
            serials.add(serial);
            for (int r = 0; r < 3; r++) txn(at(17, v * 3 + r), "VOUCHER", home(redeemer++), 10, serial, "");
        }
        for (int i = 1711; i <= 1712; i++)          // look-alikes: a heavy top-up user, 10 DISTINCT vouchers
            for (int v = 0; v < 10; v++) {
                String serial = String.format(java.util.Locale.ROOT, "V8%03d%04d", i - 1700, v);
                serialAlikes.add(serial);
                txn(at(18, v), "VOUCHER", home(i), 10, serial, "");
            }
        plant("fraud_voucher", serials, serialAlikes);

        // ---- 10 Payment reversal: 6 payments, all 6 reversed (> 3)
        for (int i = 1801; i <= 1803; i++)
            for (int p = 0; p < 6; p++) {
                String paid = txn(at(19, p * 5), "PAYMENT", home(i), 50, "", "");
                txn(at(19, p * 5 + 2), "REVERSAL", home(i), 50, "", paid);
            }
        for (int i = 1811; i <= 1813; i++)          // look-alikes: two reversals (a mistake, then another)
            for (int p = 0; p < 3; p++) {
                String paid = txn(at(20, p * 5), "PAYMENT", home(i), 50, "", "");
                if (p < 2) txn(at(20, p * 5 + 2), "REVERSAL", home(i), 50, "", paid);
            }
        for (int i = 1814; i <= 1815; i++)          // look-alikes: many payments, nothing reversed
            for (int p = 0; p < 20; p++) txn(at(21, p), "PAYMENT", home(i), 50, "", "");
        plant("fraud_reversal", homes(1801, 1803), homes(1811, 1815));
        return this;
    }

    private void activeLine(String msisdn, String dealer, String doc) {
        event(at(15, rnd.nextInt(60)), "ACTIVATION", msisdn, dealer, doc);
        call(at(16, rnd.nextInt(60)), "MO", "VOICE", msisdn, domestic(), 60, 0.1, false, "C600");
    }

    /** Sample path (relative to {@code data/samples}) → file content. */
    Map<String, String> files() {
        Map<String, String> out = new LinkedHashMap<>();
        out.put(CDR_FILE, cdr.toString());
        out.put(EVENTS_FILE, events.toString());
        out.put(PAYMENTS_FILE, payments.toString());
        return out;
    }

    List<String> allRules() { return new ArrayList<>(offenders.keySet()); }
}
