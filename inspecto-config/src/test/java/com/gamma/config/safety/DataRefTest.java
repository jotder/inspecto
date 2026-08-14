package com.gamma.config.safety;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pins the data-ref verdict that {@code DatasetRelation} and {@code ExpectationEvaluator} share.
 *
 * <p>Every refusal here asserts the <b>reason</b> in the message, not merely that something threw: the two
 * rules (shape, containment) both throw {@code IllegalArgumentException}, so a type-only assertion cannot
 * tell "refused for the right reason" from "refused for another one" — and while the shape rule holds,
 * every containment case is refused by shape first. A test that could not see the difference would report
 * the containment half as covered when it had never run.
 */
class DataRefTest {

    private static final String WHAT = "dataset physicalRef";

    private static String refusalOf(String ref) {
        return assertThrows(IllegalArgumentException.class,
                () -> DataRef.requireShape(ref, WHAT)).getMessage();
    }

    @Test
    void acceptsTheShapesRealStoresUse() {
        assertEquals("orders", DataRef.requireShape("orders", WHAT));
        assertEquals("orders/database", DataRef.requireShape("orders/database", WHAT));
        assertEquals("orders.v2", DataRef.requireShape("orders.v2", WHAT), "a dot inside a segment is legal");
        assertEquals("cdr_2026-08-14", DataRef.requireShape("cdr_2026-08-14", WHAT));
        assertEquals("shared/acme/orders", DataRef.requireShape("shared/acme/orders", WHAT));
    }

    @Test
    void refusesTraversalWhereverItAppears() {
        for (String ref : new String[]{"../etc", "a/../../etc", "a/..", "a/../b", ".."})
            assertTrue(refusalOf(ref).startsWith("unsafe " + WHAT), () -> "not refused: " + ref);
    }

    /**
     * The character class is what makes these refs structurally safe rather than merely filtered, so each
     * exclusion is pinned individually — widening the class silently would otherwise go unnoticed.
     */
    @Test
    void refusesAbsoluteUncAndDriveShapes() {
        assertTrue(refusalOf("/etc/passwd").contains("/etc/passwd"), "leading slash: first char must be alphanumeric");
        assertTrue(refusalOf("C:/data").contains("C:/data"), "a colon is not in the class, so no drive prefix");
        assertTrue(refusalOf("C:\\data").contains("C:\\data"));
        assertTrue(refusalOf("\\\\host\\share").contains("host"), "no backslash, so UNC cannot be spelled");
        assertTrue(refusalOf("a\\b").contains("a\\b"), "the Windows separator is not a ref separator");
    }

    @Test
    void refusesEmptyBlankNullAndLeadingPunctuation() {
        assertTrue(refusalOf("").startsWith("unsafe"));
        assertTrue(refusalOf(" orders").contains("orders"), "no leading space; the ref is not trimmed for you");
        assertTrue(refusalOf("-orders").contains("-orders"));
        assertTrue(refusalOf(".orders").contains(".orders"));
        assertTrue(refusalOf(null).contains("null"));
    }

    @Test
    void requireUnderResolvesAgainstTheDataRootNotTheWorkingDirectory() {
        Path root = Path.of("/spaces/default/data");
        assertEquals(Path.of("/spaces/default/data/orders"), DataRef.requireUnder(root, "orders", WHAT));
    }

    @Test
    void requireUnderNormalizesARedundantRootSoBothSidesShareOneFrame() {
        assertEquals(Path.of("/spaces/data/orders"),
                DataRef.requireUnder(Path.of("/spaces/./data"), "orders", WHAT),
                "an un-normalized root must not make a safe ref fail the containment check");
    }

    @Test
    void requireUnderAppliesTheShapeRuleToo() {
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> DataRef.requireUnder(Path.of("/data"), "../etc", WHAT))
                .getMessage().startsWith("unsafe " + WHAT));
    }

    @Test
    void requireUnderRefusesAMissingDataRootDistinctlyFromAnUnusableRef() {
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> DataRef.requireUnder(null, "orders", WHAT))
                .getMessage().contains("no data root"),
                "a view-backed space with no data dir is a different failure from a bad ref");
    }
}
