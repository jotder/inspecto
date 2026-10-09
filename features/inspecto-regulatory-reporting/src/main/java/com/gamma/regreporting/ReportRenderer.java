package com.gamma.regreporting;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.gamma.job.ApprovalFingerprint;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Renders a {@link ReportTemplate} over a <b>report context</b> into the document a regulator receives. The context
 * is plain data:
 * <pre>
 *   report    {id, createdAt, author}
 *   subject   the Case or Incident the report is raised from (its object view)
 *   incidents [ the Case's member Incidents ]   (the Incident itself when raised from one)
 *   evidence  [ {objectId, name, uri, contentType, caption, author, createdAt} ]   (attachment notes)
 *   input     {the maker's typed values}
 * </pre>
 * A source path walks maps; through a list it maps over the items. A required field that resolves empty, a value
 * over its {@code maxLength}, a source that lands on an object, or text XML cannot carry REFUSES the render — a
 * filing is never truncated or patched. Pure: no I/O, no clock.
 */
public final class ReportRenderer {

    private ReportRenderer() {}

    /** The rendered document must fit here. */
    static final int MAX_BYTES = 1024 * 1024;

    private static final ObjectMapper JSON = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    /** The document, its SHA-256 and its media type. */
    public record Rendered(String content, String sha256, String mediaType) {
    }

    /** Every reason a render was refused. */
    public static final class Refused extends Exception {
        private final List<String> problems;

        Refused(List<String> problems) {
            super(String.join("; ", problems));
            this.problems = List.copyOf(problems);
        }

        public List<String> problems() {
            return problems;
        }
    }

    /** One output entry, in template order: a plain field (one value or a list) or a repeating group. */
    private sealed interface Entry permits Plain, Group {
    }

    private record Plain(String name, Object value) implements Entry {
    }

    private record Group(String name, List<String> leaves, List<Map<String, String>> rows) implements Entry {
    }

    public static Rendered render(ReportTemplate t, Map<String, Object> context) throws Refused {
        List<String> problems = new ArrayList<>();
        List<Entry> entries = resolve(t, context, problems);
        if (!problems.isEmpty()) throw new Refused(problems);
        String content = switch (t.format()) {
            case "xml" -> xml(t, entries, problems);
            case "csv" -> csv(entries);
            case "json" -> json(t, entries);
            default -> throw new Refused(List.of("unknown format '" + t.format() + "'"));
        };
        if (!problems.isEmpty()) throw new Refused(problems);
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_BYTES)
            throw new Refused(List.of("the rendered report is " + bytes.length + " bytes; the cap is " + MAX_BYTES));
        return new Rendered(content, ApprovalFingerprint.sha256(bytes), mediaType(t.format()));
    }

    static String mediaType(String format) {
        return switch (format) {
            case "xml" -> "application/xml";
            case "csv" -> "text/csv";
            default -> "application/json";
        };
    }

    /**
     * The fingerprint of the SOURCE a report was rendered from — the context less {@code report} (its own id and
     * clock) and {@code input} (fixed on the record). Compared live against the record's to say a Case changed since.
     */
    public static String sourceFingerprint(Map<String, Object> context) {
        Map<String, Object> c = new LinkedHashMap<>(context);
        c.remove("report");
        c.remove("input");
        return ApprovalFingerprint.hash(c);
    }

    // ── resolution ───────────────────────────────────────────────────────────────────────────────

    private static List<Entry> resolve(ReportTemplate t, Map<String, Object> ctx, List<String> problems) {
        List<Entry> out = new ArrayList<>();
        Map<String, Group> groups = new LinkedHashMap<>();
        for (ReportTemplate.Field f : t.fields()) {
            if (f.group() == null) {
                Object v = value(f, ctx, problems);
                if (f.required() && blank(v)) problems.add("required field '" + f.name() + "' (" + f.source() + ") is empty");
                checkLength(f, v, problems);
                out.add(new Plain(f.name(), v));
                continue;
            }
            Group g = groups.get(f.group());
            if (g == null) {
                g = new Group(f.group(), new ArrayList<>(), new ArrayList<>());
                groups.put(f.group(), g);
                out.add(g);
            }
            g.leaves().add(f.leaf());
            String listRoot = f.source().substring(0, f.source().indexOf('.') < 0 ? f.source().length() : f.source().indexOf('.'));
            String rest = f.source().length() > listRoot.length() ? f.source().substring(listRoot.length() + 1) : "";
            List<?> items = ctx.get(listRoot) instanceof List<?> l ? l : List.of();
            if (f.required() && items.isEmpty())
                problems.add("required field '" + f.name() + "' (" + f.source() + ") is empty: no " + listRoot);
            for (int i = 0; i < items.size(); i++) {
                while (g.rows().size() <= i) g.rows().add(new LinkedHashMap<>());
                Object v = rest.isEmpty() ? items.get(i) : walk(items.get(i), rest.split("\\."), 0);
                String text = leafText(f, v, problems);
                if (f.required() && blank(text))
                    problems.add("required field '" + f.name() + "' is empty in " + listRoot + " item " + (i + 1));
                checkLength(f, text, problems);
                g.rows().get(i).put(f.leaf(), text);
            }
        }
        return out;
    }

    /** A plain field's value: a String, a List of Strings, or null. */
    private static Object value(ReportTemplate.Field f, Map<String, Object> ctx, List<String> problems) {
        if (f.source().startsWith(ReportTemplate.CONST)) return f.source().substring(ReportTemplate.CONST.length());
        String[] path = f.source().split("\\.");
        Object v = walk(ctx, path, 0);
        if (v instanceof List<?> l) {
            List<String> texts = new ArrayList<>();
            for (Object item : l) {
                String s = leafText(f, item, problems);
                if (!blank(s)) texts.add(s);
            }
            return texts;
        }
        return leafText(f, v, problems);
    }

    /** Walk {@code path[i..]} from {@code node}; through a list it maps over the items (flattening). */
    static Object walk(Object node, String[] path, int i) {
        if (i == path.length || node == null) return node;
        if (node instanceof List<?> l) {
            List<Object> out = new ArrayList<>();
            for (Object item : l) {
                Object v = walk(item, path, i);
                if (v instanceof List<?> inner) out.addAll(inner);
                else if (v != null) out.add(v);
            }
            return out;
        }
        if (node instanceof Map<?, ?> m) return walk(m.get(path[i]), path, i + 1);
        return null;
    }

    private static String leafText(ReportTemplate.Field f, Object v, List<String> problems) {
        if (v == null) return null;
        if (v instanceof Map<?, ?> || v instanceof List<?>) {
            problems.add("field '" + f.name() + "' (" + f.source() + ") resolves to an object, not a value - name a leaf");
            return null;
        }
        if (v instanceof BigDecimal d) return d.toPlainString();
        if (v instanceof Double || v instanceof Float) return new BigDecimal(v.toString()).toPlainString();
        return String.valueOf(v);
    }

    private static boolean blank(Object v) {
        if (v == null) return true;
        if (v instanceof List<?> l) return l.isEmpty();
        return String.valueOf(v).isBlank();
    }

    private static void checkLength(ReportTemplate.Field f, Object v, List<String> problems) {
        if (f.maxLength() <= 0 || v == null) return;
        List<?> values = v instanceof List<?> l ? l : List.of(v);
        for (Object one : values)
            if (String.valueOf(one).length() > f.maxLength())
                problems.add("field '" + f.name() + "' is " + String.valueOf(one).length() + " characters; its maxLength is "
                        + f.maxLength() + " (a filing is never truncated)");
    }

    // ── formats ──────────────────────────────────────────────────────────────────────────────────

    private static String xml(ReportTemplate t, List<Entry> entries, List<String> problems) {
        StringBuilder b = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        b.append('<').append(t.rootElement()).append(">\n");
        for (Entry e : entries) {
            if (e instanceof Plain p) {
                List<?> values = p.value() instanceof List<?> l ? l : p.value() == null ? List.of() : List.of(p.value());
                for (Object v : values) element(b, "  ", p.name(), String.valueOf(v), problems);
            } else if (e instanceof Group g) {
                for (Map<String, String> row : g.rows()) {
                    b.append("  <").append(g.name()).append(">\n");
                    for (String leaf : g.leaves()) if (row.get(leaf) != null) element(b, "    ", leaf, row.get(leaf), problems);
                    b.append("  </").append(g.name()).append(">\n");
                }
            }
        }
        b.append("</").append(t.rootElement()).append(">\n");
        return b.toString();
    }

    private static void element(StringBuilder b, String indent, String name, String text, List<String> problems) {
        b.append(indent).append('<').append(name).append('>').append(xmlText(name, text, problems))
                .append("</").append(name).append(">\n");
    }

    /** XML 1.0 text: escaped, and refused when it holds a character XML 1.0 cannot carry at all. */
    static String xmlText(String name, String s, List<String> problems) {
        StringBuilder out = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); ) {
            int c = s.codePointAt(i);
            i += Character.charCount(c);
            boolean legal = c == 0x9 || c == 0xA || c == 0xD || (c >= 0x20 && c <= 0xD7FF) || (c >= 0xE000 && c <= 0xFFFD)
                    || (c >= 0x10000 && c <= 0x10FFFF);
            if (!legal) {
                problems.add("field '" + name + "' holds a control character (U+" + String.format("%04X", c)
                        + ") XML 1.0 cannot carry");
                continue;
            }
            switch (c) {
                case '&' -> out.append("&amp;");
                case '<' -> out.append("&lt;");
                case '>' -> out.append("&gt;");
                case '"' -> out.append("&quot;");
                case '\'' -> out.append("&apos;");
                default -> out.appendCodePoint(c);
            }
        }
        return out.toString();
    }

    private static String csv(List<Entry> entries) {
        List<String> header = new ArrayList<>();
        List<String> row = new ArrayList<>();
        for (Entry e : entries) {
            if (e instanceof Plain p) {
                header.add(p.name());
                row.add(p.value() instanceof List<?> l ? String.join("; ", l.stream().map(String::valueOf).toList())
                        : p.value() == null ? "" : String.valueOf(p.value()));
            } else if (e instanceof Group g) {
                for (String leaf : g.leaves()) {
                    header.add(g.name() + "/" + leaf);
                    row.add(String.join("; ", g.rows().stream().map(r -> r.get(leaf) == null ? "" : r.get(leaf)).toList()));
                }
            }
        }
        return line(header) + line(row);
    }

    private static String line(List<String> cells) {
        List<String> out = new ArrayList<>(cells.size());
        for (String c : cells) out.add(csvCell(c));
        return String.join(",", out) + "\r\n";
    }

    /** RFC 4180 quoting, after neutralising a cell a spreadsheet would read as a formula. */
    static String csvCell(String c) {
        String s = c == null ? "" : c;
        if (!s.isEmpty() && "=+-@\t\r".indexOf(s.charAt(0)) >= 0) s = "'" + s;
        if (s.indexOf(',') >= 0 || s.indexOf('"') >= 0 || s.indexOf('\n') >= 0 || s.indexOf('\r') >= 0)
            s = '"' + s.replace("\"", "\"\"") + '"';
        return s;
    }

    private static String json(ReportTemplate t, List<Entry> entries) throws Refused {
        Map<String, Object> doc = new LinkedHashMap<>();
        for (Entry e : entries) {
            if (e instanceof Plain p) doc.put(p.name(), p.value());
            else if (e instanceof Group g) doc.put(g.name(), g.rows());
        }
        Object root = t.rootElement() == null ? doc : Map.of(t.rootElement(), doc);
        try {
            return JSON.writeValueAsString(root) + "\n";
        } catch (JsonProcessingException impossible) {
            throw new Refused(List.of("the report does not serialise as JSON: " + impossible.getMessage()));
        }
    }
}
