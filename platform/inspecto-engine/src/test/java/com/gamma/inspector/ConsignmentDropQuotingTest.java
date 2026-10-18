package com.gamma.inspector;

import com.gamma.util.DuckDbUtil;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ConsignmentIngestStrategy#dropTable} / {@link ConsignmentIngestStrategy#dropView} quote their relation
 * through {@code SqlIdent.q} (ANALYTICS-CORE-1 residual): a name containing a double quote used to give broken SQL,
 * which the best-effort contract swallowed, leaving the scratch relation behind.
 */
class ConsignmentDropQuotingTest {

    private static long relations(Statement st, String name) throws Exception {
        try (ResultSet rs = st.executeQuery("SELECT count(*) FROM information_schema.tables WHERE table_name = '"
                + name.replace("'", "''") + "'")) {
            assertTrue(rs.next());
            return rs.getLong(1);
        }
    }

    @Test
    void aRelationNameContainingADoubleQuoteIsDropped() throws Exception {
        File db = DuckDbUtil.tempDbFile("drop_quote_");
        try (Connection c = DuckDbUtil.openConnection(db); Statement st = c.createStatement()) {
            st.execute("CREATE TABLE \"t\"\"x\" AS SELECT 1 AS id");
            st.execute("CREATE VIEW \"v\"\"x\" AS SELECT 1 AS id");
            assertEquals(1, relations(st, "t\"x"));
            assertEquals(1, relations(st, "v\"x"));
            ConsignmentIngestStrategy.dropTable(c, "t\"x");
            ConsignmentIngestStrategy.dropView(c, "v\"x");
            assertEquals(0, relations(st, "t\"x"), "the table is gone");
            assertEquals(0, relations(st, "v\"x"), "the view is gone");
        } finally {
            DuckDbUtil.deleteTempDb(db);
        }
    }
}
