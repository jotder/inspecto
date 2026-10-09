package com.gamma.job;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
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
 * The deterministic generator of the {@code mobile-money} Space Template's SYNTHETIC golden corpus
 * ({@code PACK-MOBILE-MONEY-1}). Fixed seed, one fixed day, no real data: wallets {@code W…}, agents {@code A…},
 * partners {@code P…}, lines in the reserved E.164 country code {@code 999}.
 *
 * <p>Background (150 wallets, 20 agents, 3 partners) comes from the seeded {@link Random} and stays far under
 * every threshold: every fee and commission is exactly the configured schedule, every bank and partner movement
 * settles exactly, cash-ins and cash-outs happen at DIFFERENT agents, and no background wallet moves more than the
 * tier-1 limit out. Each typology plants offenders ({@link #offenders}, one JUST above the threshold) and
 * look-alikes ({@link #lookAlikes}, one exactly AT it plus realistic benign patterns). Reconciliation plants are
 * {@code pair|type|key} Breaks ({@link #breaks}) with benign keys ({@link #benignBreaks}).
 * {@code MobileMoneyPackGoldenTest} pins the committed samples to this output; regenerate with
 * {@code -Dmobilemoney.regenerate=true}.
 */
final class MobileMoneyCorpus {

    static final long SEED = 20261009L;
    static final LocalDate DAY = LocalDate.of(2026, 7, 1);

    // the shipped schedule (Job parameters) — the corpus computes every "correct" value from it
    private static final BigDecimal CASH_IN_RATE = new BigDecimal("0.005"), CASH_OUT_RATE = new BigDecimal("0.01");
    private static final BigDecimal FEE_RATE = new BigDecimal("0.01"), FEE_MIN = new BigDecimal("0.50");

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private final Random rnd = new Random(SEED);
    private final StringBuilder txns = new StringBuilder("txn_id,txn_ts,txn_type,wallet_id,agent_id,partner_id,amount,fee,commission\n");
    private final StringBuilder bank = new StringBuilder("stmt_line_id,value_ts,bank_ref,direction,amount\n");
    private final StringBuilder settlement = new StringBuilder("line_id,settle_date,partner_id,txn_ref,amount\n");
    private final StringBuilder wallets = new StringBuilder("snapshot_date,wallet_id,msisdn,kyc_tier,status,last_activity_date\n");
    private final StringBuilder core = new StringBuilder("snapshot_date,msisdn,status\n");
    private int txnSeq, bankSeq, lineSeq;

    /** Alert Rule name → the keys it must raise (exactly these). */
    final Map<String, Set<String>> offenders = new LinkedHashMap<>();
    /** Alert Rule name → planted look-alike keys that must NOT be raised by any rule. */
    final Map<String, Set<String>> lookAlikes = new LinkedHashMap<>();
    /** Reconciliation → exactly its planted Breaks, {@code pair|type|key}. */
    final Map<String, Set<String>> breaks = new LinkedHashMap<>();
    /** Reconciliation → keys of benign look-alikes that must not break. */
    final Map<String, Set<String>> benignBreaks = new LinkedHashMap<>();
    /** The agents the {@code mm_agent} Risk Score must rate high (score at or above 60). */
    final Set<String> highRiskAgents = new LinkedHashSet<>();

    private static String f(String fmt, Object... a) { return String.format(Locale.ROOT, fmt, a); }

    static String wallet(int i) { return f("W%06d", i); }

    static String msisdn(int i) { return f("99977%06d", i); }

    private static BigDecimal m(String v) { return new BigDecimal(v).setScale(2, RoundingMode.HALF_UP); }

    private static BigDecimal m(long v) { return BigDecimal.valueOf(v).setScale(2, RoundingMode.HALF_UP); }

    private static BigDecimal pct(BigDecimal amount, BigDecimal rate) { return amount.multiply(rate).setScale(2, RoundingMode.HALF_UP); }

    static BigDecimal fee(BigDecimal amount) { return pct(amount, FEE_RATE).max(FEE_MIN); }

    private LocalDateTime at(int hour, int minute) { return DAY.atStartOfDay().plusHours(hour).plusMinutes(minute); }

    private String txn(LocalDateTime t, String type, String wallet, String agent, String partner, BigDecimal amount,
                       BigDecimal fee, BigDecimal commission) {
        String id = f("T%07d", ++txnSeq);
        txns.append(f("%s,%s,%s,%s,%s,%s,%s,%s,%s", id, TS.format(t), type, wallet, agent, partner,
                amount.toPlainString(), fee.toPlainString(), commission.toPlainString())).append('\n');
        return id;
    }

    /** A correctly-priced cash-in (agent commission at the cash-in rate). */
    private String cashIn(LocalDateTime t, String wallet, String agent, BigDecimal amount) {
        return txn(t, "CASH_IN", wallet, agent, "", amount, m(0), pct(amount, CASH_IN_RATE));
    }

    /** A correctly-priced cash-out (schedule fee, commission at the cash-out rate). */
    private String cashOut(LocalDateTime t, String wallet, String agent, BigDecimal amount) {
        return txn(t, "CASH_OUT", wallet, agent, "", amount, fee(amount), pct(amount, CASH_OUT_RATE));
    }

    private String p2p(LocalDateTime t, String wallet, BigDecimal amount, BigDecimal fee) {
        return txn(t, "P2P", wallet, "", "", amount, fee, m(0));
    }

    private String p2p(LocalDateTime t, String wallet, BigDecimal amount) { return p2p(t, wallet, amount, fee(amount)); }

    private void bankLine(LocalDateTime t, String ref, String dir, BigDecimal amount) {
        bank.append(f("S%07d,%s,%s,%s,%s", ++bankSeq, TS.format(t), ref, dir, amount.toPlainString())).append('\n');
    }

    private void settle(LocalDate d, String partner, String ref, BigDecimal amount) {
        settlement.append(f("L%07d,%s,%s,%s,%s", ++lineSeq, d, partner, ref, amount.toPlainString())).append('\n');
    }

    /** A register row (snapshot of the golden day) and, unless {@code coreStatus} is null, the core row of its line. */
    private void register(String wallet, String line, int tier, String status, LocalDate lastActivity, String coreStatus) {
        wallets.append(f("%s,%s,%s,%d,%s,%s", DAY, wallet, line, tier, status, lastActivity)).append('\n');
        if (coreStatus != null) core.append(f("%s,%s,%s", DAY, line.replaceAll("[^0-9]", ""), coreStatus)).append('\n');
    }

    /** An ordinary, recently active, fully-KYC'd wallet on an ACTIVE line — for plants that are about something else. */
    private String plain(int i) {
        register(wallet(i), msisdn(i), 3, "ACTIVE", DAY.minusDays(3), "ACTIVE");
        return wallet(i);
    }

    private static Set<String> keys(String... k) { return new LinkedHashSet<>(List.of(k)); }

    private void plant(String rule, Set<String> keys, Set<String> alikes) {
        offenders.put(rule, keys);
        lookAlikes.put(rule, alikes);
    }

    MobileMoneyCorpus generate() {
        // ---- background: 150 wallets, cash-in at A01-A10, cash-out at A11-A20, all exactly priced and settled
        for (int i = 1; i <= 150; i++) {
            String w = wallet(i);
            register(w, msisdn(i), 1 + rnd.nextInt(3), "ACTIVE", DAY.minusDays(1 + rnd.nextInt(60)), "ACTIVE");
            cashIn(at(6 + rnd.nextInt(5), rnd.nextInt(60)), w, f("A%02d", 1 + i % 10), m(10L * (5 + rnd.nextInt(36))));
            cashOut(at(12 + rnd.nextInt(8), rnd.nextInt(60)), w, f("A%02d", 11 + i % 10), m(10L * (1 + rnd.nextInt(7))));
            if (rnd.nextBoolean()) p2p(at(12 + rnd.nextInt(8), rnd.nextInt(60)), w, m(10L * (1 + rnd.nextInt(7))));
            if (rnd.nextInt(10) < 3) {
                String partner = f("P%02d", 1 + i % 3);
                BigDecimal amount = m(10L * (1 + rnd.nextInt(7)));
                String id = txn(at(13 + rnd.nextInt(6), rnd.nextInt(60)), "BILL_PAY", w, "", partner, amount, m(0), m(0));
                settle(DAY, partner, id, amount);
            }
            if (rnd.nextInt(10) == 0) {
                BigDecimal amount = m(10L * (1 + rnd.nextInt(7)));
                LocalDateTime t = at(14 + rnd.nextInt(5), rnd.nextInt(60));
                bankLine(t.plusMinutes(5), txn(t, "BANK_OUT", w, "", "", amount, m(0), m(0)), "DR", amount);
            }
            if (rnd.nextInt(10) == 0) {
                BigDecimal amount = m(100L + 10L * rnd.nextInt(21));
                LocalDateTime t = at(8 + rnd.nextInt(3), rnd.nextInt(60));
                bankLine(t.plusMinutes(5), txn(t, "BANK_IN", w, "", "", amount, m(0), m(0)), "CR", amount);
            }
        }

        // ---- 1 Commission over / under-pay (gt 5 USD |paid - expected| per agent)
        for (int c = 0; c < 10; c++) txn(at(9, c), "CASH_IN", plain(9010 + c), "A901", "", m(200), m(0), m("6.00")); // +50
        for (int c = 0; c < 10; c++) txn(at(9, 20 + c), "CASH_OUT", plain(9020 + c), "A902", "", m(400), fee(m(400)), m(0)); // -40
        for (int c = 0; c < 10; c++) txn(at(9, 40 + c), "CASH_IN", plain(9030 + c), "A903", "", m(200), m(0), m("2.00")); // +10: cash-out rate
        for (int c = 0; c < 10; c++)                                                                                      // JUST above: +5.01
            txn(at(10, c), "CASH_IN", plain(9040 + c), "A904", "", m(200), m(0), c == 0 ? m("6.01") : m("1.00"));
        for (int c = 0; c < 10; c++)                                                                                      // AT: +5.00
            txn(at(10, 20 + c), "CASH_IN", plain(9110 + c), "A911", "", m(200), m(0), c == 0 ? m("6.00") : m("1.00"));
        for (int c = 0; c < 30; c++)                                                                                      // rounding noise: +0.03
            txn(at(11, c), "CASH_IN", plain(9120 + c), "A912", "", m(200), m(0), c < 3 ? m("1.01") : m("1.00"));
        for (int c = 0; c < 40; c++) cashOut(at(11, 30 + c % 30), plain(9160 + c), "A913", m(500));                       // big, exact
        plant("mm_commission", keys("A901", "A902", "A903", "A904"), keys("A911", "A912", "A913"));

        // ---- 2 Fee mis-charge (gt 1 USD overcharged per wallet, CASH_OUT and P2P)
        String fw;
        fw = plain(9201); for (int c = 0; c < 5; c++) p2p(at(12, c), fw, m(50), m("2.00"));                              // +7.50
        fw = plain(9202); for (int c = 0; c < 2; c++) txn(at(12, 10 + c), "CASH_OUT", fw, "A30", "", m(70), m("5.00"), pct(m(70), CASH_OUT_RATE)); // +8.60
        fw = plain(9203); p2p(at(12, 20), fw, m(1000), m("15.00"));                                                       // +5.00
        fw = plain(9204); p2p(at(12, 30), fw, m(100), m("2.01"));                                                         // JUST above: +1.01
        fw = plain(9211); p2p(at(12, 40), fw, m(100), m("2.00"));                                                         // AT: +1.00
        fw = plain(9212); p2p(at(12, 41), fw, m(100), m(0));                                                              // waived: undercharged only
        fw = plain(9213); p2p(at(12, 42), fw, m(10));                                                                     // the minimum fee, correctly
        fw = plain(9214); p2p(at(12, 43), fw, m(100), m("1.45")); p2p(at(12, 44), fw, m(100), m("1.45"));               // +0.90 over two
        plant("mm_fee", keys(wallet(9201), wallet(9202), wallet(9203), wallet(9204)),
                keys(wallet(9211), wallet(9212), wallet(9213), wallet(9214)));

        // ---- 3 Agent split transactions (gt 3 cash-ins in [900, 1000) into ONE wallet at one agent)
        split("A801", plain(9301), 5, m(950), 13);
        split("A802", plain(9302), 5, m(990), 13);
        split("A803", plain(9303), 6, m(900), 13);                       // the band's lower edge counts
        split("A804", plain(9304), 4, m("999.99"), 13);                   // JUST above: 4
        split("A811", plain(9311), 3, m(950), 14);                        // AT: 3
        for (int c = 0; c < 8; c++) split("A812", plain(9320 + c), 1, m(950), 14); // payroll-like: one each, 8 wallets
        split("A813", plain(9313), 5, m(1000), 14);                       // AT the limit: reported, not structured
        split("A814", plain(9314), 5, m("899.99"), 14);                   // just under the band
        split("A815", plain(9315), 6, m(200), 14);                        // many, small
        plant("mm_agent_split", keys("A801", "A802", "A803", "A804"), keys("A811", "A812", "A813", "A814", "A815"));

        // ---- 4 Agent round-tripping (gt 2 wallets cashing out >= 90% at the SAME agent within 60 min)
        roundTrips("A701", "A701", 9400, 4, 20, m(290));
        roundTrips("A702", "A702", 9410, 5, 20, m(290));
        roundTrips("A703", "A703", 9420, 3, 20, m(290));                   // JUST above: 3
        roundTrips("A711", "A711", 9430, 2, 20, m(290));                   // AT: 2
        roundTrips("A712", "A713", 9440, 4, 20, m(290));                   // cashed out at ANOTHER agent
        roundTrips("A714", "A714", 9450, 4, 180, m(290));                  // 3 h later
        roundTrips("A715", "A715", 9460, 4, 20, m(50));                    // a small withdrawal
        plant("mm_round_trip", keys("A701", "A702", "A703"), keys("A711", "A712", "A713", "A714", "A715"));

        // ---- Risk Score: two signals each AT their rule's threshold (silent alone) score 30 + 30 = 60 together
        split("A720", plain(9470), 3, m(950), 16);
        roundTrips("A720", "A720", 9471, 2, 20, m(290));
        split("A721", plain(9475), 3, m(950), 16);                         // one signal only: 30
        lookAlikes.get("mm_agent_split").addAll(keys("A720", "A721"));
        lookAlikes.get("mm_round_trip").add("A720");
        highRiskAgents.addAll(keys("A701", "A702", "A720"));

        // ---- 5 Dormant-wallet reactivation (no activity for > 180 days, then gt 500 USD out)
        dormant(9501, DAY.minusDays(273), "P2P", m(2000));
        dormant(9502, DAY.minusDays(395), "CASH_OUT", m(1500));
        dormant(9503, DAY.minusDays(212), "P2P", m("500.01"));            // JUST above
        dormant(9511, DAY.minusDays(273), "P2P", m(500));                 // AT
        dormant(9512, DAY.minusDays(273), "CASH_IN", m(3000));            // money IN only
        dormant(9513, DAY.minusDays(11), "P2P", m(3000));                 // an active wallet
        dormant(9514, DAY.minusDays(172), "P2P", m(2000));                // idle, but under 180 days
        dormant(9515, DAY.minusDays(180), "P2P", m(2000));                // exactly 180 days: not yet dormant
        plant("mm_dormant", keys(wallet(9501), wallet(9502), wallet(9503)),
                keys(wallet(9511), wallet(9512), wallet(9513), wallet(9514), wallet(9515)));

        // ---- 6 KYC tier-limit breach (daily outflow over 300 / 2000 / 10000 for tier 1 / 2 / 3)
        kyc(9601, 1); p2p(at(15, 0), wallet(9601), m(800));
        kyc(9602, 2); p2p(at(15, 1), wallet(9602), m(1500)); cashOut(at(15, 2), wallet(9602), "A33", m(600));
        kyc(9603, 1); p2p(at(15, 3), wallet(9603), m("300.01"));          // JUST above
        p2p(at(15, 4), wallet(9604), m(400));                              // NOT in the register: held to tier 1
        kyc(9611, 1); p2p(at(15, 5), wallet(9611), m(300));               // AT
        kyc(9612, 3); p2p(at(15, 6), wallet(9612), m(5000));
        kyc(9613, 1); cashIn(at(15, 7), wallet(9613), "A34", m(2000));    // money IN only
        kyc(9614, 2); p2p(at(15, 8), wallet(9614), m("1999.99"));
        plant("mm_kyc_limit", keys(wallet(9601), wallet(9602), wallet(9603), wallet(9604)),
                keys(wallet(9611), wallet(9612), wallet(9613), wallet(9614)));

        // ---- 7 Provisioning mismatch (an ACTIVE wallet on a line the core does not hold ACTIVE)
        register(wallet(9701), msisdn(9701), 2, "ACTIVE", DAY.minusDays(5), null);               // line unknown to core
        register(wallet(9702), msisdn(9702), 2, "ACTIVE", DAY.minusDays(5), "TERMINATED");
        register(wallet(9703), msisdn(9703), 2, "ACTIVE", DAY.minusDays(5), "SUSPENDED");
        register(wallet(9704), "+" + msisdn(9704), 2, "ACTIVE", DAY.minusDays(5), "TERMINATED"); // +E.164 on the wallet
        register(wallet(9711), msisdn(9711), 2, "CLOSED", DAY.minusDays(90), "TERMINATED");      // closed both sides
        register(wallet(9712), msisdn(9712), 2, "SUSPENDED", DAY.minusDays(9), "SUSPENDED");     // suspended both sides
        register(wallet(9713), "+" + msisdn(9713), 2, "ACTIVE", DAY.minusDays(5), "ACTIVE");      // format only
        register(wallet(9714), "+999 77 " + msisdn(9714).substring(5), 2, "ACTIVE", DAY.minusDays(5), "ACTIVE");
        core.append(f("%s,%s,ACTIVE", DAY, msisdn(9799))).append('\n');                         // a line with no wallet
        plant("mm_provisioning", keys(wallet(9701), wallet(9702), wallet(9703), wallet(9704)),
                keys(wallet(9711), wallet(9712), wallet(9713), wallet(9714)));

        // ---- 8 Reconciliation wallet vs bank float (BANK_* rows vs the trust-account statement, tolerance 0.01)
        Set<String> fb = new LinkedHashSet<>(), fbBenign = new LinkedHashSet<>();
        fb.add("AB|missing_right|" + txn(at(16, 0), "BANK_OUT", plain(9801), "", "", m(500), m(0), m(0)));  // never reached the bank
        fb.add("AB|missing_right|" + txn(at(16, 1), "BANK_OUT", plain(9802), "", "", m(250), m(0), m(0)));
        bankLine(at(16, 5), "TX-UNKNOWN-1", "CR", m(1000));                                                  // a credit nobody sent
        fb.add("AB|missing_left|TX-UNKNOWN-1");
        String id = txn(at(16, 2), "BANK_IN", plain(9803), "", "", m(300), m(0), m(0));
        bankLine(at(16, 7), id, "CR", m(200));
        fb.add("AB|value_break|" + id);
        id = txn(at(16, 3), "BANK_OUT", plain(9804), "", "", m(100), m(0), m(0));
        bankLine(at(16, 8), id, "DR", m("100.02"));                                                           // just over tolerance
        fb.add("AB|value_break|" + id);
        id = txn(at(16, 4), "BANK_OUT", plain(9811), "", "", m(100), m(0), m(0));
        bankLine(at(16, 9), id, "DR", m("100.01"));                                                           // AT tolerance
        fbBenign.add(id);
        id = txn(at(16, 5), "BANK_OUT", plain(9812), "", "", m(400), m(0), m(0));
        bankLine(at(16, 10), id, "DR", m(150));
        bankLine(at(16, 11), id, "DR", m(250));                                                               // settled in two lines
        fbBenign.add(id);
        breaks.put("mm_bank_float", fb);
        benignBreaks.put("mm_bank_float", fbBenign);

        // ---- 9 Reconciliation wallet vs partner settlement (BILL_PAY rows vs the settlement file)
        Set<String> ps = new LinkedHashSet<>(), psBenign = new LinkedHashSet<>();
        ps.add("AB|missing_right|" + txn(at(17, 0), "BILL_PAY", plain(9901), "", "P01", m(120), m(0), m(0)));
        ps.add("AB|missing_right|" + txn(at(17, 1), "BILL_PAY", plain(9902), "", "P02", m(120), m(0), m(0)));
        settle(DAY, "P03", "TX-P-UNKNOWN", m(75));                                                            // the partner claims a payment we never took
        ps.add("AB|missing_left|TX-P-UNKNOWN");
        id = txn(at(17, 2), "BILL_PAY", plain(9903), "", "P01", m(200), m(0), m(0));
        settle(DAY, "P01", id, m(180));
        ps.add("AB|value_break|" + id);
        id = txn(at(17, 3), "BILL_PAY", plain(9911), "", "P01", m(60), m(0), m(0));
        settle(DAY, "P01", id, m("60.01"));                                                                   // AT tolerance
        psBenign.add(id);
        id = txn(at(17, 4), "BILL_PAY", plain(9912), "", "P02", m(300), m(0), m(0));
        settle(DAY, "P02", id, m(100));
        settle(DAY, "P02", id, m(200));                                                                       // two lines
        psBenign.add(id);
        id = txn(at(23, 50), "BILL_PAY", plain(9913), "", "P03", m(90), m(0), m(0));
        settle(DAY.plusDays(1), "P03", id, m(90));                                                            // settled next day
        psBenign.add(id);
        breaks.put("mm_partner_settlement", ps);
        benignBreaks.put("mm_partner_settlement", psBenign);
        return this;
    }

    private void split(String agent, String wallet, int n, BigDecimal amount, int hour) {
        for (int c = 0; c < n; c++) cashIn(at(hour, 5 * c + rnd.nextInt(5)), wallet, agent, amount);
    }

    /** {@code n} wallets each cash in 300 at {@code inAgent} and cash out {@code outAmount} at {@code outAgent}. */
    private void roundTrips(String inAgent, String outAgent, int firstWallet, int n, int minutesLater, BigDecimal outAmount) {
        for (int c = 0; c < n; c++) {
            String w = wallet(firstWallet + c);
            register(w, msisdn(firstWallet + c), 2, "ACTIVE", DAY.minusDays(4), "ACTIVE");
            LocalDateTime t = at(18, 10 * c);
            cashIn(t, w, inAgent, m(300));
            cashOut(t.plusMinutes(minutesLater), w, outAgent, outAmount);
        }
    }

    private void dormant(int i, LocalDate lastActivity, String type, BigDecimal amount) {
        register(wallet(i), msisdn(i), 3, "ACTIVE", lastActivity, "ACTIVE");
        LocalDateTime t = at(20, i % 60);
        switch (type) {
            case "P2P" -> p2p(t, wallet(i), amount);
            case "CASH_OUT" -> cashOut(t, wallet(i), "A31", amount);
            default -> cashIn(t, wallet(i), "A32", amount);
        }
    }

    private void kyc(int i, int tier) { register(wallet(i), msisdn(i), tier, "ACTIVE", DAY.minusDays(2), "ACTIVE"); }

    /** Sample path (relative to {@code data/samples}) → file content. */
    Map<String, String> files() {
        String d = DAY.toString().replace("-", "");
        Map<String, String> out = new LinkedHashMap<>();
        out.put("wallet_txn/WALLET_TXN_" + d + ".csv", txns.toString());
        out.put("bank_statement/BANK_STATEMENT_" + d + ".csv", bank.toString());
        out.put("partner_settlement/PARTNER_SETTLEMENT_" + d + ".csv", settlement.toString());
        out.put("wallets/WALLETS_" + d + ".csv", wallets.toString());
        out.put("core_subscribers/CORE_SUBSCRIBERS_" + d + ".csv", core.toString());
        return out;
    }
}
