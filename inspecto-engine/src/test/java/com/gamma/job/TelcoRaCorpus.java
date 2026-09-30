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
import java.util.function.Predicate;

/**
 * The deterministic synthetic golden corpus of the {@code telco-ra} Space Template ({@code ASSURE-PACK-TELCO-RA-1}).
 * Fixed seed, canonical schemas, no real data: called numbers sit in the fictional {@code +1-555-01xx} range.
 * Every finding it plants is recorded in {@link #planted} as {@code key|reason} (Reconciliation Breaks as
 * {@code pair|type|key}); every benign look-alike, a difference a correct control must tolerate, in {@link #benign}.
 * Money is held in integer cents, so the planted differences are exact.
 *
 * <p>Feeds: {@code switch_xdr} (network truth), {@code mediated_xdr}, {@code rated_usage}, {@code billed_invoice},
 * {@code tariff} (with effective dates), {@code balance_ledger} (prepaid roll-forward), {@code ic_rates} and
 * {@code ic_statement} (interconnect settlement).
 */
final class TelcoRaCorpus {

    static final long SEED = 20260930L;
    static final int XDRS = 600;
    static final int SUBSCRIBERS = 40;
    static final String[] DAYS = {"2026-07-01", "2026-07-02", "2026-07-03"};
    static final String[] PARTNERS = {"IC_P1", "IC_P2", "IC_P3"};
    static final String UNKNOWN_PARTNER = "IC_P9";
    static final Map<String, String> PARTNER_RATE = new TreeMap<>(Map.of("IC_P1", "0.0500", "IC_P2", "0.0800", "IC_P3", "0.0600"));
    static final String OPEN_FROM = "2026-01-01 00:00:00";
    /** PLAN_A VOICE changes rate at this instant; a call is rated at its START. */
    static final String CHANGE_TS = "2026-07-02 12:00:00";
    /** A duplicate PLAN_B DATA tariff row overlaps the base row from here: every such call is ambiguous. */
    static final String OVERLAP_FROM = "2026-07-03 00:00:00";
    /** rate per unit (second / event / MB) by plan and service. PROMO zero-rates DATA. */
    static final Map<String, String> BASE_RATE = new TreeMap<>(Map.of(
            "PLAN_A|VOICE", "0.0020", "PLAN_A|SMS", "0.0500", "PLAN_A|DATA", "0.0100",
            "PLAN_B|VOICE", "0.0015", "PLAN_B|SMS", "0.0400", "PLAN_B|DATA", "0.0080",
            "PROMO|VOICE", "0.0020", "PROMO|SMS", "0.0500", "PROMO|DATA", "0.0000"));
    static final String PLAN_A_VOICE_NEW = "0.0025";

    /** file (relative to {@code data/samples}) → CSV content. */
    final Map<String, String> files = new LinkedHashMap<>();
    /** control → the findings it must emit, exactly these. */
    final Map<String, Set<String>> planted = new LinkedHashMap<>();
    /** control → the benign look-alike keys it must NOT flag. */
    final Map<String, Set<String>> benign = new LinkedHashMap<>();
    /** The distinct xDRs lost or short between switch and rating, next to the Break count that double-counts them. */
    final Set<String> lostOrShortXdrs = new LinkedHashSet<>();

    private record Xdr(String id, String sub, String service, String date, String ts, String bNumber, String partner,
                       long units) {}

    static TelcoRaCorpus generate() {
        TelcoRaCorpus c = new TelcoRaCorpus();
        c.build(new Random(SEED));
        return c;
    }

    private Map<String, String> plan;

