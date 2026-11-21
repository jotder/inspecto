package com.gamma.recon;

import com.gamma.util.ToonHelper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * COMPONENT-VALIDATE-ALL-1: {@link ReconKindValidator} now builds the run's spec shape at save, so every Reconciliation the
 * product ships (Space Templates, demo Spaces) must still build; a shipped file the save would refuse is a failure here, not
 * a surprise at a later install.
 */
class ReconShippedShapeTest {

    private static final Path REPO = Path.of("..", "..").toAbsolutePath().normalize();

    @Test
    void everyShippedReconciliationBuildsItsSpecAndPassesTheSaveValidator() throws IOException {
        List<String> refused = new ArrayList<>();
        int seen = 0;
        try (Stream<Path> files = Files.walk(REPO)) {
            for (Path p : (Iterable<Path>) files.filter(Files::isRegularFile)::iterator) {
                String rel = REPO.relativize(p).toString().replace('\\', '/');
                if (!rel.endsWith(".toon") || !rel.contains("/registry/reconciliations/") || rel.contains("/node_modules/")
                        || rel.contains("/target/") || rel.contains("/archived-documents/")) continue;
                seen++;
                try {
                    new ReconKindValidator().validate(p.getFileName().toString(), ToonHelper.load(p.toString()));
                } catch (RuntimeException e) {
                    refused.add(rel + ": " + e.getMessage());
                }
            }
        }
        assertTrue(seen >= 8, "the scan found the shipped Reconciliations (" + seen + ")");
        assertEquals(List.of(), refused);
    }
}
