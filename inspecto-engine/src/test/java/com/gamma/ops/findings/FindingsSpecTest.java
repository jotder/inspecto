package com.gamma.ops.findings;

import com.gamma.ops.ObjectType;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Configurable Findings sections (C3 / BACKLOG D6): the built-in default, the round trip, and every
 * fail-closed rejection. Validation runs at <em>authoring</em> time, so each of these is a 422 on the
 * generic {@code POST /components/findings-spec} — a spec the renderer could not draw must never reach
 * the triage panel.
 */
class FindingsSpecTest {

    @Test
    void builtInDefaultIsTodaysHardcodedShape() {
        FindingsSpec spec = FindingsSpec.defaultFor(ObjectType.CASE);

        assertEquals("case", spec.objectType());
        assertEquals(List.of("disposition", "impactAmount", "recordsAffected", "summary"),
                spec.sections().stream().map(FindingsSpec.Section::key).toList());

        FindingsSpec.Section disposition = spec.sections().get(0);
        assertEquals("select", disposition.type());
        assertEquals(5, disposition.options().size(), "the CASE_DISPOSITIONS ladder");
        assertEquals("CONFIRMED", disposition.options().get(0).value());
        assertEquals("False positive", disposition.options().get(1).label(), "humanized for display");

        // Every section is always-visible but optional — the panel's no-disposition prompt is a soft warn,
        // so making any of these mandatory would be a behaviour change for existing deployments.
        for (FindingsSpec.Section s : spec.sections()) {
            assertEquals("required", s.tier(), s.key() + " stays visible");
            assertEquals(Boolean.FALSE, s.required(), s.key() + " is not mandatory");
        }
    }

    @Test
    void roundTripsThroughMap() {
        Map<String, Object> authored = spec(
                section("disposition", Map.of("type", "select", "tier", "required",
                        "options", List.of("OPEN", "SHUT"))),
                section("amount", Map.of("type", "number", "min", 0, "max", 10, "help", "in USD")),
                section("why", Map.of("type", "multiline", "tier", "advanced",
                        "dependsOn", Map.of("key", "disposition", "equals", "SHUT"))));

        FindingsSpec parsed = FindingsSpec.fromMap(authored);
        Map<String, Object> out = parsed.toMap();

        assertEquals("incident", out.get("name"), "the component id, so file stem and URL id agree");
        assertEquals("incident", out.get("objectType"));
        // A bare string option list is legal shorthand and normalises to {value,label}.
        assertEquals(List.of(Map.of("value", "OPEN", "label", "OPEN"), Map.of("value", "SHUT", "label", "SHUT")),
                sectionOf(out, "disposition").get("options"));
        assertEquals("in USD", sectionOf(out, "amount").get("help"));
        assertEquals(Map.of("key", "disposition", "equals", "SHUT"), sectionOf(out, "why").get("dependsOn"));
        // A label defaults to the key rather than being emitted blank.
        assertEquals("amount", sectionOf(out, "amount").get("label"));

        assertEquals(out, FindingsSpec.fromMap(out).toMap(), "reparsing the wire shape is a fixed point");
    }

    @Test
    void notEqualsDependsOnSurvivesTheRoundTrip() {
        Map<String, Object> out = FindingsSpec.fromMap(spec(
                section("a", Map.of()),
                section("b", Map.of("dependsOn", Map.of("key", "a", "notEquals", "x"))))).toMap();
        assertEquals(Map.of("key", "a", "notEquals", "x"), sectionOf(out, "b").get("dependsOn"));
    }

    @Test
    void forwardDependsOnReferenceIsLegal() {
        assertDoesNotThrow(() -> FindingsSpec.fromMap(spec(
                section("a", Map.of("dependsOn", Map.of("key", "b", "equals", "1"))),
                section("b", Map.of()))));
    }

    // ── fail-closed rejections ──────────────────────────────────────────────────

