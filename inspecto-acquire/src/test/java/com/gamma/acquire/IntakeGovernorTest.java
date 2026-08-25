package com.gamma.acquire;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** T15 — {@link IntakeGovernor} admission cap + cycle-overrun controller (pipeline-graph §3.5). */
class IntakeGovernorTest {

    private static final long POLL_MS = 10_000;

    private static IntakeGovernor capped(int baseCap, int minCap) {
        return new IntakeGovernor(new IntakeGovernor.Policy(baseCap, minCap, true));
    }

    @Test
    void offByDefaultSoTheIngestPathIsUnchanged() {
        IntakeGovernor gov = new IntakeGovernor(new IntakeGovernor.Policy(0, 1, true));
        assertFalse(gov.policy().active());
        assertEquals(IntakeGovernor.UNBOUNDED, gov.capFor("p"));
        gov.observeCycle(List.of("p"), POLL_MS * 10, POLL_MS);   // wildly overran
        assertEquals(IntakeGovernor.UNBOUNDED, gov.capFor("p"), "no base cap ⇒ nothing to throttle");
    }

    @Test
    void unseenPipelineStartsAtTheBaseCap() {
        assertEquals(100, capped(100, 1).capFor("p"));
    }

    /** Hot-applied fleet globals (scheduler-system-config plan): a CHANGED policy drops every
     *  learned cap — each was learned under the old thresholds — while an unchanged re-install is a
     *  no-op that leaves adaptation undisturbed. Reads through {@code policyFor} go live at once. */
    @Test
    void setGlobalPolicyHotAppliesAndClearsLearnedCapsOnlyOnChange() {
        IntakeGovernor gov = capped(100, 1);
        gov.observeCycle(List.of("p"), POLL_MS + 1, POLL_MS);
        assertEquals(50, gov.capFor("p"), "precondition: a learned (halved) cap");

        gov.setGlobalPolicy(new IntakeGovernor.Policy(100, 1, true));   // unchanged: no-op
        assertEquals(50, gov.capFor("p"), "an equal policy must not disturb adaptation");

        gov.setGlobalPolicy(new IntakeGovernor.Policy(200, 5, true));   // changed: caps reset
        assertEquals(200, gov.capFor("p"), "learned cap must reset to the NEW base");
        assertEquals(200, gov.policy().baseCap());
        assertEquals(5, gov.policyFor("p").minCap(), "un-overridden pipeline reads the new globals");
    }

    @Test
    void overrunHalvesTheCap() {
        IntakeGovernor gov = capped(100, 1);
        gov.observeCycle(List.of("p"), POLL_MS + 1, POLL_MS);
        assertEquals(50, gov.capFor("p"));
        gov.observeCycle(List.of("p"), POLL_MS + 1, POLL_MS);
        assertEquals(25, gov.capFor("p"));
    }

    @Test
    void halvingStopsAtTheFloorAndNeverReachesZero() {
        IntakeGovernor gov = capped(100, 4);
        for (int i = 0; i < 20; i++) gov.observeCycle(List.of("p"), POLL_MS + 1, POLL_MS);
        assertEquals(4, gov.capFor("p"), "a capped pipeline must always still make progress");
    }

    @Test
    void comfortableCycleRestoresTowardsTheBaseCapButNeverPastIt() {
        IntakeGovernor gov = capped(100, 1);
        gov.observeCycle(List.of("p"), POLL_MS + 1, POLL_MS);
        gov.observeCycle(List.of("p"), POLL_MS + 1, POLL_MS);
        assertEquals(25, gov.capFor("p"));

        gov.observeCycle(List.of("p"), 100, POLL_MS);   // comfortable — under half the interval
        assertEquals(50, gov.capFor("p"));
        for (int i = 0; i < 10; i++) gov.observeCycle(List.of("p"), 100, POLL_MS);
        assertEquals(100, gov.capFor("p"), "restore is clamped at the configured base cap");
    }

    @Test
    void cycleInsideTheHysteresisBandNeitherHalvesNorRestores() {
        IntakeGovernor gov = capped(100, 1);
        gov.observeCycle(List.of("p"), POLL_MS + 1, POLL_MS);
        assertEquals(50, gov.capFor("p"));
        // Between pollInterval/2 and pollInterval: overrunning nothing, but not comfortable either.
        for (int i = 0; i < 5; i++) gov.observeCycle(List.of("p"), POLL_MS - 1, POLL_MS);
        assertEquals(50, gov.capFor("p"), "the 2x gap between thresholds is the anti-flap hysteresis");
    }

    @Test
    void capIsPerPipeline() {
        IntakeGovernor gov = capped(100, 1);
        gov.observeCycle(List.of("slow"), POLL_MS + 1, POLL_MS);
        assertEquals(50, gov.capFor("slow"));
        assertEquals(100, gov.capFor("other"), "an untouched pipeline keeps the base cap");
    }

