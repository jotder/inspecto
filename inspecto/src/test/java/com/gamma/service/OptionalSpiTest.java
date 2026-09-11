package com.gamma.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link OptionalSpi} — the optional-module absence contract must survive a jar that is <b>present but
 * unloadable</b>, not only a jar that is missing.
 *
 * <p>The live case this exists for: the assistant modules' upstream dependency is Java 25 bytecode while
 * the product's floor is Java 24, so on an older host the staged jar links with
 * {@code UnsupportedClassVersionError}. That is a {@link LinkageError} — {@code ServiceLoader} does
 * <b>not</b> wrap it into a {@link ServiceConfigurationError}, it propagates raw — so before this class
 * existed the error escaped {@code CollectorService.start()} and took the whole boot down because an
 * OPTIONAL component could not load.
 *
 * <p>⚠ Every negative test here is paired with a CONTROL that would otherwise succeed: the same provider,
 * same class loader, same service declaration, differing only in the class-file version. Without the
 * control, "found nothing" could mean the test never wired a provider at all.
 */
class OptionalSpiTest {

    /** The SPI under test. Compiled into the test classpath, so the generated provider can implement it. */
    public interface Probe {
        String id();
    }

    private static final String PROVIDER_FQN = "com.gamma.service.GeneratedProbe";
    private static final String PROVIDER_SRC = """
            package com.gamma.service;
            public class GeneratedProbe implements OptionalSpiTest.Probe {
                public String id() { return "generated"; }
            }
            """;

    // ── the control: a provider that genuinely resolves ──────────────────────────────────

    @Test
    void aWellFormedProviderIsDiscovered(@TempDir Path dir) throws Exception {
        stageProvider(dir, false);
        withContextClassLoader(dir, () -> {
            List<Probe> found = OptionalSpi.all(Probe.class);
            assertEquals(1, found.size(), "the control must find the provider, or the negatives prove nothing");
            assertEquals("generated", found.get(0).id());
            assertEquals("generated", OptionalSpi.first(Probe.class).orElseThrow().id());
        });
    }

    // ── the case that broke the boot ─────────────────────────────────────────────────────

    @Test
    void aProviderCompiledForANewerJavaIsSkippedRatherThanThrown(@TempDir Path dir) throws Exception {
        stageProvider(dir, true);
        withContextClassLoader(dir, () -> {
            // 🔴 This is what the raw ServiceLoader does, and why start() died: the LinkageError escapes.
            assertThrows(LinkageError.class,
                    () -> ServiceLoader.load(Probe.class).findFirst(),
                    "if this stops throwing the mutation has changed, not the fix — re-check the probe");

            assertTrue(OptionalSpi.all(Probe.class).isEmpty(), "unloadable is an ABSENCE, not a failure");
            assertEquals(Optional.empty(), OptionalSpi.first(Probe.class));
        });
    }

    @Test
    void aProviderDeclaredButNotPresentIsSkippedRatherThanThrown(@TempDir Path dir) throws Exception {
        // No class file at all — ServiceLoader's own ServiceConfigurationError path, the other half.
        Files.createDirectories(dir.resolve("META-INF/services"));
        Files.writeString(dir.resolve("META-INF/services/" + Probe.class.getName()), PROVIDER_FQN + "\n");
        withContextClassLoader(dir, () -> {
            assertThrows(ServiceConfigurationError.class, () -> ServiceLoader.load(Probe.class).findFirst());
            assertTrue(OptionalSpi.all(Probe.class).isEmpty());
        });
    }

    // ── fixture ──────────────────────────────────────────────────────────────────────────

    /**
     * Compile {@link #PROVIDER_SRC} into {@code dir} and declare it as a {@link Probe} provider.
     *
     * @param tooNew when true, rewrite the class file's major version to 99 — a version no JVM that can
     *               run this test accepts — which reproduces the assistant's Java-25-on-Java-24 failure
     *               exactly, without needing a second JDK.
     */
    private static void stageProvider(Path dir, boolean tooNew) throws IOException {
        Path src = dir.resolve("GeneratedProbe.java");
        Files.writeString(src, PROVIDER_SRC);
        JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
        int rc = javac.run(null, null, null,
                "-cp", System.getProperty("java.class.path"), "-d", dir.toString(), src.toString());
        assertEquals(0, rc, "the fixture failed to compile — fix the fixture, not the assertion");

        Path clazz = dir.resolve("com/gamma/service/GeneratedProbe.class");
        if (tooNew) {
            byte[] b = Files.readAllBytes(clazz);
            b[6] = 0;      // major version, high byte
            b[7] = 99;     // major version, low byte — far beyond anything that can run this test
            Files.write(clazz, b);
        }
        Files.createDirectories(dir.resolve("META-INF/services"));
        Files.writeString(dir.resolve("META-INF/services/" + Probe.class.getName()), PROVIDER_FQN + "\n");
    }

    /** Run {@code body} with {@code dir} ahead of the test classpath, then restore the loader. */
    private static void withContextClassLoader(Path dir, ThrowingRunnable body) throws Exception {
        ClassLoader previous = Thread.currentThread().getContextClassLoader();
        try (URLClassLoader loader = new URLClassLoader(
                new URL[] {dir.toUri().toURL()}, OptionalSpiTest.class.getClassLoader())) {
            Thread.currentThread().setContextClassLoader(loader);
            body.run();
        } finally {
            Thread.currentThread().setContextClassLoader(previous);
        }
    }

    private interface ThrowingRunnable {
        void run() throws Exception;
    }
}
