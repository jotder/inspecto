package {{packageName}};

import com.gamma.job.PackTestHarness;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Fires the Job through {@link PackTestHarness} — the real registration check, Parameter resolution,
 * grant filtering and dry-run substitution, with no engine to boot. If this is green the pack is
 * loadable: {@code loadFromClasspath()} goes through the same {@code META-INF/services} file the
 * engine reads, and the {@code requires:} list is validated exactly as it is at registration.
 */
class {{className}}Test {

    @Test
    void runsAndEmitsThroughItsGrant() {
        PackTestHarness harness = PackTestHarness.create().loadFromClasspath();

        PackTestHarness.Outcome run = harness.run("{{id}}", Map.of("subject", "harness"));

        assertEquals("SUCCESS", run.status(), run.message());
        assertEquals("harness", run.params().get("subject"));
        assertEquals(1, run.notifications().size(), "one notification through the granted service");
        assertTrue(run.logged("TODO: do the work"));
    }

    @Test
    void appliesTheDeclaredDefault() {
        PackTestHarness harness = PackTestHarness.create().loadFromClasspath();

        PackTestHarness.Outcome run = harness.run("{{id}}", Map.of());

        assertEquals("world", run.params().get("subject"), "the declared defaultValue");
    }

    @Test
    void dryRunRecordsInsteadOfActing() {
        PackTestHarness harness = PackTestHarness.create().loadFromClasspath();

        PackTestHarness.Outcome run = harness.dryRun("{{id}}", Map.of());

        assertEquals("SUCCESS", run.status(), run.message());
        assertTrue(run.notifications().isEmpty(), "a dry run must store nothing");
        assertTrue(run.logged("dry run: would emit notification"));
    }
}
