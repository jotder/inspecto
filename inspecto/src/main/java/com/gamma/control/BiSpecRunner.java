package com.gamma.control;

import com.gamma.access.ComponentAccess;
import com.gamma.access.WriteGates;
import com.gamma.config.spec.Finding;
import com.gamma.pipeline.ComponentRegistry;
import com.gamma.pipeline.ComponentStore;
import com.gamma.pipeline.ViewStore;
import com.gamma.query.DatasetRelation;
import com.gamma.query.MeasureCompiler;
import com.gamma.query.QueryExecutor;
import com.gamma.spi.auth.ApiException;
import com.gamma.spi.auth.ErrorCodes;
import com.gamma.spi.http.ApiContext;
import com.gamma.sql.SqlGuard;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Runs one {@link MeasureCompiler.Spec} over a Dataset AS THE CALLER, through the gates of {@code POST /bi/query}
 * (BI-INSIGHTS-1 P2): the dataset must exist and be visible (else 404), the compiled text passes {@link SqlGuard},
 * the statement runs in the same {@link QueryExecutor} sandbox, and {@link BiResultMasking} vets the spec and masks
 * the rows for a caller who cannot reveal (a classified grouped key comes back as its alias). The public seam an
 * add-on route ({@code inspecto-insights}) composes several statements from, so it never builds SQL of its own and
 * never sees a value the caller could not see through {@code /bi/query}.
 *
 * <p>Narrower than {@code /bi/query} on purpose: no bound saved query, no overlay, no comparison, no hidden order.
 */
public final class BiSpecRunner {

    private BiSpecRunner() {}

    /** The masked rows, whether the row cap cut them, and the masking summary ({@code null} when nothing is masked). */
    public record Run(List<Map<String, Object>> rows, boolean truncated, Map<String, Object> masking) {}

