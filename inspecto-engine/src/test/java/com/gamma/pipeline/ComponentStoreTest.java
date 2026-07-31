package com.gamma.pipeline;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link ComponentStore} (T19): create / replace / delete registry components, jailed + atomic, with the
 * id canonicalised to the in-file identity. Probes the {@code ConfigCodec.toToon} round-trip per type.
 */
class ComponentStoreTest {

    @Test
    void writeReadsBackWithIdStampedAsName(@TempDir Path root) throws Exception {
        ComponentStore store = new ComponentStore(root);
        store.write("transform", "redact-pii", Map.of("sql", "SELECT * EXCLUDE (ssn) FROM rows"));

        ComponentRegistry.Component c = store.get("transform", "redact-pii").orElseThrow();
        assertEquals("transform", c.type());
        assertEquals("redact-pii", c.name());                       // id stamped as name
        assertEquals("redact-pii", c.content().get("name"));
        assertEquals("SELECT * EXCLUDE (ssn) FROM rows", c.content().get("sql"));
        // written under registry/transforms/<id>.toon
        assertTrue(c.path().toString().replace('\\', '/').endsWith("transforms/redact-pii.toon"));
        assertTrue(Files.exists(c.path()));
    }

    @Test
    void listAndDeleteAcrossTypes(@TempDir Path root) throws Exception {
        ComponentStore store = new ComponentStore(root);
        store.write("grammar", "pipe", Map.of("delimiter", "|"));
        store.write("grammar", "comma", Map.of("delimiter", ","));
        store.write("sink", "warehouse", Map.of("format", "PARQUET"));

        assertEquals(2, store.list("grammar").size());
        assertEquals(1, store.list("sink").size());
        assertEquals(0, store.list("transform").size());

        assertTrue(store.delete("grammar", "pipe"));
        assertEquals(1, store.list("grammar").size());
        assertFalse(store.get("grammar", "pipe").isPresent());
        assertFalse(store.delete("grammar", "ghost"));             // already absent
    }

    @Test
    void replaceOverwritesInPlace(@TempDir Path root) throws Exception {
        ComponentStore store = new ComponentStore(root);
        store.write("sink", "wh", Map.of("format", "CSV"));
        store.write("sink", "wh", Map.of("format", "PARQUET"));   // replace
        assertEquals(1, store.list("sink").size());
        assertEquals("PARQUET", store.get("sink", "wh").orElseThrow().content().get("format"));
    }

    @Test
    void tabularFieldsRoundTripThroughToon(@TempDir Path root) throws Exception {
        // probe: nested tabular rows must survive the toToon->load round-trip. Was asserted on a
        // `schema` component until that kind was retired (W1); `grammar` exercises the same nesting.
        ComponentStore store = new ComponentStore(root);
        Map<String, Object> grammar = Map.of(
                "partitionKey", "EVENT_DATE",
                "raw", Map.of("name", "orders", "format", "CSV",
                        "fields", List.of(
                                Map.of("name", "ID", "selector", "0", "type", "VARCHAR"),
                                Map.of("name", "AMT", "selector", "1", "type", "DOUBLE"))));
        store.write("grammar", "orders", grammar);

        ComponentRegistry.Component c = store.get("grammar", "orders").orElseThrow();
        assertEquals("EVENT_DATE", c.content().get("partitionKey"));
        Object raw = c.content().get("raw");
        assertInstanceOf(Map.class, raw);
        Object fields = ((Map<?, ?>) raw).get("fields");
        assertInstanceOf(List.class, fields);
        assertEquals(2, ((List<?>) fields).size(), "both tabular field rows survive the round-trip");
    }

    /**
     * A schema has exactly ONE home: the path-addressed config TOON the engine executes
     * (`processing.schema_file`). The id-addressed registry copy was retired in unification W1 because
     * nothing could run it. This guards the ambiguity from creeping back.
     */
    @Test
    void schemaIsNotAWritableComponentKind(@TempDir Path root) {
        assertFalse(ComponentStore.WRITABLE_TYPES.contains("schema"),
                "schema lives in the config TOON only — see onboarding-pipeline-unification.md U-C");
        ComponentStore store = new ComponentStore(root);
        assertThrows(IllegalArgumentException.class,
                () -> store.write("schema", "orders", Map.of("fields", List.of())));
    }

    @Test
    void patternPackStepsSurviveTheRoundTripWithABlankStartDirection(@TempDir Path root) throws Exception {
        // probe: a pattern pack's steps are uniform {direction} rows, and the start node's direction is the
        // EMPTY STRING — never an empty map. TOON cannot represent {} as a list element: JToon encodes it as a
        // bare "-" and then fails to decode its own output ("Array length mismatch: declared 4, found 1"), so
        // an omitted key here silently breaks every seeded pack on read. Keep the blank, don't "tidy" it away.
        ComponentStore store = new ComponentStore(root);
        Map<String, Object> pack = Map.of(
                "label", "Layering chain",
                "category", "money",
                "description", "Funds relayed through a chain of intermediaries.",
                "steps", List.of(Map.of("direction", ""), Map.of("direction", "out"), Map.of("direction", "out")));
        store.write("pattern-pack", "layering-chain", pack);

        ComponentRegistry.Component c = store.get("pattern-pack", "layering-chain").orElseThrow();
        assertTrue(c.path().toString().replace('\\', '/').endsWith("pattern-packs/layering-chain.toon"));
        Object steps = c.content().get("steps");
        assertInstanceOf(List.class, steps);
        assertEquals(3, ((List<?>) steps).size(), "all three steps survive, blank start row included");
        assertEquals("", ((Map<?, ?>) ((List<?>) steps).get(0)).get("direction"), "start node stays a wildcard");
        assertEquals("out", ((Map<?, ?>) ((List<?>) steps).get(1)).get("direction"));
    }

    /**
     * Every writable type must own a registry directory, or its first write blows up at runtime rather
     * than here. Guards the seam that let {@code rule-template} rot: the UI spoke a kind the server had
     * never been widened to, and nothing on either side asserted the two agreed.
     */
    @Test
    void everyWritableTypeHasARegistryDir() {
        for (String type : ComponentStore.WRITABLE_TYPES)
            assertTrue(ComponentRegistry.dirForType(type).isPresent(),
                    "writable type '" + type + "' has no ComponentRegistry.TYPE_BY_DIR entry");
    }

    @Test
    void writesARuleTemplateUnderItsOwnDir(@TempDir Path root) throws Exception {
        ComponentStore store = new ComponentStore(root);
        store.write("rule-template", "high_sev", Map.of("source", "alerts", "projection", List.of("rule", "severity")));

        ComponentRegistry.Component c = store.get("rule-template", "high_sev").orElseThrow();
        assertEquals("rule-template", c.type());
        assertEquals("alerts", c.content().get("source"));
        assertTrue(c.path().toString().replace('\\', '/').endsWith("rule-templates/high_sev.toon"));
    }

    @Test
    void rejectsUnknownTypeUnsafeIdAndConnection(@TempDir Path root) {
        ComponentStore store = new ComponentStore(root);
        assertThrows(IllegalArgumentException.class, () -> store.list("bogus"));
        assertThrows(IllegalArgumentException.class, () -> store.list("connection"));   // has its own CRUD
        assertThrows(IllegalArgumentException.class, () -> store.write("grammar", "../escape", Map.of("x", 1)));
        assertThrows(IllegalArgumentException.class, () -> store.write("grammar", "bad/slash", Map.of("x", 1)));
    }
}
