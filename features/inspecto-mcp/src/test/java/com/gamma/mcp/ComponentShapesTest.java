package com.gamma.mcp;

import com.fasterxml.jackson.core.util.DefaultIndenter;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.alert.AlertRule;
import com.gamma.config.io.ConfigLoader;
import com.gamma.config.spec.AcceptedConfigKeys;
import com.gamma.config.spec.ConfigSpec;
import com.gamma.config.spec.ConfigSpecs;
import com.gamma.config.spec.FieldSpec;
import com.gamma.config.spec.Finding;
import com.gamma.config.spec.Severity;
import com.gamma.control.AuthorKeys;
import com.gamma.control.ShapeProbe;
import com.gamma.expectation.Expectation;
import com.gamma.job.JobConfig;
import com.gamma.notify.ChannelConfig;
import com.gamma.notify.NotificationRule;
import com.gamma.pipeline.PipelineCodec;
import com.gamma.pipeline.PipelineEdge;
import com.gamma.pipeline.PipelineGraph;
import com.gamma.pipeline.PipelineNode;
import com.gamma.query.KpiDefinition;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.RecordComponent;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * The component shape reference (COMPONENT-REFERENCE-GENERATED-1): for every {@code propose_change} kind
 * ({@link McpTools#CHANGE_ROUTES}), the keys its content may carry, each one's type, whether it is required, its default,
 * its allowed values and an example — DERIVED from the code, never written by hand, into
 * {@code docs/api/component-shapes/<kind>.json} (one file per kind, so an MCP Resource {@code inspecto://shapes/{kind}} can
 * serve one; AK-2 widening plan W5). {@code node tools/render-component-shapes.mjs} renders the page people read.
 *
 * <p>How each column is derived, so it cannot drift from what a save accepts:
 * <ul>
 *   <li><b>keys</b> — the kind's own key census in code ({@code AlertRule.MODELLED}, {@code NotificationRule.MODELLED},
 *       {@code ChannelConfig.MODELLED}, {@code KpiDefinition.KEYS}, the component census of {@code ComponentRoutes}), its
 *       {@link ConfigSpecs} fields, or the components of the record the route parses into;</li>
 *   <li><b>type, description, allowed values, pattern</b> — the {@link FieldSpec} when the kind has a spec, else the
 *       record component's Java type, else the JSON type of the example or the default;</li>
 *   <li><b>required</b> — the real parser refuses the example without the key (a spec flag the save does not enforce is {@code specRequired});</li>
 *   <li><b>default</b> — the spec's default, or: what the real parser fills in when the key is absent;</li>
 *   <li><b>unknown keys</b> — the example plus one made-up key, through the real parser: refused, kept or ignored;</li>
 *   <li><b>example</b> — a minimal body the real parser accepts (this test fails if it stops accepting it).</li>
 * </ul>
 * The parsers are the save door's content checks without the Space (no Dataset, Pipeline or Job lookups), so an example
 * that names {@code orders} is shaped right, not proved to exist.
 *
 * <p>Regenerate after a change to any of those sources:
 * {@code mvn -o test -pl features/inspecto-mcp -Dtest=ComponentShapesTest -Dshapes.write=true}, then
 * {@code node tools/render-component-shapes.mjs --write}.
 */
class ComponentShapesTest {

    static final Path DIR = Path.of("../../docs/api/component-shapes");
    private static final String UNKNOWN_PROBE = "zzUnknownKeyProbe";
    private static final String REGENERATE = "mvn -o test -pl features/inspecto-mcp -Dtest=ComponentShapesTest -Dshapes.write=true"
            + " && node tools/render-component-shapes.mjs --write";

    // ── the derivation ───────────────────────────────────────────────────────────────────────────

    /** A list-of-objects key whose element keys come from a record ({@code nodes[]} of a Pipeline graph). */
    record Element(String key, Class<? extends Record> record) {}

    /**
     * One content shape: what a kind's create/update (or one Entity List operation) sends.
     *
     * @param source   where its keys come from, named for the reader
     * @param keys     the accepted top-level keys
     * @param parse    the real parser: returns the parsed form (a Map or a record), throws on a refusal
     * @param example  a minimal body {@code parse} accepts
     * @param spec     the kind's {@link ConfigSpec} fields, or empty
     * @param prefix   the spec paths' prefix the body leaves out ({@code job.}), or {@code ""}
     * @param record   the record the body parses into (types), or {@code null}
     * @param values   allowed values the code lists outside a spec (key → values)
     * @param elements list-of-objects keys whose element keys a record names
     * @param notes    what the derivation cannot see, for the reader
     * @param types    a key's type where neither spec nor record says it right (an authored form the parser converts)
     * @param server   keys the server stamps or the store adds: listed, never required, no default
     */
    record Body(String source, Set<String> keys, Function<Map<String, Object>, Object> parse, Map<String, Object> example,
                List<FieldSpec> spec, String prefix, Class<? extends Record> record, Map<String, Collection<String>> values,
                List<Element> elements, List<String> notes, Map<String, String> types, Set<String> server) {

        Body(String source, Set<String> keys, Function<Map<String, Object>, Object> parse, Map<String, Object> example,
             List<FieldSpec> spec, String prefix, Class<? extends Record> record, Map<String, Collection<String>> values,
             List<Element> elements, List<String> notes) {
            this(source, keys, parse, example, spec, prefix, record, values, elements, notes, Map.of(), Set.of());
        }

        Body with(Map<String, String> types, Set<String> server) {
            return new Body(source, keys, parse, example, spec, prefix, record, values, elements, notes, types, server);
        }
    }

    static Map<String, Object> derive(String kind, String op, Body b) {
        Object parsed;
        try {
            parsed = b.parse().apply(copy(b.example()));
        } catch (RuntimeException e) {
            throw new AssertionError(kind + " " + op + ": the shape reference's own example is refused: " + e.getMessage(), e);
        }
        Map<String, FieldSpec> spec = new LinkedHashMap<>();
        for (FieldSpec f : b.spec())
            if (f.path().startsWith(b.prefix())) spec.put(f.path().substring(b.prefix().length()), f);

        Set<String> top = new TreeSet<>(b.keys());
        for (String path : spec.keySet()) if (!path.contains(".")) top.add(path);   // a spec field is an accepted key
        Map<String, Map<String, Object>> fields = new TreeMap<>();
        for (String key : top) fields.put(key, field(key, b, parsed, spec.get(key)));
        // a spec's nested paths (reference.load, workingSet.mode) are listed under their parent
        for (var e : spec.entrySet())
            if (e.getKey().contains(".") && top.contains(e.getKey().substring(0, e.getKey().indexOf('.'))))
                fields.put(e.getKey(), field(e.getKey(), b, null, e.getValue()));
        for (Element el : b.elements()) {
            for (RecordComponent rc : el.record().getRecordComponents()) {
                String path = el.key() + "[]." + rc.getName();
                Map<String, Object> f = new LinkedHashMap<>();
                f.put("path", path);
                f.put("type", jsonType(rc.getType()));
                boolean req = elementRequired(b, el.key(), rc.getName());
                f.put("required", req);
                if (req) f.put("requiredBy", "parser");
                fields.put(path, f);
            }
        }

        Map<String, Object> shape = new LinkedHashMap<>();
        shape.put("source", b.source());
        shape.put("unknownKeys", unknownKeys(b));
        shape.put("extensionPrefix", AcceptedConfigKeys.EXTENSION_PREFIX);
        shape.put("fields", new ArrayList<>(fields.values()));
        shape.put("example", b.example());
        if (!b.notes().isEmpty()) shape.put("notes", b.notes());
        return shape;
    }

    private static Map<String, Object> field(String key, Body b, Object parsed, FieldSpec f) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("path", key);
        boolean top = parsed != null;
        boolean server = b.server().contains(key);
        boolean bySpec = f != null && f.required();
        // top-level keys: required = the real parser refuses the example without it; a nested spec path has no probe
        boolean required = !server && (top ? requiredByParser(key, b) : bySpec);
        // a required key has no default, whatever the spec declares (Expectation's spec defaults `kind`; its parser requires it)
        Object def = required || server ? null
                : f != null && f.defaultValue() != null ? f.defaultValue() : top ? derivedDefault(key, b, parsed) : null;
        out.put("type", b.types().containsKey(key) ? b.types().get(key) : f != null ? specType(f) : type(key, b, def));
        out.put("required", required);
        if (required) out.put("requiredBy", bySpec ? "spec" : "parser");
        else if (bySpec && !server) out.put("specRequired", true);   // declared required, not refused on save
        if (server) out.put("setBy", "server");
        if (def != null) out.put("default", sorted(def));
        List<String> values = f != null && !f.enumValues().isEmpty() ? f.enumValues()
                : b.values().containsKey(key) ? new ArrayList<>(new TreeSet<>(b.values().get(key))) : List.of();
        if (!values.isEmpty()) out.put("values", values);
        if (f != null && f.pattern() != null) out.put("pattern", f.pattern());
        if (f != null && !f.description().isBlank()) out.put("description", f.description());
        return out;
    }

    /** {@code true} when the parser refuses the example without {@code key}; {@code false} when the example lacks it. */
    private static boolean requiredByParser(String key, Body b) {
        if (!b.example().containsKey(key)) return false;
        Map<String, Object> without = copy(b.example());
        without.remove(key);
        try {
            b.parse().apply(without);
            return false;
        } catch (RuntimeException refused) {
            return true;
        }
    }

    @SuppressWarnings("unchecked")
    private static boolean elementRequired(Body b, String listKey, String key) {
        Map<String, Object> probe = copy(b.example());
        List<Object> list = new ArrayList<>((List<Object>) probe.get(listKey));
        Map<String, Object> first = new LinkedHashMap<>((Map<String, Object>) list.get(0));
        if (!first.containsKey(key)) return false;
        first.remove(key);
        list.set(0, first);
        probe.put(listKey, list);
        try {
            b.parse().apply(probe);
            return false;
        } catch (RuntimeException refused) {
            return true;
        }
    }

    /** What the parser holds for {@code key} when the body leaves it out; {@code null} when nothing worth naming. */
    private static Object derivedDefault(String key, Body b, Object parsed) {
        Object from = parsed;
        if (b.example().containsKey(key)) {
            Map<String, Object> without = copy(b.example());
            without.remove(key);
            try {
                from = b.parse().apply(without);
            } catch (RuntimeException refused) {
                return null;
            }
        }
        Object v = read(from, key);
        if (v == null || v instanceof String s && s.isEmpty() || v instanceof Collection<?> c && c.isEmpty()
                || v instanceof Map<?, ?> m && m.isEmpty()) return null;
        if (v instanceof Enum<?> e) return e.name().toLowerCase(java.util.Locale.ROOT);
        if (v instanceof Record || !(v instanceof String || v instanceof Number || v instanceof Boolean
                || v instanceof Map<?, ?> || v instanceof List<?>)) return null;
        return v;
    }

    private static Object read(Object parsed, String key) {
        if (parsed instanceof Map<?, ?> m) return m.get(key);
        if (parsed instanceof Record r)
            for (RecordComponent rc : r.getClass().getRecordComponents())
                if (rc.getName().equals(key))
                    try {
                        return rc.getAccessor().invoke(r);
                    } catch (ReflectiveOperationException e) {
                        throw new IllegalStateException(e);
                    }
        return null;
    }

    private static String type(String key, Body b, Object def) {
        if (b.record() != null)
            for (RecordComponent rc : b.record().getRecordComponents())
                if (rc.getName().equals(camel(key)) && rc.getType() != Object.class) return jsonType(rc.getType());
        Object v = b.example().containsKey(key) ? b.example().get(key) : def;
        return v == null ? "any" : jsonType(v.getClass());
    }

    /** {@code on_pipeline} → {@code onPipeline}: a TOON key's record component (a Job's keys are snake_case). */
    private static String camel(String key) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < key.length(); i++)
            out.append(key.charAt(i) == '_' && i + 1 < key.length() ? Character.toUpperCase(key.charAt(++i)) : key.charAt(i));
        return out.toString();
    }

    private static String jsonType(Class<?> c) {
        if (c == String.class || c.isEnum()) return "string";
        if (c == boolean.class || c == Boolean.class) return "boolean";
        if (c == int.class || c == long.class || c == Integer.class || c == Long.class) return "integer";
        if (c == double.class || c == float.class || Number.class.isAssignableFrom(c)) return "number";
        if (List.class.isAssignableFrom(c) || Collection.class.isAssignableFrom(c)) return "array";
        if (Map.class.isAssignableFrom(c) || Record.class.isAssignableFrom(c)) return "object";
        return "any";
    }

    private static String specType(FieldSpec f) {
        return switch (f.type()) {
            case INT, LONG -> "integer";
            case BOOL -> "boolean";
            case MAP -> "object";
            case LIST -> "array";
            case CRON -> "string (cron)";
            case SQL -> "string (SQL)";
            case FILEPATH -> "string (path)";
            default -> "string";
        };
    }

    /** {@code refused}, {@code kept} or {@code ignored}: the example plus one made-up key, through the real parser. */
    private static String unknownKeys(Body b) {
        Map<String, Object> probe = copy(b.example());
        probe.put(UNKNOWN_PROBE, "x");
        Object parsed;
        try {
            parsed = b.parse().apply(probe);
        } catch (RuntimeException refused) {
            return "refused";
        }
        return read(parsed, UNKNOWN_PROBE) != null || parsed instanceof Map<?, ?> m && m.toString().contains(UNKNOWN_PROBE)
                ? "kept" : "ignored";
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> copy(Map<String, Object> m) {
        Map<String, Object> out = new LinkedHashMap<>();
        m.forEach((k, v) -> out.put(k, v instanceof Map<?, ?> mm ? copy((Map<String, Object>) mm)
                : v instanceof List<?> l ? new ArrayList<>(l) : v));
        return out;
    }

    // ── the kinds ────────────────────────────────────────────────────────────────────────────────

    private static Map<String, Object> m(Object... kv) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) out.put((String) kv[i], kv[i + 1]);
        return out;
    }

    private static List<String> lower(Enum<?>[] values) {
        return java.util.Arrays.stream(values).map(v -> v.name().toLowerCase(java.util.Locale.ROOT)).toList();
    }

    private static Set<String> components(Class<? extends Record> r) {
        Set<String> out = new LinkedHashSet<>();
        for (RecordComponent rc : r.getRecordComponents()) out.add(rc.getName());
        return out;
    }

    /** The save door's refusal for an IllegalArgumentException-free parser that returns findings (the Job spec gate). */
    private static void refuseSpecErrors(ConfigSpec spec, Map<String, Object> raw) {
        List<Finding> errors = ConfigLoader.filesystem().validate(spec, raw).stream()
                .filter(f -> f.severity() == Severity.ERROR).toList();
        if (!errors.isEmpty()) throw new IllegalArgumentException(errors.get(0).message());
    }

    /** kind → operation → content shape name (or {@code null}: the operation takes no content). */
    static Map<String, Map<String, Body>> bodies() {
        Map<String, Map<String, Body>> out = new TreeMap<>();

        Body pipeline = new Body("PipelineCodec.fromMap (the graph the Pipeline editor saves; the URL's id is its name)",
                without(components(PipelineGraph.class), "name"),
                body -> { Map<String, Object> named = new LinkedHashMap<>(body); named.putIfAbsent("name", "orders");
                          return PipelineCodec.fromMap(named); },
                m("active", true,
                  "nodes", List.of(m("id", "read", "type", "source.file", "config", m("collector", "orders_inbox")),
                                   m("id", "write", "type", "sink.parquet", "config", m("dataset", "orders"))),
                  "edges", List.of(m("from", "read", "to", "write"))),
                List.of(), "", PipelineGraph.class, Map.of(),
                List.of(new Element("nodes", PipelineNode.class), new Element("edges", PipelineEdge.class)),
                List.of("`name` comes from the URL (`PUT /pipelines/{id}/graph`); a name in the body is overwritten.",
                        "`nodes[].config` is the node type's own keys: read them from `GET /pipelines/node-types` "
                                + "(or preview with `preview_config`); the save then lowers the graph and runs the SaveGate.",
                        "`edges[].rel` defaults to `data`."));
        out.put("pipeline", ops(pipeline, "create", "update"));

        Body expectation = new Body("ConfigSpecs.expectation() + Expectation.fromMap (the component key census refuses any other key)",
                ShapeProbe.censusKeys("expectation"),
                body -> { ShapeProbe.component("expectation", "orders-id-present", body); return Expectation.fromMap(body).toMap(); },
                m("name", "orders-id-present", "kind", "non_null", "target", "orders", "column", "order_id"),
                ConfigSpecs.expectation().fields(), "", Expectation.class,
                Map.of("kind", Expectation.KINDS, "targetType", Expectation.TARGET_TYPES, "severity", Expectation.SEVERITIES),
                List.of(), List.of("`range` needs `min` or `max`; `regex` a `pattern`; `referential` `refDataset` and "
                        + "`refColumn`; `condition` a `when` tree; `baseline` its baseline keys."))
                .with(Map.of(), Set.of("lastResult", "createdAt", "updatedAt", "owner"));
        out.put("expectation", ops(expectation, "create", "update"));

        Body alertRule = new Body("AlertRule.MODELLED + AlertRoutes.parseContent (the save also checks the Dataset and the edition)",
                AlertRule.MODELLED,
                ShapeProbe::alertRule,
                m("name", "orders-error-rate", "metric", "error_rate", "comparator", "gt", "threshold", 0.05,
                  "window", "1h", "onPipeline", "orders"),
                ConfigSpecs.alert().fields(), "alert.", null,
                Map.of("severity", AlertRule.SEVERITIES, "comparator", AlertRule.COMPARATORS, "metric", AlertRule.METRICS),
                List.of(), List.of("Three shapes: a ledger rule (`metric`, `threshold`, `window`, optionally `onPipeline`), "
                        + "a Dataset measure rule (`dataset`, `measure`, `threshold`) and a freshness rule (`dataset`, "
                        + "`maximumAge`); `required` below is the example's shape (a ledger rule).",
                        "`owner` is stamped by the server on create (the signed-in Subject).")).with(Map.of(), Set.of("owner"));
        out.put("alert-rule", ops(alertRule, "create", "update"));

        Body job = new Body("ConfigSpecs.job() + JobConfig.fromMap (any other key is a parameter of the Job type)",
                new TreeSet<>(JobConfig.fromMap(Map.of("job", m("name", "n", "type", "report", "cron", "0 2 * * *",
                        "on_pipeline", "p", "on_signal", "s", "when", "true", "catch_up", true, "args", m("a", "b"),
                        "bind", m("a", "b")))).toMap().keySet()),
                body -> { ShapeProbe.jobNested(body); Map<String, Object> raw = Map.of("job", body); refuseSpecErrors(ConfigSpecs.job(), raw);
                          return JobConfig.fromMap(raw).toMap(); },
                m("name", "orders-nightly-report", "type", "report", "cron", "0 2 * * *"),
                ConfigSpecs.job().fields(), "job.", JobConfig.class, Map.of(), List.of(),
                List.of("`type` is an open registry: `GET /jobs/types` lists the installed ones and each type's own "
                        + "parameters, which sit beside these keys as single values: an object-valued key is refused (422), except "
                        + "`args`, `bind` and the `incremental` block.",
                        "The author keys (" + String.join(", ", new TreeSet<>(JobConfig.AUTHOR_KEYS))
                                + ") are stamped by the server; a value in the body is discarded."));
        out.put("job", ops(job, "create", "update"));

        Body widget = new Body("ConfigSpecs.widget() (the component key census refuses any other key)",
                ShapeProbe.censusKeys("widget"),
                body -> ShapeProbe.component("widget", "orders-by-region", body),
                m("name", "orders-by-region", "vizType", "bar", "datasetId", "orders",
                  "controls", m("x", "region", "y", "count")),
                ConfigSpecs.widget().fields(), "", null, Map.of(), List.of(),
                List.of("`vizType` names a Visualization Type; `controls` maps its fields to columns."))
                .with(Map.of(), Set.of("owner"));
        out.put("widget", ops(widget, "create", "update"));

        Body dashboard = new Body("ConfigSpecs.dashboard() (the component key census refuses any other key)",
                ShapeProbe.censusKeys("dashboard"),
                body -> ShapeProbe.component("dashboard", "orders-overview", body),
                m("name", "orders-overview", "tiles", List.of(m("widgetId", "orders-by-region"))),
                ConfigSpecs.dashboard().fields(), "", null, Map.of(), List.of(), List.of()).with(Map.of(), Set.of("owner"));
        out.put("dashboard", ops(dashboard, "create", "update"));

        Body decisionRule = new Body("DecisionRoutes.normalize (other keys are stored as sent)",
                new TreeSet<>(List.of("name", "description", "targetType", "target", "when", "consequences", "priority",
                        "enabled")),
                body -> { if (body.get("name") == null) throw new IllegalArgumentException("decision rule requires a 'name'");
                          return ShapeProbe.decisionRule(body); },
                m("name", "drop-test-orders", "target", "orders",
                  "when", m("kind", "group", "op", "AND", "items",
                          List.of(m("kind", "condition", "field", "customer", "operator", "=", "value", "TEST"))),
                  "consequences", List.of(m("type", "drop"))),
                List.of(), "", null, Map.of("targetType", List.of("pipeline", "job")), List.of(),
                List.of("`consequences[]` items name a consequence `type` with its parameters: `GET /decision-rules/consequences`.",
                        "`when` is a condition tree whose root is a group (`kind: group`, `op`, `items`)."));
        out.put("decision-rule", ops(decisionRule, "create", "update"));

        Body notificationRule = new Body("NotificationRule.MODELLED + NotificationRule.fromMap",
                NotificationRule.MODELLED,
                body -> { AuthorKeys.requireModelled("a notification rule", body, NotificationRule.MODELLED);
                          return NotificationRule.fromMap(body).toMap(); },
                m("id", "batch-failures", "eventType", "BATCH_FAILED", "category", "pipeline"),
                List.of(), "", NotificationRule.class,
                Map.of("minLevel", java.util.Arrays.stream(com.gamma.audit.EventLevel.values()).map(Enum::name).toList()),
                List.of(), List.of("`eventType` names an Event type (the `type` of an Event in `GET /events`); `category` is also the "
                        + "notification preference key that gates delivery (the built-in rules use `pipeline`).")).with(Map.of(), Set.of("name"));
        out.put("notification-rule", ops(notificationRule, "create", "update"));

        Body channel = new Body("ChannelConfig.MODELLED + ChannelConfig.fromMap",
                ChannelConfig.MODELLED,
                body -> { AuthorKeys.requireModelled("a notification channel", body, ChannelConfig.MODELLED);
                          return ChannelConfig.fromMap(body, 0L).toMap(); },
                m("id", "ops-email", "kind", "email", "target", "ops@example.com"),
                List.of(), "", ChannelConfig.class, Map.of(), List.of(),
                List.of("An update merges the body onto the stored Channel: only the keys that change are needed; `id` comes "
                        + "from the URL and `createdAt` is kept. A Channel carries no secret."))
                .with(Map.of(), Set.of("name", "createdAt"));
        out.put("notification-channel", ops(channel, "create", "update"));

        Set<String> kpiKeys = new TreeSet<>(KpiDefinition.KEYS);
        kpiKeys.add("id");
        Body kpi = new Body("KpiDefinition.KEYS + KpiDefinition.fromMap (plus `id`, the KPI's own id)",
                kpiKeys,
                body -> { Map<String, Object> c = new LinkedHashMap<>(body); Object id = c.remove("id");
                          return KpiDefinition.fromMap(id == null ? "" : String.valueOf(id), c); },
                m("dataset", "orders", "measure", "count", "timeField", "order_date", "grain", "day"),
                List.of(), "", KpiDefinition.class,
                Map.of("grain", lower(KpiDefinition.Grain.values()), "comparison", lower(KpiDefinition.Comparison.values()),
                        "direction", lower(KpiDefinition.Direction.values())), List.of(),
                List.of("`id` is the KPI's id (default: the Requirement's id). `title`, `target`, `unit` and `direction` "
                        + "default to the delivered Requirement's own; the body wins.",
                        "`measure` is `count` or `agg(field)`; the route also checks the Dataset has the field."))
                .with(Map.of("measure", "string"), Set.of("owner", "requirement"));
        out.put("requirement-kpi", ops(kpi, "create"));

        out.put("pipeline-restore", ops(null, "restore"));

        // The Entity List edits read their keys inline (no census): declared here, and entityListKeysAreReadByTheRoute
        // holds each one to a read in EntityListRoutes. Unknown keys are ignored.
        Map<String, Body> el = new LinkedHashMap<>();
        el.put("members", declared("add, remove: values to add or take off; addRanges, removeRanges: value ranges; "
                        + "expiresAt: an ISO-8601 instant after now (absent = permanent)",
                m("add", List.of("acct-1001"), "reason", "confirmed mule account"),
                Map.of("add", "array", "remove", "array", "addRanges", "array", "removeRanges", "array",
                        "expiresAt", "string", "reason", "string"), Map.of()));
        el.put("retire", declared("a retired list takes no more edits (409)",
                m("reason", "replaced by the 2027 list"), Map.of("reason", "string"), Map.of()));
        el.put("attributes", declared("exclusion lists only; name at least one of ownerKind, autoApply, visibility",
                m("autoApply", true, "reason", "apply to every new Case"),
                Map.of("ownerKind", "string", "autoApply", "boolean", "visibility", "string", "reason", "string"),
                Map.of("ownerKind", entityListConstant("OWNER_KINDS"), "visibility", entityListConstant("VISIBILITIES"))));
        out.put("entity-list", el);
        return out;
    }

    /** A private value list of {@code EntityListRoutes} (read reflectively: the route keeps it private, the reference reads it). */
    @SuppressWarnings("unchecked")
    private static Collection<String> entityListConstant(String field) {
        try {
            var f = com.gamma.entitylist.EntityListRoutes.class.getDeclaredField(field);
            f.setAccessible(true);
            return (Collection<String>) f.get(null);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("EntityListRoutes." + field + " is gone: update ComponentShapesTest", e);
        }
    }

    /** The declared shape of one Entity List edit: {@code reason} required (the edit records it on the list's fact). */
    private static Body declared(String note, Map<String, Object> example, Map<String, String> types,
                                 Map<String, Collection<String>> values) {
        return new Body("EntityListRoutes (keys read inline; declared in the generator and held to the route's source)",
                new TreeSet<>(types.keySet()),
                body -> { if (!(body.get("reason") instanceof String r) || r.isBlank())
                              throw new IllegalArgumentException("'reason' is required");
                          Map<String, Object> read = new LinkedHashMap<>(body);   // the route reads its keys, nothing else
                          read.keySet().retainAll(types.keySet());
                          return read; },
                example, List.of(), "", null, values, List.of(), List.of(note, "The MCP tool's `reason` fills `reason` when "
                        + "the content has none.")).with(types, Set.of());
    }

    private static Set<String> without(Set<String> keys, String drop) {
        Set<String> out = new LinkedHashSet<>(keys);
        out.remove(drop);
        return out;
    }

    private static Map<String, Body> ops(Body content, String... operations) {
        Map<String, Body> out = new LinkedHashMap<>();
        for (String op : operations) out.put(op, content);
        return out;
    }

    // ── the documents ────────────────────────────────────────────────────────────────────────────

    static Map<String, Map<String, Object>> documents() {
        Map<String, Map<String, Object>> docs = new TreeMap<>();
        for (var kind : bodies().entrySet()) {
            Map<String, Object> doc = new LinkedHashMap<>();
            doc.put("kind", kind.getKey());
            doc.put("generatedBy", "ComponentShapesTest (features/inspecto-mcp) - never edit by hand; regenerate: " + REGENERATE);
            Map<String, Object> operations = new LinkedHashMap<>();
            Map<String, Object> shapes = new LinkedHashMap<>();
            Map<String, String> routes = McpTools.CHANGE_ROUTES.get(kind.getKey());
            for (String op : McpTools.operations(kind.getKey())) {
                Body b = kind.getValue().get(op);
                // one shape per distinct Body: create and update share "content"; each Entity List edit has its own
                String shapeName = b == null ? null : kind.getValue().values().stream().distinct().count() == 1 ? "content" : op;
                Map<String, Object> o = new LinkedHashMap<>();
                o.put("route", routes.get(op));
                o.put("content", shapeName);
                operations.put(op, o);
                if (b != null && !shapes.containsKey(shapeName)) shapes.put(shapeName, derive(kind.getKey(), op, b));
            }
            doc.put("operations", operations);
            doc.put("shapes", shapes);
            docs.put(kind.getKey(), doc);
        }
        return docs;
    }

    /** A default with its maps' keys sorted: a parser default built with Map.of (a Decision Rule's `when`) iterates in a per-JVM order. */
    private static Object sorted(Object v) {
        if (v instanceof Map<?, ?> m) {
            Map<String, Object> out = new TreeMap<>();
            m.forEach((k, x) -> out.put(String.valueOf(k), sorted(x)));
            return out;
        }
        if (v instanceof List<?> l) return l.stream().map(ComponentShapesTest::sorted).toList();
        return v;
    }

    static String json(Object doc) throws IOException {
        DefaultPrettyPrinter pp = new DefaultPrettyPrinter()
                .withObjectIndenter(new DefaultIndenter("  ", "\n"))
                .withArrayIndenter(new DefaultIndenter("  ", "\n"));
        return new ObjectMapper().writer(pp).writeValueAsString(doc) + "\n";
    }

    // ── the tests ────────────────────────────────────────────────────────────────────────────────

    @Test
    void everyProposeChangeKindHasAShapeAndEveryOperationIsCovered() {
        Map<String, Map<String, Body>> bodies = bodies();
        assertEquals(new TreeSet<>(McpTools.CHANGE_ROUTES.keySet()), bodies.keySet(),
                "every propose_change kind needs a shape in ComponentShapesTest.bodies(), and no other");
        for (String kind : bodies.keySet())
            for (String op : McpTools.operations(kind)) {
                boolean takesContent = !"delete".equals(op) && !"restore".equals(op);   // McpTools.proposeChange's rule
                assertEquals(takesContent, bodies.get(kind).get(op) != null, kind + " " + op + ": content shape present iff it takes content");
            }
    }

    @Test
    void theCommittedShapeFilesAreCurrent() throws IOException {
        Map<String, Map<String, Object>> docs = documents();
        boolean write = Boolean.getBoolean("shapes.write");
        List<String> stale = new ArrayList<>();
        if (write) Files.createDirectories(DIR);
        for (var d : docs.entrySet()) {
            Path file = DIR.resolve(d.getKey() + ".json");
            String want = json(d.getValue());
            String have = Files.exists(file) ? Files.readString(file, StandardCharsets.UTF_8).replace("\r\n", "\n") : null;
            if (want.equals(have)) continue;
            if (write) Files.writeString(file, want, StandardCharsets.UTF_8);
            else stale.add(file.getFileName().toString());
        }
        try (var listing = Files.list(DIR)) {
            for (Path p : listing.toList()) {
                String name = p.getFileName().toString();
                if (!name.endsWith(".json") || docs.containsKey(name.substring(0, name.length() - 5))) continue;
                if (write) Files.delete(p);
                else stale.add(name + " (no such kind)");
            }
        }
        if (!stale.isEmpty())
            fail("docs/api/component-shapes is stale against the code: " + stale + " - regenerate: " + REGENERATE);
    }

    @Test
    void entityListKeysAreReadByTheRoute() throws IOException {
        String src = Files.readString(Path.of("../inspecto-entity-list/src/main/java/com/gamma/entitylist/EntityListRoutes.java"));
        for (Body b : new LinkedHashSet<>(bodies().get("entity-list").values()))
            for (String key : b.keys())
                assertTrue(src.contains("body.get(\"" + key + "\")") || src.contains("(body, \"" + key + "\")")
                                || src.contains(" " + key + "(body"),   // reason(body): EntityListFacts.reason
                        "EntityListRoutes no longer reads '" + key + "' from a body: update ComponentShapesTest.declared");
    }
}
