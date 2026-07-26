package com.gamma.config.spec;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the {@link ConfigSpec} → JSON Schema projection (AGT-6a plan D9): dotted paths nest,
 * every {@link FieldType} maps to a legal JSON Schema type, a required path pulls its whole ancestor
 * chain into {@code required}, and each authored {@link ConfigSpecs} kind projects to something an
 * LLM can actually be constrained by (i.e. no longer a bare {@code {"type":"object"}}).
 */
class ConfigJsonSchemaTest {

    // ── shape ────────────────────────────────────────────────────────────────────

    @Test
    void flatFieldsBecomeTopLevelProperties() {
        ConfigSpec spec = new ConfigSpec("t", List.of(
                FieldSpec.of("name", "Name", FieldType.STRING, "the name"),
                FieldSpec.of("count", "Count", FieldType.INT, "how many")), List.of());

        Map<String, Object> schema = ConfigJsonSchema.of(spec);

        assertEquals("object", schema.get("type"));
        Map<String, Object> props = props(schema);
        assertEquals("string", type(props, "name"));
        assertEquals("integer", type(props, "count"));
        assertEquals("the name", leaf(props, "name").get("description"));
    }

    @Test
    void dottedPathsNestIntoObjectNodes() {
        ConfigSpec spec = new ConfigSpec("t", List.of(
                FieldSpec.of("processing.threads", "Threads", FieldType.INT, "worker count"),
                FieldSpec.of("processing.duckdb.memory_limit", "Mem", FieldType.STRING, "cap")), List.of());

        Map<String, Object> processing = leaf(props(ConfigJsonSchema.of(spec)), "processing");

        assertEquals("object", processing.get("type"));
        assertEquals("integer", type(props(processing), "threads"));
        Map<String, Object> duckdb = leaf(props(processing), "duckdb");
        assertEquals("object", duckdb.get("type"));
        assertEquals("string", type(props(duckdb), "memory_limit"));
    }

    // ── required ─────────────────────────────────────────────────────────────────

    @Test
    void requiredTopLevelFieldIsListedAtTheRoot() {
        ConfigSpec spec = new ConfigSpec("t", List.of(
                FieldSpec.required("name", "Name", FieldType.STRING, "the name"),
                FieldSpec.of("other", "Other", FieldType.STRING, "optional")), List.of());

        assertEquals(List.of("name"), required(ConfigJsonSchema.of(spec)));
    }

    /**
     * The load-bearing case: {@code ConfigLoader.validate} treats a required path as required
     * absolutely, so omitting the whole enclosing block must NOT satisfy the schema.
     */
    @Test
    void requiredNestedFieldMarksItsWholeAncestorChain() {
        ConfigSpec spec = new ConfigSpec("t", List.of(
                FieldSpec.required("a.b.c", "C", FieldType.STRING, "deep")), List.of());

        Map<String, Object> schema = ConfigJsonSchema.of(spec);

        assertEquals(List.of("a"), required(schema), "root must require the first segment");
        Map<String, Object> a = leaf(props(schema), "a");
        assertEquals(List.of("b"), required(a));
        assertEquals(List.of("c"), required(leaf(props(a), "b")));
    }

    @Test
    void optionalNestedFieldAddsNoRequiredAnywhere() {
        ConfigSpec spec = new ConfigSpec("t", List.of(
                FieldSpec.of("a.b", "B", FieldType.STRING, "shallow")), List.of());

        Map<String, Object> schema = ConfigJsonSchema.of(spec);

        assertNull(schema.get("required"));
        assertNull(leaf(props(schema), "a").get("required"));
    }

    // ── type mapping ─────────────────────────────────────────────────────────────

    @Test
    void everyFieldTypeProjectsToALegalJsonSchemaType() {
        for (FieldType t : FieldType.values()) {
            ConfigSpec spec = new ConfigSpec("t", List.of(
                    FieldSpec.of("f", "F", t, "d")), List.of());
            Object projected = type(props(ConfigJsonSchema.of(spec)), "f");
            assertTrue(List.of("string", "integer", "boolean", "object", "array").contains(projected),
                    t + " projected to illegal JSON Schema type " + projected);
        }
    }

