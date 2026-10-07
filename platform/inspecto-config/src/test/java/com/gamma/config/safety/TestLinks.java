package com.gamma.config.safety;

import java.io.IOException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Creates a filesystem link for the symlink-escape security tests.
 *
 * <p>⚠ <b>Why this exists.</b> {@link Files#createSymbolicLink} needs a privilege Windows does not
 * grant by default, and both suites here previously responded by calling {@code assumeTrue(false)} —
 * so on every developer box and in every recorded green baseline, the only tests covering the
 * real-path symlink re-check <b>silently did not run</b>. A skipped security test reads exactly like
 * a passing one in the summary line.
 *
 * <p>A <b>directory junction</b> needs no elevation and {@link Path#toRealPath()} resolves it
 * identically, so it is used as the fallback and the coverage is real again.
 */
final class TestLinks {

    private TestLinks() {}

    /** Link {@code link} → {@code target} (a directory), by symlink if permitted, else by junction. */
    static Path linkDirectory(Path link, Path target) throws IOException {
        try {
            return Files.createSymbolicLink(link, target);
        } catch (FileSystemException | UnsupportedOperationException e) {
            // no privilege — fall through to the junction
        }
        assumeTrue(System.getProperty("os.name", "").toLowerCase().startsWith("windows"),
                "no symlink privilege and no junction fallback on this OS");
        try {
            Process p = new ProcessBuilder("cmd", "/c", "mklink", "/J", link.toString(), target.toString())
                    .redirectErrorStream(true).start();
            assumeTrue(p.waitFor() == 0, "could not create a directory junction");
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new IOException(ie);
        }
        assumeTrue(Files.exists(link), "junction was reported created but is not present");
        return link;
    }
}
