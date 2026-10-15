package com.gamma.la.api;

import com.gamma.entitystore.LaLimit;
import com.gamma.entitystore.LaLimits;
import com.gamma.sql.SqlSandboxPolicy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.EnumMap;
import java.util.Map;

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
        System.clearProperty("assist.sql.memory_limit");
        LaLimits.installDocPath(null);
    }

    /** LA-CONFIG-LIMITS-1: the install-wide traversal.threads / traversal.memory_limit_mb win over the properties, read per call. */
    @Test
    void theInstallWideValuesWinOverThePropertiesAndApplyAtTheNextCall(@TempDir Path dir) throws Exception {
        System.setProperty(TRAVERSAL, "7");
        System.setProperty("assist.sql.memory_limit", "3GB");
        Path doc = dir.resolve(LaLimits.INSTALL_FILE);
        LaLimits.installDocPath(doc);
        assertEquals(7, InvRoutes.traversalPolicy().maxThreads(), "no document value: the property");
        assertEquals("3GB", InvRoutes.traversalPolicy().memoryLimit());
        Map<LaLimit, Number> set = new EnumMap<>(LaLimit.class);
        set.put(LaLimit.TRAVERSAL_THREADS, 2L);
        set.put(LaLimit.TRAVERSAL_MEMORY_LIMIT_MB, 512L);
        LaLimits.writeInstall(doc, set);
        assertEquals(2, InvRoutes.traversalPolicy().maxThreads(), "the document value wins");
        assertEquals("512MiB", InvRoutes.traversalPolicy().memoryLimit());
        set.put(LaLimit.TRAVERSAL_THREADS, null);
        LaLimits.writeInstall(doc, set);
        assertEquals(7, InvRoutes.traversalPolicy().maxThreads(), "cleared: back to the property");
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
