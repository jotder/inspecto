package com.gamma.expectation;

import com.gamma.audit.Event;
import com.gamma.audit.EventLevel;
import com.gamma.audit.EventType;
import com.gamma.event.EventLog;
import com.gamma.objects.ObjectAccess;
import com.gamma.pipeline.ComponentRegistry;
import com.gamma.pipeline.ComponentStore;
import com.gamma.signal.Ref;
import com.gamma.signal.Severity;
import com.gamma.signal.Signal;
import com.gamma.spi.auth.ApiException;
import com.gamma.spi.auth.ErrorCodes;
import com.gamma.workflow.ObjectType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;

/**
 * Evaluates the authored {@code expectation} components of one Space and fires the failure consequence chain.
 * Extracted from {@code ExpectationRoutes} so the {@code POST /expectations/evaluate} route and the
 * {@code expectation.evaluate} Job run the SAME code: persist {@code lastResult}, open a correlated Incident
 * (deduped while one is still open) and emit {@code EXPECTATION_FAILED} + {@code expectation.violated}.
 *
 * <p>A baseline profile recorded by a sweep is accepted only if the WHOLE sweep passed.
 */
public final class ExpectationRunner {

    private static final Logger log = LoggerFactory.getLogger(ExpectationRunner.class);
    public static final String TYPE = "expectation";

    private final Path writeRoot;
    private final Path dataRoot;
    private final ObjectAccess objects;               // null: breaches are evented but not promoted to an Incident
    private final UnaryOperator<String> sqlMessage;   // scrubs server paths from a driver message

    public ExpectationRunner(Path writeRoot, Path dataRoot, ObjectAccess objects, UnaryOperator<String> sqlMessage) {
        this.writeRoot = writeRoot;
        this.dataRoot = dataRoot;
        this.objects = objects;
        this.sqlMessage = sqlMessage;
    }

    private ComponentStore store() {
        return new ComponentStore(writeRoot.resolve("registry"));
    }

    private BaselineProfileStore baselines() {
        return new BaselineProfileStore(writeRoot);
    }

    /** Evaluate every enabled Expectation; disabled ones are returned as stored. Sorted by name. */
    public List<Map<String, Object>> evaluateAll() throws IOException {
        ComponentStore store = store();
        List<Map<String, Object>> out = new ArrayList<>();
        Map<String, String> recorded = new LinkedHashMap<>();   // baseline expectation -> profile id
        boolean allPassed = true;
        for (ComponentRegistry.Component c : store.list(TYPE)) {
            Map<String, Object> content = c.content();
            if (!"false".equalsIgnoreCase(String.valueOf(content.getOrDefault("enabled", true)))) {
                Map<String, Object> next = runAndPersist(store, content, recorded);
                allPassed &= next.get("lastResult") instanceof Map<?, ?> r && "PASSED".equals(r.get("status"));
                out.add(next);
            } else
                out.add(content);
        }
        if (allPassed) acceptAll(recorded);
        out.sort(Comparator.comparing(c -> String.valueOf(c.get("name"))));
        return out;
    }

    /** Evaluate one stored Expectation (its current stored {@code content}). */
    public Map<String, Object> evaluateOne(Map<String, Object> content) throws IOException {
        Map<String, String> recorded = new LinkedHashMap<>();
        Map<String, Object> next = runAndPersist(store(), content, recorded);
        if (next.get("lastResult") instanceof Map<?, ?> r && "PASSED".equals(r.get("status"))) acceptAll(recorded);
        return next;
    }

    private void acceptAll(Map<String, String> recorded) throws IOException {
        BaselineProfileStore baselines = baselines();
        for (Map.Entry<String, String> r : recorded.entrySet()) baselines.acceptByRun(r.getKey(), r.getValue());
    }

