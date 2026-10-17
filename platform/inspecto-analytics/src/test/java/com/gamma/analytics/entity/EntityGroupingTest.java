package com.gamma.analytics.entity;

import static org.junit.jupiter.api.Assertions.*;

import com.gamma.analytics.similarity.SimilaritySql;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;

/** The JVM form of entity grouping (ANALYTICS-CORE-1 S2): hand cases, the caps, determinism and D-M1. */
class EntityGroupingTest {

    static EntityRecord rec(String key, String... kv) {
        Map<String, String> m = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put(kv[i], kv[i + 1]);
        return new EntityRecord(key, m);
    }

    static final List<EntityRecord> PEOPLE = List.of(
            rec("c4", "email", "ANNA@example.com ", "phone", "+44 20 7946 0001", "country", "GB", "name", "Anna Kowalska"),
            rec("c2", "email", "anna@example.com", "phone", null, "country", "gb", "name", "Anna Kowalski"),
            rec("c7", "email", null, "phone", "020-7946-0001", "country", "GB", "name", "A. Kowalska"),   // via phone only
            rec("c1", "email", "bob@example.com", "phone", "0000", "country", "GB", "name", "Bob Stone"),
            rec("c9", "email", "", "phone", "0000", "country", "FR", "name", "Bob Stone"),           // other block
            rec("c3", "email", "   ", "phone", null, "country", "GB", "name", "Bobby Stone"));      // blank: absent

    static final MatchCondition BY_EMAIL = MatchCondition.of(Normalization.TEXT, "email");
    static final MatchCondition BY_PHONE = MatchCondition.of(Normalization.DIGITS, "phone");

    @Test
    void conditionsJoinTransitivelyAndTheGroupIdIsTheSmallestKey() {
        // c4-c2 share an email; c4-c7 do NOT share a phone as written ("+44 20 7946 0001" -> 442079460001 vs 02079460001)
        var r = EntityGrouping.group(EntityGrouping.Spec.of("id", List.of(), List.of(BY_EMAIL, BY_PHONE)), PEOPLE);
        assertEquals(List.of("c2", "c2", "c7", "c1", "c1", "c3"), new ArrayList<>(r.groups().values()));
        assertEquals(List.of("c4", "c2", "c7", "c1", "c9", "c3"), new ArrayList<>(r.groups().keySet()));
    }

    @Test
    void aBlockingKeyKeepsMatchesInsideTheBlockAndAnAbsentBlockingKeyGroupsNothing() {
        List<KeyColumn> country = List.of(new KeyColumn("country", Normalization.TEXT));
        var r = EntityGrouping.group(EntityGrouping.Spec.of("id", country, List.of(BY_EMAIL, BY_PHONE)), PEOPLE);
        assertEquals("c1", r.groups().get("c1"));
        assertEquals("c9", r.groups().get("c9"), "same phone, other country: another block");
        assertEquals("c2", r.groups().get("c4"));

        List<EntityRecord> noCountry = List.of(rec("a", "email", "x@y"), rec("b", "email", "x@y", "country", "GB"));
        var none = EntityGrouping.group(EntityGrouping.Spec.of("id", country, List.of(BY_EMAIL)), noCountry);
        assertEquals(Map.of("a", "a", "b", "b"), none.groups());
    }

    @Test
    void aConditionNeedsEveryColumnPresentAndEqual() {
        MatchCondition both = new MatchCondition(List.of(new KeyColumn("last", Normalization.TEXT),
                new KeyColumn("dob", Normalization.NONE)));
        List<EntityRecord> rs = List.of(rec("1", "last", "Müller", "dob", "1980-01-02"),
                rec("2", "last", "MULLER", "dob", "1980-01-02"), rec("3", "last", "muller", "dob", null),
                rec("4", "last", "muller", "dob", "1980-01-03"));
        var r = EntityGrouping.group(EntityGrouping.Spec.of("id", List.of(), List.of(both)), rs);
        assertEquals(List.of("1", "1", "3", "4"), new ArrayList<>(r.groups().values()));
    }

