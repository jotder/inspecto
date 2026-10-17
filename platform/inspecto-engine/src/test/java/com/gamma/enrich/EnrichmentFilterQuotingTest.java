package com.gamma.enrich;

import com.gamma.util.DuckDbUtil;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link EnrichmentEngine#buildFilter}: the partition-filter key is an identifier quoted through
 * {@code SqlIdent.q}. Before ANALYTICS-CORE-1 cleanup a key containing a double quote was wrapped without
 * escaping and produced broken SQL.
 */
class EnrichmentFilterQuotingTest {

    @Test
    void filterSqlTextIsUnchangedForPlainKeys() {
        Map<String, String> a = new LinkedHashMap<>();
        a.put("event_type", "CALL");
        a.put("day", "03");
        assertEquals("(\"event_type\"='CALL' AND \"day\"='03') OR (\"event_type\"='SMS')",
                EnrichmentEngine.buildFilter(List.of(a, Map.of("event_type", "SMS"))));
        assertEquals("", EnrichmentEngine.buildFilter(List.of()));
    }

    @Test
    void aKeyContainingADoubleQuoteRunsAndSelectsTheRightRows() throws Exception {
        File db = DuckDbUtil.tempDbFile("filter_quote_");
        try (Connection c = DuckDbUtil.openConnection(db); Statement st = c.createStatement()) {
            st.execute("CREATE TABLE t AS SELECT * FROM (VALUES ('x','1'),('y','2')) v(\"a\"\"b\", id)");
            String where = EnrichmentEngine.buildFilter(List.of(Map.of("a\"b", "y")));
            List<String> ids = new ArrayList<>();
            try (ResultSet rs = st.executeQuery("SELECT id FROM t WHERE " + where)) {
                while (rs.next()) ids.add(rs.getString(1));
            }
            assertEquals(List.of("2"), ids);
        } finally {
            DuckDbUtil.deleteTempDb(db);
        }
    }
}
