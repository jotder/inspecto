package com.gamma.screening;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link Screener} over a real Entity List fact log (SCR-D3 … SCR-D9, SCR-D14). */
class ScreenerTest {

    private static final double T = Screener.DEFAULT_THRESHOLD;

    private static List<Screener.Match> screen(Path root, Screener.Subject s, String... lists) throws Exception {
        Instant now = Instant.now();
        return Screener.screen(s, Screener.load(root, List.of(lists), now), T, Screener.DEFAULT_MAX_MATCHES, now);
    }

    private static Screener.Subject name(String n) {
        return new Screener.Subject("c1", n, null);
    }

    @Test
    void aNameMatchesAListMemberWithItsScoreAndAnIdentifierMatchesExactly(@TempDir Path root) throws Exception {
        ScreeningLists.unmasked(root);
        ScreeningLists.create(root, "sanctions", "block", "subscriber", "default");
        ScreeningLists.add(root, "sanctions", "default", List.of("Vladimir Putin", "Osama bin Laden", "Kim Jong Un"));
        ScreeningLists.create(root, "deny", "block", "msisdn", "e164");
        ScreeningLists.add(root, "deny", "e164", List.of("+44 7700 900123"));

        List<Screener.Match> m = screen(root, name("Putin, Vladimir V."), "sanctions", "deny");
        assertEquals(1, m.size(), m.toString());
        assertEquals("sanctions", m.get(0).listId());
        assertEquals("block", m.get(0).purpose());
        assertEquals("vladimir putin", m.get(0).entry(), "the entry as the (unmasked) list renders it");
        assertEquals("name", m.get(0).method());
        assertTrue(m.get(0).score() >= T && m.get(0).score() < 1.0, String.valueOf(m.get(0).score()));

        List<Screener.Match> id = screen(root, new Screener.Subject("c2", null, "00447700900123"), "sanctions", "deny");
        assertEquals(List.of(new Screener.Match("deny", "block", "+447700900123", "identifier", 1.0)), id,
                "identifier under the list's sealed e164 normaliser");

        assertEquals(List.of(), screen(root, name("Jane Doe"), "sanctions"), "no match below the threshold");
    }

    @Test
    void anIdentifierMatchesTheListsRangeEntries(@TempDir Path root) throws Exception {
        ScreeningLists.unmasked(root);
        ScreeningLists.create(root, "deny", "block", "msisdn", "e164");
        ScreeningLists.addRanges(root, "deny", List.of("prefix:+4478"));
        List<Screener.Match> m = screen(root, new Screener.Subject("c", null, "+447812345678"), "deny");
        assertEquals(1, m.size());
        assertEquals("prefix:+4478", m.get(0).entry());
        assertEquals("identifier", m.get(0).method());
    }

    @Test
    void lookAlikeSpellingIsCaught(@TempDir Path root) throws Exception {
        ScreeningLists.unmasked(root);
        ScreeningLists.create(root, "sanctions", "block", "subscriber", "default");
        ScreeningLists.add(root, "sanctions", "default", List.of("Osama bin Laden"));
        List<Screener.Match> m = screen(root, name("Οsаma b1n Laden"), "sanctions");   // Greek O, Cyrillic a, digit one
        assertEquals(1, m.size(), m.toString());
        assertEquals(1.0, m.get(0).score());
    }

    @Test
    void anExpiredEntryAndARetiredListNeverMatch(@TempDir Path root) throws Exception {
        ScreeningLists.unmasked(root);
        ScreeningLists.create(root, "watch", "watch", "subscriber", "default");
        ScreeningLists.addExpiring(root, "watch", List.of("john smith"), "2020-01-01T00:00:00Z");
        assertEquals(List.of(), screen(root, name("John Smith"), "watch"), "expired");

        ScreeningLists.create(root, "old", "block", "subscriber", "default");
        ScreeningLists.add(root, "old", "default", List.of("John Smith"));
        assertEquals(1, screen(root, name("John Smith"), "old").size());
        ScreeningLists.retire(root, "old");
        assertEquals(List.of(), screen(root, name("John Smith"), "old"), "a retired list matches nothing");
    }

    @Test
    void anUnknownListIsRefusedByName(@TempDir Path root) throws Exception {
        ScreeningLists.unmasked(root);
        ScreeningLists.create(root, "sanctions", "block", "subscriber", "default");
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> Screener.load(root, List.of("sanctions", "nope"), Instant.now()));
        assertTrue(e.getMessage().contains("nope"), e.getMessage());
    }

    @Test
    void matchesAreBestFirstAndCapped(@TempDir Path root) throws Exception {
        ScreeningLists.unmasked(root);
        ScreeningLists.create(root, "pep", "watch", "subscriber", "default");
        ScreeningLists.add(root, "pep", "default", List.of("Ivan Petrov", "Ivan Petrova", "Ivan Petrovic", "Iwan Petrow"));
        Instant now = Instant.now();
        List<Screener.Prepared> lists = Screener.load(root, List.of("pep"), now);
        List<Screener.Match> all = Screener.screen(name("Ivan Petrov"), lists, T, 20, now);
        assertEquals("ivan petrov", all.get(0).entry());
        assertEquals(1.0, all.get(0).score());
        for (int i = 1; i < all.size(); i++) assertTrue(all.get(i - 1).score() >= all.get(i).score(), all.toString());
        assertEquals(2, Screener.screen(name("Ivan Petrov"), lists, T, 2, now).size(), "maxMatches caps the answer");
    }

    @Test
    void blockingComparesOnlyEntriesSharingATokenInitial(@TempDir Path root) throws Exception {
        // SCR-D9's recorded cost: a different first letter in EVERY token is never compared (Gaddafi / Qadhafi alone).
        ScreeningLists.unmasked(root);
        ScreeningLists.create(root, "sanctions", "block", "subscriber", "default");
        ScreeningLists.add(root, "sanctions", "default", List.of("Qadhafi"));
        assertEquals(List.of(), screen(root, name("Gaddafi"), "sanctions"));
        ScreeningLists.add(root, "sanctions", "default", List.of("Moammar Qadhafi"));
        assertEquals(1, screen(root, name("Muammar Gaddafi"), "sanctions").size(), "one shared initial is enough");
    }

    @Test
    void aMaskedListShowsItsEntryAsAMaskToken(@TempDir Path root) throws Exception {
        // No link-analysis.toon: maskingMode typed, and the default Entity Type 'subscriber' is masked.
        ScreeningLists.create(root, "sanctions", "block", "subscriber", "default");
        ScreeningLists.add(root, "sanctions", "default", List.of("Vladimir Putin"));
        List<Screener.Match> m = screen(root, name("Vladimir Putin"), "sanctions");
        assertEquals(1, m.size());
        assertTrue(m.get(0).entry().matches("masked:[0-9a-f]{16}"), m.get(0).entry());
    }
}
