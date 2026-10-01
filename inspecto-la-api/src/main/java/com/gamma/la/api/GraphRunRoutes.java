package com.gamma.la.api;

import com.gamma.control.ApiContext;
import com.gamma.control.ApiException;
import com.gamma.control.ErrorCodes;
import com.gamma.control.LinkAnalysisSettings;
import com.gamma.control.Roles;
import com.gamma.control.RouteModule;
import com.gamma.control.Subject;
import com.gamma.control.WriteGates;
import com.gamma.event.Event;
import com.gamma.event.EventLog;
import com.gamma.la.core.Algorithm;
import com.gamma.la.core.GraphBudget;
import com.gamma.la.core.GraphEngine;
import com.gamma.la.core.GraphInput;
import com.gamma.la.core.GraphRunException;
import com.gamma.la.core.GraphRunService;
import com.gamma.la.core.GraphRunService.RunView;
import com.gamma.la.core.GraphRunService.Status;
import com.gamma.la.core.InMemoryGraphEngine;
import com.gamma.la.core.InvalidGraphRequest;
import com.gamma.la.core.LinkEventTypes;
import com.gamma.la.core.WorkingSetGraphInput;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Server-side graph analysis over an Investigation's Working Set (LA separation D-4 step 6, design §3.3): the 28
 * {@code inspecto-la-graph} algorithms behind {@link GraphRunService}, over HTTP.
 *
 * <ul>
 *   <li>{@code GET /inv/graph/algorithms} - the catalogue, the server's hard ceilings and the budget a run takes when
 *       it states none (the Space's {@code graph_run} settings, clamped to the ceilings).</li>
 *   <li>{@code POST /inv/graph/runs} - start a run. Gated by {@code canRunLinkGraphAnalysis} (a run spends compute).
 *       {@code 200} with the finished run when it answered inline or was already terminal (an over-budget input, a
 *       cache hit); {@code 202} + {@code Location} otherwise.</li>
 *   <li>{@code GET /inv/graph/runs/{id}}, {@code GET /inv/graph/runs?investigationId=} - read; the Investigation's own
 *       access is re-checked, and a run another caller started is absent (404).</li>
 *   <li>{@code POST /inv/graph/runs/{id}/cancel} - the run's starter or an administrator.</li>
 * </ul>
 *
 * <p><b>Decision 7 - inline or job.</b> A run whose Working Set has at most {@link Algorithm#inlineNodeCeiling()} nodes is
 * waited for briefly ({@value #INLINE_WAIT_MS} ms) and answered {@code 200}; a larger one is a {@code 202} the caller polls.
 * Both are the same run in the same service - the ceiling only decides whether the request waits.
 *
 * <p><b>Never a silent cap.</b> A run past its budget is a {@code 200} whose body says {@code BUDGET_EXCEEDED}, the budget,
 * the measured size and the reason - and carries NO {@code result} key, ever. The budget in force and what was consumed
 * are echoed on every view.
 *
 * <p><b>Masked (D-U6).</b> The engine and its cache hold raw ids; {@link GraphResultJson} masks every id on the way out,
 * AFTER the cache, with the Investigation's {@link EntityMasking}. A masked pseudonym is accepted back as a node-id
 * parameter ({@code from}/{@code to}/{@code node}) and resolved to its entity, as an op's {@code ids} are.
 *
 * <p><b>Which rows.</b> The Working Set at {@code at} (default: the committed head), minus entities a {@code hide} op hid
 * and the links touching them (counted in {@code input.hiddenEntities}); a {@code resolve} identity group is NOT merged -
 * the graph has one node per entity id. Reads inherit {@link InvestigationRoutes#openForRead}'s gate on every request,
 * before the cache is consulted.
 *
 * <p><b>Service lifetime.</b> One {@link GraphRunService} per Space (write root), created on first use with that Space's
 * {@code graph_run} {@code threads}/{@code queue} (changing them needs a restart), closed with the API ({@link
 * ApiContext#onClose}). Terminal events of asynchronous runs are audited from the service's terminal hook.
 */
public final class GraphRunRoutes implements RouteModule {

    /** How long a run within its algorithm's inline ceiling is waited for before the answer becomes a 202. */
    static final long INLINE_WAIT_MS = 3_000;
    private static final int MAX_KINDS = 100;
    private static final Set<String> BODY_KEYS = Set.of("investigationId", "at", "algorithm", "params", "weights", "kinds", "budget");

    /** Test seam (the {@code Authenticators.forTest} idiom): an engine for services created AFTER this call, and the inline wait. */
    private static volatile GraphEngine engineOverride;
    private static volatile long inlineWaitOverrideMs = -1;

    /** @param engine null = the real {@link InMemoryGraphEngine}; @param inlineWaitMs negative = {@value #INLINE_WAIT_MS} */
    public static void forTest(GraphEngine engine, long inlineWaitMs) {
        engineOverride = engine;
        inlineWaitOverrideMs = inlineWaitMs;
    }

    private final Map<Path, GraphRunService> services = new ConcurrentHashMap<>();
    private volatile boolean closing;

    @Override
    public void register(ApiContext api) {
        api.onClose(this::closeServices);
        // ⚠ String LITERAL on purpose - CapabilityManifestTest's scanner matches only a literal argument.
        api.post("/inv/graph/runs", ApiContext.withCapability("canRunLinkGraphAnalysis",
                (e, m) -> start(api, e, api.body(e))));
        api.get("/inv/graph/algorithms", (e, m) -> algorithms(api));
        api.get("/inv/graph/runs", (e, m) -> list(api, e));
        api.get("/inv/graph/runs/([^/]+)", (e, m) -> get(api, e, m.group(1)));
        api.post("/inv/graph/runs/([^/]+)/cancel", (e, m) -> cancel(api, e, m.group(1)));
    }

    // ── lifecycle ────────────────────────────────────────────────────────────────────────────────────────────

    private void closeServices() {
        closing = true;
        services.values().forEach(GraphRunService::close);
        services.clear();
    }

    private GraphRunService service(Path writeRoot) {
        if (closing) throw new ApiException(503, ErrorCodes.CAPABILITY_UNAVAILABLE, "the server is shutting down");
        return services.computeIfAbsent(writeRoot, root -> {
            GraphRunService.Limits std = GraphRunService.Limits.standard();
            LinkAnalysisSettings.GraphRun g = LinkAnalysisSettings.forRoot(root).effectiveGraphRun();
            GraphRunService.Limits limits = new GraphRunService.Limits(std.defaults(), std.ceilings(),
                    g.threads() != null ? g.threads() : std.threads(), g.queue() != null ? g.queue() : std.queue(),
                    std.runTtlMs(), std.maxRuns(), std.cacheTtlMs(), std.cacheEntries());
            GraphEngine engine = engineOverride != null ? engineOverride : new InMemoryGraphEngine();
            return new GraphRunService(engine, limits, System::currentTimeMillis, GraphRunRoutes::auditTerminal);
        });
    }

    private static long inlineWaitMs() {
        return inlineWaitOverrideMs >= 0 ? inlineWaitOverrideMs : INLINE_WAIT_MS;
    }

    // ── GET /inv/graph/algorithms ────────────────────────────────────────────────────────────────────────────

    private Object algorithms(ApiContext api) {
        GraphRunService.Limits std = GraphRunService.Limits.standard();
        LinkAnalysisSettings.GraphRun g = LinkAnalysisSettings.forRoot(api.writeRoot()).effectiveGraphRun();
        GraphBudget stated = settingsDefaults(g, std);
        GraphBudget inForce = stated.resolve(std.defaults(), std.ceilings());
        List<Object> catalogue = new ArrayList<>();
        for (Algorithm a : Algorithm.values()) {
            Map<String, Object> o = new LinkedHashMap<>();
            o.put("id", a.id());
            o.put("label", a.label());
            o.put("cost", a.cost().name());
            o.put("inlineNodeCeiling", a.inlineNodeCeiling());
            List<Object> params = new ArrayList<>();
            for (Algorithm.Param p : a.params()) {
                Map<String, Object> pm = new LinkedHashMap<>();
                pm.put("name", p.name());
                pm.put("type", p.type().name());
                pm.put("default", p.defaultValue());
                if (p.min() != null) pm.put("min", p.min());
                if (p.max() != null) pm.put("max", p.max());
                if (!p.allowed().isEmpty()) pm.put("allowed", p.allowed());
                params.add(pm);
            }
            o.put("params", params);
            o.put("needsWeights", a.needsWeights());
            o.put("needsSource", a.needsSource());
            o.put("needsTarget", a.needsTarget());
            o.put("needsNode", a.needsNode());
            o.put("resultKind", a.resultKind().name());
            catalogue.add(o);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("engine", (engineOverride != null ? engineOverride : new InMemoryGraphEngine()).engineId());
        out.put("algorithms", catalogue);
        out.put("ceilings", budget(std.ceilings()));
        Map<String, Object> defaults = budget(inForce);
        defaults.put("clamped", std.ceilings().clamps(stated));
        out.put("defaults", defaults);
        Map<String, Object> pool = new LinkedHashMap<>();
        pool.put("threads", g.threads() != null ? g.threads() : std.threads());
        pool.put("queue", g.queue() != null ? g.queue() : std.queue());
        out.put("pool", pool);
        out.put("inlineWaitMs", inlineWaitMs());
        return out;
    }

    /** The Space's default budget: each stated {@code graph_run} field, else the service's shipped default. */
    private static GraphBudget settingsDefaults(LinkAnalysisSettings.GraphRun g, GraphRunService.Limits std) {
        return new GraphBudget(g.maxNodes() != null ? g.maxNodes() : std.defaults().maxNodes(),
                g.maxEdges() != null ? g.maxEdges() : std.defaults().maxEdges(),
                g.timeoutMs() != null ? g.timeoutMs() : std.defaults().timeoutMs());
    }

    // ── POST /inv/graph/runs ─────────────────────────────────────────────────────────────────────────────────

    private Object start(ApiContext api, HttpExchange ex, Map<String, Object> body) throws IOException {
        Path writeRoot = WriteGates.requireWriteRoot(api, "graph run");                      // 503
        for (String k : body.keySet())
            if (!BODY_KEYS.contains(k))
                throw bad("unknown field '" + k + "' (a run takes: " + String.join(", ", new TreeSet<>(BODY_KEYS)) + ")");
        String invId = ApiContext.str(body, "investigationId");
        if (invId == null || invId.isBlank()) throw bad("body must include 'investigationId'");
        Algorithm algorithm = algorithm(body);                                               // 422
        Map<String, Object> params = params(body);
        String weights = weights(body);
        List<String> kinds = kinds(body);
        GraphBudget asked = budget(body);
        int at = at(body);

        InvestigationRoutes.Inv inv = InvestigationRoutes.openForRead(api, ex, invId);        // 422 · 403 · 404 · PDP
        EntityMasking mask = EntityMasking.of(inv, List.of());
        for (String node : List.of(Algorithm.FROM, Algorithm.TO, Algorithm.NODE))            // a pseudonym the caller saw -> its entity
            if (params.get(node) instanceof String s) params.put(node, mask.resolve(List.of(s)).get(0));

        boolean[] cached = {false};
        WorkingSetRoutes.Relation rel = WorkingSetRoutes.relation(inv, at, cached);
        List<Map<String, Object>> entities = rel.tables().get("entities");
        List<Map<String, Object>> links = rel.tables().get("links");
        Set<String> hidden = new TreeSet<>();
        for (Map<String, Object> r : entities) if (Boolean.TRUE.equals(r.get("hidden"))) hidden.add(String.valueOf(r.get("entityId")));
        List<Map<String, Object>> shownEntities = hidden.isEmpty() ? entities
                : entities.stream().filter(r -> !hidden.contains(String.valueOf(r.get("entityId")))).toList();
        List<Map<String, Object>> shownLinks = hidden.isEmpty() ? links
                : links.stream().filter(r -> !hidden.contains(String.valueOf(r.get("source")))
                        && !hidden.contains(String.valueOf(r.get("target")))).toList();
        GraphInput input = WorkingSetGraphInput.from(shownEntities, shownLinks, kinds);
        if ("none".equals(weights)) input = new GraphInput(input.nodes(), input.edges(), Map.of(), input.droppedDangling());

        GraphRunService svc = service(writeRoot);
        GraphRunService.Limits std = GraphRunService.Limits.standard();
        GraphBudget stated = settingsDefaults(LinkAnalysisSettings.forRoot(writeRoot).effectiveGraphRun(), std);
        GraphBudget budget = new GraphBudget(asked.maxNodes() > 0 ? asked.maxNodes() : stated.maxNodes(),
                asked.maxEdges() > 0 ? asked.maxEdges() : stated.maxEdges(),
                asked.timeoutMs() > 0 ? asked.timeoutMs() : stated.timeoutMs());
        String owner = callerId(ex);
        RunView v;
        try {
            v = svc.submit(new GraphRunService.Request(owner, invId, rel.key(), scopeFingerprint(ex), algorithm, params,
                    weights, input, budget));
        } catch (InvalidGraphRequest bad) {
            throw bad(bad.getMessage());
        } catch (GraphRunException refused) {
            throw map(refused);
        }
        emit(LinkEventTypes.LINK_GRAPH_RUN_STARTED, ex, v, input.nodes().size(), input.edges().size());

        int hiddenCount = hidden.size();
        int nodes = input.nodes().size(), edges = input.edges().size(), dropped = input.droppedDangling();
        if (!v.status().terminal() && nodes <= algorithm.inlineNodeCeiling()) {
            try {
                v = svc.await(v.id(), inlineWaitMs());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        Map<String, Object> out = view(v, mask, v.status() == Status.COMPLETED, true);
        Map<String, Object> inputNote = new LinkedHashMap<>();
        inputNote.put("nodes", nodes);
        inputNote.put("edges", edges);
        inputNote.put("hiddenEntities", hiddenCount);
        inputNote.put("droppedDangling", dropped);
        inputNote.put("at", rel.headStep());
        out.put("input", inputNote);
        if (v.status().terminal()) return out;                                                // 200
        ex.getResponseHeaders().set("Location", "/api/v1/inv/graph/runs/" + v.id());
        return ApiContext.respondJson(ex, 202, out);
    }

    private static Algorithm algorithm(Map<String, Object> body) {
        String id = ApiContext.str(body, "algorithm");
        if (id == null || id.isBlank()) throw bad("body must include 'algorithm' (GET /inv/graph/algorithms lists them)");
        try {
            return Algorithm.byId(id);
        } catch (InvalidGraphRequest e) {
            throw bad(e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> params(Map<String, Object> body) {
        Object raw = body.get("params");
        if (raw == null) return new LinkedHashMap<>();
        if (!(raw instanceof Map<?, ?>)) throw bad("'params' must be an object");
        return new LinkedHashMap<>((Map<String, Object>) raw);
    }

    private static String weights(Map<String, Object> body) {
        Object raw = body.get("weights");
        if (raw == null) return "count";
        if (!"count".equals(raw) && !"none".equals(raw)) throw bad("'weights' must be \"count\" or \"none\", got '" + raw + "'");
        return (String) raw;
    }

    private static List<String> kinds(Map<String, Object> body) {
        Object raw = body.get("kinds");
        if (raw == null) return null;
        if (!(raw instanceof List<?> l) || l.size() > MAX_KINDS || l.stream().anyMatch(o -> !(o instanceof String)))
            throw bad("'kinds' must be a list of at most " + MAX_KINDS + " link kinds (strings)");
        return l.stream().map(String::valueOf).toList();
    }

    @SuppressWarnings("unchecked")
    private static GraphBudget budget(Map<String, Object> body) {
        Object raw = body.get("budget");
        if (raw == null) return GraphBudget.UNSTATED;
        if (!(raw instanceof Map<?, ?> b)) throw bad("'budget' must be an object {maxNodes, maxEdges, timeoutMs}");
        for (Object k : b.keySet())
            if (!Set.of("maxNodes", "maxEdges", "timeoutMs").contains(String.valueOf(k)))
                throw bad("budget has no field '" + k + "' (it takes maxNodes, maxEdges, timeoutMs)");
        return new GraphBudget((int) positive((Map<String, Object>) b, "maxNodes", Integer.MAX_VALUE),
                (int) positive((Map<String, Object>) b, "maxEdges", Integer.MAX_VALUE),
                positive((Map<String, Object>) b, "timeoutMs", Long.MAX_VALUE));
    }

    private static long positive(Map<String, Object> b, String key, long max) {
        Object v = b.get(key);
        if (v == null) return 0;
        if (!(v instanceof Number n) || n.doubleValue() != Math.rint(n.doubleValue()) || n.longValue() < 1 || n.longValue() > max)
            throw bad("budget." + key + " must be a positive integer, got '" + v + "'");
        return n.longValue();
    }

    private static int at(Map<String, Object> body) {
        Object v = body.get("at");
        if (v == null) return -1;
        if (!(v instanceof Number n) || n.doubleValue() != Math.rint(n.doubleValue()) || n.intValue() < 0)
            throw bad("'at' must be a log step >= 0, got '" + v + "'");
        return n.intValue();
    }

    // ── reads ────────────────────────────────────────────────────────────────────────────────────────────────

    private Object get(ApiContext api, HttpExchange ex, String id) throws IOException {
        Path writeRoot = WriteGates.requireWriteRoot(api, "graph run");
        RunView v = visible(service(writeRoot), ex, id);
        InvestigationRoutes.Inv inv = InvestigationRoutes.openForRead(api, ex, v.investigationId());   // access is the Investigation's
        EntityMasking mask = EntityMasking.of(inv, List.of());
        return view(v, mask, v.status() == Status.COMPLETED, true);
    }

    private Object list(ApiContext api, HttpExchange ex) throws IOException {
        Path writeRoot = WriteGates.requireWriteRoot(api, "graph run");
        String invId = ApiContext.query(ex, "investigationId");
        if (invId != null && !invId.isBlank()) InvestigationRoutes.openForRead(api, ex, invId);        // 404 as a read of it would
        List<Object> items = new ArrayList<>();
        Map<String, Boolean> readable = new LinkedHashMap<>();
        for (RunView v : service(writeRoot).list(callerId(ex), invId)) {
            boolean ok = readable.computeIfAbsent(v.investigationId(), i -> {
                try {
                    InvestigationRoutes.openForRead(api, ex, i);
                    return true;
                } catch (ApiException|IOException lost) {
                    return false;                                       // no longer readable by this caller: absent, as a read is
                }
            });
            if (ok) items.add(view(v, null, false, false));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("runs", items);
        out.put("total", items.size());
        return out;
    }

    /** The run if it exists and this caller started it; otherwise the same 404 either way. */
    private RunView visible(GraphRunService svc, HttpExchange ex, String id) {
        RunView v;
        try {
            v = svc.get(id);
        } catch (GraphRunException e) {
            throw map(e);
        }
        Optional<Subject> subject = ApiContext.subject(ex);
        if (subject.isPresent() && !subject.get().id().equals(v.owner()))
            throw new ApiException(404, ErrorCodes.NOT_FOUND, "no graph run '" + id + "'");
        return v;
    }

    // ── POST /inv/graph/runs/{id}/cancel ─────────────────────────────────────────────────────────────────────

    private Object cancel(ApiContext api, HttpExchange ex, String id) throws IOException {
        Path writeRoot = WriteGates.requireWriteRoot(api, "graph run");
        Optional<Subject> subject = ApiContext.subject(ex);
        boolean admin = subject.isEmpty() || subject.get().capabilities().contains(Roles.CAN_ADMINISTER);
        RunView v;
        try {
            v = service(writeRoot).cancel(id, callerId(ex), admin);
        } catch (GraphRunException e) {
            throw map(e);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("runId", v.id());
        out.put("status", v.status().name());
        out.put("cancelRequested", v.cancelRequested());
        return ApiContext.respondJson(ex, 202, out);
    }

    // ── the wire shape of one run ────────────────────────────────────────────────────────────────────────────

    /**
     * {@code result} is added only when {@code withResult} AND the run is COMPLETED - a run that did not complete has no
     * result key whatever the caller passed. {@code mask} may be null only when no result is rendered.
     */
    private static Map<String, Object> view(RunView v, EntityMasking mask, boolean withResult, boolean withMasking) {
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("runId", v.id());
        o.put("status", v.status().name());
        o.put("investigationId", v.investigationId());
        o.put("algorithm", v.algorithm().id());
        o.put("engine", v.engine());
        o.put("budget", budget(v.budget()));
        o.put("budgetClamped", v.budgetClamped());
        Map<String, Object> consumed = new LinkedHashMap<>();
        consumed.put("nodes", v.consumed().nodes());
        consumed.put("edges", v.consumed().edges());
        consumed.put("elapsedMs", v.consumed().elapsedMs());
        consumed.put("work", v.consumed().work());
        o.put("consumed", consumed);
        Map<String, Object> progress = new LinkedHashMap<>();
        progress.put("work", v.progress().work());
        progress.put("fraction", v.progress().fraction());
        o.put("progress", progress);
        o.put("cancelRequested", v.cancelRequested());
        o.put("cached", v.cached());
        if (v.exceeded() != null) {
            o.put("exceeded", v.exceeded().name());
            o.put("reason", reason(v));
        }
        if (v.failure() != null) o.put("failure", v.failure());
        o.put("createdAt", v.createdAt());
        if (v.status().terminal()) o.put("finishedAt", v.finishedAt());
        if (withResult && v.status() == Status.COMPLETED && v.result() != null && mask != null) {
            o.put("result", GraphResultJson.of(v.result(), GraphResultJson.Ids.of(mask)));
            if (withMasking) {
                Map<String, Object> note = new LinkedHashMap<>(mask.describe());
                note.put("ordering", "ranked lists keep the order of the raw ids (computed before masking); tied entries with "
                        + "masked ids can therefore appear unsorted");
                o.put("masking", note);
            }
        }
        return o;
    }

    private static String reason(RunView v) {
        GraphBudget b = v.budget();
        return switch (v.exceeded()) {
            case NODES -> "the Working Set has " + v.consumed().nodes() + " nodes; the budget allows " + b.maxNodes()
                    + " (budget.maxNodes). Raise it (up to the server ceiling), filter the Working Set, or pick a cheaper algorithm.";
            case EDGES -> "the Working Set has " + v.consumed().edges() + " links; the budget allows " + b.maxEdges()
                    + " (budget.maxEdges). Raise it (up to the server ceiling), filter by kind, or pick a cheaper algorithm.";
            case TIMEOUT -> "the run did not finish within its " + b.timeoutMs() + " ms deadline (it ran " + v.consumed().elapsedMs()
                    + " ms). Raise budget.timeoutMs (up to the server ceiling) or pick a cheaper algorithm.";
            case WORK -> "the run exceeded the engine's work budget. Pick a cheaper algorithm or shrink the Working Set.";
        };
    }

    private static Map<String, Object> budget(GraphBudget b) {
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("maxNodes", b.maxNodes());
        o.put("maxEdges", b.maxEdges());
        o.put("timeoutMs", b.timeoutMs());
        return o;
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────────────────────────────

    private static ApiException bad(String message) {
        return new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, message);
    }

    private static ApiException map(GraphRunException e) {
        return switch (e.kind()) {
            case NOT_FOUND -> new ApiException(404, ErrorCodes.NOT_FOUND, e.getMessage());
            case FORBIDDEN -> new ApiException(403, ErrorCodes.PERMISSION_DENIED, e.getMessage());
            case TERMINAL -> new ApiException(409, ErrorCodes.CONFLICT, e.getMessage());
            case REJECTED -> new ApiException(503, ErrorCodes.STORE_BUSY, e.getMessage());
        };
    }

    /** The caller as the run table knows them: the Subject's id, else the request's actor (nothing is enforced without a Subject). */
    private static String callerId(HttpExchange ex) {
        return ApiContext.subject(ex).map(Subject::id).orElseGet(() -> ApiContext.actor(ex));
    }

    /**
     * What distinguishes one caller's row scope from another's, for the result cache's key: a hash of the Subject's data
     * scopes and attributes (the inputs the Enterprise PDP's row rule reads), {@code ""} with no Subject. Today a shared
     * Working Set does not vary with scope, so this is a tripwire (design §6.1 B), not a partition in use.
     */
    static String scopeFingerprint(HttpExchange ex) {
        Optional<Subject> subject = ApiContext.subject(ex);
        if (subject.isEmpty()) return "";
        Subject s = subject.get();
        String basis = (s.dataScopes() == null ? "null" : new TreeSet<>(s.dataScopes()).toString()) + '\u0000'
                + new TreeMap<>(s.attributes());
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(basis.getBytes(StandardCharsets.UTF_8)), 0, 8);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    // ── audit (best effort, the LA-04 pattern: an audit failure never fails the analyst's call) ──────────────

    /** STARTED, from the request thread. A fast run's terminal event can precede it by milliseconds - both carry the runId. */
    private static void emit(String type, HttpExchange ex, RunView v, int nodes, int edges) {
        try {
            Event.Builder b = base(type, v, ApiContext.actor(ex), ApiContext.actorType(ex)).attr("nodes", nodes).attr("edges", edges);
            EventLog.current().emit(b);
        } catch (RuntimeException ignored) {
            // best effort
        }
    }

    /** The service's terminal hook: the END of a run, from whichever thread ended it. */
    private static void auditTerminal(RunView v) {
        try {
            String type = switch (v.status()) {
                case COMPLETED -> LinkEventTypes.LINK_GRAPH_RUN_COMPLETED;
                case CANCELLED -> LinkEventTypes.LINK_GRAPH_RUN_CANCELLED;
                case BUDGET_EXCEEDED -> LinkEventTypes.LINK_GRAPH_RUN_BUDGET_EXCEEDED;
                case FAILED -> LinkEventTypes.LINK_GRAPH_RUN_FAILED;
                case QUEUED, RUNNING -> null;
            };
            if (type == null) return;
            Event.Builder b = base(type, v, v.owner(), "user").attr("nodes", v.consumed().nodes()).attr("edges", v.consumed().edges())
                    .attr("elapsedMs", v.consumed().elapsedMs()).attr("cached", v.cached());
            if (v.exceeded() != null) {
                b = b.attr("exceeded", v.exceeded().name()).attr("maxNodes", v.budget().maxNodes())
                        .attr("maxEdges", v.budget().maxEdges()).attr("timeoutMs", v.budget().timeoutMs());
            }
            if (v.failure() != null) b = b.attr("failure", v.failure());
            EventLog.current().emit(b);
        } catch (RuntimeException ignored) {
            // best effort
        }
    }

    /** Never the run's params: they can embed entity ids. */
    private static Event.Builder base(String type, RunView v, String actor, String actorType) {
        String action = "link.graph.run." + type.substring("LINK_GRAPH_RUN_".length()).toLowerCase(java.util.Locale.ROOT);
        return Event.builder(type).source("inv").message(action + " - " + v.investigationId())
                .actor(actor).actorType(actorType).action(action).actionCategory("analysis")
                .attr("investigationId", v.investigationId()).attr("algorithm", v.algorithm().id())
                .attr("key", v.relationKey()).attr("engine", v.engine()).attr("runId", v.id());
    }
}
