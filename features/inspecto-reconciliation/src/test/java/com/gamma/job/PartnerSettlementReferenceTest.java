package com.gamma.job;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code PACK-PARTNER-SETTLEMENT-1} slice S1: the four References and the settlement-cycle calendar. The shipped
 * {@code partner-settlement} template is created through {@code POST /spaces}, the corpus References are ingested through the
 * template's own Pipelines, and the {@code ps_calendar} Job runs as authored. Its rows are held to (a) literals worked out by hand
 * from a 2026 wall calendar and the corpus holiday lists, and (b) an independent plain-Java oracle ({@code java.time}) over every
 * agreement version. An effective-dated change (one agreement, two rows) resolves to the right row on each side of its boundary.
 */
class PartnerSettlementReferenceTest {

    @BeforeEach
    void arm() { PartnerSettlementSpace.arm(); }

    @AfterEach
    void disarm() { PartnerSettlementSpace.disarm(); }

    private static Map<String, String> asOf() { return Map.of("as_of", PartnerSettlementCorpus.AS_OF.toString()); }

    private static PartnerSettlementSpace calendarSpace(Path root) throws Exception {
        PartnerSettlementSpace s = PartnerSettlementSpace.create(root);
        PartnerSettlementCorpus corpus = PartnerSettlementCorpus.generate();
        Map<String, String> refs = new java.util.LinkedHashMap<>(corpus.files());
        refs.keySet().removeIf(k -> k.startsWith("settlement_statement/") || k.startsWith("rerated_usage/"));
        s.ingest(refs);
        s.run(List.of("ps_calendar"), asOf());
        return s;
    }

    @Test
    void theCalendarHoldsEveryClosedCycleWithTheHandWorkedDates(@TempDir Path root) throws Exception {
        PartnerSettlementSpace s = calendarSpace(root);
        Map<String, String> got = new TreeMap<>();
        for (Map<String, Object> r : s.read("settlement_calendar", null, "agreement_id, period_start"))
            got.put(r.get("agreement_id") + "|" + r.get("period_start") + "|" + r.get("period_end"),
                    r.get("statement_due") + "|" + r.get("dispute_window_end") + "|" + r.get("payment_due"));
        assertEquals(14, got.size(), "A1 3 + C1 5 (its 08-01..08-15 half-month closed on 08-15) + D1 3 + E1 3, none after the as-of day: " + got.keySet());
        assertEquals("2026-08-20|2026-09-09|2026-09-09", got.get("AG-SMS-C1|2026-08-01|2026-08-15"),"C1 Aug first half: Sat 08-15 + 5 = Thu 08-20; +20 = Wed 09-09");

        // worked by hand: 2026-06-01 is a Monday; XA holidays 2026-06-10 and 2026-07-13; XB holidays 2026-06-15 and 2026-08-25
        // A1 May: 05-31 + 10 = Wed 06-10 (holiday) -> Thu 06-11; +30 = Sat 07-11 -> Sun, Mon 07-13 (holiday) -> Tue 07-14
        assertEquals("2026-06-11|2026-07-14|2026-07-14", got.get("AG-VOICE-A1|2026-05-01|2026-05-31"));
        // A1 Jun: 06-30 + 10 = Fri 07-10; +30 = Sun 08-09 -> Mon 08-10
        assertEquals("2026-07-10|2026-08-10|2026-08-10", got.get("AG-VOICE-A1|2026-06-01|2026-06-30"));
        // A1 Jul (the version that starts 07-16 owns the cycle ending 07-31): 07-31 + 10 = Mon 08-10; +30 = Wed 09-09
        assertEquals("2026-08-10|2026-09-09|2026-09-09", got.get("AG-VOICE-A1|2026-07-01|2026-07-31"));
        // E1 Jul: statement due 25 days after the cycle: Tue 08-25 is an XB holiday -> Wed 08-26 (after the as-of day 08-20)
        assertEquals("2026-08-26|2026-09-25|2026-09-25", got.get("AG-VOICE-E1|2026-07-01|2026-07-31"));
        // C1 first half-month: 06-15 + 5 = Sat 06-20 -> Mon 06-22; +20 = Sun 07-12 -> Mon 07-13 (holiday) -> Tue 07-14
        assertEquals("2026-06-22|2026-07-14|2026-07-14", got.get("AG-SMS-C1|2026-06-01|2026-06-15"));
        // semi-monthly periods are 1-15 and 16-end
        assertTrue(got.containsKey("AG-SMS-C1|2026-06-16|2026-06-30"));
        assertTrue(got.containsKey("AG-SMS-C1|2026-07-16|2026-07-31"));
    }

    @Test
    void theCalendarEqualsAnIndependentJavaTimeOracleForEveryAgreementVersion(@TempDir Path root) throws Exception {
        PartnerSettlementSpace s = calendarSpace(root);
        PartnerSettlementCorpus corpus = PartnerSettlementCorpus.generate();
        Map<String, String> want = oracle(corpus);
        Map<String, String> got = new TreeMap<>();
        for (Map<String, Object> r : s.read("settlement_calendar", null, "agreement_id, period_start"))
            got.put(r.get("agreement_id") + "|" + r.get("period_start") + "|" + r.get("period_end"),
                    r.get("statement_due") + "|" + r.get("dispute_window_end") + "|" + r.get("payment_due"));
        assertEquals(want, got);
    }

