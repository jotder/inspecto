package com.gamma.control;

import com.gamma.analytics.catalogue.AnalyticsCatalogue;
import com.gamma.analytics.catalogue.AnalyticsFunction;
import com.gamma.spi.auth.ApiException;
import com.gamma.spi.auth.ErrorCodes;
import com.gamma.spi.http.ApiContext;
import com.gamma.spi.http.RouteModule;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The Analytics function catalogue over HTTP (ANALYTICS-CORE-1 C8).
 * <pre>
 *   GET /analytics/functions[?kernel=statistics|profiler|time_series|forecast|similarity|grouping|graph]
 *       {total, kernels[{id, label, count}], functions[{id, label, kernel, cost, result, forms[], step, summary, params[]}]}
 * </pre>
 * Read-only and ungated like {@code GET /inv/graph/algorithms}: it DESCRIBES what the kernel offers and runs nothing, and
 * carries no data. Functions come from the registered {@code AnalyticsFunctionProvider}s, so a kernel added later shows up
 * here with no edit to this class. An unknown {@code kernel} is a 422 naming the valid ones, never an empty list.
 */
final class AnalyticsCatalogueRoutes implements RouteModule {

    private static final AnalyticsCatalogue CATALOGUE = AnalyticsCatalogue.load();

    @Override
    public void register(ApiContext api) {
        api.get("/analytics/functions", (e, m) -> ETags.respond(e, shape(ApiContext.query(e, "kernel"))));
    }

    static Map<String, Object> shape(String kernelParam) {
        AnalyticsFunction.Kernel only = null;
        if (kernelParam != null && !kernelParam.isBlank()) {
            String want = kernelParam.trim().toUpperCase(Locale.ROOT);
            for (AnalyticsFunction.Kernel k : AnalyticsFunction.Kernel.values()) if (k.name().equals(want)) only = k;
            if (only == null)
                throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "unknown kernel '" + kernelParam
                        + "'; one of " + Arrays.stream(AnalyticsFunction.Kernel.values()).map(k -> k.name().toLowerCase(Locale.ROOT)).toList());
        }
        Map<AnalyticsFunction.Kernel, Integer> counts = new EnumMap<>(AnalyticsFunction.Kernel.class);
        List<Object> functions = new ArrayList<>();
        for (AnalyticsFunction f : CATALOGUE.functions()) {
            counts.merge(f.kernel(), 1, Integer::sum);
            if (only == null || f.kernel() == only) functions.add(toMap(f));
        }
        List<Object> kernels = new ArrayList<>();
        counts.forEach((k, n) -> kernels.add(Map.of("id", k.name().toLowerCase(Locale.ROOT), "label", k.label(), "count", n)));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("total", functions.size());
        out.put("kernels", kernels);
        out.put("functions", functions);
        return out;
    }

    private static Map<String, Object> toMap(AnalyticsFunction f) {
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("id", f.id());
        o.put("label", f.label());
        o.put("kernel", f.kernel().name().toLowerCase(Locale.ROOT));
        o.put("cost", f.cost().name().toLowerCase(Locale.ROOT));
        o.put("result", f.result().name().toLowerCase(Locale.ROOT));
        o.put("forms", f.forms().stream().map(x -> x.name().toLowerCase(Locale.ROOT)).sorted().toList());
        o.put("step", f.step());
        o.put("summary", f.summary());
        List<Object> params = new ArrayList<>();
        for (AnalyticsFunction.Param p : f.params()) {
            Map<String, Object> pm = new LinkedHashMap<>();
            pm.put("name", p.name());
            pm.put("type", p.type().name().toLowerCase(Locale.ROOT));
            pm.put("default", p.defaultValue());
            if (p.min() != null) pm.put("min", p.min());
            if (p.max() != null) pm.put("max", p.max());
            if (!p.choices().isEmpty()) pm.put("choices", p.choices());
            pm.put("description", p.description());
            params.add(pm);
        }
        o.put("params", params);
        return o;
    }
}
