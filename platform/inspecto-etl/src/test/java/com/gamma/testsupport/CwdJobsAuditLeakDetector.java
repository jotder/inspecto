package com.gamma.testsupport;

import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

/**
 * Fails a test class that creates the legacy flat-layout {@code jobs_audit/} directory in the surefire working
 * directory (the module dir). A {@code CollectorService} over {@code SpaceRoot.legacy()} resolves its job audit dir
 * CWD-relative unless {@code -Djobs.audit.dir} is set, so a test that forgets to point it at its {@code @TempDir}
 * leaves an untracked tree behind. The offending tree is removed after the failure so the next writer is caught
 * too. Auto-registered through {@code META-INF/services} beside {@link EventLogLeakDetector}.
 *
 * <p>Checking alone was order-dependent: only the class that FIRST created the tree failed, so on Linux CI two more
 * writers surfaced that a Windows run never flagged. So the detector also PINS {@code jobs.audit.dir} to a per-class
 * temp dir whenever the class has not set it, and clears any leftover tree before the class — every class is judged
 * on its own, and a class that clears the property itself is still caught.
 */
public final class CwdJobsAuditLeakDetector implements BeforeAllCallback, AfterAllCallback {

    private static final ExtensionContext.Namespace NS = ExtensionContext.Namespace.create(CwdJobsAuditLeakDetector.class);
    private static final Path DIR = Path.of("jobs_audit").toAbsolutePath();
    private static final String PROP = "jobs.audit.dir";

    @Override
    public void beforeAll(ExtensionContext ctx) {
        if (ctx.getTestClass().map(c -> c.getEnclosingClass() != null).orElse(true)) return;   // top-level only
        if (Files.exists(DIR)) deleteTree();   // a leftover from an earlier class must not mask this one
        ctx.getStore(NS).put("absent", true);
        if (System.getProperty(PROP) == null) {
            try {
                Path pinned = Files.createTempDirectory("jobs-audit-");
                System.setProperty(PROP, pinned.toString());
                ctx.getStore(NS).put("pinned", pinned);
            } catch (IOException e) {
                throw new IllegalStateException("could not pin " + PROP, e);
            }
        }
    }

    @Override
    public void afterAll(ExtensionContext ctx) {
        Path pinned = ctx.getStore(NS).get("pinned", Path.class);
        if (pinned != null) {
            if (pinned.toString().equals(System.getProperty(PROP))) System.clearProperty(PROP);
            deleteTree(pinned);
        }
        if (!Boolean.TRUE.equals(ctx.getStore(NS).get("absent", Boolean.class)) || !Files.exists(DIR)) return;
        deleteTree();
        throw new AssertionError("CWD jobs_audit LEAK: " + ctx.getRequiredTestClass().getName() + " created " + DIR
                + " — set -Djobs.audit.dir to a @TempDir (or root the service under SpaceRoot.under(tmp))");
    }

    private static void deleteTree() {
        deleteTree(DIR);
    }

    private static void deleteTree(Path root) {
        try (Stream<Path> s = Files.walk(root)) {
            s.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        } catch (IOException ignored) {
            // best effort — the assertion is the signal
        }
    }
}