    /** Parses a {@code /bi/query}-shaped body ({@link IllegalArgumentException} → 422) with the Space's limits. */
    public static MeasureCompiler.Spec parse(ApiContext api, Map<String, Object> body) {
        Path writeRoot = WriteGates.requireWriteRoot(api, "BI query execution");
        try {
            return MeasureCompiler.parse(body, ListLimitSettings.effectiveRows(writeRoot), ListLimitSettings.MAX_ROWS);
        } catch (IllegalArgumentException bad) {
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, bad.getMessage());
        }
    }

    /**
     * Runs {@code spec} for a Job (no request): the same compile, {@link SqlGuard}, sandbox and masking as
     * {@link #run}, as a reader who may NOT reveal — a Measure over a masked column is refused and a classified grouped
     * key comes back as its alias, so nothing a Job stores outlives the reader's masking. Who may read the Dataset is
     * the Job's own check (its author, {@code PostgresPublishJobType.canRead}); {@code dataset} is that cleared content.
     * A refusal is an {@link IllegalArgumentException} or {@link SecurityException} carrying the reason.
     */
    public static Run runAsJob(Path writeRoot, Path dataRoot, String datasetId, Map<String, Object> dataset,
                               MeasureCompiler.Spec spec) throws IOException, SQLException {
        if (spec.compare() != null || MeasureCompiler.hiddenOrderColumns(spec).size() > 0)
            throw new IllegalArgumentException("BiSpecRunner takes no comparison and no hidden order");
        if (!datasetId.equals(spec.dataset())) throw new IllegalArgumentException("the spec reads another dataset");
        ComponentStore store = new ComponentStore(writeRoot.resolve("registry"));
        ViewStore views = new ViewStore(writeRoot.resolve("views"));
        String relationSql = DatasetRelation.relationSql(dataset, dataRoot, views);
        BiResultMasking masking = BiResultMasking.of(writeRoot, store, views, datasetId, dataset, false);
        spec = masking.stripCosmeticOrder(spec, null);
        MeasureCompiler.Compiled sql = MeasureCompiler.compile(spec.withLimit(spec.limit() + 1));
        if (!SqlGuard.check(sql.sql()).isEmpty())
            throw new IllegalArgumentException("compiled BI query failed the SQL safety check");
        try {
            masking.vet(spec, true, null);
        } catch (ApiException refused) {
            throw new SecurityException(refused.getMessage());
        }
        QueryExecutor.Request req = new QueryExecutor.Request(datasetId, relationSql, sql.sql(),
                spec.limit(), 0, List.of(), List.of(), sql.params());
        QueryExecutor.Result raw;
        try {
            raw = QueryExecutor.run(req);
        } catch (SQLException e) {
            // as run(): a database message can quote a stored value of a masked key, and a Job keeps its failure text
            throw new SQLException("the statement over dataset '" + datasetId + "' failed; the database's message is "
                    + "withheld because it can quote a masked value", e.getSQLState());
        }
        QueryExecutor.Result shown = masking.apply(raw, spec.groupBy(), null, null, grantable(spec));
        return new Run(shown.rows(), shown.truncated(), masking.describe());
    }

    /** Parses a {@code /bi/query}-shaped body for a Job, with the Space's row limits. */
    public static MeasureCompiler.Spec parseForJob(Path writeRoot, Map<String, Object> body) {
        return MeasureCompiler.parse(body, ListLimitSettings.effectiveRows(writeRoot), ListLimitSettings.MAX_ROWS);
    }

    /** The Space's effective time zone (what "today" and a bucket mean for a Job). */
    public static java.time.ZoneId zone(Path writeRoot) {
        return TimezoneSettings.forRoot(writeRoot).effective();
    }

    /** The grouped columns a masked alias may stand for: the group keys that are not grained dates or Measures. */
    private static Set<String> grantable(MeasureCompiler.Spec spec) {
        Set<String> grained = new HashSet<>();
        if (spec.grains() != null) for (String g : spec.grains().keySet()) grained.add(g.toLowerCase(Locale.ROOT));
        Set<String> grantable = new HashSet<>();
        for (String g : spec.groupBy()) grantable.add(g.toLowerCase(Locale.ROOT));
        grantable.removeAll(grained);
        for (MeasureCompiler.Measure m : spec.measures()) grantable.remove(m.id().toLowerCase(Locale.ROOT));
        for (MeasureCompiler.Ratio rt : spec.ratios()) grantable.remove(rt.id().toLowerCase(Locale.ROOT));
        return grantable;
    }

    /** Runs {@code spec} (its own limit; one row past it marks {@code truncated}). */
    public static Run run(ApiContext api, HttpExchange ex, MeasureCompiler.Spec spec) throws IOException {
        if (spec.compare() != null || MeasureCompiler.hiddenOrderColumns(spec).size() > 0)
            throw new IllegalArgumentException("BiSpecRunner takes no comparison and no hidden order");
        Path writeRoot = WriteGates.requireWriteRoot(api, "BI query execution");
        ComponentStore store = new ComponentStore(writeRoot.resolve("registry"));
        ViewStore views = new ViewStore(writeRoot.resolve("views"));
        String datasetId = spec.dataset();
        Map<String, Object> dataset = store.get("dataset", datasetId)
                .map(ComponentRegistry.Component::content)
                .orElseThrow(() -> new ApiException(404, ErrorCodes.NOT_FOUND, "no dataset '" + datasetId + "'"));
        if (!ComponentAccess.canView(ex, dataset))
            throw new ApiException(404, ErrorCodes.NOT_FOUND, "no dataset '" + datasetId + "'");
        String relationSql;
        try {
            relationSql = DatasetRelation.relationSql(dataset, api.dataRoot(), views);
        } catch (IllegalArgumentException bad) {
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, bad.getMessage());
        }
        BiResultMasking masking = BiResultMasking.of(writeRoot, store, views, datasetId, dataset, BiResultMasking.mayReveal(ex));
        // As /bi/query: a capped grouped spec is otherwise ordered by its group keys, so masked aliases would come back in
        // the raw keys' order (BI-MASKING-STRUCTURED-RESIDUALS-1 (2)); for a caller who cannot reveal, order by the Measure.
        spec = masking.stripCosmeticOrder(spec, null);
        MeasureCompiler.Compiled sql;
        try {
            sql = MeasureCompiler.compile(spec.withLimit(spec.limit() + 1));
        } catch (IllegalArgumentException bad) {
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, bad.getMessage());
        }
        List<Finding> findings = SqlGuard.check(sql.sql());
        if (!findings.isEmpty())
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "compiled BI query failed the SQL safety check");
        masking.vet(spec, true, null);
        QueryExecutor.Request req = new QueryExecutor.Request(datasetId, relationSql, sql.sql(),
                spec.limit(), 0, List.of(), List.of(), sql.params());
        try {
            QueryExecutor.Result raw = QueryExecutor.run(req);
            QueryExecutor.Result shown = masking.apply(raw, spec.groupBy(), null, null, grantable(spec));
            return new Run(shown.rows(), shown.truncated(), masking.describe());
        } catch (SQLException e) {
            throw masking.scrub(ServerFaults.datasetRead("BI query failed", datasetId, dataset, e, api));
        }
    }
}
