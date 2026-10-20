package com.gamma.control;

import com.gamma.etl.ConsignmentEventBus;
import com.gamma.job.JobConfig;
import com.gamma.job.JobRun;
import com.gamma.job.JobService;
import com.gamma.util.DuckDbUtil;
import com.gamma.util.Scheduler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code TELCO-BYPASS-SIMBOX-1} slice B2: the {@code tb_findings} stage in isolation (gate G-SB(c): a stage's SQL can be
 * unit-tested on its own). The Job runs as authored over HAND-MADE inputs, a few rows each, so every clause is
 * decided by one named SIM: the allow-list anti-join and its expiry, the agreement rule, persistence and its window,
 * the watch list lowering it, the allowed-line-changed signature, and the shadow switch.
 */
class BypassAllowListTest {

    /** A tb_factors row: just the columns tb_findings reads. */
    private record Fac(String msisdn, int primary, int noInbound, double inboundExcess, int mobility, double mix) {
        static Fac of(String msisdn, int primary) { return new Fac(msisdn, primary, 1, 0.0, 0, 0.0); }
    }

    /** A risk_scores_tb_sim_latest row: the score, and one positive factor so top_factors has something to say. */
    private record Risk(String msisdn, double score, String factors, String scoredAt) {
        Risk(String msisdn, double score) { this(msisdn, score, null, null); }
        Risk(String msisdn, double score, String factors) { this(msisdn, score, factors, null); }
    }

    private static void writeInputs(Path data, String day, List<Fac> facs, List<Risk> risks) throws Exception {
        DuckDbUtil.loadDriver();
        try (Connection c = DriverManager.getConnection("jdbc:duckdb:"); Statement st = c.createStatement()) {
            StringBuilder f = new StringBuilder();
            for (Fac x : facs)
                f.append(f.isEmpty() ? "" : ", ").append("('").append(x.msisdn).append("', ").append(x.primary).append(", ")
                        .append(x.noInbound).append(".0, ").append(x.inboundExcess).append(", ").append(x.mobility).append(".0, ")
                        .append(x.mix).append(", 12.5, 'R1,R2', DATE '").append(day).append("')");
            Path tbFactors = data.resolve("tb_factors");
            Files.createDirectories(tbFactors);
            for (Path old : Files.list(tbFactors).toList()) Files.delete(old);   // the snapshot is replaced by each night's run
            st.execute("COPY (SELECT CAST(msisdn AS VARCHAR) AS msisdn, CAST(primary_signals AS BIGINT) AS primary_signals, "
                    + "CAST(no_inbound_flag AS DOUBLE) AS no_inbound_flag, CAST(inbound_excess AS DOUBLE) AS inbound_excess, "
                    + "CAST(mobility_flag AS DOUBLE) AS mobility_flag, CAST(normal_service_mix AS DOUBLE) AS normal_service_mix, "
                    + "CAST(est_bypass_minutes AS DOUBLE) AS est_bypass_minutes, CAST(evidence_ids AS VARCHAR) AS evidence_ids, "
                    + "CAST(window_date AS DATE) AS window_date FROM (VALUES " + f + ") t(msisdn, primary_signals, no_inbound_flag, "
                    + "inbound_excess, mobility_flag, normal_service_mix, est_bypass_minutes, evidence_ids, window_date)) TO '"
                    + tbFactors.resolve("part.parquet").toString().replace('\\', '/') + "' (FORMAT PARQUET)");
            StringBuilder r = new StringBuilder();
            for (Risk x : risks)
                r.append(r.isEmpty() ? "" : ", ").append("('").append(x.msisdn).append("', ").append(x.score).append(", ")
                        .append(x.score >= 60 ? "true" : "false").append(", ")
                        .append(x.factors == null ? "CAST(NULL AS VARCHAR)" : "'" + x.factors + "'").append(", TIMESTAMP '").append(x.scoredAt == null ? java.time.LocalDate.parse(day).plusDays(1) + " 03:20:00" : x.scoredAt).append("')");
            Path latest = data.resolve("risk_scores_tb_sim_latest");
            Files.createDirectories(latest);
            for (Path old : Files.list(latest).toList()) Files.delete(old);
            String standard = "[{\"indicator\":\"b_diversity\",\"contribution\":14.0},{\"indicator\":\"no_inbound\",\"contribution\":14.0},"
                    + "{\"indicator\":\"short_calls\",\"contribution\":12.0},{\"indicator\":\"voice_only\",\"contribution\":10.0},"
                    + "{\"indicator\":\"mobility\",\"contribution\":-12.0}]";
            st.execute("COPY (SELECT entity_key, score, high, coalesce(factors, '" + standard + "') AS factors, scored_at FROM (VALUES " + r
                    + ") t(entity_key, score, high, factors, scored_at)) TO '" + latest.resolve("scores.parquet").toString().replace('\\', '/') + "' (FORMAT PARQUET)");
        }
    }

