package com.gamma.control;

import com.gamma.consignment.ConsignmentOutputStores;
import com.gamma.consignment.DbConsignmentOutputStore;
import com.gamma.etl.PipelineConfig;
import com.gamma.pipeline.BuiltinNodeType;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
        api.post("/lookups/test", (e, m) -> test(api, api.body(e)));
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
                    warmUp(api, cfg.identity().pipelineName(), n, false);
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
    private static Warmed warmUp(ApiContext api, String pipeline, PipelineNode node, boolean refresh) {
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

    /** For tests: drop every warm index. */
    static void clearWarm() {
        synchronized (WARM) { WARM.clear(); }
        BUILDING.clear();
        FAILED.clear();
    }

    /**
     * {@code POST /lookups/test} {@code {pipeline, step, value, at?, refresh?}} — LOOKUP-1 P3 test-a-key: the keyed join's
     * answer for one value from the point engine ({@link PointLookup}), which the parity suite holds equal to the
     * batch join. The value travels in the body, never the URL, and is not logged. The index is built on first use and
     * kept warm; it is rebuilt when the Step's config changed, its Reference's files changed (the stamp of
     * {@link PointLookup#referenceStamp}; the new index replaces the old in one write), or {@code refresh: true} asks; a
     * Reference whose files the resolver does not declare is not watched ({@code index.watchesReference=false}). 404 for an unknown keyed Step, 422 for a join the point
     * engine refuses (a duplicate key or overlapping ranges, over the memory cap); {@code at} is the row time for {@code match: asof}. An open read, like the dry run.
     */
    private static Object test(ApiContext api, Map<String, Object> body) throws Exception {
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
        for (PipelineConfig cfg : HostContext.of(api).service().loadedPipelines()) {
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
        Warmed w = warmUp(api, pipeline, node, Boolean.TRUE.equals(body.get("refresh")));
        Warm warm = w.warm();
        String reason = w.reason(), stamp = w.stamp();
        String valStr = value == null ? null : value.toString();
        String atStr = at == null || at.toString().isBlank() ? null : at.toString();
        boolean isFuzzy = RowShaper.JOIN_MATCH_FUZZY.equals(warm.index().match());
        List<PointLookup.Answer> answers = isFuzzy
                ? warm.index().testTopK(valStr, blockValues)
                : List.of(warm.index().test(valStr, atStr));
        PointLookup.Answer a = answers.getFirst();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("pipeline", pipeline);
        out.put("step", step);
        out.put("match", warm.index().match());
        out.put("compared", a.compared());
        out.put("hit", a.hit());
        out.put("matchedKey", a.matchedKey());
        out.put("matchKind", a.matchKind());
        if (a.matchScore() != null) out.put("matchScore", a.matchScore());
        out.put("mappings", toMappingList(a.mappings()));
        if (isFuzzy) {
            List<Map<String, Object>> candidates = new ArrayList<>();
            for (PointLookup.Answer cand : answers) {
                if (!cand.hit()) continue;
                Map<String, Object> c = new LinkedHashMap<>();
                c.put("matchedKey", cand.matchedKey());
                c.put("matchKind", cand.matchKind());
                c.put("matchScore", cand.matchScore());
                c.put("mappings", toMappingList(cand.mappings()));
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
        return out;
    }

    private static List<Map<String, Object>> toMappingList(List<PointLookup.MappingAnswer> answers) {
        List<Map<String, Object>> mappings = new ArrayList<>(answers.size());
        for (PointLookup.MappingAnswer m : answers) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("on", m.on());
            r.put("columns", m.columns());
            mappings.add(r);
        }
        return mappings;
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
