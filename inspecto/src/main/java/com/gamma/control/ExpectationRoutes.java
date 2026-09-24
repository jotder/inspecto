package com.gamma.control;

import com.gamma.event.Event;
import com.gamma.event.EventLevel;
import com.gamma.event.EventLog;
import com.gamma.event.EventType;
import com.gamma.expectation.BaselineEvaluator;
import com.gamma.expectation.BaselineProfileStore;
import com.gamma.expectation.Expectation;
import com.gamma.expectation.ExpectationEvaluator;
import com.gamma.objects.ObjectType;
import com.gamma.pipeline.ComponentRegistry;
import com.gamma.pipeline.ComponentStore;
import com.gamma.signal.Ref;
import com.gamma.signal.Severity;
import com.gamma.signal.Signal;
import com.gamma.util.DuckDbUtil;
import com.sun.net.httpserver.HttpExchange;
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

/**
 * Data-quality <b>Expectation</b> engine routes ({@code /expectations*}, ING-6) — the data-quality third
 * of the Rules triad. Expectations are authored objects (full CRUD) persisted as {@code expectation}
 * components under {@code <write-root>/registry}; evaluation counts violating records in the target's
 * at-rest Parquet ({@link ExpectationEvaluator}) and, on failure, opens a correlated Incident (deduped
 * while one is still open) and emits an {@code EXPECTATION_FAILED} signal — the same consequence chain
 * the mock backend drives ({@code expectations.handler.ts}).
 *
 * <p>Fail-closed: write root unset → 503; a bad expectation body / target / column → 422; a duplicate
 * create → 409; an unknown expectation → 404. CRUD writes require {@code canAuthorWorkbench} (a no-op on
 * Personal); evaluation persists {@code lastResult}, so it also needs the write root.
 *
 * <p>The {@code baseline} kind (DUCKLE-C8) additionally records a profile per evaluation in
 * {@link BaselineProfileStore} and is accepted only when the whole run passed; its audited
 * {@code /baseline/accept|clear} ops are gated {@code canOperateRuns}. An unreadable history is 503.
 */
final class ExpectationRoutes implements RouteModule {

    private static final Logger log = LoggerFactory.getLogger(ExpectationRoutes.class);
    private static final String TYPE = "expectation";

    @Override
    public void register(ApiContext api) {
        api.get("/expectations", (e, m) -> list(api));
        // HOME-TILES-1 (2026-09-16): the landing page's "Expectations breached" tile — a server-side count over
        // the registry's persisted lastResult, so the page never fetches every Expectation to count failures.
        api.get("/expectations/breached-count", (e, m) -> breachedCount(api));
        // Gated 2026-09-17: evaluation persists lastResult, opens Incidents and emits EXPECTATION_FAILED into
        // the default notification dispatch — an operate action, never the read the old exemption called it.
        api.post("/expectations/evaluate", ApiContext.withCapability("canOperateRuns", (e, m) -> evaluateAll(api)));
        api.post("/expectations/([^/]+)/evaluate", ApiContext.withCapability("canOperateRuns",
                (e, m) -> single(e, evaluateOne(api, ApiContext.name(m)))));
        // DUCKLE-C8: the baseline kind's audited ops. Accepting a refused profile (or clearing the baseline)
        // changes what the next evaluation passes, so it is gated like evaluation itself — an operate action.
        api.post("/expectations/([^/]+)/baseline/accept", ApiContext.withCapability("canOperateRuns",
                (e, m) -> acceptProfile(api, e, ApiContext.name(m))));
        api.post("/expectations/([^/]+)/baseline/clear", ApiContext.withCapability("canOperateRuns",
                (e, m) -> clearBaseline(api, e, ApiContext.name(m))));

        api.post("/expectations", ApiContext.withCapability("canAuthorWorkbench",
                (e, m) -> single(e, create(api, api.body(e)))));
        api.put("/expectations/([^/]+)", ApiContext.withCapability("canAuthorWorkbench",
                (e, m) -> single(e, update(api, ApiContext.name(m), api.body(e)))));
        api.delete("/expectations/([^/]+)", ApiContext.withCapability("canAuthorWorkbench",
                (e, m) -> delete(api, ApiContext.name(m))));
    }

