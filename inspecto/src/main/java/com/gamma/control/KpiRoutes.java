package com.gamma.control;

import com.gamma.pipeline.ComponentRegistry;
import com.gamma.pipeline.ComponentStore;
import com.gamma.pipeline.ViewStore;
import com.gamma.query.DatasetMeasureProbe;
import com.gamma.query.DatasetRelation;
import com.gamma.query.KpiDefinition;
import com.gamma.query.KpiEvaluator;
import com.gamma.util.DuckDbUtil;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * KPI definitions (`ASSURE-KPI-DEFINITIONS-1`, WS-20). A KPI is a {@code kpi} registry component — authored,
 * versioned and restored through the generic {@code /components/kpi} routes ({@link ComponentRoutes}), which call
 * {@link #requireMeasure} at save — and evaluated here:
 *
 * <ul>
 *   <li>{@code GET /kpis/{id}/value?asOf=YYYY-MM-DD} — the Measure over the current period to date and over the
 *       comparison period, the delta and Δ%, and the RAG band with its status tone ({@link KpiEvaluator}).</li>
 * </ul>
 *
 * <p>A read, so ungated like {@code POST /bi/query}. Fail closed: write root unset → 503; an unsafe id → 400; an
 * unknown or shared-away KPI → 404; a stored KPI that no longer validates → 422; a bad {@code asOf} → 400; a Dataset
 * that is absent OR that the caller may not read → the SAME 404 (indistinguishable from absence — a KPI never
 * reads a Dataset its caller could not read directly); a relation or DuckDB failure → 422.
 */
final class KpiRoutes implements RouteModule {

    static final String TYPE = "kpi";

    @Override
    public void register(ApiContext api) {
        api.get("/kpis/([^/]+)/value", (e, m) -> value(api, e, ApiContext.name(m)));
    }

    private Object value(ApiContext api, HttpExchange ex, String id) {
        Path writeRoot = WriteGates.requireWriteRoot(api, "KPI evaluation");
        ComponentStore store = new ComponentStore(writeRoot.resolve("registry"));
        Map<String, Object> content;
        try {
            content = store.get(TYPE, id).map(ComponentRegistry.Component::content)
                    .orElseThrow(() -> new ApiException(404, ErrorCodes.NOT_FOUND, "no kpi '" + id + "'"));
        } catch (IllegalArgumentException bad) {
            throw new ApiException(400, ErrorCodes.MALFORMED_REQUEST, bad.getMessage());
        }
        ComponentAccess.requireView(ex, TYPE, id, content);   // R3: shared-away ⇒ indistinguishable 404
        KpiDefinition kpi;
        try {
            kpi = KpiDefinition.fromMap(id, content);
        } catch (IllegalArgumentException bad) {
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "kpi '" + id + "' is invalid: " + bad.getMessage());
        }
        LocalDate asOf = asOf(ApiContext.query(ex, "asOf"));
        Map<String, Object> dataset = readableDataset(ex, store, kpi.dataset());
        if (dataset == null)
            throw new ApiException(404, ErrorCodes.NOT_FOUND, "kpi '" + id + "': no dataset '" + kpi.dataset() + "'");
        KpiEvaluator.Result r;
        try {
            String relationSql = DatasetRelation.relationSql(dataset, api.dataRoot(), new ViewStore(writeRoot.resolve("views")));
            r = KpiEvaluator.evaluate(kpi, relationSql, asOf);
        } catch (IllegalArgumentException bad) {
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, bad.getMessage());
        } catch (SQLException e) {
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED,
                    "kpi evaluation failed: " + DuckDbUtil.withoutPendingQueryPreamble(e.getMessage()));
        } catch (IOException e) {
            throw new ApiException(503, ErrorCodes.CAPABILITY_UNAVAILABLE, "query sandbox unavailable: " + e.getMessage());
        }
        return response(kpi, content, asOf, r);
    }

    private static Map<String, Object> response(KpiDefinition kpi, Map<String, Object> content, LocalDate asOf,
                                                KpiEvaluator.Result r) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("kpi", kpi.name());
        out.put("title", content.get("title"));
        out.put("dataset", kpi.dataset());
        out.put("measure", kpi.measureText());
        out.put("grain", kpi.grain().wire());
        out.put("comparison", kpi.comparison().wire());
        out.put("direction", kpi.direction().wire());
        out.put("asOf", asOf.toString());
        out.put("period", window(r.window()));
        out.put("value", r.value());
        out.put("comparisonPeriod", window(r.comparisonWindow()));
        out.put("comparisonValue", r.comparisonValue());
        out.put("delta", r.delta());
        out.put("deltaPct", r.deltaPct());
        out.put("target", kpi.target());
        out.put("bands", content.get("bands"));
        out.put("band", r.rag() == null ? null : r.rag().name());
        out.put("tone", r.tone());
        out.put("unit", content.get("unit"));
        out.put("format", content.get("format"));
        out.put("owner", content.get(ComponentAccess.OWNER));
        return out;
    }

    private static Map<String, Object> window(KpiDefinition.Window w) {
        if (w == null) return null;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("from", w.from().toString());
        m.put("to", w.to().toString());   // exclusive
        return m;
    }

    /** {@code YYYY-MM-DD}, or an ISO instant read as its UTC date; absent ⇒ today (UTC). 400 otherwise. */
    private static LocalDate asOf(String raw) {
        if (raw == null || raw.isBlank()) return LocalDate.now(ZoneOffset.UTC);
        try {
            return raw.length() == 10 ? LocalDate.parse(raw) : Instant.parse(raw).atZone(ZoneOffset.UTC).toLocalDate();
        } catch (DateTimeParseException bad) {
            throw new ApiException(400, ErrorCodes.MALFORMED_REQUEST, "asOf must be YYYY-MM-DD or an ISO instant, got '" + raw + "'");
        }
    }

    /**
     * The Dataset's content when it exists AND this caller may read it, else {@code null} — the two cases are one
     * answer on purpose, so a KPI can never be used to learn that a Dataset exists, let alone read it.
     */
    static Map<String, Object> readableDataset(HttpExchange ex, ComponentStore store, String datasetId) {
        Map<String, Object> dataset;
        try {
            dataset = store.get("dataset", datasetId).map(ComponentRegistry.Component::content).orElse(null);
        } catch (IllegalArgumentException unsafe) {
            return null;
        }
        return dataset != null && ComponentAccess.canView(ex, dataset) ? dataset : null;
    }

    /**
     * The save gate every {@code kpi} write runs (the {@code /components/kpi} door and the Requirement action): the
     * content validates ({@link KpiDefinition#fromMap}), and its Measure EXISTS for this author — the Dataset is
     * present and readable by them, and the measure field and {@code timeField} are columns of its Schema. Fail
     * closed: a Schema that cannot be read refuses the save, as an Alert Rule's {@code by} check does.
     *
     * @throws IllegalArgumentException naming what is wrong (→ 422 at the caller)
     */
    static KpiDefinition requireMeasure(ApiContext api, HttpExchange ex, String id, Map<String, Object> content) {
        KpiDefinition kpi = KpiDefinition.fromMap(id, content);
        Path writeRoot = WriteGates.requireWriteRoot(api, "kpi write");
        if (readableDataset(ex, new ComponentStore(writeRoot.resolve("registry")), kpi.dataset()) == null)
            throw new IllegalArgumentException("kpi dataset '" + kpi.dataset() + "' does not exist");
        List<String> columns = new DatasetMeasureProbe(() -> writeRoot, api::dataRoot).columns(kpi.dataset());
        String field = kpi.measure().field();
        if (field != null && !columns.contains(field))
            throw new IllegalArgumentException("kpi measure field '" + field + "' is not in the Schema of dataset '"
                    + kpi.dataset() + "' (have: " + columns + ")");
        if (!columns.contains(kpi.timeField()))
            throw new IllegalArgumentException("kpi timeField '" + kpi.timeField() + "' is not in the Schema of dataset '"
                    + kpi.dataset() + "' (have: " + columns + ")");
        return kpi;
    }
}
