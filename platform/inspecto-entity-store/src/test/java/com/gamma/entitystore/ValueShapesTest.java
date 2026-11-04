package com.gamma.entitystore;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ENTITY-TYPE-CATALOG-1 value shapes, the Java half of {@code entity-shape-parity.fixture.json} — the SAME file
 * {@code value-shape.spec.ts} feeds the browser, so a shape that fits on one side only fails here or there.
 */
class ValueShapesTest {

    private static final Path FIXTURE = Path.of("..", "..", "inspecto-ui", "src", "app", "inspecto", "graph",
            "entity-shape-parity.fixture.json");

    private static JsonNode fixture() throws IOException {
        return new ObjectMapper().readTree(Files.readString(FIXTURE));
    }

    @Test
    void everyFixtureCaseFitsAsExpected() throws IOException {
        List<String> wrong = new ArrayList<>();
        for (JsonNode c : fixture().get("cases")) {
            String got = ValueShapes.fit(c.get("shape").asText(), c.get("input").asText()).name().toLowerCase(Locale.ROOT);
            if (!got.equals(c.get("expected").asText())) wrong.add(c + " -> " + got);
        }
        assertEquals(List.of(), wrong);
    }

    @Test
    void theVocabularyIsClosed() throws IOException {
        JsonNode f = fixture();
        for (JsonNode v : f.get("valid")) assertEquals(v.asText(), ValueShapes.validate(v.asText()));
        for (JsonNode v : f.get("invalid"))
            assertThrows(IllegalArgumentException.class, () -> ValueShapes.validate(v.asText()), "'" + v.asText() + "' must be refused");
    }

    @Test
    void theFixtureCoversEveryShapeAndEveryFit() throws IOException {
        Set<String> names = new TreeSet<>(), fits = new TreeSet<>();
        for (JsonNode c : fixture().get("cases")) {
            String s = c.get("shape").asText();
            names.add(s.contains("(") ? s.substring(0, s.indexOf('(')) : s);
            fits.add(c.get("expected").asText());
        }
        assertEquals(new TreeSet<>(ValueShapes.NAMES), names);
        assertEquals(Set.of("none", "weak", "strong"), fits);
    }

    @Test
    void theNarrowerShapeRanksHigher() {
        assertTrue(ValueShapes.specificity("luhn(15)") > ValueShapes.specificity("digits(15)"));
        assertTrue(ValueShapes.specificity("digits(15)") > ValueShapes.specificity("e164"));
        assertTrue(ValueShapes.specificity("e164") > ValueShapes.specificity("free"));
        assertTrue(ValueShapes.specificity("luhn(15)") > ValueShapes.specificity("luhn(13-19)"), "a set length over a range");
        assertTrue(ValueShapes.specificity("digits(15)") > ValueShapes.specificity("digits(14-15)"));
        assertTrue(ValueShapes.specificity("digits(14-15)") > ValueShapes.specificity("e164"));
        assertEquals(0, ValueShapes.specificity(null));
    }

    @Test
    void theDefaultsStateValidShapes() {
        for (EntityTypes.EntityType t : EntityTypes.DEFAULTS) ValueShapes.validate(t.shape());
        assertEquals("luhn(15)", EntityTypes.coreDefault("imei").shape());
        assertEquals("e164", EntityTypes.coreDefault("msisdn").shape());
    }
}
