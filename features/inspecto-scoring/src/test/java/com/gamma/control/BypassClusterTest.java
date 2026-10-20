package com.gamma.control;

import com.gamma.job.JobConfig;
import com.gamma.util.DuckDbUtil;
import com.gamma.util.ToonHelper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code TELCO-BYPASS-SIMBOX-1} slice B3: the two cluster stages in isolation (gate G-SB(c): a stage's SQL can be
 * unit-tested on its own). {@code tb_cluster_members} and {@code tb_clusters} run as authored over HAND-MADE inputs, so every
 * clause is decided by one named SIM and every "absent" case has a twin that is present (the probe would succeed without the
 * clause). Covers each key (imei, cell, batch), the fallback when the device columns are absent, the precedence between
 * keys, the box-size and volume boundaries, every candidate clause AT and just INSIDE its threshold, roaming, the allow
 * list, the IMEI-swapping count, and the roll-up (scored, alerting, score, days seen, shadow).
 */
class BypassClusterTest {

    private static final String DAY = "2026-07-01";

    // ------------------------------------------------------------------------------------------- hand-made inputs

    /** The CDR rows of one test, written as one Parquet file under the feed's store. */
    private static final class Cdr {
        private final List<String[]> rows = new ArrayList<>();
        private int seq;
        private int target = 100000;

        private String nextTarget() { return "99972" + String.format("%06d", target++); }

        private void row(String direction, String service, String a, String b, int dur, String roaming, String cell, String imei) {
            rows.add(new String[]{"T" + (++seq), DAY + " " + String.format("%02d:%02d:%02d", (seq / 3600) % 24, (seq / 60) % 60, seq % 60),
                    direction, service, a, b, String.valueOf(dur), roaming, cell, "Y", "16", imei, "00101" + a});
        }

        /**
         * One SIM-day. {@code calls} MO voice calls, {@code distinct} of them to different numbers, {@code shortN} of them of
         * 30 s (the rest 61 s), {@code smsN} MO SMS, {@code mtN} received calls, the LAST {@code offCell} calls on other cells.
         */
        Cdr sim(String msisdn, String imei, String cell, int calls, int distinct, int shortN, int smsN, int mtN, int offCell, String roaming) {
            List<String> targets = new ArrayList<>();
            for (int k = 0; k < calls; k++) targets.add(k < distinct ? nextTarget() : targets.get(k - distinct));
            for (int k = 0; k < calls; k++)
                row("MO", "VOICE", msisdn, targets.get(k), k < shortN ? (k == 0 ? 60 : 30) : 61, roaming, k >= calls - offCell ? cell + "x" + k : cell, imei);
            for (int k = 0; k < smsN; k++) row("MO", "SMS", msisdn, nextTarget(), 0, "N", cell, imei);
            for (int k = 0; k < mtN; k++) row("MT", "VOICE", "28700" + String.format("%06d", k), msisdn, 60, "N", cell, imei);
            return this;
        }

        /** One answered 30 s MO call to a new number at an exact time (the window edges). */
        Cdr callAt(String msisdn, String imei, String cell, String ts) {
            rows.add(new String[]{"T" + (++seq), ts, "MO", "VOICE", msisdn, nextTarget(), "30", "N", cell, "Y", "16", imei, "00101" + msisdn});
            return this;
        }

        /** {@code count} unanswered MO calls (duration 0) to new numbers on the SIM's cell. */
        Cdr zero(String msisdn, String imei, String cell, int count) {
            for (int k = 0; k < count; k++) row("MO", "VOICE", msisdn, nextTarget(), 0, "N", cell, imei);
            return this;
        }

        /** A plain candidate: 20 distinct short MO calls, nothing else, on one cell. */
        Cdr base(String msisdn, String imei, String cell) { return sim(msisdn, imei, cell, 20, 20, 20, 0, 0, 0, "N"); }

        void write(Path data) throws Exception {
            DuckDbUtil.loadDriver();
            Path dir = Files.createDirectories(data.resolve("voice_cdr").resolve("database"));
            try (Connection c = DriverManager.getConnection("jdbc:duckdb:"); Statement st = c.createStatement()) {
                st.execute("CREATE TABLE v (record_id VARCHAR, start_ts TIMESTAMP, direction VARCHAR, service VARCHAR, a_number VARCHAR, "
                        + "b_number VARCHAR, duration_s INTEGER, roaming VARCHAR, cell_id VARCHAR, answered VARCHAR, release_cause VARCHAR, "
                        + "imei VARCHAR, imsi VARCHAR)");
                try (PreparedStatement ps = c.prepareStatement("INSERT INTO v VALUES (?, CAST(? AS TIMESTAMP), ?, ?, ?, ?, ?, ?, ?, ?, ?, NULLIF(?, ''), ?)")) {
                    for (String[] r : rows) {
                        for (int i = 0; i < r.length; i++) {
                            if (i == 6) ps.setInt(i + 1, Integer.parseInt(r[i]));
                            else ps.setString(i + 1, r[i]);
                        }
                        ps.addBatch();
                    }
                    ps.executeBatch();
                }
                st.execute("COPY v TO '" + dir.resolve("part.parquet").toString().replace('\\', '/') + "' (FORMAT PARQUET)");
            }
        }
    }

    private record Prof(String msisdn, String dealer, String activatedOn) {}

