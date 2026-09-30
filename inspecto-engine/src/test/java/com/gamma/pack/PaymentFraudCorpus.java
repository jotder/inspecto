package com.gamma.pack;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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

/**
 * WS-40: the deterministic synthetic corpus of the {@code payment-fraud} Space Template — payment attempts,
 * SIM-change events and disputes over three days, with PLANTED cases per typology and PLANTED look-alikes that
 * must stay silent. Fixed seed; the committed CSVs under {@code spaces/_templates/payment-fraud/data/samples}
 * are exactly {@link #files()} (asserted by {@code PaymentFraudTemplateGoldenTest}). Regenerate with
 * {@link #main}.
 *
 * <p>Every instrument is a TOKEN ({@code tok_…}); no value anywhere is a card number. The only digit-only
 * strings are planted tripwire look-alikes that are NOT Luhn-valid 13–19 digit values.
 */
public final class PaymentFraudCorpus {

    public static final long SEED = 20260701L;
    static final LocalDate DAY1 = LocalDate.of(2026, 7, 1);
    static final String[] BG_BINS = {"402400", "515300", "455600", "523400", "491700"};
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter FILE_DAY = DateTimeFormatter.ofPattern("yyyyMMdd");

    record Attempt(LocalDateTime ts, String account, String token, String bin, String device, String merchant,
                   double amount, String outcome) {}
    record SimChange(LocalDateTime ts, String account, String subscriber, String channel) {}

    private final Random rng = new Random(SEED);
    private final List<Attempt> attempts = new ArrayList<>();
    private final List<SimChange> sims = new ArrayList<>();
    /** Disputes as {attempt, opened day, reason code}; the attempt id is assigned after sorting. */
    private final List<Object[]> disputes = new ArrayList<>();

    private PaymentFraudCorpus() {}

    private static LocalDateTime at(int day, int h, int m) { return DAY1.plusDays(day - 1).atTime(h, m); }

    private double money(double lo, double hi) { return Math.round((lo + rng.nextDouble() * (hi - lo)) * 100) / 100.0; }

    private Attempt add(LocalDateTime ts, String acc, String tok, String bin, String dev, String merchant,
                        double amount, String outcome) {
        Attempt a = new Attempt(ts, acc, tok, bin, dev, merchant, amount, outcome);
        attempts.add(a);
        return a;
    }

