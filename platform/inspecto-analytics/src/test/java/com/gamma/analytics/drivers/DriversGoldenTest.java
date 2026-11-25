package com.gamma.analytics.drivers;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Hand-computed goldens for I-4 contribution / surprise / minimal cover, I-5 mix vs rate and I-6 moved-with. */
class DriversGoldenTest {

    private static final double EPS = 1e-12;

    /** Region before → after: N 100 → 150, S 200 → 190, E 100 → 160; totals 400 → 500, Δ = 100. */
    private static final List<Drivers.Element> REGIONS = List.of(
            new Drivers.Element("N", 100, 150), new Drivers.Element("S", 200, 190), new Drivers.Element("E", 100, 160));

    @Test
    void contributionsAndSharesSumToTheChange() {
        Drivers.Dimension d = Drivers.explain("region", REGIONS, 0.8);
        assertEquals(400, d.before(), EPS);
        assertEquals(500, d.after(), EPS);
        assertEquals(100, d.delta(), EPS);
        // largest share first: E +60 (0.6), N +50 (0.5), S −10 (−0.1)
        assertEquals(List.of("E", "N", "S"), d.elements().stream().map(Drivers.Scored::value).toList());
        assertEquals(60, d.elements().get(0).contribution(), EPS);
        assertEquals(0.6, d.elements().get(0).share(), EPS);
        assertEquals(0.5, d.elements().get(1).share(), EPS);
        assertEquals(-0.1, d.elements().get(2).share(), EPS);
        assertEquals(1.0, d.elements().stream().mapToDouble(Drivers.Scored::share).sum(), EPS);
    }

    @Test
    void surpriseIsTheJensenShannonOfTheShares() {
        // p = .25 .5 .25, q = .3 .38 .32; N: m = .275, ½(.25·ln(.25/.275) + .3·ln(.3/.275)) = 0.0011379340729
        Drivers.Dimension d = Drivers.explain("region", REGIONS, 0.8);
        Drivers.Scored n = d.elements().stream().filter(s -> s.value().equals("N")).findFirst().orElseThrow();
        assertEquals(0.0011379340729038, n.surprise(), 1e-15);
        assertEquals(0.0073961745371377, d.surprise(), 1e-15);
        assertEquals(0, Drivers.jsTerm(0, 0));
        assertEquals(Math.log(2) / 2, Drivers.jsTerm(1, 0), EPS);   // a value that vanished entirely
    }

    @Test
    void minimalCoverTakesTheFewestValues() {
        Drivers.Dimension d80 = Drivers.explain("region", REGIONS, 0.8);
        assertEquals(List.of("E", "N"), d80.cover().stream().map(Drivers.Scored::value).toList());
        assertEquals(1.1, d80.covered(), EPS);
        assertTrue(d80.explains());
        Drivers.Dimension d50 = Drivers.explain("region", REGIONS, 0.5);
        assertEquals(List.of("E"), d50.cover().stream().map(Drivers.Scored::value).toList());
    }

    @Test
    void aSpreadOutChangeDoesNotExplain() {
        // offsetting values: positive shares can exceed 1; an unchanged total (Δ = 0) has no shares and explains nothing
        List<Drivers.Element> flat = List.of(new Drivers.Element("a", 10, 15), new Drivers.Element("b", 10, 5),
                new Drivers.Element("c", 10, 12));   // Δ = 2, shares 2.5, −2.5, 1 → positives reach the target
        assertTrue(Drivers.explain("x", flat, 0.8).explains());
        List<Drivers.Element> none = List.of(new Drivers.Element("a", 10, 10), new Drivers.Element("b", 5, 5));
        Drivers.Dimension zero = Drivers.explain("y", none, 0.8);
        assertFalse(zero.explains());
        assertTrue(zero.cover().isEmpty());
        assertTrue(Double.isNaN(zero.elements().get(0).share()));
    }

    @Test
    void negativeValuesHaveNoSurprise() {
        Drivers.Dimension d = Drivers.explain("x", List.of(new Drivers.Element("a", -5, 10), new Drivers.Element("b", 10, 10)), 0.8);
        assertTrue(Double.isNaN(d.surprise()));
        assertEquals(1.0, d.cover().get(0).share(), EPS);
    }

    @Test
    void rankPutsExplainingSurprisingDimensionsFirst() {
        Drivers.Dimension region = Drivers.explain("region", REGIONS, 0.8);
        Drivers.Dimension flat = Drivers.explain("plan", List.of(new Drivers.Element("p", 200, 250), new Drivers.Element("q", 200, 250)), 0.8);
        Drivers.Dimension none = Drivers.explain("z", List.of(new Drivers.Element("a", 10, 10)), 0.8);
        // plan: shares unchanged (surprise 0) — explains with 2 values, but region is more surprising
        assertEquals(List.of("region", "plan", "z"), Drivers.rank(List.of(none, flat, region)).stream().map(Drivers.Dimension::name).toList());
    }