    /** SEC-7(b): an expectation's only verbs are the Workbench-authoring family — declare the
     *  per-resource applicable set on single-resource responses (design: resource-permissions-design.md). */
    private static Object single(com.sun.net.httpserver.HttpExchange e, Object result) {
        ApiContext.resourcePermissions(e, java.util.Set.of("canAuthorWorkbench"));
        return result;
    }

    // ── CRUD ──────────────────────────────────────────────────────────────────────

    /**
     * {@code GET /expectations/breached-count} → {@code {count}}: enabled Expectations whose persisted
     * {@code lastResult.status} is {@code FAILED}. Reads the same registry {@link #list} reads (O(n) content maps,
     * no evaluation); a never-evaluated Expectation has a null lastResult and does not count; write root unset ⇒ 0.
     */
    private Object breachedCount(ApiContext api) {
        Path root = api.writeRoot() == null ? null : api.writeRoot().resolve("registry");
        long count = 0;
        if (root != null) {
            for (ComponentRegistry.Component c : new ComponentStore(root).list(TYPE)) {
                Map<String, Object> content = c.content();
                if ("false".equalsIgnoreCase(String.valueOf(content.getOrDefault("enabled", "true")))) continue;
                if (content.get("lastResult") instanceof Map<?, ?> last
                        && "FAILED".equalsIgnoreCase(String.valueOf(last.get("status")))) count++;
            }
        }
        return Map.of("count", count);
    }

    private Object list(ApiContext api) {
        Path root = api.writeRoot() == null ? null : api.writeRoot().resolve("registry");
        if (root == null) return List.of();
        return new ComponentStore(root).list(TYPE).stream()
                .map(ComponentRegistry.Component::content)
                .sorted(Comparator.comparing(c -> String.valueOf(c.get("name"))))
                .toList();
    }

    private Object create(ApiContext api, Map<String, Object> body) throws IOException {
        ComponentStore store = store(api);
        census(body);
        Expectation exp = parse(body);
        if (RouteErrors.exists(store, TYPE, exp.name()))
            throw new ApiException(409, "expectation '" + exp.name() + "' already exists (use PUT to update)");
        long now = System.currentTimeMillis();
        Map<String, Object> content = exp.toMap();
        content.put("lastResult", null);
        content.put("createdAt", now);
        content.put("updatedAt", now);
        return write(store, exp.name(), content);
    }

    private Object update(ApiContext api, String name, Map<String, Object> body) throws IOException {
        ComponentStore store = store(api);
        Map<String, Object> prev = RouteErrors.existing(store, TYPE, "expectation", name);
        census(body);
        Expectation exp = parse(body);
        Map<String, Object> content = exp.toMap();
        content.put("lastResult", prev.get("lastResult"));                       // preserve last evaluation
        content.put("createdAt", prev.getOrDefault("createdAt", System.currentTimeMillis()));
        content.put("updatedAt", System.currentTimeMillis());
        return write(store, name, content);
    }

    private Object delete(ApiContext api, String name) throws IOException {
        ComponentStore store = store(api);
        RouteErrors.existing(store, TYPE, "expectation", name);   // 404 if absent
        store.delete(TYPE, name);
        baselines(api).delete(name);   // a re-created same-name expectation must not inherit this baseline
        return Map.of("deleted", name);
    }

    // ── evaluation ──────────────────────────────────────────────────────────────

    /**
     * A baseline profile recorded by this sweep is accepted only if the WHOLE sweep succeeded — every
     * evaluated expectation PASSED. One failing check anywhere leaves every profile recorded but unaccepted.
     */
    private Object evaluateAll(ApiContext api) throws IOException {
        ComponentStore store = store(api);
        List<Map<String, Object>> out = new ArrayList<>();
        Map<String, String> recorded = new LinkedHashMap<>();   // baseline expectation → profile id
        boolean allPassed = true;
        for (ComponentRegistry.Component c : store.list(TYPE)) {
            Map<String, Object> content = c.content();
            if (!"false".equalsIgnoreCase(String.valueOf(content.getOrDefault("enabled", true)))) {
                Map<String, Object> next = runAndPersist(api, store, content, recorded);
                allPassed &= next.get("lastResult") instanceof Map<?, ?> r && "PASSED".equals(r.get("status"));
                out.add(next);
            } else
                out.add(content);
        }
        if (allPassed) acceptAll(api, recorded);
        out.sort(Comparator.comparing(c -> String.valueOf(c.get("name"))));
        return out;
    }

