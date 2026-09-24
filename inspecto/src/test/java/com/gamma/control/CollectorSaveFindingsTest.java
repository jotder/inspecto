package com.gamma.control;

import com.gamma.config.spec.Finding;
import com.gamma.config.spec.FindingCodes;
import com.gamma.config.spec.Severity;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link ConfigRoutes#collectorFindings} — the save-time half of the Collector's remote-only keys
 * (`PROCESSOR-RELEASE-READINESS-1`, 2026-09-24: throttle, circuit breaker, sink.archive). Before it a
 * {@code post_action} MOVE with no target and a throttle on a local inbox both saved clean, and neither
 * ever did anything.
 */
class CollectorSaveFindingsTest {

    private static Map<String, Object> pipeline(boolean active, Map<String, Object> collector) {
        Map<String, Object> draft = new LinkedHashMap<>();
        draft.put("name", "P");
        draft.put("active", active);
        draft.put("collector", collector);
        return draft;
    }

    private static List<Finding> check(Map<String, Object> draft) {
        return ConfigRoutes.collectorFindings("pipeline", draft);
    }

    @Test
    void aMoveWithNoArchivePathIsRefusedWhenActive() {
        List<Finding> out = check(pipeline(true, Map.of("connector", "sftp",
                "post_action", Map.of("on_success", "MOVE"))));
        assertEquals(1, out.size(), out.toString());
        assertEquals(Severity.ERROR, out.get(0).severity());
        assertEquals(FindingCodes.ERR_COLLECTOR_CONFIG_INVALID, out.get(0).code());
        assertEquals("collector.post_action.archive_path", out.get(0).fieldPath());
    }

    @Test
    void theSameMoveOnAnInactiveDraftIsOnlyWarned() {
        List<Finding> out = check(pipeline(false, Map.of("connector", "sftp",
                "post_action", Map.of("on_success", "move", "archive_path", " "))));
        assertEquals(1, out.size(), out.toString());
        assertEquals(Severity.WARNING, out.get(0).severity());
        assertEquals(FindingCodes.WARN_COLLECTOR_CONFIG_INVALID, out.get(0).code());
    }

    @Test
    void aMoveWithATargetOnARemoteCollectorIsClean() {
        assertEquals(List.of(), check(pipeline(true, Map.of("connector", "sftp",
                "fetch", Map.of("rate_limit", "10MB/s"),
                "circuit_breaker", Map.of("failure_threshold", 3),
                "post_action", Map.of("on_success", "MOVE", "archive_path", "archive/yyyy/MM/dd")))));
    }

    @Test
    void remoteOnlyKeysOnALocalInboxAreWarnedAsInertByName() {
        List<Finding> out = check(pipeline(true, Map.of("connector", "local",
                "fetch", Map.of("rate_limit", "10MB/s"),
                "circuit_breaker", Map.of("failure_threshold", 3),
                "post_action", Map.of("on_success", "DELETE"))));
        assertEquals(1, out.size(), out.toString());
        Finding f = out.get(0);
        assertEquals(Severity.WARNING, f.severity(), "inert, not wrong — never refused");
        assertEquals(FindingCodes.WARN_COLLECTOR_KEY_INERT, f.code());
        for (String k : List.of("collector.fetch", "collector.circuit_breaker", "collector.post_action"))
            assertTrue(f.message().contains(k), "the message names every inert key: " + f.message());
    }

    @Test
    void anAbsentConnectorIsLocalButABoundConnectionIsNot() {
        assertEquals(1, check(pipeline(true, Map.of("retry", Map.of("count", 2)))).size(),
                "no connector and no connection = the local inbox");
        assertEquals(List.of(), check(pipeline(true, Map.of("connection", "WAREHOUSE",
                "retry", Map.of("count", 2)))), "the form derives the connector from the Connection at save");
    }

    @Test
    void aRetainPostActionOnALocalInboxIsNotInert() {
        assertEquals(List.of(), check(pipeline(true, Map.of("connector", "local",
                "post_action", Map.of("on_success", "RETAIN")))));
    }

    @Test
    void theSaveGateRunsIt() {
        // SaveGate.check is the one list every save door runs (ControlApiSaveGateParityTest); a finding only
        // this method produces reaching SaveGate proves the wiring without re-driving the five doors.
        List<Finding> all = SaveGate.check(null, "pipeline", pipeline(true, Map.of("connector", "sftp",
                "post_action", Map.of("on_success", "MOVE"))), null, null, SaveGate.Referents.MAY_ARRIVE_LATER);
        assertTrue(all.stream().anyMatch(f -> FindingCodes.ERR_COLLECTOR_CONFIG_INVALID.equals(f.code())), all.toString());
    }
}
