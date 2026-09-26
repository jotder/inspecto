package com.gamma.control;

import com.gamma.pipeline.ComponentStore;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Requirements intake ({@code /requirements*}, UI-6 + SEC-7(c)) — the backend for the Business→Builder
 * Requirement lifecycle the UI authored mock-first: Business <b>submits</b> (open — raising a requirement
 * needs no special capability, matching the Lens design), a Builder <b>triages</b> (accept/reject) and
 * later marks an accepted requirement <b>delivered</b>. Requirements persist as {@code requirement}
 * components under {@code <write-root>/registry}.
 *
 * <p>SEC-7(c): the triage + deliver transitions are gated <b>server-side</b> on {@code canTriageRequirements}
 * (a no-op on Personal — no Subject is attached); submission and listing are open. The prior mock routed
 * every write through the generic {@code canAuthorWorkbench} component CRUD, which would have both blocked
 * Business submission and left triage unenforced on the backend.
 *
 * <p>Fail-closed: write root unset → 503; unknown kind / bad body → 422; duplicate submit → 409; unknown
 * requirement → 404; an out-of-lifecycle transition (decide a non-{@code submitted}, deliver a
 * non-{@code accepted}) → 409.
 */
final class RequirementRoutes implements RouteModule {

    private static final String TYPE = "requirement";
    private static final Set<String> KINDS = Set.of("kpi", "report", "reconciliation", "rule");

    @Override
    public void register(ApiContext api) {
        api.get("/requirements", (e, m) -> list(api));
        api.post("/requirements", (e, m) -> stamped(e, submit(api, api.body(e))));
        api.post("/requirements/([^/]+)/decision", ApiContext.withCapability("canTriageRequirements",
                (e, m) -> stamped(e, decide(api, ApiContext.name(m), api.body(e)))));
        api.post("/requirements/([^/]+)/deliver", ApiContext.withCapability("canTriageRequirements",
                (e, m) -> stamped(e, deliver(api, ApiContext.name(m), api.body(e)))));
        // ASSURE-KPI-DEFINITIONS-1: a delivered kpi Requirement can CREATE a KPI definition — an explicit action,
        // never automatic. It authors a component, so it needs the authoring capability, not the triage one.
        api.post("/requirements/([^/]+)/kpi", ApiContext.withCapability("canAuthorWorkbench",
                (e, m) -> createKpi(api, e, ApiContext.name(m), api.body(e))));
    }

    /** SEC-7(b): declare the per-resource applicable set from the requirement's lifecycle state —
     *  {@code submitted}/{@code accepted} can still be triaged; {@code rejected}/{@code delivered} are
     *  terminal, so nothing applies. Design: docs/superpower/resource-permissions-design.md. */
    private static Object stamped(com.sun.net.httpserver.HttpExchange e, Object result) {
        if (result instanceof Map<?, ?> m) {
            String status = String.valueOf(m.get("status"));
            ApiContext.resourcePermissions(e, switch (status) {
                case "submitted", "accepted" -> Set.of("canTriageRequirements");
                default -> Set.of();
            });
        }
        return result;
    }

    private Object list(ApiContext api) {
        Path root = api.writeRoot() == null ? null : api.writeRoot().resolve("registry");
        if (root == null) return List.of();
        return new ComponentStore(root).list(TYPE).stream()
                .map(c -> view(c.content()))
                .sorted(Comparator.comparing(v -> String.valueOf(v.getOrDefault("submittedAt", ""))))
                .toList();
    }

