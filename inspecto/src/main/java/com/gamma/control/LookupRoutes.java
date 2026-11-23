package com.gamma.control;

import com.gamma.consignment.ConsignmentOutputStores;
import com.gamma.consignment.DbConsignmentOutputStore;
import com.gamma.etl.PipelineConfig;
import com.gamma.pipeline.BuiltinNodeType;
import com.gamma.pipeline.PipelineGraph;
import com.gamma.pipeline.PipelineLift;
import com.gamma.pipeline.PipelineNode;
import com.gamma.pipeline.exec.StepReceipt;
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
    }

    private static Object list(ApiContext api) {
        DbConsignmentOutputStore store = ConsignmentOutputStores.shared();
        boolean receipts = store != null && ConsignmentOutputStores.stepsEnabled();
        List<Map<String, Object>> rows = new ArrayList<>();
        int total = 0;
        for (PipelineConfig cfg : HostContext.of(api).service().loadedPipelines()) {
            PipelineGraph g;
            try {
                g = PipelineLift.lift(cfg);
            } catch (RuntimeException unliftable) {
                log.debug("lookups: skipping pipeline {}: {}", cfg.identity().pipelineName(), unliftable.getMessage());
                continue;
            }
            for (PipelineNode n : g.nodes()) {
                if (!BuiltinNodeType.TRANSFORM_JOIN.type().equals(n.type()) || !keyed(n)) continue;
                if (++total > MAX_LOOKUPS) continue;
                rows.add(row(g.name(), n, receipts ? store : null));
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

    private static Map<String, Object> row(String pipeline, PipelineNode n, DbConsignmentOutputStore store) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("pipeline", pipeline);
        r.put("step", n.id());
        r.put("reference", n.cfg("reference"));
        r.put("match", n.cfg("match") == null ? "exact" : n.cfg("match").toString());
        r.put("lookedUpBy", lookedUpBy(n));
        if (store != null) r.put("lastRun", store.latestStep(pipeline, n.id()).map(LookupRoutes::lastRun).orElse(null));
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
