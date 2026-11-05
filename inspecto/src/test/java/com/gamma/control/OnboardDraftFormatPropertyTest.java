package com.gamma.control;

import com.gamma.util.DuckDbUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code ONBOARDING-FIRST-RUN-1} C1, property tests over generated sample files: for many seeded random files, the
 * draft's decimal mark, record path, fields to flatten and file-name date are exactly what the generator wrote, with
 * evidence and the OF-2 confidence (high only at 200 non-blank values or more). Synthetic data only.
 */
class OnboardDraftFormatPropertyTest {

    private static final long SEED = 20261105L;

    private static OnboardDraft.Analysis analyse(Path dir, String name, String content, Map<String, String> overrides) throws Exception {
        Path f = dir.resolve(name);
        Files.writeString(f, content, StandardCharsets.UTF_8);
        return OnboardDraft.analyse(dir, f, name, OnboardDraft.fileType(f, name), overrides);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> fmt(OnboardDraft.Analysis a, String key) {
        Map<String, Object> ch = (Map<String, Object>) a.format().get(key);
        assertNotNull(ch, key + " in " + a.format());
        assertFalse(String.valueOf(ch.get("evidence")).isBlank(), "evidence is stated: " + ch);
        return ch;
    }

    private static OnboardDraft.Col col(OnboardDraft.Analysis a, String name) {
        return a.cols().stream().filter(c -> c.name().equals(name)).findFirst().orElseThrow(() -> new AssertionError(name + " in " + a.cols()));
    }

    // ── decimal mark ─────────────────────────────────────────────────────────────────────────────────────────

    /** {@code v} (2 decimals) as the file writes it: mark ',' or '.', optional grouping by the other mark or a space. */
    private static String render(BigDecimal v, String mark, String group) {
        String plain = v.abs().setScale(2, java.math.RoundingMode.UNNECESSARY).toPlainString();
        String intPart = plain.substring(0, plain.indexOf('.')), frac = plain.substring(plain.indexOf('.') + 1);
        StringBuilder g = new StringBuilder();
        for (int i = 0; i < intPart.length(); i++) {
            if (group != null && i > 0 && (intPart.length() - i) % 3 == 0) g.append(group);
            g.append(intPart.charAt(i));
        }
        return (v.signum() < 0 ? "-" : "") + g + mark + frac;
    }

    @Test
    void theDecimalMarkTheFileWasWrittenWithIsProposedAndReadsEveryValueBack(@TempDir Path dir) throws Exception {
        Random r = new Random(SEED);
        DuckDbUtil.loadDriver();
        for (int trial = 0; trial < 24; trial++) {
            String mark = r.nextBoolean() ? "," : ".";
            String group = switch (r.nextInt(3)) {
                case 0 -> null;
                case 1 -> ",".equals(mark) ? "." : ",";
                default -> " ";
            };
            int rows = r.nextBoolean() ? 240 : 40;
            char delim = ",".equals(mark) || ",".equals(group) ? ';' : ',';
            List<BigDecimal> values = new ArrayList<>();
            StringBuilder csv = new StringBuilder("id" + delim + "amount" + delim + "note\n");
            for (int i = 0; i < rows; i++) {
                // at least one value per file reaches the thousands, so grouping (when chosen) shows
                BigDecimal v = BigDecimal.valueOf(i == 0 ? 1_234_567_89L : (long) r.nextInt(500_000_000) - 100_000_000L, 2);
                values.add(v);
                String cell = render(v, mark, group);
                csv.append("R").append(i).append(delim).append(cell).append(delim).append("n").append(i).append('\n');
            }
            String what = "trial " + trial + " mark '" + mark + "' group '" + group + "' rows " + rows;
            OnboardDraft.Analysis a = analyse(dir, "t" + trial + ".csv", csv.toString(), Map.of());

            Map<String, Object> ch = fmt(a, "decimalMark");
            assertEquals(mark, ch.get("value"), what + ": " + ch);
            assertEquals(rows >= OnboardDraft.MIN_SAMPLE ? "high" : "low", ch.get("confidence"), what + ": OF-2: " + ch);
            OnboardDraft.Col amount = col(a, "amount");
            assertEquals("DOUBLE", amount.type(), what);
            boolean needsMark = ",".equals(mark) || group != null;
            assertEquals(needsMark ? mark : null, amount.decimal(), what + ": only a number a plain cast cannot read carries the mark");
            assertNull(col(a, "id").decimal(), what);

            // the expression the commit writes reads every value back exactly
            if (needsMark) assertReadsBack(values, mark, group, amount.type(), what);
        }
    }

    private static void assertReadsBack(List<BigDecimal> values, String mark, String group, String type, String what) throws Exception {
        try (Connection c = DuckDbUtil.openInMemory(null, List.of(), null); Statement st = c.createStatement()) {
            st.execute("CREATE TABLE v (raw VARCHAR, expect DECIMAL(18,2))");
            StringBuilder ins = new StringBuilder("INSERT INTO v VALUES ");
            for (int i = 0; i < values.size(); i++)
                ins.append(i == 0 ? "" : ", ").append("('").append(render(values.get(i), mark, group)).append("', ").append(values.get(i)).append(')');
            st.execute(ins.toString());
            try (ResultSet rs = st.executeQuery("SELECT count(*) FROM v WHERE CAST(" + DecimalMark.sql("raw", type, mark)
                    + " AS DECIMAL(18,2)) IS DISTINCT FROM expect")) {
                rs.next();
                assertEquals(0, rs.getLong(1), what + ": every value reads back");
            }
        }
    }

    @Test
    void aStatedMarkWinsAndAnUndecidedFileSaysSo(@TempDir Path dir) throws Exception {
        // '1.234' reads either way: '.' assumed, low
        StringBuilder csv = new StringBuilder("id,qty\n");
        for (int i = 0; i < 240; i++) csv.append(i).append(",").append(1 + i % 9).append(".").append(String.format("%03d", i)).append('\n');
        OnboardDraft.Analysis a = analyse(dir, "either.csv", csv.toString(), Map.of());
        Map<String, Object> ch = fmt(a, "decimalMark");
        assertEquals(".", ch.get("value"));
        assertEquals("low", ch.get("confidence"), ch.toString());
        assertTrue(String.valueOf(ch.get("evidence")).contains("read either way"), ch.toString());

        // the user says ','
        OnboardDraft.Analysis b = analyse(dir, "either2.csv", csv.toString(), Map.of("decimal", ","));
        assertEquals(",", fmt(b, "decimalMark").get("value"));
        assertEquals("high", fmt(b, "decimalMark").get("confidence"));
        assertEquals("DOUBLE", col(b, "qty").type(), "'1.234' with ',' as the mark is 1234, kept DOUBLE so a later fraction is not cut: " + b.columns());

        // identifiers with a leading zero are never numbers, under either mark: the zeros would be lost
        StringBuilder ids = new StringBuilder("siren;account;amount\n");
        for (int i = 0; i < 240; i++)
            ids.append(String.format("000 %03d %03d", i, i)).append(';').append(String.format("012.%03d.%03d", i, i)).append(';')
                    .append(i).append(",50\n");
        OnboardDraft.Analysis z = analyse(dir, "ids.csv", ids.toString(), Map.of());
        assertEquals(",", fmt(z, "decimalMark").get("value"), "the amounts decide the mark");
        assertFalse(String.valueOf(fmt(z, "decimalMark").get("evidence")).contains("'account'"), "an identifier is no evidence: " + fmt(z, "decimalMark"));
        assertEquals("VARCHAR", col(z, "siren").type(), z.columns().toString());
        assertEquals("VARCHAR", col(z, "account").type(), z.columns().toString());
        assertEquals("DOUBLE", col(z, "amount").type());
        assertEquals(",", col(b, "qty").decimal());

        // two columns that disagree: '.' kept, low, both named
        StringBuilder mixed = new StringBuilder("a;b\n");
        for (int i = 0; i < 240; i++) mixed.append(i).append(".5;").append(i).append(",5\n");
        Map<String, Object> m = fmt(analyse(dir, "mixed.csv", mixed.toString(), Map.of()), "decimalMark");
        assertEquals(".", m.get("value"));
        assertEquals("low", m.get("confidence"));
        assertTrue(String.valueOf(m.get("evidence")).contains("'a'") && String.valueOf(m.get("evidence")).contains("'b'"), m.toString());

        // nothing has a mark: it changes nothing, high
        Map<String, Object> none = fmt(analyse(dir, "ints.csv", "a,b\n1,x\n2,y\n", Map.of()), "decimalMark");
        assertEquals("high", none.get("confidence"), none.toString());
    }

    // ── nested JSON: record path and fields to flatten ─────────────────────────────────────────────────────────

    private static String key(Random r) {
        String[] words = {"data", "items", "payload", "result", "rows", "batch", "export", "page", "content", "body"};
        return words[r.nextInt(words.length)] + r.nextInt(100);
    }

    @Test
    void theRecordListIsFoundAndNestedObjectsAreSpreadIntoDottedColumns(@TempDir Path dir) throws Exception {
        Random r = new Random(SEED + 1);
        for (int trial = 0; trial < 16; trial++) {
            int depth = r.nextInt(3);                   // 0 = the document is the list
            List<String> wrapper = new ArrayList<>();
            for (int i = 0; i < depth; i++) wrapper.add(key(r));
            int nest = 1 + r.nextInt(3);                 // depth of the nested object inside each record
            int records = r.nextBoolean() ? 220 : 30;
            boolean withList = r.nextBoolean();

            StringBuilder recs = new StringBuilder("[");
            for (int i = 0; i < records; i++) {
                if (i > 0) recs.append(',');
                StringBuilder inner = new StringBuilder("\"leaf\":\"v" + i + "\"");
                for (int k = nest; k >= 1; k--) inner = new StringBuilder("\"lvl" + k + "\":{" + inner + ",\"n" + k + "\":" + (i * k) + "}");
                recs.append("{\"id\":\"X").append(i).append("\",").append(inner);
                if (withList) recs.append(",\"tags\":[\"a\",\"b").append(i).append("\"]");
                recs.append('}');
            }
            recs.append(']');
            // each wrapper level also holds an object with no list in it, which must not be taken for the records
            String doc = recs.toString();
            for (int i = depth - 1; i >= 0; i--) doc = "{\"meta\":{\"count\":" + records + "},\"" + wrapper.get(i) + "\":" + doc + "}";
            String what = "trial " + trial + " path " + wrapper + " nest " + nest + " records " + records;

            OnboardDraft.Analysis a = analyse(dir, "t" + trial + ".json", doc, Map.of());
            Map<String, Object> path = fmt(a, "recordPath");
            assertEquals(depth == 0 ? "$" : String.join(".", wrapper), path.get("value"), what);
            assertEquals(records >= OnboardDraft.MIN_SAMPLE ? "high" : "low", path.get("confidence"), what + ": " + path);
            assertEquals(records, a.rows(), what);

            // expected columns: id, the leaf and every n<k> under lvl1.lvl2..., and the list as one column
            Set<String> want = new LinkedHashSet<>();
            want.add("id");
            String prefix = "";
            List<String> spread = new ArrayList<>();
            for (int k = 1; k <= nest; k++) {
                prefix = prefix.isEmpty() ? "lvl" + k : prefix + ".lvl" + k;
                spread.add(prefix);
                want.add(prefix + ".n" + k);
            }
            want.add(prefix + ".leaf");
            if (withList) want.add("tags");
            Set<String> got = new LinkedHashSet<>();
            for (OnboardDraft.Col c : a.cols()) got.add(c.selector());
            assertEquals(want, got, what);
            assertEquals(spread, fmt(a, "flatten").get("value"), what);
            OnboardDraft.Col leaf = a.cols().stream().filter(c -> c.selector().endsWith(".leaf")).findFirst().orElseThrow();
            assertEquals(prefix.replace('.', '_') + "_leaf", leaf.name(), "a dotted path becomes a plain column name");
            if (withList)
                assertTrue(String.valueOf(fmt(a, "flatten").get("evidence")).contains("tags"), "a list is named as kept JSON text");
            assertEquals("BIGINT", a.cols().stream().filter(c -> c.selector().endsWith(".n1")).findFirst().orElseThrow().type(), what);
        }
    }

    @Test
    void anObjectThatIsSometimesAValueStaysWholeAndAStatedPathIsChecked(@TempDir Path dir) throws Exception {
        String doc = "{\"a\":[{\"x\":1}],\"b\":[{\"geo\":{\"lat\":1}},{\"geo\":\"unknown\"}]}";
        OnboardDraft.Analysis first = analyse(dir, "two.json", doc, Map.of());
        assertEquals("a", fmt(first, "recordPath").get("value"));
        assertEquals("low", fmt(first, "recordPath").get("confidence"), "another list exists");
        assertTrue(String.valueOf(fmt(first, "recordPath").get("evidence")).contains("'b'"));

        OnboardDraft.Analysis b = analyse(dir, "two2.json", doc, Map.of("recordPath", "b"));
        assertEquals("b", fmt(b, "recordPath").get("value"));
        assertEquals(List.of("geo"), b.cols().stream().map(OnboardDraft.Col::selector).toList(), "no value is lost to a spread");
        assertTrue(String.valueOf(fmt(b, "flatten").get("evidence")).contains("geo is an object in some records"));

        assertThrows(com.gamma.spi.auth.ApiException.class, () -> analyse(dir, "two3.json", doc, Map.of("recordPath", "c")));
        // a key with a dot is escaped in the selector, not split
        OnboardDraft.Analysis dotted = analyse(dir, "dot.json", "{\"r.s\":[{\"k\":{\"m.n\":2}}]}", Map.of());
        assertEquals("r\\.s", fmt(dotted, "recordPath").get("value"));
        assertEquals("k.m\\.n", dotted.cols().get(0).selector());
        // a backslash is kept as it is: the engine's selector reading unescapes only '\.'
        OnboardDraft.Analysis slash = analyse(dir, "slash.json", "[{\"w\\\\k\":\"v\"}]", Map.of());
        assertEquals("w\\k", slash.cols().get(0).selector());
        assertEquals(List.of("w\\k"), OnboardJson.segments(slash.cols().get(0).selector()));
    }

    // ── a date in the file name ──────────────────────────────────────────────────────────────────────────────

    @Test
    void theDateInAFileNameIsReadAndTheNameIsProposedWithoutIt() {
        Random r = new Random(SEED + 2);
        String[][] styles = {{"yyyy-MM-dd", "%Y-%m-%d", "high"}, {"yyyy_MM_dd", "%Y_%m_%d", "high"}, {"yyyyMMdd", "%Y%m%d", "low"},
                {"dd.MM.yyyy", "%d.%m.%Y", "low"}, {"yyyy-MM", "%Y-%m", "high"}, {"yyyyMM", "%Y%m", "low"}};
        String[] stems = {"sales", "cdr_export", "Orders", "mobile money"};
        for (int trial = 0; trial < 60; trial++) {
            LocalDate d = LocalDate.of(1995, 1, 1).plusDays(r.nextInt(365 * 40));
            String[] s = styles[r.nextInt(styles.length)];
            String text = d.format(java.time.format.DateTimeFormatter.ofPattern(s[0], Locale.ROOT));
            String stem = stems[r.nextInt(stems.length)];
            boolean before = r.nextBoolean();
            String file = (before ? text + "_" + stem : stem + "_" + text) + (r.nextBoolean() ? ".csv" : ".json");
            Map<String, Object> ch = OnboardDraft.fileDate(file);
            String iso = s[0].contains("dd") ? d.toString() : d.toString().substring(0, 7);
            assertEquals(iso, ch.get("value"), file);
            assertEquals(s[1], ch.get("format"), file);
            assertEquals(s[2], ch.get("confidence"), file);
            assertEquals(stem.replace(' ', '_'), ch.get("name"), file);
        }
        for (String plain : List.of("customers.csv", "export_v2.csv", "account_123456789.csv", "cells_2026-13-40.csv", "x_123456.csv")) {
            Map<String, Object> ch = OnboardDraft.fileDate(plain);
            assertNull(ch.get("value"), plain + " holds no date: " + ch);
            assertEquals("high", ch.get("confidence"));
        }
    }
}
