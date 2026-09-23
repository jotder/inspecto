package com.gamma.etl;

import com.gamma.config.io.ConfigLoader;
import com.gamma.config.spec.ConfigSpecs;
import com.gamma.config.spec.Finding;
import com.gamma.config.spec.Severity;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Collector's retry / circuit-breaker / rate-limit keys are declared in {@code ConfigSpecs.pipeline()}
 * (PROCESSOR-RELEASE-READINESS-1 G4/G5, 2026-09-23). Until then only {@code PipelineConfigParser} knew them,
 * so a mistyped duration or count saved cleanly and failed at LOAD. This pins the two authorities to the
 * same answer: a value the spec accepts, the parser accepts, and a value the parser refuses, the spec
 * refuses at the save.
 */
class CollectorResilienceSpecParityTest {

    /** Durations the parser's {@code toMillis} accepts, and ones it throws on. */
    private static final List<String> GOOD_DURATIONS = List.of("30", "30s", "5m", "2h", "1d", "5 m", "10S");
    private static final List<String> BAD_DURATIONS = List.of("5x", "fast", "1.5s", "m", "5ms");

    private static final List<String> DURATION_KEYS = List.of(
            "retry.initial_delay", "retry.max_delay", "circuit_breaker.cooldown");

    @Test
    void theSpecAndTheParserAgreeOnEveryCollectorDuration() {
        List<String> disagreements = new ArrayList<>();
        for (String key : DURATION_KEYS) {
            for (String v : GOOD_DURATIONS) check(key, v, true, disagreements);
            for (String v : BAD_DURATIONS) check(key, v, false, disagreements);
        }
        assertTrue(disagreements.isEmpty(), "spec and parser disagree:\n  " + String.join("\n  ", disagreements));
    }

    @Test
    void theSpecAndTheParserAgreeOnCountsAndTheBackoff() {
        List<String> disagreements = new ArrayList<>();
        check("retry.count", "3", true, disagreements);
        check("retry.count", "three", false, disagreements);
        check("circuit_breaker.failure_threshold", "4", true, disagreements);
        check("circuit_breaker.failure_threshold", "four", false, disagreements);
        for (String b : List.of("EXPONENTIAL", "linear", "FIXED", "constant"))
            check("retry.backoff", b, true, disagreements);
        assertTrue(disagreements.isEmpty(), "spec and parser disagree:\n  " + String.join("\n  ", disagreements));
    }

    /**
     * ⚠ The one-way case, stated rather than hidden: {@code RetryPolicy.Backoff.from} maps an unknown
     * backoff to EXPONENTIAL silently, so the parser can never refuse one — the spec's ENUM now does, at
     * the save. That is the fail-closed direction, not drift.
     */
    @Test
    void anUnknownBackoffIsRefusedBySpecAlthoughTheEngineWouldDefaultIt() {
        assertTrue(!specFindings("retry.backoff", "SIDEWAYS").isEmpty(),
                "an unknown retry.backoff must be refused at the save");
    }

    private static void check(String key, String value, boolean shouldAccept, List<String> out) {
        boolean specAccepts = specFindings(key, value).isEmpty();
        boolean parserAccepts = parserAccepts(key, value);
        if (specAccepts != shouldAccept || parserAccepts != shouldAccept)
            out.add("collector.%s = '%s': spec %s, parser %s (expected %s)".formatted(key, value,
                    specAccepts ? "accepts" : "refuses", parserAccepts ? "accepts" : "refuses",
                    shouldAccept ? "accept" : "refuse"));
    }

    private static List<Finding> specFindings(String key, String value) {
        return ConfigLoader.filesystem().validate(ConfigSpecs.pipeline(), config(key, value)).stream()
                .filter(f -> f.severity() == Severity.ERROR && f.fieldPath().startsWith("collector."))
                .toList();
    }

    private static boolean parserAccepts(String key, String value) {
        try {
            PipelineConfig.fromMap(config(key, value));
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** A minimal draft whose only collector setting is {@code collector.<key> = value}. */
    private static Map<String, Object> config(String key, String value) {
        String[] parts = key.split("\\.");
        Map<String, Object> collector = new LinkedHashMap<>();
        collector.put("connector", "local");
        collector.put(parts[0], new LinkedHashMap<>(Map.of(parts[1], value)));
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", "RESILIENCE_ETL");
        m.put("dirs", Map.of("poll", "in", "database", "out"));
        m.put("processing", new LinkedHashMap<String, Object>(Map.of("threads", 1)));
        m.put("collector", collector);
        return m;
    }
}
