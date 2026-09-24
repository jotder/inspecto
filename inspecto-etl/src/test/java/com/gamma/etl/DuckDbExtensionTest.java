package com.gamma.etl;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code AIRGAP-EXTENSIONS-1}: the extension loader must reach {@code INSTALL} — the only step that
 * egresses — <b>last</b>, and must not reach it at all when the deployment staged the binary.
 *
 * <p>These assertions are about <b>statement order</b>, not about DuckDB, so they run against a recording
 * JDBC proxy rather than a real connection. That is deliberate on two counts: the test is hermetic (a
 * suite that proves "no network fetch happens" must not itself need a network to pass), and it can assert
 * a <em>negative</em> — that no {@code INSTALL} was ever issued — which a live connection cannot show,
 * because a machine with the extension already cached passes either way. ⚠ A live-connection test here
 * would be the {@code EgressGuardTest} mistake in miniature: green because the environment was friendly.
 *
 * <p>Measured 2026-09-11 against duckdb_jdbc 1.5.2.1, and the reason step 1 is safe to try first: a bare
 * {@code LOAD excel} with the extension absent fails in ~1 ms with <i>"Install it first"</i> and does
 * <b>not</b> auto-install, whatever {@code autoinstall_known_extensions} says — that setting governs
 * autoloading, not an explicit {@code LOAD}.
 */
class DuckDbExtensionTest {

    /** Records every statement executed, and fails the ones the scenario says are unavailable. */
    private static final class Recorder implements InvocationHandler {
        final List<String> executed = new ArrayList<>();
        private final Predicate<String> unavailable;

        Recorder(Predicate<String> unavailable) {
            this.unavailable = unavailable;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            switch (method.getName()) {
                case "createStatement" -> {
                    return Proxy.newProxyInstance(getClass().getClassLoader(),
                            new Class<?>[]{Statement.class}, this);
                }
                case "execute" -> {
                    String sql = (String) args[0];
                    executed.add(sql);
                    if (unavailable.test(sql)) throw new SQLException("not available here: " + sql);
                    return Boolean.FALSE;
                }
                case "close" -> {
                    return null;
                }
                case "toString" -> {
                    return "recording-connection";
                }
                case "hashCode" -> {
                    return System.identityHashCode(proxy);
                }
                case "equals" -> {
                    return proxy == args[0];
                }
                default -> throw new UnsupportedOperationException(method.getName());
            }
        }

        Connection connection() {
            return (Connection) Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[]{Connection.class}, this);
        }