    private record Space(Path root, Path data, JobConfig job) {}

    private static Space space(Path tmp) throws Exception {
        Path root = BypassPack.copyTemplate(tmp);
        return new Space(root, root.resolve("data"), BypassPack.job(root, "tb_findings"));
    }

    /** Run tb_findings once for {@code day} with the parameters overridden, and return its rows for that day by SIM. */
    private static Map<String, Map<String, Object>> findings(Space s, String day, Map<String, String> overrides) throws Exception {
        try (Scheduler sch = new Scheduler();
             JobService js = new JobService(List.of(s.job), new ConsignmentEventBus(), sch, null,
                     s.root.resolve("audit").toString(), null, null, s.data.toString())) {
            js.start();
            Map<String, String> args = new LinkedHashMap<>(BypassPack.window(day));
            args.putAll(overrides);
            JobRun r = BypassPack.runJob(js, "tb_findings", args);
            assertEquals("SUCCESS", r.status(), r.message());
        }
        return BypassPack.rows(s.data, "tb_findings", "entity_key", "window_date = DATE '" + day + "'");
    }

    private static Map<String, String> statuses(Map<String, Map<String, Object>> rows) {
        return rows.entrySet().stream().collect(Collectors.toMap(Map.Entry::getKey, e -> (String) e.getValue().get("status"),
                (a, b) -> a, java.util.TreeMap::new));
    }

    // ------------------------------------------------------------------------------------------- allow list

    /**
     * Allow entries by kind: permanent, future expiry, expired, expiring EXACTLY at the run's as-of time (not live: the
     * comparison is strict), a prefix, a same-length range, and the two entries that must NOT match (a longer number inside
     * the range as a string, and a row of another purpose). One SIM each; persist_days 1 so the only reason a high SIM does
     * not alert is the list.
     */
    @Test
    void theAllowListAntiJoinSuppressesLiveEntriesAndNothingElse(@TempDir Path tmp) throws Exception {
        Space s = space(tmp);
        List<Fac> facs = new ArrayList<>();
        List<Risk> risks = new ArrayList<>();
        for (String m : List.of("30000011", "30000021", "30000031", "30000041", "30000051", "30000061", "30000071", "300000615",
                "30000081", "30000091", "30000101")) {
            facs.add(Fac.of(m, 6));
            risks.add(new Risk(m, 70.0));
        }
        // a SIM with one positive factor only: a protective factor, however large, is never a 'top factor'
        facs.add(Fac.of("30000111", 6));
        risks.add(new Risk("30000111", 70.0, "[{\"indicator\":\"service_mix\",\"contribution\":-15.0},{\"indicator\":\"b_diversity\",\"contribution\":14.0},"
                + "{\"indicator\":\"mobility\",\"contribution\":-12.0}]"));
        writeInputs(s.data, "2026-07-01", facs, risks);
        BypassPack.writeSidecar(s.data, "tb_allow", "allow", List.of(
                BypassPack.Entry.key("30000011", null),                          // permanent
                BypassPack.Entry.key("30000021", "2026-07-02 12:00:00"),         // expires after the run's as-of time (07-02 00:00)
                BypassPack.Entry.key("30000031", "2026-07-01 12:00:00"),         // expired before it
                BypassPack.Entry.key("30000041", "2026-07-02 00:00:00"),         // expires AT it: not live
                BypassPack.Entry.prefix("3000005"),                              // covers 30000051 only
                BypassPack.Entry.range("30000060", "30000069")));                // covers 30000061; 300000615 is longer
        // a row of another purpose in the allow sidecar must not suppress (the list's purpose is part of the match)
        BypassPack.writeSidecar(s.data, "tb_watch", "watch", List.of());
        appendOtherPurposeRow(s.data, "30000091");

        Map<String, String> got = statuses(findings(s, "2026-07-01", Map.of("persist_days", "1")));
        Map<String, String> want = new java.util.TreeMap<>();
        want.put("30000011", "allowed");
        want.put("30000021", "allowed");
        want.put("30000031", "alert");
        want.put("30000041", "alert");
        want.put("30000051", "allowed");
        want.put("30000061", "allowed");
        want.put("30000071", "alert");
        want.put("300000615", "alert");
        want.put("30000081", "alert");
        want.put("30000091", "alert");
        want.put("30000101", "alert");
        want.put("30000111", "alert");
        assertEquals(want, got);
        Map<String, Map<String, Object>> rows = BypassPack.rows(s.data, "tb_findings", "entity_key", null);
        assertEquals(0.0, ((Number) rows.get("30000011").get("alert_score")).doubleValue(), "an allowed SIM carries no alert score");
        assertEquals(70.0, ((Number) rows.get("30000011").get("score")).doubleValue(), "but keeps its score: allowed is not unmonitored");
        assertEquals(70.0, ((Number) rows.get("30000031").get("alert_score")).doubleValue(), "an expired entry stops suppressing");
        assertEquals(Boolean.TRUE, rows.get("30000011").get("allowed"));
        assertEquals(Boolean.FALSE, rows.get("30000031").get("allowed"));
        assertEquals("b_diversity,no_inbound,short_calls", rows.get("30000031").get("top_factors"),
                "the top three positive factors, ties broken by id; a protective factor is never a top factor");
        assertEquals(12.5, ((Number) rows.get("30000031").get("est_bypass_minutes")).doubleValue());
        assertEquals("b_diversity", rows.get("30000111").get("top_factors"), "the only positive factor; the larger protective ones are not listed");
        assertEquals("SIM", rows.get("30000031").get("kind"));
    }

