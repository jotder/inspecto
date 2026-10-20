package com.gamma.control;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code TELCO-BYPASS-SIMBOX-1} slice B3 golden test. The shipped {@code telco-bypass} Space Template, copied verbatim,
 * ingests the synthetic {@link BypassCorpus} (B3 extension) through its own Pipelines, runs the six nightly Jobs as authored
 * and sweeps the shipped Alert Rules with the production {@link com.gamma.alert.AlertService}. Held to:
 * <ul>
 *   <li>an independent plain-Java oracle, written from the raw call rows and the plan, for every member SIM of every box
 *       (its key, the sizes of all three keys, the IMEI count, the minutes);</li>
 *   <li>the planted boxes by name: P1 (one device), P2 (slow-roll, one device, every SIM below the SIM-day floor), P3 (swaps IMEIs,
 *       caught by its cell), the two devices of a farm at one cell, and the smallest box of five;</li>
 *   <li>every look-alike silent for its planted reason: the dense building, the receiving block, one short of a box, the
 *       registered and unregistered PBX pair, and the box nobody can see without a device column;</li>
 *   <li>one Incident per box, none more on a second night or a re-run, the member SIM Alerts counted in the box row;</li>
 *   <li>the same boxes through the cell key when the feed carries no device column, and nothing raised in shadow mode.</li>
 * </ul>
 */
class TelcoBypassClusterGoldenTest {

    private static final String DAY1 = "2026-07-01", DAY2 = "2026-07-02";
    private static final String P1 = "imei:990000000000001", P2 = "imei:990000000000002", P3 = "cell:C0003",
            X4A = "imei:990000000000700", X4B = "imei:990000000000701", X7 = "imei:990000000000730";

    // ------------------------------------------------------------------------------------------- the oracle

    /** One member SIM as the oracle derives it. {@code kind} is null for a SIM that is not in a box. */
    private record M(String msisdn, String kind, String key, String imei, String cell, String batch, int imeiSims, int cellSims,
                     int batchSims, int imeiCount, int calls, double minutes) {}

    /** Per-SIM accumulators over one day of calls. */
    private static final class Acc {
        final List<BypassCorpus.Call> mo = new ArrayList<>();
        int events, voiceEvents, mtVoice;
    }

