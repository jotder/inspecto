package com.gamma.config.safety;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A config's relative DATA path resolves under its <b>Space directory</b>, never the process working directory
 * ({@code DATA-DIRS-RESOLVE-AGAINST-CWD-1}, operator decision 2026-09-23) — through the one resolver the loader
 * and the 422 gate both call, {@link PathJail#resolveDataPath}.
 */
class DataPathResolutionTest {

    @Test
    void theSpaceDirIsTheParentOfTheNearestConfigDirectory(@TempDir Path tmp) {
        Path space = tmp.resolve("spaces/demo");
        assertEquals(space, PathJail.spaceDirOf(space.resolve("config/orders")));
        assertEquals(space, PathJail.spaceDirOf(space.resolve("config")));
        // the NEAREST config wins, so a Space under an unrelated …/config/… still resolves to its own base
        assertEquals(tmp.resolve("config/spaces/x"), PathJail.spaceDirOf(tmp.resolve("config/spaces/x/config/a")));
        assertNull(PathJail.spaceDirOf(tmp.resolve("examples/07-steps/filter")), "a single-tenant example has no Space");
        assertNull(PathJail.spaceDirOf(null), "nor does an in-memory draft");
    }

    @Test
    void aRelativeDataPathResolvesUnderTheSpaceDirNotBesideTheConfig(@TempDir Path tmp) {
        Path space = tmp.resolve("spaces/demo").toAbsolutePath().normalize();
        assertEquals(space.resolve("data/orders/database"),
                PathJail.resolveDataPath(space.resolve("config/orders"), "data/orders/database", "dirs.database"));
    }

    @Test
    void anAbsoluteDataPathIsUnchanged(@TempDir Path tmp, @TempDir Path elsewhere) {
        Path abs = elsewhere.resolve("nas/inbox").toAbsolutePath().normalize();
        assertEquals(abs, PathJail.resolveDataPath(tmp.resolve("config"), abs.toString(), "dirs.poll"));
    }

    /** No Space: the working-directory reading stands — the single-tenant equivalent of the Space dir. */
    @Test
    void withNoSpaceTheWorkingDirectoryReadingStands(@TempDir Path tmp) {
        Path example = tmp.resolve("examples/07-steps/filter");
        assertEquals(Path.of("out/inbox").toAbsolutePath().normalize(),
                PathJail.resolveDataPath(example, "out/inbox", "dirs.poll"));
        assertEquals("out/inbox", PathJail.dataPath(example, "out/inbox", "dirs.poll"),
                "a reader keeps the authored string — relative IS the working-directory reading");
    }

    @Test
    void aUriIsLeftToContainmentToRefuse(@TempDir Path tmp) {
        assertEquals("s3://lake/orders", PathJail.dataPath(tmp.resolve("config"), "s3://lake/orders", "data_path"));
    }

    /**
     * ⛔ The ambiguous case is REFUSED, naming both paths — a config still spelled from the server root
     * ({@code spaces/demo/data/…}) launched from that root must not silently start writing somewhere new.
     * The probe is a plain CWD-relative name (see {@code JobPathResolutionTest} for why it must be).
     */
    @Test
    void theAmbiguousCaseIsRefusedAndNamesBothPaths(@TempDir Path tmp) throws Exception {
        Path cwd = Path.of("").toAbsolutePath().normalize();
        Path authored = cwd.resolve("target").resolve("data-path-probe-" + UUID.randomUUID());
        Files.createDirectories(authored);
        try {
            String relativeToCwd = "target/" + authored.getFileName();
            Path configDir = tmp.resolve("spaces/demo/config/orders");
            PathJail.Escape e = assertThrows(PathJail.Escape.class,
                    () -> PathJail.resolveDataPath(configDir, relativeToCwd, "dirs.database"));
            assertTrue(e.getMessage().contains("under its Space directory"), e.getMessage());
            assertTrue(e.getMessage().contains(authored.toString()), "names the path it USED to mean: " + e.getMessage());
            assertTrue(e.getMessage().contains(tmp.resolve("spaces/demo").toAbsolutePath().normalize()
                    .resolve(relativeToCwd).normalize().toString()), "and the one it means now: " + e.getMessage());
        } finally {
            Files.deleteIfExists(authored);
        }
    }

    /**
     * {@code DATA-PATH-RESIDUALS-1} (a): the pre-2026-09-23 server-root spelling, loaded where that CWD path does
     * NOT exist (so the ambiguity refusal cannot fire), used to resolve silently to a doubled
     * {@code spaces/demo/spaces/demo/data/…}. It is refused by name instead — by the loader's resolver and the gate.
     */
    @Test
    void aValueThatRepeatsTheSpacesOwnPathIsRefusedNotDoubled(@TempDir Path tmp) {
        Path space = tmp.resolve("spaces/demo").toAbsolutePath().normalize();
        Path configDir = space.resolve("config/orders");
        String old = "spaces/demo/data/orders/database";
        assertFalse(Files.exists(Path.of(old).toAbsolutePath()), "probe precondition: the old CWD path is absent");

        PathJail.Escape e = assertThrows(PathJail.Escape.class,
                () -> PathJail.resolveDataPath(configDir, old, "dirs.database"));
        assertTrue(e.getMessage().contains("repeats its own Space's path ('spaces/demo')"), e.getMessage());
        assertThrows(PathJail.Escape.class, () -> PathJail.dataPath(configDir, old, "dirs.database"),
                "the reader form refuses too");

        List<com.gamma.config.spec.Finding> findings = ConfigSafetyValidator.check("pipeline",
                Map.of("dirs", Map.of("database", old)), SafetyPolicy.withRoots(space), configDir);
        assertFalse(findings.isEmpty(), "the 422 gate refuses what the loader refuses");

        // ONE repeated segment is not the old spelling — a Space named `data` keeps its `data/…` paths.
        Path dataSpace = tmp.resolve("spaces/data").toAbsolutePath().normalize();
        assertEquals(dataSpace.resolve("data/orders"),
                PathJail.resolveDataPath(dataSpace.resolve("config"), "data/orders", "dirs.poll"));
    }

    /** The 422 gate resolves a data path exactly as the loader does — under the Space dir, then jails it. */
    @Test
    void theWriteGateJudgesADataPathFromTheSpaceDir(@TempDir Path tmp) {
        Path space = tmp.resolve("spaces/demo").toAbsolutePath().normalize();
        SafetyPolicy roots = SafetyPolicy.withRoots(space);
        Map<String, Object> raw = Map.of("dirs", Map.of("poll", "data/inbox/orders", "database", "data/orders/database"));

        assertEquals(List.of(), ConfigSafetyValidator.check("pipeline", raw, roots, space.resolve("config/orders")),
                "Space-relative data paths are inside the Space's root");
        assertFalse(ConfigSafetyValidator.check("pipeline", raw, roots, null).isEmpty(),
                "with no home they read from the CWD, which is outside that root");
    }
}
