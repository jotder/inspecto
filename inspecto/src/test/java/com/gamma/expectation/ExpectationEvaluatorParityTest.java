package com.gamma.expectation;

import com.gamma.util.DuckDbUtil;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Golden corpus for the {@code non_null} / {@code range} / {@code regex} shorthand kinds: the failing-row
 * count each one produces, pinned independently of the SQL that computes it. Written against the hand-built
 * per-kind predicates and kept green when those moved onto the Condition Language tree
 * ({@code ConditionSql}) — Decision Kernel step 1 — so the two generators are proven to agree.
 *
 * <p>The corpus holds NO empty-string cells on purpose: the tree reads {@code ''} as null for
 * {@code isNull}/{@code isNotNull} (settled, plan §8b), the old {@code IS NULL} did not — that one
 * deliberate difference is pinned separately in {@link #anEmptyStringCellIsNullToTheTree}.
 */
class ExpectationEvaluatorParityTest {

    @TempDir
    static Path data;

    @BeforeAll
    static void seed() throws Exception {
        Path dir = Files.createDirectories(data.resolve("t").resolve("p=0"));
        write(dir, "SELECT * FROM (VALUES "
                + "(1,'a@x.com','10',10.0,'US'),"
                + "(2,NULL,'20',20.0,'US'),"
                + "(3,'zz','abc',-5.0,'ZZ'),"
                + "(4,'bad','100',100.0,'FR'),"
                + "(5,'c@x.com',NULL,NULL,'fr'),"
                + "(6,' ','0.5',0.0,'O''Brien'),"
                + "(7,'d@x.com','-1',250.5,'50%')"
                + ") t(id,email,amt_text,amt,code)");
        Path blanks = Files.createDirectories(data.resolve("blanks").resolve("p=0"));
        write(blanks, "SELECT * FROM (VALUES (1,'x'),(2,''),(3,NULL)) t(id,v)");
    }

    private static void write(Path dir, String select) throws Exception {
        String parquet = dir.resolve("data.parquet").toString().replace("\\", "/");
        DuckDbUtil.loadDriver();
        File db = DuckDbUtil.tempDbFile("expectation_parity_seed_");
        try (Connection conn = DuckDbUtil.openConnection(db); Statement st = conn.createStatement()) {
            st.execute("COPY (" + select + ") TO '" + parquet + "' (FORMAT PARQUET)");
        } finally {
            DuckDbUtil.deleteTempDb(db);
        }
    }

    private static long violations(String target, String column, String kind, Object... extra) throws Exception {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", "e");
        m.put("target", target);
        m.put("column", column);
        m.put("kind", kind);
        for (int i = 0; i < extra.length; i += 2) m.put((String) extra[i], extra[i + 1]);
        return ExpectationEvaluator.evaluate(Expectation.fromMap(m), data).violations();
    }

    @Test
    void nonNullCountsNullCells() throws Exception {
        assertEquals(1, violations("t", "email", "non_null"));
        assertEquals(1, violations("t", "amt", "non_null"));
        assertEquals(1, violations("t", "amt_text", "non_null"));
        assertEquals(0, violations("t", "id", "non_null"));
    }

    @Test
    void rangeCountsCellsOutsideTheBoundsAndIgnoresNulls() throws Exception {
        assertEquals(2, violations("t", "amt", "range", "min", 0, "max", 100));        // -5, 250.5
        assertEquals(1, violations("t", "amt", "range", "min", 0));                    // -5
        assertEquals(1, violations("t", "amt", "range", "max", 100));                  // 250.5
        assertEquals(4, violations("t", "amt", "range", "min", 0.5, "max", 99.5));     // -5, 0, 100, 250.5
        assertEquals(1, violations("t", "amt_text", "range", "min", 0, "max", 100));   // -1 ('abc' casts to null)
        assertEquals(0, violations("t", "id", "range", "min", 1, "max", 7));
    }

    @Test
    void regexCountsNonMatchingNonNullCells() throws Exception {
        assertEquals(3, violations("t", "email", "regex", "pattern", "^[^@]+@[^@]+$"));  // zz, bad, ' '
        assertEquals(3, violations("t", "code", "regex", "pattern", "^[A-Z]{2}$"));      // fr, O'Brien, 50%
        assertEquals(6, violations("t", "code", "regex", "pattern", "^O'B"));            // a quote in the pattern
        assertEquals(3, violations("t", "amt_text", "regex", "pattern", "^\\d+$"));      // abc, 0.5, -1
        assertEquals(0, violations("t", "id", "regex", "pattern", "^\\d$"));             // a numeric column, cast
    }

    /** The one deliberate difference from the old {@code IS NULL}: the tree reads {@code ''} as null. */
    @Test
    void anEmptyStringCellIsNullToTheTree() throws Exception {
        assertEquals(2, violations("blanks", "v", "non_null"));
    }

    /** The tree's pattern rules now apply at save: a lookahead means different things in RE2 and the JVM. */
    @Test
    void aRegexTheConditionLanguageRefusesIsRefusedAtSave() {
        Map<String, Object> m = new LinkedHashMap<>(Map.of("name", "e", "target", "t", "column", "email",
                "kind", "regex", "pattern", "^(?=a)a"));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () -> Expectation.fromMap(m));
        m.put("pattern", "^a");
        assertEquals("regex", Expectation.fromMap(m).kind());   // the same body with a plain pattern is accepted
    }
}
