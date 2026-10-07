package com.gamma.workflow;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** ASSURE-WORKFLOW-SLA-1 — SLA targets counted in a business calendar with an explicit IANA zone. */
class SlaPolicyTest {

    private static final ZoneId LONDON = ZoneId.of("Europe/London");

    private static long at(String local) {
        return LocalDateTime.parse(local).atZone(LONDON).toInstant().toEpochMilli();
    }

    private static String local(long ms) {
        return java.time.Instant.ofEpochMilli(ms).atZone(LONDON).toLocalDateTime().toString();
    }

    private static Map<String, Object> policy(Map<String, Object> calendar, List<Map<String, Object>> targets) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("objectType", "INCIDENT");
        m.put("calendar", calendar);
        m.put("targets", targets);
        return m;
    }

    private static final Map<String, Object> OFFICE = Map.of("zone", "Europe/London",
            "workingDays", List.of("MON", "TUE", "WED", "THU", "FRI"), "start", "09:00", "end", "17:00",
            "holidays", List.of("2026-12-25"));

    private static SlaPolicy.Calendar office() {
        return SlaPolicy.fromComponent("incident", policy(OFFICE,
                List.of(Map.of("priority", "*", "resolutionMinutes", 60)))).calendar();
    }

    @Test
    void workingMinutesSkipTheNightAndTheWeekend() {
        // Friday 16:00 + 2 working hours = Monday 10:00
        assertEquals("2026-10-05T10:00", local(office().addWorkingMinutes(at("2026-10-02T16:00"), 120)));
        // Saturday: the clock starts Monday 09:00
        assertEquals("2026-10-05T09:30", local(office().addWorkingMinutes(at("2026-10-03T11:00"), 30)));
    }

    @Test
    void aHolidayIsNotWorkingTime() {
        // Thursday 2026-12-24 16:00 + 2h: one hour Thursday, Friday 25th is a holiday, weekend, Monday 10:00.
        assertEquals("2026-12-28T10:00", local(office().addWorkingMinutes(at("2026-12-24T16:00"), 120)));
    }

    @Test
    void aDstDayKeepsItsWallClockWorkingHours() {
        // Europe/London springs forward Sunday 2026-03-29; Monday's 09:00–17:00 is eight wall-clock hours either side.
        assertEquals("2026-03-30T17:00", local(office().addWorkingMinutes(at("2026-03-27T17:00"), 8 * 60)));
        // Autumn: clocks go back Sunday 2026-10-25.
        assertEquals("2026-10-26T12:00", local(office().addWorkingMinutes(at("2026-10-23T15:00"), 5 * 60)));
    }

    @Test
    void aRoundTheClockCalendarCountsTheShortDstDayAsTwentyThreeHours() {
        SlaPolicy.Calendar allDay = SlaPolicy.fromComponent("incident", policy(Map.of("zone", "Europe/London"),
                List.of(Map.of("priority", "*", "resolutionMinutes", 60)))).calendar();
        long start = at("2026-03-28T12:00");
        long due = allDay.addWorkingMinutes(start, 24 * 60);
        assertEquals(24L * 3_600_000L, due - start, "24 elapsed hours");
        assertEquals("2026-03-29T13:00", local(due), "…which is 13:00 wall-clock after the spring-forward");
    }

    @Test
    void theTargetForAPriorityFallsBackToTheStar() {
        SlaPolicy p = SlaPolicy.fromComponent("incident", policy(OFFICE, List.of(
                Map.of("priority", "critical", "responseMinutes", 15, "resolutionMinutes", 120),
                Map.of("priority", "*", "resolutionMinutes", 480))));
        assertEquals(15L, p.targetFor("CRITICAL").orElseThrow().responseMinutes());
        assertEquals(480L, p.targetFor("minor").orElseThrow().resolutionMinutes());
        assertEquals(480L, p.targetFor(null).orElseThrow().resolutionMinutes());
    }

    @Test
    void theZoneMustBeAnExplicitIanaIdNeverTheHostsOrAnOffset() {
        for (Object zone : new Object[]{null, "", "+01:00", "GMT+1", "Mars/Olympus"}) {
            Map<String, Object> cal = new LinkedHashMap<>(OFFICE);
            if (zone == null) cal.remove("zone"); else cal.put("zone", zone);
            assertThrows(IllegalArgumentException.class, () -> SlaPolicy.fromComponent("incident",
                    policy(cal, List.of(Map.of("priority", "*", "resolutionMinutes", 60)))), String.valueOf(zone));
        }
        assertThrows(IllegalArgumentException.class, () -> SlaPolicy.fromComponent("incident",
                policy(null, List.of(Map.of("priority", "*", "resolutionMinutes", 60)))), "no calendar at all");
    }

    @Test
    void malformedPoliciesAreRefused() {
        List<Map<String, Object>> ok = List.of(Map.of("priority", "*", "resolutionMinutes", 60));
        assertThrows(IllegalArgumentException.class, () -> SlaPolicy.fromComponent("case", policy(OFFICE, ok)), "id mismatch");
        assertThrows(IllegalArgumentException.class, () -> SlaPolicy.fromComponent("incident", policy(OFFICE, List.of())));
        assertThrows(IllegalArgumentException.class, () -> SlaPolicy.fromComponent("incident", policy(OFFICE,
                List.of(Map.of("priority", "*")))), "no target");
        assertThrows(IllegalArgumentException.class, () -> SlaPolicy.fromComponent("incident", policy(OFFICE,
                List.of(Map.of("priority", "*", "responseMinutes", 90, "resolutionMinutes", 60)))), "response > resolution");
        assertThrows(IllegalArgumentException.class, () -> SlaPolicy.fromComponent("incident", policy(OFFICE,
                List.of(Map.of("priority", "*", "resolutionMinutes", 0)))));
        assertThrows(IllegalArgumentException.class, () -> SlaPolicy.fromComponent("incident", policy(OFFICE,
                List.of(Map.of("priority", "*", "resolutionMinutes", 60), Map.of("priority", "*", "resolutionMinutes", 90)))));
        Map<String, Object> overnight = new LinkedHashMap<>(OFFICE);
        overnight.put("start", "22:00");
        overnight.put("end", "06:00");
        assertThrows(IllegalArgumentException.class, () -> SlaPolicy.fromComponent("incident", policy(overnight, ok)));
        Map<String, Object> badDay = new LinkedHashMap<>(OFFICE);
        badDay.put("holidays", List.of("25/12/2026"));
        assertThrows(IllegalArgumentException.class, () -> SlaPolicy.fromComponent("incident", policy(badDay, ok)));
    }
}
