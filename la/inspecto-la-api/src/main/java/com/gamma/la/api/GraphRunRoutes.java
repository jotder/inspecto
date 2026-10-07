package com.gamma.la.api;

import com.gamma.spi.http.ApiContext;
import com.gamma.control.ApiException;
import com.gamma.control.ErrorCodes;
import com.gamma.entitystore.LinkAnalysisSettings;
import com.gamma.access.Roles;
import com.gamma.spi.http.RouteModule;
import com.gamma.control.Subject;
import com.gamma.access.WriteGates;
import com.gamma.audit.Event;
import com.gamma.audit.EventSink;
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
import com.gamma.la.storage.IndexReader;
import com.gamma.la.storage.RoutingGraphEngine;
import com.gamma.la.storage.SqlGraphEngine;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

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
 *   <li>{@code POST /inv/graph/runs/{id}/cancel} - the run's starter or an administrator, and only while the
 *       Investigation is still readable by the caller; anyone else gets the 404 of an unknown run.</li>
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
 * {@code graph_run} {@code threads}/{@code queue} (applied when the service is built), closed with the API ({@link
 * ApiContext#onClose}) - or earlier, when it has been unused for {@link GraphRunServices#IDLE_TTL_MS} or its Space is gone
 * ({@link GraphRunServices}). Terminal events of asynchronous runs are audited from the service's terminal hook.
 *
 * <p><b>Result size.</b> Each list of a result is cut to {@code graph_run.max_result_items} (default 10 000, ceiling 1 000 000)
 * on the way out, never in the cache; {@link GraphResultJson#of(com.gamma.la.core.GraphResult, GraphResultJson.Ids, int)}
 * says what it cut.
 */
public final class GraphRunRoutes implements RouteModule {

    /** How long a run within its algorithm's inline ceiling is waited for before the answer becomes a 202. */
    static final long INLINE_WAIT_MS = 3_000;
    private static final int MAX_KINDS = 100;
    private static final Set<String> BODY_KEYS = Set.of("investigationId", "at", "algorithm", "params", "weights", "kinds", "budget",
            "input", "dataset", "sourceCol", "targetCol", "linkKindCol", "seeds");
    /** The body fields only {@code input:"index"} takes (it reads a Dataset's edge index, not the Working Set). */
    private static final Set<String> INDEX_KEYS = Set.of("dataset", "sourceCol", "targetCol", "linkKindCol", "seeds");

    /** Test seam (the {@code Authenticators.forTest} idiom): an engine for services created AFTER this call, and the inline wait. */
    private static volatile GraphEngine engineOverride;
    private static volatile long inlineWaitOverrideMs = -1;

    /** @param engine null = the real {@link InMemoryGraphEngine}; @param inlineWaitMs negative = {@value #INLINE_WAIT_MS} */
    public static void forTest(GraphEngine engine, long inlineWaitMs) {
        engineOverride = engine;
        inlineWaitOverrideMs = inlineWaitMs;
    }

    private final GraphRunServices services = new GraphRunServices(GraphRunRoutes::newService, System::currentTimeMillis,
            GraphRunServices.IDLE_TTL_MS);

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
        services.close();
    }

    private GraphRunService service(Path writeRoot) {
        return services.get(writeRoot);
    }

    /** A Space's service, with that Space's {@code threads}/{@code queue} (read now; they apply for this service's lifetime). */
    private static GraphRunService newService(Path root) {
        GraphRunService.Limits std = GraphRunService.Limits.standard();
        LinkAnalysisSettings.GraphRun g = LinkAnalysisSettings.forRoot(root).effectiveGraphRun();
        GraphRunService.Limits limits = new GraphRunService.Limits(std.defaults(), std.ceilings(),
                g.threads() != null ? g.threads() : std.threads(), g.queue() != null ? g.queue() : std.queue(),
                std.runTtlMs(), std.maxRuns(), std.cacheTtlMs(), std.cacheEntries());
        GraphEngine engine = new RoutingGraphEngine(engineOverride != null ? engineOverride : new InMemoryGraphEngine(),
                new SqlGraphEngine(InvRoutes.traversalPolicy()));
        return new GraphRunService(engine, limits, System::currentTimeMillis, GraphRunRoutes::auditTerminal);
    }

    /** Items per result list in force: the Space's {@code max_result_items}, else the default, never above the hard ceiling. */
    private static int resultItemLimit(Path writeRoot) {
        Integer stated = LinkAnalysisSettings.forRoot(writeRoot).effectiveGraphRun().maxResultItems();
        return Math.min(stated != null ? stated : GraphResultJson.DEFAULT_MAX_RESULT_ITEMS, GraphResultJson.MAX_RESULT_ITEMS);
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
        String memoryId = (engineOverride != null ? engineOverride : new InMemoryGraphEngine()).engineId();
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
            o.put("engines", SqlGraphEngine.nativeAlgorithms().contains(a) ? List.of(memoryId, SqlGraphEngine.ENGINE_ID) : List.of(memoryId));
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
        Map<String, Object> items = new LinkedHashMap<>();                   // the response-size policy, stated like the budget
        items.put("limit", resultItemLimit(api.writeRoot()));
        items.put("default", GraphResultJson.DEFAULT_MAX_RESULT_ITEMS);
        items.put("ceiling", GraphResultJson.MAX_RESULT_ITEMS);
        items.put("clamped", g.maxResultItems() != null && g.maxResultItems() > GraphResultJson.MAX_RESULT_ITEMS);
        out.put("resultItems", items);
        return out;
    }

    /** The Space's default budget: each stated {@code graph_run} field, else the service's shipped default. */
    private static GraphBudget settingsDefaults(LinkAnalysisSettings.GraphRun g, GraphRunService.Limits std) {
        return new GraphBudget(g.maxNodes() != null ? g.maxNodes() : std.defaults().maxNodes(),
                g.maxEdges() != null ? g.maxEdges() : std.defaults().maxEdges(),
                g.timeoutMs() != null ? g.timeoutMs() : std.defaults().timeoutMs());
    }

    // ── POST /inv/graph/runs ─────────────────────────────────────────────────────────────────────────────────

    /** Stands in for a raw id sent while masking hides it: names no node (one per parameter, so from and to never coincide), so the engine answers as for any unknown id. */
    private static final String ABSENT_NODE = "\u0000absent";

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
        boolean indexed = indexInput(body);                                                  // 422 on an unknown input
        InvestigationRoutes.Inv inv = InvestigationRoutes.openForRead(api, ex, invId);        // 422 · 403 · 404 · PDP
        EntityMasking mask = EntityMasking.of(inv, List.of());
        for (String node : List.of(Algorithm.FROM, Algorithm.TO, Algorithm.NODE))            // a pseudonym the caller saw -> its entity
            if (params.get(node) instanceof String s)
                // a RAW id the masking hides is treated as a node that does not exist, so the answer cannot tell whether it is in the Working Set
                params.put(node, mask.hidesRaw(s) ? ABSENT_NODE + node : mask.resolve(List.of(s)).get(0));

        GraphInput input;
        String relationKey;
        int hiddenCount = 0;
        int headStep = -1;
        IndexPlan plan = null;
        if (indexed) {
            plan = planIndexed(api, ex, writeRoot, inv, mask, body, algorithm, params, kinds, at, asked);
            input = plan.ref();
            relationKey = "index:" + plan.ref().indexId();
        } else {
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
            GraphInput.Materialised graph = WorkingSetGraphInput.from(shownEntities, shownLinks, kinds);
            if ("none".equals(weights)) graph = new GraphInput.Materialised(graph.nodes(), graph.edges(), Map.of(), graph.droppedDangling());
            input = graph;
            relationKey = cacheScope(rel.key(), kinds);
            hiddenCount = hidden.size();
            headStep = rel.headStep();
        }

        GraphRunService svc = service(writeRoot);
        GraphRunService.Limits std = GraphRunService.Limits.standard();
        GraphBudget stated = settingsDefaults(LinkAnalysisSettings.forRoot(writeRoot).effectiveGraphRun(), std);
        GraphBudget budget = new GraphBudget(asked.maxNodes() > 0 ? asked.maxNodes() : stated.maxNodes(),
                asked.maxEdges() > 0 ? asked.maxEdges() : stated.maxEdges(),
                asked.timeoutMs() > 0 ? asked.timeoutMs() : stated.timeoutMs());
        String owner = callerId(ex);
        RunView v;
        try {
            v = svc.submit(new GraphRunService.Request(owner, invId, relationKey, scopeFingerprint(ex), algorithm, params,
                    weights, input, budget));
        } catch (InvalidGraphRequest bad) {
            throw bad(bad.getMessage());
        } catch (GraphRunException refused) {
            throw map(refused);
        }
        int nodes = input.estimateNodes(), edges = input.estimateEdges();
        int dropped = input instanceof GraphInput.Materialised m ? m.droppedDangling() : 0;      // an index edge has both endpoints by construction
        emit(LinkEventTypes.LINK_GRAPH_RUN_STARTED, ex, v, nodes, edges);

        if (!v.status().terminal() && nodes <= algorithm.inlineNodeCeiling()) {
            try {
                v = svc.await(v.id(), inlineWaitMs());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        Map<String, Object> out = view(v, mask, v.status() == Status.COMPLETED, true, resultItemLimit(writeRoot));
        Map<String, Object> inputNote = new LinkedHashMap<>();
        inputNote.put("nodes", nodes);
        inputNote.put("edges", edges);
        inputNote.put("hiddenEntities", hiddenCount);
        inputNote.put("droppedDangling", dropped);
        if (plan != null) {                                                                   // an index run: the figures are estimates until measured
            inputNote.put("kind", "index");
            inputNote.put("estimated", true);
            inputNote.put("version", plan.ref().version());
            out.put("source", plan.source());
        } else {
            inputNote.put("at", headStep);
        }
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

    /**
     * The Working Set key PLUS the link-kind filter: {@code kinds} shapes the graph the run sees, so it must separate cache
     * entries ({@code kinds:["sms"]} then {@code kinds:["voice"]} on one Working Set are different graphs).
     */
    private static String cacheScope(String relationKey, List<String> kinds) {
        if (kinds == null || kinds.isEmpty()) return relationKey;
        return relationKey + "|kinds=" + String.join(",", kinds.stream().sorted().distinct().toList());
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

    // ── input: "index" (D-3 step 7) ──────────────────────────────────────────────────────────────────────────

    /** {@code input}: {@code "workingSet"} (default, today's behaviour) or {@code "index"}; anything else is a 422. */
    private static boolean indexInput(Map<String, Object> body) {
        Object raw = body.get("input");
        if (raw == null || "workingSet".equals(raw)) {
            for (String k : INDEX_KEYS)
                if (body.containsKey(k)) throw bad("'" + k + "' is only for input \"index\" (this run reads the Working Set)");
            return false;
        }
        if ("index".equals(raw)) return true;
        throw bad("'input' must be \"workingSet\" or \"index\", got '" + raw + "'");
    }

    private record IndexPlan(GraphInput.IndexRef ref, Map<String, Object> source) { }

    /**
     * Everything an {@code input:"index"} run decides BEFORE the service sees it, each refusal a stated 422 - never a silent
     * reroute to the Working Set: the algorithm must be index-native, the walk within the cap, no time travel ({@code at}), no
     * hidden entities, an index that fits and passes the staleness gate. Then the seeds' degrees are read once for the pre-work size
     * estimate. The Dataset's view gate and the four-eyes bound run first, as on {@code /inv/projection/neighbors}.
     */
    private static IndexPlan planIndexed(ApiContext api, HttpExchange ex, Path writeRoot, InvestigationRoutes.Inv inv, EntityMasking mask,
                                         Map<String, Object> body, Algorithm algorithm, Map<String, Object> params, List<String> kinds,
                                         int at, GraphBudget asked) throws IOException {
        if (!SqlGraphEngine.nativeAlgorithms().contains(algorithm))
            throw bad("'" + algorithm.id() + "' cannot run from the index - only "
                    + SqlGraphEngine.nativeAlgorithms().stream().map(Algorithm::id).sorted().toList()
                    + " can; run it with input \"workingSet\"");
        if (at >= 0)
            throw bad("'at' cannot be combined with input \"index\": the index holds the Dataset as built, with no Working Set history "
                    + "(and no time-zone contract yet)");
        if (algorithm == Algorithm.NEIGHBORHOOD && params.get("hops") instanceof Number h && h.intValue() > SqlGraphEngine.MAX_HOPS)
            throw bad("hops " + h + " is over the index cap of " + SqlGraphEngine.MAX_HOPS + " hops; run it with input \"workingSet\"");
        String dataset = ApiContext.str(body, "dataset");
        if (dataset == null || dataset.isBlank()) throw bad("input \"index\" needs 'dataset' (the Dataset whose edge index to read)");
        String sourceCol = InvRoutes.ident(body, "sourceCol", true);
        String targetCol = InvRoutes.ident(body, "targetCol", true);
        String kindCol = InvRoutes.ident(body, "linkKindCol", false);
        if (kinds != null && !kinds.isEmpty() && kindCol == null) throw bad("'kinds' needs 'linkKindCol' on input \"index\"");

        List<String> seeds = new ArrayList<>();
        if (algorithm == Algorithm.DEGREE_CENTRALITY) {
            if (!(body.get("seeds") instanceof List<?> raw) || raw.isEmpty() || raw.stream().anyMatch(o -> !(o instanceof String s) || s.isBlank()))
                throw bad("degreeCentrality from the index scores the SEED nodes only: 'seeds' must be a non-empty list of node ids");
            if (raw.size() > SqlGraphEngine.FRONTIER_CAP)
                throw bad("'seeds' lists " + raw.size() + " nodes; the index cap is " + SqlGraphEngine.FRONTIER_CAP + " keys per level");
            for (Object o : raw) {
                String s = (String) o;
                seeds.add(mask.hidesRaw(s) ? ABSENT_NODE + "seed" : mask.resolve(List.of(s)).get(0));
            }
        } else {
            if (body.containsKey("seeds")) throw bad("'seeds' is only for degreeCentrality; " + algorithm.id() + " starts from params.node");
            if (!(params.get(Algorithm.NODE) instanceof String node) || node.isBlank()) throw bad("params.node is required");
            seeds.add(node);
        }

        // hidden entities: the Investigation hides some, and the index cannot know which - refuse rather than show what it hides
        for (Map<String, Object> r : WorkingSetRoutes.relation(inv, -1, new boolean[1]).tables().get("entities"))
            if (Boolean.TRUE.equals(r.get("hidden")))
                throw bad("the Investigation hides entities, which the index cannot honour; run it with input \"workingSet\"");

        String relationSql = InvRoutes.relationFor(api, ex, writeRoot, dataset);                      // 404 · 422
        GraphRunService.Limits std = GraphRunService.Limits.standard();
        long bound = asked.maxEdges() > 0 ? asked.maxEdges()
                : settingsDefaults(LinkAnalysisSettings.forRoot(writeRoot).effectiveGraphRun(), std).maxEdges();
        InvRoutes.refuseIfSensitive(writeRoot, "a graph run from the index (budget " + bound + ")", bound, SqlGraphEngine.FRONTIER_CAP);

        IndexedRead.Selection sel = IndexedRead.select(writeRoot, api.dataRoot(), relationSql, dataset, sourceCol, targetCol, (m, utc) -> {
            boolean indexHasKind = m.kindColumn() != null;
            if (indexHasKind != (kindCol != null) || (indexHasKind && !kindCol.equalsIgnoreCase(m.kindColumn())))
                return IndexedRead.Fitted.no(IndexedRead.Reason.column_not_indexed);
            return IndexedRead.Fitted.ok(null);
        });
        if (!sel.usable()) {
            IndexedRead.Outcome<Object> no = sel.flat();
            throw bad("the edge index cannot serve this run (" + no.reason().name() + (no.details() != null ? ": " + no.details() : "")
                    + "); input \"index\" never falls back to the Working Set - fix the cause or run it with input \"workingSet\"");
        }
        long nodes = 0, edges = 0;
        try (IndexReader reader = IndexReader.borrow(sel.dir(), sel.manifest(), InvRoutes.traversalPolicy())) {
            for (String seed : seeds) {
                if (seed.indexOf((char) 0) >= 0) continue;                                               // a masked raw id: names no node
                long d = reader.degree(seed);
                nodes += d + 1;
                edges += d;
            }
        } catch (SQLException | IOException | RuntimeException unreadable) {
            throw new ApiException(503, ErrorCodes.STORE_BUSY, "the edge index could not be read (" + unreadable.getClass().getSimpleName() + ")");
        }
        GraphInput.IndexRef ref = new GraphInput.IndexRef(sel.dir(), sel.version(), dataset + ":" + sel.manifest().mapping().hash(), seeds,
                kinds, (int) Math.min(Integer.MAX_VALUE, nodes), (int) Math.min(Integer.MAX_VALUE, edges));
        return new IndexPlan(ref, sel.<Boolean>served(Boolean.TRUE).source());
    }

    // ── reads ────────────────────────────────────────────────────────────────────────────────────────────────

    private Object get(ApiContext api, HttpExchange ex, String id) throws IOException {
        Path writeRoot = WriteGates.requireWriteRoot(api, "graph run");
        RunView v = visible(service(writeRoot), ex, id);
        InvestigationRoutes.Inv inv = InvestigationRoutes.openForRead(api, ex, v.investigationId());   // access is the Investigation's
        EntityMasking mask = EntityMasking.of(inv, List.of());
        return view(v, mask, v.status() == Status.COMPLETED, true, resultItemLimit(writeRoot));
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
            if (ok) items.add(view(v, null, false, false, 0));
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
            throw e.kind() == GraphRunException.Kind.NOT_FOUND ? absent(id) : map(e);
        }
        Optional<Subject> subject = ApiContext.subject(ex);
        if (subject.isPresent() && !subject.get().id().equals(v.owner())) throw absent(id);
        return v;
    }

    /** The one answer for a run that is unknown OR not this caller's: the same status AND message either way. */
    private static ApiException absent(String id) {
        return new ApiException(404, ErrorCodes.NOT_FOUND, "no graph run '" + id + "'");
    }

    // ── POST /inv/graph/runs/{id}/cancel ─────────────────────────────────────────────────────────────────────

    private Object cancel(ApiContext api, HttpExchange ex, String id) throws IOException {
        Path writeRoot = WriteGates.requireWriteRoot(api, "graph run");
        Optional<Subject> subject = ApiContext.subject(ex);
        boolean admin = subject.isEmpty() || subject.get().capabilities().contains(Roles.CAN_ADMINISTER);
        GraphRunService svc = service(writeRoot);
        RunView known;
        try {
            known = svc.get(id);
        } catch (GraphRunException e) {
            throw e.kind() == GraphRunException.Kind.NOT_FOUND ? absent(id) : map(e);
        }
        // a run another caller started, or one whose Investigation this caller can no longer read, is absent - the same 404 as an unknown id
        // (an administrator is not the Investigation's reader by right, and may stop any run)
        if (!admin) {
            if (!callerId(ex).equals(known.owner())) throw absent(id);
            try {
                InvestigationRoutes.openForRead(api, ex, known.investigationId());
            } catch (ApiException | IOException lost) {      // unreadable is absent, as list() already treats it
                throw absent(id);
            }
        }
        RunView v;
        try {
            v = svc.cancel(id, callerId(ex), admin);
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
    private static Map<String, Object> view(RunView v, EntityMasking mask, boolean withResult, boolean withMasking, int maxItems) {
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
        progress.put("known", v.progress().known());          // additive: false = this algorithm reports no fraction (0 = unknown)
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
            o.put("result", GraphResultJson.of(v.result(), GraphResultJson.Ids.of(mask), maxItems));
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
        boolean index = "index".equals(v.inputKind());
        if (index && (v.exceeded() == GraphRunService.Exceeded.NODES || v.exceeded() == GraphRunService.Exceeded.EDGES))
            return "the index read " + (v.exceeded() == GraphRunService.Exceeded.NODES
                    ? v.consumed().nodes() + " nodes; the budget allows " + b.maxNodes() + " (budget.maxNodes)"
                    : v.consumed().edges() + " links; the budget allows " + b.maxEdges() + " (budget.maxEdges)")
                    + " - a figure estimated from the seeds' degrees before the walk, or measured after it. Raise the budget (up to the "
                    + "server ceiling) or pick fewer, smaller seeds.";
        return switch (v.exceeded()) {
            case INDEX_CAP -> "the walk reached past the index's cap (" + v.exceededDetail() + "). The index never answers over its cap, "
                    + "and does not fall back to the Working Set: pick a less connected seed or run it with input \"workingSet\".";
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
            EventSink.current().emit(b);
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
            EventSink.current().emit(b);
        } catch (RuntimeException ignored) {
            // best effort
        }
    }

    /** Never the run's params: they can embed entity ids. */
    private static Event.Builder base(String type, RunView v, String actor, String actorType) {
        String action = "link.graph.run." + type.substring("LINK_GRAPH_RUN_".length()).toLowerCase(java.util.Locale.ROOT);
        Event.Builder b = Event.builder(type).source("inv").message(action + " - " + v.investigationId())
                .actor(actor).actorType(actorType).action(action).actionCategory("analysis")
                .attr("investigationId", v.investigationId()).attr("algorithm", v.algorithm().id())
                .attr("key", v.relationKey()).attr("engine", v.engine()).attr("runId", v.id())
                .attr("source", "index".equals(v.inputKind()) ? "index" : "workingSet");
        if ("index".equals(v.inputKind())) b = b.attr("indexVersion", v.indexVersion());
        return b;
    }
}