    private static void appendOtherPurposeRow(Path data, String msisdn) throws Exception {
        DuckDbUtil.loadDriver();
        Path file = data.resolve("entity_list_tb_allow").resolve("entries.parquet");
        try (Connection c = DriverManager.getConnection("jdbc:duckdb:"); Statement st = c.createStatement()) {
            st.execute("CREATE TABLE e AS SELECT * FROM read_parquet('" + file.toString().replace('\\', '/') + "')");
            st.execute("INSERT INTO e VALUES ('tb_allow', 'watch', 'msisdn', 'key', '" + msisdn + "', '" + msisdn + "', '" + msisdn
                    + "', NULL, TIMESTAMP '2026-06-30 12:00:00', 'x', 'y')");
            st.execute("COPY e TO '" + file.toString().replace('\\', '/') + "' (FORMAT PARQUET)");
        }
    }

    /** An allow entry's expiry is the only enforcement: the same SIM is suppressed on one night and alerts the next. */
    @Test
    void anEntryExpiringBetweenTwoNightsUnsuppressesTheSimOnTheSecond(@TempDir Path tmp) throws Exception {
        Space s = space(tmp);
        writeInputs(s.data, "2026-07-01", List.of(Fac.of("30000011", 6)), List.of(new Risk("30000011", 70.0)));
        BypassPack.writeSidecar(s.data, "tb_allow", "allow", List.of(BypassPack.Entry.key("30000011", "2026-07-02 12:00:00")));
        BypassPack.writeSidecar(s.data, "tb_watch", "watch", List.of());
        assertEquals(Map.of("30000011", "allowed"), statuses(findings(s, "2026-07-01", Map.of("persist_days", "1"))));
        writeInputs(s.data, "2026-07-02", List.of(Fac.of("30000011", 6)), List.of(new Risk("30000011", 70.0)));
        assertEquals(Map.of("30000011", "alert"), statuses(findings(s, "2026-07-02", Map.of("persist_days", "1"))),
                "the entry expired at 2026-07-02 12:00, before night 2's as-of time (2026-07-03 00:00)");
    }