    private static void writeProfiles(Path data, List<Prof> profiles) throws Exception {
        DuckDbUtil.loadDriver();
        Path dir = Files.createDirectories(data.resolve("subscriber_profile").resolve("database"));
        try (Connection c = DriverManager.getConnection("jdbc:duckdb:"); Statement st = c.createStatement()) {
            st.execute("CREATE TABLE p (msisdn VARCHAR, plan VARCHAR, dealer_id VARCHAR, activated_on DATE, corporate_flag VARCHAR)");
            for (Prof p : profiles)
                st.execute("INSERT INTO p VALUES ('" + p.msisdn + "', 'PREPAID', '" + p.dealer + "', DATE '" + p.activatedOn + "', 'N')");
            st.execute("COPY p TO '" + dir.resolve("part.parquet").toString().replace('\\', '/') + "' (FORMAT PARQUET)");
        }
    }

    private record Space(Path root, Path data) {}

    private static Space space(Path tmp) throws Exception {
        Path root = BypassPack.copyTemplate(tmp);
        return new Space(root, root.resolve("data"));
    }

    private static void stages(Space s, String day, Map<String, String> overrides) throws Exception {
        BypassPack.runJobs(s.root, List.of("tb_cluster_members", "tb_clusters"), day, overrides);
    }

    /** Only the roll-up stage: its inputs are hand-written partitions that the member stage would replace. */
    private static void rollUp(Space s, String day, Map<String, String> overrides) throws Exception {
        BypassPack.runJobs(s.root, List.of("tb_clusters"), day, overrides);
    }

    private static Map<String, Map<String, Object>> members(Space s, String day) throws Exception {
        return BypassPack.rows(s.data, "tb_cluster_members", "msisdn", "window_date = DATE '" + day + "'");
    }

    private static Map<String, Map<String, Object>> clusters(Space s, String day) throws Exception {
        return BypassPack.rows(s.data, "tb_clusters", "cluster_key", "window_date = DATE '" + day + "'");
    }

    private static long n(Map<String, Object> row, String col) { return ((Number) row.get(col)).longValue(); }

    // ------------------------------------------------------------------------------------------- each key

    /** A box on one device: six candidate SIMs on six different cells and one IMEI: the key is the device. */
    @Test
    void theDeviceKeyFindsABoxWhoseSimsAreOnDifferentCells(@TempDir Path tmp) throws Exception {
        Space s = space(tmp);
        Cdr c = new Cdr();
        for (int i = 0; i < 6; i++) c.base("9971000" + i, "990000000001", "A" + i);
        c.write(s.data);
        stages(s, DAY, Map.of());
        Map<String, Map<String, Object>> m = members(s, DAY);
        assertEquals(6, m.size());
        for (Map<String, Object> r : m.values()) {
            assertEquals("imei", r.get("cluster_kind"));
            assertEquals("imei:990000000001", r.get("cluster_key"));
            assertEquals(6L, n(r, "imei_sims"));
            assertEquals(1L, n(r, "cell_sims"));
            assertEquals(0L, n(r, "batch_sims"), "no profile: no batch");
            assertEquals(1L, n(r, "imei_count"));
            assertEquals(20L, n(r, "calls_mo"));
            assertEquals(10.5, ((Number) r.get("talk_minutes")).doubleValue(), 1e-9, "19 calls of 30 s and one of 60 s");
        }
        Map<String, Map<String, Object>> cl = clusters(s, DAY);
        assertEquals(Set.of("imei:990000000001"), cl.keySet());
        Map<String, Object> box = cl.get("imei:990000000001");
        assertEquals("imei", box.get("cluster_kind"));
        assertEquals(6L, n(box, "candidate_sims"));
        assertEquals(0L, n(box, "scored_sims"), "tb_features has no row: the SIM-day stage has not seen them");
        assertEquals(0L, n(box, "alerting_sims"));
        assertNull(box.get("max_score"));
        assertEquals(63.0, ((Number) box.get("est_bypass_minutes")).doubleValue(), 1e-9);
        assertEquals(6L, n(box, "cells"));
        assertEquals(1L, n(box, "imeis"));
        assertEquals(0L, n(box, "batches"));
        assertEquals(0L, n(box, "spoofed_sims"));
        assertEquals(1L, n(box, "days_seen"));
        assertEquals(java.time.LocalDate.parse(DAY), box.get("first_day"));
        assertEquals(java.time.LocalDate.parse(DAY), box.get("last_day"));
        assertEquals("99710000,99710001,99710002,99710003,99710004", box.get("example_msisdns"), "the first five, in order");
        assertEquals(6L, n(box, "alert_value"));
    }

    /**
     * The device columns absent (the feed does not carry the IMEI): the cell key finds the same box when its SIMs share a
     * cell, and the same SIMs on different cells have no shared key at all - a box that hides both is invisible to this stage.
     */
    @Test
    void theCellKeyIsTheFallbackWhenTheDeviceIsAbsent(@TempDir Path tmp) throws Exception {
        Space s = space(tmp);
        Cdr c = new Cdr();
        for (int i = 0; i < 6; i++) c.base("9971000" + i, "", "SITE1");        // one cell, no IMEI
        for (int i = 0; i < 6; i++) c.base("9971100" + i, "", "B" + i);        // six cells, no IMEI
        c.write(s.data);
        stages(s, DAY, Map.of());
        Map<String, Map<String, Object>> m = members(s, DAY);
        Set<String> want = new TreeSet<>();
        for (int i = 0; i < 6; i++) want.add("9971000" + i);
        assertEquals(want, m.keySet(), "the six on one cell are a box; the six on six cells share no key");
        for (Map<String, Object> r : m.values()) {
            assertEquals("cell", r.get("cluster_kind"));
            assertEquals("cell:SITE1", r.get("cluster_key"));
            assertNull(r.get("imei_key"));
            assertEquals(0L, n(r, "imei_sims"));
            assertEquals(0L, n(r, "imei_count"));
        }
        Map<String, Object> box = clusters(s, DAY).get("cell:SITE1");
        assertEquals(6L, n(box, "candidate_sims"));
        assertEquals(1L, n(box, "cells"));
        assertEquals(0L, n(box, "imeis"));
    }

