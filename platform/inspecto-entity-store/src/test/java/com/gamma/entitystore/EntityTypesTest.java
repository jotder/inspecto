package com.gamma.entitystore;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.entitystore.EntityTypes.EntityType;
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
    private static final Path FIXTURE = Path.of("..", "..", "inspecto-ui", "src", "app", "inspecto", "graph",
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

    /** ENTITY-TYPE-CATALOG-1 P0: the ids and classifications are data contracts; families only add guidance. */
    @Test
    void theDefaultIdsAndClassificationsAreUnchangedAndEachSitsUnderOneFamily() {
        Map<String, List<String>> want = new LinkedHashMap<>();
        want.put("subscriber", List.of("SUBSCRIBER"));
        want.put("imsi", List.of("IMSI"));
        want.put("imei", List.of("IMEI"));
        want.put("msisdn", List.of("MSISDN"));
        want.put("wallet", List.of("WALLET"));
        want.put("account", List.of("ACCOUNT"));
        want.put("agent", List.of("AGENT", "TILL"));
        want.put("handset", List.of("HANDSET"));
        want.put("cell", List.of("CELL", "CELL_ID"));
        Map<String, List<String>> got = new LinkedHashMap<>();
        for (EntityType t : EntityTypes.DEFAULTS) got.put(t.id(), t.classifications());
        assertEquals(want, got);

        Map<String, String> families = new LinkedHashMap<>();
        for (EntityType t : EntityTypes.DEFAULTS) {
            families.put(t.id(), t.family());
            assertFalse(t.definition() == null || t.definition().isBlank(), t.id() + " has a definition");
            assertFalse(t.example() == null || t.example().isBlank(), t.id() + " has an example");
        }
        assertEquals(Map.of("subscriber", "party", "imsi", "identifier", "imei", "device", "msisdn", "identifier",
                "wallet", "account", "account", "account", "agent", "organisation", "handset", "device", "cell", "place"), families);
    }

    @Test
    void theFamiliesAreTheClosedSevenInPickerOrder() {
        assertEquals(List.of("party", "organisation", "account", "identifier", "device", "place", "document"), EntityTypes.familyIds());
        for (Map<String, Object> f : EntityTypes.familiesShape()) {
            assertEquals(List.of("id", "label", "definition"), List.copyOf(f.keySet()));
            assertFalse(((String) f.get("definition")).isBlank());
        }
    }

    @Test
    void aStatedTypeWithoutGuidanceTakesTheCoreDefaultsOfItsIdAndANewIdHasNone() {
        List<EntityType> parsed = EntityTypes.parse(List.of(
                Map.of("id", "msisdn", "label", "Line", "normaliser", "e164", "masked", true, "classifications", List.of("MSISDN")),
                Map.of("id", "note", "label", "Free text", "normaliser", "default", "masked", false, "classifications", List.of())));
        EntityType core = EntityTypes.coreDefault("msisdn");
        assertEquals(new EntityType("msisdn", "Line", "e164", true, List.of("MSISDN"), "identifier", core.definition(), core.example(), "e164"),
                parsed.get(0));
        assertEquals(new EntityType("note", "Free text", "default", false, List.of()), parsed.get(1));
        assertFalse(EntityTypes.shape(List.of(parsed.get(1))).get(0).containsKey("family"), "unstated guidance is not written");
    }

    @Test
    void statedGuidanceWinsAndABadFamilyOrOverlongGuidanceIsRefused() {
        Map<String, Object> t = new LinkedHashMap<>(Map.of("id", "msisdn", "label", "Line", "normaliser", "e164", "masked", true,
                "classifications", List.of("MSISDN"), "family", "party", "definition", " Who called. ", "example", "+1"));
        EntityType p = EntityTypes.parse(List.of(t)).get(0);
        assertEquals("party", p.family());
        assertEquals("Who called.", p.definition());
        assertEquals("+1", p.example());

        t.put("family", "telephony");
        assertTrue(assertThrows(IllegalArgumentException.class, () -> EntityTypes.parse(List.of(t))).getMessage().contains("family must be one of"));
        t.put("family", 7);
        assertTrue(assertThrows(IllegalArgumentException.class, () -> EntityTypes.parse(List.of(t))).getMessage().contains("family must be a string"));
        t.put("family", "party");
        t.put("definition", "x".repeat(400));
        assertEquals(400, EntityTypes.parse(List.of(t)).get(0).definition().length(), "the pack's term bound, not a tighter one");
        t.put("definition", "x".repeat(401));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> EntityTypes.parse(List.of(t))).getMessage().contains("definition is longer"));
        t.put("definition", "ok");
        t.put("example", "x".repeat(65));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> EntityTypes.parse(List.of(t))).getMessage().contains("example is longer"));
        t.put("example", "+1");

        // ENTITY-TYPE-CATALOG-1 P1: a stated shape is one of the closed vocabulary, and the wire carries it
        t.put("shape", "digits(12)");
        assertEquals("digits(12)", EntityTypes.shape(EntityTypes.parse(List.of(t))).get(0).get("shape"));
        t.put("shape", "\\d+");
        assertTrue(assertThrows(IllegalArgumentException.class, () -> EntityTypes.parse(List.of(t))).getMessage().contains("value shape must be one of"));
    }

    /** A pack relabels a type and lends it its term's definition; the family and example stay the core's. */
    @Test
    void aPackOverlayKeepsTheFamilyAndTakesTheTermDefinition() {
        IndustryPack telecom = IndustryPacks.bundled().find("telecom");
        IndustryPack money = IndustryPacks.bundled().find("mobile-money");
        EntityType core = EntityTypes.coreDefault("msisdn");
        EntityType tel = telecom.overlay(EntityTypes.DEFAULTS).stream().filter(x -> x.id().equals("msisdn")).findFirst().orElseThrow();
        assertEquals("identifier", tel.family());
        assertEquals(core.example(), tel.example());
        assertEquals(telecom.term("entity_type.msisdn").definition(), tel.definition());
        EntityType mm = money.overlay(EntityTypes.DEFAULTS).stream().filter(x -> x.id().equals("msisdn")).findFirst().orElseThrow();
        assertEquals(core.definition(), mm.definition(), "no term, the core definition stands");
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
                new EntityType("agent", "Agent / till", "upper-trim", false, List.of("AGENT", "TILL"),
                        "organisation", "A shop that serves customers.", "TILL-1", "free"),
                new EntityType("msisdn", "MSISDN", "e164", true, List.of("MSISDN"),
                        "identifier", "A telephone number.", "+44 7700 900001", "e164"),
                new EntityType("note", "Free text", "default", false, List.of()));
        Path f = dir.resolve(LinkAnalysisSettings.FILE);
        new LinkAnalysisSettings(1200, null, null, "all", null, null, types, null, null, null, null, null, null, null, null).write(f);
        LinkAnalysisSettings back = LinkAnalysisSettings.read(f);
        assertEquals(types, back.entityTypes(), Files.readString(f));
        assertEquals(1200, back.projectionNodeCap());
        assertEquals("all", back.maskingMode());

        new LinkAnalysisSettings(null, null, null, null, null, null, null, null, null, null, null, null, null, null, null).write(f);
        assertFalse(Files.readString(f).contains("entity_types"), "written only when stated");
        assertNull(LinkAnalysisSettings.read(f).entityTypes());
        assertEquals(EntityTypes.DEFAULTS, LinkAnalysisSettings.read(f).effectiveEntityTypes());
    }

    /** A hand-edited invalid list reads as inherit, and costs none of the other keys. */
    @Test
    void anInvalidStoredListReadsAsInherit(@TempDir Path dir) throws Exception {
        Path f = dir.resolve(LinkAnalysisSettings.FILE);
        new LinkAnalysisSettings(700, null, null, null, null, null,
                List.of(new EntityType("imsi", "IMSI", "digits", true, List.of("IMSI"))), null, null, null, null, null, null, null, null).write(f);
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
