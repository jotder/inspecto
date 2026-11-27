package com.gamma.control;

import com.gamma.access.ComponentAccess;
import com.gamma.consignment.ConsignmentOutputStores;
import com.gamma.consignment.DbConsignmentOutputStore;
import com.gamma.enrich.ReferenceReader;
import com.gamma.etl.PipelineConfig;
import com.gamma.pipeline.BuiltinNodeType;
import com.gamma.pipeline.ComponentRegistry;
import com.gamma.pipeline.ComponentStore;
import com.gamma.pipeline.ViewStore;
import com.gamma.pipeline.PipelineGraph;
import com.gamma.pipeline.PipelineLift;
import com.gamma.pipeline.PipelineNode;
import com.gamma.pipeline.exec.PointLookup;
import com.gamma.pipeline.exec.RowShaper;
import com.gamma.pipeline.exec.StepReceipt;
import com.gamma.spi.auth.ApiException;
import com.gamma.spi.auth.ErrorCodes;
import com.gamma.spi.http.ApiContext;
import com.gamma.spi.http.RouteModule;
import com.sun.net.httpserver.HttpExchange;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code GET /lookups} — the Lookups screen's read (LOOKUP-1): every keyed {@code transform.join} in this Space's loaded
 * Pipelines (Pipeline, Step, Reference, match, the input columns it looks up), each with its last run read from the
 * Step Receipts: outcome, when, rows in, and the receipt's {@code diag.counts} ({@code lookup.<on>.hit|miss},
 * {@code reference.*}). Counts only, never a value; {@code diag.msg} is not served.
 *
 * <p>A non-keyed join (a plain {@code USING} join or the legacy prefix path) is not listed: it is a join, not a
 * lookup, and leaves no counts. A Pipeline that cannot be lifted is skipped. With no Consignment registry, or
 * receipts switched off, {@code lastRun} is absent and {@code known.lastRun=false}.
 *
 * <p>An open read under Space admission, like {@code GET /consignments/{id}}.
 */
final class LookupRoutes implements RouteModule {

    private static final Logger log = LoggerFactory.getLogger(LookupRoutes.class);

    /** Bound on the listed Steps; {@code total} reports the true count. */
    static final int MAX_LOOKUPS = 500;

    @Override
    public void register(ApiContext api) {
        api.get("/lookups", (e, m) -> list(api));
        // LOOKUP-TEST-OPEN-READ-1: it answers a Reference's own rows (and builds its index), so it is authoring, like
        // POST /enrichment/preview - never an open read
        api.post("/lookups/test", ApiContext.withCapability("canAuthorWorkbench", (e, m) -> test(api, e, api.body(e))));
        if (Boolean.getBoolean(PROP_PRELOAD)) {
            Thread t = new Thread(() -> preload(api), "lookup-preload");
            t.setDaemon(true);
            t.start();
        }
    }

    /** System property: build the keyed lookups' point indexes at start (up to {@link #MAX_WARM}), not on first test. */
    static final String PROP_PRELOAD = "lookup.point.preload";