    /**
     * The boxes of one day from the raw call rows, in plain Java: the five clauses (strict) at the candidate volume floor, the
     * allow list, the three key sizes and the first key that reaches the box size (device, cell, batch). Written from plan
     * section 4.3 and the shipped parameter values, not from the SQL.
     *
     * @param withImei false: the feed carries no device column, every IMEI reads as absent
     */
    private static Map<String, M> oracle(List<BypassCorpus.Call> all, List<BypassCorpus.Profile> profiles, LocalDate day, Set<String> allowed,
                                         boolean withImei) {
        LocalDateTime lo = day.atStartOfDay(), hi = day.plusDays(1).atStartOfDay();
        Map<String, Acc> acc = new TreeMap<>();
        for (BypassCorpus.Call c : all) {
            if (c.ts().isBefore(lo) || !c.ts().isBefore(hi)) continue;
            if (c.direction().equals("MO")) {
                Acc a = acc.computeIfAbsent(c.a(), k -> new Acc());
                a.events++;
                if (c.service().equals("VOICE")) {
                    a.voiceEvents++;
                    if (!c.roaming().equals("Y")) a.mo.add(c);
                }
            } else {
                Acc a = acc.computeIfAbsent(c.b(), k -> new Acc());
                a.events++;
                if (c.service().equals("VOICE")) {
                    a.voiceEvents++;
                    a.mtVoice++;
                }
            }
        }
        Map<String, BypassCorpus.Profile> prof = new HashMap<>();
        for (BypassCorpus.Profile p : profiles) prof.put(p.msisdn(), p);
        List<M> cands = new ArrayList<>();
        for (Map.Entry<String, Acc> e : acc.entrySet()) {
            Acc a = e.getValue();
            int n = a.mo.size();
            if (n < 10) continue;
            Set<String> targets = new TreeSet<>();
            Map<String, Integer> cells = new TreeMap<>(), imeis = new TreeMap<>();
            int answered = 0, shortCalls = 0, seconds = 0;
            for (BypassCorpus.Call c : a.mo) {
                targets.add(c.b());
                cells.merge(c.cell(), 1, Integer::sum);
                if (withImei && !c.imei().isEmpty()) imeis.merge(c.imei(), 1, Integer::sum);
                if (c.dur() > 0) {
                    answered++;
                    seconds += c.dur();
                    if (c.dur() <= 60) shortCalls++;
                }
            }
            if (answered == 0) continue;
            double inbound = (double) a.mtVoice / (a.mtVoice + n);
            double bDiv = (double) targets.size() / n;
            double shortRatio = (double) shortCalls / answered;
            double voiceOnly = (double) a.voiceEvents / a.events;
            String topCell = null;
            int topN = 0;
            for (Map.Entry<String, Integer> c : cells.entrySet())
                if (c.getValue() >= topN) { topN = c.getValue(); topCell = c.getKey(); }   // ascending order: the larger id wins a tie
            double topShare = (double) topN / n;
            if (!(inbound < 0.05 && bDiv > 0.9 && shortRatio > 0.9 && voiceOnly > 0.99 && topShare > 0.9)) continue;
            if (allowed.contains(e.getKey())) continue;
            String imei = null;
            int imeiN = 0;
            for (Map.Entry<String, Integer> c : imeis.entrySet())
                if (c.getValue() >= imeiN) { imeiN = c.getValue(); imei = c.getKey(); }
            BypassCorpus.Profile p = prof.get(e.getKey());
            String batch = p == null ? null : p.dealer() + "/" + p.activatedOn();
            double minutes = BigDecimal.valueOf(seconds).divide(BigDecimal.valueOf(60), 10, RoundingMode.HALF_UP).setScale(1, RoundingMode.HALF_UP).doubleValue();
            cands.add(new M(e.getKey(), null, null, imei, topCell, batch, 0, 0, 0, imeis.size(), n, minutes));
        }
        Map<String, Integer> byImei = new HashMap<>(), byCell = new HashMap<>(), byBatch = new HashMap<>();
        for (M m : cands) {
            if (m.imei != null) byImei.merge(m.imei, 1, Integer::sum);
            byCell.merge(m.cell, 1, Integer::sum);
            if (m.batch != null) byBatch.merge(m.batch, 1, Integer::sum);
        }
        Map<String, M> out = new TreeMap<>();
        for (M m : cands) {
            int si = m.imei == null ? 0 : byImei.get(m.imei), sc = byCell.get(m.cell), sb = m.batch == null ? 0 : byBatch.get(m.batch);
            String kind = si >= 5 ? "imei" : sc >= 5 ? "cell" : sb >= 5 ? "batch" : null;
            if (kind == null) continue;
            String key = kind + ":" + (kind.equals("imei") ? m.imei : kind.equals("cell") ? m.cell : m.batch);
            out.put(m.msisdn, new M(m.msisdn, kind, key, m.imei, m.cell, m.batch, si, sc, sb, m.imeiCount, m.calls, m.minutes));
        }
        return out;
    }

    private static Map<String, Set<String>> boxesOf(Map<String, M> members) {
        Map<String, Set<String>> out = new TreeMap<>();
        for (M m : members.values()) out.computeIfAbsent(m.key, k -> new TreeSet<>()).add(m.msisdn);
        return out;
    }

