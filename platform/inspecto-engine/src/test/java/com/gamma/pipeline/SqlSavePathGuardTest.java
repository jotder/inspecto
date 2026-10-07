package com.gamma.pipeline;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The save path must refuse author SQL the sandbox guard rejects.
 *
 * <p><b>The gap this closes.</b> {@code SqlGuard} had ten call sites and not one of them was a save. It ran
 * at {@code POST /components/transform/describe} — the pane's preview — and at execution in
 * {@code RowShaper}. So a hand-written {@code read_csv(...)}, a data-definition statement or a
 * multi-statement string saved cleanly, armed cleanly, and failed only when the pipeline ran. Fail-LATE,
 * never fail-open: execution always refused it, so this was never a route to running blocked SQL. But the
 * refusal arrived at the worst possible moment, and nothing tested the gap.
 *
 * <p>Both surfaces that write author SQL are covered: the graph save ({@code PipelineEditable.lower}) and
 * the linear Recipe compile ({@code RecipeCompiler}), which share one helper so they refuse the same
 * shapes with the same code and message.
 */
class SqlSavePathGuardTest {

    /** The shape both committed fixtures carry, so "the guard does not refuse real configs" is testable. */
    private static final String CLEAN = "SELECT ORDER_ID, ORDER_DATE, REGION, STATUS, GROSS, "
            + "ROUND(GROSS / 1.19, 2) AS NET, CASE WHEN GROSS >= 50 THEN 'LARGE' ELSE 'SMALL' END "
            + "AS SIZE_BAND FROM input WHERE STATUS <> 'CANCELLED'";

    private static List<PipelineCompileException.Refusal> refusalsFor(Object sql) {
        List<PipelineCompileException.Refusal> out = new ArrayList<>();
        PipelineEditable.refuseUnsafeSql(sql, "s1", out);
        return out;
    }

    private static void assertRefused(String sql, String because) {
        List<PipelineCompileException.Refusal> r = refusalsFor(sql);
        assertTrue(r.stream().anyMatch(x -> PipelineEditable.SQL_STEP_REFUSED.equals(x.code())),
                because + " must be refused at save, got " + r);
    }

    @Test
    void aFileReadingFunctionIsRefused() {
        assertRefused("SELECT * FROM read_csv('/etc/passwd')", "read_csv");
        assertRefused("SELECT * FROM read_parquet('x.parquet')", "read_parquet");
        assertRefused("SELECT glob('*') AS g FROM input", "glob");
    }

    @Test
    void aMultiStatementStringIsRefused() {
        assertRefused("SELECT 1 FROM input; SELECT 2 FROM input", "a second statement");
    }

    @Test
    void aStatementThatIsNotReadOnlyIsRefused() {
        assertRefused("CREATE TABLE t AS SELECT * FROM input", "CREATE");
        assertRefused("ATTACH 'other.db' AS o", "ATTACH");
        assertRefused("COPY input TO 'out.csv'", "COPY");
    }

    /** The message carries the guard's own words, so an author is told WHICH construct tripped it. */
    @Test
    void theRefusalNamesTheOffendingConstruct() {
        List<PipelineCompileException.Refusal> r = refusalsFor("SELECT * FROM read_csv('x')");
        assertTrue(r.stream().anyMatch(x -> x.message().toLowerCase().contains("read_csv")),
                "the refusal must name read_csv rather than say 'invalid SQL', got " + r);
    }

    @Test
    void theCommittedFixtureShapeIsNotRefused() {
        assertEquals(List.of(), refusalsFor(CLEAN),
                "both committed *_pipeline.toon fixtures carry this SELECT — refusing it would turn the "
                        + "repo-wide RecipeConverter sweep red");
    }

    /**
     * ⚠ Blankness stays {@code SQL_STEP_EMPTY}'s business. If the guard also refused blank input, one
     * defect would report twice and the author would see two refusals for one mistake.
     */
    @Test
    void blankAndAbsentSqlAreLeftToTheEmptyCheck() {
        assertEquals(List.of(), refusalsFor(""), "blank is SQL_STEP_EMPTY's business");
        assertEquals(List.of(), refusalsFor("   "), "whitespace is SQL_STEP_EMPTY's business");
        assertEquals(List.of(), refusalsFor(null), "absent is SQL_STEP_EMPTY's business");
        assertEquals(List.of(), refusalsFor(42), "a non-string is not this check's problem");
    }

    // ── through the real save path ────────────────────────────────────────────────────────────────

    private static PipelineCompileException lowerExpectingRefusal(String sql) {
        PipelineGraph g = new PipelineGraph("p", false,
                List.of(PipelineNode.of("s1", BuiltinNodeType.TRANSFORM_SQL.type(), Map.of("sql", sql))),
                List.of());
        return assertThrows(PipelineCompileException.class,
                () -> PipelineEditable.lower(g, new LinkedHashMap<>(), false));
    }

    /** The integration half: the call site is actually wired into {@code lower}, not merely present. */
    @Test
    void lowerRefusesUnsafeSqlOnATransformSqlNode() {
        PipelineCompileException e = lowerExpectingRefusal("SELECT * FROM read_csv('x')");
        assertTrue(e.refusals().stream().anyMatch(r -> PipelineEditable.SQL_STEP_REFUSED.equals(r.code())),
                "lower() must carry SQL_STEP_REFUSED, got " + e.refusals());
    }

    /** A non-strict lower over a clean single node must not produce a SQL refusal of any kind. */
    @Test
    void lowerDoesNotRefuseTheCleanShape() {
        PipelineGraph g = new PipelineGraph("p", false,
                List.of(PipelineNode.of("s1", BuiltinNodeType.TRANSFORM_SQL.type(), Map.of("sql", CLEAN))),
                List.of());
        try {
            PipelineEditable.lower(g, new LinkedHashMap<>(), false);
        } catch (PipelineCompileException e) {
            assertTrue(e.refusals().stream().noneMatch(
                            r -> PipelineEditable.SQL_STEP_REFUSED.equals(r.code())),
                    "a clean SELECT must never raise SQL_STEP_REFUSED, got " + e.refusals());
        }
    }

    /** The linear Recipe surface is the other way author SQL reaches disk, and it refuses identically. */
    @Test
    void theRecipeSurfaceRefusesTheSameShapes() {
        Map<String, Object> recipe = new LinkedHashMap<>();
        recipe.put("name", "p");
        recipe.put("steps", List.of(Map.of("sql", Map.of("sql", "SELECT * FROM read_csv('x')"))));

        PipelineCompileException e = assertThrows(PipelineCompileException.class,
                () -> RecipeCompiler.compile(recipe, new LinkedHashMap<>(), false));
        assertTrue(e.refusals().stream().anyMatch(r -> PipelineEditable.SQL_STEP_REFUSED.equals(r.code())),
                "the Recipe surface must refuse what the graph save refuses, got " + e.refusals());
    }
}
