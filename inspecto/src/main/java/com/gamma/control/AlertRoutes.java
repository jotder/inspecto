package com.gamma.control;

import com.gamma.alert.AlertRule;
import com.gamma.alert.AlertService;
import com.gamma.config.spec.Finding;
import com.gamma.config.spec.FindingCodes;
import com.gamma.config.spec.Severity;
import com.gamma.etl.EditionFeatures;
import com.gamma.pipeline.ComponentStore;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Alert execution engine routes ({@code /alerts*}, v4.1 B5): read-only listings of recent alerts and
 * the loaded rules, a manual evaluation sweep, and (SHOULD, alert-rule authoring) the CRUD writes that
 * arm/disarm rules at runtime. The engine itself is event-driven off the batch bus and lives in the
 * lean core (no agent required).
 *
 * <p>Alert Rules are authored objects persisted as {@code alert-rule} components under
 * {@code <write-root>/registry} (2026-07-18 — promoted off raw {@code *_alert.toon} files onto the
 * same {@link ComponentStore} CRUD contract {@link ExpectationRoutes}/{@code DecisionRoutes} already
 * use): write root unset → 503; an invalid rule body → 422; a duplicate create → 409; an unknown rule
 * on update/delete → 404. A create/update persists the component <em>and</em> arms the rule in the
 * running {@link AlertService} so {@code GET /alerts/rules} and evaluation reflect it immediately —
 * the in-memory list stays the evaluation-time source of truth (cheap per-batch reads), the
 * ComponentStore is what a restart re-arms from. CRUD requires {@code canAuthorAlertRules} (a no-op
 * on Personal).
 */
final class AlertRoutes implements RouteModule {

    private static final String TYPE = "alert-rule";

    @Override
    public void register(ApiContext api) {
        api.get("/alerts", (e, m) -> api.service().alertService()
                .map(a -> (Object) a.recent(ApiContext.parseIntOr(ApiContext.query(e, "limit"), 50)))
                .orElse(java.util.List.of()));
        api.get("/alerts/rules", (e, m) -> api.service().alertService()
                .map(a -> (Object) a.rules())
                .orElse(java.util.List.of()));
        // Gated 2026-09-17: a fired rule emits ALERT_FIRED, which the default NotificationRules dispatch to
        // email/webhook — this route can page people, so it is an operate action, not a read.
        api.post("/alerts/evaluate", ApiContext.withCapability("canOperateRuns", (e, m) -> api.service().alertService()
                .map(a -> (Object) a.evaluateAll())
                .orElseThrow(() -> new ApiException(503, ErrorCodes.CAPABILITY_UNAVAILABLE,
                        "alert engine not armed (no alert-rule components loaded)"))));
        api.post("/alerts/rules", ApiContext.withCapability("canAuthorAlertRules",
                (e, m) -> editionRefused(e) ? ApiContext.HANDLED : single(e, create(api, e, api.body(e)))));
        api.put("/alerts/rules/([^/]+)", ApiContext.withCapability("canAuthorAlertRules",
                (e, m) -> editionRefused(e) ? ApiContext.HANDLED
                        : single(e, update(api, e, ApiContext.name(m), api.body(e)))));
        api.delete("/alerts/rules/([^/]+)", ApiContext.withCapability("canAuthorAlertRules",
                (e, m) -> delete(api, ApiContext.name(m))));
    }

    /** An alert rule's only verbs are the alert-authoring family — declare the applicable set (SEC-7b). */
    private static Object single(HttpExchange e, Object result) {
        ApiContext.resourcePermissions(e, Set.of("canAuthorAlertRules"));
        return result;
    }

    // ── CRUD ──────────────────────────────────────────────────────────────────────

    private Object create(ApiContext api, HttpExchange e, Map<String, Object> body) throws IOException {
        ComponentStore store = store(api);
        // R3 envelope: the authenticated Subject becomes the owner — who the rule's alerts are addressed to.
        Map<String, Object> shaped = ComponentAccess.onCreate(e, body);
        AlertRule rule = parse(api, shaped);                                 // 422 on an invalid rule
        if (RouteErrors.exists(store, TYPE, rule.name()))
            throw new ApiException(409, ErrorCodes.CONFLICT, "alert rule '" + rule.name() + "' already exists (use PUT to update)");
        Map<String, Object> content = write(store, rule.name(), persisted(rule, shaped));
        alerts(api).upsert(rule);                                       // arm in the running engine
        return content;
    }

