package com.gamma.sql;

import com.gamma.util.DuckDbUtil;
import com.gamma.util.SqlIdent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The summarize kernel reads its input glob through {@link SqlViews#parquet} (ANALYTICS-CORE-1 M9) and must keep
 * {@code union_by_name}: two input files whose schemas drifted (the second gained a column) summarize together.
 */
class SummarizeSupportTest {

    @Test
    void aDriftedInputGlobSummarizesByColumnName(@TempDir Path dir) throws Exception {
        Path in = dir.resolve("in");
        java.nio.file.Files.createDirectories(in);
        try (Connection c = DuckDbUtil.openInMemory(null, java.util.List.of(dir));
             Statement st = c.createStatement()) {
            st.execute("COPY (SELECT 1 AS id, 10 AS amt) TO " + SqlIdent.pathStr(in.resolve("a.parquet")) + " (FORMAT PARQUET)");
            st.execute("COPY (SELECT 'x' AS tag, 32 AS amt, 2 AS id) TO " + SqlIdent.pathStr(in.resolve("b.parquet"))
                    + " (FORMAT PARQUET)");
        }
        Path out = dir.resolve("out.parquet");
        long rows = SummarizeSupport.summarizeToFile("summ_test_", in.toString().replace('\\', '/') + "/*.parquet",
                "SELECT count(*) AS n, sum(amt) AS total, count(tag) AS tagged FROM input",
                out.toString(), "PARQUET");
        assertEquals(1, rows);
        try (Connection c = DuckDbUtil.openInMemory(null, java.util.List.of(dir));
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT n, total, tagged FROM " + SqlViews.parquetFile(out))) {
            rs.next();
            assertEquals(2, rs.getLong(1));
            assertEquals(42, rs.getLong(2));
            assertEquals(1, rs.getLong(3));
        }
    }
}
