package {{packageName}};

import com.gamma.pipeline.PipelineNode;
import com.gamma.pipeline.PipelineNodeTypes;
import com.gamma.pipeline.PipelineRel;
import com.gamma.pipeline.exec.PipelineNodeExecutors;
import com.gamma.pipeline.exec.RowShaper;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Runs the Step the way the engine does — through {@code RowShaper.shape} over a real DuckDB — rather
 * than by calling the executor directly. That is the difference between "my class works" and "the engine
 * will find and run my class".
 */
class {{className}}Test {

    private static Connection open() throws Exception {
        Class.forName("org.duckdb.DuckDBDriver");
        Connection c = DriverManager.getConnection("jdbc:duckdb:");
        try (Statement st = c.createStatement()) {
            st.execute("CREATE TABLE src (msisdn VARCHAR, imsi VARCHAR, cell VARCHAR)");
            st.execute("INSERT INTO src VALUES ('491701234567','262011234567890','A1'), (NULL,'262019999999999','B2')");
        }
        return c;
    }

    /** 🔴 The registration itself — if either service file is wrong, this fails before any SQL runs. */
    @Test
    void bothHalvesAreDiscovered() {
        assertTrue(PipelineNodeTypes.isKnown({{className}}NodeType.TYPE),
                "descriptor not discovered — check META-INF/services/com.gamma.pipeline.PipelineNodeType");
        assertTrue(PipelineNodeExecutors.get({{className}}NodeType.TYPE).isPresent(),
                "executor not discovered — check META-INF/services/com.gamma.pipeline.exec.PipelineNodeExecutor");
    }

    /** The engine's own dispatch finds the executor and shapes the rows. */
    @Test
    void theEngineRunsTheStep() throws Exception {
        try (Connection c = open()) {
            PipelineNode node = PipelineNode.of("r", {{className}}NodeType.TYPE,
                    Map.of("columns", List.of("msisdn", "imsi"), "keep", "5"));

            List<RowShaper.Relation> out = RowShaper.shape(c, node, "src", "redact");

            assertEquals(List.of(PipelineRel.DATA), out.stream().map(RowShaper.Relation::rel).toList());
            // ...and what it produced is what the descriptor promised the validator.
            assertTrue(PipelineNodeTypes.get({{className}}NodeType.TYPE).orElseThrow().emits()
                    .containsAll(out.stream().map(RowShaper.Relation::rel).toList()));

            try (Statement st = c.createStatement();
                 ResultSet rs = st.executeQuery(
                         "SELECT msisdn, imsi, cell FROM \"" + out.get(0).table() + "\" ORDER BY imsi")) {
                assertTrue(rs.next());
                assertEquals("49170*******", rs.getString(1), "prefix kept, tail masked, length preserved");
                assertEquals("26201**********", rs.getString(2));
                assertEquals("A1", rs.getString(3), "an unlisted column travels untouched");

                assertTrue(rs.next());
                assertNull(rs.getString(1), "NULL stays NULL rather than becoming a mask string");
            }
        }
    }

    /** A redaction Step that redacts nothing is a typo — it must fail loudly, not pass rows through. */
    @Test
    void anEmptyColumnListIsRefused() throws Exception {
        try (Connection c = open()) {
            PipelineNode node = PipelineNode.of("r", {{className}}NodeType.TYPE, Map.of());
            Exception e = assertThrows(IllegalArgumentException.class,
                    () -> RowShaper.shape(c, node, "src", "bad"));
            assertTrue(e.getMessage().contains("columns"), e.getMessage());
        }
    }

    /** ⚠ Column names reach SQL from an operator's config: a reserved word must not break the Step. */
    @Test
    void aReservedWordColumnNameIsQuoted() throws Exception {
        try (Connection c = open()) {
            try (Statement st = c.createStatement()) {
                st.execute("CREATE TABLE odd (\"order\" VARCHAR)");
                st.execute("INSERT INTO odd VALUES ('123456789')");
            }
            PipelineNode node = PipelineNode.of("r", {{className}}NodeType.TYPE,
                    Map.of("columns", List.of("order"), "keep", "2"));

            List<RowShaper.Relation> out = RowShaper.shape(c, node, "odd", "quoted");
            try (Statement st = c.createStatement();
                 ResultSet rs = st.executeQuery("SELECT \"order\" FROM \"" + out.get(0).table() + '"')) {
                assertTrue(rs.next());
                assertEquals("12*******", rs.getString(1));
            }
        }
    }
}
