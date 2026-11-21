package com.gamma.control;

import com.gamma.util.ToonHelper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * UNKNOWN-KEYS-SILENT-1: the census of a Dataset's and a Reconciliation's keys, and the scan that keeps every shipped file
 * (Space Templates, demo and example Spaces) inside it, so a later refusal cannot break shipped content.
 */
class UnknownComponentKeysTest {

    private static final Path REPO = Path.of("..").toAbsolutePath().normalize();

    @Test
    void aMisspelledKeyIsNamedAndAnExtensionKeyPasses() {
        assertEquals(List.of(), UnknownComponentKeys.of("dataset", Map.of("physicalRef", "x", "name", "x", "x-note", "mine")));
        assertEquals(List.of("physicalRefs"), UnknownComponentKeys.of("dataset", Map.of("physicalRef", "x", "physicalRefs", "y")));
        assertEquals(List.of(), UnknownComponentKeys.of("widget", Map.of("anything", 1)), "other kinds are not judged here");
    }

    @Test
    void aReconciliationToleranceKeyIsCheckedInsideCompareColumns() {
        Map<String, Object> recon = Map.of("datasets", List.of("a", "b"), "keyColumns", List.of("k"),
                "compareColumns", List.of(Map.of("column", "amount", "agg", "sum", "toleranceTyp", "absolute", "tolerance", 0)));
        assertEquals(List.of("compareColumns[0].toleranceTyp"), UnknownComponentKeys.of("reconciliation", recon));
        assertEquals(List.of("tolerence"), UnknownComponentKeys.of("reconciliation",
                Map.of("datasets", List.of("a", "b"), "tolerence", 1)));
    }

    /** Every shipped Dataset and Reconciliation file is inside the census: no stray key waits to break a later refusal. */
    @Test
    void everyShippedDatasetAndReconciliationIsInsideTheCensus() throws IOException {
        List<String> strays = new ArrayList<>();
        int seen = 0;
        try (Stream<Path> files = Files.walk(REPO)) {
            for (Path p : (Iterable<Path>) files.filter(Files::isRegularFile)::iterator) {
                String rel = REPO.relativize(p).toString().replace('\\', '/');
                if (rel.contains("/node_modules/") || rel.contains("/target/") || rel.startsWith(".git/")
                        || rel.contains("/archived-documents/") || !rel.endsWith(".toon")) continue;
                String type = rel.contains("/registry/datasets/") ? "dataset" : rel.contains("/registry/reconciliations/") ? "reconciliation" : null;
                if (type == null) continue;
                seen++;
                List<String> unknown = UnknownComponentKeys.of(type, ToonHelper.load(p.toString()));
                if (!unknown.isEmpty()) strays.add(rel + " " + unknown);
            }
        }
        assertTrue(seen > 250, "the scan found the shipped files (" + seen + ")");
        assertEquals(List.of(), strays);
    }
}
