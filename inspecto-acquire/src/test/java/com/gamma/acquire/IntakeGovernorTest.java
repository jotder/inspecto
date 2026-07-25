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
}