    /** A night the SIM was allowed AND below high is not a high night: when the entry lapses it starts counting from one. */
    @Test
    void anAllowedNightBelowHighDoesNotCountTowardPersistence(@TempDir Path tmp) throws Exception {
        Space s = space(tmp);
        BypassPack.writeSidecar(s.data, "tb_allow", "allow", List.of(BypassPack.Entry.key("30000011", "2026-07-02 12:00:00")));
        BypassPack.writeSidecar(s.data, "tb_watch", "watch", List.of());
        writeInputs(s.data, "2026-07-01", List.of(Fac.of("30000011", 6)), List.of(new Risk("30000011", 50.0)));
        Map<String, Map<String, Object>> a = findings(s, "2026-07-01", Map.of());
        assertEquals(Map.of("30000011", "allowed"), statuses(a));
        assertEquals(0L, ((Number) a.get("30000011").get("persist_hits")).longValue(), "a night below high is not a hit");
        writeInputs(s.data, "2026-07-02", List.of(Fac.of("30000011", 6)), List.of(new Risk("30000011", 70.0)));
        Map<String, Map<String, Object>> b = findings(s, "2026-07-02", Map.of());
        assertEquals(Map.of("30000011", "persistence"), statuses(b), "its first HIGH night, whatever the earlier row said");
        assertEquals(1L, ((Number) b.get("30000011").get("persist_hits")).longValue());
    }

    // ------------------------------------------------------------------------------------------- agreement, persistence, watch

    @Test
    void agreementNeedsMinSignalsPrimarySignalsAndTheBoundaryIsInclusive(@TempDir Path tmp) throws Exception {
        Space s = space(tmp);
        writeInputs(s.data, "2026-07-01", List.of(Fac.of("30000151", 4), Fac.of("30000161", 5), Fac.of("30000171", 6), Fac.of("30000181", 4)),
                List.of(new Risk("30000151", 90.0), new Risk("30000161", 70.0), new Risk("30000171", 61.0), new Risk("30000181", 90.0)));
        BypassPack.writeSidecar(s.data, "tb_allow", "allow", List.of(BypassPack.Entry.key("30000181", null)));
        BypassPack.writeSidecar(s.data, "tb_watch", "watch", List.of());
        Map<String, Map<String, Object>> rows = findings(s, "2026-07-01", Map.of("persist_days", "1"));
        assertEquals(Map.of("30000151", "agreement", "30000161", "alert", "30000171", "alert", "30000181", "allowed"), statuses(rows),
                "the highest score with 4 primary signals does not alert; exactly min_signals (5) does; an allowed SIM is 'allowed' whatever its signals");
        assertEquals(0.0, ((Number) rows.get("30000151").get("alert_score")).doubleValue());
    }

    /**
     * persist_days 2 of persist_window 3: one high night is held back ('persistence'), the second high night within the
     * window alerts, a night three days after the first is outside the window, and a watched SIM needs one night fewer.
     */
    @Test
    void persistenceCountsHighNightsInTheWindowAndAWatchedSimNeedsOneFewer(@TempDir Path tmp) throws Exception {
        Space s = space(tmp);
        BypassPack.writeSidecar(s.data, "tb_allow", "allow", List.of());
        // night 1: L high, M high and watched (entry live), N high, P high, Q high, plus X high only on night 1 and 4
        BypassPack.writeSidecar(s.data, "tb_watch", "watch", List.of(BypassPack.Entry.key("30000112", "2026-07-02 12:00:00"),
                BypassPack.Entry.key("30000152", "2026-07-01 12:00:00")));   // R's watch entry lapsed before night 1's as-of time
        List<String> sims = List.of("30000102", "30000112", "30000122", "30000132", "30000142", "30000152");   // L, M, N, P, Q, R
        writeInputs(s.data, "2026-07-01", sims.stream().map(m -> Fac.of(m, 6)).toList(),
                sims.stream().map(m -> new Risk(m, 70.0)).toList());
        assertEquals(Map.of("30000102", "persistence", "30000112", "alert", "30000122", "persistence",
                "30000132", "persistence", "30000142", "persistence", "30000152", "persistence"), statuses(findings(s, "2026-07-01", Map.of())),
                "night 1: only the watched SIM (one night needed) alerts; a lapsed watch entry is not a watch");

        // night 2: L high again, N and P low (not high: no row), M high (its watch entry is gone), Q low
        BypassPack.writeSidecar(s.data, "tb_watch", "watch", List.of());
        writeInputs(s.data, "2026-07-02", List.of(Fac.of("30000102", 6), Fac.of("30000112", 6), Fac.of("30000122", 6), Fac.of("30000132", 6), Fac.of("30000142", 6)),
                List.of(new Risk("30000102", 70.0), new Risk("30000112", 70.0), new Risk("30000122", 30.0),
                        new Risk("30000132", 30.0), new Risk("30000142", 30.0)));
        assertEquals(Map.of("30000102", "alert", "30000112", "alert"), statuses(findings(s, "2026-07-02", Map.of())),
                "night 2: a second high night in the window alerts; low SIMs have no finding");

        // night 3: P high again: night 1 is two days earlier, inside the 3-day window, so P has 2 hits and alerts
        writeInputs(s.data, "2026-07-03", List.of(Fac.of("30000132", 6), Fac.of("30000142", 6)),
                List.of(new Risk("30000132", 70.0), new Risk("30000142", 30.0)));
        assertEquals(Map.of("30000132", "alert"), statuses(findings(s, "2026-07-03", Map.of())), "night 3: night 1 is inside the window");

        // night 4: Q high again: its night 1 is THREE days earlier, outside the window of 3, so it is a first night again
        writeInputs(s.data, "2026-07-04", List.of(Fac.of("30000142", 6)), List.of(new Risk("30000142", 70.0)));
        Map<String, Map<String, Object>> n4 = findings(s, "2026-07-04", Map.of());
        assertEquals(Map.of("30000142", "persistence"), statuses(n4), "night 4: night 1 fell out of the window");
        assertEquals(1L, ((Number) n4.get("30000142").get("persist_hits")).longValue());
    }