    private static void assertMembersEqualTheOracle(Map<String, M> want, Map<String, Map<String, Object>> got, String label) {
        assertEquals(want.keySet(), got.keySet(), label + ": the member SIMs");
        for (M m : want.values()) {
            Map<String, Object> r = got.get(m.msisdn);
            String at = label + " " + m.msisdn;
            assertEquals(m.kind, r.get("cluster_kind"), at);
            assertEquals(m.key, r.get("cluster_key"), at);
            assertEquals(m.imei, r.get("imei_key"), at + " imei_key");
            assertEquals(m.cell, r.get("cell_key"), at + " cell_key");
            assertEquals(m.batch, r.get("batch_key"), at + " batch_key");
            assertEquals(m.imeiSims, ((Number) r.get("imei_sims")).intValue(), at + " imei_sims");
            assertEquals(m.cellSims, ((Number) r.get("cell_sims")).intValue(), at + " cell_sims");
            assertEquals(m.batchSims, ((Number) r.get("batch_sims")).intValue(), at + " batch_sims");
            assertEquals(m.imeiCount, ((Number) r.get("imei_count")).intValue(), at + " imei_count");
            assertEquals(m.calls, ((Number) r.get("calls_mo")).intValue(), at + " calls_mo");
            assertEquals(m.minutes, ((Number) r.get("talk_minutes")).doubleValue(), 0.051, at + " talk_minutes");
        }
    }

    // ------------------------------------------------------------------------------------------- planted sets

    private static final class Planted {
        final BypassCorpus c;
        final Set<String> p1, p2, p3, x4a = new TreeSet<>(), x4b = new TreeSet<>(), x7;
        final String l2a, l2b;

        Planted(BypassCorpus c) {
            this.c = c;
            p1 = c.archetypes.get("P1");
            p2 = c.archetypes.get("P2");
            p3 = c.archetypes.get("P3");
            List<String> farm = new ArrayList<>(c.farm);
            for (int i = 0; i < farm.size(); i++) (i < 6 ? x4a : x4b).add(farm.get(i));
            x7 = c.smallestBox;
            List<String> l2 = new ArrayList<>(c.archetypes.get("L2"));
            l2a = l2.get(0);
            l2b = l2.get(1);
        }

        /** The boxes the corpus plants, by key. */
        Map<String, Set<String>> boxes() {
            Map<String, Set<String>> out = new TreeMap<>();
            out.put(P1, p1);
            out.put(P2, p2);
            out.put(P3, p3);
            out.put(X4A, x4a);
            out.put(X4B, x4b);
            out.put(X7, x7);
            return out;
        }
    }

    // ------------------------------------------------------------------------------------------- the golden run

