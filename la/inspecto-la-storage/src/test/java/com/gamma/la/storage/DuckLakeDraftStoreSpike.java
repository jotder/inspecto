package com.gamma.la.storage;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.stream.Stream;

/**
 * DUCKDB-2-LOOP-QUERIES-1 C8 spike: DuckLake as the home of an Investigation's log and Working Sets, measured against what
 * {@code InvestigationStore} needs - byte-identical text lines appended one step at a time, history, and more than one
 * writer. Loads the extension from the local cache with auto-install OFF (no network). Prints; runs only with
 * {@code -Dinspecto.bench.dir=...}.
 */
@Tag("bench")
@EnabledIfSystemProperty(named = "inspecto.bench.dir", matches = ".+")
class DuckLakeDraftStoreSpike {

    private static Connection lake(Path dir) throws SQLException {
        Connection c = DriverManager.getConnection("jdbc:duckdb:");
        try (Statement st = c.createStatement()) {
            st.execute("SET autoinstall_known_extensions = false");
            st.execute("LOAD ducklake");
            String d = dir.toAbsolutePath().toString().replace('\\', '/');
            st.execute("ATTACH 'ducklake:" + d + "/meta.ducklake' AS lake (DATA_PATH '" + d + "/files/')");
        }
        return c;
    }

    private static long files(Path dir) throws Exception {
        try (Stream<Path> s = Files.walk(dir.resolve("files"))) {
            return s.filter(Files::isRegularFile).count();
        }
    }

    @Test
    void appendHistoryAndTwoWriters() throws Exception {
        Path dir = Path.of(System.getProperty("inspecto.bench.dir")).resolve("ducklake-spike");
        if (Files.exists(dir)) try (Stream<Path> s = Files.walk(dir)) {
            s.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        }
        Files.createDirectories(dir);
        int steps = Integer.getInteger("inspecto.bench.steps", 200);
        String set = "{\"hash\":\"h\",\"step\":0,\"workingSet\":{\"entities\":[" + "\"e\",".repeat(2_000) + "\"e\"]}}";
        try (Connection c = lake(dir); Statement st = c.createStatement()) {
            st.execute("CREATE TABLE lake.log (inv VARCHAR, step INTEGER, line VARCHAR, ws VARCHAR)");
            // 1. appending one step at a time, as an Investigation is written
            long t = System.nanoTime();
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO lake.log VALUES ('inv-1', ?, ?, ?)")) {
                for (int i = 1; i <= steps; i++) {
                    ps.setInt(1, i);
                    ps.setString(2, "{\"op\":\"expand\",\"step\":" + i + ",\"note\":\"  spaced  \\u00e9 \"}");
                    ps.setString(3, set);
                    ps.executeUpdate();
                }
            }
            long appendMs = (System.nanoTime() - t) / 1_000_000;
            long snaps;
            try (ResultSet rs = st.executeQuery("SELECT count(*) FROM ducklake_snapshots('lake')")) {
                rs.next();
                snaps = rs.getLong(1);
            }
            System.out.printf("C8 append: %d steps in %d ms (%.1f ms/step), %d data files, %d snapshots%n",
                    steps, appendMs, appendMs / (double) steps, files(dir), snaps);

            // 2. byte identity of a stored line
            try (ResultSet rs = st.executeQuery("SELECT line FROM lake.log WHERE step = 7")) {
                rs.next();
                System.out.printf("C8 byte-identical line: %s%n",
                        rs.getString(1).equals("{\"op\":\"expand\",\"step\":7,\"note\":\"  spaced  \\u00e9 \"}"));
            }

            // 3. time travel to an earlier snapshot
            try (ResultSet rs = st.executeQuery("SELECT count(*) FROM lake.log AT (VERSION => 10)")) {
                rs.next();
                System.out.printf("C8 time travel to snapshot 10: %d rows%n", rs.getLong(1));
            }

            // 4. compaction of the small files
            t = System.nanoTime();
            try {
                st.execute("CALL ducklake_merge_adjacent_files('lake')");
                System.out.printf("C8 merge_adjacent_files: %d ms, %d data files after%n", (System.nanoTime() - t) / 1_000_000, files(dir));
            } catch (SQLException e) {
                System.out.printf("C8 merge_adjacent_files failed: %s%n", e.getMessage().lines().findFirst().orElse(""));
            }

            // 5. a second writer (another DuckDB instance in this JVM, as a second pod would be another process)
            try (Connection other = lake(dir); Statement o = other.createStatement()) {
                o.execute("INSERT INTO lake.log VALUES ('inv-2', 1, 'x', 'y')");
                System.out.println("C8 second writer on a DuckDB-file catalog: ACCEPTED");
            } catch (SQLException e) {
                System.out.printf("C8 second writer on a DuckDB-file catalog: REFUSED (%s)%n",
                        e.getMessage().lines().findFirst().orElse(""));
            }
        }
    }
}