    @Test
    void stringRefinementsProjectAsStrings() {
        for (FieldType t : List.of(FieldType.FILEPATH, FieldType.CRON, FieldType.SQL)) {
            ConfigSpec spec = new ConfigSpec("t", List.of(FieldSpec.of("f", "F", t, "d")), List.of());
            assertEquals("string", type(props(ConfigJsonSchema.of(spec)), "f"), t + " is a STRING refinement");
        }
    }

    @Test
    void enumFieldCarriesItsAllowedValuesAndDefault() {
        ConfigSpec spec = new ConfigSpec("t", List.of(
                FieldSpec.enumField("mode", "Mode", List.of("fast", "safe"), "safe", "how to run")), List.of());

        Map<String, Object> mode = leaf(props(ConfigJsonSchema.of(spec)), "mode");

        assertEquals("string", mode.get("type"));
        assertEquals(List.of("fast", "safe"), mode.get("enum"));
        assertEquals("safe", mode.get("default"));
    }

    @Test
    void defaultAndPatternAreProjectedWhenPresentAndOmittedWhenNot() {
        ConfigSpec with = new ConfigSpec("t", List.of(
                new FieldSpec("f", "F", "d", FieldType.STRING, false, "dflt", List.of(), "[a-z]+", null, null)),
                List.of());
        Map<String, Object> f = leaf(props(ConfigJsonSchema.of(with)), "f");
        assertEquals("dflt", f.get("default"));
        assertEquals("[a-z]+", f.get("pattern"));

        ConfigSpec without = new ConfigSpec("t", List.of(
                FieldSpec.of("f", "F", FieldType.STRING, "d")), List.of());
        Map<String, Object> bare = leaf(props(ConfigJsonSchema.of(without)), "f");
        assertFalse(bare.containsKey("default"));
        assertFalse(bare.containsKey("pattern"));
    }

    @Test
    void labelBacksTheDescriptionWhenTheFieldHasNoProse() {
        ConfigSpec spec = new ConfigSpec("t", List.of(
                FieldSpec.of("f", "Fallback Label", FieldType.STRING, "")), List.of());

        assertEquals("Fallback Label", leaf(props(ConfigJsonSchema.of(spec)), "f").get("description"));
    }

    // ── deliberate omissions ─────────────────────────────────────────────────────

    @Test
    void additionalPropertiesIsNeverForbidden() {
        // A ConfigSpec enumerates what it can validate, not every key a kind accepts.
        Map<String, Object> schema = ConfigJsonSchema.forType("pipeline");
        assertFalse(schema.containsKey("additionalProperties"));
        props(schema).values().forEach(v -> {
            if (v instanceof Map<?, ?> m) assertFalse(m.containsKey("additionalProperties"));
        });
    }

    @Test
    void listHasNoGuessedItemType() {
        ConfigSpec spec = new ConfigSpec("t", List.of(
                FieldSpec.of("f", "F", FieldType.LIST, "d")), List.of());
        Map<String, Object> f = leaf(props(ConfigJsonSchema.of(spec)), "f");
        assertEquals("array", f.get("type"));
        assertFalse(f.containsKey("items"));
    }

    @Test
    void uiOnlyHintsAreNotProjected() {
        ConfigSpec spec = new ConfigSpec("t", List.of(
                new FieldSpec("f", "F", "d", FieldType.STRING, false, null, List.of(), null,
                        "cron-editor", "other=yes")), List.of());
        Map<String, Object> f = leaf(props(ConfigJsonSchema.of(spec)), "f");
        assertFalse(f.containsKey("uiHint"));
        assertFalse(f.containsKey("visibleWhen"));
    }

    // ── edge cases ───────────────────────────────────────────────────────────────