    @Test
    void theGroupsDoNotDependOnInputOrder() {
        Random rnd = new Random(7);
        List<EntityRecord> rs = EntityGroupingParityTest.randomRecords(rnd, 400);
        EntityGrouping.Spec spec = EntityGroupingParityTest.spec(true, null);
        Map<String, String> first = EntityGrouping.group(spec, rs).groups();
        for (int k = 0; k < 5; k++) {
            List<EntityRecord> shuffled = new ArrayList<>(rs);
            Collections.shuffle(shuffled, rnd);
            assertEquals(new HashMap<>(first), new HashMap<>(EntityGrouping.group(spec, shuffled).groups()));
        }
    }

    @Test
    void anEmptyOrRepeatedKeyIsRefused() {
        var spec = EntityGrouping.Spec.of("id", List.of(), List.of(BY_EMAIL));
        var empty = assertThrows(IllegalStateException.class,
                () -> EntityGrouping.group(spec, List.of(rec(null, "email", "a"), rec("x", "email", "a"))));
        assertTrue(empty.getMessage().contains("1 record(s) have an empty 'id'"), empty.getMessage());
        var twice = assertThrows(IllegalStateException.class,
                () -> EntityGrouping.group(spec, List.of(rec("x", "email", "a"), rec("x", "email", "b"))));
        assertTrue(twice.getMessage().contains("more than once"), twice.getMessage());
    }

    @Test
    void theCapsFailClosedBeforeAnythingIsGrouped() {
        List<EntityRecord> placeholder = new ArrayList<>();
        for (int i = 0; i < 5; i++) placeholder.add(rec("r" + i, "phone", "0000"));
        var tight = new EntityGrouping.Spec("id", List.of(), List.of(BY_PHONE), null, 4, 1_000);
        var block = assertThrows(IllegalStateException.class, () -> EntityGrouping.group(tight, placeholder));
        assertTrue(block.getMessage().startsWith("a block holds 5 records, more than the cap of 4"), block.getMessage());

        var fewPairs = new EntityGrouping.Spec("id", List.of(), List.of(BY_PHONE), null, 10, 9);
        var pairs = assertThrows(IllegalStateException.class, () -> EntityGrouping.group(fewPairs, placeholder));
        assertTrue(pairs.getMessage().startsWith("10 candidate pairs, more than the cap of 9"), pairs.getMessage());

        assertEquals(5, EntityGrouping.group(new EntityGrouping.Spec("id", List.of(), List.of(BY_PHONE), null, 5, 10),
                placeholder).groups().size());
    }

    @Test
    void aSpecRefusesBadShapes() {
        assertThrows(IllegalArgumentException.class, () -> EntityGrouping.Spec.of("id", List.of(), List.of()));
        var review = new SimilarityCandidate("name", SimilaritySql.Method.JARO_WINKLER, true, 0.9);
        var noBlock = assertThrows(IllegalArgumentException.class,
                () -> new EntityGrouping.Spec("id", List.of(), List.of(BY_EMAIL), review, 10, 10));
        assertTrue(noBlock.getMessage().contains("blocking"), noBlock.getMessage());
        assertThrows(IllegalArgumentException.class,
                () -> new EntityGrouping.Spec("id", List.of(), List.of(BY_EMAIL), null, 1, 10));
        assertThrows(IllegalArgumentException.class,
                () -> new EntityGrouping.Spec("id", List.of(), List.of(BY_EMAIL), null, 10, EntityGrouping.MAX_PAIRS_CEILING + 1));
        assertThrows(IllegalArgumentException.class, () -> new SimilarityCandidate("n", SimilaritySql.Method.JARO, true, 1.5));
    }

