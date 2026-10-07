package com.gamma.job;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** DUCKLE-C2: facts only, every explanation traces to a listed difference, absent ≠ zero, "not compared" is stated. */
class JobRunDiffTest {

    private static JobRun run(String id, String trigger, String status, long ms, String message) {
        return new JobRun(id, "rollup", "maintenance", trigger, "2026-09-16 01:00:00", "2026-09-16 01:00:01", status, ms, message);
    }

    private static RunArtifact dataset(String runId, String name, long rows, long bytes) {
        return new RunArtifact(runId, "rollup", 1, name, "dataset", "store/" + name, null, rows, bytes, null, "2026-09-16T01:00:01Z");
    }

    private static RunArtifact receipt(String runId, Map<String, Object> detail) {
        return new RunArtifact(runId, "rollup", 9, "params", "params", null, null, 0, 0, null, "2026-09-16T01:00:01Z", detail);
    }

    @Test
    @SuppressWarnings("unchecked")
    void comparesByKindFromRecordedFactsOnly() {
        JobRun a = run("r1", "cron", "SUCCESS", 1000, "");
        JobRun b = run("r2", "manual", "FAILED", 1500, "boom");
        List<RunArtifact> aArts = List.of(dataset("r1", "txn_rollup", 100, 2048),
                receipt("r1", Map.of("day", Map.of("source", "config", "overrode", List.of()))));
        List<RunArtifact> bArts = List.of(dataset("r2", "txn_rollup", 90, 2048),
                receipt("r2", Map.of("day", Map.of("source", "args", "overrode", List.of("config")))));

        Map<String, Object> d = JobRunDiff.diff(a, aArts, b, bArts);
        Map<String, Object> kinds = (Map<String, Object>) d.get("kinds");
        for (String k : List.of("code", "runtime", "inputs")) {
            Map<String, Object> kind = (Map<String, Object>) kinds.get(k);
            assertEquals(false, kind.get("compared"), k);
            assertTrue(String.valueOf(kind.get("reason")).startsWith("no recorded fact"), k);
        }
        Map<String, Object> exec = (Map<String, Object>) kinds.get("execution");
        List<Map<String, Object>> ed = (List<Map<String, Object>>) exec.get("differences");
        assertEquals(List.of("status", "message", "durationMs"), ed.stream().map(x -> x.get("field")).toList());
        assertEquals(ed.size(), ((List<?>) exec.get("explanation")).size(), "one explanation line per difference, never more");

        Map<String, Object> inv = (Map<String, Object>) kinds.get("invocation");
        List<Map<String, Object>> id = (List<Map<String, Object>>) inv.get("differences");
        assertEquals(List.of("trigger", "params.day.source"), id.stream().map(x -> x.get("field")).toList());
        assertTrue(String.valueOf(id.get(1).get("explanation")).contains("config") && String.valueOf(id.get(1).get("explanation")).contains("args"));

        Map<String, Object> out = (Map<String, Object>) kinds.get("output");
        List<Map<String, Object>> od = (List<Map<String, Object>>) out.get("differences");
        assertEquals(1, od.size());
        assertEquals("output.txn_rollup.rows", od.get(0).get("field"));
        assertTrue(String.valueOf(od.get(0).get("explanation")).contains("-10"));
        assertEquals(6, d.get("differenceCount"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void absentIsNotZero() {
        JobRun a = run("r1", "cron", "SUCCESS", 1, "");
        JobRun b = run("r2", "cron", "FAILED", 1, "died at node 2");
        Map<String, Object> d = JobRunDiff.diff(a, List.of(dataset("r1", "txn_rollup", 100, 10)), b, List.of());
        Map<String, Object> out = (Map<String, Object>) ((Map<String, Object>) d.get("kinds")).get("output");
        List<Map<String, Object>> od = (List<Map<String, Object>>) out.get("differences");
        assertEquals(1, od.size());
        assertEquals("absent", od.get(0).get("b"), "the run that died produced nothing — that is absent, not 0 rows");
        assertTrue(String.valueOf(od.get(0).get("explanation")).contains("not produced by b"));
        Map<String, Object> inv = (Map<String, Object>) ((Map<String, Object>) d.get("kinds")).get("invocation");
        assertTrue(String.valueOf(inv.get("note")).contains("neither run carries a parameter receipt"));
    }

    @Test
    void identicalRunsDifferNowhereAndStillStateWhatWasNotCompared() {
        JobRun a = run("r1", "cron", "SUCCESS", 5, "");
        Map<String, Object> d = JobRunDiff.diff(a, List.of(dataset("r1", "x", 1, 1)), a, List.of(dataset("r1", "x", 1, 1)));
        assertEquals(0, d.get("differenceCount"));
        assertEquals(false, ((Map<?, ?>) ((Map<?, ?>) d.get("kinds")).get("code")).get("compared"));
    }
}
