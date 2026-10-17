package com.gamma.entitystore;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Data-prep acceptance <b>AC-09</b> (LA-EDITIONS-1 §3): the core holds no word derived from a shipped Industry pack, a
 * second pack loads alongside the first, and a collision fails closed at load. The banned words are DERIVED from the
 * shipped packs' terms (every label and abbreviation), never hand-kept. The server's neutral labels are checked here;
 * the text the SPA and the server modules show is checked by {@code tools/check-vocabulary.mjs} rule
 * {@code industry-pack-term}, from the same term files.
 */
class IndustryPackNeutralityTest {

    /** Every pack label and abbreviation (an abbreviation such as {@code CDR / XDR} yields both halves). */
    private static Set<String> packWords() {
        Set<String> words = new LinkedHashSet<>();
        for (IndustryPack p : IndustryPacks.bundled().installed())
            for (IndustryPack.Term t : p.terms()) {
                words.add(t.label());
                for (String a : t.abbreviation().split("/")) if (!a.isBlank()) words.add(a.trim());
            }
        return words;
    }

    private static List<String> hits(String text, Set<String> words) {
        List<String> out = new ArrayList<>();
        for (String w : words)
            if (Pattern.compile("(?<![A-Za-z0-9_-])" + Pattern.quote(w.toLowerCase(Locale.ROOT)) + "(?![A-Za-z0-9_-])")
                    .matcher(text.toLowerCase(Locale.ROOT)).find()) out.add(w);
        return out;
    }

    @Test
    void theDerivedWordListIsNotEmpty() {
        Set<String> words = packWords();
        assertTrue(words.contains("MSISDN") && words.contains("Wallet") && words.contains("CDR"), words.toString());
    }

    @Test
    void theCoreEntityTypeLabelsHoldNoPackWord() {
        Set<String> words = packWords();
        for (EntityTypes.EntityType t : EntityTypes.DEFAULTS)
            assertEquals(List.of(), hits(t.label(), words), "core Entity Type '" + t.id() + "' label '" + t.label() + "'");
    }

    @Test
    void theCoreConceptLabelsHoldNoPackWord() {
        Set<String> words = packWords();
        for (var e : IndustryPack.CONCEPTS.entrySet())
            assertEquals(List.of(), hits(e.getValue(), words), "core concept '" + e.getKey() + "' label '" + e.getValue() + "'");
    }

    @Test
    void aSecondPackLoadsAlongsideTheFirstAndEachRelabelsOnlyItsOwnTerms() {
        IndustryPack telecom = IndustryPacks.bundled().find("telecom");
        IndustryPack money = IndustryPacks.bundled().find("mobile-money");
        assertEquals("MSISDN", label(telecom.overlay(EntityTypes.DEFAULTS), "msisdn"));
        assertEquals("Phone number", label(money.overlay(EntityTypes.DEFAULTS), "msisdn"), "mobile money does not relabel the phone number");
        assertEquals("Wallet", label(money.overlay(EntityTypes.DEFAULTS), "wallet"));
        assertFalse(label(telecom.overlay(EntityTypes.DEFAULTS), "wallet").equals("Wallet"));
    }

    private static String label(List<EntityTypes.EntityType> types, String id) {
        return types.stream().filter(t -> t.id().equals(id)).findFirst().orElseThrow().label();
    }
}
