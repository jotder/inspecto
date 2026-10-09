package com.gamma.regreporting;

import com.gamma.config.io.ConfigCodec;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * A <b>Report Template</b> ({@code REGULATORY-REPORTING-1}): which fields a regulator-format document has, where each
 * one's value comes from in the {@link ReportContext report context}, how it is rendered ({@code xml | csv | json}) and
 * where a submitted report is dropped. A TOON document:
 * <pre>
 *   id: sample-sar
 *   title: ...
 *   format: xml
 *   rootElement: suspiciousActivityReport          (xml only)
 *   delivery:
 *     kind: file-drop
 *     dir: regulatory-submissions/sample-sar        (jailed like the report Job's out_dir)
 *   fields[N]{name,source,required,maxLength}:
 *     reportId,report.id,true,64
 *     incident/title,incidents.title,false,400       (a "group/leaf" field: one repeating group per list item)
 *     narrative,input.narrative,true,20000          (input.* = a value the maker types)
 * </pre>
 * Built-ins ship on the module classpath ({@link #BUILT_INS}); a Space's own sit in its config root under
 * {@link #SPACE_DIR}, and win on an id clash. A template that does not validate is listed with its problems and can
 * draft nothing.
 */
public record ReportTemplate(String id, String title, String description, String format, String rootElement,
                             String deliveryKind, String deliveryDir, List<Field> fields, String origin) {

    /** One output field. {@code group} is the part before {@code /} (null for a plain field). */
    public record Field(String name, String group, String leaf, String source, boolean required, int maxLength) {
    }

    static final String SPACE_DIR = "regulatory-report-templates";
    static final List<String> BUILT_INS = List.of("sample-sar");
    static final Set<String> FORMATS = Set.of("xml", "csv", "json");
    static final Set<String> ROOTS = Set.of("report", "subject", "incidents", "evidence", "input");
    static final Set<String> LIST_ROOTS = Set.of("incidents", "evidence");
    static final String CONST = "const:";
    static final Pattern SAFE_ID = Pattern.compile("[a-z0-9][a-z0-9-]{1,63}");
    /** An XML element name we are sure of: ASCII NCName, no "xml" prefix games. */
    static final Pattern ELEMENT = Pattern.compile("[A-Za-z_][A-Za-z0-9_.-]{0,63}");
    static final Pattern INPUT_KEY = Pattern.compile("[A-Za-z][A-Za-z0-9_]{0,63}");
    static final int MAX_FIELDS = 200;

    /** The {@code input.*} keys this template reads — the only inputs a draft may carry. */
    public Set<String> inputKeys() {
        Set<String> keys = new LinkedHashSet<>();
        for (Field f : fields) if (f.source().startsWith("input.")) keys.add(f.source().substring("input.".length()));
        return keys;
    }

    /** The file extension a submitted report of this template carries. */
    public String extension() {
        return format;
    }

    /** The view the templates route lists. */
    public Map<String, Object> view() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("title", title);
        m.put("description", description);
        m.put("format", format);
        m.put("origin", origin);
        m.put("delivery", Map.of("kind", deliveryKind, "dir", deliveryDir));
        m.put("inputs", List.copyOf(inputKeys()));
        List<Map<String, Object>> fs = new ArrayList<>();
        for (Field f : fields) {
            Map<String, Object> v = new LinkedHashMap<>();
            v.put("name", f.name());
            v.put("source", f.source());
            v.put("required", f.required());
            v.put("maxLength", f.maxLength());
            fs.add(v);
        }
        m.put("fields", fs);
        return m;
    }

    /** A template that failed to load: its id (or file name) and why. */
    public record Problem(String id, String origin, List<String> problems) {
        public Map<String, Object> view() {
            return Map.of("id", id, "origin", origin, "problems", problems);
        }
    }

    /** Thrown by {@link #parse} with every problem found, not just the first. */
    public static final class Invalid extends Exception {
        private final List<String> problems;

        Invalid(List<String> problems) {
            super(String.join("; ", problems));
            this.problems = List.copyOf(problems);
        }

        public List<String> problems() {
            return problems;
        }
    }

    // ── parsing ──────────────────────────────────────────────────────────────────────────────────

    /** Parse and validate one template document; {@code expectedId} (the file name) must equal its {@code id}. */
    public static ReportTemplate parse(String toon, String expectedId, String origin) throws Invalid {
        List<String> problems = new ArrayList<>();
        Map<String, Object> m;
        try {
            m = ConfigCodec.toMap(toon);
        } catch (RuntimeException unparseable) {
            throw new Invalid(List.of("does not parse as TOON: " + unparseable.getMessage()));
        }
        for (String k : m.keySet())
            if (!Set.of("id", "title", "description", "format", "rootElement", "delivery", "fields").contains(k))
                problems.add("unknown key '" + k + "'");
        String id = str(m.get("id"));
        if (id == null || !SAFE_ID.matcher(id).matches()) problems.add("'id' must match " + SAFE_ID.pattern());
        else if (expectedId != null && !expectedId.equals(id))
            problems.add("'id' is '" + id + "' but the file is named '" + expectedId + "'");
        String title = str(m.get("title"));
        if (title == null) problems.add("'title' is required");
        String format = str(m.get("format"));
        if (format == null || !FORMATS.contains(format.toLowerCase(Locale.ROOT)))
            problems.add("'format' must be one of " + new java.util.TreeSet<>(FORMATS));
        else format = format.toLowerCase(Locale.ROOT);
        String root = str(m.get("rootElement"));
        if ("xml".equals(format) && (root == null || !ELEMENT.matcher(root).matches()))
            problems.add("'rootElement' must match " + ELEMENT.pattern() + " for an xml template");

        String kind = null, dir = null;
        if (m.get("delivery") instanceof Map<?, ?> d) {
            kind = str(d.get("kind"));
            dir = str(d.get("dir"));
            for (Object k : d.keySet())
                if (!Set.of("kind", "dir").contains(String.valueOf(k))) problems.add("unknown key 'delivery." + k + "'");
            if (!"file-drop".equals(kind)) problems.add("'delivery.kind' must be file-drop (the only delivery built)");
            if (dir == null) problems.add("'delivery.dir' is required");
        } else problems.add("'delivery' is required ({kind: file-drop, dir: ...})");

        List<Field> fields = new ArrayList<>();
        if (m.get("fields") instanceof List<?> rows && !rows.isEmpty()) {
            if (rows.size() > MAX_FIELDS) problems.add("at most " + MAX_FIELDS + " fields");
            Set<String> names = new LinkedHashSet<>();
            Map<String, String> groupRoot = new TreeMap<>();
            Set<String> plainNames = new LinkedHashSet<>();
            for (Object row : rows) {
                if (!(row instanceof Map<?, ?> f)) {
                    problems.add("every field is {name, source, required, maxLength}");
                    continue;
                }
                String name = str(f.get("name"));
                String source = str(f.get("source"));
                boolean required = Boolean.parseBoolean(String.valueOf(f.get("required")));
                int max;
                try {
                    max = f.get("maxLength") == null ? 0 : Integer.parseInt(String.valueOf(f.get("maxLength")));
                } catch (NumberFormatException nan) {
                    problems.add("field '" + name + "': maxLength must be a whole number");
                    continue;
                }
                if (name == null || source == null) {
                    problems.add("every field needs a name and a source");
                    continue;
                }
                if (!names.add(name)) problems.add("field '" + name + "' is declared twice");
                String group = null, leaf = name;
                int slash = name.indexOf('/');
                if (slash >= 0) {
                    group = name.substring(0, slash);
                    leaf = name.substring(slash + 1);
                }
                if (!ELEMENT.matcher(leaf).matches() || (group != null && !ELEMENT.matcher(group).matches()))
                    problems.add("field '" + name + "': a name is an element name (" + ELEMENT.pattern()
                            + "), optionally 'group/leaf'");
                if (max < 0) problems.add("field '" + name + "': maxLength must be 0 (none) or more");
                problems.addAll(sourceProblems(name, source));
                if (group != null) {
                    String listRoot = source.contains(".") ? source.substring(0, source.indexOf('.')) : source;
                    if (!LIST_ROOTS.contains(listRoot))
                        problems.add("field '" + name + "': a group field reads a list (" + LIST_ROOTS + ")");
                    String prior = groupRoot.putIfAbsent(group, listRoot);
                    if (prior != null && !prior.equals(listRoot))
                        problems.add("group '" + group + "' mixes " + prior + " and " + listRoot
                                + " - one group repeats over one list");
                } else plainNames.add(name);
                fields.add(new Field(name, group, leaf, source, required, max));
            }
            for (String g : groupRoot.keySet())
                if (plainNames.contains(g)) problems.add("'" + g + "' is both a field and a group");
        } else problems.add("'fields' must list at least one {name, source, required, maxLength}");

        if (!problems.isEmpty()) throw new Invalid(problems);
        return new ReportTemplate(id, title, str(m.get("description")), format, root, kind, dir, List.copyOf(fields), origin);
    }

    private static List<String> sourceProblems(String name, String source) {
        if (source.startsWith(CONST)) return List.of();
        String root = source.contains(".") ? source.substring(0, source.indexOf('.')) : source;
        if (!ROOTS.contains(root))
            return List.of("field '" + name + "': source must start with one of " + ROOTS + " or be 'const:<text>'");
        if (root.equals("input")) {
            String key = source.substring(Math.min(source.length(), "input.".length()));
            if (!source.startsWith("input.") || !INPUT_KEY.matcher(key).matches())
                return List.of("field '" + name + "': an input source is 'input.<key>' (" + INPUT_KEY.pattern() + ")");
        }
        for (String seg : source.split("\\.", -1))
            if (seg.isEmpty()) return List.of("field '" + name + "': source '" + source + "' has an empty segment");
        return List.of();
    }

    private static String str(Object o) {
        if (o == null) return null;
        String s = String.valueOf(o).trim();
        return s.isEmpty() ? null : s;
    }

    // ── loading ──────────────────────────────────────────────────────────────────────────────────

    /** Every template visible in a Space: built-ins, then the Space's own (which replace a built-in of the same id). */
    public record Catalog(Map<String, ReportTemplate> templates, List<Problem> problems) {
        public ReportTemplate get(String id) {
            return templates.get(id);
        }
    }

    /** Load the catalog. {@code configRoot} may be null (no write root): built-ins only. Never throws on a bad file. */
    public static Catalog load(Path configRoot) {
        Map<String, ReportTemplate> out = new TreeMap<>();
        List<Problem> problems = new ArrayList<>();
        for (String id : BUILT_INS) {
            String origin = "built-in";
            try (InputStream in = ReportTemplate.class.getResourceAsStream(
                    "/META-INF/inspecto/regulatory-templates/" + id + ".toon")) {
                if (in == null) {
                    problems.add(new Problem(id, origin, List.of("the built-in template is missing from the jar")));
                    continue;
                }
                out.put(id, parse(new String(in.readAllBytes(), StandardCharsets.UTF_8), id, origin));
            } catch (Invalid bad) {
                problems.add(new Problem(id, origin, bad.problems()));
            } catch (IOException unreadable) {
                problems.add(new Problem(id, origin, List.of("unreadable: " + unreadable.getMessage())));
            }
        }
        if (configRoot != null) {
            Path dir = configRoot.toAbsolutePath().normalize().resolve(SPACE_DIR);
            if (Files.isDirectory(dir)) {
                try (Stream<Path> s = Files.list(dir)) {
                    for (Path p : s.filter(Files::isRegularFile).sorted().toList()) {
                        String file = p.getFileName().toString();
                        if (!file.endsWith(".toon")) continue;
                        String id = file.substring(0, file.length() - ".toon".length());
                        String origin = "space";
                        try {
                            out.put(id, parse(Files.readString(p, StandardCharsets.UTF_8), id, origin));
                        } catch (Invalid bad) {
                            problems.add(new Problem(id, origin, bad.problems()));
                            out.remove(id);   // a broken Space override must not fall back to the built-in silently
                        } catch (IOException | RuntimeException unreadable) {
                            problems.add(new Problem(id, origin, List.of("unreadable: " + unreadable.getMessage())));
                            out.remove(id);
                        }
                    }
                } catch (IOException unlistable) {
                    problems.add(new Problem(SPACE_DIR, "space", List.of("unreadable: " + unlistable.getMessage())));
                }
            }
        }
        return new Catalog(out, List.copyOf(problems));
    }
}
