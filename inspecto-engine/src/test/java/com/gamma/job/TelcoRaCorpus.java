package com.gamma.job;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;

/**
 * The deterministic synthetic golden corpus of the {@code telco-ra} Space Template ({@code ASSURE-PACK-TELCO-RA-1}).
 * Fixed seed, canonical schemas, no real data. Every leakage it plants is recorded in {@link #planted}; every benign
 * look-alike (a difference a correct control must tolerate) in {@link #benign}. Money is held in integer cents so
 * the planted differences are exact.
 *
 * <p>Feeds: {@code switch_xdr} (network truth), {@code mediated_xdr}, {@code rated_usage}, {@code billed_invoice},
 * {@code tariff}, {@code balance_ledger} (prepaid roll-forward), {@code ic_rates} and {@code ic_statement}
 * (interconnect settlement).
 */
final class TelcoRaCorpus {

    static final long SEED = 20260930L;
    static final int XDRS = 600;
    static final int SUBSCRIBERS = 40;
    static final String[] DAYS = {"2026-07-01", "2026-07-02", "2026-07-03"};
    static final String[] PARTNERS = {"IC_P1", "IC_P2", "IC_P3"};
    static final Map<String, String> PARTNER_RATE = Map.of("IC_P1", "0.0150", "IC_P2", "0.0200", "IC_P3", "0.0120");
    /** rate per unit (second / event / MB) by plan and service. PROMO zero-rates DATA — a benign look-alike. */
    static final Map<String, String> TARIFF = new TreeMap<>(Map.of(
            "PLAN_A|VOICE", "0.0020", "PLAN_A|SMS", "0.0500", "PLAN_A|DATA", "0.0100",
            "PLAN_B|VOICE", "0.0015", "PLAN_B|SMS", "0.0400", "PLAN_B|DATA", "0.0080",
            "PROMO|VOICE", "0.0020", "PROMO|SMS", "0.0500", "PROMO|DATA", "0.0000"));

    /** file (relative to {@code data/samples}) → CSV content. */
    final Map<String, String> files = new LinkedHashMap<>();
    /** control → the item keys it must flag (exactly these). */
    final Map<String, Set<String>> planted = new LinkedHashMap<>();
    /** control → the benign look-alike keys it must NOT flag. */
    final Map<String, Set<String>> benign = new LinkedHashMap<>();

    private record Xdr(String id, String sub, String service, String date, String ts, long bNumber, String partner,
                       long units) {}

    static TelcoRaCorpus generate() {
        TelcoRaCorpus c = new TelcoRaCorpus();
        c.build(new Random(SEED));
        return c;
    }