    /** Lookups whose index is being built, and the last refusal of each that failed (key = pipeline/step). */
    private static final java.util.Set<String> BUILDING = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private static final Map<String, String> FAILED = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Build every keyed lookup's index at start, in Pipeline order, up to {@link #MAX_WARM} (more would only evict the
     * first). A refusal (over the cap, a duplicate key) is kept for {@code GET /lookups} and the next lookup goes on.
     */
    static void preload(ApiContext api) {
        int built = 0;
        for (PipelineConfig cfg : HostContext.of(api).service().loadedPipelines()) {
            PipelineGraph g;
            try {
                g = PipelineLift.stageTwo(cfg);
            } catch (RuntimeException noChain) {
                continue;
            }
            for (PipelineNode n : g.nodes()) {
                if (!BuiltinNodeType.TRANSFORM_JOIN.type().equals(n.type()) || !keyed(n)) continue;
                if (built++ >= MAX_WARM) return;
                try {
                    warmUp(api, cfg.identity().pipelineName(), n, false, () -> {});
                } catch (RuntimeException refused) {
                    log.warn("lookups: preloading {}/{} refused: {}", cfg.identity().pipelineName(), n.id(),
                            refused.getMessage());
                }
            }
        }
    }

    /** The index that answers, why this call built it ({@code null}: kept), and the Reference stamp it was built at. */
    private record Warmed(Warm warm, String reason, String stamp) {}

    /**
     * The kept index of {@code node}, built first when it is new, asked for, its Step changed or its Reference's files
     * changed. Tracks {@link #BUILDING} and {@link #FAILED} for the readiness {@code GET /lookups} reports.
     *
     * @throws ApiException 422 when the point engine refuses the join
     */
    private static Warmed warmUp(ApiContext api, String pipeline, PipelineNode node, boolean refresh,
                                 Runnable beforeBuild) {
        String key = pipeline + "/" + node.id();
        Map<String, Object> config = new LinkedHashMap<>(node.config());
        Warm warm;
        synchronized (WARM) { warm = WARM.get(key); }
        RowShaper.ReferenceResolver refs = PipelineGraphRoutes.dryRunReferences(api);
        // stamped BEFORE the build: a Reference committed while it builds differs from this stamp next time
        Object reference = node.cfg("reference");   // none: the build refuses it (422), so there is nothing to stamp
        String stamp = reference == null || reference.toString().isBlank() ? null
                : PointLookup.referenceStamp(reference.toString().trim(), refs);
        String reason = warm == null ? "first use"
                : refresh ? "reload asked"
                : !warm.config().equals(config) ? "step changed"
                : stamp != null && !stamp.equals(warm.stamp()) ? "reference changed"
                : null;
        if (reason != null) {
            beforeBuild.run();   // a 429 here leaves the kept index as it was
            BUILDING.add(key);
            try {
                warm = new Warm(config, PointLookup.load(node, refs), stamp);
                FAILED.remove(key);
            } catch (IllegalArgumentException refused) {
                FAILED.put(key, refused.getMessage());
                throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, refused.getMessage());
            } catch (RuntimeException e) {
                FAILED.put(key, String.valueOf(e.getMessage()));
                throw e;
            } catch (Exception e) {
                FAILED.put(key, String.valueOf(e.getMessage()));
                throw new IllegalStateException(e.getMessage(), e);
            } finally {
                BUILDING.remove(key);
            }
            // the old index answers until this put: the swap is one map write, never a half-built index
            synchronized (WARM) { WARM.put(key, warm); }
        }
        return new Warmed(warm, reason, stamp);
    }

    /** {@code GET /lookups}'s per-row index readiness: ready (with its size), building, failed (with why) or cold. */
    private static Map<String, Object> indexState(String pipeline, String step) {
        String key = pipeline + "/" + step;
        Warm warm;
        synchronized (WARM) { warm = WARM.get(key); }
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("state", BUILDING.contains(key) ? "building" : warm != null ? "ready" : FAILED.containsKey(key) ? "failed" : "cold");
        if (warm != null) {
            s.put("rows", warm.index().rows());
            s.put("bytes", warm.index().bytes());
            s.put("loadedAt", warm.index().loadedAt().toString());
        }
        if (FAILED.containsKey(key)) s.put("error", FAILED.get(key));
        return s;
    }

    /** Built point indexes kept warm, at most this many (least recently used dropped first). */
    static final int MAX_WARM = 8;

    private record Warm(Map<String, Object> config, PointLookup index, String stamp) {}

    private static final Map<String, Warm> WARM = new LinkedHashMap<>(16, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, Warm> eldest) { return size() > MAX_WARM; }
    };

    /** For tests: drop every warm index, and refill every caller's budgets. */
    static void clearWarm() {
        synchronized (WARM) { WARM.clear(); }
        BUILDING.clear();
        FAILED.clear();
        calls = RateLimiter.standard();
        reloads = reloadLimiter();
    }

    /** For tests: drop every warm index (as the 8-slot cache evicts), keeping every caller's budgets. */
    static void dropWarmForTest() {
        synchronized (WARM) { WARM.clear(); }
    }

    /** Fuzzy {@code candidates} served per answer, at most (D-AK16): the rest are dropped, never listed. */
    static final int MAX_CANDIDATES = 5;

    /** Every test, per caller: the budget of the other expensive reads ({@code /db/query}), so keys cannot be guessed at speed. */
    static volatile RateLimiter calls = RateLimiter.standard();
    /** A test that builds the index (asked, cold, evicted, changed), per caller: burst 3, then one every 30 s - up to 256 MiB each. */
    static volatile RateLimiter reloads = reloadLimiter();

    private static RateLimiter reloadLimiter() { return new RateLimiter(3.0, 1.0 / 30.0); }

    /** 429 with {@code Retry-After} when {@code caller}'s {@code bucket} is empty. */
    private static void charge(HttpExchange ex, RateLimiter bucket, String caller, String what) {
        if (bucket.tryConsume(caller)) return;
        ex.getResponseHeaders().set("Retry-After", Long.toString(bucket.retryAfterSeconds(caller)));
        throw new ApiException(429, ErrorCodes.RATE_LIMITED, "rate limit exceeded for " + what + " - retry later");
    }

    /**
     * {@code POST /lookups/test} {@code {pipeline, step, value, at?, refresh?}} — LOOKUP-1 P3 test-a-key: the keyed join's
     * answer for one value from the point engine ({@link PointLookup}), which the parity suite holds equal to the
     * batch join. The value travels in the body, never the URL, and is not logged. The index is built on first use and
     * kept warm; it is rebuilt when the Step's config changed, its Reference's files changed (the stamp of
     * {@link PointLookup#referenceStamp}; the new index replaces the old in one write), or {@code refresh: true} asks; a
     * Reference whose files the resolver does not declare is not watched ({@code index.watchesReference=false}). 404 for an unknown keyed Step, 422 for a join the point
     * engine refuses (a duplicate key or overlapping ranges, over the memory cap); {@code at} is the row time for {@code match: asof}.
     *
     * <p>LOOKUP-TEST-OPEN-READ-1: needs {@code canAuthorWorkbench}; 404 (as an unknown Step) when a Dataset over the
     * Reference's store is not viewable by the caller; the matched key, the mapped columns and at most
     * {@link #MAX_CANDIDATES} fuzzy candidates are masked as {@code /bi/query} masks them ({@link #masking}); 429 past
     * the per-caller {@link #calls} budget, or the {@link #reloads} budget for a call that builds the index.
     *
     * <p>AGENT-LOOKUP-PREVIEW-1: an agent data route ({@link AgentGate#LOOKUP_TEST}). An agent's answer is stamped (masked
     * by the Reference's classification, or raw by decision for a {@code path:} Reference, D-AK25, which a machine agent is
     * refused: 403); its per-route budgets are its own (agent client and subject), and a call that builds the index pays
     * {@link AgentGate#LOOKUP_BUILD_WEIGHT} of the agent budget in all.
     */
    private static Object test(ApiContext api, HttpExchange ex, Map<String, Object> body) throws Exception {
        com.gamma.spi.auth.Subject subject = ApiContext.subject(ex).orElse(null);
        // an agent spends its own per-route budgets, never its person's (D-AK17: keyed by agent client and subject)
        String agentKey = AgentGate.agentBudgetKey(subject);
        String ip = ApiContext.ip(ex);
        String caller = agentKey != null ? agentKey : subject != null ? subject.id() : ip == null ? "unknown" : ip;
        charge(ex, calls, caller, "/lookups/test");
        String pipeline = body == null || body.get("pipeline") == null ? null : body.get("pipeline").toString();
        String step = body == null || body.get("step") == null ? null : body.get("step").toString();
        if (pipeline == null || pipeline.isBlank() || step == null || step.isBlank())
            throw new ApiException(400, ErrorCodes.MALFORMED_REQUEST, "pipeline and step are required");
        Object value = body.get("value");
        Object at = body.get("at");
        Map<String, String> blockValues = new LinkedHashMap<>();
        if (body.get("blockValues") instanceof Map<?, ?> bm) {
            for (Map.Entry<?, ?> e : bm.entrySet()) {
                if (e.getKey() != null && e.getValue() != null && !e.getValue().toString().isBlank()) {
                    blockValues.put(e.getKey().toString(), e.getValue().toString());
                }
            }
        }
        PipelineNode node = null;
        List<PipelineConfig> loaded = HostContext.of(api).service().loadedPipelines();
        for (PipelineConfig cfg : loaded) {
            if (!cfg.identity().pipelineName().equals(pipeline)) continue;
            try {
                for (PipelineNode n : PipelineLift.stageTwo(cfg).nodes())
                    if (n.id().equals(step) && BuiltinNodeType.TRANSFORM_JOIN.type().equals(n.type()) && keyed(n)) node = n;
            } catch (RuntimeException noChain) {
                // no Stage-2 chain: no keyed join to test
            }
        }
        if (node == null)
            throw new ApiException(404, ErrorCodes.NOT_FOUND, "no keyed lookup '" + step + "' in pipeline '" + pipeline + "'");
        Masking mask = masking(api, ex, node, loaded);
        if (mask == null)   // a Dataset over the Reference is shared away from the caller: as absent as an unknown Step
            throw new ApiException(404, ErrorCodes.NOT_FOUND, "no keyed lookup '" + step + "' in pipeline '" + pipeline + "'");
        boolean agent = subject != null && subject.agent();
        if (agent && mask.path() && subject.machine())   // D-AK25: a path Reference answers raw, so never to a machine
            throw new ApiException(403, ErrorCodes.PERMISSION_DENIED, "a machine agent tests a lookup over a by-name "
                    + "Reference only: a path Reference has no classification, so its values would answer unmasked (D-AK25)");
        boolean refresh = Boolean.TRUE.equals(body.get("refresh"));
        // every build this call causes (asked, cold, evicted, changed) spends the build budget, not only refresh: true;
        // an agent's build also pays the rest of its weight 20 in the agent budget (design §3.4), before anything is read
        Warmed w = warmUp(api, pipeline, node, refresh, () -> {
            charge(ex, reloads, caller, "a lookup index build");
            if (agent) chargeAgentBuild(ex);
        });
        Warm warm = w.warm();
        String reason = w.reason(), stamp = w.stamp();
        String valStr = value == null ? null : value.toString();
        String atStr = at == null || at.toString().isBlank() ? null : at.toString();
        boolean isFuzzy = RowShaper.JOIN_MATCH_FUZZY.equals(warm.index().match());
        List<PointLookup.Answer> answers = isFuzzy
                ? warm.index().testTopK(valStr, blockValues)
                : List.of(warm.index().test(valStr, atStr));
        PointLookup.Answer a = answers.getFirst();
        List<Map<String, String>> sources = warm.index().answerSources();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("pipeline", pipeline);
        out.put("step", step);
        out.put("match", warm.index().match());
        out.put("compared", a.compared());
        out.put("hit", a.hit());
        out.put("matchedKey", mask.key(a.matchedKey()));
        out.put("matchKind", a.matchKind());
        if (a.matchScore() != null) out.put("matchScore", a.matchScore());
        out.put("mappings", toMappingList(a, sources, mask));
        if (isFuzzy) {
            List<Map<String, Object>> candidates = new ArrayList<>();
            for (PointLookup.Answer cand : answers) {
                if (!cand.hit()) continue;
                if (candidates.size() >= MAX_CANDIDATES) break;
                Map<String, Object> c = new LinkedHashMap<>();
                c.put("matchedKey", mask.key(cand.matchedKey()));
                c.put("matchKind", cand.matchKind());
                c.put("matchScore", cand.matchScore());
                c.put("mappings", toMappingList(cand, sources, mask));
                candidates.add(c);
            }
            out.put("candidates", candidates);
        }
        Map<String, Object> index = new LinkedHashMap<>();
        index.put("rows", warm.index().rows());
        index.put("bytes", warm.index().bytes());
        index.put("loadedAt", warm.index().loadedAt().toString());
        index.put("buildMs", warm.index().buildMillis());
        index.put("reloaded", reason);                 // why this answer built the index, or null when it was kept
        index.put("watchesReference", stamp != null);  // false: a Reference change is only seen on reload
        out.put("index", index);
        // AGENT-KIT-1 W6 (D-AK13): prove the masking for an agent's answer. A by-name Reference was masked by its
        // classification; a path Reference has none and answers raw by decision (D-AK25; the host accepts that stamp only
        // on AgentGate.RAW_FOR_BUILDERS). A by-name Reference read with no registry was classified by nothing: no stamp,
        // so an agent's answer is withheld (500). A no-op for a person.
        if (mask.basis() != null) com.gamma.util.AnswerMasking.applied(mask.basis(), mask.hiddenOf(sources));
        else if (mask.path()) com.gamma.util.AnswerMasking.unmaskedByDecision("lookup over a path Reference: raw for a builder (D-AK25)");
        return out;
    }

    /**
     * An agent's lookup test that builds its index costs {@link AgentGate#LOOKUP_BUILD_WEIGHT} in all: the host charged the
     * route's weight before the handler; the rest is spent here, before the build, through the host's charger
     * ({@link com.gamma.util.AgentBudget}) - a 429 past the budget. Fails closed when no charger is there.
     */
    private static void chargeAgentBuild(HttpExchange ex) {
        if (!(ApiContext.attr(ex, com.gamma.util.AgentBudget.ATTR) instanceof java.util.function.IntConsumer charge))
            throw new IllegalStateException("an agent's lookup index build reached no agent budget");
        charge.accept(AgentGate.LOOKUP_BUILD_WEIGHT - AgentGate.DATA_ROUTES.get(AgentGate.LOOKUP_TEST));
    }

    /**
     * {@code a}'s mappings as the caller may see them: on a hit, each column the Reference supplies (and the matched-key
     * column) through {@code mask}; on a miss they hold only the Step's configured defaults, which are not Reference data.
     */
    private static List<Map<String, Object>> toMappingList(PointLookup.Answer a, List<Map<String, String>> sources,
                                                           Masking mask) {
        List<Map<String, Object>> mappings = new ArrayList<>(a.mappings().size());
        for (int i = 0; i < a.mappings().size(); i++) {
            PointLookup.MappingAnswer m = a.mappings().get(i);
            Map<String, String> from = i < sources.size() ? sources.get(i) : Map.of();
            Map<String, Object> cols = new LinkedHashMap<>();
            for (Map.Entry<String, String> c : m.columns().entrySet()) {
                String src = from.get(c.getKey());
                cols.put(c.getKey(), !a.hit() || src == null ? c.getValue()
                        : PointLookup.MATCHED_KEY.equals(src) ? mask.key(c.getValue()) : mask.column(src, c.getValue()));
            }
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("on", m.on());
            r.put("columns", cols);
            mappings.add(r);
        }
        return mappings;
    }

    /**
     * How one caller sees a lookup's Reference values (LOOKUP-TEST-OPEN-READ-1): {@code bi} masks a by-name Reference as
     * {@code /bi/query} masks a Dataset over its store; {@code null} = raw. A {@code path:} Reference has no
     * classification (no Dataset or Pipeline schema describes the file), so nothing in it is masked: its readers can
     * already dry-run a Pipeline over the same file. {@code basis}: the by-name Reference a registry classified, else
     * {@code null}; {@code path}: the Reference is a {@code path:} one.
     */
    private record Masking(List<BiResultMasking> bis, List<String> keyColumns, String basis, boolean path) {
        /** How many of the key and answered Reference columns this caller sees masked (the stamp's count). */
        int hiddenOf(List<Map<String, String>> sources) {
            java.util.Set<String> cols = new java.util.LinkedHashSet<>(keyColumns);
            for (Map<String, String> s : sources)
                for (String c : s.values()) if (!PointLookup.MATCHED_KEY.equals(c)) cols.add(c);
            int n = 0;
            for (String c : cols) if (hiding(c) != null) n++;
            return n;
        }

        /** The first masking that hides {@code column}, or {@code null}: strictest wins across the Datasets on the store. */
        private BiResultMasking hiding(String column) {
            for (BiResultMasking bi : bis) if (bi.hidesFromCaller(column)) return bi;
            return null;
        }

        Object column(String column, Object value) {
            BiResultMasking bi = value == null ? null : hiding(column);
            return bi == null ? value : bi.cell(column, value);
        }

        /** The matched key: masked when a column it is read from ({@code key}, or {@code from} / {@code to}) is hidden. */
        Object key(Object value) {
            if (value == null) return null;
            for (String k : keyColumns) {   // range and asof keys join two columns: withheld, never one column's alias
                BiResultMasking bi = hiding(k);
                if (bi != null) return keyColumns.size() == 1 ? bi.cell(k, value) : BiResultMasking.UNLINKABLE;
            }
            return value;
        }
    }

    /**
     * The caller's {@link Masking} for keyed join {@code node}, or {@code null} when a registry Dataset over the by-name
     * Reference's store (its {@code physicalRef}, or {@code sourceName}, naming the producing Pipeline or its Stream) is
     * not viewable by the caller ({@link ComponentAccess#canView}). The classification is the one {@link BiResultMasking}
     * reads for a Dataset over that store: the Datasets on it and the producing Pipeline's schema; an untraceable lineage
     * masks every column (fail closed).
     */
    private static Masking masking(ApiContext api, HttpExchange ex, PipelineNode node, List<PipelineConfig> loaded) {
        List<String> keyColumns = new ArrayList<>();
        for (String k : List.of("key", "from", "to"))
            if (node.cfg(k) != null && !node.cfg(k).toString().isBlank()) keyColumns.add(node.cfg(k).toString().trim());
        String ref = null;
        boolean path = false;
        try {
            Object r = node.cfg("reference");
            com.gamma.enrich.EnrichmentConfig.Reference parsed = r == null ? null : ReferenceReader.parse(r.toString());
            if (parsed != null && parsed.byName()) ref = parsed.ref();
            else path = parsed != null;
        } catch (RuntimeException unparseable) {
            // the build refuses it (422) before anything is answered
        }
        Path writeRoot = api.writeRoot();
        if (ref == null || writeRoot == null) return new Masking(List.of(), keyColumns, null, path);   // no classification
        // the stores the Reference is: the producing Pipeline's name and its Stream (PipelineSchemas.producers' rule)
        java.util.Set<String> stores = new java.util.LinkedHashSet<>();
        stores.add(ref);
        for (PipelineConfig p : loaded)
            if (p.identity().pipelineName().equals(ref) && p.stream() != null) stores.add(p.stream());
        ComponentStore registry = new ComponentStore(writeRoot.resolve("registry"));
        ViewStore views = new ViewStore(writeRoot.resolve("views"));
        boolean reveal = BiResultMasking.mayReveal(ex);
        List<BiResultMasking> bis = new ArrayList<>();
        // each Dataset on the store masks as a read of it would (its own columns, whatever spelling of the store: a
        // store/table ref, another case, a sourceName), and the caller must be able to view every one
        for (ComponentRegistry.Component d : registry.list("dataset")) {
            if (!readsStore(d.content(), stores)) continue;
            if (!ComponentAccess.canView(ex, d.content())) return null;
            bis.add(BiResultMasking.of(writeRoot, registry, views, d.name(), d.content(), reveal));
        }
        // ...and a Dataset over both stores, so the lineage also reads the producing Pipeline's schema
        Map<String, Object> overStore = new LinkedHashMap<>();
        java.util.Iterator<String> it = stores.iterator();
        overStore.put("physicalRef", it.next());
        if (it.hasNext()) overStore.put("sourceName", it.next());
        bis.add(BiResultMasking.of(writeRoot, registry, views, "(lookup " + ref + ")", overStore, reveal));
        return new Masking(bis, keyColumns, "lookup " + ref, false);
    }

    /** {@code dataset} reads one of {@code stores} directly (its {@code physicalRef} or {@code sourceName}, by its head). */
    private static boolean readsStore(Map<String, Object> dataset, java.util.Set<String> stores) {
        for (String s : com.gamma.mask.EvidenceMasker.datasetStores(dataset)) {
            int slash = s.indexOf('/');
            String head = slash < 0 ? s : s.substring(0, slash);
            for (String store : stores) if (store.equalsIgnoreCase(head)) return true;
        }
        return false;
    }

    private static List<String> blockCols(PipelineNode n) {
        Object raw = n.cfg("block");
        List<String> out = new ArrayList<>();
        if (raw instanceof List<?> list) {
            for (Object o : list) if (o != null && !o.toString().isBlank()) out.add(o.toString());
        } else if (raw != null && !raw.toString().isBlank()) {
            out.add(raw.toString());
        }
        return out;
    }

    private static Object list(ApiContext api) {
        DbConsignmentOutputStore store = ConsignmentOutputStores.shared();
        boolean receipts = store != null && ConsignmentOutputStores.stepsEnabled();
        List<Map<String, Object>> rows = new ArrayList<>();
        int total = 0;
        for (PipelineConfig cfg : HostContext.of(api).service().loadedPipelines()) {
            // A keyed join runs only at rest (the ingest lane refuses it), in the Stage-2 chain the job runner lifts with
            // PipelineLift.stageTwo — whose name (`<pipeline>_stage2`) is the one its Step Receipts carry.
            PipelineGraph g;
            try {
                g = PipelineLift.stageTwo(cfg);
            } catch (RuntimeException noChain) {
                log.debug("lookups: no Stage-2 chain in {}: {}", cfg.identity().pipelineName(), noChain.getMessage());
                continue;
            }
            for (PipelineNode n : g.nodes()) {
                if (!BuiltinNodeType.TRANSFORM_JOIN.type().equals(n.type()) || !keyed(n)) continue;
                if (++total > MAX_LOOKUPS) continue;
                rows.add(row(cfg.identity().pipelineName(), g.name(), n, receipts ? store : null));
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("lookups", rows);
        out.put("total", total);
        out.put("known", Map.of("lastRun", receipts));
        return out;
    }

    /** The keyed form, as the run decides it: {@code key:}, or {@code from:}/{@code to:} for a range. */
    private static boolean keyed(PipelineNode n) {
        for (String k : List.of("key", "from", "to"))
            if (n.cfg(k) != null && !n.cfg(k).toString().isBlank()) return true;
        return false;
    }

    private static Map<String, Object> row(String pipeline, String receiptPipeline, PipelineNode n,
                                           DbConsignmentOutputStore store) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("pipeline", pipeline);
        r.put("step", n.id());
        r.put("reference", n.cfg("reference"));
        r.put("match", n.cfg("match") == null ? "exact" : n.cfg("match").toString());
        List<String> block = blockCols(n);
        if (!block.isEmpty()) r.put("block", block);
        r.put("lookedUpBy", lookedUpBy(n));
        r.put("index", indexState(pipeline, n.id()));
        if (store != null) {
            StepReceipt last = null;
            try {
                last = store.latestStep(receiptPipeline, n.id()).orElse(null);
            } catch (IllegalStateException unreadable) {
                // one failed read must not blank the whole list: this row shows no last run
                log.warn("lookups: last run of {}/{} unreadable: {}", receiptPipeline, n.id(), unreadable.getMessage());
            }
            r.put("lastRun", last == null ? null : lastRun(last));
            // The LAST run's unmatched-key sample (LOOKUP-1 §4.6): fingerprint, length, masked shape, count — never a
            // value. Read for that run's Consignment only, so a failed run never shows an older run's sample.
            List<Map<String, Object>> unmatched = new ArrayList<>();
            try {
                for (var e : last == null ? List.<com.gamma.pipeline.exec.LookupMissSample.Entry>of()
                        : store.lookupMisses(receiptPipeline, n.id(), last.consignmentId())) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("mapping", e.mapping());
                    m.put("fingerprint", e.fingerprint());
                    m.put("length", e.length());
                    m.put("shape", e.shape());
                    m.put("n", e.n());
                    unmatched.add(m);
                }
            } catch (IllegalStateException unreadable) {
                log.warn("lookups: unmatched sample of {}/{} unreadable: {}", receiptPipeline, n.id(), unreadable.getMessage());
            }
            r.put("unmatched", unmatched);
        }
        return r;
    }

    /** {@code lookups[].on} in order, else {@code on} (a scalar or a list). */
    private static List<String> lookedUpBy(PipelineNode n) {
        List<String> on = new ArrayList<>();
        if (n.cfg("lookups") instanceof List<?> list) {
            for (Object o : list)
                if (o instanceof Map<?, ?> m && m.get("on") != null && !on.contains(m.get("on").toString()))
                    on.add(m.get("on").toString());
        } else if (n.cfg("on") instanceof List<?> list) {
            for (Object o : list) if (o != null) on.add(o.toString());
        } else if (n.cfg("on") != null) {
            on.add(n.cfg("on").toString());
        }
        return on;
    }

    private static Map<String, Object> lastRun(StepReceipt s) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("consignmentId", s.consignmentId());
        r.put("outcome", s.outcome().name());
        r.put("startedAt", s.startedAt());
        r.put("rowsIn", s.in());
        r.put("counts", s.diag().counts());
        r.put("truncated", s.truncated());
        return r;
    }
}