    @Test
    void everyPlantedBoxIsOneIncidentTheLookAlikesAreSilentAndASecondNightUpdatesTheSameBox(@TempDir Path tmp) throws Exception {
        BypassCorpus corpus = new BypassCorpus().generateB3();
        Planted pl = new Planted(corpus);
        Path space = BypassPack.copyTemplate(tmp);
        Path data = space.resolve("data");
        BypassPack.ingest(space, corpus.files(true));
        // the Fraud Lead's registered PBX lines: one permanent entry, one whose verification lapses before night 2
        BypassPack.writeSidecar(data, "tb_allow", "allow", List.of(BypassPack.Entry.key(pl.l2a, null),
                BypassPack.Entry.key(pl.l2b, "2026-07-02 12:00:00")));

        // ---- night 1
        BypassPack.runNight(space, DAY1, Map.of());
        Map<String, M> want1 = oracle(corpus.calls(), corpus.profiles(), LocalDate.parse(DAY1), Set.of(pl.l2a, pl.l2b), true);
        Map<String, Map<String, Object>> m1 = BypassPack.rows(data, "tb_cluster_members", "msisdn", "window_date = DATE '" + DAY1 + "'");
        assertMembersEqualTheOracle(want1, m1, "night 1");
        assertEquals(pl.boxes(), boxesOf(want1), "the oracle finds exactly the planted boxes (so it is not merely mirroring the Job)");
        assertEquals(60 + 200 + 20 + 12 + 5, m1.size());
        Map<String, Map<String, Object>> c1 = BypassPack.rows(data, "tb_clusters", "cluster_key", "window_date = DATE '" + DAY1 + "'");
        assertEquals(pl.boxes().keySet(), c1.keySet());
        assertBoxRows(c1, pl, 1, false);

        BypassPack.Sweep sweep = new BypassPack.Sweep(space);
        Map<String, Set<String>> alerts1 = sweep.evaluate();
        assertEquals(pl.boxes().keySet(), alerts1.get("tb_box_suspected"), "night 1: one Incident per box, keyed by the box");
        assertEquals(6, sweep.incidents("tb_box_suspected"));
        assertNull(alerts1.get("tb_sim_high"), "night 1: persistence holds every SIM Alert back, the box Incident does not wait");
        assertEquals(6, sweep.incidents());

        // ---- look-alikes are silent for the planted reason
        for (String m : corpus.archetypes.get("L7")) assertFalse(m1.containsKey(m), "dense building " + m + " is not a candidate (SMS, data and received calls)");
        assertTrue(m1.values().stream().noneMatch(r -> "cell:C0009".equals(r.get("cluster_key"))), "80 SIMs on one cell make no box by themselves");
        for (String m : corpus.receivingBlock) assertFalse(m1.containsKey(m), "X5 " + m + " receives calls: no candidate clause holds");
        for (String m : corpus.oneShort) assertFalse(m1.containsKey(m), "X6 " + m + ": four candidates and a fifth under the floor are one short of a box");
        for (String m : corpus.archetypes.get("L3")) assertFalse(m1.containsKey(m), "the unregistered dialler pair shares a batch with the registered pair: four SIMs, below the box size");
        for (String m : corpus.archetypes.get("L1")) assertFalse(m1.containsKey(m));
        for (String m : corpus.archetypes.get("L5")) assertFalse(m1.containsKey(m));
        for (String m : corpus.archetypes.get("P5")) assertFalse(m1.containsKey(m));
        assertFalse(m1.containsKey(pl.l2a), "an allowed line is not counted");
        assertFalse(m1.containsKey(pl.l2b), "an allowed line is not counted");

        // ---- night 2: one allowed line starts to move, the other's entry has lapsed
        BypassPack.ingest(space, corpus.filesOnDay(1, Set.of(pl.l2a)));
        BypassPack.runNight(space, DAY2, Map.of());
        Map<String, M> want2 = oracle(corpus.callsOnDay(1, Set.of(pl.l2a)), corpus.profiles(), LocalDate.parse(DAY2), Set.of(pl.l2a), true);
        Map<String, Map<String, Object>> m2 = BypassPack.rows(data, "tb_cluster_members", "msisdn", "window_date = DATE '" + DAY2 + "'");
        assertMembersEqualTheOracle(want2, m2, "night 2");
        assertEquals(pl.boxes(), boxesOf(want2), "the lapsed entry's SIM and the dialler pair are three candidates on one batch: still no box");
        assertFalse(m2.containsKey(pl.l2b), "the lapsed SIM is a candidate again but belongs to no box");
        Map<String, Map<String, Object>> c2 = BypassPack.rows(data, "tb_clusters", "cluster_key", "window_date = DATE '" + DAY2 + "'");
        assertEquals(pl.boxes().keySet(), c2.keySet());
        assertBoxRows(c2, pl, 2, true);

        Map<String, Set<String>> alerts2 = sweep.evaluate();
        assertEquals(pl.boxes().keySet(), alerts2.get("tb_box_suspected"), "night 2 updates the same boxes");
        assertEquals(6, sweep.incidents("tb_box_suspected"), "a second night opens no second Incident for a box");
        // every member SIM of a box that also alerts on its own is a SIM Alert: the box row counted them
        Set<String> alertingMembers = new TreeSet<>();
        for (Map<String, Object> r : c2.values()) if (n(r, "alerting_sims") > 0) alertingMembers.add((String) r.get("cluster_key"));
        Set<String> simAlerts = alerts2.get("tb_sim_high");
        for (Map.Entry<String, Set<String>> b : pl.boxes().entrySet())
            if (!b.getKey().equals(P2)) assertTrue(simAlerts.containsAll(b.getValue()), b.getKey() + ": every member SIM alerts on its own on night 2");
        assertTrue(Collections.disjoint(simAlerts, pl.p2), "the slow-roll box: no SIM alerts alone, the box Incident is the only finding");
        List<String> x6 = new ArrayList<>(corpus.oneShort);
        for (int i = 0; i < 4; i++) assertTrue(simAlerts.contains(x6.get(i)), "X6 " + x6.get(i) + " alerts as a SIM and is in no box");
        assertFalse(simAlerts.contains(x6.get(4)), "the fifth X6 SIM is under the SIM-day floor: no row, no alert");
        assertEquals(Set.of(P2), difference(pl.boxes().keySet(), alertingMembers), "only the slow-roll box has no alerting member");

        // ---- a re-run of night 2 and a further sweep change nothing
        BypassPack.runNight(space, DAY2, Map.of());
        Map<String, Map<String, Object>> c2b = BypassPack.rows(data, "tb_clusters", "cluster_key", "window_date = DATE '" + DAY2 + "'");
        assertEquals(c2.keySet(), c2b.keySet());
        for (String k : c2.keySet()) {
            assertEquals(n(c2.get(k), "days_seen"), n(c2b.get(k), "days_seen"), k + ": a re-run does not count its own night twice");
            assertEquals(n(c2.get(k), "candidate_sims"), n(c2b.get(k), "candidate_sims"), k);
            assertEquals(n(c2.get(k), "alerting_sims"), n(c2b.get(k), "alerting_sims"), k);
        }
        assertEquals(alerts2, sweep.evaluate());
        assertEquals(6, sweep.incidents("tb_box_suspected"));
        System.out.println("BYPASS-B3: " + m1.size() + " member SIMs in " + c1.size() + " boxes, " + sweep.incidents("tb_box_suspected")
                + " box Incidents, " + simAlerts.size() + " SIM alerts night 2");
    }