    /** SIMs that swap IMEIs (every call another one) cannot be grouped by device: the cell key catches them, and they are counted. */
    @Test
    void anImeiSwappingBoxIsCaughtByItsCellAndTheSwapsAreCounted(@TempDir Path tmp) throws Exception {
        Space s = space(tmp);
        Cdr c = new Cdr();
        int imei = 0;
        for (int i = 0; i < 5; i++) {
            String m = "9971000" + i;
            for (int k = 0; k < 20; k++) c.sim(m, "99000" + String.format("%07d", imei++), "SITE2", 1, 1, 1, 0, 0, 0, "N");
        }
        c.write(s.data);
        stages(s, DAY, Map.of());
        Map<String, Map<String, Object>> m = members(s, DAY);
        assertEquals(5, m.size());
        for (Map<String, Object> r : m.values()) {
            assertEquals("cell:SITE2", r.get("cluster_key"));
            assertEquals(20L, n(r, "imei_count"), "20 IMEIs in a day");
            assertEquals(1L, n(r, "imei_sims"), "no device is shared by two SIMs");
        }
        Map<String, Object> box = clusters(s, DAY).get("cell:SITE2");
        assertEquals(5L, n(box, "spoofed_sims"));
        assertEquals(5L, n(box, "imeis"), "five distinct dominant IMEIs, none shared");
        // at the spoof threshold: spoof_imei_count 3 counts a SIM with exactly 3 IMEIs, and not one with 2
        Space t = space(tmp.resolve("two"));
        Cdr d = new Cdr();
        for (int i = 0; i < 5; i++) {
            String m2 = "9971000" + i;
            int distinctImeis = i < 2 ? 3 : 2;   // two SIMs at the threshold, three just below
            for (int k = 0; k < 20; k++) d.sim(m2, "99001" + String.format("%02d%05d", i, k % distinctImeis), "SITE3", 1, 1, 1, 0, 0, 0, "N");
        }
        d.write(t.data);
        stages(t, DAY, Map.of());
        assertEquals(2L, n(clusters(t, DAY).get("cell:SITE3"), "spoofed_sims"), "exactly the two SIMs with 3 IMEIs");
    }

