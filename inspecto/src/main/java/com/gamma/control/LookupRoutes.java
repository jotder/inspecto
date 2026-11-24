package com.gamma.control;

import com.gamma.consignment.ConsignmentOutputStores;
import com.gamma.consignment.DbConsignmentOutputStore;
import com.gamma.etl.PipelineConfig;
import com.gamma.pipeline.BuiltinNodeType;
import com.gamma.pipeline.PipelineGraph;
import com.gamma.pipeline.PipelineLift;
import com.gamma.pipeline.PipelineNode;
import com.gamma.pipeline.exec.PointLookup;
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
    }

    /** Built point indexes kept warm, at most this many (least recently used dropped first). */
    static final int MAX_WARM = 8;

    private record Warm(Map<String, Object> config, PointLookup index) {}

    private static final Map<String, Warm> WARM = new LinkedHashMap<>(16, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, Warm> eldest) { return size() > MAX_WARM; }
    };

    /** For tests: drop every warm index. */
    static void clearWarm() { synchronized (WARM) { WARM.clear(); } }

    /**
     * {@code POST /lookups/test} {@code {pipeline, step, value, refresh?}} — LOOKUP-1 P3 test-a-key: the keyed join's
     * answer for one value from the point engine ({@link PointLookup}), which the parity suite holds equal to the
     * batch join. The value travels in the body, never the URL, and is not logged. The index is built on first use and
     * kept warm; it is rebuilt when the Step's config changed or {@code refresh: true} asks (a Reference that changed
     * since {@code index.loadedAt} is not seen until then). 404 for an unknown keyed Step, 422 for a join the point
     * engine refuses (range / as-of, a duplicate key, over the row cap). An open read, like the dry run.
     */
    private static Object test(ApiContext api, Map<String, Object> body) throws Exception {
        String pipeline = body == null || body.get("pipeline") == null ? null : body.get("pipeline").toString();
        String step = body == null || body.get("step") == null ? null : body.get("step").toString();
        if (pipeline == null || pipeline.isBlank() || step == null || step.isBlank())
            throw new ApiException(400, ErrorCodes.MALFORMED_REQUEST, "pipeline and step are required");
        Object value = body.get("value");
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
        String key = pipeline + "/" + step;
        Map<String, Object> config = new LinkedHashMap<>(node.config());
        Warm warm;
        synchronized (WARM) { warm = WARM.get(key); }
        if (warm == null || !warm.config().equals(config) || Boolean.TRUE.equals(body.get("refresh"))) {
            try {
                warm = new Warm(config, PointLookup.load(node, PipelineGraphRoutes.dryRunReferences(api)));
            } catch (IllegalArgumentException refused) {
                throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, refused.getMessage());
            }
            synchronized (WARM) { WARM.put(key, warm); }
        }
        PointLookup.Answer a = warm.index().test(value == null ? null : value.toString());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("pipeline", pipeline);
        out.put("step", step);
        out.put("match", warm.index().match());
        out.put("compared", a.compared());
        out.put("hit", a.hit());
        out.put("matchedKey", a.matchedKey());
        out.put("matchKind", a.matchKind());
        List<Map<String, Object>> mappings = new ArrayList<>();
        for (PointLookup.MappingAnswer m : a.mappings()) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("on", m.on());
            r.put("columns", m.columns());
            mappings.add(r);
        }
        out.put("mappings", mappings);
        out.put("index", Map.of("rows", warm.index().rows(), "loadedAt", warm.index().loadedAt().toString(),
                "buildMs", warm.index().buildMillis()));
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
        r.put("lookedUpBy", lookedUpBy(n));
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
