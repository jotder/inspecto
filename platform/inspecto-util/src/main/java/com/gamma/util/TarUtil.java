package com.gamma.util;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

/**
 * Recursive directory cleanup.
 *
 * <p>This class once held the tar/gzip helpers of the retired URA pre-ETL CLI (2026-11-06); only
 * {@link #deleteTree} still has callers.
 */
public final class TarUtil {

    private TarUtil() {}

    /**
     * Recursively delete {@code dir} and all its contents (bottom-up walk).
     *
     * <p>Failures are logged to {@link System#err} but do not propagate — a locked
     * file on Windows (e.g. an active AV scan) is non-fatal; the directory will be
     * cleaned on the next run.
     */
    public static void deleteTree(Path dir) {
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException e) {
                    System.err.printf("[WARN] Could not delete %s: %s%n", p, e.getMessage());
                }
            });
        } catch (IOException e) {
            System.err.printf("[WARN] deleteTree failed for %s: %s%n", dir, e.getMessage());
        }
    }
}
