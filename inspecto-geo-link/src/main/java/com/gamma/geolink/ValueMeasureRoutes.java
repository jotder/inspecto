package com.gamma.geolink;

import com.gamma.control.ApiContext;
import com.gamma.control.ApiException;
import com.gamma.control.ErrorCodes;
import com.gamma.control.RouteModule;
import com.gamma.control.WriteGates;
import com.gamma.event.Event;
import com.gamma.event.EventLog;
import com.gamma.event.EventType;
import com.gamma.util.DuckDbUtil;
import com.sun.net.httpserver.HttpExchange;

import java.nio.file.Path;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * {@code GET /inv/value-measures} (LA-18) — one named value Measure ({@link ValueMeasures}) over a WHOLE Dataset:
 * the entities breaching its visible thresholds in a {@code [from, to)} window.
 *
 * <p>Query {@code dataset, sourceCol, targetCol, linkKindCol?, name, valueCol, timeCol, from, to}, plus any of the
 * Measure's thresholds and its kind list ({@code cashOutKinds}/{@code benefitKinds}, comma-separated). Answer
 * {@code {measure, threshold, entities, count, truncated, rowsInWindow, unvalued, fences}} — {@code measure} is the
 * block with every default filled in, exactly what an Alert Rule bound with it would store.
 *
 * <p>{@code last} ({@code <N>h|<N>d}, exclusive with {@code from}/{@code to}) is a rolling window resolved at read
 * time in UTC; the answer's {@code window} states the {@code [from, to)} it resolved. {@code agentList}
 * ({@code cashOutConcentration} only) restricts the agents to an Entity List of Entity Type {@code agent}.
 *
 * <p>Gate order: write root 503 → query 422 → Dataset 404 (R3 via {@link InvRoutes#relationFor}) → agent list 404 /
 * retired 409 / not of Entity Type {@code agent} 422 → unknown column 422. ⛔ No {@code filter} is accepted — the Measure reads the Dataset, never an analyst's view (the §2.6 ≥ 5 000
 * trap). A GET: read-only, persists nothing, audited as {@code LINK_VALUE_MEASURED}.
 */
public final class ValueMeasureRoutes implements RouteModule {

    private static final Pattern SAFE_IDENT = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
    private static final List<String> ROLE_KEYS = List.of("dataset", "sourceCol", "targetCol", "linkKindCol");

    @Override
    public void register(ApiContext api) {
        api.get("/inv/value-measures", (e, m) -> measure(api, e));
    }

    private Object measure(ApiContext api, HttpExchange ex) {
        Path writeRoot = WriteGates.requireWriteRoot(api, "value measure");
        Map<String, Object> block = new LinkedHashMap<>();
        String raw = ex.getRequestURI().getRawQuery();
        if (raw != null)
            for (String kv : raw.split("&")) {
                int eq = kv.indexOf('=');
                String k = eq < 0 ? kv : kv.substring(0, eq);
                if (!k.isEmpty() && !ROLE_KEYS.contains(k)) block.put(k, ApiContext.query(ex, k));
            }
        String datasetId = ApiContext.query(ex, "dataset");
        if (datasetId == null || datasetId.isBlank())
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "query must include 'dataset'");
        Map<String, Object> roles = new LinkedHashMap<>();
        for (String k : List.of("sourceCol", "targetCol", "linkKindCol")) {
            String v = ApiContext.query(ex, k);
            if (v == null || v.isBlank()) {
                if (!k.equals("linkKindCol"))
                    throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "query must include '" + k + "'");
                continue;
            }
            if (!SAFE_IDENT.matcher(v).matches())
                throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "unsafe column identifier '" + v + "' for " + k);
            roles.put(k, v);
        }
        ValueMeasures.Spec spec;
        try {
            spec = ValueMeasures.parse(block, false);
        } catch (IllegalArgumentException bad) {
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, bad.getMessage());
        }
        String relationSql = InvRoutes.relationFor(api, ex, writeRoot, datasetId.trim());
        ValueMeasures.Result r;
        try {
            r = ValueMeasures.forInvestigation(datasetId.trim(), relationSql, roles, spec,
                    ValueMeasures.agents(writeRoot, spec));
        } catch (IllegalArgumentException bad) {
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, bad.getMessage());
        } catch (SQLException | java.io.IOException e) {
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "value measure failed: "
                    + DuckDbUtil.withoutPendingQueryPreamble(e.getMessage()));
        }
        Map<String, Object> out = answer(spec, r);
        out.put("fences", Map.of("maxEntities", ValueMeasures.MAX_ENTITIES, "timeoutSeconds",
                ValueMeasures.TIMEOUT_SECONDS, "maxWindowDays", ValueMeasures.MAX_WINDOW_DAYS));
        try {
            EventLog.current().emit(Event.builder(EventType.LINK_VALUE_MEASURED).source("inv")
                    .message("link.value.measured — " + spec.name() + " over " + datasetId)
                    .actor(ApiContext.actor(ex)).actorType(ApiContext.actorType(ex))
                    .action("link.value.measured").actionCategory("analysis")
                    .attr("dataset", datasetId.trim()).attr("measure", spec.name())
                    .attr("entities", r.entities().size()).attr("truncated", r.truncated()));
        } catch (RuntimeException ignored) {
            // best effort (LA-04) — the read already succeeded
        }
        return out;
    }

    /** The answer both this route and the Alert Rule binding give: the block, its thresholds in words, the entities. */
    static Map<String, Object> answer(ValueMeasures.Spec spec, ValueMeasures.Result r) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("measure", spec.toMap());
        out.put("threshold", ValueMeasures.label(spec));
        // the window THIS evaluation read — for a rolling `last`, resolved now against the server clock
        out.put("window", Map.of("from", spec.from(), "to", spec.to(), "timezone", "UTC"));
        out.put("entities", r.entities());
        out.put("count", r.entities().size());
        out.put("truncated", r.truncated());
        out.put("rowsInWindow", r.rowsInWindow());
        out.put("unvalued", r.unvalued());
        return out;
    }
}