    private static <T> Set<T> difference(Set<T> a, Set<T> b) {
        Set<T> out = new TreeSet<>(a);
        out.removeAll(b);
        return out;
    }

    private static long n(Map<String, Object> row, String col) { return ((Number) row.get(col)).longValue(); }

    /** The roll-up of every planted box, named: its kind, size, scored and alerting members, cells, devices, IMEI swaps and days seen. */
    private static void assertBoxRows(Map<String, Map<String, Object>> rows, Planted pl, int night, boolean sinceNight1) {
        // key, kind, candidate, scored, cells, imeis, spoofed
        Object[][] want = {
                {P1, "imei", 60L, 60L, 1L, 1L, 0L},
                {P2, "imei", 200L, 0L, 1L, 1L, 0L},
                {P3, "cell", 20L, 20L, 1L, 20L, 20L},
                {X4A, "imei", 6L, 6L, 1L, 1L, 0L},
                {X4B, "imei", 6L, 6L, 1L, 1L, 0L},
                {X7, "imei", 5L, 5L, 5L, 1L, 0L}};
        for (Object[] w : want) {
            Map<String, Object> r = rows.get((String) w[0]);
            String at = "night " + night + " " + w[0];
            assertEquals(w[1], r.get("cluster_kind"), at);
            assertEquals(w[2], n(r, "candidate_sims"), at + " candidate_sims");
            assertEquals(w[3], n(r, "scored_sims"), at + " scored_sims (members with a SIM-day row: P2 has none, each of its SIMs is under the volume floor)");
            assertEquals(w[4], n(r, "cells"), at + " cells");
            assertEquals(w[5], n(r, "imeis"), at + " imeis");
            assertEquals(w[6], n(r, "spoofed_sims"), at + " spoofed_sims (members using at least 3 IMEIs in a day)");
            assertEquals(n(r, "candidate_sims"), n(r, "alert_value"), at + " alert_value");
            assertEquals((long) night, n(r, "days_seen"), at + " days_seen");
            assertEquals(LocalDate.parse(DAY1), r.get("first_day"), at + " first_day");
            assertEquals(LocalDate.parse(night == 1 ? DAY1 : DAY2), r.get("last_day"), at + " last_day");
            // night 1: persistence holds every SIM back; night 2: every scored member alerts
            assertEquals(night == 1 ? 0L : (long) w[3], n(r, "alerting_sims"), at + " alerting_sims");
            if ((long) w[3] == 0L) assertNull(r.get("max_score"), at + " max_score: no member has a score");
            else assertTrue(((Number) r.get("max_score")).doubleValue() >= 60.0, at + " max_score is high");
            assertTrue(((Number) r.get("est_bypass_minutes")).doubleValue() > 0.0, at);
        }
        assertEquals(5, rows.get(P1).get("example_msisdns").toString().split(",").length);
        assertEquals(5, rows.get(X7).get("example_msisdns").toString().split(",").length);
    }

