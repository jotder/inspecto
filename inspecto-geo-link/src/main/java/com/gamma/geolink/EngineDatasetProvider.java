package com.gamma.geolink;

import com.gamma.la.core.DatasetProvider;
import com.gamma.la.core.InputFingerprint;
import com.gamma.pipeline.ComponentRegistry;
import com.gamma.query.ConditionSql;
import com.gamma.query.DatasetRead;
import com.gamma.query.DatasetRelation;
import com.gamma.query.QueryExecutor;
import com.gamma.query.ResultSetDescriptor;
import com.gamma.sql.SqlSandboxPolicy;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The BRIDGE's implementation of Link Analysis's {@link DatasetProvider} port (LA separation D-1 step 5b/6): every method
 * delegates to the engine ({@code DatasetRead}, {@code QueryExecutor}, {@code ConditionSql}) and maps the engine's records to
 * the LA-owned ones field-for-field, so no engine type reaches {@code inspecto-la-core} / {@code inspecto-la-api}. Registered
 * through {@code META-INF/services/com.gamma.la.core.DatasetProvider}.
 */
public final class EngineDatasetProvider implements DatasetProvider {

    @Override
    public Optional<Map<String, Object>> dataset(Path writeRoot, String datasetId) {
        return DatasetRead.dataset(writeRoot, datasetId);
    }

    @Override
    public List<Entry> datasets(Path writeRoot) {
        return DatasetRead.datasets(writeRoot).stream().map(EngineDatasetProvider::entry).toList();
    }

    private static Entry entry(ComponentRegistry.Component c) {
        return new Entry(c.name(), c.content());
    }

    @Override
    public String relationSql(Map<String, Object> dataset, Path dataRoot, Path writeRoot) {
        return DatasetRead.relationSql(dataset, dataRoot, writeRoot);
    }

    @Override
    public InputFingerprint inputFingerprint(Map<String, Object> dataset, Path dataRoot, Path writeRoot) {
        // The engine's own resolution (DatasetRelation: store root, Consignment subtraction) - never paths scraped from SQL.
        Optional<DatasetRelation.InputFiles> in = DatasetRead.inputFiles(dataset, dataRoot, InputFingerprint.MAX_FILES);
        if (in.isEmpty()) return InputFingerprint.noFiles(relationSql(dataset, dataRoot, writeRoot));
        if (in.get().overLimit()) return InputFingerprint.tooMany(InputFingerprint.MAX_FILES);
        return InputFingerprint.ofFiles(in.get().files().stream()
                .map(f -> new InputFingerprint.FileStamp(f.path(), f.size(), f.mtimeMillis())).toList());
    }

    @Override
    public Result run(Request req) throws SQLException, IOException {
        return result(QueryExecutor.run(request(req)));
    }

    @Override
    public Result run(Request req, SqlSandboxPolicy policy) throws SQLException, IOException {
        return result(QueryExecutor.run(request(req), policy));
    }

    @Override
    public Result run(Request req, SqlSandboxPolicy policy, ZoneId timeZone) throws SQLException, IOException {
        return result(QueryExecutor.run(request(req), policy, timeZone));
    }

    @Override
    public Result runPlanned(String datasetName, String relationSql, SqlSandboxPolicy policy, Planner planner)
            throws SQLException, IOException {
        return result(QueryExecutor.runPlanned(datasetName, relationSql, policy, columns -> request(planner.plan(columns))));
    }

    @Override
    public String predicate(Object filter) {
        return ConditionSql.predicate(filter);
    }

    // ── record mapping ─────────────────────────────────────────────────────────────────────────────────

    static QueryExecutor.Request request(Request r) {
        return new QueryExecutor.Request(r.datasetName(), r.relationSql(), r.sql(), r.limit(), r.offset(), r.projection(),
                r.sort() == null ? List.of() : r.sort().stream().map(s -> new QueryExecutor.Sort(s.field(), s.descending())).toList(),
                r.binds());
    }

    static Result result(QueryExecutor.Result r) {
        return new Result(r.columns().stream().map(EngineDatasetProvider::column).toList(), r.rows(), r.rowCount(),
                r.truncated(), r.elapsedMs());
    }

    private static Column column(ResultSetDescriptor.Column c) {
        return new Column(c.name(), c.type(), c.role(), c.cardinality());
    }
}
