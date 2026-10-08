package com.gamma.query;

import com.gamma.util.Conditions;
import com.gamma.util.DottedPath;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * EQUIVALENCE CORPUS (MODULE-REORG-P7-KERNEL, Decision Kernel step 7 precondition): how far can the structured
 * condition tree ({@link ConditionTree}, strict mode) stand in for the {@link Conditions} text notation Access
 * Policies use? A generated cross-product of attribute contexts x expressions is evaluated both ways through a
 * TEST-ONLY translator (never shipped; the tree is NOT given new operators) and every pair is classified
 * EQUAL / DIVERGENT(feature) / UNTRANSLATABLE(reason). The counts are a committed golden table; any change to
 * either engine that moves them fails this test until the golden is consciously regenerated
 * ({@code -Dgolden.update=true}). Malformed sources must fail in {@link Conditions#parse} AND be refused by
 * the translator, so "fail closed on a parse error" is identical on both sides.
 */
class ConditionsTreeEquivalenceTest {

    private static final String GOLDEN = "conditions-tree-equivalence.golden.txt";

    // ── contexts ────────────────────────────────────────────────────────────────────

    private static Map<String, Object> m(Object... kv) {
        Map<String, Object> o = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) o.put((String) kv[i], kv[i + 1]);
        return o;
    }

    private static List<Map<String, Object>> contexts() {
        return List.of(
                m("subject", m("name", "Alice", "age", 30, "roles", List.of("admin", "ops"), "active", true, "code", "5",
                                "blank", "", "nul", null),
                        "resource", m("space", "alpha", "tags", List.of("a", "b"), "level", 5), "env", m("mode", "prod")),
                m("subject", m("name", "alice", "age", "30", "roles", List.of(), "active", "true", "code", 5,
                                "blank", "x", "nul", "n"),
                        "resource", m("space", "Alpha", "tags", List.of("a"), "level", "5"), "env", m("mode", "dev")),
                m(),
                m("subject", m("name", "a", "age", 5.0, "roles", List.of(1, 2, 5), "active", false, "code", "abc"),
                        "resource", m("space", "a", "tags", List.of("a", "b", "c"), "level", 0), "env", m("mode", null)),
                m("subject", m("name", 5, "age", "abc", "active", 1, "roles", "admin", "blank", null),
                        "resource", m("space", "", "tags", "a", "level", true), "env", m("mode", "")),
                m("subject", m("name", "Alice", "roles", List.of("ADMIN"), "active", Boolean.FALSE),
                        "env", m("mode", "prod", "missing", "here")),
                m("resource", m("space", "alpha", "level", 30)),
                m("subject", m("name", "", "age", 0, "roles", List.of("a", "ops"), "code", "0", "nul", null),
                        "resource", m("tags", List.of(), "space", "a")),
                m("subject", m("name", "Alice Smith", "age", -1, "roles", List.of("admin"), "active", true),
                        "resource", m("space", "alpha-1", "tags", List.of("alpha"), "level", 5.5)),
                m("env", m("mode", "prod"), "subject", m("name", true, "age", false, "code", "true")),
                m("subject", m("name", "5", "age", 5, "roles", List.of("5", 5), "code", 5.0),
                        "resource", m("space", "5", "level", "05")),
                m("subject", m("name", "Alice", "age", 30, "roles", List.of("admin", "ops"), "active", true, "code", "5",
                                "blank", "", "nul", null),
                        "resource", m("space", "Alice", "tags", List.of("Alice"), "level", 30), "env", m("mode", "Alice")));
    }

    // ── expression generation ───────────────────────────────────────────────────────

    private static final List<String> REFS = List.of("subject.name", "subject.age", "subject.roles", "subject.active",
            "subject.code", "subject.blank", "subject.nul", "resource.space", "resource.tags", "resource.level",
            "env.mode", "env.missing");
    private static final List<String> LITS = List.of("'Alice'", "'alice'", "'admin'", "'a'", "''", "'5'", "5", "30", "0",
            "true", "false", "null");
    private static final List<String> RHS_REFS = List.of("resource.space", "resource.tags", "subject.roles", "subject.name",
            "env.missing");
    private static final List<String> OPS = List.of("==", "!=", "in", "contains");

    private static List<String> atoms() {
        List<String> out = new ArrayList<>();
        for (String r : REFS) for (String op : OPS) {
            for (String l : LITS) out.add(r + " " + op + " " + l);
            for (String rr : RHS_REFS) out.add(r + " " + op + " " + rr);
        }
        for (String l : LITS) for (String op : OPS) for (String r : REFS) out.add(l + " " + op + " " + r);
        return out;
    }

    private static List<String> expressions() {
        List<String> atoms = atoms();
        List<String> out = new ArrayList<>(atoms);
        for (String a : atoms) out.add("not " + a);
        List<String> pick = new ArrayList<>();
        for (int i = 0; i < atoms.size(); i += atoms.size() / 20) pick.add(atoms.get(i));
        for (String a : pick) for (String b : pick) {
            out.add(a + " and " + b);
            out.add(a + " or not (" + b + ")");
        }
        List<String> few = pick.subList(0, 8);
        for (String a : few) for (String b : few) for (String c : few) out.add("(" + a + " or " + b + ") and not " + c);
        return out;
    }

    // ── test-only translator: Conditions text -> condition tree ─────────────────────

    private sealed interface Node permits Lit, Ref, Not, Bin, Cmp { }
    private record Lit(String kind, String text) implements Node { }   // string | number | bool | null
    private record Ref(String path) implements Node { }
    private record Not(Node inner) implements Node { }
    private record Bin(boolean and, Node l, Node r) implements Node { }
    private record Cmp(String op, Node l, Node r) implements Node { }

    /** The tree cannot express this expression; {@code reason} is the golden key. */
    private static final class Untranslatable extends RuntimeException {
        Untranslatable(String reason) {
            super(reason);
        }
    }

    private static final class TParser {
        private final List<String[]> toks = new ArrayList<>(); // kind, text
        private int i;

        TParser(String s) {
            lex(s);
        }

        private void lex(String s) {
            int n = s.length(), p = 0;
            if (s.isBlank()) throw new IllegalArgumentException("blank");
            while (p < n) {
                char c = s.charAt(p);
                if (Character.isWhitespace(c)) { p++; continue; }
                if (c == '(' || c == ')') { toks.add(new String[]{String.valueOf(c), ""}); p++; continue; }
                if (c == '=' || c == '!') {
                    if (p + 1 >= n || s.charAt(p + 1) != '=') throw new IllegalArgumentException("lone " + c);
                    toks.add(new String[]{c == '=' ? "==" : "!=", ""});
                    p += 2;
                    continue;
                }
                if (c == '\'' || c == '"') {
                    StringBuilder sb = new StringBuilder();
                    int q = p + 1;
                    while (q < n && s.charAt(q) != c) {
                        if (s.charAt(q) == '\\' && q + 1 < n) { sb.append(s.charAt(q + 1)); q += 2; }
                        else sb.append(s.charAt(q++));
                    }
                    if (q >= n) throw new IllegalArgumentException("unterminated string");
                    toks.add(new String[]{"string", sb.toString()});
                    p = q + 1;
                    continue;
                }
                if (Character.isDigit(c) || (c == '-' && p + 1 < n && Character.isDigit(s.charAt(p + 1)))) {
                    int q = p + 1;
                    while (q < n && (Character.isDigit(s.charAt(q)) || s.charAt(q) == '.')) q++;
                    String num = s.substring(p, q);
                    Double.parseDouble(num); // NumberFormatException is an IllegalArgumentException
                    toks.add(new String[]{"number", num});
                    p = q;
                    continue;
                }
                if (Character.isLetter(c) || c == '_') {
                    int q = p + 1;
                    while (q < n && (Character.isLetterOrDigit(s.charAt(q)) || "_-.".indexOf(s.charAt(q)) >= 0)) q++;
                    String w = s.substring(p, q);
                    switch (w) {
                        case "and", "or", "not", "in", "contains", "true", "false", "null" -> toks.add(new String[]{w, ""});
                        default -> {
                            if (w.startsWith(".") || w.endsWith(".") || w.contains(".."))
                                throw new IllegalArgumentException("malformed reference");
                            toks.add(new String[]{"ref", w});
                        }
                    }
                    p = q;
                    continue;
                }
                throw new IllegalArgumentException("unexpected character " + c);
            }
            toks.add(new String[]{"eof", ""});
        }

        private String peek() { return toks.get(i)[0]; }

        Node parse() {
            Node e = or();
            if (!peek().equals("eof")) throw new IllegalArgumentException("trailing input");
            return e;
        }

        private Node or() {
            Node l = and();
            while (peek().equals("or")) { i++; l = new Bin(false, l, and()); }
            return l;
        }

        private Node and() {
            Node l = unary();
            while (peek().equals("and")) { i++; l = new Bin(true, l, unary()); }
            return l;
        }

        private Node unary() {
            if (peek().equals("not")) { i++; return new Not(unary()); }
            Node l = operand();
            String op = peek();
            if (op.equals("==") || op.equals("!=") || op.equals("in") || op.equals("contains")) {
                i++;
                return new Cmp(op, l, operand());
            }
            return l;
        }

        private Node operand() {
            String[] t = toks.get(i);
            switch (t[0]) {
                case "(" -> { i++; Node e = or(); if (!peek().equals(")")) throw new IllegalArgumentException("expected )"); i++; return e; }
                case "string", "number" -> { i++; return new Lit(t[0], t[1]); }
                case "true", "false" -> { i++; return new Lit("bool", t[0]); }
                case "null" -> { i++; return new Lit("null", ""); }
                case "ref" -> { i++; return new Ref(t[1]); }
                default -> throw new IllegalArgumentException("expected a value");
            }
        }
    }

    private static Map<String, Object> leaf(String field, String operator, String value, String valueField) {
        Map<String, Object> l = new LinkedHashMap<>();
        l.put("kind", "condition");
        l.put("field", field);
        l.put("operator", operator);
        if (value != null) l.put("value", value);
        if (valueField != null) l.put("valueField", valueField);
        return l;
    }

    private static Map<String, Object> group(boolean and, boolean negate, Object... items) {
        Map<String, Object> g = new LinkedHashMap<>();
        g.put("kind", "group");
        g.put("op", and ? "AND" : "OR");
        if (negate) g.put("negate", true);
        g.put("items", Arrays.asList(items));
        return g;
    }

    /** Translate a parsed node to a tree node (leaf or group); throws {@link Untranslatable}. */
    private static Map<String, Object> translate(Node n) {
        return switch (n) {
            case Not x -> group(true, true, translate(x.inner()));
            case Bin b -> group(b.and(), false, translate(b.l()), translate(b.r()));
            case Cmp c -> cmp(c);
            case Ref r -> throw new Untranslatable("bare reference used as a boolean");
            case Lit l -> throw new Untranslatable("bare literal used as a boolean");
        };
    }

    private static String litValue(Lit l) {
        return l.text();
    }

    private static Map<String, Object> cmp(Cmp c) {
        Node l = c.l(), r = c.r();
        if (l instanceof Lit && r instanceof Lit) throw new Untranslatable("literal compared with a literal");
        if (!(l instanceof Ref) && !(l instanceof Lit)) throw new Untranslatable("parenthesised operand");
        if (!(r instanceof Ref) && !(r instanceof Lit)) throw new Untranslatable("parenthesised operand");
        boolean eq = c.op().equals("==") || c.op().equals("!=");
        if (l instanceof Lit ll && r instanceof Ref rr) {
            if (eq) return cmp(new Cmp(c.op(), rr, ll)); // equality is symmetric
            if (c.op().equals("contains")) throw new Untranslatable("literal on the left of contains");
            if (ll.kind().equals("null")) throw new Untranslatable("null member in an in-test");
            return leaf(rr.path(), "contains", litValue(ll), null); // lit in ref  ~  ref contains lit
        }
        Ref f = (Ref) l;
        if (r instanceof Ref g) {
            return switch (c.op()) {
                case "==" -> leaf(f.path(), "=", null, g.path());
                case "!=" -> leaf(f.path(), "!=", null, g.path());
                case "in" -> leaf(g.path(), "contains", null, f.path());
                default -> leaf(f.path(), "contains", null, g.path());
            };
        }
        Lit v = (Lit) r;
        switch (c.op()) {
            case "==", "!=" -> {
                if (v.kind().equals("null")) return leaf(f.path(), c.op().equals("==") ? "isNull" : "isNotNull", null, null);
                return leaf(f.path(), c.op().equals("==") ? "=" : "!=", litValue(v), null);
            }
            case "in" -> throw new Untranslatable("in-test against a non-collection right operand");
            default -> {
                if (v.kind().equals("null")) throw new Untranslatable("null member in a contains-test");
                return leaf(f.path(), "contains", litValue(v), null);
            }
        }
    }

    /** Root wrapper: the strict tree wants a group root. */
    private static Map<String, Object> toTree(String src) {
        Map<String, Object> t = translate(new TParser(src).parse());
        return "group".equals(t.get("kind")) ? t : group(true, false, t);
    }

    // ── evaluation ──────────────────────────────────────────────────────────────────

    private static void flatten(String prefix, Map<?, ?> in, Map<String, Object> out) {
        for (Map.Entry<?, ?> e : in.entrySet()) {
            String k = prefix + e.getKey();
            if (e.getValue() instanceof Map<?, ?> sub) flatten(k + ".", sub, out);
            else out.put(k, e.getValue());
        }
    }

    private static boolean treeResult(Map<String, Object> tree, Map<String, Object> ctx) {
        Map<String, Object> row = new LinkedHashMap<>();
        flatten("", ctx, row);
        return ConditionTree.matchedStrict(tree, List.of(row)) == 1;
    }

    // ── divergence attribution (first diverging atom, by priority) ──────────────────

    private static void atoms(Node n, List<Cmp> out) {
        switch (n) {
            case Not x -> atoms(x.inner(), out);
            case Bin b -> { atoms(b.l(), out); atoms(b.r(), out); }
            case Cmp c -> out.add(c);
            default -> { }
        }
    }

    private static String feature(Node whole, Map<String, Object> ctx, Map<String, Object> flat) {
        List<Cmp> atoms = new ArrayList<>();
        atoms(whole, atoms);
        for (Cmp a : atoms) {
            Boolean text = Conditions.parse(render(a)).test(ctx);
            boolean tree;
            try {
                tree = treeResult(toTree(render(a)), ctx);
            } catch (Untranslatable | IllegalArgumentException e) {
                continue;
            }
            if (text == tree) continue;
            List<Object> vals = new ArrayList<>();
            boolean missing = false;
            for (Node o : List.of(a.l(), a.r())) {
                if (o instanceof Ref r) {
                    missing |= !flat.containsKey(r.path());
                    vals.add(DottedPath.resolve(ctx, r.path()));
                } else if (o instanceof Lit l) {
                    vals.add(switch (l.kind()) {
                        case "number" -> Double.valueOf(l.text());
                        case "bool" -> Boolean.valueOf(l.text());
                        case "null" -> null;
                        default -> l.text();
                    });
                }
            }
            if (vals.stream().anyMatch(v -> v instanceof Collection<?>)) return "COLLECTION_MEMBERSHIP";
            if (missing) return "MISSING_ATTRIBUTE";
            if (vals.stream().anyMatch(v -> v == null || "".equals(v))) return "NULL_OR_EMPTY_SEMANTICS";
            if (a.op().equals("contains") && vals.get(0) instanceof String && vals.get(1) instanceof String)
                return "CASE_INSENSITIVE_SUBSTRING";
            // in / contains over a scalar: Conditions needs a real collection (or two strings for contains);
            // the tree's contains stringifies the cell, so scalars "contain" their own text
            if (a.op().equals("in") || a.op().equals("contains")) return "SCALAR_CONTAINMENT_STRINGIFIED";
            if (!vals.get(0).getClass().equals(vals.get(1).getClass())
                    && !(vals.get(0) instanceof Number && vals.get(1) instanceof Number)) return "STRICT_TYPING_COERCION";
            // two strings: the tree infers a number/boolean type from the TEXT ('05' == '5' is numerically true)
            if (vals.stream().anyMatch(v -> v instanceof String t && t.matches("-?[0-9]+(\\.[0-9]+)?|(?i)true|false")))
                return "STRICT_TYPING_COERCION";
            return "OTHER";
        }
        return "COMPOSITION";
    }

    private static String render(Cmp c) {
        return str(c.l()) + " " + c.op() + " " + str(c.r());
    }

    private static String str(Node n) {
        return switch (n) {
            case Ref r -> r.path();
            case Lit l -> l.kind().equals("string") ? "'" + l.text().replace("\\", "\\\\").replace("'", "\\'") + "'"
                    : l.kind().equals("null") ? "null" : l.text();
            default -> throw new IllegalStateException();
        };
    }

    // ── the corpus ──────────────────────────────────────────────────────────────────

    private static final List<String> MALFORMED = List.of("", "   ", "a ==", "== a", "a = b", "a ! b", "(a == 1", "a == 1)",
            "'unterminated", "a == 1 b", "and", "a and", "not", "a == == b", "a..b == 1", ".a == 1", "a. == 1",
            "1.2.3 == a", "a == #", "a in", "a contains", "a == 1 or", "()", "a == 1 and or b == 2", "a b");

    private static String classify() {
        List<Map<String, Object>> contexts = contexts();
        List<String> exprs = expressions();
        Map<String, Integer> counts = new TreeMap<>();
        int pairs = 0, translatable = 0, untranslatable = 0;
        for (String src : exprs) {
            Conditions.Condition cond = Conditions.parse(src); // every generated source is well-formed
            Node ast = new TParser(src).parse();
            Map<String, Object> tree = null;
            String reason = null;
            try {
                tree = toTree(src);
                ConditionTree.validateStrict(tree);
            } catch (Untranslatable u) {
                reason = u.getMessage();
            } catch (IllegalArgumentException refused) {
                reason = "strict tree refuses the translated form (" + refused.getMessage().replaceAll("items\\[\\d+\\]", "items[n]") + ")";
            }
            if (reason == null) translatable++; else untranslatable++;
            for (Map<String, Object> ctx : contexts) {
                pairs++;
                if (reason != null) {
                    counts.merge("UNTRANSLATABLE", 1, Integer::sum);
                    counts.merge("  untranslatable: " + reason, 1, Integer::sum);
                    continue;
                }
                boolean text = cond.test(ctx);
                boolean tr = treeResult(tree, ctx);
                if (text == tr) {
                    counts.merge("EQUAL", 1, Integer::sum);
                } else {
                    Map<String, Object> flat = new LinkedHashMap<>();
                    flatten("", ctx, flat);
                    counts.merge("DIVERGENT", 1, Integer::sum);
                    counts.merge("  divergent: " + feature(ast, ctx, flat), 1, Integer::sum);
                }
            }
        }
        StringBuilder sb = new StringBuilder();
        sb.append("# Conditions text vs. condition tree (strict) - equivalence corpus golden. Regenerate with -Dgolden.update=true\n");
        sb.append("contexts: ").append(contexts.size()).append('\n');
        sb.append("expressions: ").append(exprs.size()).append(" (translatable ").append(translatable)
                .append(", untranslatable ").append(untranslatable).append(")\n");
        sb.append("pairs: ").append(pairs).append('\n');
        counts.forEach((k, v) -> sb.append(k).append(": ").append(v).append('\n'));
        sb.append("malformed sources: ").append(MALFORMED.size()).append(" (all refused by Conditions.parse and by the translator)\n");
        return sb.toString();
    }

    @Test
    void classificationMatchesTheCommittedGolden() throws IOException {
        String actual = classify();
        Path src = Path.of("src/test/resources", GOLDEN);
        if (Boolean.getBoolean("golden.update")) {
            Files.writeString(src, actual, StandardCharsets.UTF_8);
            return;
        }
        String expected;
        try (InputStream in = ConditionsTreeEquivalenceTest.class.getResourceAsStream("/" + GOLDEN)) {
            expected = new String(in != null ? in.readAllBytes() : Files.readAllBytes(src), StandardCharsets.UTF_8);
        }
        assertEquals(expected.replace("\r\n", "\n"), actual, "equivalence classification moved - update the golden consciously");
    }

    @Test
    void everyDivergenceIsAttributedToANamedFeature() {
        String g = classify();
        assertTrue(!g.contains("divergent: OTHER") && !g.contains("divergent: COMPOSITION"),
                "an unattributed divergence means the corpus cannot explain a difference:\n" + g);
    }

    @Test
    void malformedSourcesFailClosedIdenticallyOnBothSides() {
        for (String bad : MALFORMED) {
            assertThrows(IllegalArgumentException.class, () -> Conditions.parse(bad), "Conditions must refuse: [" + bad + "]");
            assertThrows(IllegalArgumentException.class, () -> new TParser(bad).parse(), "translator must refuse: [" + bad + "]");
        }
    }

    @Test
    void aPartialTranslationOfATruncatedSourceIsRefusedByTheStrictTree() {
        // 'a ==' -> a half-built leaf; 'a == 1 or' -> a group whose second item is missing; '()' -> empty group
        Map<String, Object> halfLeaf = group(true, false, leaf("a", "=", null, null));
        Map<String, Object> emptyGroup = group(true, false);
        assertThrows(IllegalArgumentException.class, () -> ConditionTree.validateStrict(halfLeaf));
        assertThrows(IllegalArgumentException.class, () -> ConditionTree.validateStrict(emptyGroup));
        // while the default API would have read both as "no constraint / matches everything"
        assertEquals(1, ConditionTree.matched(halfLeaf, List.of(Map.of("a", 1))));
        assertEquals(1, ConditionTree.matched(emptyGroup, List.of(Map.of("a", 1))));
    }
}