    @Test
    void normalizationsAndTheEmptyRule() {
        assertEquals("jose muller", Normalization.TEXT.apply("  José   MÜLLER\t"));
        assertEquals("442079460001", Normalization.DIGITS.apply("+44 (20) 7946-0001"));
        assertNull(Normalization.DIGITS.apply("n/a"));
        assertNull(Normalization.TEXT.apply(" \t "));
        assertNull(Normalization.NONE.apply(""));
        assertEquals(" a ", Normalization.NONE.apply(" a "));
        assertNull(Normalization.TEXT.apply(null));
    }

    @Test
    void textOrderIsCodePointOrder() {
        assertTrue(EntityGrouping.compareText("", "😀") < 0, "U+E000 sorts before U+1F600 by code point");
        assertTrue("".compareTo("😀") > 0, "...but after it by UTF-16 unit, which is why compareText exists");
        assertTrue(EntityGrouping.compareText("ab", "abc") < 0);
        assertEquals(0, EntityGrouping.compareText("é", "é"));
    }

    // ── D-M1: identity is never asserted by similarity ──────────────────────────────

    @Test
    void aMatchConditionCannotCarryASimilarityMethodScoreOrThreshold() {
        for (Class<?> type : List.of(MatchCondition.class, KeyColumn.class))
            for (RecordComponent c : type.getRecordComponents()) {
                assertNotEquals(SimilaritySql.Method.class, c.getType(), type + "." + c.getName());
                assertNotEquals(double.class, c.getType(), type + "." + c.getName());
                assertNotEquals(Double.class, c.getType(), type + "." + c.getName());
            }
        assertEquals(List.of(String.class, Normalization.class),
                Arrays.stream(KeyColumn.class.getRecordComponents()).map(RecordComponent::getType).toList());
        assertFalse(MatchCondition.class.isAssignableFrom(SimilarityCandidate.class));
        // the spec's conditions are typed MatchCondition; the similarity review is its own component
        assertEquals(List.class, EntityGrouping.Spec.class.getRecordComponents()[2].getType());
        assertEquals(SimilarityCandidate.class, EntityGrouping.Spec.class.getRecordComponents()[3].getType());
    }

    @Test
    void aSimilarityReviewNeverMovesARecordBetweenGroups() {
        List<KeyColumn> country = List.of(new KeyColumn("country", Normalization.TEXT));
        var plain = EntityGrouping.Spec.of("id", country, List.of(BY_EMAIL, BY_PHONE));
        // threshold 0: every pair is "a candidate", the strongest possible pull toward merging
        var everything = new SimilarityCandidate("name", SimilaritySql.Method.JARO_WINKLER, true, 0.0);
        var reviewed = new EntityGrouping.Spec("id", country, List.of(BY_EMAIL, BY_PHONE), everything, 1_000, 1_000_000);
        var a = EntityGrouping.group(plain, PEOPLE);
        var b = EntityGrouping.group(reviewed, PEOPLE);
        assertEquals(a.groups(), b.groups());
        assertTrue(a.candidates().isEmpty());

        // "Bob Stone" (c1) and "Bobby Stone" (c3) are alike but share no email or phone: separate groups, c3 flagged
        assertEquals("c3", b.groups().get("c3"));
        EntityGrouping.Candidate c3 = b.candidates().get("c3");
        assertTrue(c3.review());
        assertNotEquals(b.groups().get("c3"), c3.group(), "a candidate always points at ANOTHER group");
        assertEquals(SimilaritySql.Method.JARO_WINKLER.score("bobby stone", "bob stone"), c3.score());
        // c9 is alone in its block: nothing to compare with
        assertEquals(new EntityGrouping.Candidate(null, null, false), b.candidates().get("c9"));
    }

    @Test
    void theResultCannotBeEditedByACaller() {
        var r = EntityGrouping.group(EntityGrouping.Spec.of("id", List.of(), List.of(BY_EMAIL)), PEOPLE);
        assertThrows(UnsupportedOperationException.class, () -> r.groups().put("c3", "c1"));
    }
}