    private Object update(ApiContext api, HttpExchange e, String name, Map<String, Object> body) throws IOException {
        ComponentStore store = store(api);
        Map<String, Object> stored = RouteErrors.existing(store, TYPE, "alert rule", name);   // 404 if absent
        // The name is the storage key — immutable on update. Bind it from the path, not the body, so a
        // stale/edited body name can never fork the component or the in-memory rule.
        Map<String, Object> patched = new java.util.LinkedHashMap<>(body);
        patched.put("name", name);
        // R3 envelope: edit access against the stored rule, owner/shares carried forward, and an owner change
        // only by the owner or an access admin — so an edit never silently re-addresses someone's alerts.
        Map<String, Object> shaped = ComponentAccess.onUpdate(e, TYPE, name, stored, patched);
        AlertRule rule = parse(api, shaped);
        Map<String, Object> content = write(store, name, persisted(rule, shaped));
        alerts(api).upsert(rule);
        return content;
    }

    private Object delete(ApiContext api, String name) throws IOException {
        ComponentStore store = store(api);
        RouteErrors.existing(store, TYPE, "alert rule", name);   // 404 if absent
        store.delete(TYPE, name);
        alerts(api).remove(name);
        return Map.of("deleted", name);
    }

    /**
     * Shared authoring entry point (S6) reused by {@code DecisionRoutes}' {@code create-alert}
     * consequence: parse + validate exactly like the human {@code POST}/{@code PUT /alerts/rules}
     * (never duplicate {@link AlertRule#fromMap} validation), persist via the same {@link ComponentStore},
     * and arm in the running {@link AlertService}. Upserts (create if absent, update if present) so a
     * Decision Rule firing repeatedly against the same alert name converges rather than 409ing.
     */
    static Map<String, Object> authorFromConsequence(ApiContext api, Map<String, Object> body) throws IOException {
        ComponentStore store = new ComponentStore(WriteGates.requireWriteRoot(api, "alert rule write").resolve("registry"));
        // No Subject: a consequence fires on data, not on a request. An existing rule keeps its envelope (the
        // owner its alerts go to, its shares), exactly as a plain content save does in R3.
        Map<String, Object> shaped = carryEnvelope(storedContent(store, ApiContext.str(body, "name")), body);
        AlertRule rule = parse(api, shaped);
        Map<String, Object> content = write(store, rule.name(), persisted(rule, shaped));
        alerts(api).upsert(rule);
        return content;
    }

    // ── helpers ─────────────────────────────────────────────────────────────────

    /**
     * What an Alert Rule write stores: the rule's own map plus the R3 {@code shares} list, which
     * {@link AlertRule} does not model — without it a save through these routes would strip a shared rule's
     * protection. The {@code owner} rides {@link AlertRule#toMap} itself (DUCKLE-C1 residual 2: it is the
     * addressee of the rule's alerts).
     */
    private static Map<String, Object> persisted(AlertRule rule, Map<String, Object> shaped) {
        Map<String, Object> out = rule.toMap();
        if (shaped.containsKey(ComponentAccess.SHARES)) out.put(ComponentAccess.SHARES, shaped.get(ComponentAccess.SHARES));
        return out;
    }

    /** {@code body} with the stored envelope ({@code owner}, {@code shares}) carried forward where it omits them. */
    private static Map<String, Object> carryEnvelope(Map<String, Object> stored, Map<String, Object> body) {
        Map<String, Object> out = new java.util.LinkedHashMap<>(body);
        if (stored == null) return out;
        for (String key : List.of(ComponentAccess.OWNER, ComponentAccess.SHARES))
            if (!out.containsKey(key) && stored.containsKey(key)) out.put(key, stored.get(key));
        return out;
    }

    /** The stored rule's content, or {@code null} when there is none (or the name is not a storable one —
     *  the write that follows refuses that itself). */
    private static Map<String, Object> storedContent(ComponentStore store, String name) {
        if (name == null) return null;
        try {
            return store.get(TYPE, name).map(com.gamma.pipeline.ComponentRegistry.Component::content).orElse(null);
        } catch (IllegalArgumentException unsafeName) {
            return null;
        }
    }

