package com.gamma.query;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Rule Template compilation — the {@code :name} → positional-{@code ?} rewrite that makes a stored
 * template executable. These tests exist because the rewrite decides what the statement *means*: two
 * things in DuckDB SQL look exactly like a bind and are not (a {@code ::} cast and a {@code :name} inside
 * a string literal), and an undeclared hole must never reach the driver.
 */
class RuleTemplateTest {

    private static RuleTemplate template(String paramSql, RuleTemplate.Param... params) {
        return new RuleTemplate("orders_over", "Orders over", "orders", List.of(), null, null,
                List.of(params), paramSql);
    }

    private static RuleTemplate.Param param(String name, String value) {
        return new RuleTemplate.Param(name, name, "gt", value);
    }

    @Test
    void replacesEachDeclaredHoleWithAPositionalBindInOrder() {
        RuleTemplate t = template(
                "SELECT * FROM orders WHERE total > :threshold AND region = :region",
                param("threshold", "100"), param("region", "EU"));

        RuleTemplate.Compiled c = t.compile(Map.of());

        assertEquals("SELECT * FROM orders WHERE total > ? AND region = ?", c.sql());
        // Order is the order of appearance in the SQL, NOT the order of `params` — positional binds.
        assertEquals(List.of("100", "EU"), c.binds());
    }

    @Test
    void suppliedValuesWinOverTheAuthoredDefaults() {
        RuleTemplate t = template("SELECT * FROM orders WHERE total > :threshold",
                param("threshold", "100"));

        assertEquals(List.of("250"), t.compile(Map.of("threshold", "250")).binds());
        // An absent key falls back to the default rather than binding null.
        assertEquals(List.of("100"), t.compile(Map.of("other", "9")).binds());
    }

    @Test
    void theSameHoleUsedTwiceBindsTwice() {
        RuleTemplate t = template("SELECT * FROM orders WHERE a > :x OR b < :x", param("x", "5"));
        RuleTemplate.Compiled c = t.compile(Map.of());
        assertEquals("SELECT * FROM orders WHERE a > ? OR b < ?", c.sql());
        // Positional binds cannot be reused by name — the value must be supplied once per placeholder.
        assertEquals(List.of("5", "5"), c.binds());
    }

    @Test
    void aCastIsNotABind() {
        // ⚠ `::INTEGER` would otherwise read as a bind named `INTEGER` and be rejected as undeclared,
        // making every cast-using template unrunnable.
        RuleTemplate t = template("SELECT * FROM orders WHERE total::INTEGER > :threshold",
                param("threshold", "100"));

        RuleTemplate.Compiled c = t.compile(Map.of());
        assertEquals("SELECT * FROM orders WHERE total::INTEGER > ?", c.sql());
        assertEquals(List.of("100"), c.binds());
    }

    @Test
    void aColonNameInsideAStringLiteralIsText() {
        // ⚠ Rewriting this would change the statement's meaning: the row filter becomes a bind.
        RuleTemplate t = template("SELECT * FROM orders WHERE note = ':threshold' AND total > :threshold",
                param("threshold", "100"));

        RuleTemplate.Compiled c = t.compile(Map.of());
        assertEquals("SELECT * FROM orders WHERE note = ':threshold' AND total > ?", c.sql());
        assertEquals(List.of("100"), c.binds());
    }

    @Test
    void anEscapedQuoteDoesNotEndTheLiteralEarly() {
        RuleTemplate t = template("SELECT * FROM orders WHERE note = 'it''s :here' AND total > :threshold",
                param("threshold", "100"));

        RuleTemplate.Compiled c = t.compile(Map.of());
        assertEquals("SELECT * FROM orders WHERE note = 'it''s :here' AND total > ?", c.sql());
        assertEquals(List.of("100"), c.binds());
    }

    @Test
    void anUndeclaredHoleIsRejectedRatherThanPassedThrough() {
        RuleTemplate t = template("SELECT * FROM orders WHERE total > :threshold AND x = :sneaky",
                param("threshold", "100"));

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> t.compile(Map.of()));
        assertTrue(e.getMessage().contains(":sneaky"), e.getMessage());
        // Fail closed: an unbound placeholder reaching the driver would bind by position against the
        // wrong value, which is worse than refusing to run.
    }

    @Test
    void aTemplateWithNoSqlAtAllIsItsOwnDistinctFailure() {
        RuleTemplate t = template(null);
        assertThrows(IllegalStateException.class, () -> t.compile(Map.of()));
    }

    @Test
    void fallsBackToSqlOverrideWhenParamSqlIsAbsent() {
        RuleTemplate t = new RuleTemplate("t", "T", "orders", List.of(), null,
                "SELECT 1 FROM orders WHERE total > :threshold", List.of(param("threshold", "7")), null);

        assertEquals(List.of("7"), t.compile(Map.of()).binds());
    }

    @Test
    void readsAStoredComponentConfigIncludingTheStarProjection() {
        RuleTemplate t = RuleTemplate.from(Map.of(
                "id", "orders_over", "name", "Orders over", "source", "orders",
                "projection", "*",
                "paramSql", "SELECT * FROM orders WHERE total > :threshold",
                "params", List.of(Map.of("name", "threshold", "field", "total", "operator", "gt", "value", "100"))));

        assertNotNull(t);
        assertEquals("orders_over", t.id());
        // `"*"` means all columns — an EMPTY projection, not a column literally named `*`.
        assertEquals(List.of(), t.projection());
        assertEquals(1, t.params().size());
        assertEquals("threshold", t.params().get(0).name());
        assertEquals(List.of("100"), t.compile(Map.of()).binds());
    }
}