    private Object evaluateOne(ApiContext api, String name) throws IOException {
        ComponentStore store = store(api);
        Map<String, String> recorded = new LinkedHashMap<>();
        Map<String, Object> next = runAndPersist(api, store, RouteErrors.existing(store, TYPE, "expectation", name),
                recorded);
        if (next.get("lastResult") instanceof Map<?, ?> r && "PASSED".equals(r.get("status"))) acceptAll(api, recorded);
        return next;
    }

    private static void acceptAll(ApiContext api, Map<String, String> recorded) throws IOException {
        BaselineProfileStore baselines = baselines(api);
        for (Map.Entry<String, String> r : recorded.entrySet()) baselines.acceptByRun(r.getKey(), r.getValue());
    }

    /**
     * Evaluate one expectation, persist its result, fire the failure consequence chain, return the updated
     * content. A baseline expectation also RECORDS its profile — pass or fail — into {@code recorded}, for
     * the caller to accept once it knows whether the whole run succeeded.
     */
    private Map<String, Object> runAndPersist(ApiContext api, ComponentStore store, Map<String, Object> content,
                                              Map<String, String> recorded) throws IOException {
        Expectation exp = parse(content);
        ExpectationEvaluator.Result result;
        Map<String, Object> baselineDetail = new LinkedHashMap<>();
        try {
            result = "baseline".equals(exp.kind())
                    ? evaluateBaseline(api, exp, recorded, baselineDetail)
                    : ExpectationEvaluator.evaluate(exp, api.dataRoot());
        } catch (IllegalArgumentException bad) {
            throw new ApiException(422, bad.getMessage());
        } catch (SQLException sql) {
            throw new ApiException(422, "expectation evaluation failed: " + DuckDbUtil.withoutPendingQueryPreamble(sql.getMessage()));
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

        if ("FAILED".equals(result.status())) raiseIncident(api, exp, result.violations());
        return next;
    }

    /**
     * The {@code baseline} kind: profile the input, compare it with the median of the accepted window, and
     * RECORD the profile whatever the verdict (a refused run still records it). Acceptance is the caller's.
     */
    private static ExpectationEvaluator.Result evaluateBaseline(ApiContext api, Expectation exp,
                                                                Map<String, String> recorded,
                                                                Map<String, Object> detail) throws SQLException {
        try {
            BaselineProfileStore baselines = baselines(api);
            long now = System.currentTimeMillis();
            var profile = BaselineEvaluator.profile(exp, api.dataRoot());
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
            // Fail closed: an unreadable durable history yields no verdict — never one against "no baseline".
            throw new ApiException(503, "baseline evaluation unavailable: " + store.getMessage());
        }
    }

    // ── baseline ops (audited) ────────────────────────────────────────────────────

    /**
     * {@code POST /expectations/{name}/baseline/accept} {@code {profileId?}} — accept a recorded profile into
     * the baseline (default: the most recent one). Returns the audit entry, which carries the window it
     * replaced. 404 unknown expectation/profile · 422 not a baseline expectation · 409 already accepted.
     */
    private Object acceptProfile(ApiContext api, HttpExchange e, String name) throws IOException {
        Expectation exp = baselineExpectation(api, name);
        Map<String, Object> body = api.body(e);
        Object id = body == null ? null : body.get("profileId");
        try {
            return baselines(api).accept(exp.name(), id == null ? null : String.valueOf(id).trim(),
                    ApiContext.actor(e), exp.baseline().window());
        } catch (java.util.NoSuchElementException missing) {
            throw new ApiException(404, missing.getMessage());
        } catch (IllegalStateException already) {
            throw new ApiException(409, already.getMessage());
        } catch (IOException store) {
            throw new ApiException(503, "baseline store unavailable: " + store.getMessage());
        }
    }

    /** {@code POST /expectations/{name}/baseline/clear} — un-accept every accepted profile (history kept);
     *  returns the audit entry carrying the accepted ids it replaced. */
    private Object clearBaseline(ApiContext api, HttpExchange e, String name) {
        Expectation exp = baselineExpectation(api, name);
        try {
            return baselines(api).clear(exp.name(), ApiContext.actor(e));
        } catch (IOException store) {
            throw new ApiException(503, "baseline store unavailable: " + store.getMessage());
        }
    }

    private Expectation baselineExpectation(ApiContext api, String name) {
        Expectation exp = parse(RouteErrors.existing(store(api), TYPE, "expectation", name));
        if (!"baseline".equals(exp.kind()))
            throw new ApiException(422, "expectation '" + name + "' is kind '" + exp.kind()
                    + "' — only a baseline expectation has a baseline to accept or clear");
        return exp;
    }

    /**
     * On a failed evaluation: open a correlated Incident (deduped while one for {@code expectation:<name>}
     * is still open) and emit the {@code EXPECTATION_FAILED} signal that fans out to notification channels.
     * Never disturbs the evaluation response — a persistence hiccup is logged and swallowed.
     */
    private void raiseIncident(ApiContext api, Expectation exp, long violations) {
        String correlationId = "expectation:" + exp.name();
        try {
            // ⚠ Through the seam since EDG-01 cell 7. Absent inspecto-ops there is nothing to raise an
            // Incident on, so the breach is still EVENTED below but not promoted — the same posture the
            // amended EDITIONS SP-CTL-02 takes for a sequence gap.
            com.gamma.objects.ObjectAccess objects = api.service().objects().orElse(null);
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
                    .source(ExpectationRoutes.class.getName())
                    .correlationId(correlationId)
                    .message(title + " — " + description)
                    .attr("expectation", exp.name())
                    .attr("kind", exp.kind())
                    .attr("violations", violations)
                    .attr("severity", exp.severity()));

            // AGT-5 P1 D4: the canonical expectation.violated Signal, additive to the legacy event
            // above — the triage layer subscribes to Signals, and RCA is otherwise blind to quality
            // breaches. Same correlationId so triage dedupes against the open Incident.
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

    // ── helpers ─────────────────────────────────────────────────────────────────

    private ComponentStore store(ApiContext api) {
        return new ComponentStore(WriteGates.requireWriteRoot(api, "expectation").resolve("registry"));
    }

    /** DUCKLE-C8's durable profile history. No write root ⇒ 503: there is no non-durable fallback. */
    private static BaselineProfileStore baselines(ApiContext api) {
        return new BaselineProfileStore(WriteGates.requireWriteRoot(api, "expectation baseline"));
    }

    /**
     * DUCKLE-C3 / COMPONENT-KIND-KEY-CENSUS-1, {@code expectation} third: refuse a top-level key of an
     * upsert body that nothing reads, instead of <b>silently dropping</b> it.
     *
     * <p>🔴 The loss mode here is not the one {@code widget}/{@code dashboard} have. Those persist the
     * raw body, so a dead key rots on disk. This route never persists the body at all — it rebuilds the
     * content from {@link Expectation#toMap()} — so before this gate a typo'd {@code patern:} returned
     * <b>200 with the key gone</b> and the author had no way to tell. Same census, opposite symptom.
     *
     * <p>Deliberately a KEY census only, exactly as for {@code widget}/{@code dashboard}: the accepted
     * NAMES come from {@code ConfigSpecs.expectation()} but its required-field and cross-field rules are
     * NOT run here, so a draft that saves today still saves. {@link Expectation}'s own constructor remains
     * the only value validator.
     *
     * <p>Applied on the two AUTHORING paths only, never in {@link #parse} — {@code runAndPersist} parses
     * <em>stored</em> content, and censusing there would make a legacy expectation carrying a dead key
     * (one written through the {@code /components/expectation} back door before that was gated)
     * un-evaluatable rather than merely un-editable.
     */
    private static void census(Map<String, Object> body) {
        if (body == null) return;   // parse() owns the "missing body" message
        try {
            ComponentRoutes.refuseUnknownComponentKeys(TYPE, body);
        } catch (IllegalArgumentException e) {
            throw new ApiException(422, e.getMessage());
        }
    }

    private static Expectation parse(Map<String, Object> body) {
        try {
            return Expectation.fromMap(body);
        } catch (IllegalArgumentException e) {
            throw new ApiException(422, e.getMessage());
        }
    }

    private static Object write(ComponentStore store, String name, Map<String, Object> content) throws IOException {
        try {
            ComponentRegistry.Component c = store.write(TYPE, name, content);
            return c.content();
        } catch (IllegalArgumentException e) {
            throw new ApiException(422, e.getMessage());
        }
    }
}
