package com.gamma.etl;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Every shipped Pipeline loads no matter which directory the process was launched from
 * ({@code SCHEMA-FILE-RESOLVES-AGAINST-CWD-1}).
 *
 * <p>The defect: the shipped configs spelled their satellite refs from the <b>server root</b>
 * ({@code schema_file: spaces/demo/config/orders/orders_schema.toon}), and the loader fell back to
 * resolving that against the process working directory. A bundle launched from {@code inspecto-deploy/}
 * with {@code -Dspaces.root=..\spaces} therefore looked for {@code inspecto-deploy\spaces\demo\…}, and
 * every demo Pipeline failed to register.
 *
 * <p>⚠ No CWD trickery is needed to reproduce that: surefire's working directory is the MODULE directory
 * ({@code inspecto-etl/}), not the repo root — exactly the "launched from somewhere else" condition. Each
 * space is copied into a temp dir (so the proof is also that it RELOCATES) and each config loaded by its
 * absolute path, so the only thing that can make a ref miss is a ref that means something relative to the
 * CWD.
 *
 * <p>The copy is VERBATIM. Loading creates each Pipeline's status dir, and a data path resolves under the
 * Space directory ({@code DATA-DIRS-RESOLVE-AGAINST-CWD-1}), so it lands inside the temp copy; until that
 * row the copy had to re-point every {@code spaces/<space>/data/…} path, or the sweep littered the module dir
 * with a {@code spaces/} tree that other tests then mistook for the repo root. A config ref still spelled
 * {@code spaces/<space>/config/…} would fail here, which is the point.
 */
class ShippedPipelinesLoadFromAnyWorkingDirectoryTest {

    /** Runtime state (gitignored) — not shipped. */
    private static final Set<String> NOT_SHIPPED_SPACES = Set.of("_shared", "uat");

    private static Path spacesRoot() {
        return Path.of("..", "spaces").toAbsolutePath().normalize();
    }

    @Test
    void theWorkingDirectoryIsNotTheRepoRoot() {
        // The premise of the sweep below. If surefire ever ran from the repo root, the old CWD fallback
        // would satisfy every legacy ref and the sweep would pass vacuously.
        assertFalse(Files.isDirectory(Path.of("spaces", "default", "config")),
                "the test JVM's CWD must not hold the shipped spaces — otherwise a CWD-relative ref still resolves");
    }

    @Test
    void everyShippedPipelineLoadsFromARelocatedCopy(@TempDir Path tmp) throws Exception {
        List<Path> pipelines = new ArrayList<>();
        try (Stream<Path> spaces = Files.list(spacesRoot())) {
            for (Path space : spaces.filter(Files::isDirectory).toList()) {
                String name = space.getFileName().toString();
                if (NOT_SHIPPED_SPACES.contains(name)) continue;
                pipelines.addAll(copySpace(space, tmp.resolve(name)));
            }
        }
        assertTrue(pipelines.size() >= 30, "found only " + pipelines.size()
                + " *_pipeline.toon under " + spacesRoot() + " (32 when this landed) — the walk is broken");
        List<String> failures = new ArrayList<>();
        for (Path p : pipelines) {
            try {
                PipelineConfig.load(p.toString());
            } catch (Exception | Error e) {
                failures.add(tmp.relativize(p) + ": " + e);
            }
        }
        assertTrue(failures.isEmpty(), failures.size() + " of " + pipelines.size()
                + " shipped Pipelines do not load outside the repo root:\n" + String.join("\n", failures));
    }

    /** Copy one space's {@code config/} tree verbatim; returns the copied pipelines. */
    private static List<Path> copySpace(Path space, Path into) throws IOException {
        Path config = space.resolve("config");
        List<Path> out = new ArrayList<>();
        if (!Files.isDirectory(config)) return out;
        try (Stream<Path> all = Files.walk(config)) {
            for (Path f : all.filter(Files::isRegularFile).toList()) {
                Path dest = into.resolve(space.relativize(f));
                Files.createDirectories(dest.getParent());
                Files.copy(f, dest);
                if (f.getFileName().toString().endsWith("_pipeline.toon")) out.add(dest);
            }
        }
        return out;
    }
}
