package com.gamma.analytics.time;

import static org.junit.jupiter.api.Assertions.*;

import com.gamma.analytics.time.SeasonalityDetector.Detection;
import com.gamma.analytics.time.SeasonalityDetector.Params;
import com.gamma.analytics.time.SeasonalityDetector.Verdict;
import java.util.OptionalDouble;
import java.util.Random;
import org.junit.jupiter.api.Test;

/** Hand ACF figures and the detection rule: significance, minimum cycles, peak, ties, NONE. */
class SeasonalityDetectorTest {

    /** A period-p sine plus seeded noise. */
    static double[] seasonal(int n, int period, double noise, long seed) {
        Random r = new Random(seed);
        double[] x = new double[n];
        for (int t = 0; t < n; t++) x[t] = 100 + 10 * Math.sin(2 * Math.PI * t / period) + noise * r.nextGaussian();
        return x;
    }

    @Test
    void goldenAcf() {
        // x = 1,2,3,4: mean 2.5, deviations -1.5,-0.5,0.5,1.5, sum of squares 5
        double[] x = {1, 2, 3, 4};
        assertEquals(1.0, SeasonalityDetector.acf(x, 0).getAsDouble(), 1e-12);
        assertEquals(1.25 / 5, SeasonalityDetector.acf(x, 1).getAsDouble(), 1e-12);   // (.75 - .25 + .75) / 5
        assertEquals(-1.5 / 5, SeasonalityDetector.acf(x, 2).getAsDouble(), 1e-12);   // (-.75 - .75) / 5
        assertEquals(-2.25 / 5, SeasonalityDetector.acf(x, 3).getAsDouble(), 1e-12);
        assertEquals(0.0, SeasonalityDetector.acf(x, 4).getAsDouble(), 0.0, "lag >= n: no pairs");
        assertTrue(SeasonalityDetector.acf(new double[] {3, 3, 3}, 1).isEmpty(), "constant series: undefined");
        assertTrue(SeasonalityDetector.acf(new double[0], 1).isEmpty());
        assertThrows(IllegalArgumentException.class, () -> SeasonalityDetector.acf(new double[] {1, Double.NaN}, 1));
    }

    @Test
    void findsAWeeklyPeriodInDailyData() {
        Detection d = SeasonalityDetector.detect(seasonal(84, 7, 2, 1), new int[] {7, 14, 30});
        assertEquals(7, d.period().getAsInt());
        assertEquals(84, d.n());
        assertEquals(Math.max(0.3, 1.96 / Math.sqrt(84)), d.threshold(), 1e-12);
        assertEquals(Verdict.CHOSEN, d.evidence().get(0).verdict());
        assertEquals(Verdict.TOO_FEW_CYCLES, d.evidence().get(2).verdict(), "30 needs 90 values");
        assertTrue(d.evidence().get(2).acf().isEmpty());
    }

    @Test
    void findsTheDefaultCandidateOfAGrain() {
        assertArrayEquals(new int[] {24, 168}, SeasonalityDetector.candidatesFor(TimeGrain.HOUR));
        assertEquals(24, SeasonalityDetector.detect(seasonal(24 * 6, 24, 1, 2), SeasonalityDetector.candidatesFor(TimeGrain.HOUR))
                .period().getAsInt());
        assertEquals(12, SeasonalityDetector.detect(seasonal(48, 12, 1, 3), SeasonalityDetector.candidatesFor(TimeGrain.MONTH))
                .period().getAsInt());
        assertEquals(0, SeasonalityDetector.candidatesFor(TimeGrain.YEAR).length);
    }

    @Test
    void noPeriodInNoiseTrendOrAConstant() {
        double[] noise = new Random(9).doubles(200).toArray();
        Detection n = SeasonalityDetector.detect(noise, new int[] {7, 12, 24});
        assertTrue(n.period().isEmpty());
        n.evidence().forEach(e -> assertNotEquals(Verdict.CHOSEN, e.verdict()));

        double[] trend = new double[100];
        for (int t = 0; t < 100; t++) trend[t] = 3 * t;
        Detection tr = SeasonalityDetector.detect(trend, new int[] {7, 12});
        assertTrue(tr.period().isEmpty(), "a trend's ACF is high but decays with no peak");
        assertEquals(Verdict.NOT_A_PEAK, tr.evidence().get(0).verdict());

        Detection c = SeasonalityDetector.detect(new double[50], new int[] {7});
        assertTrue(c.period().isEmpty());
        assertEquals(Verdict.CONSTANT_SERIES, c.evidence().get(0).verdict());
    }

    @Test
    void minimumCyclesRule() {
        double[] x = seasonal(20, 7, 0, 4);   // under 3 cycles of 7
        Detection d = SeasonalityDetector.detect(x, new int[] {7});
        assertTrue(d.period().isEmpty());
        assertEquals(Verdict.TOO_FEW_CYCLES, d.evidence().get(0).verdict());
        assertEquals(7, SeasonalityDetector.detect(x, new int[] {7}, new Params(0.3, 2)).period().getAsInt(), "two cycles allowed");
    }

    @Test
    void highestAcfWinsAndATieGoesToTheShorterPeriod() {
        // decide() over supplied ACF values: peaks at 7 and 12, 12 higher -> 12
        Detection d = SeasonalityDetector.decide(100, new int[] {12, 7}, Params.DEFAULT,
                lag -> OptionalDouble.of(lag == 7 ? 0.6 : lag == 12 ? 0.7 : 0.1));
        assertEquals(12, d.period().getAsInt());
        assertEquals(7, d.evidence().get(0).period(), "evidence in ascending period order");
        assertEquals(Verdict.ELIGIBLE, d.evidence().get(0).verdict());
        Detection tie = SeasonalityDetector.decide(100, new int[] {14, 7}, Params.DEFAULT,
                lag -> OptionalDouble.of(lag == 7 || lag == 14 ? 0.8 : 0.1));
        assertEquals(7, tie.period().getAsInt());
        Detection weak = SeasonalityDetector.decide(100, new int[] {7}, Params.DEFAULT,
                lag -> OptionalDouble.of(lag == 7 ? 0.29 : 0.1));
        assertEquals(Verdict.NOT_SIGNIFICANT, weak.evidence().get(0).verdict(), "below the 0.3 floor");
    }

    @Test
    void inputRules() {
        assertThrows(IllegalArgumentException.class, () -> SeasonalityDetector.detect(new double[10], new int[] {1}));
        assertThrows(IllegalArgumentException.class, () -> SeasonalityDetector.detect(new double[10], new int[] {7, 7}));
        assertThrows(IllegalArgumentException.class, () -> new Params(0, 3));
        assertThrows(IllegalArgumentException.class, () -> new Params(0.3, 1));
        assertThrows(IllegalArgumentException.class,
                () -> SeasonalityDetector.detect(new double[SeasonalityDetector.MAX_SERIES_LENGTH + 1], new int[] {7}));
        assertArrayEquals(new int[] {6, 7, 8, 11, 12, 13}, SeasonalityDetector.lagsFor(new int[] {12, 7}));
    }
}
