package com.gamma.alert;

import com.gamma.job.JobConfig;
import com.gamma.pipeline.ComponentRegistry;
import com.gamma.pipeline.ComponentStore;
import com.gamma.pipeline.ViewStore;
import com.gamma.util.DuckDbUtil;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * `ASSURE-PACK-BUSINESS-ASSURANCE-1` (d): each business-assurance Job's {@code sink_dataset} is a Dataset the
 * template ships, backed by a zero-row seed snapshot, so enabling the Job needs no hand re-pointing and the
 * Dataset has a real Schema the moment the template is copied. Regenerate the seeds with env
 * {@code BA_SINK_REGENERATE=true}.
 */
class BusinessAssuranceSinkDatasetTest {

    private static final Path PACK = Path.of("..", "..", "spaces", "_templates", "business-assurance").toAbsolutePath().normalize();
    private static final Path CFG = PACK.resolve("config");

    /** Empty feeds typed like the stores the Jobs read (the same columns the pack's corpus views produce). */
    private static final List<String> FEEDS = List.of(
            "CREATE TABLE daily_revenue (ds DATE, revenue DOUBLE)",
            "CREATE TABLE margin_lines (ds DATE, product VARCHAR, channel VARCHAR, partner VARCHAR, units INTEGER,"
                    + " revenue DOUBLE, cost DOUBLE, margin DOUBLE)");

    private static final Map<String, String> JOB_TO_VIEW = Map.of(
            "ba_revenue_forecast", "ba_revenue_forecast", "ba_margin_erosion", "ba_margin_erosion");

    private static List<String> describe(Statement st, String relation) throws Exception {
        List<String> out = new ArrayList<>();
        try (ResultSet rs = st.executeQuery("DESCRIBE SELECT * FROM " + relation)) {
            while (rs.next()) out.add(rs.getString("column_name") + " " + rs.getString("column_type"));
        }
        return out;
    }

    @Test
    void everyJobSinkIsAShippedDatasetWithAZeroRowSeedOfTheJobsSchema() throws Exception {
        boolean regenerate = "true".equals(System.getenv("BA_SINK_REGENERATE"));
        ComponentStore store = new ComponentStore(CFG.resolve("registry"));
        DuckDbUtil.loadDriver();
        try (Connection c = DriverManager.getConnection("jdbc:duckdb:"); Statement st = c.createStatement()) {
            for (String ddl : FEEDS) st.execute(ddl);
            for (Map.Entry<String, String> e : JOB_TO_VIEW.entrySet()) {
                JobConfig job = JobConfig.load(CFG.resolve("jobs").resolve(e.getKey() + "_job.toon").toString());
                String sink = job.params().get("sink_dataset");
                String sql = job.params().get("sql");

                Map<String, Object> ds = store.get("dataset", sink).map(ComponentRegistry.Component::content)
                        .orElseThrow(() -> new AssertionError(e.getKey() + " sinks to '" + sink + "', which is not a shipped Dataset"));
                assertEquals(sink, ds.get("physicalRef"), sink + " Dataset must read the Job's sink directory");

                Path seed = PACK.resolve("data").resolve(sink).resolve("seed.parquet");
                String seedRel = "read_parquet('" + seed.toString().replace('\\', '/') + "')";
                if (regenerate) {
                    Files.createDirectories(seed.getParent());
                    st.execute("COPY (SELECT * FROM (" + sql + ") LIMIT 0) TO '" + seed.toString().replace('\\', '/')
                            + "' (FORMAT PARQUET)");
                }
                assertTrue(Files.exists(seed), "no seed snapshot at " + seed + " (BA_SINK_REGENERATE=true)");
                List<String> jobSchema = describe(st, "(" + sql + " LIMIT 0)");
                assertEquals(jobSchema, describe(st, seedRel), e.getKey() + ": seed Schema drifted from the Job SQL - regenerate");
                try (ResultSet rs = st.executeQuery("SELECT count(*) FROM " + seedRel)) {
                    rs.next();
                    assertEquals(0, rs.getLong(1), "a seed snapshot carries no rows");
                }
                // the Job writes the same columns the Alert Rules / dashboard read off the view-backed Dataset
                String viewSql = new ViewStore(CFG.resolve("views")).get(e.getValue()).orElseThrow().derivedSql();
                assertEquals(jobSchema, describe(st, "(" + viewSql + " LIMIT 0)"),
                        e.getKey() + ": Job schema differs from its view-backed Dataset");
            }
        }
    }
}
