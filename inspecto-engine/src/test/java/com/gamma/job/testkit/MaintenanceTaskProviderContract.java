package com.gamma.job.testkit;

import com.gamma.job.JobConfig;
import com.gamma.job.JobResult;
import com.gamma.job.MaintenanceTaskContext;
import com.gamma.job.MaintenanceTaskProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The platform's TCK for a {@link MaintenanceTaskProvider} (MODULE-REORG-1 P5b): what the {@code maintenance} Job Type's
 * dispatcher assumes of every provider. It runs each task only in {@code dryRun} against a bare context (no
 * {@code JobService}, no {@code JobContext}), so it asserts the dispatch rules, not the task's work.
 *
 * <p>Defects each test catches:
 * <ul>
 *   <li>{@link #tasksAreLowerCaseTokensAndStable} - a blank or mixed-case task name is unreachable from the job's
 *       {@code task:} key; a set that changes between calls makes the catalogue and the dispatcher disagree.</li>
 *   <li>{@link #taskNamesAreOwnedByOneProvider} - two providers claiming one task name: the dispatcher runs whichever
 *       the ServiceLoader lists first.</li>
 *   <li>{@link #anUnknownTaskIsRefusedNeverRun} - a provider that ignores the task name runs its one task for ANY name,
 *       so a typo in a job definition silently executes something else (found in {@code OpsMaintenanceTasks}, P5b).</li>
 *   <li>{@link #aDeclaredTaskNeverReturnsNull} - the dispatcher dereferences the result; null is a
 *       NullPointerException in the scheduler thread.</li>
 * </ul>
 */
public abstract class MaintenanceTaskProviderContract {

    private static final Pattern TOKEN = Pattern.compile("[a-z][a-z0-9_.-]*");

    /** The provider under test. */
    protected abstract MaintenanceTaskProvider provider();

    /** Parameters a dry run of any task needs to get past argument parsing (the dry run itself changes nothing). */
    protected Map<String, String> params() {
        return Map.of();
    }

    /** Every provider the runtime would see; a seam only so the self-test can plant a duplicate. */
    protected Iterable<MaintenanceTaskProvider> registered() {
        return ServiceLoader.load(MaintenanceTaskProvider.class);
    }

    @Test
    void tasksAreLowerCaseTokensAndStable() {
        Set<String> tasks = provider().tasks();
        assertNotNull(tasks, "tasks()");
        assertFalse(tasks.isEmpty(), "a provider that offers no task has no reason to be registered");
        for (String t : tasks)
            assertTrue(t != null && TOKEN.matcher(t).matches(), "task name '" + t + "' must match " + TOKEN);
        assertEquals(tasks, provider().tasks(), "tasks() must be the same set every call");
    }

    @Test
    void taskNamesAreOwnedByOneProvider() {
        Set<String> mine = provider().tasks();
        List<String> clashes = new ArrayList<>();
        for (MaintenanceTaskProvider other : registered()) {
            if (other.getClass() == provider().getClass()) continue;
            Set<String> overlap = new HashSet<>(other.tasks());
            overlap.retainAll(mine);
            if (!overlap.isEmpty()) clashes.add(other.getClass().getName() + " also claims " + overlap);
        }
        assertTrue(clashes.isEmpty(), "task names claimed twice: " + clashes);
    }

    @Test
    void anUnknownTaskIsRefusedNeverRun(@TempDir Path dir) {
        String unknown = "no_such_task_tck";
        assertFalse(provider().tasks().contains(unknown));
        JobResult r = null;
        try {
            r = provider().run(unknown, context(dir));
        } catch (Throwable refused) {
            return;   // a refusal by exception is the house style (the provider's own switch default)
        }
        assertTrue(r != null && !r.success(), "run('" + unknown + "') returned " + r + " - an unknown task must be refused, not run");
    }

    @Test
    void aDeclaredTaskNeverReturnsNull(@TempDir Path dir) {
        for (String task : provider().tasks()) {
            try {
                assertNotNull(provider().run(task, context(dir)), "run('" + task + "') returned null");
            } catch (AssertionError e) {
                throw e;
            } catch (Throwable argumentOrHostMissing) {
                // a dry run without its host may refuse by exception; that is allowed, only a null result is not
            }
        }
    }

    private MaintenanceTaskContext context(Path dir) {
        JobConfig cfg = new JobConfig("tck", "maintenance", null, null, true, false, params(), null, null, Map.of(), Map.of());
        return new MaintenanceTaskContext(cfg, null, true, dir.toString(), dir.toString(), null, null);
    }
}
