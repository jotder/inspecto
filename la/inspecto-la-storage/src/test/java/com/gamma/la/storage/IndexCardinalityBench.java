package com.gamma.la.storage;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;

/**
 * LA-DAILY-INGEST-1 follow-up (scale): a FULL index build over a synthetic day at a given ENTITY CARDINALITY, to measure what
 * {@code DailyIndexScaleBench} left open (entities above 1.8 M). The corpus is {@code rows} edges whose endpoints are drawn
 * from {@code entities} distinct ids, skewed (the source is {@code entities * u^2}, the target uniform), written as one
 * Parquet file by DuckDB itself. Gated: {@code -Dinspecto.bench.dir=<dir>}; knobs {@code inspecto.bench.rows} (10000000),
 * {@code inspecto.bench.entities} (5000000), {@code inspecto.bench.buildMemory} (default = no limit). Prints {@code CARD ...}.
 */
@Tag("bench")
@EnabledIfSystemProperty(named = "inspecto.bench.dir", matches = ".+")
class IndexCardinalityBench {

    @Test
    void fullBuildAtEntityCardinality() throws Exception {
        Path dir = Path.of(System.getProperty("inspecto.bench.dir")).toAbsolutePath().resolve("card-" + System.nanoTime());
        long rows = Long.getLong("inspecto.bench.rows", 10_000_000L), entities = Long.getLong("inspecto.bench.entities", 5_000_000L);
        String mem = System.getProperty("inspecto.bench.buildMemory", "default");
        Files.createDirectories(dir);
        Path file = dir.resolve("day.parquet");
        long t0 = System.nanoTime();
        try (Connection c = DriverManager.getConnection("jdbc:duckdb:"); Statement st = c.createStatement()) {
            st.execute("SET TimeZone = 'UTC'");
            st.execute("COPY (SELECT '4417' || CAST(floor(" + entities + " * pow(random(), 2)) AS BIGINT) AS s, '4417' || CAST(floor("
                    + entities + " * random()) AS BIGINT) AS t, CASE WHEN random() < 0.8 THEN 'call' ELSE 'sms' END AS k, TIMESTAMP '2026-01-01' + "
                    + "to_seconds(CAST(floor(86400 * random()) AS BIGINT)) AS ts FROM range(" + rows + ")) TO '" + file.toString().replace(java.io.File.separatorChar, '/')
                    + "' (FORMAT parquet)");
        }
        System.out.printf("CARD generated %,d rows over %,d entities, %.2f GB parquet in %.1f s%n", rows, entities,
                Files.size(file) / 1e9, (System.nanoTime() - t0) / 1e9);
        IndexMapping m = new IndexMapping("s", "t", "k", "ts", null, null, List.of());
        String rel = "SELECT * FROM read_parquet('" + file.toString().replace(java.io.File.separatorChar, '/') + "')";
        long t = System.nanoTime();
        IndexBuilder.Result r = IndexBuilder.build(new IndexBuilder.Request("card", m, rel, new IndexStore(dir.resolve("idx"), "card", m.hash()),
                "fp", new IndexBuilder.Options(null, "default".equals(mem) ? null : mem, null, null, null),
                List.of(new IndexManifest.InputFile("day.parquet", Files.size(file), Files.getLastModifiedTime(file).toMillis())),
                IndexBuilder.Mode.FULL, null, List.of(dir)));
        System.out.printf("CARD FULL %.1f s wall, %,d edges, %,d nodes, %d buckets, peakWS=%d MB%n", (System.nanoTime() - t) / 1e9, r.edges(),
                r.nodes(), r.manifest().buckets(), peakMb());
        System.out.println("CARD timings(ms) " + r.timingsMs());
    }

    private static long peakMb() {
        try {
            Process pr = new ProcessBuilder("powershell", "-NoProfile", "-Command",
                    "[math]::Round((Get-Process -Id " + ProcessHandle.current().pid() + ").PeakWorkingSet64/1MB)").redirectErrorStream(true).start();
            try (var r = new java.io.BufferedReader(new java.io.InputStreamReader(pr.getInputStream()))) {
                return Long.parseLong(r.readLine().trim());
            }
        } catch (Exception e) {
            return -1;
        }
    }
}