    // ------------------------------------------------------------------------------------------- no device column

    @Test
    void withoutADeviceColumnTheCellKeyFindsTheBoxesThatShareACellAndNothingElse(@TempDir Path tmp) throws Exception {
        BypassCorpus corpus = new BypassCorpus().generateB3();
        Planted pl = new Planted(corpus);
        Path space = BypassPack.copyTemplate(tmp);
        Path data = space.resolve("data");
        BypassPack.ingest(space, corpus.files(false));
        BypassPack.runJobs(space, List.of("tb_cluster_members", "tb_clusters"), DAY1, Map.of());
        Map<String, M> want = oracle(corpus.calls(), corpus.profiles(), LocalDate.parse(DAY1), Set.of(), false);
        Map<String, Map<String, Object>> got = BypassPack.rows(data, "tb_cluster_members", "msisdn", "window_date = DATE '" + DAY1 + "'");
        assertMembersEqualTheOracle(want, got, "no IMEI");
        Map<String, Set<String>> boxes = boxesOf(want);
        Set<String> farm = new TreeSet<>(pl.x4a);
        farm.addAll(pl.x4b);
        Map<String, Set<String>> expect = new TreeMap<>();
        expect.put("cell:C0001", pl.p1);
        expect.put("cell:C0002", pl.p2);
        expect.put("cell:C0003", pl.p3);
        expect.put("cell:C6000", farm);
        // L2 and L3 (registered and unregistered PBX) are not allowed in this run: four candidates on one batch, still below the box size
        assertEquals(expect, boxes, "the farm is ONE box here (no device to tell its two halves apart); the five-SIM box on five cells is invisible");
        for (String m : pl.x7) assertFalse(got.containsKey(m), "X7 shares no cell and no batch: without a device column nothing links it");
        Map<String, Map<String, Object>> clusters = BypassPack.rows(data, "tb_clusters", "cluster_key", "window_date = DATE '" + DAY1 + "'");
        assertEquals(expect.keySet(), clusters.keySet());
        for (Map<String, Object> r : clusters.values()) {
            assertEquals("cell", r.get("cluster_kind"));
            assertEquals(0L, n(r, "imeis"), "no device column, no device key");
            assertEquals(0L, n(r, "spoofed_sims"));
        }
        assertEquals(12L, n(clusters.get("cell:C6000"), "candidate_sims"));
    }

    // ------------------------------------------------------------------------------------------- shadow

    @Test
    void shadowModeWritesTheBoxesAndRaisesNoIncident(@TempDir Path tmp) throws Exception {
        BypassCorpus corpus = new BypassCorpus().generateB3();
        Path space = BypassPack.copyTemplate(tmp);
        Path data = space.resolve("data");
        BypassPack.ingest(space, corpus.files(true));
        BypassPack.runNight(space, DAY1, Map.of("shadow", "true"));
        Map<String, Map<String, Object>> rows = BypassPack.rows(data, "tb_clusters", "cluster_key", "window_date = DATE '" + DAY1 + "'");
        assertEquals(6, rows.size(), "the boxes are found exactly as in live mode");
        for (Map<String, Object> r : rows.values()) assertEquals(0L, n(r, "alert_value"), "shadow: " + r.get("cluster_key"));
        BypassPack.Sweep sweep = new BypassPack.Sweep(space);
        assertEquals(Map.of(), sweep.evaluate(), "no Alert Rule has anything to raise");
        assertEquals(0, sweep.incidents());
    }
}
