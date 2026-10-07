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
        assertTrue(failure.getMessage().startsWith(DuckDbUtil.PENDING_QUERY_PREAMBLE),
                "the driver no longer writes the preamble this seam strips: " + failure.getMessage());

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
