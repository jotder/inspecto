package com.gamma.config.safety;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pins a Space's allowed roots (PATH-2 tier 3, narrowed by {@code CROSS-SPACE-JAIL-1}): operator-declared
 * property + <b>that Space's own</b> registered base — never another Space's — and the fail-closed posture
 * underneath it, which until 2026-08-14 did not actually hold: the record constructor silently
 * substituted the CWD for an empty list, granting the server's working directory to every containment
 * check on an unconfigured deployment.
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
    void aRegisteredSpaceBaseIsItsOwnRootOnTheNextCallNoRestart() {
        System.clearProperty("assist.safety.roots");
        Path base = Path.of("spaces", "acme").toAbsolutePath();
        DiscoveredRoots.register("acme", base);
        assertEquals(List.of(base.normalize()), SafetyPolicy.forSpace("acme").allowedRoots());
    }

    @Test
    void theDeclaredListAndTheSpaceBaseSumRatherThanShadow() {
        Path declared = Path.of("mnt", "backups").toAbsolutePath().normalize();
        System.setProperty("assist.safety.roots", declared.toString());
        Path base = Path.of("spaces", "acme").toAbsolutePath().normalize();
        DiscoveredRoots.register("acme", base);
        List<Path> roots = SafetyPolicy.forSpace("acme").allowedRoots();
        assertTrue(roots.contains(declared), "an out-of-layout destination stays declared: " + roots);
        assertTrue(roots.contains(base), "the Space's own base derives: " + roots);
    }

    /**
     * {@code CROSS-SPACE-JAIL-1}: the probe that would SUCCEED if the union were back. Two Spaces hosted;
     * a job in {@code acme} names a directory inside {@code beta}'s base by absolute path.
     */
    @Test
    void anotherSpacesBaseIsNotAnAllowedRoot() {
        System.clearProperty("assist.safety.roots");
        Path acme = Path.of("spaces", "acme").toAbsolutePath().normalize();
        Path beta = Path.of("spaces", "beta").toAbsolutePath().normalize();
        DiscoveredRoots.register("acme", acme);
        DiscoveredRoots.register("beta", beta);

        assertEquals(List.of(acme), SafetyPolicy.forSpace("acme").allowedRoots(), "acme sees only its own base");
        assertEquals(List.of(beta), SafetyPolicy.forSpace("beta").allowedRoots(), "beta sees only its own base");

        Map<String, Object> job = new LinkedHashMap<>();
        job.put("task", "cleanup");
        job.put("dir", beta.resolve("data").toString());
        List<com.gamma.config.spec.Finding> f =
                ConfigSafetyValidator.check("job", Map.of("job", job), SafetyPolicy.forSpace("acme"));
        assertTrue(f.stream().anyMatch(x -> x.fieldPath().equals("job.dir")),
                "acme must not address beta's directory: " + f);
        assertTrue(ConfigSafetyValidator.check("job", Map.of("job", job), SafetyPolicy.forSpace("beta")).isEmpty(),
                "beta still addresses its own directory");
    }

    @Test
    void anUnboundThreadIsTheDefaultSpaceAndGetsNoNamedSpacesBase() {
        System.clearProperty("assist.safety.roots");
        DiscoveredRoots.register("acme", Path.of("spaces", "acme").toAbsolutePath());
        assertTrue(SafetyPolicy.defaultPolicy().allowedRoots().isEmpty(),
                "no Space binding = the default Space, which has no base here: "
                        + SafetyPolicy.defaultPolicy().allowedRoots());
    }

    @Test
    void anUnregisteredBaseLeaves() {
        System.clearProperty("assist.safety.roots");
        DiscoveredRoots.register("acme", Path.of("spaces", "acme").toAbsolutePath());
        DiscoveredRoots.unregister("acme");
        assertTrue(SafetyPolicy.forSpace("acme").allowedRoots().isEmpty(),
                "the root set must not only ever grow within a process lifetime");
    }

    @Test
    void theBaseIsNormalisedAndReRegisteringReplacesIt() {
        System.clearProperty("assist.safety.roots");
        DiscoveredRoots.register("acme", Path.of("spaces", ".", "acme").toAbsolutePath());
        DiscoveredRoots.register("acme", Path.of("spaces", "acme").toAbsolutePath());
        assertEquals(List.of(Path.of("spaces", "acme").toAbsolutePath().normalize()),
                SafetyPolicy.forSpace("acme").allowedRoots());
    }

    @Test
    void withRootsStillBypassesTheRegistry() {
        DiscoveredRoots.register("acme", Path.of("spaces", "acme").toAbsolutePath());
        Path only = Path.of("workspace").toAbsolutePath().normalize();
        assertEquals(List.of(only), SafetyPolicy.withRoots(only).allowedRoots(),
                "an explicit policy (skill workspace, tests) is scoped, not unioned");
    }
}
