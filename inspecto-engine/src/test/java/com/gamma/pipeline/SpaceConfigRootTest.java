package com.gamma.pipeline;

import com.gamma.event.EventLog;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.MDC;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link SpaceConfigRoot} — the resolution behind {@code MATERIALIZE-SPACE-ROOT-1}.
 *
 * <p>The defect these pin: registry-reading job types read the JVM-wide {@code -Dassist.write.root} while
 * their data directory was per-Space, so in a multi-Space deployment a run read one Space's registry and
 * wrote another's data. It was invisible because single-Space deployments — Personal, and every test
 * before this one — make the two the same path.
 *
 * <p>⚠ The named-Space cases are the whole point, so they set the space MDC explicitly: that is what
 * {@code JobService} puts on the worker thread, and resolution keys on it.
 */
class SpaceConfigRootTest {

    @BeforeEach
    @AfterEach
    void reset() {
        SpaceConfigRoot.clear();
        MDC.remove(EventLog.SPACE_MDC_KEY);
        System.clearProperty("assist.write.root");
        System.clearProperty("data.dir");
    }

    @Test
    void aNamedSpaceResolvesItsOwnRootAndIgnoresTheJvmProperty(@TempDir Path ucc, @TempDir Path serverWide) {
        System.setProperty("assist.write.root", serverWide.toString());
        SpaceConfigRoot.register("ucc", ucc);
        MDC.put(EventLog.SPACE_MDC_KEY, "ucc");

        // This is the fix: the JVM-wide value is present AND different, and must lose.
        assertEquals(ucc, SpaceConfigRoot.current());
        assertEquals(ucc.resolve("registry"), SpaceConfigRoot.currentRegistry());
    }

    @Test
    void anUnregisteredNamedSpaceDoesNotFallBackToTheJvmProperty(@TempDir Path serverWide) {
        System.setProperty("assist.write.root", serverWide.toString());
        MDC.put(EventLog.SPACE_MDC_KEY, "ucc");

        // ⛔ Falling back here is precisely what produced the defect — it silently hands back ANOTHER
        // space's registry, which fails far away and reads as missing data rather than misconfiguration.
        assertNull(SpaceConfigRoot.current(), "a named space must not inherit the server-wide write root");
        assertNull(SpaceConfigRoot.currentRegistry());
    }

    @Test
    void theDefaultSpaceStillFallsBackToTheJvmProperty(@TempDir Path serverWide) {
        System.setProperty("assist.write.root", serverWide.toString());
        // No MDC ⇒ the default space. Every single-space deployment lands here, and must be unchanged.
        assertEquals(serverWide, SpaceConfigRoot.current());
    }

    @Test
    void nothingRegisteredAndNoPropertyIsNull() {
        assertNull(SpaceConfigRoot.current());
        assertNull(SpaceConfigRoot.currentRegistry());
    }

    @Test
    void forSpaceResolvesWithoutTheMdc(@TempDir Path ucc, @TempDir Path serverWide) {
        System.setProperty("assist.write.root", serverWide.toString());
        SpaceConfigRoot.register("ucc", ucc);

        // A caller holding an explicit id must not depend on the ambient MDC being set — the MDC is unset
        // here, so current() would answer for the DEFAULT space while forSpace answers for ucc.
        assertEquals(ucc, SpaceConfigRoot.forSpace("ucc"));
        assertEquals(serverWide, SpaceConfigRoot.current());
        assertNull(SpaceConfigRoot.forSpace("no_such_space"));
    }

    @Test
    void requireCurrentNamesTheSpaceItCouldNotResolve() {
        MDC.put(EventLog.SPACE_MDC_KEY, "ucc");
        IllegalStateException e =
                assertThrows(IllegalStateException.class, () -> SpaceConfigRoot.requireCurrent("materialize"));
        assertTrue(e.getMessage().contains("materialize"), e.getMessage());
        // Naming the space is the difference between a usable message and "needs a registry".
        assertTrue(e.getMessage().contains("ucc"), e.getMessage());
    }

    // ── the data root: same map, DELIBERATELY the opposite precedence ────────────

    @Test
    void aNamedSpacesDataRootResolvesFromItsRegistration(@TempDir Path uccData) {
        SpaceConfigRoot.registerDataRoot("ucc", uccData);
        MDC.put(EventLog.SPACE_MDC_KEY, "ucc");

        assertEquals(uccData, SpaceConfigRoot.currentDataRoot());
    }

    @Test
    void anExplicitDataDirPropertyWinsOverTheSpacesOwn(@TempDir Path uccData, @TempDir Path override) {
        SpaceConfigRoot.registerDataRoot("ucc", uccData);
        MDC.put(EventLog.SPACE_MDC_KEY, "ucc");
        System.setProperty("data.dir", override.toString());

        // 🔴 The OPPOSITE of the config root, on purpose: `System.getProperty("data.dir", root.dataDir())`
        // is the rule CollectorService's four call sites already apply, and quietly harmonising the two
        // lanes would be a behaviour change wearing a refactor's clothes. Pinned so nobody "tidies" it.
        assertEquals(override, SpaceConfigRoot.currentDataRoot());
        // ...while the config root still lets the space win. The asymmetry is the assertion.
        SpaceConfigRoot.register("ucc", uccData);
        System.setProperty("assist.write.root", override.toString());
        assertEquals(uccData, SpaceConfigRoot.current());
    }