        boolean issuedInstall() {
            return executed.stream().anyMatch(s -> s.startsWith("INSTALL"));
        }
    }

    /** Run {@code body} with {@code -Dduckdb.extension.dir} set to {@code dir} ({@code null} = unset). */
    private static void withExtensionDir(String dir, Runnable body) {
        String previous = System.getProperty(DuckDbExtension.DIR_PROPERTY);
        try {
            if (dir == null) System.clearProperty(DuckDbExtension.DIR_PROPERTY);
            else System.setProperty(DuckDbExtension.DIR_PROPERTY, dir);
            body.run();
        } finally {
            if (previous == null) System.clearProperty(DuckDbExtension.DIR_PROPERTY);
            else System.setProperty(DuckDbExtension.DIR_PROPERTY, previous);
        }
    }

    /** The air-gapped deployment: the file is staged, so the network step must never be reached. */
    @Test
    void aStagedExtensionIsLoadedFromTheFileAndNeverInstalled(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("ducklake.duckdb_extension"), "not a real binary, only its name matters");
        Recorder rec = new Recorder(sql -> sql.equals("LOAD ducklake"));   // nothing cached on this host

        withExtensionDir(dir.toString(), () ->
                assertTrue(DuckDbExtension.tryLoad(rec.connection(), "ducklake")));

        assertFalse(rec.issuedInstall(),
                "an air-gapped install with the extension staged must never reach INSTALL — that is the "
                        + "whole claim. Executed: " + rec.executed);
        assertEquals(1, rec.executed.size(),
                "staged = ONE statement, the load by path — no bare LOAD that could pick up ~/.duckdb: "
                        + rec.executed);
        assertTrue(rec.executed.get(0).startsWith("LOAD '"), "the staged file must be loaded by path: " + rec.executed);
        assertTrue(rec.executed.get(0).contains("ducklake.duckdb_extension"));
    }

    /**
     * D-8: the staged file wins even when a warm {@code ~/.duckdb} cache WOULD satisfy {@code LOAD <name>} —
     * otherwise a bundle missing its binary passes on the build box and fails on the customer's.
     */
    @Test
    void aStagedDirectoryIsPreferredOverAWarmCache(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("excel.duckdb_extension"), "stub");
        Recorder rec = new Recorder(sql -> false);            // a bare LOAD excel would succeed here too
        withExtensionDir(dir.toString(), () -> assertTrue(ExcelExtension.tryLoad(rec.connection())));
        assertEquals(1, rec.executed.size(), rec.executed.toString());
        assertTrue(rec.executed.get(0).startsWith("LOAD '"), rec.executed.toString());
    }

    /** ...and the same for excel, so the two callers cannot drift apart again. */
    @Test
    void excelTakesTheIdenticalPathThroughTheSharedLoader(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("excel.duckdb_extension"), "stub");
        Recorder rec = new Recorder(sql -> sql.equals("LOAD excel"));

        withExtensionDir(dir.toString(), () -> assertTrue(ExcelExtension.tryLoad(rec.connection())));

        assertFalse(rec.issuedInstall(), "executed: " + rec.executed);
        assertEquals(DuckDbExtension.DIR_PROPERTY, ExcelExtension.DIR_PROPERTY,
                "one flag, quoted from two places — they must stay the same string");
    }

    /** A cached extension short-circuits: one statement, no directory lookup, no INSTALL. */
    @Test
    void anAlreadyCachedExtensionLoadsByNameAlone() {
        Recorder rec = new Recorder(sql -> false);            // everything succeeds
        withExtensionDir(null, () -> assertTrue(DuckDbExtension.tryLoad(rec.connection(), "ducklake")));
        assertEquals(List.of("LOAD ducklake"), rec.executed);
    }

    /** Fails only the FIRST {@code LOAD <name>}, so the post-INSTALL one succeeds — a fresh networked host. */
    private static Predicate<String> uncachedUntilInstalled(String name) {
        return new Predicate<>() {
            private boolean firstLoadSeen;

            @Override
            public boolean test(String sql) {
                if (!sql.equals("LOAD " + name)) return false;
                if (firstLoadSeen) return false;              // the post-INSTALL LOAD succeeds
                firstLoadSeen = true;
                return true;
            }
        };
    }

    /** The networked deployment is unchanged: with nothing staged, INSTALL is still reached. */
    @Test
    void aNetworkedDeploymentStillFallsThroughToInstall() {
        Recorder rec = new Recorder(uncachedUntilInstalled("ducklake"));
        withExtensionDir(null, () -> assertTrue(DuckDbExtension.tryLoad(rec.connection(), "ducklake")));
        assertEquals(List.of("LOAD ducklake", "INSTALL ducklake", "LOAD ducklake"), rec.executed,
                "removing the fallback would break every networked deployment — this is not dead code");
    }

    /**
     * D-8 (2026-09-24, reversing the old fall-through): a set directory WITHOUT the file fails loudly, names
     * the exact path, and issues NO statement at all — in particular no INSTALL. The flag is the bundle's
     * declaration that it carries its extensions; a missing one is a packaging defect, not a cue to egress.
     */
    @Test
    void aStagedDirectoryWithoutTheFileFailsLoudlyAndNeverInstalls(@TempDir Path dir) {
        Recorder rec = new Recorder(uncachedUntilInstalled("ducklake"));
        String[] message = new String[1];
        withExtensionDir(dir.toString(), () -> message[0] = assertThrows(SQLException.class,
                () -> DuckDbExtension.ensureLoaded(rec.connection(), "ducklake", "output.ducklake.enabled"))
                .getMessage());
        assertEquals(List.of(), rec.executed, "nothing may be executed, least of all INSTALL");
        assertTrue(message[0].contains(dir.resolve("ducklake.duckdb_extension").toAbsolutePath().toString()),
                "the failure must name the missing file: " + message[0]);
        assertTrue(message[0].contains("output.ducklake.enabled"), message[0]);
    }

    /** When every layer fails, the caller gets a message naming all three remedies — never a silent skip. */
    @Test
    void aTotalFailureNamesEveryRemedy() {
        Recorder rec = new Recorder(sql -> true);             // nothing works at all
        SQLException thrown = assertThrows(SQLException.class,
                () -> DuckDbExtension.ensureLoaded(rec.connection(), "ducklake", "output.ducklake.enabled"));
        assertTrue(thrown.getMessage().contains("output.ducklake.enabled"),
                "the failure must say what the caller needed it for: " + thrown.getMessage());
        assertTrue(thrown.getMessage().contains(DuckDbExtension.DIR_PROPERTY), thrown.getMessage());
    }
}
