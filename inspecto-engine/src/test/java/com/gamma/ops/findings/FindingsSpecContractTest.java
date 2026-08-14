package com.gamma.ops.findings;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The cross-language contract for the <b>{@code AttributeSpec} vocabulary</b> (BACKLOG §6, D6 residual):
 * the control types, disclosure tiers and section keys this side validates must match the ones the Angular
 * renderer can actually draw.
 *
 * <p><b>Why it is needed.</b> {@code AttributeSpec} (`inspecto-ui/.../component-model/attribute-spec.ts`)
 * is deliberately the canonical shape — a `findings-spec` is authored in it and served verbatim, so
 * {@code <inspecto-schema-form>} needs no translation. The cost of that choice is that
 * {@link FindingsSpec#TYPES} / {@link FindingsSpec#TIERS} are a hand-kept MIRROR, and drift is silent in
 * the worse direction: a member added only in TypeScript makes the server <b>422 a section the renderer
 * could have drawn</b>, and the author sees a validation error naming a type their form offers.
 *
 * <p><b>Why a committed file rather than generation</b> — the same reasoning as
 * {@link com.gamma.query.MeasureGrammarContractTest}: both sides compare to ONE checked-in artifact, so a
 * divergence lands as a reviewable diff on the contract file instead of being absorbed by whichever side
 * ran a generator.
 *
 * <p><b>Order is not part of this contract</b> (unlike the measure grammar, whose list is joined into a
 * user-visible message) — {@code TYPES}/{@code TIERS} are {@code Set}s here, so the file is sorted and both
 * sides compare sorted.
 */
class FindingsSpecContractTest {

    private static final String CONTRACT = "inspecto-ui/src/app/inspecto/mock/attribute-spec.contract.json";

    private static final ObjectMapper JSON = new ObjectMapper();

    /** Walk up from the module's CWD to the repo root, so the path works under surefire and an IDE alike. */
    private static Path contractPath() {
        Path dir = Path.of("").toAbsolutePath();
        for (int up = 0; up < 4 && dir != null; up++, dir = dir.getParent()) {
            Path candidate = dir.resolve(CONTRACT);
            if (Files.exists(candidate)) return candidate;
        }
        throw new AssertionError("cannot locate " + CONTRACT + " from " + Path.of("").toAbsolutePath());
    }

    @SuppressWarnings("unchecked")
    private static List<String> published(String key) throws IOException {
        Map<String, Object> contract = JSON.readValue(contractPath().toFile(), Map.class);
        List<String> list = (List<String>) contract.get(key);
        assertNotNull(list, "contract file has no `" + key + "` key");
        return list;
    }

    @Test
    void theControlTypesThisSideAcceptsMatchTheCommittedContract() throws IOException {
        assertEquals(published("types"), new ArrayList<>(new TreeSet<>(FindingsSpec.TYPES)),
                "FindingsSpec.TYPES and " + CONTRACT + " disagree — a type the schema form can draw "
                        + "would then be 422'd when authored as a findings-spec, or vice versa");
    }

    @Test
    void theDisclosureTiersThisSideAcceptsMatchTheCommittedContract() throws IOException {
        assertEquals(published("tiers"), new ArrayList<>(new TreeSet<>(FindingsSpec.TIERS)),
                "FindingsSpec.TIERS and " + CONTRACT + " disagree — a tier only one side knows puts a "
                        + "field in the wrong disclosure bucket, or 422s a spec the renderer accepts");
    }

    /**
     * The contract is only meaningful if these names really are what {@link FindingsSpec#fromMap} accepts —
     * the {@code AGGS} precedent's round-trip. {@code select} carries options because it is the one type
     * that requires them.
     */
    @Test
    void everyContractTypeAndTierIsAcceptedAndAnythingElseIsRefused() throws IOException {
        for (String type : published("types")) {
            assertDoesNotThrow(() -> FindingsSpec.fromMap(specWith(section("f", "type", type))),
                    "contract lists type '" + type + "' but fromMap refuses it");
        }
        for (String tier : published("tiers")) {
            assertDoesNotThrow(() -> FindingsSpec.fromMap(specWith(section("f", "tier", tier))),
                    "contract lists tier '" + tier + "' but fromMap refuses it");
        }

        IllegalArgumentException badType = assertThrows(IllegalArgumentException.class,
                () -> FindingsSpec.fromMap(specWith(section("f", "type", "colorpicker"))));
        assertTrue(badType.getMessage().contains("colorpicker"), () -> "unhelpful refusal: " + badType.getMessage());

        IllegalArgumentException badTier = assertThrows(IllegalArgumentException.class,
                () -> FindingsSpec.fromMap(specWith(section("f", "tier", "hidden"))));
        assertTrue(badTier.getMessage().contains("hidden"), () -> "unhelpful refusal: " + badTier.getMessage());
    }

    /**
     * The section-key half. {@code fromMap} rejects unknown keys rather than ignoring them (a typo'd
     * {@code tier} silently defaulting is how a required field becomes invisible), so the published
     * {@code sectionKeys} must be exactly what it tolerates — probed through the parser rather than by
     * widening {@code SECTION_KEYS}' visibility for a test.
     */
    @Test
    void everyContractSectionKeyIsAcceptedByTheParser() throws IOException {
        for (String key : published("sectionKeys")) {
            Map<String, Object> s = section("f", key, sampleValue(key));
            assertDoesNotThrow(() -> FindingsSpec.fromMap(specWith(s)),
                    "contract lists section key '" + key + "' but fromMap refuses it");
        }
    }

    /**
     * ⚠ The deliberate asymmetry, pinned so it stays deliberate: {@code group} and {@code secret} exist on
     * the frontend {@code AttributeSpec} but are <b>not</b> authorable on a findings section. If a future
     * change wants one of them in a findings-spec it must move in the contract file — and this test —
     * rather than being discovered as a 422 by an author.
     */
    @Test
    void aFrontendOnlyKeyIsRefusedOnASection() throws IOException {
        for (String key : published("frontendOnlyKeys")) {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> FindingsSpec.fromMap(specWith(section("f", key, "x"))),
                    "contract calls '" + key + "' frontend-only, but fromMap accepts it on a section");
            assertTrue(e.getMessage().contains(key), () -> "unhelpful refusal: " + e.getMessage());
        }
    }

    // ── fixtures ────────────────────────────────────────────────────────────────

    /** A minimal valid section, plus one key under test. */
    private static Map<String, Object> section(String key, String extraKey, Object extraValue) {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("key", key);
        s.put("label", "Field");
        s.put("type", "string");
        s.put("tier", "optional");
        s.put(extraKey, extraValue);
        if ("select".equals(s.get("type"))) s.put("options", List.of("a", "b"));
        return s;
    }

    private static Map<String, Object> specWith(Map<String, Object> section) {
        return Map.of("objectType", "case", "sections", List.of(section));
    }

    /** A value each section key will actually accept — the parser validates most of them. */
    private static Object sampleValue(String key) {
        return switch (key) {
            case "key" -> "f";
            case "type" -> "string";
            case "tier" -> "optional";
            case "required" -> true;
            case "options" -> List.of("a", "b");
            case "pattern" -> "[a-z]+";
            case "min" -> 0;
            case "max" -> 10;
            // Self-referential on purpose: dependsOn is resolved against the sibling keys, and this
            // single-section fixture is its own only sibling.
            case "dependsOn" -> Map.of("key", "f", "equals", "x");
            default -> "x";   // label / default / help / placeholder are free text
        };
    }
}