    /** One calendar row per agreement version and closed cycle, computed with java.time and no SQL. */
    static Map<String, String> oracle(PartnerSettlementCorpus corpus) {
        Map<String, String> country = new TreeMap<>();
        for (String p : corpus.partners) { String[] f = p.split(","); country.put(f[0], f[3]); }
        Set<String> holidays = new HashSet<>();
        for (String h : corpus.holidays) { String[] f = h.split(","); holidays.add(f[0] + "|" + f[1]); }
        Map<String, String> out = new TreeMap<>();
        for (PartnerSettlementCorpus.Agreement a : corpus.agreements) {
            LocalDate last = a.to() == null || a.to().isAfter(PartnerSettlementCorpus.AS_OF.minusDays(1)) ? PartnerSettlementCorpus.AS_OF.minusDays(1) : a.to();
            for (LocalDate d = a.from(); !d.isAfter(last); d = d.plusDays(1)) {
                LocalDate start = switch (a.cycle()) {
                    case "monthly" -> d.equals(d.withDayOfMonth(d.lengthOfMonth())) ? d.withDayOfMonth(1) : null;
                    case "semi_monthly" -> d.getDayOfMonth() == 15 ? d.withDayOfMonth(1)
                            : d.equals(d.withDayOfMonth(d.lengthOfMonth())) ? d.withDayOfMonth(16) : null;
                    case "weekly" -> d.getDayOfWeek() == DayOfWeek.SUNDAY ? d.minusDays(6) : null;
                    default -> throw new AssertionError(a.cycle());
                };
                if (start == null) continue;
                String c = country.get(a.partner());
                LocalDate due = roll(d.plusDays(a.stmtDue()), c, holidays);
                LocalDate disp = roll(due.plusDays(a.dispute()), c, holidays);
                LocalDate pay = roll(due.plusDays(a.payment()), c, holidays);
                out.put(a.id() + "|" + start + "|" + d, due + "|" + disp + "|" + pay);
            }
        }
        return out;
    }

    private static LocalDate roll(LocalDate d, String country, Set<String> holidays) {
        while (d.getDayOfWeek() == DayOfWeek.SATURDAY || d.getDayOfWeek() == DayOfWeek.SUNDAY || holidays.contains(country + "|" + d)) d = d.plusDays(1);
        return d;
    }

    @Test
    void aMidCycleChangeIsTwoEffectiveDatedRowsAndALookupOnEachSideOfTheBoundaryFindsTheRightOne(@TempDir Path root) throws Exception {
        PartnerSettlementSpace s = calendarSpace(root);
        assertEquals("1.0000", terms(s, "AG-VOICE-A1", "2026-07-15"), "the day before the change: the first version");
        assertEquals("2.0000", terms(s, "AG-VOICE-A1", "2026-07-16"), "the day of the change: the second version");
        assertEquals("1.0000", terms(s, "AG-VOICE-A1", "2026-05-01"), "the first day of the first version");
        assertNull(terms(s, "AG-VOICE-A1", "2026-04-30"), "before the agreement exists no version is in force");
        assertEquals(2, s.read("settlement_agreement", "agreement_id = 'AG-VOICE-A1'", "valid_from").size());
        // the open-ended version has a NULL valid_to, not a sentinel
        assertNull(s.read("settlement_agreement", "agreement_id = 'AG-VOICE-A1'", "valid_from").get(1).get("valid_to"));
    }

    private static String terms(PartnerSettlementSpace s, String agreement, String day) throws Exception {
        try (Connection c = DriverManager.getConnection("jdbc:duckdb:"); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT CAST(tolerance AS VARCHAR) FROM read_parquet('"
                     + s.data().resolve("settlement_agreement").toString().replace('\\', '/') + "/**/*.parquet') WHERE agreement_id = '" + agreement
                     + "' AND DATE '" + day + "' >= valid_from AND (valid_to IS NULL OR DATE '" + day + "' <= valid_to)")) {
            return rs.next() ? rs.getString(1) : null;
        }
    }

    @Test
    void anAgreementWithoutAPartnerCountryStillGetsWeekendsRolledAndNoHolidays(@TempDir Path root) throws Exception {
        PartnerSettlementSpace s = PartnerSettlementSpace.create(root);
        Map<String, String> refs = new java.util.LinkedHashMap<>(PartnerSettlementCorpus.generate().files());
        refs.keySet().removeIf(k -> k.startsWith("settlement_statement/") || k.startsWith("rerated_usage/"));
        // drop the partner list: every agreement's country is unknown, so only weekends roll
        refs.put("partner/partner.csv", "partner_id,name,type,country,status\nPX,Unrelated Test Partner,interconnect,XC,active\n");
        s.ingest(refs);
        s.run(List.of("ps_calendar"), asOf());
        Map<String, String> got = new TreeMap<>();
        for (Map<String, Object> r : s.read("settlement_calendar", null, "agreement_id, period_start"))
            got.put(r.get("agreement_id") + "|" + r.get("period_start"), String.valueOf(r.get("statement_due")));
        assertEquals("2026-06-10", got.get("AG-VOICE-A1|2026-05-01"), "no country: the XA holiday on Wed 06-10 does not apply");
        assertTrue(Files.isDirectory(s.data().resolve("settlement_calendar")));
    }
}