    @Test
    void nonAdaptivePolicyIsAHardCap() {
        IntakeGovernor gov = new IntakeGovernor(new IntakeGovernor.Policy(100, 1, false));
        gov.observeCycle(List.of("p"), POLL_MS * 10, POLL_MS);
        assertEquals(100, gov.capFor("p"), "adaptive=false pins the cap at the base value");
    }

    @Test
    void minCapCannotExceedTheBaseCap() {
        IntakeGovernor gov = capped(2, 50);
        gov.observeCycle(List.of("p"), POLL_MS + 1, POLL_MS);
        assertEquals(2, gov.capFor("p"), "a floor above the base cap clamps to the base cap");
    }

    @Test
    void forgetDropsPerPipelineState() {
        IntakeGovernor gov = capped(100, 1);
        gov.observeCycle(List.of("p"), POLL_MS + 1, POLL_MS);
        assertEquals(50, gov.capFor("p"));
        gov.forget("p");
        assertEquals(100, gov.capFor("p"), "forgotten ⇒ back to the base cap, no leaked entry");
    }

    // ── per-flow processing.intake overrides (T15 follow-up) ───────────────────────

    @Test
    void anOverrideCapsOneFlowWhileTheFleetStaysUnbounded() {
        IntakeGovernor gov = new IntakeGovernor(new IntakeGovernor.Policy(0, 1, true)); // globals: off
        gov.configure("noisy", new IntakeGovernor.Policy(10, 1, true));
        assertEquals(10, gov.capFor("noisy"));
        assertEquals(IntakeGovernor.UNBOUNDED, gov.capFor("other"), "the override is that flow's alone");
        gov.observeCycle(List.of("noisy"), POLL_MS + 1, POLL_MS);
        assertEquals(5, gov.capFor("noisy"), "the controller adapts against the OVERRIDE thresholds");
    }

    @Test
    void aZeroOverrideExemptsOneFlowFromAFleetWideCap() {
        IntakeGovernor gov = capped(100, 1);
        gov.configure("exempt", new IntakeGovernor.Policy(0, 1, true));
        assertEquals(IntakeGovernor.UNBOUNDED, gov.capFor("exempt"));
        gov.observeCycle(List.of("exempt"), POLL_MS * 10, POLL_MS);
        assertEquals(IntakeGovernor.UNBOUNDED, gov.capFor("exempt"), "explicitly unbounded ⇒ never throttled");
        assertEquals(100, gov.capFor("other"), "the global cap still governs the rest of the fleet");
    }

    @Test
    void aChangedOverrideDropsTheLearnedCapAndAnUnchangedOneKeepsIt() {
        IntakeGovernor gov = capped(100, 1);
        gov.configure("p", new IntakeGovernor.Policy(40, 1, true));
        gov.observeCycle(List.of("p"), POLL_MS + 1, POLL_MS);
        assertEquals(20, gov.capFor("p"));

        // Re-installing the SAME policy (the ingest path does this every cycle) must not reset adaptation.
        gov.configure("p", new IntakeGovernor.Policy(40, 1, true));
        assertEquals(20, gov.capFor("p"), "an idempotent re-configure keeps the learned cap");

        // A DIFFERENT policy drops it — the old learned value was learned under other thresholds.
        gov.configure("p", new IntakeGovernor.Policy(60, 1, true));
        assertEquals(60, gov.capFor("p"), "a changed override restarts from its own base cap");
    }

    @Test
    void clearingTheOverrideRestoresTheGlobals() {
        IntakeGovernor gov = capped(100, 1);
        gov.configure("p", new IntakeGovernor.Policy(10, 1, true));
        gov.observeCycle(List.of("p"), POLL_MS + 1, POLL_MS);
        assertEquals(5, gov.capFor("p"));
        gov.configure("p", null);   // block removed from the TOON
        assertEquals(100, gov.capFor("p"), "no override ⇒ the -D globals, with no stale learned cap");
    }

    @Test
    void forgetDropsTheOverrideToo() {
        IntakeGovernor gov = new IntakeGovernor(new IntakeGovernor.Policy(0, 1, true));
        gov.configure("p", new IntakeGovernor.Policy(10, 1, true));
        assertEquals(10, gov.capFor("p"));
        gov.forget("p");
        assertEquals(IntakeGovernor.UNBOUNDED, gov.capFor("p"), "churn must not leak the override map");
    }

    @Test
    void perFlowAdaptiveFalsePinsThatFlowOnly() {
        IntakeGovernor gov = capped(100, 1);
        gov.configure("pinned", new IntakeGovernor.Policy(30, 1, false));
        gov.observeCycle(List.of("pinned", "global"), POLL_MS + 1, POLL_MS);
        assertEquals(30, gov.capFor("pinned"), "adaptive=false in the override is a hard cap for that flow");
        assertEquals(50, gov.capFor("global"), "the globally-governed flow in the same cycle still adapts");
    }
}
