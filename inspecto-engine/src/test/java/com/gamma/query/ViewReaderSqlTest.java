package com.gamma.query;

import com.gamma.consignment.ConsignmentOutput;
import com.gamma.consignment.ConsignmentOutput.State;
import com.gamma.consignment.ConsignmentOutputStores;
import com.gamma.consignment.DbConsignmentOutputStore;
import com.gamma.pipeline.ViewDefinition;
import com.gamma.util.JdbcDrivers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link ViewReaderSql} (consignment addressing §7-A, the {@code deriveViewSql} residual): a view's derived SQL
 * is <b>persisted and executed later</b>, so its source read has to be rebuilt at read time — a glob baked in at
 * write time keeps reading a revision the catalog has since marked superseded.
 *
 * <p>The decisive test is {@link #aSupersededFileIsExcludedAtREADTimeNotWriteTime}: the definition is written
 * BEFORE the supersede is recorded, which is the real sequence and the one a write-time filter cannot serve.
 */
class ViewReaderSqlTest {

    @AfterEach
    void clearRegistry() {
        ConsignmentOutputStores.use(null);   // process-wide static — never leak into another test
    }

    private static String parquet(Connection conn, Path dir, String name, int id) throws Exception {
        String path = dir.resolve(name + ".parquet").toString().replace("\\", "/");
        try (Statement st = conn.createStatement()) {
            st.execute("COPY (SELECT " + id + " AS id) TO '" + path + "' (FORMAT PARQUET)");
        }
        return path;
    }

    private static ConsignmentOutput row(String path, State state) {
        return new ConsignmentOutput("c-" + path.hashCode(), "run-1", "cdr", "", null, path,
                1, 100, "2026-08-10T10:00:00Z", 0, state);
    }

    /** A runner-shaped definition: the source read is the token, its ingredients ride alongside. */
    private static ViewDefinition templated(Path root) {
        return new ViewDefinition("v", "p", List.of("src"),
                "SELECT * FROM " + ViewReaderSql.READER_TOKEN,
                root.toString().replace("\\", "/"), "PARQUET", "2026-08-28T00:00:00Z");
    }

    private static List<Integer> idsFrom(Connection conn, String sql) throws Exception {
        List<Integer> ids = new ArrayList<>();
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT id FROM (" + sql + ") t ORDER BY id")) {
            while (rs.next()) ids.add(rs.getInt(1));
        }
        return ids;
    }

    /**
     * The defect this closes. The view is defined while one revision is live; a recompute then supersedes it and
     * writes a new one. The persisted SQL never changes — only the render does — so the read must drop the old
     * file. Baking the glob in (the old behaviour) reads BOTH and double-counts silently.
     */
    @Test
    void aSupersededFileIsExcludedAtREADTimeNotWriteTime(@TempDir Path dir) throws Exception {
        try (Connection conn = JdbcDrivers.connect("jdbc:duckdb:");
             DbConsignmentOutputStore db = DbConsignmentOutputStore.open("jdbc:duckdb:")) {
            String rev1 = parquet(conn, dir, "rev1", 1);
            ViewDefinition def = templated(dir);          // defined FIRST, while rev1 is the only revision

            // the recompute: rev1 is superseded, rev2 lands beside it (retention has not run)
            parquet(conn, dir, "rev2", 2);
            db.record(List.of(row(rev1, State.SUPERSEDED)));
            ConsignmentOutputStores.use(db);

            assertEquals(List.of(2), idsFrom(conn, ViewReaderSql.rendered(def)),
                    "the render must drop the superseded revision — a baked-in glob would read both");
        }
    }

    /** With nothing to exclude the render is an ordinary glob read, so the common path stays a no-op. */
    @Test
    void withNothingToExcludeTheRenderReadsEverythingPresent(@TempDir Path dir) throws Exception {
        try (Connection conn = JdbcDrivers.connect("jdbc:duckdb:")) {
            parquet(conn, dir, "a", 1);
            parquet(conn, dir, "b", 2);
            assertNull(ConsignmentOutputStores.shared(), "precondition: no registry");

            String sql = ViewReaderSql.rendered(templated(dir));
            assertFalse(sql.contains(ViewReaderSql.READER_TOKEN), "the token must be gone after rendering");
            assertEquals(List.of(1, 2), idsFrom(conn, sql));
        }
    }

    /** Hand-authored and pre-template definitions keep executing byte-for-byte as they always did. */
    @Test
    void plainSqlPassesThroughUntouched() {
        String plain = "SELECT * FROM read_parquet('/data/x/**/*.parquet')";
        assertEquals(plain, ViewReaderSql.rendered(
                new ViewDefinition("v", "p", List.of(), plain, "2026-08-28T00:00:00Z")));
    }

    /** No derived SQL stays null — the caller's own "re-run the pipeline" handling is untouched. */
    @Test
    void aDefinitionWithNoDerivedSqlRendersNull() {
        assertNull(ViewReaderSql.rendered(
                new ViewDefinition("v", "p", List.of(), null, "2026-08-28T00:00:00Z")));
    }

    /**
     * A torn definition — templated SQL whose reader ingredients are missing — must refuse loudly. Rendering
     * it as an unfiltered glob would reintroduce the very staleness this class exists to remove, and leaving
     * the token in would reach DuckDB as a syntax error naming nothing useful.
     */
    @Test
    void aTemplatedDefinitionMissingItsReaderFieldsRefuses() {
        ViewDefinition torn = new ViewDefinition("v", "p", List.of(),
                "SELECT * FROM " + ViewReaderSql.READER_TOKEN, null, null, "2026-08-28T00:00:00Z");
        var e = assertThrows(IllegalArgumentException.class, () -> ViewReaderSql.rendered(torn));
        assertTrue(e.getMessage().contains("torn"), e.getMessage());
    }

    /** The persisted round trip carries the reader fields, or the render above could never happen. */
    @Test
    void theReaderFieldsSurviveTheMapRoundTrip() {
        ViewDefinition def = templated(Path.of("/data/store"));
        ViewDefinition back = ViewDefinition.fromMap(def.toMap());
        assertEquals(def.readerRoot(), back.readerRoot());
        assertEquals("PARQUET", back.readerFormat());
        assertEquals(def.derivedSql(), back.derivedSql());
    }
}
