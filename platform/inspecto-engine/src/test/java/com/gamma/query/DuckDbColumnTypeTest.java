package com.gamma.query;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The DuckDB-type-name → coarse-type mapping and the set-level temporal tie-break
 * (`TYPEFLOW-DATASET-COLUMNS-1` steps 3+4).
 *
 * <p>⚠ The coarse vocabulary and the role heuristic are pinned by
 * {@code inspecto-ui/src/app/inspecto/contracts/column-role.contract.json}; the cases below mirror it so the
 * two sides cannot drift silently. Step 1 of the row exists precisely because the heuristic once lived twice
 * and unpinned.
 */
class DuckDbColumnTypeTest {

    @Test
    void theFourCoarseTypesCoverDuckDbsSpellings() {
        assertEquals("number", ResultSetDescriptor.columnType("BIGINT"));
        assertEquals("number", ResultSetDescriptor.columnType("HUGEINT"));
        assertEquals("number", ResultSetDescriptor.columnType("UBIGINT"));
        assertEquals("number", ResultSetDescriptor.columnType("DOUBLE"));
        assertEquals("boolean", ResultSetDescriptor.columnType("BOOLEAN"));
        assertEquals("date", ResultSetDescriptor.columnType("DATE"));
        assertEquals("date", ResultSetDescriptor.columnType("TIMESTAMPTZ"));
        assertEquals("string", ResultSetDescriptor.columnType("VARCHAR"));
        assertEquals("string", ResultSetDescriptor.columnType(null), "an absent type is not a crash");
    }

    /** Parameterised and spelled-out forms arrive whole from DESCRIBE — match the leading token. */
    @Test
    void parameterisedAndSpelledOutFormsResolve() {
        assertEquals("number", ResultSetDescriptor.columnType("DECIMAL(18,2)"));
        assertEquals("date", ResultSetDescriptor.columnType("TIMESTAMP WITH TIME ZONE"));
        assertEquals("date", ResultSetDescriptor.columnType("TIME WITH TIME ZONE"));
    }

    /**
     * ⛔ Two deliberate non-obvious calls: an INTERVAL is a DURATION, not a point in time — calling it
     * temporal would hand it to a time axis it cannot sit on; and a composite has no coarse type, so it
     * falls to string rather than being rendered as something it is not.
     */
    @Test
    void anIntervalIsNotADateAndACompositeIsNotItsElement() {
        assertEquals("string", ResultSetDescriptor.columnType("INTERVAL"));
        assertEquals("string", ResultSetDescriptor.columnType("VARCHAR[]"));
        assertEquals("string", ResultSetDescriptor.columnType("BIGINT[]"), "a LIST of numbers is not a number");
        assertEquals("string", ResultSetDescriptor.columnType("STRUCT(a INTEGER)"));
        assertEquals("string", ResultSetDescriptor.columnType("MAP(VARCHAR, INTEGER)"));
    }

    @Test
    void oneDateColumnIsTheTemporalAxis() {
        List<ResultSetDescriptor.Column> cols = ResultSetDescriptor.describeTypeNames(
                List.of("event_time", "amount", "customer_id", "customer"),
                List.of("TIMESTAMP", "DECIMAL(18,2)", "BIGINT", "VARCHAR"));

        assertEquals("temporal", role(cols, "event_time"));
        assertEquals("measure", role(cols, "amount"));
        assertEquals("dimension", role(cols, "customer_id"), "an _id column is never a measure");
        assertEquals("dimension", role(cols, "customer"));
    }

    /**
     * ✅ Q2 (operator): several date columns ⇒ derive {@code temporal} for NONE. Guessing which of
     * created_at / updated_at is *the* axis is a confident wrong answer a human then has to find and undo;
     * with none marked, the editor shows an unanswered question instead of a plausible mistake.
     */
    @Test
    void severalDateColumnsMeanNoTemporalAtAll() {
        List<ResultSetDescriptor.Column> cols = ResultSetDescriptor.describeTypeNames(
                List.of("created_at", "updated_at", "amount"),
                List.of("TIMESTAMP", "DATE", "DOUBLE"));

        assertEquals("dimension", role(cols, "created_at"));
        assertEquals("dimension", role(cols, "updated_at"));
        assertEquals(0, cols.stream().filter(c -> "temporal".equals(c.role())).count(),
                "no column may be guessed as the time axis when several could be");
        assertEquals("measure", role(cols, "amount"), "the tie-break touches dates only");
        assertEquals("date", cols.get(0).type(), "…and their TYPE is still date — only the role backs off");
    }

    private static String role(List<ResultSetDescriptor.Column> cols, String name) {
        return cols.stream().filter(c -> c.name().equals(name)).findFirst().orElseThrow().role();
    }
}
