package com.gamma.analytics.baseline;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * ANALYTICS-CORE-1 M6: RobustStats now delegates to the kernel's Stats. These pin that every figure is BIT-identical to
 * the anomaly module's pre-move implementation, copied verbatim below as the oracle.
 */
class RobustStatsTest {

    /** The pre-move RobustStats (features/inspecto-anomaly, before 2026-10-17), verbatim. */
    private static final class Before {
        static double median(Collection<Double> values) {
            if (values.isEmpty()) throw new IllegalArgumentException("median of no values");
            double[] v = values.stream().mapToDouble(Double::doubleValue).sorted().toArray();
            int n = v.length;
            return n % 2 == 1 ? v[n / 2] : (v[n / 2 - 1] + v[n / 2]) / 2.0;
        }

        static double scaledMad(Collection<Double> values, double median) {
            return 1.4826 * median(values.stream().map(x -> Math.abs(x - median)).toList());
        }

        static double floor(double median, double unit) {
            return Math.max(unit, 0.05 * Math.abs(median));
        }

        static double z(double observed, double median, double scaledMad, double floor) {
            return (observed - median) / Math.max(scaledMad, floor);
        }
    }

    @Test
    void everyFigureIsBitIdenticalToThePreMoveImplementation() {
        Random r = new Random(4242);
        for (int n = 1; n <= 200; n++) {
            List<Double> v = new ArrayList<>();
            for (int i = 0; i < n; i++) v.add(r.nextBoolean() ? Math.rint(r.nextGaussian() * 50) : r.nextGaussian() * 1e4);
            double m = RobustStats.median(v);
            assertEquals(Double.doubleToRawLongBits(Before.median(v)), Double.doubleToRawLongBits(m), "median n=" + n);
            double mad = RobustStats.scaledMad(v, m);
            assertEquals(Double.doubleToRawLongBits(Before.scaledMad(v, m)), Double.doubleToRawLongBits(mad), "mad n=" + n);
            double unit = r.nextInt(3) + 0.5;
            double fl = RobustStats.floor(m, unit);
            assertEquals(Before.floor(m, unit), fl, 0.0);
            double obs = r.nextGaussian() * 100;
            assertEquals(Double.doubleToRawLongBits(Before.z(obs, m, mad, fl)),
                    Double.doubleToRawLongBits(RobustStats.z(obs, m, mad, fl)), "z n=" + n);
        }
        assertEquals(1.4826, RobustStats.MAD_SCALE, 0.0);
        assertEquals(0.05, RobustStats.FLOOR_SHARE, 0.0);
    }

    @Test
    void emptyInputStillThrowsTheSameMessage() {
        assertEquals("median of no values",
                assertThrows(IllegalArgumentException.class, () -> RobustStats.median(List.of())).getMessage());
        assertThrows(IllegalArgumentException.class, () -> RobustStats.scaledMad(List.of(), 0));
    }
}