    private Object submit(ApiContext api, Map<String, Object> body) throws IOException {
        ComponentStore store = store(api);
        String id = ApiContext.str(body, "id");
        if (id == null) id = ApiContext.str(body, "name");
        if (id == null) throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "requirement 'id' is required");
        String title = ApiContext.str(body, "title");
        if (title == null) throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "requirement 'title' is required");
        String kind = ApiContext.str(body, "kind");
        if (kind == null || !KINDS.contains(kind.toLowerCase())) throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "requirement 'kind' must be one of " + KINDS);
        if (RouteErrors.exists(store, TYPE, id))
            throw new ApiException(409, ErrorCodes.CONFLICT, "requirement '" + id + "' already exists");

        Map<String, Object> content = new LinkedHashMap<>();
        content.put("title", title);
        content.put("kind", kind.toLowerCase());
        content.put("description", ApiContext.str(body, "description") == null ? "" : body.get("description"));
        content.put("status", "submitted");
        content.put("submittedAt", Instant.now().toString());
        // A KPI target/threshold is a business acceptance criterion, authored by Business ON the
        // Requirement — not buried in the Component/dashboard that implements it (product sign-off
        // 2026-07-22, mirrors the "Business submits a Requirement, Builder delivers" pattern). Persist
        // the optional target (+ comparator/unit) for a kpi requirement so it rides on the object the
        // Builder is asked to satisfy. Optional, so an in-flight kpi requirement without one still submits.
        if ("kpi".equals(kind.toLowerCase())) {
            putIfPresent(content, body, "target");
            putIfPresent(content, body, "comparator");
            putIfPresent(content, body, "unit");
        }
        return write(store, id, content);
    }

    /** Copy {@code key} from the request body into {@code content} only when the caller supplied it. */
    private static void putIfPresent(Map<String, Object> content, Map<String, Object> body, String key) {
        Object v = body.get(key);
        if (v != null) content.put(key, v);
    }

    private Object decide(ApiContext api, String id, Map<String, Object> body) throws IOException {
        ComponentStore store = store(api);
        Map<String, Object> content = RouteErrors.existing(store, TYPE, "requirement", id);
        if (!"submitted".equals(content.get("status")))
            throw new ApiException(409, ErrorCodes.CONFLICT, "requirement '" + id + "' is not awaiting a decision (status "
                    + content.get("status") + ")");
        boolean accept = Boolean.parseBoolean(String.valueOf(body.get("accept")))
                || Boolean.TRUE.equals(body.get("accept"));
        content.put("status", accept ? "accepted" : "rejected");
        content.put("decisionNote", ApiContext.str(body, "note"));
        content.put("decidedAt", Instant.now().toString());
        return write(store, id, content);
    }

    private Object deliver(ApiContext api, String id, Map<String, Object> body) throws IOException {
        ComponentStore store = store(api);
        Map<String, Object> content = RouteErrors.existing(store, TYPE, "requirement", id);
        if (!"accepted".equals(content.get("status")))
            throw new ApiException(409, ErrorCodes.CONFLICT, "only an accepted requirement can be delivered (status "
                    + content.get("status") + ")");
        content.put("status", "delivered");
        content.put("deliveredNote", ApiContext.str(body, "note"));
        content.put("deliveredAt", Instant.now().toString());
        return write(store, id, content);
    }

    private static final String KPI_TYPE = KpiRoutes.TYPE;

    /**
     * {@code POST /requirements/{id}/kpi} — create a KPI definition from a delivered {@code kpi} Requirement. The
     * Requirement supplies what Business authored on it — {@code target}, {@code unit}, its {@code title}, and the
     * good direction read off its {@code comparator} ({@code >=}/{@code >} → up, {@code <=}/{@code <} → down) —
     * and the body supplies what only a Builder knows: {@code dataset}, {@code measure}, {@code timeField},
     * {@code grain}, and optionally {@code id} (default: the Requirement's id), {@code comparison}, {@code bands},
     * {@code format}, or a {@code direction} that overrides the comparator's. Body keys win.
     *
     * <p>Fail closed: write root unset → 503; unsafe id → 422; unknown Requirement → 404; not a {@code kpi}
     * Requirement → 422; not {@code delivered}, or it already created one → 409; an invalid KPI or a Measure that
     * does not exist for this author → 422; a KPI of that id already exists → 409. Then the maker-checker hold (the
     * KPI is the governed write), the KPI written, and the Requirement stamped with {@code kpi: <id>}.
     */
    private Object createKpi(ApiContext api, com.sun.net.httpserver.HttpExchange ex, String id, Map<String, Object> body)
            throws IOException {
        ComponentStore store = store(api);
        Map<String, Object> req = RouteErrors.existing(store, TYPE, "requirement", id);
        if (!"kpi".equals(req.get("kind")))
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "requirement '" + id + "' is a "
                    + req.get("kind") + " requirement, not a kpi one");
        if (!"delivered".equals(req.get("status")))
            throw new ApiException(409, ErrorCodes.CONFLICT, "only a delivered requirement can create a KPI (status "
                    + req.get("status") + ")");
        if (req.get(KPI_TYPE) != null)
            throw new ApiException(409, ErrorCodes.CONFLICT, "requirement '" + id + "' already created kpi '" + req.get(KPI_TYPE) + "'");

        Map<String, Object> kpi = new LinkedHashMap<>();
        kpi.put("title", req.get("title"));
        putIfPresent(kpi, req, "target");
        putIfPresent(kpi, req, "unit");
        String direction = directionOf(req.get("comparator"));
        if (direction != null) kpi.put("direction", direction);
        kpi.putAll(body);
        String kpiId = ApiContext.str(kpi, "id") == null ? id : ApiContext.str(kpi, "id");
        kpi.remove("id");
        kpi.put("requirement", id);
        Map<String, Object> shaped = ComponentAccess.onCreate(ex, kpi);
        try {
            KpiRoutes.requireMeasure(api, ex, kpiId, shaped);
        } catch (IllegalArgumentException bad) {
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, bad.getMessage());
        }
        if (RouteErrors.exists(store, KPI_TYPE, kpiId))
            throw new ApiException(409, ErrorCodes.CONFLICT, "kpi '" + kpiId + "' already exists");
        Map<String, Object> proposed = new LinkedHashMap<>(shaped);
        proposed.put("name", kpiId);   // what the store persists
        PendingChanges.hold(api, ex, KPI_TYPE, kpiId, proposed, null);   // maker-checker: the KPI is the governed write
        Map<String, Object> written;
        try {
            written = store.write(KPI_TYPE, kpiId, shaped).content();
        } catch (IllegalArgumentException bad) {
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, bad.getMessage());
        }
        req.put(KPI_TYPE, kpiId);
        write(store, id, req);
        return written;
    }

    /** The KPI direction a Requirement's comparator states, or {@code null} when it states none. */
    private static String directionOf(Object comparator) {
        return switch (comparator == null ? "" : comparator.toString().trim()) {
            case ">=", ">", "gte", "gt" -> "up";
            case "<=", "<", "lte", "lt" -> "down";
            default -> null;
        };
    }

    // ── helpers ─────────────────────────────────────────────────────────────────

    /** The API JSON view: the stored content with the component's in-file {@code name} surfaced as {@code id}. */
    private static Map<String, Object> view(Map<String, Object> content) {
        Map<String, Object> v = new LinkedHashMap<>(content);
        v.put("id", content.get("name"));
        v.remove("name");
        return v;
    }

    private ComponentStore store(ApiContext api) {
        return new ComponentStore(WriteGates.requireWriteRoot(api, "requirement").resolve("registry"));
    }

    private static Object write(ComponentStore store, String id, Map<String, Object> content) throws IOException {
        try {
            return view(store.write(TYPE, id, content).content());
        } catch (IllegalArgumentException e) {
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, e.getMessage());
        }
    }
}
