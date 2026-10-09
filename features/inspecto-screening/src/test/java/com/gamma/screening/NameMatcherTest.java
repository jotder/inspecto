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
}