    private void build(Random rnd) {
        plan = new LinkedHashMap<>();
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
                    String.format("+1-555-01%02d", rnd.nextInt(100)), partner, units));
        }

        // ── choose planted / benign xDRs, disjoint ──
        List<Xdr> pool = new ArrayList<>(xdrs);
        Collections.shuffle(pool, rnd);
        // a benign call that STARTS before the PLAN_A VOICE change and runs past it: rated at the start rate
        Xdr spanning = takeOne(pool, x -> "PLAN_A".equals(plan.get(x.sub)) && "VOICE".equals(x.service));
        Xdr spanned = new Xdr(spanning.id, spanning.sub, "VOICE", "2026-07-02", "2026-07-02 11:50:00", spanning.bNumber,
                spanning.partner, 1200);
        xdrs.set(xdrs.indexOf(spanning), spanned);

        Predicate<Xdr> unambiguous = x -> !ambiguous(x);
        Set<String> droppedAtMediation = take(pool, 4, x -> true);
        Set<String> droppedAtRating = take(pool, 3, x -> true);
        Set<String> truncated = take(pool, 2, x -> "VOICE".equals(x.service) && x.units >= 300);
        Set<String> roundedByOne = take(pool, 4, x -> "VOICE".equals(x.service) && x.units >= 10);
        Set<String> underRated = take(pool, 2, unambiguous.and(x -> cents(x.units, rate(x)) >= 50));
        Set<String> staleRate = take(pool, 3, x -> "PLAN_A".equals(plan.get(x.sub)) && "VOICE".equals(x.service)
                && x.ts.compareTo(CHANGE_TS) >= 0 && x.units >= 200);
        Set<String> centOff = take(pool, 5, unambiguous.and(x -> cents(x.units, rate(x)) >= 5));

        Set<String> completeness = new LinkedHashSet<>();
        for (String id : droppedAtMediation) {
            completeness.add("AB|missing_right|" + id);
            completeness.add("AC|missing_right|" + id);
        }
        for (String id : droppedAtRating) completeness.add("AC|missing_right|" + id);
        for (String id : truncated) {
            completeness.add("AB|value_break|" + id);
            completeness.add("AC|value_break|" + id);
        }
        planted.put("ra_xdr_completeness", completeness);
        benign.put("ra_xdr_completeness", roundedByOne);
        Set<String> lost = new LinkedHashSet<>();
        droppedAtMediation.forEach(id -> lost.add(id + "|lost_at_mediation"));
        droppedAtRating.forEach(id -> lost.add(id + "|lost_at_rating"));
        truncated.forEach(id -> lost.add(id + "|short"));
        planted.put("ra_xdr_lost", lost);
        benign.put("ra_xdr_lost", roundedByOne);
        lostOrShortXdrs.addAll(droppedAtMediation);
        lostOrShortXdrs.addAll(droppedAtRating);
        lostOrShortXdrs.addAll(truncated);

        Set<String> rerating = new LinkedHashSet<>();
        underRated.forEach(id -> rerating.add(id + "|rate_mismatch"));
        staleRate.forEach(id -> rerating.add(id + "|rate_mismatch"));
        Set<String> rerateBenign = new LinkedHashSet<>(centOff);
        rerateBenign.add(spanned.id);

        StringBuilder sw = new StringBuilder("XDR_ID,EVENT_TS,EVENT_DATE,SUBSCRIBER_ID,B_NUMBER,SERVICE,PARTNER,USAGE_UNITS\n");
        StringBuilder md = new StringBuilder("XDR_ID,EVENT_DATE,SUBSCRIBER_ID,SERVICE,USAGE_UNITS\n");
        StringBuilder rt = new StringBuilder("XDR_ID,EVENT_TS,EVENT_DATE,SUBSCRIBER_ID,PLAN,SERVICE,USAGE_UNITS,CHARGE\n");
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
            long charge = cents(mediated, rate(x));
            if (underRated.contains(x.id)) charge = charge / 2;                              // a stale half rate
            if (staleRate.contains(x.id)) charge = cents(mediated, BASE_RATE.get("PLAN_A|VOICE"));   // pre-change rate
            if (centOff.contains(x.id)) charge = charge - 1;                                  // one cent: benign
            if (ambiguous(x)) rerating.add(x.id + "|ambiguous_tariff");
            rt.append(x.id).append(',').append(x.ts).append(',').append(x.date).append(',').append(x.sub).append(',')
                    .append(plan.get(x.sub)).append(',').append(x.service).append(',').append(mediated).append(',')
                    .append(money(charge)).append('\n');
            ratedCentsBySub.merge(x.sub, charge, Long::sum);
        }
        planted.put("ra_rerating", rerating);
        benign.put("ra_rerating", rerateBenign);
        files.put("switch_xdr/SWITCH_XDR_20260703.csv", sw.toString());
        files.put("mediated_xdr/MEDIATED_XDR_20260703.csv", md.toString());
        files.put("rated_usage/RATED_USAGE_20260703.csv", rt.toString());

        StringBuilder tf = new StringBuilder("PLAN,SERVICE,RATE_PER_UNIT,EFFECTIVE_FROM,EFFECTIVE_TO\n");
        for (Map.Entry<String, String> e : BASE_RATE.entrySet()) {
            String ps = e.getKey().replace('|', ',');
            if ("PLAN_A|VOICE".equals(e.getKey())) {
                tf.append(ps).append(',').append(e.getValue()).append(',').append(OPEN_FROM).append(',').append(CHANGE_TS).append('\n');
                tf.append(ps).append(',').append(PLAN_A_VOICE_NEW).append(',').append(CHANGE_TS).append(",\n");
            } else {
                tf.append(ps).append(',').append(e.getValue()).append(',').append(OPEN_FROM).append(",\n");
            }
        }
        tf.append("PLAN_B,DATA,").append(BASE_RATE.get("PLAN_B|DATA")).append(',').append(OVERLAP_FROM).append(",\n");
        files.put("tariff/TARIFF_20260701.csv", tf.toString());

        // ── rated → billed ──
        List<String> ratedSubs = new ArrayList<>(ratedCentsBySub.keySet());
        Collections.shuffle(ratedSubs, rnd);
        Set<String> unbilled = new LinkedHashSet<>(ratedSubs.subList(0, 1));
        Set<String> underBilled = new LinkedHashSet<>(ratedSubs.subList(1, 4));
        Set<String> billedFourCentsOff = new LinkedHashSet<>(ratedSubs.subList(4, 7));   // just under 0.05
        Set<String> rvb = new LinkedHashSet<>();
        unbilled.forEach(s -> rvb.add("AB|missing_right|" + s));
        underBilled.forEach(s -> rvb.add("AB|value_break|" + s));
        planted.put("ra_rated_vs_billed", rvb);
        benign.put("ra_rated_vs_billed", billedFourCentsOff);
        StringBuilder bi = new StringBuilder("INVOICE_ID,BILL_PERIOD,SUBSCRIBER_ID,CHARGE\n");
        int inv = 1;
        for (Map.Entry<String, Long> e : ratedCentsBySub.entrySet()) {
            if (unbilled.contains(e.getKey())) continue;
            long billed = e.getValue();
            if (underBilled.contains(e.getKey())) billed -= 100 + rnd.nextInt(400);
            if (billedFourCentsOff.contains(e.getKey())) billed += rnd.nextBoolean() ? 4 : -4;
            bi.append(String.format("INV%05d", inv++)).append(",2026-07,").append(e.getKey()).append(',')
                    .append(money(billed)).append('\n');
        }
        files.put("billed_invoice/BILLED_INVOICE_202607.csv", bi.toString());

        // ── prepaid balance roll-forward: opening + topups + adjustments − debits = closing, and day N's
        //    opening = day N−1's closing ──
        List<String> laterDays = new ArrayList<>(), allDays = new ArrayList<>();
        for (String s : subs) for (int d = 0; d < DAYS.length; d++) {
            allDays.add(s + "|" + DAYS[d]);
            if (d > 0) laterDays.add(s + "|" + DAYS[d]);
        }
        Collections.shuffle(laterDays, rnd);
        Set<String> minted = new LinkedHashSet<>(laterDays.subList(0, 2));      // 7.00 appears between days
        Set<String> nullOpening = new LinkedHashSet<>(laterDays.subList(2, 3)); // an opening the feed left blank
        List<String> rest = new ArrayList<>(allDays);
        rest.removeAll(minted);
        rest.removeAll(nullOpening);
        Collections.shuffle(rest, rnd);
        Set<String> drift = new LinkedHashSet<>(rest.subList(0, 3));            // 5.00 vanishes within the day
        Set<String> goodwill = new LinkedHashSet<>(rest.subList(3, 6));         // an adjustment explains the move
        Set<String> offByFour = new LinkedHashSet<>(rest.subList(6, 8));        // 0.04: just under the 0.05 tolerance
        Set<String> rf = new LinkedHashSet<>();
        drift.forEach(k -> rf.add(k + "|movement"));
        minted.forEach(k -> rf.add(k + "|continuity"));
        nullOpening.forEach(k -> rf.add(k + "|null_value"));
        planted.put("ra_rollforward", rf);
        Set<String> rfBenign = new LinkedHashSet<>(goodwill);
        rfBenign.addAll(offByFour);
        benign.put("ra_rollforward", rfBenign);
        StringBuilder bl = new StringBuilder("SUBSCRIBER_ID,BALANCE_DATE,OPENING,TOPUPS,ADJUSTMENTS,USAGE_DEBITS,CLOSING\n");
        for (String s : subs) {
            long balance = 1000 + rnd.nextInt(4001);
            for (String d : DAYS) {
                String k = s + "|" + d;
                if (minted.contains(k)) balance += 700;
                long topups = rnd.nextInt(3) == 0 ? (rnd.nextBoolean() ? 1000 : 2000) : 0;
                long debits = rnd.nextInt(401);
                long adjustments = goodwill.contains(k) ? 300 : 0;
                long closing = balance + topups + adjustments - debits;
                if (drift.contains(k)) closing -= 500;
                if (offByFour.contains(k)) closing -= 4;
                bl.append(s).append(',').append(d).append(',').append(nullOpening.contains(k) ? "" : money(balance))
                        .append(',').append(money(topups)).append(',').append(money(adjustments)).append(',')
                        .append(money(debits)).append(',').append(money(closing)).append('\n');
                balance = closing;
            }
        }
        files.put("balance_ledger/BALANCE_LEDGER_20260703.csv", bl.toString());

        // ── interconnect settlement ──
        Map<String, Long> minutes = new TreeMap<>();
        for (Xdr x : xdrs)
            if (!x.partner.isEmpty()) minutes.merge(x.partner + "|" + x.date, (x.units + 59) / 60, Long::sum);
        List<String> partnerDays = new ArrayList<>(minutes.keySet());
        Collections.shuffle(partnerDays, rnd);
        Set<String> overBilled = new LinkedHashSet<>(), missingStatement = new LinkedHashSet<>(),
                justUnder = new LinkedHashSet<>();
        for (String pd : partnerDays) {
            long expected = cents(minutes.get(pd), PARTNER_RATE.get(pd.split("\\|")[0]));
            if (overBilled.size() < 2) overBilled.add(pd);
            else if (missingStatement.isEmpty()) missingStatement.add(pd);
            else if (justUnder.size() < 2 && expected >= 1000) justUnder.add(pd);   // 0.9 % of ≥ 10.00
        }
        if (justUnder.size() < 2) throw new IllegalStateException("corpus too small for the settlement boundary cases");
        String unknownKey = UNKNOWN_PARTNER + "|" + DAYS[1];
        Set<String> st = new LinkedHashSet<>();
        overBilled.forEach(k -> st.add(k + "|amount_mismatch"));
        missingStatement.forEach(k -> st.add(k + "|missing_statement"));
        st.add(unknownKey + "|unknown_partner");
        planted.put("ra_settlement", st);
        benign.put("ra_settlement", justUnder);
        StringBuilder ir = new StringBuilder("PARTNER,RATE_PER_MIN\n");
        PARTNER_RATE.forEach((p, r) -> ir.append(p).append(',').append(r).append('\n'));
        files.put("ic_rates/IC_RATES_20260701.csv", ir.toString());
        StringBuilder sb = new StringBuilder("STATEMENT_ID,PARTNER,EVENT_DATE,MINUTES,AMOUNT\n");
        int line = 1;
        for (Map.Entry<String, Long> e : minutes.entrySet()) {
            if (missingStatement.contains(e.getKey())) continue;
            String[] pd = e.getKey().split("\\|");
            long mins = e.getValue();
            long expected = cents(mins, PARTNER_RATE.get(pd[0]));
            long amount = expected;
            if (overBilled.contains(e.getKey())) { mins = mins * 11 / 10 + 1; amount = expected * 11 / 10 + 1; }
            if (justUnder.contains(e.getKey())) amount = expected + expected * 9 / 1000;
            sb.append(String.format("ST%04d", line++)).append(',').append(pd[0]).append(',').append(pd[1]).append(',')
                    .append(mins).append(',').append(money(amount)).append('\n');
        }
        sb.append(String.format("ST%04d", line)).append(',').append(UNKNOWN_PARTNER).append(',').append(DAYS[1])
                .append(",90,45.00\n");
        files.put("ic_statement/IC_STATEMENT_20260703.csv", sb.toString());
    }

    /** A PLAN_B DATA call on or after the duplicate tariff row's start matches two tariff rows. */
    private boolean ambiguous(Xdr x) {
        return "PLAN_B".equals(plan.get(x.sub)) && "DATA".equals(x.service) && x.ts.compareTo(OVERLAP_FROM) >= 0;
    }

    /** The rate in force at the call's START. */
    private String rate(Xdr x) {
        String key = plan.get(x.sub) + "|" + x.service;
        if ("PLAN_A|VOICE".equals(key) && x.ts.compareTo(CHANGE_TS) >= 0) return PLAN_A_VOICE_NEW;
        return BASE_RATE.get(key);
    }

    private static Xdr takeOne(List<Xdr> pool, Predicate<Xdr> ok) {
        for (var it = pool.iterator(); it.hasNext(); ) {
            Xdr x = it.next();
            if (ok.test(x)) { it.remove(); return x; }
        }
        throw new IllegalStateException("corpus too small");
    }

    private static Set<String> take(List<Xdr> pool, int n, Predicate<Xdr> ok) {
        Set<String> out = new LinkedHashSet<>();
        for (var it = pool.iterator(); it.hasNext() && out.size() < n; ) {
            Xdr x = it.next();
            if (ok.test(x)) { out.add(x.id); it.remove(); }
        }
        if (out.size() < n) throw new IllegalStateException("corpus too small to plant " + n);
        return out;
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