    private ComponentStore store(ApiContext api) {
        return new ComponentStore(WriteGates.requireWriteRoot(api, "alert rule write").resolve("registry"));
    }

    private static Map<String, Object> write(ComponentStore store, String name, Map<String, Object> content)
            throws IOException {
        try {
            return store.write(TYPE, name, content).content();
        } catch (IllegalArgumentException e) {
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, e.getMessage());
        }
    }

    /**
     * Alert Rules are Professional+ ({@code SP-CTL-07}; `PROCESSOR-RELEASE-READINESS-1` G9): on a Personal build
     * this answers the request itself - 422, the save gate's body shape, one {@code ERR_EDITION_FEATURE}
     * finding - and returns {@code true}. Used by create, update and {@code /components/alert-rule}; a Decision
     * Rule's {@code create-alert} is refused inside {@link #parse}, where the consequence records the message.
     */
    static boolean editionRefused(HttpExchange e) throws IOException {
        if (EditionFeatures.present(EditionFeatures.ALERT_DISPATCH)) return false;
        Finding f = new Finding(Severity.ERROR, "alert-rule", EditionFeatures.refusal(EditionFeatures.ALERT_DISPATCH),
                FindingCodes.ERR_EDITION_FEATURE, "run the Professional or Enterprise edition");
        ApiContext.respondJson(e, 422, Map.of("written", false, "error", f.message(), "findings", List.of(f)));
        return true;
    }

    private static AlertService alerts(ApiContext api) {
        return api.service().alertService()
                .orElseThrow(() -> new ApiException(503, ErrorCodes.CAPABILITY_UNAVAILABLE, "alert engine unavailable"));
    }

    private static AlertRule parse(ApiContext api, Map<String, Object> body) {
        if (!EditionFeatures.present(EditionFeatures.ALERT_DISPATCH))   // the consequence's door (G9)
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, EditionFeatures.refusal(EditionFeatures.ALERT_DISPATCH));
        AlertRule rule;
        try {
            rule = AlertRule.fromMap(body);
        } catch (IllegalArgumentException e) {
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, e.getMessage());
        }
        // LA-23: a rule over an Investigation's Working Set must pass that Investigation's owner-only / PDP gate,
        // which only the Link Analysis module can apply — and only its route records the binding the evaluator
        // requires, so a rule written here would be armed yet never evaluate. Refused loudly, with the way in.
        if (rule.isInvestigationRule())
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "an Alert Rule over an Investigation (investigation:) is authored through "
                    + "POST /inv/investigations/{id}/alert-rules, which checks that you own the Investigation");
        requireGroupingColumns(api, rule);
        return rule;
    }

    /**
     * ASSURE-PER-ENTITY-ALERTS-1: every {@code by} column of a per-entity rule must exist in its Dataset's
     * Schema — the relation's columns, as {@code GET /datasets/{id}/rows} reports them. <b>Fail closed</b>: a
     * Schema that cannot be read (unknown Dataset, a relation DuckDB cannot open, no data yet) refuses the save
     * rather than arming a rule whose every sweep would silently compute nothing. A rule with no {@code by} is
     * not looked at. Also run by {@code /components/alert-rule} ({@code ComponentRoutes}).
     */
    static void requireGroupingColumns(ApiContext api, AlertRule rule) {
        if (!rule.isGrouped()) return;
        java.nio.file.Path writeRoot = WriteGates.requireWriteRoot(api, "alert rule write");
        List<String> columns;
        try {
            columns = new com.gamma.query.DatasetMeasureProbe(() -> writeRoot, api::dataRoot).columns(rule.dataset());
        } catch (IllegalArgumentException e) {
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "alert.by cannot be checked against "
                    + "the Schema of dataset '" + rule.dataset() + "': " + e.getMessage());
        }
        List<String> missing = rule.by().stream().filter(c -> !columns.contains(c)).toList();
        if (!missing.isEmpty())
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "alert.by column(s) " + missing
                    + " are not in the Schema of dataset '" + rule.dataset() + "' (have: " + columns + ")");
    }
}
