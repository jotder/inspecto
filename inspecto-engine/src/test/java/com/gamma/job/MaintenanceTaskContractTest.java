package com.gamma.job;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins {@link MaintenanceJob#BUILT_IN_TASKS} against the {@code switch} it claims to describe, and pins
 * {@link MaintenanceJob#availableTasks()} against what this build can actually run.
 *
 * <p><b>Why this test exists.</b> The served {@code JobTypeDescriptor} for {@code maintenance} drives the
 * Jobs authoring form, and until 2026-09-09 its description was a hand-written string. Measured then, it
 * was wrong in both directions at once: it advertised four tasks a Personal bundle refuses — the incident
 * purge plus the three backup tasks, all contributed by optional modules — and hid seven the engine ships.
 * A form that offers a task the dispatch cannot run, or hides one it can, is the defect; deriving the
 * string fixes today's instance, and this test is what stops it recurring.
 *
 * <p>The constant is not self-certifying, so the first test re-parses this repository's own source for the
 * {@code case} labels — the same approach {@code MapNodeKeyContractTest} takes to catch a constant going
 * stale relative to the code it describes.
 */
class MaintenanceTaskContractTest {

    private static final String MAINTENANCE_JOB =
            "inspecto-engine/src/main/java/com/gamma/job/MaintenanceJob.java";

    /** The switch's own text, from its opening to the {@code default} arm that asks the providers. */
    private static final String REGION_START = "return switch (task) {";
    private static final String REGION_END = "default -> {";

    /** One {@code case} arm, whose label list may carry several quoted ids (heartbeat/noop share an arm). */
    private static final Pattern CASE_ARM = Pattern.compile("case\\s+([^:>]+?)\\s*->");
    private static final Pattern QUOTED = Pattern.compile("\"([a-z0-9_]+)\"");

    @Test
    void theConstantMatchesTheSwitchLabels() throws IOException {
        String source = Files.readString(repoFile(MAINTENANCE_JOB));
        int from = source.indexOf(REGION_START);
        int to = source.indexOf(REGION_END, from);
        assertTrue(from > 0 && to > from,
                "the maintenance switch moved or was renamed — re-anchor this scan before trusting it");

        Set<String> labels = new TreeSet<>();
        Matcher arms = CASE_ARM.matcher(source.substring(from, to));
        while (arms.find()) {
            Matcher ids = QUOTED.matcher(arms.group(1));
            while (ids.find()) labels.add(ids.group(1));
        }

        assertEquals(new TreeSet<>(MaintenanceJob.BUILT_IN_TASKS), labels,
                "BUILT_IN_TASKS must list exactly the task ids the switch carries — the served descriptor "
                        + "is built from it, so a mismatch is a form that offers or hides the wrong task");
    }

    @Test
    void theConstantHasNoDuplicatesAndKeepsItsStatedSize() {
        List<String> tasks = MaintenanceJob.BUILT_IN_TASKS;
        assertEquals(tasks.size(), new TreeSet<>(tasks).size(), "BUILT_IN_TASKS carries a duplicate: " + tasks);
        assertEquals(20, tasks.size(),
                "20 ids across 19 arms is the documented shape; update the javadoc with the constant");
    }

    /**
     * The seven the hand-written description omitted. Named individually rather than by count, so this
     * fails with the id that went missing rather than with an arithmetic complaint.
     */
    @Test
    void theTasksTheOldDescriptionHidAreAllPresent() {
        for (String hidden : List.of("dedup_prune", "event_prune", "partition_prune", "receipt_prune",
                "reference_compact", "retire_superseded", "heartbeat")) {
            assertTrue(MaintenanceJob.BUILT_IN_TASKS.contains(hidden),
                    hidden + " is a built-in task the served description must offer");
        }
    }

    /**
     * ⛔ The incident purge was REMOVED from the switch by EDG-01 cell 7, not merely also provided — a
     * named case always beats a contributed provider, so leaving it would have kept the built-in on every
     * edition. It must therefore not be a built-in, and on a build with no provider it must not be
     * advertised at all.
     */
    @Test
    void aContributedTaskIsNeitherBuiltInNorAdvertisedWithoutItsModule() {
        assertFalse(MaintenanceJob.BUILT_IN_TASKS.contains("incident_purge"),
                "incident_purge moved to the optional operational-objects module — it is not a built-in");

        // The default reactor carries no MaintenanceTaskProvider: every optional module depends ON the
        // core, so the arrow points module -> core and a test here never sees one on its classpath. That
        // makes this the Personal case, and the honest expectation is the built-ins and nothing else.
        assertEquals(MaintenanceJob.BUILT_IN_TASKS, MaintenanceJob.availableTasks(),
                "with no provider installed, the advertised set must be exactly the built-ins — the old "
                        + "description named backup / backup_verify / restore / incident_purge here, all of "
                        + "which a Personal bundle refuses");
    }

    /** The built-ins must lead, so the served string reads the same way the switch does. */
    @Test
    void availableTasksKeepsTheBuiltInsFirstAndInSwitchOrder() {
        List<String> available = MaintenanceJob.availableTasks();
        assertTrue(available.size() >= MaintenanceJob.BUILT_IN_TASKS.size(),
                "availableTasks() may add contributed ids, never drop built-in ones");
        assertEquals(MaintenanceJob.BUILT_IN_TASKS, available.subList(0, MaintenanceJob.BUILT_IN_TASKS.size()),
                "the built-ins lead, in the order the switch reads");
    }

    /** Walk up from the module's CWD to the repo root, so the path works under surefire and an IDE alike. */
    private static Path repoFile(String relative) {
        Path dir = Path.of("").toAbsolutePath();
        for (int up = 0; up < 4 && dir != null; up++, dir = dir.getParent()) {
            Path candidate = dir.resolve(relative);
            if (Files.exists(candidate)) return candidate;
        }
        throw new AssertionError("cannot locate " + relative + " from " + Path.of("").toAbsolutePath());
    }
}
