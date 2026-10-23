package com.gamma.util;

import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link DuckDbUtil#withoutPendingQueryPreamble} against the REAL driver, so a DuckDB upgrade that rewords
 * the preamble turns this red instead of silently letting it back into every author-facing 422
 * (TESTRUN-BINDER-ERROR-LEAKS-PREAMBLE-1).
 */
class DuckDbPendingQueryPreambleTest {

    @Test
    void aPlainStatementFailureReadsAsTheBinderErrorOnceStripped() throws Exception {
        DuckDbUtil.loadDriver();
        SQLException failure;
        try (Connection c = DriverManager.getConnection("jdbc:duckdb:");
             Statement st = c.createStatement()) {
            st.execute("CREATE TABLE t(EVENT_DATE VARCHAR)");
            failure = assertThrows(SQLException.class,
                    () -> st.execute("CREATE TABLE u AS SELECT * FROM t WHERE EVENT_TS IS NOT NULL"));
        }
        // DuckDB 2.0 (DUCKDB-2-MIGRATION-1): the driver no longer writes the pending-query preamble, so the
        // message already starts with the error itself; the strip stays as a sanitiser and must keep it intact.
        assertFalse(failure.getMessage().contains(DuckDbUtil.PENDING_QUERY_PREAMBLE),
                "the driver writes the preamble again - re-check every caller of the strip: " + failure.getMessage());
        assertTrue(failure.getMessage().startsWith("Binder Error: "), failure.getMessage());
        assertEquals("Binder Error: y",
                DuckDbUtil.withoutPendingQueryPreamble(DuckDbUtil.PENDING_QUERY_PREAMBLE + "Binder Error: y"),
                "a preamble, should one appear, is still stripped");

        String stripped = DuckDbUtil.withoutPendingQueryPreamble("test run failed: " + failure.getMessage());
        assertTrue(stripped.startsWith("test run failed: Binder Error: "), stripped);
        assertTrue(stripped.contains("\"EVENT_TS\""), stripped);
        assertTrue(stripped.contains("Candidate bindings: \"EVENT_DATE\""), stripped);
    }

    @Test
    void anythingElseIsLeftVerbatim() {
        assertNull(DuckDbUtil.withoutPendingQueryPreamble(null));
        String other = "Invalid Input Error: something else\nError: Binder Error: x";
        assertEquals(other, DuckDbUtil.withoutPendingQueryPreamble(other));
    }
}
