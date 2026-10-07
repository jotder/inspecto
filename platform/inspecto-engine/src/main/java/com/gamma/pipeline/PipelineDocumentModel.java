package com.gamma.pipeline;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static com.gamma.util.Values.strOrEmpty;

/**
 * The <b>Pipeline Document</b> as structure, before any rendering (ELT amendment §5.1, D-8).
 *
 * <p>⛔ <b>Why this exists: so the secret masking happens ONCE.</b> The document renders to Markdown for
 * review and to XLSX for sign-off (D-8's fast-follow). A second renderer projecting the recipe on its own
 * would duplicate the decision of <em>which keys are secret-shaped</em> — and a hand-mirrored decision has
 * drifted four separate times in this repo. A drifted mask here would not be a cosmetic bug: it would put
 * a credential into a workbook someone emails. So every content decision — which rows exist, what a value
 * reads as, and what is masked — is made here, and a renderer only decides shape.
 *
 * <p>The model is deliberately flat and dumb: headings, prose and tables of strings. Everything a renderer
 * needs is already a {@code String}.
 */
public final class PipelineDocumentModel {

    private PipelineDocumentModel() {}

    /** §5.1 "connection (secrets masked)" — any key whose name contains one of these never renders verbatim. */
    static final Set<String> SECRET_KEYS = Set.of(
            "password", "secret", "token", "credential", "passphrase", "private_key", "access_key");

    static final String MASK = "••••";

    /** Verb → section heading, in the pipeline order {@link RecipeConverter} emits. */
    static final Map<String, String> VERB_LABEL = Map.of(
            "collect", "Collect", "parse", "Parse", "map", "Map", "transform", "Transform",
            "dedup", "Dedup", "summarize", "Summarize", "route", "Route", "sink", "Sink");

    /** One table: an optional name (the XLSX sheet title), its headers and its already-stringified rows. */
    public record Table(String name, List<String> headers, List<List<String>> rows) {}

    /** A heading, whatever prose belongs under it, and its tables — in render order. */
    public record Section(int level, String heading, List<String> prose, List<Table> tables) {}

    /** The whole document. */
    public record Doc(String title, List<Section> sections) {}

    // ── value decisions (the part a renderer must never re-make) ─────────────────

    /** A secret-shaped key never renders its value (§5.1). */
    public static String masked(String key, Object v) {
        String k = key.toLowerCase(Locale.ROOT);
        for (String s : SECRET_KEYS) if (k.contains(s)) return MASK;
        return value(v);
    }

    /** Scalars plainly; a list comma-joined; a map as {@code k=v} pairs — always single-line, table-safe. */
    public static String value(Object v) {
        if (v == null) return "";
        if (v instanceof List<?> list) {
            List<String> parts = new ArrayList<>();
            for (Object o : list) parts.add(value(o));
            return String.join(", ", parts);
        }
        if (v instanceof Map<?, ?> m) {
            List<String> parts = new ArrayList<>();
            for (Map.Entry<?, ?> e : m.entrySet()) parts.add(e.getKey() + "=" + value(e.getValue()));
            return String.join(", ", parts);
        }
        return String.valueOf(v);
    }

    public static String label(String key) {
        String known = VERB_LABEL.get(key);
        if (known != null) return known;
        String spaced = key.replace('_', ' ');
        return spaced.isEmpty() ? spaced : Character.toUpperCase(spaced.charAt(0)) + spaced.substring(1);
    }

    /** One line naming what this Step actually does, drawn from the keys that carry its intent. */
    public static String summarize(String verb, Map<String, Object> cfg) {
        return switch (verb) {
            case "collect" -> first(cfg, "connection", "dir", "path", "files");
            case "parse" -> first(cfg, "grammar", "format");
            case "map" -> first(cfg, "schema", "mapping");
            case "transform" -> cfg.containsKey("join") ? "join " + value(cfg.get("join"))
                    : cfg.containsKey("filter") ? "filter " + value(cfg.get("filter")) : "";
            case "dedup" -> cfg.containsKey("key") ? "key " + value(cfg.get("key")) : "";
            case "summarize" -> cfg.containsKey("group_by") ? "by " + value(cfg.get("group_by")) : "";
            case "route" -> branches(cfg).size() + " branch(es)";
            case "sink" -> first(cfg, "database", "table", "format");
            default -> "";
        };
    }

    static String first(Map<String, Object> cfg, String... keys) {
        for (String k : keys) if (cfg.get(k) != null) return value(cfg.get(k));
        return "";
    }

    // ── shape helpers, shared with the renderers ────────────────────────────────

    @SuppressWarnings("unchecked")
    public static Map<String, Object> asMap(Object o) {
        return o instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    }

