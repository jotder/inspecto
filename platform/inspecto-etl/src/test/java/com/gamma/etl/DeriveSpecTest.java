package com.gamma.etl;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link DeriveSpec}: the closed-vocabulary derive compiler and evaluator, over hand-built decoded maps. */
class DeriveSpecTest {

    private static Map<String, Object> m(Object... kv) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) out.put((String) kv[i], kv[i + 1]);
        return out;
    }

    private static final Map<String, Object> RECORD = m(
            "ids", m("e", List.of(m("t", 0, "v", "1234"), m("t", 1, "v", "ab"), m("t", 1, "v", "cd"))),
            "use", m("u", List.of(m("g", 1, "up", 10, "dn", "20"), m("g", 1, "up", 5, "dn", "x"),
                    m("g", 2, "up", 100, "dn", "1.5"))),
            "none", m("u", List.of()),
            "tags", m("t", List.of(m("n", "a"), m("n", "b"), m("n", "c"))));

    private static DeriveSpec specOf(Object derive) {
        return DeriveSpec.parse(m("R", derive), Set.of("R"), null, null);
    }

    private static DeriveSpec spec(String col, Object op) {
        return specOf(m(col, op));
    }

    private static Object one(String col, Object op) throws IOException {
        return spec(col, op).evaluate("R", RECORD, new DeriveSpec.Stats())[0];
    }

    private static Map<String, Object> select(String path, Object where, String take, Object nth) {
        Map<String, Object> o = m("select", path, "where", where, "take", take);
        if (nth != null) o.put("nth", nth);
        return o;
    }

    private static Map<String, Object> reduce(String op, String of, Object... extra) {
        Map<String, Object> o = m("reduce", "use.u", "op", op);
        if (of != null) o.put("of", of);
        for (int i = 0; i < extra.length; i += 2) o.put((String) extra[i], extra[i + 1]);
        return o;
    }

    @Test
    void selectByTypeLiteralNthAndMissing() throws IOException {
        assertEquals("1234", one("C", select("ids.e", m("t", 0), "v", null)));
        assertEquals("ab", one("C", select("ids.e", m("t", "1"), "v", null)), "stringified leaf equality");
        assertEquals("cd", one("C", select("ids.e", m("t", 1), "v", 1)), "nth is 0-based among matches");
        assertEquals(null, one("C", select("ids.e", m("t", 1), "v", 2)), "nth beyond the matches");
        assertEquals(null, one("C", select("ids.e", m("t", 9), "v", null)), "no match");
        assertEquals(null, one("C", select("nope.e", m("t", 0), "v", null)), "missing list path");
        assertEquals(null, one("C", select("none.u", m("t", 0), "v", null)), "empty list");
        assertEquals(null, one("C", select("ids.e", m("t", 0), "absent", null)), "take of a missing leaf");
    }

    @Test
    void noMatchIsCounted() throws IOException {
        DeriveSpec.Stats st = new DeriveSpec.Stats();
        spec("C", select("ids.e", m("t", 9), "v", null)).evaluate("R", RECORD, st);
        assertEquals(1, st.noMatch);
    }

    @Test
    void reduceSumMinMaxFirstCountJoin() throws IOException {
        assertEquals("115", one("C", reduce("sum", "up")));
        assertEquals("5", one("C", reduce("min", "up")));
        assertEquals("100", one("C", reduce("max", "up")));
        assertEquals("1", one("C", reduce("first", "g")));
        assertEquals("3", one("C", reduce("count", null)));
        assertEquals("1,1,2", one("C", reduce("join", "g")));
        assertEquals("1;1;2", one("C", reduce("join", "g", "sep", ";")));
        assertEquals("a|b|c", one("C", m("reduce", "tags.t", "op", "join", "of", "n", "sep", "|")));
    }

    @Test
    void groupByCombinesTheGroupsIntoOneScalar() throws IOException {
        List<String> g = List.of("g");
        assertEquals("115", one("C", reduce("sum", "up", "group_by", g)));
        assertEquals("5", one("C", reduce("min", "up", "group_by", g)));
        assertEquals("100", one("C", reduce("max", "up", "group_by", g)));
    }

    @Test
    void emptyListMissingPathAndNonNumericGiveNullNeverZero() throws IOException {
        assertEquals(null, one("C", m("reduce", "none.u", "op", "sum", "of", "up")));
        assertEquals(null, one("C", m("reduce", "none.u", "op", "count")));
        assertEquals(null, one("C", m("reduce", "nope.u", "op", "sum", "of", "up")));
        assertEquals(null, one("C", reduce("sum", "absent")), "no leaf at all");
        // "dn" holds 20, x, 1.5: x is skipped and counted, never coerced to 0
        DeriveSpec.Stats st = new DeriveSpec.Stats();
        assertEquals("21.5", spec("C", reduce("sum", "dn")).evaluate("R", RECORD, st)[0]);
        assertEquals(1, st.nonNumeric);
        assertEquals("1.5", spec("C", reduce("min", "dn")).evaluate("R", RECORD, new DeriveSpec.Stats())[0]);
    }

    @Test
    void aListOverMaxListElementsFailsTheFile() {
        DeriveSpec capped = DeriveSpec.parse(m("R", m("C", reduce("sum", "up"))), Set.of("R"), 2, null);
        IOException e = assertThrows(IOException.class, () -> capped.evaluate("R", RECORD, new DeriveSpec.Stats()));
        assertTrue(e.getMessage().startsWith("ASN1-DERIVE-LIST-TOO-LARGE"), e.getMessage());
        DeriveSpec atCap = DeriveSpec.parse(m("R", m("C", reduce("sum", "up"))), Set.of("R"), 3, null);
        assertDoesNotThrow(() -> atCap.evaluate("R", RECORD, new DeriveSpec.Stats()));
    }

    @Test
    void columnsKeepProfileOrderAndASegmentWithoutDeriveAddsNothing() throws IOException {
        DeriveSpec s = specOf(m("B", reduce("count", null), "A", reduce("sum", "up")));
        assertEquals(List.of("B", "A"), s.columns("R"));
        assertEquals(List.of(), s.columns("Other"));
        assertArrayEquals(new Object[]{"3", "115"}, s.evaluate("R", RECORD, new DeriveSpec.Stats()));
        assertEquals(0, s.evaluate("Other", RECORD, new DeriveSpec.Stats()).length);
        assertEquals(List.of(), DeriveSpec.none().columns("R"));
    }

    // validation: one red test per code

    private static void refused(String code, Runnable r) {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, r::run);
        assertTrue(e.getMessage().startsWith("ASN1-DERIVE-" + code + ":"), e.getMessage());
    }

    @Test
    void unknownSegment() {
        refused("UNKNOWN-SEGMENT", () -> DeriveSpec.parse(m("Nope", m("C", reduce("count", null))), Set.of("R"), null, null));
    }

    @Test
    void unknownOpAndExtraKeys() {
        refused("UNKNOWN-OP", () -> spec("C", reduce("avg", "up")));
        refused("UNKNOWN-OP", () -> spec("C", m("reduce", "use.u", "of", "up")));
        refused("UNKNOWN-OP", () -> spec("C", reduce("sum", "up", "expr", "1+1")));
        refused("UNKNOWN-OP", () -> spec("C", m("select", "ids.e", "reduce", "ids.e")));
        refused("UNKNOWN-OP", () -> spec("C", m("select", "ids.e", "take", "v")));
        refused("UNKNOWN-OP", () -> spec("C", select("ids.e", m("a", 1, "b", 2), "v", null)));
        refused("UNKNOWN-OP", () -> spec("C", reduce("join", "g", "sep", ",,")));
        refused("UNKNOWN-OP", () -> spec("C", reduce("first", "g", "group_by", List.of("g"))));
        refused("UNKNOWN-OP", () -> spec("C", "sum(up)"));
    }

    @Test
    void columnClash() {
        refused("COLUMN-CLASH", () -> spec("EVENT_TYPE", reduce("count", null)));
        DeriveSpec s = spec("C", reduce("count", null));
        refused("COLUMN-CLASH", () -> s.checkClash("R", List.of("X", "C")));
    }

    @Test
    void badPath() {
        refused("BAD-PATH", () -> spec("C", reduce("sum", null)));
        refused("BAD-PATH", () -> spec("C", m("reduce", "", "op", "count")));
        refused("BAD-PATH", () -> spec("C", m("reduce", "a..b", "op", "count")));
        refused("BAD-PATH", () -> spec("C", m("reduce", "a.b.c.d.e.f.g.h.i", "op", "count")));
        refused("BAD-PATH", () -> spec("C", m("reduce", "$parent.x", "op", "count")));
        refused("BAD-PATH", () -> spec("C", reduce("sum", "up", "group_by", List.of())));
    }

    @Test
    void limit() {
        refused("LIMIT", () -> DeriveSpec.parse(null, null, 0, null));
        refused("LIMIT", () -> DeriveSpec.parse(null, null, DeriveSpec.CEILING_MAX_LIST_ELEMENTS + 1, null));
        refused("LIMIT", () -> DeriveSpec.parse(null, null, null, "abc"));
        refused("LIMIT", () -> DeriveSpec.parse(m("R", m("A", reduce("count", null), "B", reduce("count", null))),
                Set.of("R"), null, 1));
    }

    @Test
    void decodeProfileValidatesTheAsn1Block() {
        Map<String, Object> block = m("segments", m("R", "r.toon"), "derive", m("Nope", m("C", reduce("count", null))));
        refused("UNKNOWN-SEGMENT", () -> DecodeProfile.validateDerive(block));
        refused("LIMIT", () -> DecodeProfile.validateDerive(m("max_list_elements", 0)));
        DecodeProfile.validateDerive(m("segments", m("R", "r.toon"), "derive", m("R", m("C", reduce("count", null)))));
        DecodeProfile.validateDerive(m("segments", m("R", "r.toon")));
    }
}