    /** A box fed by one dealer on one day: the SIMs share neither device nor cell. */
    @Test
    void theBatchKeyFindsSimsActivatedTogether(@TempDir Path tmp) throws Exception {
        Space s = space(tmp);
        Cdr c = new Cdr();
        List<Prof> profiles = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            String m = "9971000" + i;
            c.base(m, "99000100000" + i, "C" + i);
            profiles.add(new Prof(m, "D1", "2026-06-01"));
        }
        for (int i = 0; i < 4; i++) {   // another dealer: only four candidates
            String m = "9971100" + i;
            c.base(m, "99000200000" + i, "E" + i);
            profiles.add(new Prof(m, "D2", "2026-06-01"));
        }
        c.write(s.data);
        writeProfiles(s.data, profiles);
        stages(s, DAY, Map.of());
        Map<String, Map<String, Object>> m = members(s, DAY);
        assertEquals(6, m.size());
        for (Map<String, Object> r : m.values()) {
            assertEquals("batch", r.get("cluster_kind"));
            assertEquals("batch:D1/2026-06-01", r.get("cluster_key"));
            assertEquals(6L, n(r, "batch_sims"));
        }
        Map<String, Object> box = clusters(s, DAY).get("batch:D1/2026-06-01");
        assertEquals(1L, n(box, "batches"));
        assertEquals(6L, n(box, "cells"));
    }

    /** One box is one key: device before cell before batch; two devices at one site are two boxes. */
    @Test
    void aSimJoinsTheFirstKeyThatReachesTheBoxSizeAndAFarmIsOneBoxPerDevice(@TempDir Path tmp) throws Exception {
        Space s = space(tmp);
        Cdr c = new Cdr();
        List<Prof> profiles = new ArrayList<>();
        // G1: share device, cell and batch -> device
        for (int i = 0; i < 6; i++) { c.base("9971000" + i, "990000000011", "G1"); profiles.add(new Prof("9971000" + i, "D1", "2026-06-01")); }
        // G2: share cell and batch, own devices -> cell
        for (int i = 0; i < 5; i++) { c.base("9971100" + i, "99000000002" + i, "G2"); profiles.add(new Prof("9971100" + i, "D2", "2026-06-02")); }
        // G3: share only the batch -> batch
        for (int i = 0; i < 5; i++) { c.base("9971200" + i, "99000000003" + i, "G3" + i); profiles.add(new Prof("9971200" + i, "D3", "2026-06-03")); }
        // G4: a farm: two devices of six SIMs at one cell (twelve SIMs on the cell) -> two device boxes
        for (int i = 0; i < 12; i++) c.base("9971300" + String.format("%02d", i), i < 6 ? "990000000041" : "990000000042", "G4");
        // G5: a device with only four SIMs inside a cell of eight -> the cell
        for (int i = 0; i < 8; i++) c.base("9971400" + i, i < 4 ? "990000000051" : "99000000006" + i, "G5");
        c.write(s.data);
        writeProfiles(s.data, profiles);
        stages(s, DAY, Map.of());
        Map<String, Map<String, Object>> cl = clusters(s, DAY);
        Map<String, Long> got = new TreeMap<>();
        cl.forEach((k, r) -> got.put(k, n(r, "candidate_sims")));
        Map<String, Long> want = new TreeMap<>();
        want.put("imei:990000000011", 6L);
        want.put("cell:G2", 5L);
        want.put("batch:D3/2026-06-03", 5L);
        want.put("imei:990000000041", 6L);
        want.put("imei:990000000042", 6L);
        want.put("cell:G5", 8L);
        assertEquals(want, got);
        // the evidence columns still say what else the box shares
        assertEquals(1L, n(cl.get("imei:990000000011"), "cells"));
        assertEquals(1L, n(cl.get("imei:990000000011"), "batches"));
        assertEquals(5L, n(cl.get("cell:G5"), "imeis"), "one device of four SIMs and four SIMs on their own devices");
    }

    // ------------------------------------------------------------------------------------------- the boundaries

    /** One mini box: five SIMs on one device and five different cells; the fifth SIM carries the variation under test. */
    private record Var(String name, boolean box, int calls, int distinct, int shortN, int smsN, int mtN, int offCell, String roaming, int zero, String extraAt) {
        static Var of(String name, boolean box, int calls, int distinct, int shortN, int smsN, int mtN, int offCell) {
            return new Var(name, box, calls, distinct, shortN, smsN, mtN, offCell, "N", 0, null);
        }

        static Var unanswered(String name, boolean box, int shortN, int zero) {
            return new Var(name, box, 20, 20, shortN, 0, 0, 0, "N", zero, null);
        }

        /** Nine calls in the window plus one at an exact time: 00:00:00 of the day is INSIDE the window, 00:00:00 of the next day is not. */
        static Var edge(String name, boolean box, String extraAt) {
            return new Var(name, box, 9, 9, 9, 0, 0, 0, "N", 0, extraAt);
        }
    }

    /**
     * Every candidate clause AT its threshold (the fifth SIM drops out, four are left, below the box size) and just INSIDE it
     * (the fifth stays, a box of five), plus the volume floor, roaming, and the box size itself. Twenty-three mini boxes in one
     * run, each on its own device and its own cells, so a clause decides exactly one box and its twin proves the probe succeeds.
     */
    @Test
    void everyCandidateClauseAndTheBoxSizeHoldAtTheirBoundary(@TempDir Path tmp) throws Exception {
        Space s = space(tmp);
        Cdr c = new Cdr();
        List<Var> vars = List.of(
                // inbound_ratio < 0.05: 19 MO + 1 MT = 0.05 (AT); 20 + 1 = 0.0476 (INSIDE: 1 / 20 = 0.05 would read as AT)
                Var.of("inbound_at", false, 19, 19, 19, 0, 1, 0), Var.of("inbound_in", true, 20, 20, 20, 0, 1, 0),
                // b_diversity > 0.9: 18 of 20 (AT); 19 of 20 (INSIDE)
                Var.of("bdiv_at", false, 20, 18, 20, 0, 0, 0), Var.of("bdiv_in", true, 20, 19, 20, 0, 0, 0),
                // short_call_ratio > 0.9: 18 of 20 answered calls at most 60 s (AT); 19 of 20 (INSIDE)
                Var.of("short_at", false, 20, 20, 18, 0, 0, 0), Var.of("short_in", true, 20, 20, 19, 0, 0, 0),
                // the short-call share counts ANSWERED calls only: 19 of 20 answered (INSIDE; 19 of 24 attempts would not be); 18 of 20 (AT,
                // and counting the four unanswered calls as short would make it 22 of 20)
                Var.unanswered("unanswered_in", true, 19, 4), Var.unanswered("unanswered_at", false, 18, 4),
                // voice_only_share > 0.99: 99 voice of 100 events (AT); 199 of 200 (INSIDE)
                Var.of("voice_at", false, 99, 99, 99, 1, 0, 0), Var.of("voice_in", true, 199, 199, 199, 1, 0, 0),
                // top_cell_share > 0.9: 18 of 20 on the top cell (AT); 19 of 20 (INSIDE)
                Var.of("cell_at", false, 20, 20, 20, 0, 0, 2), Var.of("cell_in", true, 20, 20, 20, 0, 0, 1),
                // cand_min_events 10: 9 calls (AT-1) is not counted, 10 is
                Var.of("floor_out", false, 9, 9, 9, 0, 0, 0), Var.of("floor_at", true, 10, 10, 10, 0, 0, 0),
                // roaming calls are left out (the same SIM, not roaming, is the control: bdiv_in above is the same shape)
                new Var("roaming_out", false, 20, 20, 20, 0, 0, 0, "Y", 0, null),
                // the top-cell share is over the SIM's MO calls, not all its events: 91 of 100 calls (4 received calls make 104 events: 91 / 104 would be 0.875)
                Var.of("cell_mt_in", true, 100, 100, 100, 0, 4, 9),
                // a SIM with no answered call has no short-call share: not a candidate (a NULL clause is not true)
                new Var("no_answer", false, 0, 0, 0, 0, 0, 0, "N", 12, null),
                // the window is [start, end): 9 calls plus one AT the start count (10, a box); plus one AT the end do not (9)
                Var.edge("window_start_in", true, DAY + " 00:00:00"), Var.edge("window_end_out", false, "2026-07-02 00:00:00"));
        int bi = 0;
        Map<String, String> keyOf = new LinkedHashMap<>();
        for (Var v : vars) {
            String imei = "99001" + String.format("%07d", bi);
            for (int i = 0; i < 5; i++) {
                String m = "9972" + String.format("%02d%03d", bi, i);
                if (i < 4) c.base(m, imei, "M" + bi + "_" + i);
                else {
                    c.sim(m, imei, "M" + bi + "_" + i, v.calls, v.distinct, v.shortN, v.smsN, v.mtN, v.offCell, v.roaming);
                    if (v.zero > 0) c.zero(m, imei, "M" + bi + "_" + i, v.zero);
                    if (v.extraAt != null) c.callAt(m, imei, "M" + bi + "_" + i, v.extraAt);
                }
            }
            keyOf.put(v.name, "imei:" + imei);
            bi++;
        }
        // the box size: five SIMs are a box, four are not
        String five = "99002" + "0000001", four = "99002" + "0000002";
        for (int i = 0; i < 5; i++) c.base("9973000" + i, five, "N5_" + i);
        for (int i = 0; i < 4; i++) c.base("9973100" + i, four, "N4_" + i);
        keyOf.put("size_five", "imei:" + five);
        keyOf.put("size_four", "imei:" + four);
        c.write(s.data);
        stages(s, DAY, Map.of());
        Map<String, Map<String, Object>> cl = clusters(s, DAY);
        Set<String> wantBoxes = new TreeSet<>();
        for (Var v : vars) if (v.box) wantBoxes.add(keyOf.get(v.name));
        wantBoxes.add(keyOf.get("size_five"));
        assertEquals(wantBoxes, cl.keySet(), "boxes: the just-inside variants and a box of five; none of the AT variants");
        for (String k : wantBoxes) assertEquals(5L, n(cl.get(k), "candidate_sims"), k);
        // a member's call count is its MO VOICE calls: the SMS of the voice_in variant is not one (199, not 200)
        String voiceIn = "9972" + String.format("%02d", vars.indexOf(vars.stream().filter(v -> v.name.equals("voice_in")).findFirst().orElseThrow())) + "004";
        assertEquals(199L, n(members(s, DAY).get(voiceIn), "calls_mo"));
        // the dropped SIM is the only difference: the four plain SIMs of an AT box are not members of any box
        Map<String, Map<String, Object>> m = members(s, DAY);
        for (Var v : vars) {
            if (v.box) continue;
            String prefix = "9972" + String.format("%02d", vars.indexOf(v));
            assertTrue(m.keySet().stream().noneMatch(x -> x.startsWith(prefix)), v.name + ": its four remaining SIMs are below the box size");
        }
    }

    /** The allow list takes a SIM out of the count (live entry, prefix, range) and an entry at or past its expiry does not. */
    @Test
    void anAllowedSimIsLeftOutOfTheBoxUntilItsEntryLapses(@TempDir Path tmp) throws Exception {
        Space s = space(tmp);
        Cdr c = new Cdr();
        List<String> names = List.of("live", "expired", "at_expiry", "prefix", "range", "none", "permanent", "range_len");
        for (int b = 0; b < names.size(); b++)
            for (int i = 0; i < 5; i++) c.base(b == 7 ? "997470000" + i : "9974" + b + "00" + i, "99003" + String.format("%07d", b), "L" + b + "_" + i);
        c.write(s.data);
        BypassPack.writeSidecar(s.data, "tb_allow", "allow", List.of(
                BypassPack.Entry.key("99740000", "2026-07-02 12:00:00"),      // live at the run's as-of time (07-02 00:00)
                BypassPack.Entry.key("99741000", "2026-07-01 12:00:00"),      // lapsed
                BypassPack.Entry.key("99742000", "2026-07-02 00:00:00"),      // lapses AT it: not live
                BypassPack.Entry.prefix("9974300"),                            // covers 99743000 only (box 3's first SIM; 99743001... differ at the 7th digit? no: see below)
                BypassPack.Entry.range("99744000", "99744000"),               // exactly one SIM
                BypassPack.Entry.key("99746000", null),                         // permanent
                BypassPack.Entry.range("99747000", "99747999")));             // a same-length range: the nine-digit SIMs 997470000.. sort inside it but are longer
        stages(s, DAY, Map.of());
        Set<String> boxes = clusters(s, DAY).keySet();
        // box 0: its first SIM is allowed (live) -> four left -> none. box 1 and 2: the entry lapsed -> five -> box. box 3: prefix 9974300
        // covers all five SIMs 9974300x -> none left. box 4: the range covers one SIM -> four left -> none. box 5: no entry -> box. box 6: a permanent
        // entry covers one SIM -> none. box 7: the range is shorter than the SIMs -> all five stay -> box.
        assertEquals(Set.of("imei:990030000001", "imei:990030000002", "imei:990030000005", "imei:990030000007"), boxes);
    }

    // ------------------------------------------------------------------------------------------- the roll-up

    private static void writePartition(Path data, String store, String day, String selectColumns) throws Exception {
        DuckDbUtil.loadDriver();
        Path dir = Files.createDirectories(data.resolve(store).resolve("window_date=" + day));
        try (Connection c = DriverManager.getConnection("jdbc:duckdb:"); Statement st = c.createStatement()) {
            st.execute("COPY (" + selectColumns + ") TO '" + dir.resolve("part.parquet").toString().replace('\\', '/') + "' (FORMAT PARQUET)");
        }
    }

    private static String memberRow(String msisdn, String kind, String key, String imei, String cell, int imeiCount, double minutes) {
        return "SELECT '" + msisdn + "' AS msisdn, '" + kind + "' AS cluster_kind, '" + key + "' AS cluster_key, '" + imei + "' AS imei_key, '"
                + cell + "' AS cell_key, CAST(NULL AS VARCHAR) AS batch_key, " + imeiCount + " AS imei_count, CAST(" + minutes + " AS DOUBLE) AS talk_minutes";
    }

    /**
     * {@code tb_clusters} over hand-made members, features and findings: how many members were scored and alerting, the highest
     * score, the minutes, the days seen across nights (a re-run does not count its own night twice, a gap does not reset the
     * first day), the example list capped at five, and the shadow switch.
     */
    @Test
    void theRollUpCountsScoredAndAlertingMembersAndKeepsTheHistory(@TempDir Path tmp) throws Exception {
        Space s = space(tmp);
        String a1 = String.join(" UNION ALL ", List.of(
                memberRow("90000001", "imei", "imei:AAA", "AAA", "K1", 1, 10.0), memberRow("90000002", "imei", "imei:AAA", "AAA", "K2", 4, 5.5),
                memberRow("90000003", "imei", "imei:AAA", "AAA", "K1", 1, 2.25), memberRow("90000004", "imei", "imei:AAA", "AAA", "K3", 1, 1.0),
                memberRow("90000005", "imei", "imei:AAA", "AAA", "K4", 1, 1.0), memberRow("90000006", "imei", "imei:AAA", "AAA", "K5", 1, 1.0),
                memberRow("90000011", "cell", "cell:BBB", "", "BBB", 1, 3.0), memberRow("90000012", "cell", "cell:BBB", "", "BBB", 1, 3.0)));
        writePartition(s.data, "tb_cluster_members", DAY, a1.replace("'' AS imei_key", "CAST(NULL AS VARCHAR) AS imei_key"));
        // scored: members 1, 2 and 11 have a SIM-day row; findings: 1 alerts (80), 2 is held by persistence (70), 11 has none
        writePartition(s.data, "tb_features", DAY, "SELECT * FROM (VALUES ('90000001'), ('90000002'), ('90000011')) t(msisdn)");
        // another night: a member scored then, a high finding then - neither is tonight's
        writePartition(s.data, "tb_features", "2026-06-30", "SELECT * FROM (VALUES ('90000003')) t(msisdn)");
        writePartition(s.data, "tb_findings", "2026-06-30", "SELECT * FROM (VALUES ('90000004', 95.0, 'alert')) t(entity_key, score, status)");
        writePartition(s.data, "tb_findings", DAY, "SELECT * FROM (VALUES ('90000001', 80.0, 'alert'), ('90000002', 70.0, 'persistence'), "
                + "('90000099', 99.0, 'alert')) t(entity_key, score, status)");
        rollUp(s, DAY, Map.of("shadow", "false"));
        Map<String, Map<String, Object>> cl = clusters(s, DAY);
        Map<String, Object> a = cl.get("imei:AAA"), b = cl.get("cell:BBB");
        assertEquals(6L, n(a, "candidate_sims"));
        assertEquals(2L, n(a, "scored_sims"));
        assertEquals(1L, n(a, "alerting_sims"), "only status alert counts");
        assertEquals(80.0, ((Number) a.get("max_score")).doubleValue(), 0.0, "the highest score of a member, whatever its status; a SIM outside the box does not count");
        assertEquals(20.8, ((Number) a.get("est_bypass_minutes")).doubleValue(), 1e-9, "20.75 rounded to one decimal");
        assertEquals(5L, n(a, "cells"));
        assertEquals(1L, n(a, "imeis"));
        assertEquals(1L, n(a, "spoofed_sims"), "one member used 4 IMEIs, the threshold is 3");
        assertEquals("90000001,90000002,90000003,90000004,90000005", a.get("example_msisdns"), "capped at five");
        assertEquals(2L, n(b, "candidate_sims"));
        assertEquals(1L, n(b, "scored_sims"));
        assertEquals(0L, n(b, "alerting_sims"));
        assertNull(b.get("max_score"));
        assertEquals(0L, n(b, "imeis"));

        // night 2 (box A again, box C new), then a re-run of night 2, then night 4 after a gap
        writePartition(s.data, "tb_cluster_members", "2026-07-02", memberRow("90000001", "imei", "imei:AAA", "AAA", "K1", 1, 1.0) + " UNION ALL "
                + memberRow("90000021", "cell", "cell:CCC", "", "CCC", 1, 1.0).replace("'' AS imei_key", "CAST(NULL AS VARCHAR) AS imei_key"));
        rollUp(s, "2026-07-02", Map.of());
        rollUp(s, "2026-07-02", Map.of());
        Map<String, Map<String, Object>> d2 = clusters(s, "2026-07-02");
        assertEquals(Set.of("imei:AAA", "cell:CCC"), d2.keySet());
        assertEquals(2L, n(d2.get("imei:AAA"), "days_seen"), "a re-run does not count its own night twice");
        assertEquals(java.time.LocalDate.parse(DAY), d2.get("imei:AAA").get("first_day"));
        assertEquals(java.time.LocalDate.parse("2026-07-02"), d2.get("imei:AAA").get("last_day"));
        assertEquals(1L, n(d2.get("cell:CCC"), "days_seen"));
        assertEquals(java.time.LocalDate.parse("2026-07-02"), d2.get("cell:CCC").get("first_day"));
        writePartition(s.data, "tb_cluster_members", "2026-07-04", memberRow("90000001", "imei", "imei:AAA", "AAA", "K1", 1, 1.0));
        rollUp(s, "2026-07-04", Map.of("shadow", "true"));
        Map<String, Object> d4 = clusters(s, "2026-07-04").get("imei:AAA");
        assertEquals(3L, n(d4, "days_seen"), "nights 1, 2 and 4");
        assertEquals(java.time.LocalDate.parse(DAY), d4.get("first_day"), "a gap does not reset the first day");
        assertEquals(0L, n(d4, "alert_value"), "shadow: the row is written and nothing can breach");
        assertEquals(1L, n(d4, "candidate_sims"));
    }

    // ------------------------------------------------------------------------------------------- the shipped parts

    @Test
    void theShippedClusterPartsDeclareTheChainTheAlertRuleTheCaseRuleAndTheViews() throws Exception {
        Path cfg = BypassPack.TEMPLATE.resolve("config");
        JobConfig members = JobConfig.load(cfg.resolve("jobs/tb_cluster_members_job.toon").toString());
        JobConfig clusters = JobConfig.load(cfg.resolve("jobs/tb_clusters_job.toon").toString());
        JobConfig findings = JobConfig.load(cfg.resolve("jobs/tb_findings_job.toon").toString());
        JobConfig features = JobConfig.load(cfg.resolve("jobs/tb_features_job.toon").toString());
        assertEquals("40 3 * * *", members.cron(), "after tb_findings (03:30)");
        assertEquals("50 3 * * *", clusters.cron(), "after tb_cluster_members");
        assertEquals("tb_cluster_members", members.params().get("sink_dataset"));
        assertEquals("tb_clusters", clusters.params().get("sink_dataset"));
        assertEquals(Set.of("voice_cdr", "subscriber_profile", "entity_list_tb_allow"), new TreeSet<>(List.of(members.params().get("sources").split(","))));
        assertEquals(Set.of("tb_cluster_members", "tb_features", "tb_findings", "tb_clusters"), new TreeSet<>(List.of(clusters.params().get("sources").split(","))));
        for (JobConfig j : List.of(members, clusters)) {
            assertEquals("window_date", j.params().get("incremental.column"));
            assertEquals("31", j.params().get("incremental.retain"));
        }
        // the candidate clauses are the SAME numbers as the SIM-day stage's box_like: one set of thresholds, no drift
        for (String k : List.of("cand_max_inbound", "cand_min_b_div", "cand_min_short", "cand_min_voice_only", "cand_min_top_cell", "short_s"))
            assertEquals(features.params().get(k), members.params().get(k), k + " must not drift between the two stages");
        assertEquals("10", members.params().get("cand_min_events"), "lower than the SIM-day floor (20), so a slow-roll SIM still counts");
        assertTrue(Integer.parseInt(members.params().get("cand_min_events")) < Integer.parseInt(features.params().get("min_events")));
        assertEquals("5", members.params().get("min_box_sims"));
        assertEquals("3", clusters.params().get("spoof_imei_count"));
        assertEquals("false", clusters.params().get("shadow"), "the shadow switch ships OFF");
        assertEquals("false", findings.params().get("shadow"));

        Map<String, Object> rule = ToonHelper.load(cfg.resolve("registry/alert-rules/tb_box_suspected.toon").toString());
        assertEquals("tb_clusters", rule.get("dataset"));
        assertEquals("max(alert_value)", rule.get("measure"));
        assertEquals(List.of("cluster_key"), rule.get("by"), "one Incident per box: the Case anchor (D-BP3)");
        assertEquals("CRITICAL", rule.get("severity"));
        assertEquals("tb_box_suspected", rule.get("runbook"));
        Map<String, Object> runbook = ToonHelper.load(cfg.resolve("registry/runbooks/tb_box_suspected.toon").toString());
        assertEquals("tb_box_suspected", runbook.get("name"));
        Map<String, Object> view = ToonHelper.load(cfg.resolve("registry/link-analysis-views/tb_box_members.toon").toString());
        @SuppressWarnings("unchecked")
        Map<String, Object> proj = (Map<String, Object>) ((Map<String, Object>) view.get("query")).get("projection");
        assertEquals("tb_cluster_members", proj.get("datasetId"));
        assertEquals("msisdn", proj.get("sourceCol"));
        assertEquals("cluster_key", proj.get("targetCol"));
        assertEquals("cluster_kind", proj.get("linkKindCol"));
        assertTrue(Files.isRegularFile(cfg.resolve("ops/tb_box_caserule.toon")));
        Path data = BypassPack.TEMPLATE.resolve("data");
        assertTrue(Files.isRegularFile(data.resolve("tb_cluster_members/window_date=1970-01-01/seed.parquet")));
        assertTrue(Files.isRegularFile(data.resolve("tb_clusters/window_date=1970-01-01/seed.parquet")));
    }

    private static boolean regenerate() { return "true".equals(System.getenv("TELCO_BYPASS_REGENERATE")); }

    /** The seeds are what the Jobs' SQL really produces (zero rows, same columns and types). Regenerate with env TELCO_BYPASS_REGENERATE=true. */
    @Test
    void theClusterSeedsMatchTheSqlOfTheJobsThatFillThem() throws Exception {
        Path data = BypassPack.TEMPLATE.resolve("data");
        DuckDbUtil.loadDriver();
        try (Connection c = DriverManager.getConnection("jdbc:duckdb:"); Statement st = c.createStatement()) {
            for (String t : List.of("voice_cdr/database/schema-seed.parquet", "subscriber_profile/database/schema-seed.parquet")) {
                String view = t.substring(0, t.indexOf('/'));
                st.execute("CREATE TABLE " + view + " AS SELECT * FROM read_parquet('" + data.resolve(t).toString().replace('\\', '/') + "')");
            }
            st.execute("CREATE TABLE entity_list_tb_allow (list_id VARCHAR, purpose VARCHAR, entity_type VARCHAR, match VARCHAR, entry VARCHAR, "
                    + "lo VARCHAR, hi VARCHAR, expires_at TIMESTAMP, added_at TIMESTAMP, added_by VARCHAR, reason VARCHAR)");
            JobConfig members = JobConfig.load(BypassPack.TEMPLATE.resolve("config/jobs/tb_cluster_members_job.toon").toString());
            String membersSql = substitute(members.params().get("sql"), members.params(), DAY);
            Path membersSeed = data.resolve("tb_cluster_members/window_date=1970-01-01/seed.parquet");
            if (regenerate()) {
                Files.createDirectories(membersSeed.getParent());
                st.execute("COPY (SELECT * EXCLUDE (window_date) FROM (" + membersSql + ") LIMIT 0) TO '" + membersSeed.toString().replace('\\', '/') + "' (FORMAT PARQUET)");
            }
            String membersRel = "read_parquet('" + membersSeed.toString().replace('\\', '/') + "')";
            assertEquals(describe(st, "(SELECT * EXCLUDE (window_date), window_date FROM (" + membersSql + ") LIMIT 0)"), describe(st, membersRel),
                    "the tb_cluster_members seed drifted from the Job's SQL - regenerate with env TELCO_BYPASS_REGENERATE=true");
            assertEquals(0, scalar(st, "SELECT count(*) FROM " + membersRel));
            st.execute("CREATE TABLE tb_cluster_members AS SELECT *, DATE '1970-01-01' AS window_date FROM " + membersRel);

            st.execute("CREATE TABLE tb_features AS SELECT * FROM read_parquet('" + data.resolve("tb_features/window_date=1970-01-01/seed.parquet").toString().replace('\\', '/')
                    + "', hive_partitioning = true)");
            st.execute("CREATE TABLE tb_findings AS SELECT * FROM read_parquet('" + data.resolve("tb_findings/window_date=1970-01-01/seed.parquet").toString().replace('\\', '/')
                    + "', hive_partitioning = true)");
            st.execute("CREATE TABLE tb_clusters (cluster_kind VARCHAR, cluster_key VARCHAR, candidate_sims BIGINT, scored_sims BIGINT, alerting_sims BIGINT, "
                    + "max_score DOUBLE, est_bypass_minutes DOUBLE, cells BIGINT, imeis BIGINT, batches BIGINT, spoofed_sims BIGINT, first_day DATE, "
                    + "last_day DATE, days_seen BIGINT, example_msisdns VARCHAR, alert_value BIGINT, window_date DATE)");
            JobConfig clusters = JobConfig.load(BypassPack.TEMPLATE.resolve("config/jobs/tb_clusters_job.toon").toString());
            String clustersSql = substitute(clusters.params().get("sql"), clusters.params(), DAY);
            Path clustersSeed = data.resolve("tb_clusters/window_date=1970-01-01/seed.parquet");
            if (regenerate()) {
                Files.createDirectories(clustersSeed.getParent());
                st.execute("COPY (SELECT * EXCLUDE (window_date) FROM (" + clustersSql + ") LIMIT 0) TO '" + clustersSeed.toString().replace('\\', '/') + "' (FORMAT PARQUET)");
            }
            String clustersRel = "read_parquet('" + clustersSeed.toString().replace('\\', '/') + "')";
            assertEquals(describe(st, "(SELECT * EXCLUDE (window_date), window_date FROM (" + clustersSql + ") LIMIT 0)"), describe(st, clustersRel),
                    "the tb_clusters seed drifted from the Job's SQL - regenerate with env TELCO_BYPASS_REGENERATE=true");
            assertEquals(0, scalar(st, "SELECT count(*) FROM " + clustersRel));
        }
    }

    // ------------------------------------------------------------------------------------------- plumbing

    private static String substitute(String sql, Map<String, String> params, String day) {
        Map<String, String> window = BypassPack.window(day);
        List<String> names = new ArrayList<>(params.keySet());
        names.sort((a, b) -> b.length() - a.length());
        for (String n : names) {
            String v = params.get(n);
            if (v.startsWith("$")) v = window.getOrDefault(n, v);
            sql = sql.replace("$" + n, "'" + v.replace("'", "''") + "'");
        }
        return sql;
    }

    private static List<String> describe(Statement st, String relation) throws Exception {
        List<String> out = new ArrayList<>();
        try (ResultSet rs = st.executeQuery("DESCRIBE SELECT * FROM " + relation)) {
            while (rs.next()) out.add(rs.getString("column_name") + " " + rs.getString("column_type"));
        }
        return out;
    }

    private static long scalar(Statement st, String sql) throws Exception {
        try (ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }
}
