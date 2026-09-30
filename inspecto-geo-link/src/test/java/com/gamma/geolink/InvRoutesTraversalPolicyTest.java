package com.gamma.geolink;

import com.gamma.sql.SqlSandboxPolicy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The recursive-paths walk runs under {@code assist.sql.traversal_threads} (default 4); every other sandboxed
 * query keeps {@code assist.sql.threads} (default 2). Operator decision 2026-09-30 (G-R4).
 */
class InvRoutesTraversalPolicyTest {

    private static final String THREADS = "assist.sql.threads";
    private static final String TRAVERSAL = "assist.sql.traversal_threads";

    @AfterEach
    void clear() {
        System.clearProperty(THREADS);
        System.clearProperty(TRAVERSAL);
    }

    @Test
    void defaultsAreFourForTraversalAndTwoElsewhere() {
        assertEquals(4, InvRoutes.traversalPolicy().maxThreads());
        assertEquals(2, SqlSandboxPolicy.defaultPolicy().maxThreads());
    }

    @Test
    void traversalUsesTraversalSettingAndOtherQueriesDoNot() {
        System.setProperty(THREADS, "3");
        System.setProperty(TRAVERSAL, "7");
        assertEquals(7, InvRoutes.traversalPolicy().maxThreads());
        // positive twin: the general sandbox policy ignores the traversal setting
        assertEquals(3, SqlSandboxPolicy.defaultPolicy().maxThreads());
    }

    @Test
    void generalThreadSettingDoesNotReachTraversal() {
        System.setProperty(THREADS, "9");
        assertEquals(4, InvRoutes.traversalPolicy().maxThreads());
    }

    @Test
    void invalidTraversalSettingFallsBackToDefault() {
        System.setProperty(TRAVERSAL, "abc");
        assertEquals(4, InvRoutes.traversalPolicy().maxThreads());
        System.setProperty(TRAVERSAL, "0");
        assertEquals(4, InvRoutes.traversalPolicy().maxThreads());
        System.setProperty(TRAVERSAL, "-2");
        assertEquals(4, InvRoutes.traversalPolicy().maxThreads());
    }
}
