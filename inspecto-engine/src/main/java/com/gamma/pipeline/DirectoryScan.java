package com.gamma.pipeline;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * The one "scan a config directory, tolerate what cannot be read" walk for this package (JAVA-5).
 *
 * <p>{@link ComponentRegistry}, {@link PipelineStore} and {@link ViewStore} each carried the same
 * five-line walk: list the directory, keep regular files with the right suffix, sort for a stable
 * order, hand each to a loader, and degrade to a warning if the directory itself cannot be read.
 *
 * <p>The tolerance is the load-bearing part. A store that throws on an unreadable directory takes
 * the whole console down with it; a store that silently returns nothing hides a misconfigured root.
 * Warn-and-continue is the deliberate middle, and it is worth having in one place so a fourth store
 * cannot pick a different one by accident.
 *
 * <p>⚠ Per-file failure is NOT handled here — each caller's action decides whether a corrupt file is
 * skipped with a warning or omitted silently, because that answer differs by store.
 */
final class DirectoryScan {

    private static final Logger log = LoggerFactory.getLogger(DirectoryScan.class);

    private DirectoryScan() {}

    /**
     * Hand every {@code suffix}-named regular file directly in {@code dir} to {@code action}, in name
     * order. An unreadable directory logs one warning naming {@code what} and yields nothing.
     */
    static void forEachFile(Path dir, String suffix, String what, Consumer<Path> action) {
        try (Stream<Path> files = Files.list(dir)) {
            files.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(suffix))
                    .sorted()
                    .forEach(action);
        } catch (IOException e) {
            log.warn("Cannot scan {} dir {}: {}", what, dir, e.getMessage());
        }
    }
}
