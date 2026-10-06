package com.gamma.config.safety;

import com.gamma.config.spec.Finding;
import com.gamma.config.spec.FindingCodes;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Policy-narrowing D9 (S6 plan-time half): {@code permit.advance_state: false} refuses a config that implies an advance. */
class StateAdvancePlanTimeTest {

    private static SafetyPolicy policy(boolean advance) {
        return new SafetyPolicy(List.of(Path.of(".")), List.of(), 4, 0, 0, Set.of(), Set.of(), advance);
    }

    private static List<String> refused(String type, Map<String, Object> raw, boolean advance) {
        return ConfigSafetyValidator.check(type, raw, policy(advance)).stream()
                .filter(f -> FindingCodes.ERR_SAFETY_STATE_ADVANCE_REFUSED.equals(f.code()))
                .map(Finding::fieldPath).toList();
    }

    private static final Map<String, Object> ADVANCING = Map.of(
            "collector", Map.of("connector", "sftp", "duplicate", Map.of("mode", "checksum")),
            "dirs", Map.of("markers", "markers"),
            "processing", Map.of(
                    "duplicate_check", Map.of("enabled", true),
                    "dedup", Map.of("keys", List.of("id"), "scope", "window(P4D)"),
                    "steps", List.of(Map.of("dedup", Map.of("keys", List.of("id"), "scope", "WINDOW(P1D)")))));

    @Test
    void everyAdvancingKeyIsRefusedWhenAdvanceStateIsFalse() {
        assertEquals(List.of("collector.connector", "collector.duplicate.mode", "processing.duplicate_check.enabled",
                        "processing.dedup.scope", "processing.steps[0].dedup.scope"),
                refused("pipeline", ADVANCING, false));
    }

    /** The negative probe: the same config passes when the policy permits the advance. */
    @Test
    void theSameConfigPassesWhenAdvanceStateIsPermitted() {
        assertEquals(List.of(), refused("pipeline", ADVANCING, true));
    }

    @Test
    void nonAdvancingShapesAreNotRefused() {
        Map<String, Object> raw = Map.of(
                "collector", Map.of("connector", "local", "duplicate", Map.of("mode", "path")),
                "processing", Map.of("duplicate_check", Map.of("enabled", true),   // no dirs.markers
                        "dedup", Map.of("keys", List.of("id"), "scope", "batch")));
        assertEquals(List.of(), refused("pipeline", raw, false));
        assertEquals(List.of(), refused("pipeline", Map.of("collector", Map.of("connector", "dataset", "dataset", "d")), false));
    }

    @Test
    void aJobIncrementalColumnIsRefused() {
        Map<String, Object> raw = Map.of("job", Map.of("type", "pipeline", "incremental_column", "ts"));
        assertEquals(List.of("job.incremental_column"), refused("job", raw, false));
        assertEquals(List.of(), refused("job", raw, true));
        assertTrue(refused("job", Map.of("job", Map.of("type", "pipeline")), false).isEmpty());
    }
}
