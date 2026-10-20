package com.gamma.job;

import com.gamma.util.DuckDbUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The zero-row schema seeds of the {@code partner-settlement} Space Template. Every Dataset a Job reads or writes ships a seed
 * with exactly the Schema the real thing has, so the template applies (the Alert Rule gate reads the Schema of
 * {@code settlement_findings}), the chain runs on a fresh Space, and a Dataset has a Schema before its first run. The feed seeds
 * are pinned to what the Pipelines really write; the six Job seeds to the Jobs' own SQL, run in chain order. Regenerate with
 * {@code PARTNER_SETTLEMENT_REGENERATE=true}.
 */
class PartnerSettlementSeedTest {

    private static final Path TPL = PartnerSettlementSpace.TEMPLATE;

    private static boolean regenerate() { return "true".equals(System.getenv("PARTNER_SETTLEMENT_REGENERATE")); }

    @BeforeEach
    void arm() { PartnerSettlementSpace.arm(); }

    @AfterEach
    void disarm() { PartnerSettlementSpace.disarm(); }

    /** {@code name, type} of every field the feed's shipped Schema declares, in order. */
    private static List<String[]> fields(String feed) throws Exception {
        List<String[]> out = new ArrayList<>();
        boolean in = false;
        for (String l : Files.readAllLines(TPL.resolve("config").resolve(feed).resolve(feed + "_schema.toon"))) {
            if (l.trim().startsWith("fields[") && l.contains("{name,")) { in = true; continue; }
            if (in && !l.startsWith("    ")) break;
            if (in) {
                String[] parts = l.trim().split(",(?=(?:[^\"]*\"[^\"]*\")*[^\"]*$)");
                out.add(new String[]{parts[0], parts[2].replace("\"", "")});
            }
        }
        return out;
    }

    private static String columns(List<String[]> f) {
        StringBuilder sb = new StringBuilder("{");
        for (String[] c : f) { if (sb.length() > 1) sb.append(", "); sb.append('\'').append(c[0]).append("': '").append(c[1]).append('\''); }
        return sb.append('}').toString();
    }

    private static String emptyCsv(List<String[]> f) throws Exception {
        Path p = Files.createTempFile("partner-settlement-empty", ".csv");
        p.toFile().deleteOnExit();
        StringBuilder h = new StringBuilder();
        for (String[] c : f) { if (!h.isEmpty()) h.append(','); h.append(c[0]); }
        Files.writeString(p, h + "\n");
        return p.toString().replace('\\', '/');
    }

    private static String sql(String f) { return f.replace('\\', '/'); }

    /** {@code $name} to a quoted literal, longest name first, as the Job framework substitutes. */
    private static String substitute(String sql, Map<String, String> params) {
        List<String> names = new ArrayList<>(params.keySet());
        names.sort((a, b) -> b.length() - a.length());
        for (String n : names) sql = sql.replace("$" + n, "'" + params.get(n).replace("'", "''") + "'");
        return sql;
    }

    private static List<String> describe(Statement st, String relation) throws Exception {
        List<String> out = new ArrayList<>();
        try (ResultSet rs = st.executeQuery("DESCRIBE SELECT * FROM " + relation)) {
            while (rs.next()) out.add(rs.getString("column_name") + " " + rs.getString("column_type"));
        }
        return out;
    }

    @Test
    void everyDatasetShipsAnEmptySeedWithTheSchemaOfTheRealThing(@TempDir Path root) throws Exception {
        DuckDbUtil.loadDriver();
        try (Connection c = DriverManager.getConnection("jdbc:duckdb:"); Statement st = c.createStatement()) {
            for (String feed : PartnerSettlementSpace.FEEDS) {
                Path seed = TPL.resolve("data").resolve(feed).resolve("database").resolve("schema-seed.parquet");
                List<String[]> f = fields(feed);
                if (regenerate()) {
                    Files.createDirectories(seed.getParent());
                    st.execute("COPY (SELECT * FROM read_csv('" + emptyCsv(f) + "', header = true, columns = " + columns(f) + ")) TO '"
                            + sql(seed.toString()) + "' (FORMAT PARQUET)");
                }
                assertTrue(Files.exists(seed), feed + " has no seed at " + seed + " - regenerate with env PARTNER_SETTLEMENT_REGENERATE=true");
                st.execute("CREATE OR REPLACE TABLE " + feed + " AS SELECT * FROM read_parquet('" + sql(seed.toString()) + "')");
                try (ResultSet rs = st.executeQuery("SELECT count(*) FROM " + feed)) {
                    rs.next();
                    assertEquals(0, rs.getLong(1), feed + ": a seed carries no rows");
                }
            }
            for (String job : PartnerSettlementSpace.CHAIN) {
                JobConfig cfg = JobConfig.load(TPL.resolve("config").resolve("jobs").resolve(job + "_job.toon").toString());
                Map<String, String> params = new HashMap<>(cfg.params());
                params.put("as_of", PartnerSettlementCorpus.AS_OF.toString());
                String q = substitute(params.get("sql"), params);
                String sink = params.get("sink_dataset");
                Path seed = TPL.resolve("data").resolve(sink).resolve("schema-seed.parquet");
                if (regenerate()) {
                    Files.createDirectories(seed.getParent());
                    st.execute("COPY (SELECT * FROM (" + q + ") LIMIT 0) TO '" + sql(seed.toString()) + "' (FORMAT PARQUET)");
                }
                assertTrue(Files.exists(seed), sink + " has no seed at " + seed + " - regenerate with env PARTNER_SETTLEMENT_REGENERATE=true");
                assertEquals(describe(st, "(SELECT * FROM (" + q + ") LIMIT 0)"), describe(st, "read_parquet('" + sql(seed.toString()) + "')"),
                        sink + ": the seed drifted from the Job's SQL - regenerate with env PARTNER_SETTLEMENT_REGENERATE=true");
                st.execute("CREATE OR REPLACE TABLE " + sink + " AS SELECT * FROM read_parquet('" + sql(seed.toString()) + "')");
                try (ResultSet rs = st.executeQuery("SELECT count(*) FROM " + sink)) {
                    rs.next();
                    assertEquals(0, rs.getLong(1), sink + ": a seed carries no rows");
                }
            }
        }
        // the feed seeds equal what the Pipelines really write
        PartnerSettlementSpace s = PartnerSettlementSpace.create(root);
        s.ingest(PartnerSettlementCorpus.generateWithPositions().files());
        try (Connection c = DriverManager.getConnection("jdbc:duckdb:"); Statement st = c.createStatement()) {
            for (String feed : PartnerSettlementSpace.FEEDS) {
                Path db = s.data().resolve(feed).resolve("database");
                List<String> files = new ArrayList<>();
                try (Stream<Path> w = Files.walk(db)) {
                    for (Path p : w.filter(x -> x.toString().endsWith(".parquet") && !x.getFileName().toString().equals("schema-seed.parquet")).toList())
                        files.add("'" + sql(p.toString()) + "'");
                }
                assertFalse(files.isEmpty(), feed + ": the Pipeline wrote no file");
                assertEquals(describe(st, "read_parquet([" + String.join(",", files) + "])"),
                        describe(st, "read_parquet('" + sql(db.resolve("schema-seed.parquet").toString()) + "')"),
                        feed + ": the seed's Schema is not the Pipeline's output Schema");
            }
        }
    }
}
