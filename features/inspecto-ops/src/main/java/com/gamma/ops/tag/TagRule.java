package com.gamma.ops.tag;

import com.gamma.query.ConditionTree;
import com.gamma.util.ToonHelper;
import com.gamma.ops.ObjectService;
import com.gamma.workflow.ObjectType;
import com.gamma.ops.OperationalObject;
import com.gamma.util.Values;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * A <strong>Tag Rule</strong> (GLOSSARY §9) — a saved search that applies a {@link Tag}, the Gmail-filter
 * metaphor: it auto-tags newly created objects that match its {@link Filter} (hooked into
 * {@link ObjectService#open}) and can be applied in bulk to every existing match
 * ({@code POST /tags/rules/{name}/apply}).
 *
 * <p>Authored as a {@code *_tagrule.toon} (a {@code tag_rule { … }} block) loaded at bootstrap, or saved at
 * runtime via {@code POST /tags/rules} (which persists the same file under the write root). A rule must set
 * at least one criterion — an unconstrained rule would tag everything.
 *
 * @since 4.0.0
 */
@com.gamma.api.PublicApi(since = "4.0.0")
public record TagRule(String name, String tag, Filter filter, long createdAt, Map<String, Object> extra) {

    /** The keys a {@code tag_rule { … }} block models (the filter fields may be nested or flattened); any other key rides in {@link #extra}. */
    public static final Set<String> MODELLED = Set.of("name", "tag", "filter", "createdAt",
            "type", "q", "status", "priority", "severity", "category");

    public TagRule(String name, String tag, Filter filter, long createdAt) {
        this(name, tag, filter, createdAt, Map.of());
    }

    public TagRule {
        extra = extra == null || extra.isEmpty() ? Map.of() : java.util.Collections.unmodifiableMap(new LinkedHashMap<>(extra));
        if (name == null || name.isBlank()) throw new IllegalArgumentException("tag rule name is required");
        name = name.trim();
        if (tag == null || tag.isBlank()) throw new IllegalArgumentException("tag rule needs a 'tag' to apply");
        tag = tag.trim();
        if (tag.contains(",")) throw new IllegalArgumentException("tag names may not contain commas");
        if (filter == null || !filter.hasCriterion())
            throw new IllegalArgumentException("a tag rule needs at least one criterion (type/q/status/priority/severity/category)");
    }

    /** Whether this rule's filter matches {@code o}. */
    public boolean matches(OperationalObject o) {
        return filter.matches(o);
    }

    /** JSON-ready view (stable key order) — backs the {@code /tags/rules} API. */
    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("tag", tag);
        m.put("filter", filter.toMap());
        m.put("createdAt", createdAt);
        extra.forEach(m::putIfAbsent);   // MODULE-REORG-P4-2: author-owned x- annotations ride through
        return m;
    }

    /** Load a {@code *_tagrule.toon} (a {@code tag_rule { … }} block). */
    @SuppressWarnings("unchecked")
    public static TagRule load(Path path) throws IOException {
        Map<String, Object> root = ToonHelper.load(path.toString());
        Object r = root.get("tag_rule");
        if (!(r instanceof Map)) throw new IllegalArgumentException(path + " has no 'tag_rule' block");
        return fromMap((Map<String, Object>) r);
    }

    /** Parse + validate from a decoded {@code tag_rule { … }} map (filter fields nested or flattened). */
    @SuppressWarnings("unchecked")
    public static TagRule fromMap(Map<String, Object> m) {
        if (m == null) throw new IllegalArgumentException("missing 'tag_rule' block");
        Map<String, Object> f = m.get("filter") instanceof Map ? (Map<String, Object>) m.get("filter") : m;
        Filter filter = new Filter(Values.trimToNull(f.get("type")), Values.trimToNull(f.get("q")), Values.trimToNull(f.get("status")),
                Values.trimToNull(f.get("priority")), Values.trimToNull(f.get("severity")), Values.trimToNull(f.get("category")));
        long at = 0;
        Object created = m.get("createdAt");
        if (created != null) {
            try {
                at = Long.parseLong(created.toString().trim());
            } catch (NumberFormatException ignored) {
                // informational only
            }
        }
        return new TagRule(Values.trimToNull(m.get("name")), Values.trimToNull(m.get("tag")), filter, at > 0 ? at : System.currentTimeMillis(),
                Extras.of(m, MODELLED));
    }

    /**
     * The rule's criteria — every set field must match: {@code type}/{@code status}/{@code priority}/
     * {@code severity} exact (case-insensitive), {@code category} a case-insensitive path prefix (e.g.
     * {@code "Pipeline"} or {@code "Pipeline / Ingest"}), {@code q} a case-insensitive substring of title +
     * description. Incident statuses are compared on the mail lifecycle (GLOSSARY §9), tolerating the legacy
     * pre-rename names so config-overridden deployments keep matching. The flat fields are authoring sugar for
     * {@link #tree()}, which {@link #matches} evaluates through the Condition Language.
     */
    public record Filter(String type, String q, String status, String priority, String severity, String category) {

        /** The six criteria a filter block may hold. */
        public static final java.util.Set<String> KEYS = java.util.Set.of("type", "q", "status", "priority", "severity", "category");

        /** Validates {@code type} eagerly so a bad value rejects at authoring time, not at match time. */
        public Filter {
            if (type != null && !type.isBlank()) ObjectType.of(type.trim());
        }

        /** At least one criterion is set (an empty filter would match everything). */
        public boolean hasCriterion() {
            return notBlank(type) || notBlank(q) || notBlank(status) || notBlank(priority)
                    || notBlank(severity) || notBlank(category);
        }

        /** Whether {@code o} satisfies every set criterion — evaluated as {@link #tree()} over {@link #context}. */
        public boolean matches(OperationalObject o) {
            return ConditionTree.matched(tree(), List.of(context(o))) == 1;
        }

        /**
         * The criteria as a condition tree (Decision Kernel step 3) — the flat fields stay the authoring form,
         * this is what they mean. Evaluated over {@link #context}. {@code status} is the one that needs shape:
         * an Incident's cell is already folded onto the mail lifecycle, so its operand is folded too, while
         * every other type compares exactly — hence the type-guarded pair.
         */
        public Map<String, Object> tree() {
            List<Object> all = new ArrayList<>();
            if (notBlank(type)) all.add(leaf("type", "=", ObjectType.of(type.trim()).name(), false));
            if (notBlank(status)) {
                String exact = status.trim().toUpperCase(Locale.ROOT);
                all.add(group("OR",
                        group("AND", leaf("type", "=", ObjectType.INCIDENT.name(), false),
                                leaf("status", "=", foldIncident(exact), false)),
                        group("AND", leaf("type", "!=", ObjectType.INCIDENT.name(), false),
                                leaf("status", "=", exact, false))));
            }
            if (notBlank(priority)) all.add(leaf("priority", "=", priority.trim(), true));
            if (notBlank(severity)) all.add(leaf("severity", "=", severity.trim(), true));
            if (notBlank(category)) all.add(leaf("category", "startsWith", category.trim(), true));
            if (notBlank(q)) all.add(leaf("text", "contains", q.trim(), true));
            return group("AND", all.toArray());
        }

        /**
         * The row the tree is evaluated over: {@code type}, {@code status} (an Incident's folded onto the mail
         * lifecycle, GLOSSARY §9, tolerating the legacy pre-rename names so config-overridden deployments keep
         * matching), {@code priority}, {@code severity}, {@code category} (the attribute) and {@code text}
         * (title + description) — absent values are empty strings.
         */
        public static Map<String, Object> context(OperationalObject o) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("type", o.typeName());
            String status = o.status() == null ? "" : o.status().trim().toUpperCase(Locale.ROOT);
            row.put("status", o.objectType() == ObjectType.INCIDENT ? foldIncident(status) : status);
            row.put("priority", nullToEmpty(o.priority()));
            row.put("severity", nullToEmpty(o.severity()));
            row.put("category", nullToEmpty(o.attributes().get("category")));
            row.put("text", o.title() + " " + o.description());
            return row;
        }

        private static Map<String, Object> group(String op, Object... items) {
            Map<String, Object> g = new LinkedHashMap<>();
            g.put("kind", "group");
            g.put("op", op);
            g.put("items", List.of(items));
            return g;
        }

        private static Map<String, Object> leaf(String field, String operator, String value, boolean ignoreCase) {
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("kind", "condition");
            c.put("field", field);
            c.put("operator", operator);
            c.put("value", value);
            if (ignoreCase) c.put("ignoreCase", true);
            return c;
        }

        /** JSON-ready view of the set criteria only. */
        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            if (notBlank(type)) m.put("type", type);
            if (notBlank(q)) m.put("q", q);
            if (notBlank(status)) m.put("status", status);
            if (notBlank(priority)) m.put("priority", priority);
            if (notBlank(severity)) m.put("severity", severity);
            if (notBlank(category)) m.put("category", category);
            return m;
        }

        /** Fold the legacy incident lifecycle names onto the mail lifecycle for comparison. */
        private static String foldIncident(String upper) {
            return switch (upper) {
                case "OPEN" -> "IDENTIFIED";
                case "ASSIGNED", "IN_PROGRESS" -> "DIAGNOSING";
                case "CLOSED" -> "ARCHIVED";
                default -> upper;
            };
        }

        private static boolean notBlank(String s) {
            return s != null && !s.isBlank();
        }

        private static String nullToEmpty(String s) {
            return s == null ? "" : s.trim();
        }
    }
}
