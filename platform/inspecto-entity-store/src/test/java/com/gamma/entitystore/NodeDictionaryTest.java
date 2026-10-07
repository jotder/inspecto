package com.gamma.entitystore;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.entitystore.EntityTypes;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * SP5 correctness (fast, small): the DuckDB E.164 macro agrees with the sealed Java {@code e164} normaliser, the
 * three longest-prefix strategies agree with a brute-force oracle, and the daily upsert enriches only NEW numbers.
 * The timing side is {@link NodeDictionaryBench} (gated on {@code -Dbench.run=true}).
 */
class NodeDictionaryTest {

    private static final Path FIXTURE = Path.of("..", "..", "inspecto-ui", "src", "app", "inspecto", "graph",
            "entity-normaliser-parity.fixture.json");

    private Connection c;

    @BeforeEach
    void open() throws Exception {
        c = DriverManager.getConnection("jdbc:duckdb:");
        try (Statement st = c.createStatement()) { NodeDictionarySql.macros(st); }
    }

    @AfterEach
    void close() throws Exception { c.close(); }

    private String sqlE164(String raw) throws Exception {
        try (var ps = c.prepareStatement("SELECT e164(?)")) {
            ps.setString(1, raw);
            try (ResultSet rs = ps.executeQuery()) { rs.next(); return rs.getString(1); }
        }
    }

    @Test
    void sqlMacroMatchesJavaNormaliserOnTheSharedFixtureAndMalformedInputs() throws Exception {
        List<String> inputs = new ArrayList<>();
        for (JsonNode n : new ObjectMapper().readTree(Files.readString(FIXTURE)).get("cases")) {
            if ("e164".equals(n.get("normaliser").asText())) inputs.add(n.get("input").asText());
        }
        inputs.addAll(List.of("", " ", "N/A", "+", "++44", "00", "000044 7700 900123", "+44(0)7700 900123", "112", "0",
                "+4477009001230000000000", "abc", "+44 7700  900123", "\t+44 12\n", "0044", "00 44", "+0"));
        List<String> failures = new ArrayList<>();
        for (String in : inputs) {
            String want = EntityTypes.normalise("e164", in), got = sqlE164(in);
            if (!want.equals(got)) failures.add("'" + in + "' java='" + want + "' sql='" + got + "'");
        }
        assertEquals(List.of(), failures);
    }

    @Test
    void oneNumberSpelledThreeWaysCollapsesToOneId() throws Exception {
        assertEquals("+447700900123", sqlE164("+44 7700 900123"));
        assertEquals("+447700900123", sqlE164("0044-7700-900123"));
        assertEquals("+447700900123", sqlE164("+44(0)7700.900123".replace("(0)", "")));
        // GAP (pinned, not endorsed): a leading "(" hides the "+", so "(+44) 7700 900123" is a DIFFERENT id (no "+").
        assertEquals("447700900123", sqlE164(" (+44) 7700.900123 "));
        assertEquals("447700900123", EntityTypes.normalise("e164", " (+44) 7700.900123 "));
        assertEquals("", sqlE164("N/A"));
    }

    @Test
    void nationalAndShortCodesAreNotInventedIntoInternationalIds() throws Exception {
        // The sealed normaliser has no default country: a national number stays national, so it is NOT enrichable.
        assertEquals("07700900123", sqlE164("07700 900123"));
        assertEquals("112", sqlE164("112"));
        assertEquals(List.of(), enrichable("07700900123", "112"));
    }

