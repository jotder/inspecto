package com.gamma.pipeline.exec;

import com.gamma.pipeline.PipelineNode;
import org.duckdb.DuckDBAppender;
import org.duckdb.DuckDBConnection;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Platform Services Stage 2, slice S2-2 — the <b>bridge spike</b> (measurement only; design
 * {@code docs/superpower/platform-services-stage2-design.md} §5.1). It prices what an {@code EXECUTED} Step
 * costs between map and the write, against the fused flat lane, over one generated fixture in one warm JVM:
 *
 * <ul>
 *   <li><b>V0</b> — nothing between the mapped relation and the write (the flat lane's fused path).</li>
 *   <li><b>V1</b> — one built-in {@code transform.sql} {@code SELECT * FROM input} shaped by the real
 *       {@link RowShaper}: the per-node materialisation every graph-lane node already pays.</li>
 *   <li><b>V2</b> — a no-op executed Step on the live {@link PipelineNodeExecutor} seam: every input row read
 *       through JDBC and written back through a {@link DuckDBAppender}. The JVM round trip — the bridge.</li>
 *   <li><b>S</b> (S2-3) — the same no-op as a real {@link StepExecutor} ({@code copyRow} from {@code in()} to
 *       {@code emit("data")}), run by {@link RowShaper#shape} through {@code StepRunner}: its own thread, the
 *       watchdog, the engine-owned statement and appender. It must cost what V2 costs — the seam adds no
 *       material overhead to the bridge — and the harness asserts it (S within 1.5× of V2's node time).</li>
 * </ul>
 *
 * <p>V3 (an Arrow stream) is not measured: the only Arrow Java in the offline repository is 0.8.0, which
 * predates the C Data interface {@code registerArrowStream} needs.
 *
 * <p>The write is the same statement for every variant ({@code COPY … TO … (FORMAT PARQUET)}), so the
 * variants differ only in what runs before it. The V2 executor is invoked directly — never registered — so
 * nothing here reaches a production registry. Gated on {@code -Dbench.run=true} like the other benchmarks
 * in this tree, so the default suite skips it. Run:
 * <pre>
 *   mvn -o -pl inspecto-engine -am test -Dtest=BridgeSpikeBenchmark -Dsurefire.failIfNoSpecifiedTests=false \
 *       -Dbench.run=true -Dbench.rows=2000000 -Dbench.cols=10,50 -Dbench.runs=5
 * </pre>
 */
class BridgeSpikeBenchmark {

    /** The no-op executed Step: read every row through JDBC, append every value back. Test scope only. */
    static final class NoOpBridgeExecutor implements PipelineNodeExecutor {
        @Override public String type() { return "bench.noop"; }

        @Override
        public List<RowShaper.Relation> shape(Connection conn, PipelineNode node, String input, String outPrefix,
                                              RowShaper.ReferenceResolver references) throws SQLException {
            String out = outPrefix + "data";
            exec(conn, "CREATE TABLE \"" + out + "\" AS SELECT * FROM \"" + input + "\" LIMIT 0");
            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery("SELECT * FROM \"" + input + "\"");
                 DuckDBAppender app = ((DuckDBConnection) conn).createAppender("", out)) {
                ResultSetMetaData md = rs.getMetaData();
                int n = md.getColumnCount();
                String[] types = new String[n];
                for (int i = 0; i < n; i++) types[i] = md.getColumnTypeName(i + 1);
                while (rs.next()) {
                    app.beginRow();
                    for (int i = 0; i < n; i++) appendValue(app, rs, i + 1, types[i]);
                    app.endRow();
                }
            }
            return List.of(new RowShaper.Relation("data", out));
        }

        private static void appendValue(DuckDBAppender app, ResultSet rs, int c, String type) throws SQLException {
            switch (type) {
                case "BIGINT" -> { long v = rs.getLong(c); if (rs.wasNull()) app.appendNull(); else app.append(v); }
                case "INTEGER" -> { int v = rs.getInt(c); if (rs.wasNull()) app.appendNull(); else app.append(v); }
                case "DOUBLE" -> { double v = rs.getDouble(c); if (rs.wasNull()) app.appendNull(); else app.append(v); }
                case "BOOLEAN" -> { boolean v = rs.getBoolean(c); if (rs.wasNull()) app.appendNull(); else app.append(v); }
                case "DATE" -> { LocalDate v = rs.getObject(c, LocalDate.class); if (v == null) app.appendNull(); else app.append(v); }
                case "TIMESTAMP" -> { LocalDateTime v = rs.getObject(c, LocalDateTime.class); if (v == null) app.appendNull(); else app.append(v); }
                default -> { String v = rs.getString(c); if (v == null) app.appendNull(); else app.append(v); }
            }
        }
    }

    /** Mixed-type column generators over {@code range(rows) t(i)}, cycled to the requested width. */
    private static final String[][] COLUMN_KINDS = {
            {"BIGINT", "i * %d"},
            {"VARCHAR", "'k' || (i %% 9973 + %d)"},
            {"DOUBLE", "(i %% 100003) * 0.5 + %d"},
            {"DATE", "DATE '2026-01-01' + CAST((i + %d) %% 365 AS INTEGER)"},
            {"TIMESTAMP", "TIMESTAMP '2026-01-01' + to_seconds(i + %d)"},
            {"INTEGER", "CAST((i + %d) %% 1000000 AS INTEGER)"},
            {"BOOLEAN", "(i + %d) %% 3 = 0"},
            {"VARCHAR", "CASE WHEN (i + %d) %% 17 = 0 THEN NULL ELSE 'v' || (i %% 131) END"},
    };

    record Timing(double nodeMs, double writeMs) { double totalMs() { return nodeMs + writeMs; } }

    /** S2-3's variant S: the V2 no-op, as a registered StepExecutor. */
    private static final String STEP_TYPE = "transform.bench_noop_step";
    private static final String STEP_OWNER = "bench-step.jar";
    private static final int VARIANTS = 4;

    @Test
    void measureTheBridge(@TempDir Path dir) throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean("bench.run"),
                "BridgeSpikeBenchmark skipped — pass -Dbench.run=true to run it");
        int rows = Integer.getInteger("bench.rows", 2_000_000);
        int runs = Integer.getInteger("bench.runs", 5);
        int[] widths = Arrays.stream(System.getProperty("bench.cols", "10,50").split(","))
                .mapToInt(s -> Integer.parseInt(s.trim())).toArray();

        com.gamma.pipeline.PipelineNodeTypes.register(new com.gamma.pipeline.PipelineNodeType() {
            @Override public String type() { return STEP_TYPE; }
            @Override public java.util.Optional<com.gamma.pipeline.ExecutionMode> mode() {
                return java.util.Optional.of(com.gamma.pipeline.ExecutionMode.EXECUTED);
            }
        }, STEP_OWNER);
        StepExecutors.register(new StepExecutor() {
            @Override public String type() { return STEP_TYPE; }
            @Override public void execute(StepContext ctx) throws Exception {
                StepInput in = ctx.in();
                StepOutput out = ctx.emit("data");
                while (in.next()) out.copyRow(in);
            }
        }, STEP_OWNER, StepExecutors.Grant.NONE);
        try (Connection conn = DriverManager.getConnection("jdbc:duckdb:")) {
            System.out.printf("%n=== BridgeSpikeBenchmark (S2-2): %,d rows, widths %s, 1 warm-up + %d runs ===%n",
                    rows, Arrays.toString(widths), runs);
            System.out.printf("host: %s %s, %d cores, JDK %s, max heap %d MB, DuckDB %s, threads=%s, memory_limit=%s%n",
                    System.getProperty("os.name"), System.getProperty("os.arch"),
                    Runtime.getRuntime().availableProcessors(), System.getProperty("java.version"),
                    Runtime.getRuntime().maxMemory() >> 20, one(conn, "SELECT version()"),
                    one(conn, "SELECT current_setting('threads')"), one(conn, "SELECT current_setting('memory_limit')"));

            for (int cols : widths) {
                generate(conn, rows, cols);
                String checksum = checksum(conn, "mapped");
                List<List<Timing>> results = new ArrayList<>();
                for (int v = 0; v < VARIANTS; v++) results.add(new ArrayList<>());
                for (int r = 0; r <= runs; r++) {           // r == 0 is the warm-up, not recorded
                    for (int v = 0; v < VARIANTS; v++) {
                        Timing t = runVariant(conn, v, dir, checksum, rows);
                        if (r > 0) results.get(v).add(t);
                    }
                }
                report(cols, rows, results);
                double v2 = median(results.get(2).stream().mapToDouble(Timing::nodeMs).toArray());
                double step = median(results.get(3).stream().mapToDouble(Timing::nodeMs).toArray());
                assertTrue(step <= 1.5 * v2, String.format(
                        "S2-3: the Step seam must cost what the raw bridge costs — S node %.0f ms vs V2 %.0f ms", step, v2));
                exec(conn, "DROP TABLE mapped");
            }
        } finally {
            StepExecutors.deregister(STEP_OWNER);
            com.gamma.pipeline.PipelineNodeTypes.deregister(STEP_OWNER);
        }
    }

    /** One variant: the between-node step (timed as node), then the shared write (timed as write). */
    private static Timing runVariant(Connection conn, int variant, Path dir, String checksum, int rows) throws Exception {
        String prefix = "v" + variant + "_";
        long t0 = System.nanoTime();
        String toWrite = switch (variant) {
            case 0 -> "mapped";
            case 1 -> RowShaper.shape(conn, PipelineNode.of("n", "transform.sql",
                    Map.of("sql", "SELECT * FROM input")), "mapped", prefix).getFirst().table();
            case 3 -> RowShaper.shape(conn, PipelineNode.of("n", STEP_TYPE), "mapped", prefix).getFirst().table();
            default -> new NoOpBridgeExecutor().shape(conn, PipelineNode.of("n", "bench.noop"), "mapped",
                    prefix, RowShaper.ReferenceResolver.NONE).getFirst().table();
        };
        long t1 = System.nanoTime();
        String file = dir.resolve(prefix + System.nanoTime() + ".parquet").toString().replace('\\', '/');
        exec(conn, "COPY (SELECT * FROM \"" + toWrite + "\") TO '" + file + "' (FORMAT PARQUET)");
        long t2 = System.nanoTime();
        // Conservation: every variant must hand the write the same rows, or its number means nothing.
        assertEquals(rows, Long.parseLong(one(conn, "SELECT count(*) FROM '" + file + "'")), "V" + variant + " rows");
        assertEquals(checksum, checksum(conn, "\"" + toWrite + "\""), "V" + variant + " checksum");
        if (variant != 0) exec(conn, "DROP TABLE \"" + toWrite + "\"");
        return new Timing((t1 - t0) / 1e6, (t2 - t1) / 1e6);
    }

    private static void report(int cols, int rows, List<List<Timing>> results) {
        System.out.printf("%n-- %d columns --%n", cols);
        System.out.printf("%-4s %12s %12s %12s %14s %14s %9s%n",
                "var", "node ms", "write ms", "total ms", "spread ms", "rows/s", "vs V0");
        double v0 = median(results.get(0).stream().mapToDouble(Timing::totalMs).toArray());
        for (int v = 0; v < VARIANTS; v++) {
            double[] total = results.get(v).stream().mapToDouble(Timing::totalMs).toArray();
            double med = median(total);
            System.out.printf("%-4s %12.0f %12.0f %12.0f %6.0f–%-7.0f %14.0f %8.2fx%n", v == 3 ? "S" : "V" + v,
                    median(results.get(v).stream().mapToDouble(Timing::nodeMs).toArray()),
                    median(results.get(v).stream().mapToDouble(Timing::writeMs).toArray()),
                    med, Arrays.stream(total).min().orElse(0), Arrays.stream(total).max().orElse(0),
                    rows / (med / 1000.0), med / v0);
        }
    }

    private static void generate(Connection conn, int rows, int cols) throws SQLException {
        StringBuilder sel = new StringBuilder();
        for (int c = 0; c < cols; c++) {
            String[] kind = COLUMN_KINDS[c % COLUMN_KINDS.length];
            if (c > 0) sel.append(", ");
            sel.append("CAST(").append(String.format(kind[1], c + 1)).append(" AS ").append(kind[0])
               .append(") AS c").append(c);
        }
        exec(conn, "CREATE TABLE mapped AS SELECT " + sel + " FROM range(" + rows + ") t(i)");
    }

    /** An order-independent content hash of a relation, so V1/V2 are proven to carry V0's exact rows. */
    private static String checksum(Connection conn, String rel) throws SQLException {
        return one(conn, "SELECT CAST(sum(hash(t)) AS VARCHAR) FROM " + rel + " t");
    }

    private static double median(double[] xs) {
        double[] s = xs.clone();
        Arrays.sort(s);
        return s.length % 2 == 1 ? s[s.length / 2] : (s[s.length / 2 - 1] + s[s.length / 2]) / 2;
    }

    private static String one(Connection conn, String sql) throws SQLException {
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getString(1);
        }
    }

    private static void exec(Connection conn, String sql) throws SQLException {
        try (Statement st = conn.createStatement()) { st.execute(sql); }
    }
}
