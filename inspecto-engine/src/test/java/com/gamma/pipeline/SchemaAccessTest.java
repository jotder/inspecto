package com.gamma.pipeline;

import com.gamma.util.CanonicalHash;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The read-only {@code schema} Platform Service (S1-5): list/get/fingerprint over the space's
 * seeded {@code registry/schemas/*.toon} components, live per call.
 */
class SchemaAccessTest {

    private static void writeSchema(Path root, String file, String toon) throws Exception {
        Path d = root.resolve("schemas");
        Files.createDirectories(d);
        Files.writeString(d.resolve(file), toon);
    }

    @Test
    void listsGetsAndFingerprintsSeededSchemas(@TempDir Path root) throws Exception {
        writeSchema(root, "cdr.toon", "name: cdr-v3\nfields: [a, b]\n");
        writeSchema(root, "billing.toon", "name: billing\nfields: [x]\n");
        SchemaAccess schemas = SchemaAccess.over(() -> ComponentRegistry.scan(root));

        assertEquals(java.util.Set.of("cdr-v3", "billing"), java.util.Set.copyOf(schemas.list()));
        var content = schemas.get("cdr-v3").orElseThrow();
        assertEquals("cdr-v3", content.get("name"));
        // The fingerprint is the same canonical hash the engine pins into manifests/outputs.
        assertEquals(CanonicalHash.sha256(content), schemas.fingerprint("cdr-v3").orElseThrow());
        assertNotEquals(schemas.fingerprint("cdr-v3"), schemas.fingerprint("billing"));

        assertTrue(schemas.get("missing").isEmpty());
        assertTrue(schemas.fingerprint("missing").isEmpty());
    }

    @Test
    void isLivePerCallAndEmptyTolerant(@TempDir Path root) throws Exception {
        SchemaAccess schemas = SchemaAccess.over(() -> ComponentRegistry.scan(root));
        assertTrue(schemas.list().isEmpty(), "no registry directory yet — empty, no exception");

        writeSchema(root, "late.toon", "name: late\n");
        assertEquals(java.util.List.of("late"), schemas.list(), "an operator edit is visible without a restart");
    }
}