    @Test
    void aWatchedSimWithPersistDaysOneAlertsOnItsFirstNight(@TempDir Path tmp) throws Exception {
        Space s = space(tmp);
        BypassPack.writeSidecar(s.data, "tb_allow", "allow", List.of());
        BypassPack.writeSidecar(s.data, "tb_watch", "watch", List.of(BypassPack.Entry.key("30000112", null)));
        writeInputs(s.data, "2026-07-01", List.of(Fac.of("30000112", 6)), List.of(new Risk("30000112", 70.0)));
        Map<String, Map<String, Object>> rows = findings(s, "2026-07-01", Map.of("persist_days", "1"));
        assertEquals(Map.of("30000112", "alert"), statuses(rows), "persist_days 1 minus a watched night: no more than its first night is asked");
        assertEquals(Boolean.TRUE, rows.get("30000112").get("watched"));
    }

    // ------------------------------------------------------------------------------------------- allowed line changed

    @Test
    void aChangedAllowedLineIsFlaggedAndAnUnchangedOrUnlistedOneIsNot(@TempDir Path tmp) throws Exception {
        Space s = space(tmp);
        BypassPack.writeSidecar(s.data, "tb_allow", "allow", List.of(BypassPack.Entry.key("30000011", null), BypassPack.Entry.key("30000021", null)));
        BypassPack.writeSidecar(s.data, "tb_watch", "watch", List.of());
        List<Fac> n1 = List.of(Fac.of("30000011", 6), Fac.of("30000021", 6), Fac.of("30000031", 6));
        List<Risk> r1 = List.of(new Risk("30000011", 70.0), new Risk("30000021", 70.0), new Risk("30000031", 70.0));
        writeInputs(s.data, "2026-07-01", n1, r1);
        Map<String, Map<String, Object>> a = findings(s, "2026-07-01", Map.of("persist_days", "1"));
        for (String m : a.keySet()) assertEquals(0L, ((Number) a.get(m).get("shape_changed")).longValue(), m + ": no earlier night, nothing to compare");
        assertEquals("1/0/0/0", a.get("30000011").get("shape_sig"));

        // night 2: 30000011 now moves across cells (mobility flips) and its score falls below high; 30000021 is unchanged;
        // 30000031 (not allowed) changes the same way and is NOT flagged
        writeInputs(s.data, "2026-07-02",
                List.of(new Fac("30000011", 6, 1, 0.0, 1, 0.0), Fac.of("30000021", 6), new Fac("30000031", 6, 1, 0.0, 1, 0.0)),
                List.of(new Risk("30000011", 50.0), new Risk("30000021", 70.0), new Risk("30000031", 70.0)));
        Map<String, Map<String, Object>> b = findings(s, "2026-07-02", Map.of("persist_days", "1"));
        assertEquals(Map.of("30000011", "allowed", "30000021", "allowed", "30000031", "alert"), statuses(b),
                "an allowed line below high still has a finding row: it is how its shape is followed");
        assertEquals(1L, ((Number) b.get("30000011").get("shape_changed")).longValue(), "mobility flipped");
        assertEquals("1/0/1/0", b.get("30000011").get("shape_sig"));
        assertEquals(0L, ((Number) b.get("30000021").get("shape_changed")).longValue());
        assertEquals(0L, ((Number) b.get("30000031").get("shape_changed")).longValue(), "only an allowed line is followed");

        // night 3: the line stays as it was on night 2: no change against the NEWEST earlier night
        writeInputs(s.data, "2026-07-03", List.of(new Fac("30000011", 6, 1, 0.0, 1, 0.0), Fac.of("30000021", 6)),
                List.of(new Risk("30000011", 50.0), new Risk("30000021", 70.0)));
        Map<String, Map<String, Object>> c = findings(s, "2026-07-03", Map.of("persist_days", "1"));
        assertEquals(0L, ((Number) c.get("30000011").get("shape_changed")).longValue());
    }