    @SuppressWarnings("unchecked")
    public static List<Map<String, Object>> steps(Object o) {
        if (!(o instanceof List<?> list)) return List.of();
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object e : list) if (e instanceof Map<?, ?> m) out.add((Map<String, Object>) m);
        return out;
    }

    public static Map<String, Object> branches(Map<String, Object> cfg) {
        return asMap(cfg.get("branches"));
    }

    @SuppressWarnings("unchecked")
    public static List<Map<String, Object>> rows(Map<String, Object> component, String key) {
        if (component == null) return List.of();
        Object v = component.get(key);
        if (!(v instanceof List<?> list)) return List.of();
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object e : list) if (e instanceof Map<?, ?> m) out.add((Map<String, Object>) m);
        return out;
    }

    /** A schema component's fields, wherever the kind puts them ({@code raw.fields} or a bare {@code fields}). */
    public static List<Map<String, Object>> schemaFields(Map<String, Object> schema) {
        if (schema == null) return List.of();
        List<Map<String, Object>> raw = rows(asMap(schema.get("raw")), "fields");
        return raw.isEmpty() ? rows(schema, "fields") : raw;
    }

    // ── the field table (§5.1's "straight join of the Schema and Mapping CSVs") ──

    /**
     * Mapping rules drive the rows (they are the output contract); the Schema field of the same name
     * supplies type/unit/description/classification. A schema field with no mapping rule still appears, so
     * a reviewer sees the whole output shape, not just the mapped part.
     */
    public static Table fieldTable(Map<String, Object> cfg, Map<String, Map<String, Object>> components) {
        List<Map<String, Object>> rules = rows(components.get(strOrEmpty(cfg.get("mapping"))), "rules");
        List<Map<String, Object>> fields = schemaFields(components.get(strOrEmpty(cfg.get("schema"))));
        if (rules.isEmpty() && fields.isEmpty()) return null;

        Map<String, Map<String, Object>> byName = new LinkedHashMap<>();
        for (Map<String, Object> f : fields) byName.put(strOrEmpty(f.get("name")), f);

        List<List<String>> out = new ArrayList<>();
        Set<String> seen = new java.util.LinkedHashSet<>();
        for (Map<String, Object> r : rules) {
            String target = strOrEmpty(r.get("targetColumn"));
            seen.add(target);
            String kind = strOrEmpty(r.get("transformType"));
            out.add(fieldRow(target, strOrEmpty(r.get("sourceExpression")),
                    kind.isEmpty() ? "DIRECT" : kind, byName.getOrDefault(target, Map.of())));
        }
        for (Map<String, Object> f : fields) {
            String n = strOrEmpty(f.get("name"));
            if (!seen.contains(n)) out.add(fieldRow(n, strOrEmpty(f.get("selector")), "", f));
        }
        return new Table("Fields",
                List.of("Target", "Source", "Kind", "Type", "Unit", "Description", "Classification"), out);
    }

    private static List<String> fieldRow(String target, String source, String kind, Map<String, Object> field) {
        return List.of(target, source, kind, strOrEmpty(field.get("type")), strOrEmpty(field.get("unit")),
                strOrEmpty(field.get("description")), strOrEmpty(field.get("classification")));
    }

    /** §5.1's route branch table: name, condition, mode, destination chain. */
    public static Table branchTable(Map<String, Object> cfg) {
        Map<String, Object> branches = branches(cfg);
        if (branches.isEmpty()) return null;
        String mode = strOrEmpty(cfg.get("mode"));
        List<List<String>> out = new ArrayList<>();
        for (Map.Entry<String, Object> e : branches.entrySet()) {
            Map<String, Object> b = asMap(e.getValue());
            List<String> dest = new ArrayList<>();
            for (Map<String, Object> step : steps(b.get("steps"))) {
                for (Map.Entry<String, Object> s : step.entrySet()) {
                    String d = summarize(s.getKey(), asMap(s.getValue()));
                    dest.add(d.isEmpty() ? label(s.getKey()) : label(s.getKey()) + " → " + d);
                }
            }
            out.add(List.of(e.getKey(), value(b.get("when")), mode,
                    Boolean.TRUE.equals(b.get("default")) ? "yes" : "", String.join("; ", dest)));
        }
        return new Table("Branches", List.of("Branch", "Condition", "Mode", "Default", "Destination"), out);
    }

    /** Every remaining key of a Step, so nothing configured goes unreported — masked where it must be. */
    public static Table configTable(Map<String, Object> cfg, Set<String> skip) {
        List<List<String>> out = new ArrayList<>();
        for (Map.Entry<String, Object> e : cfg.entrySet())
            if (!skip.contains(e.getKey())) out.add(List.of(e.getKey(), masked(e.getKey(), e.getValue())));
        return out.isEmpty() ? null : new Table("Settings", List.of("Setting", "Value"), out);
    }
}
