package com.gamma.agent.skill;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Pins the slot formats to the insights module's templated sentence (InsightScan.fmt / pct / share). */
class BriefSlotsTest {

    private static BriefSlots.Insight of(String type, double value, double expected, double delta, String slice) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("insightId", "i");
        m.put("type", type);
        m.put("measure", "orders");
        m.put("slice", slice);
        m.put("period", "2026-11");
        m.put("value", value);
        m.put("expected", expected);
        m.put("delta", delta);
        return BriefSlots.Insight.of(1, m);
    }

    @Test
    void seriesTypesUseTwoDecimalsAndThousandsAboveAHundred() {
        var i = of("spike", 1234.5, 100.0, 1134.5, "x");
        assertEquals("1,235", i.slot("value"));   // %,.0f rounds half up
    }

    @Test
    void smallValuesPercentAndDirection() {
        var i = of("spike", 4.6, 3.0, 1.6, "Hamburg");
        assertEquals("4.60", i.slot("value"));
        assertEquals("1.60", i.slot("delta"));
        assertEquals("53.3 %", i.slot("pct"));
        assertEquals("up", i.direction());
        assertEquals("down", of("spike", 2, 6, -4, "x").direction());
        assertEquals("66.7 %", of("spike", 2, 6, -4, "x").slot("pct"), "unsigned: the direction word carries the sign");
    }

    @Test
    void concentrationIsAShareAndPointsAndZeroExpectedHasNoPct() {
        var c = of("concentration", 0.62, 0.5, 0.12, "x");
        assertEquals("62.0 %", c.slot("value"));
        assertEquals("12.0 points", c.slot("delta"));
        assertEquals("n/a", of("spike", 3, 0, 3, "x").slot("pct"));
    }

    @Test
    void storedTextIsOneBoundedLine() {
        var i = of("spike", 1, 1, 0, "a\nb\u0007" + "x".repeat(200));
        String s = i.slot("slice");
        assertEquals(80, BriefSlots.clean(s).length());
        assertEquals(-1, BriefSlots.clean(s).indexOf('\n'));
    }
}
