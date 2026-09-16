package com.gamma.config.safety;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A job's relative path resolves against the <b>Space config root</b>, not the process working directory
 * (`JOB-DIR-CWD-CONTAINMENT-1`, operator 2026-09-16).
 *
 * <p>⛔ The rule lives in ONE place because the 422 write gate and the run-time tasks both call it. Before
 * this, {@code ConfigSafetyValidator} and {@code PathJail.require} both resolved against the CWD — they
 * agreed, which is why this was a semantics choice and not a bug. Moving only one would have passed a draft
 * the run then jails.
 */
class JobPathResolutionTest {

    @Test
    void anAbsolutePathIsUnchanged(@TempDir Path space, @TempDir Path elsewhere) {
        Path abs = elsewhere.resolve("data");
        assertEquals(abs.toAbsolutePath().normalize(),
                PathJail.resolveJobPath(space, abs.toString(), "job.dir"));
    }

    @Test
    void aRelativePathResolvesAgainstTheSpaceRoot(@TempDir Path space) throws Exception {
        Files.createDirectories(space.resolve("inbox"));

        assertEquals(space.toAbsolutePath().normalize().resolve("inbox"),
                PathJail.resolveJobPath(space, "inbox", "job.dir"));
    }

    /** With no Space, the legacy working-directory behaviour stands — the job runner outside a Space. */
    @Test
    void aNullSpaceRootKeepsTheLegacyBehaviour() {
        assertEquals(Path.of("inbox").toAbsolutePath().normalize(),
                PathJail.resolveJobPath(null, "inbox", "job.dir"));
    }

    /**
     * ⛔ THE CASE THE OPERATOR CHOSE TO REFUSE. The value means something different than it used to; rather
     * than silently reading somewhere else, it throws and names BOTH paths so the change is fixed
     * deliberately. A fallback here would be the bug, not the kindness.
     */
    @Test
    void theAmbiguousCaseIsRefusedAndNamesBothPaths(@TempDir Path space, @TempDir Path cwdSide) throws Exception {
        // exists where the OLD rule would have looked, absent under the Space root
        Path authored = cwdSide.resolve("legacy-data");
        Files.createDirectories(authored);
        Path relativeToCwd = Path.of("").toAbsolutePath().relativize(authored);

        PathJail.Escape e = assertThrows(PathJail.Escape.class,
                () -> PathJail.resolveJobPath(space, relativeToCwd.toString(), "job.dir"));

        assertTrue(e.getMessage().contains("Space config root"), e.getMessage());
        assertTrue(e.getMessage().contains(authored.toAbsolutePath().normalize().toString()),
                "the message must name the path the job USED to mean: " + e.getMessage());
        assertTrue(e.getMessage().contains("not resolved silently"), e.getMessage());
    }

    /**
     * ⚠ …but only when it is genuinely ambiguous. A relative path that exists in NEITHER place resolves
     * against the Space root and is then judged by the jail like any other — refusing it here would make
     * every not-yet-created directory unauthorable.
     */
    @Test
    void aPathThatExistsNowhereResolvesAgainstTheSpaceRoot(@TempDir Path space) {
        assertEquals(space.toAbsolutePath().normalize().resolve("not-created-yet"),
                PathJail.resolveJobPath(space, "not-created-yet", "job.dir"));
    }

    @Test
    void aBlankValueIsRefused(@TempDir Path space) {
        assertThrows(PathJail.Escape.class, () -> PathJail.resolveJobPath(space, "  ", "job.dir"));
    }
}