    private List<String> enrichable(String... ids) throws Exception {
        try (Statement st = c.createStatement()) {
            st.execute("CREATE OR REPLACE TEMP TABLE t_ids(id VARCHAR)");
            for (String id : ids) st.execute("INSERT INTO t_ids VALUES ('" + id + "')");
        }
        List<String> out = new ArrayList<>();
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(NodeDictionarySql.numsOf("t_ids"))) {
            while (rs.next()) out.add(rs.getString(1));
        }
        return out;
    }

    // ------------------------------------------------------------ longest prefix

    private static final List<NodeDictionarySql.Prefix> PFX = List.of(
            new NodeDictionarySql.Prefix("44", "UK", null, null),
            new NodeDictionarySql.Prefix("447", "UK", "MOB", null),
            new NodeDictionarySql.Prefix("4477", "UK", "MOB-A", null),
            new NodeDictionarySql.Prefix("447700", "UK", "MOB-A", "R900"),
            new NodeDictionarySql.Prefix("1", "US", null, null),
            new NodeDictionarySql.Prefix("1212", "US", "NYC", null));

    private void seedNumbers(String... ids) throws Exception {
        NodeDictionarySql.loadPrefixes(c, PFX);
        NodeDictionarySql.loadIntervals(c, PFX);
        try (Statement st = c.createStatement()) {
            st.execute("CREATE OR REPLACE TABLE n_ids(id VARCHAR)");
            for (String id : ids) st.execute("INSERT INTO n_ids VALUES ('" + id + "')");
            st.execute("CREATE OR REPLACE TABLE nums AS " + NodeDictionarySql.numsOf("n_ids"));
        }
    }

    @Test
    void allThreeStrategiesPickTheLongestPrefixAndAgreeWithTheOracle() throws Exception {
        String[] ids = {"+447700900123", "+447712345678", "+447812345678", "+442012345678", "+12125550100", "+13105550100",
                "+99912345678", "+4412345678", "+447700123456"};
        seedNumbers(ids);
        List<Integer> lens = NodeDictionarySql.lengths(c);
        Map<String, String> a = NodeDictionarySql.fetch(c, NodeDictionarySql.lpmCascade("nums", lens));
        Map<String, String> cand = NodeDictionarySql.fetch(c, NodeDictionarySql.lpmCandidates("nums", lens));
        Map<String, String> asof = NodeDictionarySql.fetch(c, NodeDictionarySql.lpmAsof("nums"));
        assertEquals(a, cand);
        assertEquals(a, asof);
        assertEquals("6|UK|MOB-A|R900", a.get("+447700900123"));
        assertEquals("4|UK|MOB-A|null", a.get("+447712345678"));
        assertEquals("3|UK|MOB|null", a.get("+447812345678"));
        assertEquals("2|UK|null|null", a.get("+442012345678"));
        assertEquals("4|US|NYC|null", a.get("+12125550100"));
        assertEquals("1|US|null|null", a.get("+13105550100"));
        assertNull(a.get("+99912345678"));          // no covering prefix: absent, not an error
        assertEquals("2|UK|null|null", a.get("+4412345678"));
        assertEquals("6|UK|MOB-A|R900", a.get("+447700123456"));
    }

    @Test
    void shortNumbersMatchOnlyPrefixesNoLongerThanThemselvesInCascadeAndCandidates() throws Exception {
        seedNumbers("+447", "+4477", "+44", "+4");
        List<Integer> lens = NodeDictionarySql.lengths(c);
        Map<String, String> a = NodeDictionarySql.fetch(c, NodeDictionarySql.lpmCascade("nums", lens));
        assertEquals(a, NodeDictionarySql.fetch(c, NodeDictionarySql.lpmCandidates("nums", lens)));
        assertEquals("3|UK|MOB|null", a.get("+447"));
        assertEquals("2|UK|null|null", a.get("+44"));
        assertNull(a.get("+4"));
        // ASOF needs length >= prefix length: "+4477" falls in the 447700.. leaf interval's neighbour, never a false hit.
        Map<String, String> asof = NodeDictionarySql.fetch(c, NodeDictionarySql.lpmAsof("nums"));
        for (var e : asof.entrySet()) assertEquals(a.get(e.getKey()), e.getValue(), "asof differs for " + e.getKey());
    }

    @Test
    void generatedPrefixTableAndNumbersAgreeAcrossStrategies() throws Exception {
        var pfx = NodeDictionarySql.genPrefixes(1000, 7);
        NodeDictionarySql.loadPrefixes(c, pfx);
        NodeDictionarySql.loadIntervals(c, pfx);
        try (Statement st = c.createStatement()) {
            st.execute("CREATE TABLE g_ids AS " + NodeDictionarySql.genIds(0, 20000, pfx.size()));
            st.execute("CREATE TABLE nums AS " + NodeDictionarySql.numsOf("g_ids"));
        }
        List<Integer> lens = NodeDictionarySql.lengths(c);
        Map<String, String> a = NodeDictionarySql.fetch(c, NodeDictionarySql.lpmCascade("nums", lens));
        assertEquals(a, NodeDictionarySql.fetch(c, NodeDictionarySql.lpmCandidates("nums", lens)));
        assertEquals(a, NodeDictionarySql.fetch(c, NodeDictionarySql.lpmAsof("nums")));
        assertEquals(true, a.size() > 19000, "every generated number starts with a table prefix");
    }

    // ------------------------------------------------------------ daily upsert

    @Test
    void dailyUpsertEnrichesOnlyNewNumbersAndIsIdempotent() throws Exception {
        NodeDictionarySql.loadPrefixes(c, PFX);
        List<Integer> lens = NodeDictionarySql.lengths(c);
        try (Statement st = c.createStatement()) {
            st.execute("CREATE TABLE dict(id VARCHAR, plen INT, country VARCHAR, operator VARCHAR, rng VARCHAR)");
            // an OLD row enriched under an older prefix table: the upsert must NOT touch it
            st.execute("INSERT INTO dict VALUES ('+447700900123', 2, 'OLD', NULL, NULL)");
            st.execute("CREATE TABLE day_raw(raw VARCHAR)");
            st.execute("INSERT INTO day_raw VALUES ('+44 7700 900123'), ('00447700900123'), ('+1 212 555 0100'), "
                    + "('0012125550100'), (' +1-212-555-0100 '), ('N/A'), ('+'), (''), ('07700 900123')");
        }
        assertEquals(2L, NodeDictionarySql.upsertDay(c, "day_raw", lens)); // +12125550100 and national 07700900123
        assertEquals("OLD", scalar("SELECT country FROM dict WHERE id='+447700900123'"));
        assertEquals("US", scalar("SELECT country FROM dict WHERE id='+12125550100'"));
        assertEquals("NYC", scalar("SELECT operator FROM dict WHERE id='+12125550100'"));
        assertNull(scalar("SELECT country FROM dict WHERE id='07700900123'")); // stored (so not "new" again), unenriched
        assertEquals("3", scalar("SELECT count(*) FROM dict"));
        assertEquals(0L, NodeDictionarySql.upsertDay(c, "day_raw", lens));      // re-run: nothing new
        assertEquals("3", scalar("SELECT count(*) FROM dict"));
    }

    private String scalar(String sql) throws Exception {
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) { rs.next(); return rs.getString(1); }
    }
}
