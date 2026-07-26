package com.gamma.config.spec;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Projects a {@link ConfigSpec} into a JSON Schema object — the missing half of {@link FieldSpec}'s
 * stated purpose ("LLM grammar-constrained generation"). An agent tool whose payload schema is a bare
 * {@code {"type":"object"}} gives a model no structure to aim at; the same tool handed this projection
 * is constrained by <b>the very spec that will judge its output</b>
 * ({@code ConfigLoader.validate(spec, draft)}), so a schema-honouring generation cannot fail on shape.
 *
 * <h3>Path nesting</h3>
 * A {@link FieldSpec#path()} is dotted ({@code "processing.threads"}) but JSON Schema is a tree, so
 * each segment becomes a nested {@code properties} node. A field is listed in its <b>parent's</b>
 * {@code required} array; because {@code ConfigLoader.validate} treats a required path as required
 * <i>absolutely</i> (not "required if the parent is present"), every ancestor of a required field is
 * marked required in turn — otherwise omitting the whole {@code processing} block would satisfy the
 * schema while failing validation.
 *
 * <h3>Deliberate omissions</h3>
 * <ul>
 *   <li>{@code additionalProperties} is left unset (i.e. permitted). A {@link ConfigSpec} enumerates
 *       the fields it can <i>validate</i>, not every key a kind accepts, so {@code false} here would
 *       reject configs the control plane happily applies.</li>
 *   <li>{@link FieldType#LIST} yields a bare {@code "array"} with no {@code items} — the element type
 *       is not part of the spec model, and guessing one would constrain a model away from valid input.</li>
 *   <li>{@link FieldSpec#visibleWhen()} and {@link FieldSpec#uiHint()} are rendering hints with no
 *       JSON Schema meaning and are not projected.</li>
 * </ul>
 *
 * @since 4.4.0
 */
@com.gamma.api.PublicApi(since = "4.4.0")
public final class ConfigJsonSchema {

    private static final ObjectMapper JSON = new ObjectMapper();

    private ConfigJsonSchema() {}

    /**
     * The JSON Schema for {@code spec} as a nested map (stable key order). A {@code null} spec, or one
     * with no fields, yields the bare {@code {"type":"object"}} — the same thing the callers used to
     * hand-write, so an unspecced kind degrades to today's behaviour rather than failing.
     */
    public static Map<String, Object> of(ConfigSpec spec) {
        Map<String, Object> root = objectNode();
        if (spec == null || spec.fields() == null || spec.fields().isEmpty()) return root;
        for (FieldSpec field : spec.fields()) {
            if (field == null || field.path() == null || field.path().isBlank()) continue;
            put(root, field);
        }
        prune(root);
        return root;
    }

    /** {@link #of(ConfigSpec)} serialised — the form an agent {@code ToolSpec} carries. */
    public static String toJson(ConfigSpec spec) {
        try {
            return JSON.writeValueAsString(of(spec));
        } catch (Exception e) {
            // Total, like JsonAttributes: a projection failure must never break tool registration.
            return "{\"type\":\"object\"}";
        }
    }

    /**
     * The JSON Schema for the {@link ConfigSpecs} kind named {@code type}, or the bare
     * {@code {"type":"object"}} when no spec is registered for it.
     */
    public static Map<String, Object> forType(String type) {
        return of(ConfigSpecs.forType(type));
    }

    // ── projection ──────────────────────────────────────────────────────────────────

    /** Walk {@code field}'s dotted path from {@code root}, creating object nodes, and place its schema. */
    private static void put(Map<String, Object> root, FieldSpec field) {
        String[] segments = field.path().split("\\.");
        // A required path is required absolutely, so the whole ancestor chain is too — starting with
        // the first segment at the root.
        if (field.required()) require(root, segments[0]);
        Map<String, Object> node = root;
        // Every segment but the last is an intermediate object.
        for (int i = 0; i < segments.length - 1; i++) {
            node = child(node, segments[i]);
            if (field.required()) require(node, segments[i + 1]);
        }
        merge(properties(node), segments[segments.length - 1], leafSchema(field));
    }

    /** The child object node under {@code parent.properties[name]}, creating or upgrading it as needed. */
    private static Map<String, Object> child(Map<String, Object> parent, String name) {
        Map<String, Object> props = properties(parent);
        Object existing = props.get(name);
        if (existing instanceof Map<?, ?> m) {
            @SuppressWarnings("unchecked")
            Map<String, Object> node = (Map<String, Object>) m;
            // A path can name both a MAP field and its children ("x" plus "x.y"): keep the
            // field's own metadata but make sure the node can hold properties.
            node.put("type", "object");
            return node;
        }
        Map<String, Object> node = objectNode();
        props.put(name, node);
        return node;
    }

    /** The leaf schema for one field: its JSON type plus whatever constraints the spec declares. */
    private static Map<String, Object> leafSchema(FieldSpec field) {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("type", jsonType(field.type()));
        if (!field.description().isBlank()) s.put("description", field.description());
        else if (!field.label().isBlank()) s.put("description", field.label());
        if (field.type() == FieldType.ENUM && !field.enumValues().isEmpty()) {
            s.put("enum", List.copyOf(field.enumValues()));
        }
        if (field.pattern() != null) s.put("pattern", field.pattern());
        if (field.defaultValue() != null) s.put("default", field.defaultValue());
        return s;
    }

    /**
     * {@link FieldType} → JSON Schema type. {@code FILEPATH}/{@code CRON}/{@code SQL} are STRING
     * refinements on the wire (see {@link FieldType}), so they project as strings — the refinement
     * survives in the field's description, which is what a model actually reads.
     */
    private static String jsonType(FieldType type) {
        return switch (type) {
            case INT, LONG -> "integer";
            case BOOL -> "boolean";
            case MAP -> "object";
            case LIST -> "array";
            case STRING, ENUM, FILEPATH, CRON, SQL -> "string";
        };
    }

    // ── node helpers ────────────────────────────────────────────────────────────────

    private static Map<String, Object> objectNode() {
        Map<String, Object> n = new LinkedHashMap<>();
        n.put("type", "object");
        return n;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> properties(Map<String, Object> node) {
        return (Map<String, Object>) node.computeIfAbsent("properties", k -> new LinkedHashMap<String, Object>());
    }

    @SuppressWarnings("unchecked")
    private static void require(Map<String, Object> node, String name) {
        List<String> req = (List<String>) node.computeIfAbsent("required", k -> new ArrayList<String>());
        if (!req.contains(name)) req.add(name);
    }

    /** Place {@code leaf} at {@code name}, preserving an object node already created by a child path. */
    @SuppressWarnings("unchecked")
    private static void merge(Map<String, Object> props, String name, Map<String, Object> leaf) {
        Object existing = props.get(name);
        if (existing instanceof Map<?, ?> m) {
            Map<String, Object> node = (Map<String, Object>) m;
            // The node already exists because a child path created it — keep "object" and its
            // properties/required, and adopt only the descriptive keys from the leaf.
            leaf.forEach((k, v) -> {
                if (!"type".equals(k)) node.putIfAbsent(k, v);
            });
            return;
        }
        props.put(name, leaf);
    }

    /** Drop the empty {@code properties} maps left on leaf-less object nodes. */
    @SuppressWarnings("unchecked")
    private static void prune(Map<String, Object> node) {
        Object props = node.get("properties");
        if (!(props instanceof Map<?, ?> m)) return;
        if (m.isEmpty()) {
            node.remove("properties");
            return;
        }
        ((Map<String, Object>) m).values().forEach(v -> {
            if (v instanceof Map<?, ?> child) prune((Map<String, Object>) child);
        });
    }
}