    private void build() {
        // ── Background: 60 ordinary accounts, one device + one instrument each, 2–4 attempts a day at distinct hours.
        List<Attempt> bgApproved = new ArrayList<>();
        for (int i = 1; i <= 60; i++) {
            String acc = String.format(Locale.ROOT, "acc_bg_%03d", i);
            for (int day = 1; day <= 3; day++) {
                List<Integer> hours = new ArrayList<>();
                for (int h = 8; h <= 21; h++) hours.add(h);
                java.util.Collections.shuffle(hours, rng);
                int n = 2 + rng.nextInt(3);
                for (int k = 0; k < n; k++) {
                    Attempt a = add(at(day, hours.get(k), rng.nextInt(60)), acc, "tok_bg_" + String.format(Locale.ROOT, "%03d", i),
                            BG_BINS[i % BG_BINS.length], "dev_bg_" + String.format(Locale.ROOT, "%03d", i),
                            "m_" + String.format(Locale.ROOT, "%02d", 1 + rng.nextInt(20)), money(5, 150),
                            rng.nextDouble() < 0.1 ? "DECLINED" : "APPROVED");
                    if ("APPROVED".equals(a.outcome())) bgApproved.add(a);
                }
            }
        }
        // Background disputes (legitimate customers disputing a charge) and legitimate SIM changes.
        for (int k = 0; k < 4; k++)
            disputes.add(new Object[]{bgApproved.get(rng.nextInt(bgApproved.size())), 3, "R_NOT_RECOGNISED"});
        for (int i : new int[]{11, 22, 33})
            sims.add(new SimChange(at(2, 7, 15), String.format(Locale.ROOT, "acc_bg_%03d", i),
                    String.format(Locale.ROOT, "sub_bg_%03d", i), "STORE"));

        // ── Card testing (per device): many distinct instruments, tiny amounts, one device.
        for (int k = 0; k < 10; k++)       // PLANTED dev_ct_01: 10 distinct instruments ≤ 2.00 on day 2
            add(at(2, 3, 10 + 3 * k), "acc_ct_guest", "tok_ct01_" + k, "499001", "dev_ct_01", "m_ct",
                    money(0.5, 1.99), k < 8 ? "DECLINED" : "APPROVED");
        for (int k = 0; k < 7; k++)        // LOOK-ALIKE dev_la_cta: 7 distinct tiny instruments — below 8
            add(at(1, 4, 5 + 4 * k), "acc_la_cta", "tok_lacta_" + k, "499002", "dev_la_cta", "m_ct2",
                    money(0.5, 1.99), "DECLINED");
        for (int k = 0; k < 12; k++)       // LOOK-ALIKE dev_la_ctb: a shared kiosk, 12 instruments, normal amounts
            add(at(3, 9, 0).plusMinutes(20L * k), "acc_la_ctb_" + k, "tok_lactb_" + k, BG_BINS[k % 5], "dev_la_ctb",
                    "m_kiosk", money(20, 60), "APPROVED");

        // ── BIN attack (per BIN): many distinct instruments of one BIN declined in a day.
        for (int k = 0; k < 25; k++)       // PLANTED BIN 498765: 25 instruments, 22 declined, on day 3
            add(at(3, 1, 0).plusMinutes(2L * k), "acc_ba_" + k, "tok_ba_" + k, "498765", "dev_ba_" + k, "m_ba",
                    money(10, 30), k < 22 ? "DECLINED" : "APPROVED");
        for (int k = 0; k < 30; k++)       // LOOK-ALIKE BIN 477777: a payroll-card run, 30 instruments, 3 declined
            add(at(2, 6, 0).plusMinutes(5L * k), "acc_la_ba_" + k, "tok_la_ba_" + k, "477777", "dev_la_ba_" + k,
                    "m_payroll", money(100, 300), k < 3 ? "DECLINED" : "APPROVED");

        // ── Velocity burst (per instrument): ≥ 6 attempts inside any 60 minutes.
        List<Attempt> vb01 = new ArrayList<>();
        for (int k = 0; k < 7; k++)        // PLANTED tok_vb_01: 7 attempts in 36 minutes
            vb01.add(add(at(2, 14, 6 * k), "acc_vb01", "tok_vb_01", "402400", "dev_vb01", "m_03",
                    money(50, 200), k % 2 == 1 && k < 6 ? "DECLINED" : "APPROVED"));
        for (int k = 0; k < 6; k++)        // PLANTED tok_vb_02: 6 attempts in 55 minutes
            add(at(3, 20, 0).plusMinutes(11L * k), "acc_vb02", "tok_vb_02", "515300", "dev_vb02", "m_04",
                    money(50, 200), k == 1 || k == 4 ? "DECLINED" : "APPROVED");
        for (int k = 0; k < 5; k++)        // LOOK-ALIKE tok_la_vb: 5 in 48 minutes, then 3 spread out
            add(at(1, 10, 12 * k), "acc_la_vb", "tok_la_vb", "455600", "dev_la_vb", "m_05", money(20, 80), "APPROVED");
        for (int h : new int[]{13, 15, 17})
            add(at(1, h, 0), "acc_la_vb", "tok_la_vb", "455600", "dev_la_vb", "m_05", money(20, 80), "APPROVED");
        for (int m : new int[]{0, 12, 24, 36, 48, 61})   // LOOK-ALIKE tok_la_vb2: 6 attempts spanning 61 minutes
            add(at(2, 16, 0).plusMinutes(m), "acc_la_vb2", "tok_la_vb2", "523400", "dev_la_vb2", "m_06",
                    money(20, 80), "APPROVED");
        // Two disputes on the burst instrument's approved attempts.
        disputes.add(new Object[]{vb01.get(0), 3, "R_FRAUD_CNP"});
        disputes.add(new Object[]{vb01.get(2), 3, "R_FRAUD_CNP"});

        // ── SIM-swap takeover (per account): SIM change, then within 24 h an APPROVED payment ≥ 200 on a device
        //    the account never used before the change.
        history("acc_ss01", "dev_ss01", "tok_ss01", 1);                             // PLANTED
        sims.add(new SimChange(at(2, 9, 0), "acc_ss01", "sub_ss01", "CALL_CENTRE"));
        add(at(2, 11, 30), "acc_ss01", "tok_ss01", "491700", "dev_ss01_new", "m_07", 450.00, "APPROVED");
        history("acc_ss02", "dev_ss02", "tok_ss02", 1);                             // PLANTED
        history("acc_ss02", "dev_ss02", "tok_ss02", 2);
        sims.add(new SimChange(at(3, 14, 0), "acc_ss02", "sub_ss02", "ONLINE"));
        add(at(3, 22, 15), "acc_ss02", "tok_ss02", "402400", "dev_ss02_new", "m_08", 800.00, "APPROVED");
        history("acc_la_ss1", "dev_la_ss1", "tok_la_ss1", 1);                       // LOOK-ALIKE: 50.5 h later
        sims.add(new SimChange(at(1, 8, 30), "acc_la_ss1", "sub_la_ss1", "ONLINE"));
        add(at(3, 11, 0), "acc_la_ss1", "tok_la_ss1", "515300", "dev_la_ss1_new", "m_09", 500.00, "APPROVED");
        history("acc_la_ss2", "dev_la_ss2", "tok_la_ss2", 1);                       // LOOK-ALIKE: the usual device
        sims.add(new SimChange(at(2, 10, 0), "acc_la_ss2", "sub_la_ss2", "STORE"));
        add(at(2, 12, 0), "acc_la_ss2", "tok_la_ss2", "455600", "dev_la_ss2", "m_10", 600.00, "APPROVED");
        history("acc_la_ss3", "dev_la_ss3", "tok_la_ss3", 1);                       // LOOK-ALIKE: below 200
        sims.add(new SimChange(at(2, 16, 0), "acc_la_ss3", "sub_la_ss3", "ONLINE"));
        add(at(2, 17, 0), "acc_la_ss3", "tok_la_ss3", "523400", "dev_la_ss3_new", "m_11", 40.00, "APPROVED");
        history("acc_la_ss4", "dev_la_ss4", "tok_la_ss4", 1);                       // LOOK-ALIKE: declined
        sims.add(new SimChange(at(2, 7, 0), "acc_la_ss4", "sub_la_ss4", "ONLINE"));
        add(at(2, 7, 30), "acc_la_ss4", "tok_la_ss4", "491700", "dev_la_ss4_new", "m_12", 900.00, "DECLINED");

        // ── Card-number tripwire look-alikes: digit-only merchant references that are NOT Luhn-valid 13–19 digit
        //    values — a 16-digit Luhn-INVALID number, a 12-digit number and a 20-digit number. All must ingest.
        for (String merchant : new String[]{"4111111111111112", "411111111111", "41111111111111111111"})
            add(at(1, 20, 30), "acc_la_pan", "tok_la_pan", "402400", "dev_la_pan", merchant, 12.50, "APPROVED");
    }

