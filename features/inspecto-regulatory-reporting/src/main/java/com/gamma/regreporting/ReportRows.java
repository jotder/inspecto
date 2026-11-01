package com.gamma.regreporting;

import com.gamma.access.ComponentAccess;
import com.gamma.query.DatasetRead;
import com.gamma.query.QueryExecutor;
import com.gamma.spi.auth.ApiException;
import com.gamma.spi.auth.ErrorCodes;
import com.gamma.spi.http.ApiContext;
import com.gamma.util.SqlIdent;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The {@code rows} of a report context ({@link ReportTemplate.Rows}): the rows of the template's Dataset whose
 * {@code matchOn} column equals one of the keys the member Incidents carry, in a stable order. A Dataset the caller
 * cannot see reads as absent (404); one with no data yet is 409; one that cannot be bound, or a read over {@link #MAX_ROWS}, refuses (422): a
 * filing is never built from part of its rows.
 */
final class ReportRows {

    private ReportRows() {}

    /** The most rows one report may carry. */
    static final int MAX_ROWS = 1000;

    static List<Map<String, Object>> read(ApiContext api, HttpExchange ex, Path root, ReportTemplate.Rows spec,
                                          Collection<String> keys) {
        String id = spec.dataset();
        Map<String, Object> dataset = DatasetRead.dataset(root, id).orElse(null);
        if (dataset == null || !ComponentAccess.canView(ex, dataset))
            throw new ApiException(404, ErrorCodes.NOT_FOUND, "no dataset '" + id + "' (the template's rows source)");
        if (keys.isEmpty()) return List.of();
        String relationSql;
        try {
            relationSql = DatasetRead.relationSql(dataset, api.dataRoot(), root);
        } catch (IllegalArgumentException bad) {
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "rows dataset '" + id + "': " + bad.getMessage());
        }
        String sql = "SELECT * FROM " + SqlIdent.q(id) + " WHERE CAST(" + SqlIdent.q(spec.matchOn()) + " AS VARCHAR) IN ("
                + String.join(", ", java.util.Collections.nCopies(keys.size(), "?")) + ") ORDER BY ALL";
        QueryExecutor.Result r;
        try {
            r = QueryExecutor.run(new QueryExecutor.Request(id, relationSql, sql, MAX_ROWS, 0, List.of(), List.of(),
                    List.copyOf(keys)));
        } catch (SQLException e) {
            throw com.gamma.control.ServerFaults.datasetRead("rows dataset read failed", id, dataset, e, api);
        } catch (IOException e) {
            throw new ApiException(503, ErrorCodes.CAPABILITY_UNAVAILABLE, "query sandbox unavailable");
        }
        if (r.truncated())
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "rows dataset '" + id + "' holds more than "
                    + MAX_ROWS + " rows for this subject - a report is never filed from part of its rows");
        List<Map<String, Object>> out = new ArrayList<>(r.rows().size());
        for (Map<String, Object> row : r.rows()) {
            Map<String, Object> m = new LinkedHashMap<>();
            row.forEach((k, v) -> m.put(k, plain(v)));
            out.add(m);
        }
        return out;
    }

    /** A value as the renderer and the source fingerprint take it: text, a number or a boolean. */
    private static Object plain(Object v) {
        if (v == null || v instanceof String || v instanceof Boolean || v instanceof BigDecimal || v instanceof Long
                || v instanceof Integer) return v;
        return String.valueOf(v);
    }
}