    @Test
    void rejectsAnUnknownObjectType() {
        Map<String, Object> m = spec(section("a", Map.of()));
        m.put("objectType", "sasquatch");
        assertThrows(IllegalArgumentException.class, () -> FindingsSpec.fromMap(m));
    }

    @Test
    void rejectsAMissingOrEmptySectionList() {
        Map<String, Object> none = new LinkedHashMap<>();
        none.put("objectType", "case");
        assertThrows(IllegalArgumentException.class, () -> FindingsSpec.fromMap(none));

        Map<String, Object> empty = spec();
        assertThrows(IllegalArgumentException.class, () -> FindingsSpec.fromMap(empty));
        assertThrows(IllegalArgumentException.class, () -> FindingsSpec.fromMap(null));
    }

    @Test
    void rejectsABlankOrDuplicateKey() {
        assertThrows(IllegalArgumentException.class,
                () -> FindingsSpec.fromMap(spec(section(" ", Map.of()))));
        assertThrows(IllegalArgumentException.class,
                () -> FindingsSpec.fromMap(spec(section("dup", Map.of()), section("dup", Map.of()))));
    }

    @Test
    void rejectsAnUnknownTypeOrTier() {
        assertThrows(IllegalArgumentException.class,
                () -> FindingsSpec.fromMap(spec(section("a", Map.of("type", "rocket")))));
        assertThrows(IllegalArgumentException.class,
                () -> FindingsSpec.fromMap(spec(section("a", Map.of("tier", "someday")))));
    }

    @Test
    void rejectsASelectWithNoOptions() {
        assertThrows(IllegalArgumentException.class,
                () -> FindingsSpec.fromMap(spec(section("a", Map.of("type", "select")))));
    }

    @Test
    void rejectsAnInvalidPatternAndInvertedBounds() {
        assertThrows(IllegalArgumentException.class,
                () -> FindingsSpec.fromMap(spec(section("a", Map.of("pattern", "([")))));
        assertThrows(IllegalArgumentException.class,
                () -> FindingsSpec.fromMap(spec(section("a", Map.of("min", 10, "max", 1)))));
    }

    @Test
    void rejectsADependsOnThatNamesNoSibling() {
        assertThrows(IllegalArgumentException.class, () -> FindingsSpec.fromMap(spec(
                section("a", Map.of("dependsOn", Map.of("key", "ghost", "equals", "1"))))));
    }

    @Test
    void rejectsADependsOnWithBothOrNeitherSense() {
        assertThrows(IllegalArgumentException.class, () -> FindingsSpec.fromMap(spec(
                section("a", Map.of()),
                section("b", Map.of("dependsOn", Map.of("key", "a", "equals", "1", "notEquals", "2"))))));
        assertThrows(IllegalArgumentException.class, () -> FindingsSpec.fromMap(spec(
                section("a", Map.of()),
                section("b", Map.of("dependsOn", Map.of("key", "a"))))));
    }

    /** An unknown key is rejected, not ignored: a typo'd {@code tier} would silently hide a field. */
    @Test
    void rejectsAnUnknownSectionKey() {
        assertThrows(IllegalArgumentException.class,
                () -> FindingsSpec.fromMap(spec(section("a", Map.of("teir", "required")))));
    }

    // ── helpers ─────────────────────────────────────────────────────────────────

    private static Map<String, Object> spec(Map<String, Object>... sections) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("objectType", "incident");
        m.put("sections", new ArrayList<>(List.of(sections)));
        return m;
    }

    private static Map<String, Object> section(String key, Map<String, Object> extras) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("key", key);
        m.putAll(extras);
        return m;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> sectionOf(Map<String, Object> specMap, String key) {
        for (Map<String, Object> s : (List<Map<String, Object>>) specMap.get("sections")) {
            if (key.equals(s.get("key"))) return s;
        }
        return fail("no section '" + key + "'");
    }
}