    @Test
    void refusesABadCoverTarget() {
        assertThrows(IllegalArgumentException.class, () -> Drivers.explain("x", REGIONS, 0));
        assertThrows(IllegalArgumentException.class, () -> Drivers.explain("x", REGIONS, 1.5));
    }

    // ── I-5 mix vs rate ─────────────────────────────────────────────────────────────────────────────────────────

    @Test
    void mixPlusRateIsExactlyTheRatioChange() {
        // A 50/100 → 90/150 (rate .5 → .6), B 30/100 → 15/50 (rate .3 → .3); R .4 → .525, Δ .125
        // weights A .5 → .75, B .5 → .25; mix = .25·.5 − .25·.3 = .05; rate = .75·.1 + .25·0 = .075
        ShiftShare.Result r = ShiftShare.decompose(List.of(
                new ShiftShare.Slice("A", 50, 100, 90, 150), new ShiftShare.Slice("B", 30, 100, 15, 50)));
        assertEquals(0.4, r.before(), EPS);
        assertEquals(0.525, r.after(), EPS);
        assertEquals(0.125, r.delta(), EPS);
        assertEquals(0.05, r.mix(), EPS);
        assertEquals(0.075, r.rate(), EPS);
        assertEquals(r.delta(), r.mix() + r.rate(), EPS);
        assertEquals(0.125, r.parts().get(0).mix(), EPS);
        assertEquals(-0.075, r.parts().get(1).mix(), EPS);
    }

    @Test
    void aSliceThatAppearsIsAllMix() {
        // A 10/100 → 30/100, C new 0/0 → 20/100; R .1 → .25; w A 1 → .5, C 0 → .5; C's earlier rate := its later .2
        // mix = −.5·.1 + .5·.2 = .05; rate = .5·.2 + 0 = .1; sum .15
        ShiftShare.Result r = ShiftShare.decompose(List.of(
                new ShiftShare.Slice("A", 10, 100, 30, 100), new ShiftShare.Slice("C", 0, 0, 20, 100)));
        assertEquals(0.15, r.delta(), EPS);
        assertEquals(0.05, r.mix(), EPS);
        assertEquals(0.1, r.rate(), EPS);
        assertEquals(0.0, r.parts().get(1).rate(), EPS);
        // and the mirror: a slice that disappears
        ShiftShare.Result gone = ShiftShare.decompose(List.of(
                new ShiftShare.Slice("A", 30, 100, 10, 100), new ShiftShare.Slice("C", 20, 100, 0, 0)));
        assertEquals(gone.delta(), gone.mix() + gone.rate(), EPS);
        assertEquals(0.0, gone.parts().get(1).rate(), EPS);
    }

    @Test
    void aZeroTotalDenominatorIsRefused() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> ShiftShare.decompose(List.of(new ShiftShare.Slice("A", 0, 0, 5, 10))));
        assertTrue(e.getMessage().contains("earlier"), e.getMessage());
    }

    @Test
    void aNumeratorWithoutADenominatorIsRefusedNotSplitWrongly() {
        // A 10/100 → 10/100, B 5/0 → 0/0: R .15 → .10, but no w·r term can carry B's 5 — mix + rate would be 0, not −.05
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> ShiftShare.decompose(List.of(
                new ShiftShare.Slice("A", 10, 100, 10, 100), new ShiftShare.Slice("B", 5, 0, 0, 0))));
        assertTrue(e.getMessage().contains("no denominator"), e.getMessage());
    }

    @Test
    void aRoundingResidueIsNoChange() {
        // 0.1 + 0.2 ≠ 0.3 in doubles: the totals differ by ~5e-17, which must not explain anything
        Drivers.Dimension d = Drivers.explain("x", List.of(new Drivers.Element("a", 0.1, 0.3), new Drivers.Element("b", 0.2, 0)), 0.8);
        assertEquals(0, d.delta());
        assertFalse(d.explains());
    }

    // ── I-6 moved with it ───────────────────────────────────────────────────────────────────────────────────────

    @Test
    void correlatesTheChangesNotTheLevels() {
        Double[] a = {1d, 2d, 4d, 7d, 11d};          // changes 1 2 3 4
        Double[] same = {0d, 2d, 6d, 12d, 20d};      // changes 2 4 6 8 → r = 1
        Double[] opposite = {0d, 4d, 7d, 9d, 10d};   // changes 4 3 2 1 → r = −1
        assertEquals(1.0, MovedWith.ofChanges(a, same).r(), EPS);
        assertEquals(-1.0, MovedWith.ofChanges(a, opposite).r(), EPS);
        assertEquals(4, MovedWith.ofChanges(a, same).pairs());
    }

    @Test
    void aMissingBucketBreaksItsPairsAndIsNeverZero() {
        Double[] a = {1d, 2d, null, 4d, 5d, 7d};
        Double[] b = {1d, 2d, 3d, 4d, 5d, 7d};
        assertEquals(3, MovedWith.ofChanges(a, b).pairs());   // changes at 1→2, 4→5, 5→7; none across the gap
        assertTrue(Double.isNaN(MovedWith.ofChanges(new Double[]{1d, 2d}, new Double[]{3d, 4d}).r()));
    }
}