    private static Expectation parse(Map<String, Object> body) {
        try {
            return Expectation.fromMap(body);
        } catch (IllegalArgumentException e) {
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, e.getMessage());
        }
    }

    /**
     * Evaluate one expectation, persist its result, fire the failure consequence chain, return the updated
     * content. A baseline expectation also RECORDS its profile - pass or fail - into {@code recorded}, for
     * the caller to accept once it knows whether the whole run succeeded.
     */
    private Map<String, Object> runAndPersist(ComponentStore store, Map<String, Object> content,
                                              Map<String, String> recorded) throws IOException {
        Expectation exp = parse(content);
        ExpectationEvaluator.Result result;
        Map<String, Object> baselineDetail = new LinkedHashMap<>();
        try {
            result = "baseline".equals(exp.kind())
                    ? evaluateBaseline(exp, recorded, baselineDetail)
                    : ExpectationEvaluator.evaluate(exp, dataRoot);
        } catch (IllegalArgumentException bad) {
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, bad.getMessage());
        } catch (SQLException sql) {
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED,
                    "expectation evaluation failed: " + sqlMessage.apply(sql.getMessage()));
        }

        Map<String, Object> lastResult = new LinkedHashMap<>();
        lastResult.put("status", result.status());
        lastResult.put("violations", result.violations());
        lastResult.put("checkedAt", result.checkedAt());
        lastResult.putAll(baselineDetail);

        Map<String, Object> next = new LinkedHashMap<>(content);
        next.put("lastResult", lastResult);
        next.put("updatedAt", result.checkedAt());
        // archive=false: a run-check stamps lastResult, it doesn't author a new version (MET-5).
        store.write(TYPE, exp.name(), next, false);

        if ("FAILED".equals(result.status())) raiseIncident(exp, result.violations());
        return next;
    }

    /**
     * The {@code baseline} kind: profile the input, compare it with the median of the accepted window, and
     * RECORD the profile whatever the verdict (a refused run still records it). Acceptance is the caller's.
     */
    private ExpectationEvaluator.Result evaluateBaseline(Expectation exp, Map<String, String> recorded,
                                                         Map<String, Object> detail) throws SQLException {
        try {
            BaselineProfileStore baselines = baselines();
            long now = System.currentTimeMillis();
            var profile = BaselineEvaluator.profile(exp, dataRoot);
            if (exp.baseline().trailingDays() > 0) {
                // trailing-days mode: the baseline is the profile's own earlier days - no history to record or accept
                var trailing = BaselineEvaluator.compareTrailing(exp.baseline(), profile);
                detail.put("baselineSize", trailing.baselineSize());
                detail.put("findings", trailing.findings().stream().map(BaselineEvaluator.Finding::toMap).toList());
                return new ExpectationEvaluator.Result(trailing.violations() > 0 ? "FAILED" : "PASSED",
                        trailing.violations(), now);
            }
            var cmp = BaselineEvaluator.compare(exp.baseline(), profile,
                    baselines.acceptedWindow(exp.name(), exp.baseline().window()));
            String status = cmp.violations() > 0 ? "FAILED" : "PASSED";
            String profileId = baselines.record(exp.name(), profile, status, now);
            recorded.put(exp.name(), profileId);
            detail.put("profileId", profileId);
            detail.put("baselineSize", cmp.baselineSize());
            detail.put("findings", cmp.findings().stream().map(BaselineEvaluator.Finding::toMap).toList());
            return new ExpectationEvaluator.Result(status, cmp.violations(), now);
        } catch (IOException store) {
            // Fail closed: an unreadable durable history yields no verdict - never one against "no baseline".
            throw new ApiException(503, ErrorCodes.CAPABILITY_UNAVAILABLE, "baseline evaluation unavailable: " + store.getMessage());
        }
    }

    /**
     * On a failed evaluation: open a correlated Incident (deduped while one for {@code expectation:<name>}
     * is still open) and emit the {@code EXPECTATION_FAILED} signal that fans out to notification channels.
     * Never disturbs the evaluation - a persistence hiccup is logged and swallowed.
     */
    private void raiseIncident(Expectation exp, long violations) {
        String correlationId = "expectation:" + exp.name();
        try {
            // Absent inspecto-ops there is nothing to raise an Incident on, so the breach is still EVENTED
            // below but not promoted (EDITIONS SP-CTL-02 posture for a sequence gap).
            if (objects == null) return;
            if (objects.hasActive(ObjectType.INCIDENT, correlationId)) return;   // one Incident already tracks it

            String title = "Expectation failed: " + exp.name();
            String description = exp.kind() + " check on " + exp.targetType() + " \"" + exp.target() + "\""
                    + (exp.column() != null ? " column \"" + exp.column() + "\"" : "")
                    + " — " + violations + " violating record(s).";
            Map<String, String> attrs = new LinkedHashMap<>();
            attrs.put("expectation", exp.name());
            attrs.put("kind", exp.kind());
            attrs.put("target", exp.target());
            if (exp.column() != null) attrs.put("column", exp.column());
            attrs.put("violations", String.valueOf(violations));
            objects.open(ObjectType.INCIDENT, title, description, exp.severity(), correlationId, attrs);

            EventLog.current().emit(Event.builder(EventType.EXPECTATION_FAILED)
                    .level("CRITICAL".equals(exp.severity()) ? EventLevel.ERROR : EventLevel.WARN)
                    .source(ExpectationRunner.class.getName())
                    .correlationId(correlationId)
                    .message(title + " — " + description)
                    .attr("expectation", exp.name())
                    .attr("kind", exp.kind())
                    .attr("violations", violations)
                    .attr("severity", exp.severity()));

            // AGT-5 P1 D4: the canonical expectation.violated Signal, additive to the legacy event
            // above - the triage layer subscribes to Signals. Same correlationId so triage dedupes.
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("expectation", exp.name());
            payload.put("kind", exp.kind());
            payload.put("violations", violations);
            payload.put("severity", exp.severity());
            EventLog.current().emit(new Signal(null, "expectation.violated", java.time.Instant.now(),
                    Severity.parse(exp.severity()), Ref.of("expectation", exp.name()),
                    Ref.of(exp.targetType(), exp.target()), correlationId, null, null, null,
                    title + " — " + description, payload, 1).toEvent());
        } catch (RuntimeException e) {
            log.warn("could not raise incident for failed expectation {}: {}", exp.name(), e.getMessage());
        }
    }
}
