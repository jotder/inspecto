package com.gamma.control;

import com.gamma.spi.auth.ApiException;
import com.gamma.spi.auth.ErrorCodes;
import com.gamma.spi.http.ApiContext;
import com.gamma.spi.http.RouteModule;
import com.gamma.expectation.BaselineProfileStore;
import com.gamma.expectation.Expectation;
import com.gamma.expectation.ExpectationRunner;
import com.gamma.pipeline.ComponentRegistry;
import com.gamma.pipeline.ComponentStore;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import com.gamma.access.WriteGates;

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
 * Basic); evaluation persists {@code lastResult}, so it also needs the write root.
 *
 * <p>The {@code baseline} kind (DUCKLE-C8) additionally records a profile per evaluation in
 * {@link BaselineProfileStore} and is accepted only when the whole run passed; its audited
 * {@code /baseline/accept|clear} ops are gated {@code canOperateRuns}. An unreadable history is 503.
 */
final class ExpectationRoutes implements RouteModule {

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
                (e, m) -> single(e, create(api, e, api.body(e)))));
        api.put("/expectations/([^/]+)", ApiContext.withCapability("canAuthorWorkbench",
                (e, m) -> single(e, update(api, e, ApiContext.name(m), api.body(e)))));
        api.delete("/expectations/([^/]+)", ApiContext.withCapability("canAuthorWorkbench",
                (e, m) -> delete(api, e, ApiContext.name(m))));
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

    private Object create(ApiContext api, com.sun.net.httpserver.HttpExchange e, Map<String, Object> body) throws IOException {
        ComponentStore store = store(api);
        census(body);
        Expectation exp = parse(body);
        if (RouteErrors.exists(store, TYPE, exp.name()))
            throw new ApiException(409, ErrorCodes.CONFLICT, "expectation '" + exp.name() + "' already exists (use PUT to update)");
        long now = System.currentTimeMillis();
        Map<String, Object> content = AuthorKeys.carry(body, exp.toMap());   // MODULE-REORG-P4-2: x- annotations are kept
        content.put("lastResult", null);
        content.put("createdAt", now);
        content.put("updatedAt", now);
        PendingChanges.hold(api, e, TYPE, exp.name(), content, null);   // maker-checker
        return write(store, exp.name(), content);
    }

    private Object update(ApiContext api, com.sun.net.httpserver.HttpExchange e, String name, Map<String, Object> body) throws IOException {
        ComponentStore store = store(api);
        Map<String, Object> prev = RouteErrors.existing(store, TYPE, "expectation", name);
        census(body);
        Expectation exp = parse(body);
        Map<String, Object> content = AuthorKeys.carry(body, exp.toMap());   // PUT replaces: x- annotations are the posted ones
        content.put("lastResult", prev.get("lastResult"));                       // preserve last evaluation
        content.put("createdAt", prev.getOrDefault("createdAt", System.currentTimeMillis()));
        content.put("updatedAt", System.currentTimeMillis());
        PendingChanges.hold(api, e, TYPE, name, content, prev);   // maker-checker
        return write(store, name, content);
    }

    private Object delete(ApiContext api, com.sun.net.httpserver.HttpExchange e, String name) throws IOException {
        ComponentStore store = store(api);
        Map<String, Object> current = RouteErrors.existing(store, TYPE, "expectation", name);   // 404 if absent
        PendingChanges.hold(api, e, TYPE, name, null, current);   // maker-checker
        store.delete(TYPE, name);
        baselines(api).delete(name);   // a re-created same-name expectation must not inherit this baseline
        return Map.of("deleted", name);
    }

    // ── evaluation ──────────────────────────────────────────────────────────────

    /** The shared evaluator (also driven by the {@code expectation.evaluate} Job) over this request's Space. */
    private ExpectationRunner runner(ApiContext api) {
        return new ExpectationRunner(WriteGates.requireWriteRoot(api, "expectation"), api.dataRoot(),
                HostContext.of(api).service().objects().orElse(null), m -> ServerFaults.sqlMessage(m, api));
    }

    private Object evaluateAll(ApiContext api) throws IOException {
        return runner(api).evaluateAll();
    }

    private Object evaluateOne(ApiContext api, String name) throws IOException {
        return runner(api).evaluateOne(RouteErrors.existing(store(api), TYPE, "expectation", name));
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
            throw new ApiException(404, ErrorCodes.NOT_FOUND, missing.getMessage());
        } catch (IllegalStateException already) {
            throw new ApiException(409, ErrorCodes.CONFLICT, already.getMessage());
        } catch (IOException store) {
            throw new ApiException(503, ErrorCodes.CAPABILITY_UNAVAILABLE, "baseline store unavailable: " + store.getMessage());
        }
    }

    /** {@code POST /expectations/{name}/baseline/clear} — un-accept every accepted profile (history kept);
     *  returns the audit entry carrying the accepted ids it replaced. */
    private Object clearBaseline(ApiContext api, HttpExchange e, String name) {
        Expectation exp = baselineExpectation(api, name);
        try {
            return baselines(api).clear(exp.name(), ApiContext.actor(e));
        } catch (IOException store) {
            throw new ApiException(503, ErrorCodes.CAPABILITY_UNAVAILABLE, "baseline store unavailable: " + store.getMessage());
        }
    }

    private Expectation baselineExpectation(ApiContext api, String name) {
        Expectation exp = parse(RouteErrors.existing(store(api), TYPE, "expectation", name));
        if (!"baseline".equals(exp.kind()))
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "expectation '" + name + "' is kind '" + exp.kind()
                    + "' — only a baseline expectation has a baseline to accept or clear");
        return exp;
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
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, e.getMessage());
        }
    }

    private static Expectation parse(Map<String, Object> body) {
        try {
            return Expectation.fromMap(body);
        } catch (IllegalArgumentException e) {
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, e.getMessage());
        }
    }

    private static Object write(ComponentStore store, String name, Map<String, Object> content) throws IOException {
        try {
            ComponentRegistry.Component c = store.write(TYPE, name, content);
            return c.content();
        } catch (IllegalArgumentException e) {
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, e.getMessage());
        }
    }
}