    private void build(Random rnd) {
        // ── subscribers and their plans ──
        Map<String, String> plan = new LinkedHashMap<>();
        String[] plans = {"PLAN_A", "PLAN_B", "PROMO"};
        for (int i = 1; i <= SUBSCRIBERS; i++) plan.put(String.format("S%03d", i), plans[rnd.nextInt(plans.length)]);
        List<String> subs = new ArrayList<>(plan.keySet());

        // ── switch xDRs (network truth) ──
        List<Xdr> xdrs = new ArrayList<>();
        for (int i = 1; i <= XDRS; i++) {
            int r = rnd.nextInt(10);
            String service = r < 5 ? "VOICE" : r < 8 ? "SMS" : "DATA";
            long units = switch (service) {
                case "VOICE" -> 5 + rnd.nextInt(1796);
                case "SMS" -> 1;
                default -> 1 + rnd.nextInt(500);
            };
            String date = DAYS[rnd.nextInt(DAYS.length)];
            int sec = rnd.nextInt(86_400);
            String ts = date + String.format(" %02d:%02d:%02d", sec / 3600, sec / 60 % 60, sec % 60);
            String partner = "VOICE".equals(service) && rnd.nextInt(2) == 0 ? PARTNERS[rnd.nextInt(PARTNERS.length)] : "";
            xdrs.add(new Xdr(String.format("X%06d", i), subs.get(rnd.nextInt(subs.size())), service, date, ts,
                    9_000_000_000L + rnd.nextInt(1_000_000_000), partner, units));
        }

        // ── choose planted / benign xDRs, disjoint ──
        List<Xdr> pool = new ArrayList<>(xdrs);
        Collections.shuffle(pool, rnd);
        Set<String> droppedAtMediation = take(pool, 4, x -> true);
        Set<String> droppedAtRating = take(pool, 3, x -> true);
        Set<String> truncated = take(pool, 2, x -> "VOICE".equals(x.service) && x.units >= 300);
        Set<String> roundedByOne = take(pool, 4, x -> "VOICE".equals(x.service) && x.units >= 10);
        Set<String> underRated = take(pool, 5, x -> cents(x.units, rate(plan, x)) >= 50);
        Set<String> centOff = take(pool, 5, x -> cents(x.units, rate(plan, x)) >= 5);

        planted.put("ra_xdr_completeness", new LinkedHashSet<>());
        for (String id : droppedAtMediation) {
            planted.get("ra_xdr_completeness").add("AB|missing_right|" + id);
            planted.get("ra_xdr_completeness").add("AC|missing_right|" + id);
        }
        for (String id : droppedAtRating) planted.get("ra_xdr_completeness").add("AC|missing_right|" + id);
        for (String id : truncated) {
            planted.get("ra_xdr_completeness").add("AB|value_break|" + id);
            planted.get("ra_xdr_completeness").add("AC|value_break|" + id);
        }
        benign.put("ra_xdr_completeness", roundedByOne);
        planted.put("ra_rerating", underRated);
        benign.put("ra_rerating", centOff);

        StringBuilder sw = new StringBuilder("XDR_ID,EVENT_TS,EVENT_DATE,SUBSCRIBER_ID,B_NUMBER,SERVICE,PARTNER,USAGE_UNITS\n");
        StringBuilder md = new StringBuilder("XDR_ID,EVENT_DATE,SUBSCRIBER_ID,SERVICE,USAGE_UNITS\n");
        StringBuilder rt = new StringBuilder("XDR_ID,EVENT_DATE,SUBSCRIBER_ID,PLAN,SERVICE,USAGE_UNITS,CHARGE\n");
        Map<String, Long> ratedCentsBySub = new TreeMap<>();
        for (Xdr x : xdrs) {
            sw.append(x.id).append(',').append(x.ts).append(',').append(x.date).append(',').append(x.sub).append(',')
                    .append(x.bNumber).append(',').append(x.service).append(',').append(x.partner).append(',')
                    .append(x.units).append('\n');
            if (droppedAtMediation.contains(x.id)) continue;
            long mediated = truncated.contains(x.id) ? x.units - 120 : roundedByOne.contains(x.id) ? x.units - 1 : x.units;
            md.append(x.id).append(',').append(x.date).append(',').append(x.sub).append(',').append(x.service).append(',')
                    .append(mediated).append('\n');
            if (droppedAtRating.contains(x.id)) continue;
            long charge = cents(mediated, rate(plan, x));
            if (underRated.contains(x.id)) charge = charge / 2;          // a stale half rate: revenue leakage
            if (centOff.contains(x.id)) charge = charge - 1;             // one cent of rounding: benign
            rt.append(x.id).append(',').append(x.date).append(',').append(x.sub).append(',').append(plan.get(x.sub))
                    .append(',').append(x.service).append(',').append(mediated).append(',').append(money(charge)).append('\n');
            ratedCentsBySub.merge(x.sub, charge, Long::sum);
        }
        files.put("switch_xdr/SWITCH_XDR_20260703.csv", sw.toString());
        files.put("mediated_xdr/MEDIATED_XDR_20260703.csv", md.toString());
        files.put("rated_usage/RATED_USAGE_20260703.csv", rt.toString());

        StringBuilder tf = new StringBuilder("PLAN,SERVICE,RATE_PER_UNIT\n");
        TARIFF.forEach((k, v) -> tf.append(k.replace('|', ',')).append(',').append(v).append('\n'));
        files.put("tariff/TARIFF_20260701.csv", tf.toString());

        // ── rated → billed ──
        List<String> ratedSubs = new ArrayList<>(ratedCentsBySub.keySet());
        Collections.shuffle(ratedSubs, rnd);
        Set<String> unbilled = new LinkedHashSet<>(ratedSubs.subList(0, 1));
        Set<String> underBilled = new LinkedHashSet<>(ratedSubs.subList(1, 4));
        Set<String> billedTwoCentsOff = new LinkedHashSet<>(ratedSubs.subList(4, 7));
        planted.put("ra_rated_vs_billed", new LinkedHashSet<>());
        unbilled.forEach(s -> planted.get("ra_rated_vs_billed").add("AB|missing_right|" + s));
        underBilled.forEach(s -> planted.get("ra_rated_vs_billed").add("AB|value_break|" + s));
        benign.put("ra_rated_vs_billed", billedTwoCentsOff);
        StringBuilder bi = new StringBuilder("INVOICE_ID,BILL_PERIOD,SUBSCRIBER_ID,CHARGE\n");
        int inv = 1;
        for (Map.Entry<String, Long> e : ratedCentsBySub.entrySet()) {
            if (unbilled.contains(e.getKey())) continue;
            long billed = e.getValue();
            if (underBilled.contains(e.getKey())) billed -= 100 + rnd.nextInt(400);
            if (billedTwoCentsOff.contains(e.getKey())) billed += rnd.nextBoolean() ? 2 : -2;
            bi.append(String.format("INV%05d", inv++)).append(",2026-07,").append(e.getKey()).append(',')
                    .append(money(billed)).append('\n');
        }
        files.put("billed_invoice/BILLED_INVOICE_202607.csv", bi.toString());

        // ── prepaid balance roll-forward: opening + topups + adjustments − debits = closing ──
        List<String> subDays = new ArrayList<>();
        for (String s : subs) for (String d : DAYS) subDays.add(s + "|" + d);
        List<String> shuffled = new ArrayList<>(subDays);
        Collections.shuffle(shuffled, rnd);
        Set<String> drift = new LinkedHashSet<>(shuffled.subList(0, 3));
        Set<String> goodwill = new LinkedHashSet<>(shuffled.subList(3, 6));
        planted.put("ra_rollforward", drift);
        benign.put("ra_rollforward", goodwill);
        StringBuilder bl = new StringBuilder("SUBSCRIBER_ID,BALANCE_DATE,OPENING,TOPUPS,ADJUSTMENTS,USAGE_DEBITS,CLOSING\n");
        for (String s : subs) {
            long balance = 1000 + rnd.nextInt(4001);
            for (String d : DAYS) {
                long topups = rnd.nextInt(3) == 0 ? (rnd.nextBoolean() ? 1000 : 2000) : 0;
                long debits = rnd.nextInt(401);
                long adjustments = goodwill.contains(s + "|" + d) ? 300 : 0;   // a goodwill credit explains the move
                long closing = balance + topups + adjustments - debits;
                if (drift.contains(s + "|" + d)) closing -= 500;              // 5.00 vanishes unexplained
                bl.append(s).append(',').append(d).append(',').append(money(balance)).append(',').append(money(topups))
                        .append(',').append(money(adjustments)).append(',').append(money(debits)).append(',')
                        .append(money(closing)).append('\n');
                balance = closing;
            }
        }
        files.put("balance_ledger/BALANCE_LEDGER_20260703.csv", bl.toString());

        // ── interconnect settlement: the partner's statement against our switch minutes × agreed rate ──
        Map<String, Long> minutes = new TreeMap<>();
        for (Xdr x : xdrs)
            if (!x.partner.isEmpty()) minutes.merge(x.partner + "|" + x.date, (x.units + 59) / 60, Long::sum);
        List<String> partnerDays = new ArrayList<>(minutes.keySet());
        Collections.shuffle(partnerDays, rnd);
        Set<String> overBilled = new LinkedHashSet<>(partnerDays.subList(0, 2));
        Set<String> slightlyOver = new LinkedHashSet<>(partnerDays.subList(2, 4));
        planted.put("ra_settlement", overBilled);
        benign.put("ra_settlement", slightlyOver);
        StringBuilder ir = new StringBuilder("PARTNER,RATE_PER_MIN\n");
        new TreeMap<>(PARTNER_RATE).forEach((p, r) -> ir.append(p).append(',').append(r).append('\n'));
        files.put("ic_rates/IC_RATES_20260701.csv", ir.toString());
        StringBuilder st = new StringBuilder("STATEMENT_ID,PARTNER,EVENT_DATE,MINUTES,AMOUNT\n");
        int line = 1;
        for (Map.Entry<String, Long> e : minutes.entrySet()) {
            String[] pd = e.getKey().split("\\|");
            long mins = e.getValue();
            long expected = cents(mins, PARTNER_RATE.get(pd[0]));
            long amount = expected;
            if (overBilled.contains(e.getKey())) { mins = mins * 11 / 10 + 1; amount = expected * 11 / 10 + 1; }
            if (slightlyOver.contains(e.getKey())) amount = expected + expected * 4 / 1000;   // 0.4 %: inside 1 %
            st.append(String.format("ST%04d", line++)).append(',').append(pd[0]).append(',').append(pd[1]).append(',')
                    .append(mins).append(',').append(money(amount)).append('\n');
        }
        files.put("ic_statement/IC_STATEMENT_20260703.csv", st.toString());
    }

