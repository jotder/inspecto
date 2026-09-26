package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.control.EntityTypes.EntityType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * LA-17 step 2 — the Entity Type normalisers, the validator, and the {@code entity_types} TOON round trip.
 *
 * <p>⛔ <b>Parity is the contract.</b> {@link #everySharedFixtureCaseFoldsIdentically} runs
 * {@code entity-normaliser-parity.fixture.json} — the SAME file {@code entity-key.spec.ts} feeds the browser — so
 * the Java and TS keys cannot drift apart while both stay green.
 */
class EntityTypesTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Path FIXTURE = Path.of("..", "inspecto-ui", "src", "app", "inspecto", "graph",
            "entity-normaliser-parity.fixture.json");

    @Test
    void everySharedFixtureCaseFoldsIdentically() throws Exception {
        JsonNode cases = JSON.readTree(Files.readString(FIXTURE)).get("cases");
        assertTrue(cases.size() > 0, "the fixture has cases");
        List<String> failures = new ArrayList<>();
        for (JsonNode c : cases) {
            String n = c.get("normaliser").asText(), in = c.get("input").asText(), want = c.get("expected").asText();
            String got = EntityTypes.normalise(n, in);
            if (!want.equals(got)) failures.add(n + "('" + in + "') = '" + got + "', want '" + want + "'");
        }
        assertEquals(List.of(), failures);
    }

    @Test
    void anUndeclaredNormaliserIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> EntityTypes.normalise("lower", "x"));
    }

    @Test
    void theDefaultsAreTheSeededNineAndValidate() {
        assertEquals(List.of("subscriber", "imsi", "imei", "msisdn", "wallet", "account", "agent", "handset", "cell"),
                EntityTypes.DEFAULTS.stream().map(EntityType::id).toList());
        assertDoesNotThrow(() -> EntityTypes.validate(EntityTypes.DEFAULTS));
        assertEquals(EntityTypes.DEFAULTS, EntityTypes.parse(EntityTypes.shape(EntityTypes.DEFAULTS)));
    }

    @Test
    void theValidatorRefusesEachBadList() {
        EntityType ok = new EntityType("imsi", "IMSI", "digits", true, List.of("IMSI"));
        assertRefused(List.of(), "may not be empty");
        assertRefused(List.of(new EntityType("IMSI", "IMSI", "digits", true, List.of())), "must match");
        assertRefused(List.of(new EntityType("9x", "X", "digits", true, List.of())), "must match");
        assertRefused(List.of(new EntityType("a".repeat(33), "X", "digits", true, List.of())), "must match");
        assertRefused(List.of(ok, new EntityType("imsi", "Other", "default", false, List.of())), "duplicate");
        assertRefused(List.of(new EntityType("imsi", "  ", "digits", true, List.of())), "label is blank");
        assertRefused(List.of(new EntityType("imsi", "IMSI", "lower", true, List.of())), "normaliser must be one of");
        assertRefused(List.of(new EntityType("imsi", "IMSI", "digits", true, List.of(" "))), "classification is blank");
        assertRefused(List.of(ok, new EntityType("sim", "SIM", "digits", true, List.of(" imsi "))), "claimed by both");
        List<EntityType> many = new ArrayList<>();
        for (int i = 0; i < 65; i++) many.add(new EntityType("t" + i, "T", "default", false, List.of()));
        assertRefused(many, "at most 64");
        assertDoesNotThrow(() -> EntityTypes.validate(many.subList(0, 64)));
    }

    @Test
    void parseRefusesAMalformedShape() {
        Map<String, Object> t = new LinkedHashMap<>(EntityTypes.shape(List.of(EntityTypes.DEFAULTS.get(0))).get(0));
        t.put("masked", "yes");
        assertThrows(IllegalArgumentException.class, () -> EntityTypes.parse(List.of(t)));
        assertThrows(IllegalArgumentException.class, () -> EntityTypes.parse("subscriber"));
        assertThrows(IllegalArgumentException.class, () -> EntityTypes.parse(List.of("subscriber")));
    }

    /** JToon must carry a list of objects that each hold a list — written and read back unchanged. */
    @Test
    void entityTypesRoundTripThroughLinkAnalysisToon(@TempDir Path dir) throws Exception {
        List<EntityType> types = List.of(
                new EntityType("agent", "Agent / till", "upper-trim", false, List.of("AGENT", "TILL")),
                new EntityType("msisdn", "MSISDN", "e164", true, List.of("MSISDN")),
                new EntityType("note", "Free text", "default", false, List.of()));
        Path f = dir.resolve(LinkAnalysisSettings.FILE);
        new LinkAnalysisSettings(1200, null, null, "all", null, null, types).write(f);
        LinkAnalysisSettings back = LinkAnalysisSettings.read(f);
        assertEquals(types, back.entityTypes(), Files.readString(f));
        assertEquals(1200, back.projectionNodeCap());
        assertEquals("all", back.maskingMode());

        new LinkAnalysisSettings(null, null, null, null, null, null, null).write(f);
        assertFalse(Files.readString(f).contains("entity_types"), "written only when stated");
        assertNull(LinkAnalysisSettings.read(f).entityTypes());
        assertEquals(EntityTypes.DEFAULTS, LinkAnalysisSettings.read(f).effectiveEntityTypes());
    }

    /** A hand-edited invalid list reads as inherit, and costs none of the other keys. */
    @Test
    void anInvalidStoredListReadsAsInherit(@TempDir Path dir) throws Exception {
        Path f = dir.resolve(LinkAnalysisSettings.FILE);
        new LinkAnalysisSettings(700, null, null, null, null, null,
                List.of(new EntityType("imsi", "IMSI", "digits", true, List.of("IMSI")))).write(f);
        Files.writeString(f, Files.readString(f).replace("digits", "rot13"));
        LinkAnalysisSettings s = LinkAnalysisSettings.read(f);
        assertNull(s.entityTypes());
        assertEquals(EntityTypes.DEFAULTS, s.effectiveEntityTypes());
        assertEquals(700, s.projectionNodeCap(), "the other keys survive");
    }

    private static void assertRefused(List<EntityType> types, String fragment) {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> EntityTypes.validate(types));
        assertTrue(e.getMessage().contains(fragment), e.getMessage());
    }
}
