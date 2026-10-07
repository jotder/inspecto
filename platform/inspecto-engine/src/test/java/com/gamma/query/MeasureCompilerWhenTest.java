package com.gamma.query;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** The additive {@code when} condition tree on a {@link MeasureCompiler.Spec} (MODULE-REORG-P7-KERNEL step 5). */
class MeasureCompilerWhenTest {

    private static Map<String, Object> body(Object when) {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("dataset", "d");
        b.put("measures", List.of(Map.of("agg", "count")));
        b.put("groupBy", List.of("k"));
        b.put("filters", List.of(Map.of("field", "a", "op", "=", "value", "x")));
        if (when != null) b.put("when", when);
        return b;
    }

    private static Map<String, Object> tree(String field, String op, String value) {
        return Map.of("kind", "group", "op", "AND", "items",
                List.of(Map.of("kind", "condition", "field", field, "operator", op, "value", value)));
    }

    @Test
    void absentOrEmptyWhenLeavesTheCompiledSqlUntouched() {
        String plain = MeasureCompiler.compile(MeasureCompiler.parse(body(null), 10, 10));
        assertEquals(plain, MeasureCompiler.compile(MeasureCompiler.parse(body(Map.of()), 10, 10)));
        assertEquals(plain, MeasureCompiler.compile(MeasureCompiler.parse(body(Map.of("kind", "group", "op", "AND", "items", List.of())), 10, 10)));
        assertTrue(plain.contains("WHERE \"a\" = 'x' GROUP BY"), plain);
    }

    @Test
    void whenIsAndedAfterTheUntouchedFlatTerms() {
        String sql = MeasureCompiler.compile(MeasureCompiler.parse(body(tree("s", "=", "FAILED")), 10, 10));
        assertTrue(sql.contains("WHERE \"a\" = 'x' AND (CAST(\"s\" AS VARCHAR) = 'FAILED') GROUP BY"), sql);
    }

    @Test
    void whenAloneBecomesTheWhereClause() {
        Map<String, Object> b = body(tree("s", "=", "F"));
        b.remove("filters");
        assertTrue(MeasureCompiler.compile(MeasureCompiler.parse(b, 10, 10)).contains(" WHERE (CAST(\"s\" AS VARCHAR) = 'F') GROUP BY"));
    }

    @Test
    void theSevenArgConstructorStaysSourceCompatibleWithNoWhen() {
        MeasureCompiler.Spec s = new MeasureCompiler.Spec("d", List.of(), List.of("k"), Map.of(), List.of(), List.of(), 5);
        assertNull(s.when());
        assertEquals("SELECT \"k\" FROM \"d\" LIMIT 5", MeasureCompiler.compile(s));
    }

    @Test
    void aBareLeafOrNonObjectWhenIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> MeasureCompiler.parse(
                body(Map.of("kind", "condition", "field", "s", "operator", "=", "value", "x")), 10, 10));
        assertThrows(IllegalArgumentException.class, () -> MeasureCompiler.parse(body("s = 'x'"), 10, 10));
        assertThrows(IllegalArgumentException.class, () -> MeasureCompiler.parse(body(tree("s", "matches", "(?=x)")), 10, 10));
    }

    @Test
    void hostileColumnAndValueAreEscapedIntoQuotedTokens() {
        String sql = MeasureCompiler.compile(MeasureCompiler.parse(
                body(tree("s\"; DROP TABLE t; --", "=", "x' OR '1'='1")), 10, 10));
        assertTrue(sql.contains("\"s\"\"; DROP TABLE t; --\""), "the identifier's quote is doubled: " + sql);
        assertTrue(sql.contains("'x'' OR ''1''=''1'"), "the value's quote is doubled: " + sql);
    }
}
