package {{packageName}};

import com.gamma.pipeline.PipelineNode;
import com.gamma.pipeline.PipelineNodeTypes;
import com.gamma.pipeline.PipelineRel;
import com.gamma.pipeline.exec.RowShaper;
import com.gamma.pipeline.exec.StepExecutors;
import com.gamma.pipeline.exec.StepFailure;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Runs the Step the way the engine does — through {@code RowShaper.shape} over a real DuckDB — rather than
 * by calling {@code execute} directly. On this test classpath the engine finds both halves through
 * {@code META-INF/services}, exactly as it does inside the pack.
 */
class {{className}}Test {

    private static Connection open() throws Exception {
        Connection c = DriverManager.getConnection("jdbc:duckdb:");
        try (Statement st = c.createStatement()) {
            st.execute("CREATE TABLE src AS SELECT * FROM (VALUES ('4917012', 1), (NULL, 2), ('4917099', 3)) t(msisdn, n)");
        }
        return c;
    }

    /** If either service file is wrong, this fails before any row is read. */
    @Test
    void bothHalvesAreDiscovered() {
        assertTrue(PipelineNodeTypes.isKnown({{className}}NodeType.TYPE),
                "descriptor not discovered — check META-INF/services/com.gamma.pipeline.PipelineNodeType");
        assertTrue(StepExecutors.get({{className}}NodeType.TYPE).isPresent(),
                "Step not discovered — check META-INF/services/com.gamma.pipeline.exec.StepExecutor");
    }

    @Test
    void theEngineRunsTheStep() throws Exception {
        try (Connection c = open()) {
            PipelineNode node = PipelineNode.of("n", {{className}}NodeType.TYPE, Map.of("required", "msisdn"));

            Map<String, String> out = new LinkedHashMap<>();
            for (RowShaper.Relation r : RowShaper.shape(c, node, "src", "n")) out.put(r.rel(), r.table());

            assertEquals(2, count(c, out.get(PipelineRel.DATA)));
            assertEquals(1, count(c, out.get({{className}}NodeType.MISSING)), "the NULL row is a reject, not an error");
        }
    }

    /** A bad config fails the batch, and the engine drops whatever the Step created. */
    @Test
    void aMissingAttributeFailsTheBatch() throws Exception {
        try (Connection c = open()) {
            PipelineNode node = PipelineNode.of("n", {{className}}NodeType.TYPE, Map.of());
            StepFailure f = assertThrows(StepFailure.class, () -> RowShaper.shape(c, node, "src", "n"));
            assertEquals(StepFailure.STEP_FAILED, f.code());
            assertTrue(f.getMessage().contains("required"), f.getMessage());
        }
    }

    private static int count(Connection c, String table) throws Exception {
        try (Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT count(*) FROM \"" + table + "\"")) {
            rs.next();
            return rs.getInt(1);
        }
    }
}