    /** Three ordinary approved attempts on the account's usual device on {@code day}. */
    private void history(String acc, String dev, String tok, int day) {
        for (int h : new int[]{8, 12, 18})
            add(at(day, h, 5), acc, tok, "402400", dev, "m_" + String.format(Locale.ROOT, "%02d", h), money(10, 60), "APPROVED");
    }

    /** The corpus as file name (relative to {@code data/samples}) → content, in a stable order. */
    public static Map<String, String> files() {
        PaymentFraudCorpus c = new PaymentFraudCorpus();
        c.build();
        Comparator<Attempt> byTs = Comparator.comparing(Attempt::ts).thenComparing(Attempt::account)
                .thenComparing(Attempt::token).thenComparing(Attempt::merchant);
        List<Attempt> sorted = new ArrayList<>(c.attempts);
        sorted.sort(byTs);
        Map<Attempt, String> ids = new java.util.IdentityHashMap<>();
        for (int i = 0; i < sorted.size(); i++) ids.put(sorted.get(i), String.format(Locale.ROOT, "pa_%06d", i + 1));
        List<SimChange> sims = new ArrayList<>(c.sims);
        sims.sort(Comparator.comparing(SimChange::ts).thenComparing(SimChange::account));

        Map<String, String> out = new LinkedHashMap<>();
        for (int day = 1; day <= 3; day++) {
            LocalDate d = DAY1.plusDays(day - 1);
            String stamp = d.format(FILE_DAY);
            StringBuilder pa = new StringBuilder(
                    "ATTEMPT_ID,ATTEMPT_TS,ATTEMPT_DATE,ACCOUNT_ID,INSTRUMENT_TOKEN,BIN,DEVICE_ID,MERCHANT_ID,AMOUNT,CURRENCY,OUTCOME\n");
            for (Attempt a : sorted)
                if (a.ts().toLocalDate().equals(d))
                    pa.append(String.join(",", ids.get(a), a.ts().format(TS), d.toString(), a.account(), a.token(),
                            a.bin(), a.device(), a.merchant(), String.format(Locale.ROOT, "%.2f", a.amount()), "EUR",
                            a.outcome())).append('\n');
            out.put("payment_attempts/PAYMENT_ATTEMPTS_" + stamp + ".csv", pa.toString());

            StringBuilder sc = new StringBuilder("EVENT_ID,EVENT_TS,EVENT_DATE,ACCOUNT_ID,SUBSCRIBER_TOKEN,CHANNEL\n");
            for (int i = 0; i < sims.size(); i++) {
                SimChange s = sims.get(i);
                if (s.ts().toLocalDate().equals(d))
                    sc.append(String.join(",", String.format(Locale.ROOT, "sc_%04d", i + 1), s.ts().format(TS),
                            d.toString(), s.account(), s.subscriber(), s.channel())).append('\n');
            }
            out.put("sim_changes/SIM_CHANGES_" + stamp + ".csv", sc.toString());

            StringBuilder ds = new StringBuilder("DISPUTE_ID,OPENED_DATE,ATTEMPT_ID,ACCOUNT_ID,REASON_CODE,AMOUNT\n");
            for (int i = 0; i < c.disputes.size(); i++) {
                Object[] x = c.disputes.get(i);
                Attempt a = (Attempt) x[0];
                if ((int) x[1] == day)
                    ds.append(String.join(",", String.format(Locale.ROOT, "dp_%04d", i + 1), d.toString(), ids.get(a),
                            a.account(), (String) x[2], String.format(Locale.ROOT, "%.2f", a.amount()))).append('\n');
            }
            if (ds.indexOf("\n") < ds.length() - 1)   // a day with no dispute ships no file (an empty file quarantines)
                out.put("disputes/DISPUTES_" + stamp + ".csv", ds.toString());
        }
        return out;
    }

    /** Regenerate the committed corpus: {@code main <template dir>/data/samples}. */
    public static void main(String[] args) throws IOException {
        Path samples = Path.of(args[0]);
        for (Map.Entry<String, String> e : files().entrySet()) {
            Path f = samples.resolve(e.getKey());
            Files.createDirectories(f.getParent());
            Files.writeString(f, e.getValue());
        }
    }
}
