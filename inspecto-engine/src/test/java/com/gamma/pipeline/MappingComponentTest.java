package com.gamma.pipeline;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The {@code mapping} component kind — the first CSV-backed registry kind (ELT amendment Phase 1
 * slice 3, D-3: filename = identity). Persisted as {@code registry/mappings/<id>.csv}; content
 * decodes to {@code {name, rules}}; MET-5 history works with the {@code .csv} suffix.
 */
class MappingComponentTest {

    private static final List<Map<String, String>> RULES = List.of(
            Map.of("targetColumn", "ID", "sourceExpression", "ID", "transformType", "DIRECT"),
            Map.of("targetColumn", "GROSS", "sourceExpression", "TRY_CAST(A AS DOUBLE) * 2, 0 + 2",
                    "transformType", "EXPR"));

    @Test
    void writesCsvAndReadsBackByFilenameIdentity(@TempDir Path root) throws Exception {
        ComponentStore store = new ComponentStore(root);
        store.write("mapping", "orders_std", Map.of("rules", RULES));

        Path file = root.resolve("mappings/orders_std.csv");
        assertTrue(Files.exists(file), "persisted as a CSV, not TOON");
        assertTrue(Files.readString(file, StandardCharsets.UTF_8)
                .startsWith("targetColumn,sourceExpression,transformType"), "canonical header");

        ComponentRegistry.Component c = store.get("mapping", "orders_std").orElseThrow();
        assertEquals("orders_std", c.name());
        assertEquals(RULES, c.content().get("rules"), "rules round-trip through the CSV");
    }

    @Test
    void registryScanIndexesCsvMappings(@TempDir Path root) throws Exception {
        Files.createDirectories(root.resolve("mappings"));
        Files.writeString(root.resolve("mappings/std.csv"), """
                targetColumn,sourceExpression,transformType
                A,B,DIRECT
                """);
        ComponentRegistry reg = ComponentRegistry.scan(root);
        ComponentRegistry.Component c = reg.resolve("mapping/std").orElseThrow();
        assertEquals("std", c.name(), "identity = filename stem (D-3)");
        assertInstanceOf(List.class, c.content().get("rules"));
    }

    @Test
    void contentWithoutRulesIsRefused(@TempDir Path root) {
        ComponentStore store = new ComponentStore(root);
        assertThrows(IllegalArgumentException.class,
                () -> store.write("mapping", "empty", Map.of("something", "else")));
    }

    @Test
    void historyVersionsWorkWithTheCsvSuffix(@TempDir Path root) throws Exception {
        ComponentStore store = new ComponentStore(root);
        store.write("mapping", "m", Map.of("rules", List.of(
                Map.of("targetColumn", "A", "sourceExpression", "A", "transformType", "DIRECT"))));
        store.write("mapping", "m", Map.of("rules", RULES));   // replace → archives v1

        List<ComponentStore.ComponentVersion> versions = store.versions("mapping", "m");
        assertEquals(1, versions.size());
        assertEquals(1, versions.get(0).version());
        Object v1Rules = versions.get(0).content().get("rules");
        assertEquals("A", ((Map<?, ?>) ((List<?>) v1Rules).get(0)).get("targetColumn"));
    }

    @Test
    void schemaIsWritableAgain(@TempDir Path root) throws Exception {
        // Re-added (slice 3) with the W1 objection resolved: schema/<id> refs now execute.
        ComponentStore store = new ComponentStore(root);
        store.write("schema", "orders_v1", Map.of(
                "raw", Map.of("name", "orders_v1", "format", "CSV")));
        assertTrue(Files.exists(root.resolve("schemas/orders_v1.toon")), "schema stays TOON");
        assertTrue(store.get("schema", "orders_v1").isPresent());
    }
}
