package com.gamma.screening;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Golden pairs for the Screening name method (SCR-D3). Each group is judged against the default threshold
 * ({@link Screener#DEFAULT_THRESHOLD} 0.85). A change to the method that moves any pair across it must be a
 * deliberate change of this file.
 */
class NameMatcherTest {

    private static final double T = Screener.DEFAULT_THRESHOLD;

    /** Spelling variants, transliterations, reordering, missing middle names and punctuation: the same person. */
    private static final String[][] SAME = {
            {"Vladimir Putin", "Vladimir Putin"},
            {"Putin, Vladimir", "Vladimir Putin"},
            {"Vladimir Vladimirovich Putin", "Vladimir Putin"},
            {"Mohammed Al-Rashid", "Muhammad Al Rashid"},
            {"Mohammed", "Muhammad"},
            {"José Müller", "Jose Mueller"},
            {"Jon Smyth", "John Smith"},
            {"Ivan Petrov", "Ivan Petrova"},
            {"Anna Kowalski", "Anna Kowalska"},
            {"Sergei Ivanov", "Sergey Ivanov"},
            {"Ahmed Hassan", "Ahmad Hasan"},
            {"Abdul Rahman", "Abdulrahman"},
            {"Usama bin Ladin", "Osama bin Laden"},
            {"Muammar Gaddafi", "Moammar Qadhafi"},
            {"Alexander Lukashenko", "Aleksandr Lukashenka"},
            {"Acme Trading LLC", "ACME Trading L.L.C."},
            {"Hans Gruber", "Hans Gruber Holding GmbH"},
            {"Dr. John Smith", "John Smith"},
    };

    /**
     * Look-alikes: a name spelled with letters or digits that LOOK like the listed ones — Cyrillic а / р, Greek ο,
     * a zero for an O, a dotted capital I. They must fold to the listed name exactly.
     */
    private static final String[][] LOOK_ALIKES = {
            {"Vlаdimir Рutin", "Vladimir Putin"},   // Cyrillic a, Cyrillic Er
            {"0sama bin Laden", "Osama bin Laden"},
            {"Οsama bin Laden", "Osama bin Laden"},       // Greek capital Omicron
            {"İstanbul Holding", "Istanbul Holding"},     // Turkish dotted capital I
            {"Jоhn Sm1th", "John Smith"},                 // Cyrillic o, digit one
    };

    /** Different people (one shared token, or a different first name): never a match. */
    private static final String[][] DIFFERENT = {
            {"John Smith", "Jane Smith"},
            {"Mark Lee", "Mike Lee"},
            {"Daniel Ortega", "Daniela Ortiz"},
            {"Kim Jong Un", "Kim Jong Il"},
            {"Tom Lee", "Tommy Leeson"},
            {"Ali", "Ali Hassan Mohammed"},
            {"Alice Johnson", "Bob Williams"},
    };

    /**
     * Near spellings the method flags BY DESIGN (recall over precision: a reviewer dismisses them). Pinned so a
     * change that silently stops flagging one-letter differences is noticed.
     */
    private static final String[][] FLAGGED_NEAR_SPELLINGS = {
            {"Chen Wei", "Chen Wen"},
            {"Maria Garcia", "Mario Garza"},
    };

    @Test
    void theSamePersonScoresAtOrAboveTheThreshold() {
        for (String[] p : SAME)
            assertTrue(NameMatcher.score(p[0], p[1]) >= T, p[0] + " ~ " + p[1] + " = " + NameMatcher.score(p[0], p[1]));
    }

    @Test
    void lookAlikeSpellingsFoldToTheListedName() {
        for (String[] p : LOOK_ALIKES) {
            assertEquals(NameMatcher.fold(p[1]), NameMatcher.fold(p[0]), p[0]);
            assertEquals(1.0, NameMatcher.score(p[0], p[1]), p[0]);
        }
    }

    @Test
    void differentPeopleScoreBelowTheThreshold() {
        for (String[] p : DIFFERENT)
            assertTrue(NameMatcher.score(p[0], p[1]) < T, p[0] + " vs " + p[1] + " = " + NameMatcher.score(p[0], p[1]));
    }

    @Test
    void nearSpellingsAreFlaggedByDesign() {
        for (String[] p : FLAGGED_NEAR_SPELLINGS)
            assertTrue(NameMatcher.score(p[0], p[1]) >= T, p[0] + " ~ " + p[1] + " = " + NameMatcher.score(p[0], p[1]));
    }

    @Test
    void theScoreIsSymmetricBoundedAndRounded() {
        for (String[][] group : List.of(SAME, LOOK_ALIKES, DIFFERENT, FLAGGED_NEAR_SPELLINGS))
            for (String[] p : group) {
                double ab = NameMatcher.score(p[0], p[1]), ba = NameMatcher.score(p[1], p[0]);
                assertEquals(ab, ba, p[0] + " / " + p[1]);
                assertTrue(ab >= 0 && ab <= 1, String.valueOf(ab));
                assertEquals(ab, Math.round(ab * 10_000.0) / 10_000.0, 0.0);
            }
    }

    @Test
    void foldDropsHonorificsAndPunctuationAndKeepsAllDigitTokens() {
        assertEquals("john smith", NameMatcher.fold("  Mr. JOHN   smith, "));
        assertEquals("louis 14", NameMatcher.fold("Louis 14"), "an all-digit token is not read as letters");
        assertEquals("strasse", NameMatcher.fold("Straße"));
        assertEquals(List.of(), NameMatcher.tokens("-- ,, --"));
        assertEquals(0.0, NameMatcher.score("", "John Smith"));
        assertEquals(0.0, NameMatcher.score(null, "John Smith"));
    }

    @Test
    void jaroWinklerMatchesTheReferenceValues() {
        // The textbook pairs (Winkler 1990): MARTHA / MARHTA 0.9611, DWAYNE / DUANE 0.84, DIXON / DICKSONX 0.8133.
        assertEquals(0.9611, NameMatcher.jaroWinkler("martha", "marhta"), 1e-4);
        assertEquals(0.84, NameMatcher.jaroWinkler("dwayne", "duane"), 1e-4);
        assertEquals(0.8133, NameMatcher.jaroWinkler("dixon", "dicksonx"), 1e-4);
        assertEquals(0.0, NameMatcher.jaroWinkler("abc", "xyz"));
    }

    // ---- SCREENING-1 (2): phonetic / transliteration blocking + second reading ----

    private static final String[][] TRANSLITERATIONS = {
            {"Gaddafi", "Qadhafi"}, {"Gaddafi", "Kadhafi"}, {"Gaddafi", "Gadhafi"}, {"Gaddafi", "Khadafi"},
            {"Qadhafi", "Khadafi"}, {"Muammar Gaddafi", "Muammar Qadhafi"},
            {"Muhammad", "Mohammed"}, {"Muhammed", "Mohammed"},
            {"Hussein", "Husain"}, {"Hussein", "Hussain"}, {"Husain", "Hussain"},
            {"Yusuf", "Yousef"},
    };

    /** Not the same person: the phonetic reading must not pull these over the threshold. */
    private static final String[][] STILL_DIFFERENT = {
            {"Hussein", "Hassan"}, {"Gaddafi", "Gandhi"},
            {"Qasim Hussein", "Kassem Hassan"}, {"Muhammad Ali", "Mustafa Kamal"}, {"Yusuf", "Yaser"},
    };

    @Test
    void transliterationVariantsMatchAndShareABlock() {
        for (String[] p : TRANSLITERATIONS) {
            assertTrue(NameMatcher.score(p[0], p[1]) >= T, p[0] + " ~ " + p[1] + " = " + NameMatcher.score(p[0], p[1]));
            var ka = NameMatcher.blockKeys(NameMatcher.tokens(p[0])); var kb = NameMatcher.blockKeys(NameMatcher.tokens(p[1]));
            assertTrue(ka.stream().anyMatch(kb::contains), p[0] + " / " + p[1] + " share no block");
        }
        assertEquals("kadafi", NameMatcher.phonetic("gaddafi"));
        assertEquals("kadafi", NameMatcher.phonetic("qadhafi"));
        assertEquals("husin", NameMatcher.phonetic("hussein"));
    }

    @Test
    void phoneticReadingDoesNotMatchLookAlikeButDifferentNames() {
        for (String[] p : STILL_DIFFERENT)
            assertTrue(NameMatcher.score(p[0], p[1]) < T, p[0] + " vs " + p[1] + " = " + NameMatcher.score(p[0], p[1]));
        assertTrue(NameMatcher.score("Gaddafi", "Qadhafi") <= NameMatcher.PHONETIC_CAP);
    }

    /** Blocking exists for cost: the phonetic keys may add candidates, but only a bounded fraction. */
    @Test
    void phoneticBlockingKeepsCandidatePairsBounded() {
        java.util.Random r = new java.util.Random(42);
        String[] syll = {"ka", "mo", "ha", "sa", "li", "ro", "an", "ben", "dar", "yu", "ga", "qa", "ou", "ei", "dh", "kh", "ma", "ne", "to", "vi"};
        List<List<String>> names = new java.util.ArrayList<>();
        for (int i = 0; i < 3000; i++) {
            StringBuilder n = new StringBuilder();
            for (int w = 0; w < 2 + r.nextInt(2); w++) {
                for (int k = 0; k < 2 + r.nextInt(2); k++) n.append(syll[r.nextInt(syll.length)]);
                n.append(' ');
            }
            names.add(NameMatcher.tokens(n.toString()));
        }
        java.util.Map<Character, List<Integer>> plain = new java.util.HashMap<>(), phon = new java.util.HashMap<>();
        for (int i = 0; i < names.size(); i++) {
            for (String t : names.get(i)) { }
            for (Character c : initialsOf(names.get(i))) plain.computeIfAbsent(c, x -> new java.util.ArrayList<>()).add(i);
            for (Character c : NameMatcher.blockKeys(names.get(i))) phon.computeIfAbsent(c, x -> new java.util.ArrayList<>()).add(i);
        }
        long before = 0, after = 0;
        for (int q = 0; q < 300; q++) {
            before += candidates(plain, initialsOf(names.get(q)));
            after += candidates(phon, NameMatcher.blockKeys(names.get(q)));
        }
        System.out.println("SCREENING-1 candidate pairs (300 subjects x 3000 members): before=" + before + " after=" + after);
        assertTrue(after >= before);
        assertTrue(after <= before * 1.5, "before=" + before + " after=" + after);
    }

    private static java.util.Set<Character> initialsOf(List<String> tokens) {
        java.util.Set<Character> out = new java.util.LinkedHashSet<>();
        for (String t : tokens) out.add(t.charAt(0));
        return out;
    }

    private static long candidates(java.util.Map<Character, List<Integer>> idx, java.util.Set<Character> keys) {
        java.util.Set<Integer> out = new java.util.HashSet<>();
        for (Character c : keys) out.addAll(idx.getOrDefault(c, List.of()));
        return out.size();
    }
}
