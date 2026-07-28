package com.gamma.control;

import com.gamma.config.spec.Finding;
import com.gamma.pipeline.ComponentRegistry;
import com.gamma.pipeline.ComponentStore;
import com.gamma.pipeline.ViewStore;
import com.gamma.query.DatasetRelation;
import com.gamma.query.Parameters;
import com.gamma.query.QueryExecutor;
import com.gamma.query.RuleTemplate;
import com.gamma.sql.SqlGuard;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Rule Template execution ({@code POST /rule-templates/{id}/simulate}). A {@code rule-template} component
 * is a saved query whose condition literals were replaced by named {@code :name} binds, so one stored
 * template answers many questions. Authoring/storage shipped 2026-07-27 through the generic
 * {@code /components/rule-template/{id}} CRUD; this module adds the execution half, which never existed —
 * the backend had no model of a rule template at all.
 *
 * <p><b>{@code :name} binds, not interpolation.</b> {@link RuleTemplate#compile} rewrites each declared
 * hole to a positional {@code ?} and hands the values to {@link QueryExecutor} as real
 * {@link java.sql.PreparedStatement} binds, so a supplied value can never change the statement's shape.
 * This is deliberately NOT the {@code $}-parameter path: {@link Parameters} resolves run-time <em>context</em>
 * tokens ({@code $day(-7)}) into SQL literals because they may appear anywhere in a statement, whereas a
 * template hole is one value in a known position — a bind. Both may appear in one template; {@code compile}
 * runs first (see {@link RuleTemplate}).
 *
 * <p>Fail-closed: write root unset → 503 · unknown template/dataset → 404 · an undeclared {@code :name}, a
 * template with no SQL, a bad {@code $}-parameter, or SQL that fails the {@link SqlGuard} allow-list or
 * DuckDB → 422. {@code simulate} is read-only and persists nothing, so it is gated on
 * {@code canAuthorWorkbench} (previewing what you are authoring), matching {@code DecisionRoutes}'
 * {@code /simulate}; there is no {@code /apply} sibling because a rule template has no consequences to
 * enact — running it *is* the whole operation.
 */
final class RuleRoutes implements RouteModule {

    private static final String TYPE = "rule-template";
    private static final int DEFAULT_LIMIT = 500;
    private static final int MAX_LIMIT = 10_000;

    @Override
    public void register(ApiContext api) {
        api.post("/rule-templates/([^/]+)/simulate", ApiContext.withCapability("canAuthorWorkbench",
                (e, m) -> simulate(api, e, ApiContext.name(m))));
    }

    private Object simulate(ApiContext api, HttpExchange ex, String id) throws IOException {
        Path writeRoot = api.writeRoot();
        if (writeRoot == null)
            throw new ApiException(503, "no write root configured — rule templates are unavailable");
        ComponentStore store = new ComponentStore(writeRoot.resolve("registry"));

        Map<String, Object> config = component(store, TYPE, id)
                .orElseThrow(() -> new ApiException(404, "unknown rule template '" + id + "'"));
        RuleTemplate template = RuleTemplate.from(config);

        Map<String, Object> body = api.body(ex);

        // 1. Bind the declared `:name` holes. An undeclared hole or a template with no SQL is a 422 the
        //    author can act on, not a 500.
        RuleTemplate.Compiled compiled;
        try {
            compiled = template.compile(callerValues(body));
        } catch (IllegalArgumentException | IllegalStateException bad) {
            throw new ApiException(422, bad.getMessage());
        }

        // 2. Resolve any run-time `$`-context tokens in the (already bind-rewritten) text.
        String resolved;
        try {
            resolved = Parameters.resolve(compiled.sql(), List.of(), Map.of(),
                    Parameters.Context.of(ApiContext.actor(ex), null));
        } catch (IllegalArgumentException bad) {
            throw new ApiException(422, bad.getMessage());
        }

        // 3. Safety-gate the SQL text. The binds are NOT part of it, so no bind value can influence this.
        List<Finding> findings = SqlGuard.check(resolved);
        if (!findings.isEmpty())
            return ApiContext.respondJson(ex, 422, Map.of(
                    "error", "rule template failed the SQL safety check", "findings", findings));

        // 4. Resolve the template's source dataset to a trusted relation registered as a view.
        String source = template.source();
        String relationSql = null;
        if (source != null) {
            Map<String, Object> dataset = component(store, "dataset", source)
                    .orElseThrow(() -> new ApiException(404,
                            "rule template '" + id + "' reads unknown dataset '" + source + "'"));
            try {
                relationSql = DatasetRelation.relationSql(dataset, api.dataRoot(),
                        new ViewStore(writeRoot.resolve("views")));
            } catch (IllegalArgumentException bad) {
                throw new ApiException(422, bad.getMessage());
            }
        }

        QueryExecutor.Result result;
        try {
            result = QueryExecutor.run(new QueryExecutor.Request(source, relationSql, resolved,
                    limit(body), 0, List.of(), List.of(), compiled.binds()));
        } catch (IllegalArgumentException bad) {
            throw new ApiException(422, bad.getMessage());
        } catch (SQLException sql) {
            throw new ApiException(422, "rule template failed: " + sql.getMessage());
        } catch (IOException io) {
            throw new ApiException(503, "query sandbox unavailable: " + io.getMessage());
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", id);
        out.put("matched", result.rowCount());
        out.put("truncated", result.truncated());
        out.put("elapsedMs", result.elapsedMs());
        out.put("columns", result.columns());
        out.put("rows", result.rows());
        // Echo what each hole was bound to. With values supplied per call, this is what makes a simulation
        // reviewable rather than opaque — the same reason the AI surface echoes its derived args.
        out.put("boundTo", boundTo(template, compiled));
        return out;
    }

    /** Each declared parameter's name paired with the value this run bound to it, in declaration order. */
    private static List<Map<String, Object>> boundTo(RuleTemplate template, RuleTemplate.Compiled compiled) {
        // `binds` is positional (a hole used twice binds twice), so report the declaration, not the
        // position — an operator reads "threshold = 250", not "?3 = 250".
        return template.params().stream()
                .map(p -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("name", p.name());
                    m.put("field", p.field());
                    m.put("value", compiled.binds().isEmpty() ? p.value() : boundValue(template, compiled, p));
                    return m;
                })
                .toList();
    }

    private static Object boundValue(RuleTemplate template, RuleTemplate.Compiled compiled,
                                     RuleTemplate.Param p) {
        // The first placeholder for this parameter; `params` order need not match SQL order.
        int i = 0;
        for (RuleTemplate.Param q : template.params()) {
            if (q.name() != null && q.name().equals(p.name())) break;
            i++;
        }
        return i < compiled.binds().size() ? compiled.binds().get(i) : p.value();
    }

    private static java.util.Optional<Map<String, Object>> component(ComponentStore store, String type, String id) {
        return store.list(type).stream()
                .map(ComponentRegistry.Component::content)
                .filter(c -> id.equals(String.valueOf(c.get("id"))) || id.equals(String.valueOf(c.get("name"))))
                .findFirst();
    }

    private static Map<String, String> callerValues(Map<String, Object> body) {
        Map<String, String> values = new LinkedHashMap<>();
        if (body != null && body.get("params") instanceof Map<?, ?> m)
            m.forEach((k, v) -> { if (k != null && v != null) values.put(String.valueOf(k), String.valueOf(v)); });
        return values;
    }

    private static int limit(Map<String, Object> body) {
        Object raw = body == null ? null : body.get("limit");
        if (!(raw instanceof Number n)) return DEFAULT_LIMIT;
        return Math.max(1, Math.min(MAX_LIMIT, n.intValue()));
    }
}
