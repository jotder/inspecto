package com.gamma.control;

import com.gamma.acquire.ConnectionProfile;
import com.gamma.config.io.ConfigLoader;
import com.gamma.config.spec.Finding;
import com.gamma.config.spec.FindingCodes;
import com.gamma.config.spec.Severity;
import com.gamma.etl.PipelineConfig;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Every shipped example Pipeline under {@code inspecto/examples/} passes the authoring gate and loads
 * (`PROCESSOR-RELEASE-READINESS-1` G10, 2026-09-24). The runnable examples are exercised by hand
 * ({@code run-example} / {@code serve-example}) and sit outside every other test; the {@code _reference/}
 * templates need infrastructure (an SFTP server, a database, an https receiver, a DuckLake catalog) and can
 * never run here at all. Without this they rot silently: a key renamed in the engine leaves the demo
 * showing a shape the save gate refuses.
 *
 * <p>Judged the way a bundle import judges them ({@link SaveGate.Referents#MAY_ARRIVE_LATER}): a template
 * names a Connection this test Space does not hold, and a missing referent is a warning there, never a
 * refusal. The Connections themselves are loaded through {@link ConnectionProfile#load}, the loader the
 * service uses. And no example may carry keys the save would call inert.
 */
class ShippedExamplesPassTheSaveGateTest {

    private static final Path EXAMPLES = Path.of("examples");

    private static List<Path> files(String suffix) throws Exception {
        try (Stream<Path> w = Files.walk(EXAMPLES)) {
            return w.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(suffix))
                    .filter(p -> !p.toString().replace('\\', '/').contains("/out/"))
                    .sorted().toList();
        }
    }

    @Test
    void everyShippedPipelinePassesTheSaveGateAndLoads() throws Exception {
        List<Path> pipelines = files("pipeline.toon");
        assertTrue(pipelines.size() >= 30, "expected the shipped examples under " + EXAMPLES.toAbsolutePath());
        assertTrue(pipelines.stream().anyMatch(p -> p.toString().replace('\\', '/').contains("/_reference/")),
                "the _reference templates are part of the walk");
        List<String> problems = new ArrayList<>();
        for (Path p : pipelines) {
            Map<String, Object> draft = ConfigLoader.filesystem().decode(p.toString());
            for (Finding f : SaveGate.check(null, "pipeline", draft, null, p.toAbsolutePath().getParent(),
                    SaveGate.Referents.MAY_ARRIVE_LATER)) {
                if (f.severity() == Severity.ERROR || FindingCodes.WARN_COLLECTOR_KEY_INERT.equals(f.code()))
                    problems.add(p + ": " + f.code() + " " + f.fieldPath() + " — " + f.message());
            }
            try {
                PipelineConfig.loadForValidation(p.toString());
            } catch (Exception e) {
                problems.add(p + ": does not load — " + e.getMessage());
            }
        }
        assertTrue(problems.isEmpty(), "shipped examples the save gate or the loader refuses:\n  "
                + String.join("\n  ", problems));
    }

    @Test
    void everyShippedConnectionLoads() throws Exception {
        List<Path> connections = files("_connection.toon");
        assertFalse(connections.isEmpty(), "the _reference templates ship their Connections");
        for (Path p : connections) {
            ConnectionProfile c = ConnectionProfile.load(p);
            assertNotNull(c.id(), p + " names its Connection");
            assertNotNull(c.connector(), p + " names its connector");
        }
    }
}