    private static Set<String> take(List<Xdr> pool, int n, java.util.function.Predicate<Xdr> ok) {
        Set<String> out = new LinkedHashSet<>();
        for (var it = pool.iterator(); it.hasNext() && out.size() < n; ) {
            Xdr x = it.next();
            if (ok.test(x)) { out.add(x.id); it.remove(); }
        }
        if (out.size() < n) throw new IllegalStateException("corpus too small to plant " + n);
        return out;
    }

    private static String rate(Map<String, String> plan, Xdr x) {
        return TARIFF.get(plan.get(x.sub) + "|" + x.service);
    }

    /** units × rate, rounded half-up to whole cents. */
    static long cents(long units, String rate) {
        return new BigDecimal(rate).multiply(BigDecimal.valueOf(units)).movePointRight(2)
                .setScale(0, RoundingMode.HALF_UP).longValueExact();
    }

    static String money(long cents) {
        return BigDecimal.valueOf(cents, 2).toPlainString();
    }

    /** Write the corpus under {@code samplesDir}. */
    void writeTo(Path samplesDir) throws IOException {
        for (Map.Entry<String, String> f : files.entrySet()) {
            Path p = samplesDir.resolve(f.getKey());
            Files.createDirectories(p.getParent());
            Files.writeString(p, f.getValue(), StandardCharsets.UTF_8);
        }
    }
}