    @Test
    void aPathThatIsBothALeafAndAPrefixStaysAnObjectAndKeepsItsProse() {
        // "x" is declared as a MAP field AND has a child "x.y" — the node must hold both.
        ConfigSpec spec = new ConfigSpec("t", List.of(
                FieldSpec.of("x", "X", FieldType.MAP, "the bag"),
                FieldSpec.of("x.y", "Y", FieldType.STRING, "inside")), List.of());

        Map<String, Object> x = leaf(props(ConfigJsonSchema.of(spec)), "x");

        assertEquals("object", x.get("type"));
        assertEquals("the bag", x.get("description"), "the MAP field's own prose survives");
        assertEquals("string", type(props(x), "y"), "the child is still reachable");
    }

    @Test
    void prefixDeclaredAfterItsChildAlsoMerges() {
        // Same as above but authored in the opposite order — order must not matter.
        ConfigSpec spec = new ConfigSpec("t", List.of(
                FieldSpec.of("x.y", "Y", FieldType.STRING, "inside"),
                FieldSpec.of("x", "X", FieldType.MAP, "the bag")), List.of());

        Map<String, Object> x = leaf(props(ConfigJsonSchema.of(spec)), "x");

        assertEquals("object", x.get("type"));
        assertEquals("the bag", x.get("description"));
        assertEquals("string", type(props(x), "y"));
    }

    @Test
    void nullOrEmptySpecDegradesToTheBareObjectSchema() {
        assertEquals(Map.of("type", "object"), ConfigJsonSchema.of(null));
        assertEquals(Map.of("type", "object"), ConfigJsonSchema.of(new ConfigSpec("t", List.of(), List.of())));
        assertEquals(Map.of("type", "object"), ConfigJsonSchema.forType("no-such-kind"));
    }

    @Test
    void blankPathsAreSkippedRatherThanCrashing() {
        ConfigSpec spec = new ConfigSpec("t", List.of(
                FieldSpec.of("", "Blank", FieldType.STRING, "d"),
                FieldSpec.of("real", "Real", FieldType.STRING, "d")), List.of());

        Map<String, Object> props = props(ConfigJsonSchema.of(spec));

        assertEquals(1, props.size());
        assertTrue(props.containsKey("real"));
    }

    // ── the whole authored catalogue ──────────────────────────────────────────────

    /** The point of the exercise: no authored kind may still project as a structure-less object. */
    @Test
    void everyAuthoredKindProjectsRealStructure() {
        for (String type : ConfigSpecs.TYPES) {
            Map<String, Object> schema = ConfigJsonSchema.forType(type);
            assertEquals("object", schema.get("type"), type);
            assertFalse(props(schema).isEmpty(), type + " must project properties, not a bare object");
        }
    }

    @Test
    void toJsonSerialisesToParseableSchemaJson() {
        String json = ConfigJsonSchema.toJson(ConfigSpecs.forType("expectation"));
        assertTrue(json.startsWith("{\"type\":\"object\""), json.substring(0, Math.min(60, json.length())));
        assertTrue(json.contains("\"properties\""));
        // No empty properties bags left behind by the pruning pass.
        assertFalse(json.contains("\"properties\":{}"));
    }

    @Test
    void pipelineProjectionNestsItsProcessingBlock() {
        // A real authored spec, not a synthetic one — proves the projection survives ~35 fields.
        Map<String, Object> pipeline = ConfigJsonSchema.forType("pipeline");
        Map<String, Object> props = props(pipeline);
        assertTrue(props.containsKey("processing"), "expected a nested processing block: " + props.keySet());
        assertEquals("object", leaf(props, "processing").get("type"));
    }

    // ── helpers ──────────────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private static Map<String, Object> props(Map<String, Object> node) {
        return (Map<String, Object>) node.getOrDefault("properties", Map.of());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> leaf(Map<String, Object> props, String name) {
        Object v = props.get(name);
        assertNotNull(v, "no property '" + name + "' in " + props.keySet());
        return (Map<String, Object>) v;
    }

    private static Object type(Map<String, Object> props, String name) {
        return leaf(props, name).get("type");
    }

    @SuppressWarnings("unchecked")
    private static List<String> required(Map<String, Object> node) {
        return (List<String>) node.get("required");
    }
}
