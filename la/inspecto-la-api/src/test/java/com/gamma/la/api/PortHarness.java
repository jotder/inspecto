package com.gamma.la.api;

import com.gamma.spi.http.ApiContext;
import com.gamma.control.testkit.FakeApiContext;
import com.gamma.la.core.DatasetProvider;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * A test harness that needs NO control plane and NO engine: the platform test kit's {@link FakeApiContext} that records the routes a module
 * registers, a stub {@link HttpExchange}, and a fake {@link DatasetProvider}. Link Analysis routes run through it against
 * the ports alone (LA separation D-1 step 5b/6).
 */
final class PortHarness {

    final Path root;
    final ApiContext api;
    private final FakeApiContext fake;

    PortHarness(Path root) {
        this.root = root;
        this.fake = new FakeApiContext(root);
        this.api = fake;
    }

    /** Call a registered route: {@code METHOD /concrete/path}, with a JSON-ish body map. */
    Object call(String method, String path, Map<String, Object> body) throws Exception {
        return fake.dispatch(method, path, body);
    }

    /** A fake Dataset registry: id -> columns. {@code run} answers the columns of the Dataset the request names. */
    static final class FakeDatasets implements DatasetProvider {
        final Map<String, List<String>> columnsByDataset;
        final List<String> ran = new ArrayList<>();

        FakeDatasets(Map<String, List<String>> columnsByDataset) {
            this.columnsByDataset = columnsByDataset;
        }

        @Override public Optional<Map<String, Object>> dataset(Path writeRoot, String id) {
            return columnsByDataset.containsKey(id) ? Optional.of(Map.of("id", id)) : Optional.empty();
        }

        @Override public List<Entry> datasets(Path writeRoot) {
            return columnsByDataset.keySet().stream().map(n -> new Entry(n, Map.of())).toList();
        }

        @Override public List<Path> readRoots(Map<String, Object> dataset, Path dataRoot) {
            return List.of(dataRoot);
        }

        @Override public String relationSql(Map<String, Object> dataset, Path dataRoot, Path writeRoot) {
            return "SELECT * FROM fake_" + dataset.get("id");
        }

        @Override public Result run(Request req) {
            ran.add(req.datasetName());
            List<Column> cols = columnsByDataset.getOrDefault(req.datasetName(), List.of()).stream()
                    .map(c -> new Column(c, "string", "dimension", null)).toList();
            return new Result(cols, List.of(), 0, false, 0);
        }

        @Override public Result run(Request req, com.gamma.sql.SqlSandboxPolicy policy) { return run(req); }

        @Override public Result run(Request req, com.gamma.sql.SqlSandboxPolicy policy, java.time.ZoneId tz) { return run(req); }

        @Override public Result runPlanned(String name, String relationSql, com.gamma.sql.SqlSandboxPolicy policy, Planner planner) {
            throw new UnsupportedOperationException("not needed by these tests");
        }

        @Override public String predicate(Object filter) { return "TRUE"; }
    }

    /** A request that has no network behind it (the platform test kit's, kept under this name for the tests). */
    static final class StubExchange extends FakeApiContext.StubExchange {
        StubExchange(String method, String path, Map<String, Object> body) {
            super(method, path, body);
        }
    }
}