    @Test
    void anUnregisteredSpacesDataRootIsNull() {
        MDC.put(EventLog.SPACE_MDC_KEY, "ucc");
        assertNull(SpaceConfigRoot.currentDataRoot());
    }

    @Test
    void forgetDropsBothRootsTogether(@TempDir Path cfg, @TempDir Path data) {
        SpaceConfigRoot.register("ucc", cfg);
        SpaceConfigRoot.registerDataRoot("ucc", data);
        MDC.put(EventLog.SPACE_MDC_KEY, "ucc");

        SpaceConfigRoot.forget("ucc");

        // ⛔ Neither may outlive the other: a config root left behind with no data root (or the reverse)
        // is precisely the crossed-wires state this class exists to prevent.
        assertNull(SpaceConfigRoot.current());
        assertNull(SpaceConfigRoot.currentDataRoot());
    }

    @Test
    void forgetDropsOnlyThatSpace(@TempDir Path ucc, @TempDir Path demo) {
        SpaceConfigRoot.register("ucc", ucc);
        SpaceConfigRoot.register("demo", demo);

        SpaceConfigRoot.forget("ucc");

        assertNull(SpaceConfigRoot.forSpace("ucc"));
        assertEquals(demo, SpaceConfigRoot.forSpace("demo"));
    }

    // ── the config READ root (JOB-PATH-PIPELINEJOBRUNNER-SPLIT-1, option 1: additive) ──────

    @Test
    void theDefaultSpacesReadRootIsTheLaunchDirWhenNothingIsRegistered(@TempDir Path serverWide) {
        System.setProperty("assist.write.root", serverWide.toString());
        // The rule a job's relative path has ALWAYS been resolved by, computed independently: the launch dir —
        // and NOT the write root, even though the write root is set. That non-fallback is the point.
        assertEquals(Path.of("").toAbsolutePath(), SpaceConfigRoot.currentConfigReadRoot());
        assertEquals(serverWide, SpaceConfigRoot.current(), "the WRITE root is untouched by the read-root accessor");
    }

    @Test
    void aRegisteredReadRootWinsAndLeavesTheWriteRootAndRegistryUnchanged(@TempDir Path launch, @TempDir Path serverWide) {
        System.setProperty("assist.write.root", serverWide.toString());
        Path registryBefore = SpaceConfigRoot.currentRegistry();

        SpaceConfigRoot.registerConfigReadRoot(EventLog.DEFAULT_SPACE_ID, launch);

        assertEquals(launch, SpaceConfigRoot.currentConfigReadRoot());
        // 🔴 The regression the row warns about: `out/`, `write/`, `registry/` are derived from current(), and a
        // read-root registration must not move them by construction. Same answer before and after.
        assertEquals(serverWide, SpaceConfigRoot.current());
        assertEquals(registryBefore, SpaceConfigRoot.currentRegistry());
        assertEquals(serverWide.resolve("registry"), SpaceConfigRoot.currentRegistry());
    }

    @Test
    void aSelfContainedSpacesReadRootIsItsConfigRoot(@TempDir Path ucc, @TempDir Path launch) {
        SpaceConfigRoot.register("ucc", ucc);
        MDC.put(EventLog.SPACE_MDC_KEY, "ucc");
        // One root serves both roles for a space SpaceBootstrap.load registered ...
        assertEquals(ucc, SpaceConfigRoot.currentConfigReadRoot());
        // ... until the control plane says otherwise explicitly.
        SpaceConfigRoot.registerConfigReadRoot("ucc", launch);
        assertEquals(launch, SpaceConfigRoot.currentConfigReadRoot());
        assertEquals(ucc, SpaceConfigRoot.current());
    }

    @Test
    void anUnregisteredNamedSpaceHasNoReadRoot(@TempDir Path serverWide) {
        System.setProperty("assist.write.root", serverWide.toString());
        MDC.put(EventLog.SPACE_MDC_KEY, "ucc");
        // Mirrors forSpace: a named space never inherits the default space's answer, not even the launch dir.
        assertNull(SpaceConfigRoot.currentConfigReadRoot());
        assertNull(SpaceConfigRoot.forSpaceConfigReadRoot("ucc"));
        assertEquals(Path.of("").toAbsolutePath(), SpaceConfigRoot.forSpaceConfigReadRoot(null),
                "null id means the default space, as forSpace(null) does");
    }

    @Test
    void forgetAndClearDropTheReadRootToo(@TempDir Path ucc, @TempDir Path launch) {
        SpaceConfigRoot.register("ucc", ucc);
        SpaceConfigRoot.registerConfigReadRoot("ucc", launch);
        SpaceConfigRoot.forget("ucc");
        assertNull(SpaceConfigRoot.forSpaceConfigReadRoot("ucc"), "a read root must not outlive its space");

        SpaceConfigRoot.registerConfigReadRoot(EventLog.DEFAULT_SPACE_ID, launch);
        SpaceConfigRoot.clear();
        assertEquals(Path.of("").toAbsolutePath(), SpaceConfigRoot.currentConfigReadRoot());
    }

    @Test
    void decisionRulesRegistersThroughTheSameMap(@TempDir Path cfg) {
        // DecisionRules takes a REGISTRY root and the shared map holds CONFIG roots; the forwarding must
        // not drift, or rules and jobs would resolve different trees again — the split-brain this avoids.
        DecisionRules.register("ucc", cfg.resolve("registry"));
        assertEquals(cfg, SpaceConfigRoot.forSpace("ucc"));
    }
}
