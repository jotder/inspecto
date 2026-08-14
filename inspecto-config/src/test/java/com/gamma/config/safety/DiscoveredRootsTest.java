package com.gamma.config.safety;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pins the allowed-roots union (PATH-2 tier 3): operator-declared property + every registered space
 * base — and the fail-closed posture underneath it, which until 2026-08-14 did not actually hold: the
 * record constructor silently substituted the CWD for an empty list, granting the server's working
 * directory to every containment check on an unconfigured deployment.
 *
 * <p>⚠ The property and the registry are both process-global, so every test here saves and restores
 * {@code assist.safety.roots} (surefire sets it reactor-wide) and clears the registry — leaking either
 * would flip containment verdicts in unrelated tests.
 */
class DiscoveredRootsTest {

    private String savedProp;

    @BeforeEach
    void save() {
        savedProp = System.getProperty("assist.safety.roots");
        DiscoveredRoots.clear();
    }

    @AfterEach
    void restore() {
        if (savedProp == null) System.clearProperty("assist.safety.roots");
        else System.setProperty("assist.safety.roots", savedProp);
        DiscoveredRoots.clear();
    }

    @Test
    void unsetPropertyAndNoSpacesYieldsAnEmptyRootListNotTheCwd() {
        System.clearProperty("assist.safety.roots");
        List<Path> roots = SafetyPolicy.defaultPolicy().allowedRoots();
        assertTrue(roots.isEmpty(),
                "an unconfigured deployment must fail closed, not silently allow the working directory: " + roots);
    }

    @Test
    void aRegisteredSpaceBaseJoinsTheUnionOnTheNextCallNoRestart() {
        System.clearProperty("assist.safety.roots");
        Path base = Path.of("spaces", "acme").toAbsolutePath();
        DiscoveredRoots.register(base);
        assertEquals(List.of(base.normalize()), SafetyPolicy.defaultPolicy().allowedRoots());
    }

    @Test
    void theDeclaredListAndTheRegistrySumRatherThanShadow() {
        Path declared = Path.of("mnt", "backups").toAbsolutePath().normalize();
        System.setProperty("assist.safety.roots", declared.toString());
        Path base = Path.of("spaces", "acme").toAbsolutePath().normalize();
        DiscoveredRoots.register(base);
        List<Path> roots = SafetyPolicy.defaultPolicy().allowedRoots();
        assertTrue(roots.contains(declared), "an out-of-layout destination stays declared: " + roots);
        assertTrue(roots.contains(base), "a hosted space base derives: " + roots);
    }

    @Test
    void anUnregisteredBaseLeavesTheUnion() {
        System.clearProperty("assist.safety.roots");
        Path base = Path.of("spaces", "acme").toAbsolutePath();
        DiscoveredRoots.register(base);
        DiscoveredRoots.unregister(base);
        assertTrue(SafetyPolicy.defaultPolicy().allowedRoots().isEmpty(),
                "the root set must not only ever grow within a process lifetime");
    }

    @Test
    void registrationKeysOnTheNormalisedBaseSoARedundantSpellingIsIdempotent() {
        System.clearProperty("assist.safety.roots");
        DiscoveredRoots.register(Path.of("spaces", ".", "acme").toAbsolutePath());
        DiscoveredRoots.register(Path.of("spaces", "acme").toAbsolutePath());
        assertEquals(1, SafetyPolicy.defaultPolicy().allowedRoots().size());
    }

    @Test
    void withRootsStillBypassesTheRegistry() {
        DiscoveredRoots.register(Path.of("spaces", "acme").toAbsolutePath());
        Path only = Path.of("workspace").toAbsolutePath().normalize();
        assertEquals(List.of(only), SafetyPolicy.withRoots(only).allowedRoots(),
                "an explicit policy (skill workspace, tests) is scoped, not unioned");
    }
}
