package com.gamma.util;

import com.gamma.util.OnboardingCounters.Event;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** {@link OnboardingCounters}: the append-only per-installation file behind OB-13. */
class OnboardingCountersTest {

    @AfterEach
    void reset() {
        OnboardingCounters.install(null);
        OnboardingCounters.clock(null);
    }

    private static void at(String instant) {
        OnboardingCounters.clock(Clock.fixed(Instant.parse(instant), ZoneOffset.UTC));
    }

    @Test
    void withNoFileEveryCallIsANoOp() {
        OnboardingCounters.install(null);
        OnboardingCounters.record(Event.LAUNCH);
        Map<String, Object> m = OnboardingCounters.metrics();
        assertEquals(false, m.get("recording"));
        assertEquals(0, m.get("launches"));
    }

    @Test
    void theMilestonesCountFromTheFirstLaunch(@TempDir Path dir) {
        OnboardingCounters.install(dir.resolve("c.tsv"));
        at("2026-10-22T10:00:00Z");
        OnboardingCounters.record(Event.LAUNCH);
        at("2026-10-22T10:01:00Z");
        OnboardingCounters.record(Event.SPACE_CREATED);
        at("2026-10-22T10:03:00Z");
        OnboardingCounters.record(Event.FIRST_DATASET);
        at("2026-10-22T10:07:30Z");
        OnboardingCounters.record(Event.FIRST_GRAPH);
        at("2026-10-22T11:00:00Z");
        OnboardingCounters.record(Event.LAUNCH);
        OnboardingCounters.record(Event.FIRST_GRAPH);   // once per installation: not written again

        Map<String, Object> m = OnboardingCounters.metrics();
        assertEquals("2026-10-22T10:00:00Z", m.get("firstLaunch"));
        assertEquals(2, m.get("launches"));
        assertEquals(1, m.get("spacesCreated"));
        assertEquals(60L, m.get("firstSpaceSeconds"));
        assertEquals(180L, m.get("firstDatasetSeconds"));
        assertEquals(450L, m.get("firstGraphSeconds"));
        assertNull(m.get("guideCompleted"));
        assertNull(m.get("guideCompletedSeconds"));
    }

    @Test
    void onceOnlyEventsSurviveARestart(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("c.tsv");
        OnboardingCounters.install(f);
        OnboardingCounters.record(Event.GUIDE_COMPLETED);
        OnboardingCounters.install(f);   // a restart re-reads the file
        OnboardingCounters.record(Event.GUIDE_COMPLETED);
        assertEquals(1, Files.readAllLines(f).size());
    }

    @Test
    void anUnknownStepIsDroppedAndNothingElseIsWritten(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("sub/c.tsv");
        OnboardingCounters.install(f);
        OnboardingCounters.record(Event.STEP_ABANDONED, "add_data:file");
        OnboardingCounters.record(Event.STEP_ABANDONED, "MULE-HUB");
        OnboardingCounters.record(Event.SPACE_CREATED, "graph");   // a step rides only an abandoned step
        List<String> lines = Files.readAllLines(f);
        assertTrue(lines.get(0).endsWith("\tstep_abandoned\tadd_data:file"), lines.toString());
        assertTrue(lines.get(1).endsWith("\tstep_abandoned"), lines.toString());
        assertTrue(lines.get(2).endsWith("\tspace_created"), lines.toString());
        assertEquals(Map.of("add_data:file", 1), OnboardingCounters.metrics().get("abandoned"));
    }

    @Test
    void aDamagedLineIsSkipped(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("c.tsv");
        Files.writeString(f, "not-a-time\tlaunch\n2026-10-22T10:00:00Z\tlaunch\n2026-10-22T10:00:05Z\tnonsense\n");
        OnboardingCounters.install(f);
        Map<String, Object> m = OnboardingCounters.metrics();
        assertEquals(1, m.get("launches"));
        assertEquals("2026-10-22T10:00:00Z", m.get("firstLaunch"));
    }
}