    // ------------------------------------------------------------------------------------------- shadow

    @Test
    void shadowModeWritesTheFindingsAndRaisesNothing(@TempDir Path tmp) throws Exception {
        Space s = space(tmp);
        BypassPack.writeSidecar(s.data, "tb_allow", "allow", List.of(BypassPack.Entry.key("30000011", null)));
        BypassPack.writeSidecar(s.data, "tb_watch", "watch", List.of());
        List<Fac> n1 = List.of(Fac.of("30000011", 6), Fac.of("30000021", 6), Fac.of("30000031", 4));
        List<Risk> r1 = List.of(new Risk("30000011", 70.0), new Risk("30000021", 70.0), new Risk("30000031", 70.0));
        writeInputs(s.data, "2026-07-01", n1, r1);
        Map<String, String> shadow = Map.of("persist_days", "1", "shadow", "true");
        Map<String, Map<String, Object>> a = findings(s, "2026-07-01", shadow);
        assertEquals(Map.of("30000011", "allowed", "30000021", "shadow", "30000031", "agreement"), statuses(a),
                "the agreement and allow decisions still apply; only what WOULD have alerted is 'shadow'");
        for (Map<String, Object> row : a.values()) assertEquals(0.0, ((Number) row.get("alert_score")).doubleValue(), "nothing carries an alert score");
        assertEquals(70.0, ((Number) a.get("30000021").get("score")).doubleValue(), "the score is still written");
        // the allowed-line signal is a page too: silent in shadow
        writeInputs(s.data, "2026-07-02", List.of(new Fac("30000011", 6, 1, 0.0, 1, 0.0)), List.of(new Risk("30000011", 50.0)));
        Map<String, Map<String, Object>> b = findings(s, "2026-07-02", shadow);
        assertEquals(0L, ((Number) b.get("30000011").get("shape_changed")).longValue());
        assertEquals("1/0/1/0", b.get("30000011").get("shape_sig"), "the shape is still followed");
    }

    /** Scores written before the window ended belong to an earlier night's run (the scoring Job failed): never joined to today's factors. */
    @Test
    void aScoresRunOlderThanTheWindowIsNeverJoined(@TempDir Path tmp) throws Exception {
        Space s = space(tmp);
        BypassPack.writeSidecar(s.data, "tb_allow", "allow", List.of());
        BypassPack.writeSidecar(s.data, "tb_watch", "watch", List.of());
        writeInputs(s.data, "2026-07-01", List.of(Fac.of("30000011", 6)), List.of(new Risk("30000011", 70.0, null, "2026-07-01 23:59:59")));
        assertTrue(findings(s, "2026-07-01", Map.of("persist_days", "1")).isEmpty(), "scored before the window's end (2026-07-02 00:00)");
        writeInputs(s.data, "2026-07-01", List.of(Fac.of("30000011", 6)), List.of(new Risk("30000011", 70.0, null, "2026-07-02 00:00:00")));
        assertEquals(Map.of("30000011", "alert"), statuses(findings(s, "2026-07-01", Map.of("persist_days", "1"))), "scored exactly at the window's end: current");
    }

    @Test
    void aFactorsSnapshotOfAnotherDayProducesNoFindings(@TempDir Path tmp) throws Exception {
        Space s = space(tmp);
        BypassPack.writeSidecar(s.data, "tb_allow", "allow", List.of());
        BypassPack.writeSidecar(s.data, "tb_watch", "watch", List.of());
        writeInputs(s.data, "2026-06-30", List.of(Fac.of("30000011", 6)), List.of(new Risk("30000011", 70.0)));
        assertTrue(findings(s, "2026-07-01", Map.of("persist_days", "1")).isEmpty(),
                "a stale tb_factors snapshot (a failed upstream night) must not be dressed up as today's findings");
    }
}
