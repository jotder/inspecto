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
import com.gamma.pipeline.PipelineEditable;
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
 * COMPONENT-REFERENCE-MORE-KINDS-1 adds the component kinds {@code propose_change} does not offer
 * ({@link McpTools#COMPONENT_SHAPE_KINDS}): each file is named by its component type, carries {@code proposeChange: false}
 * and the generic {@code /components/<type>} routes; {@link #UNSHAPED} names every other component kind and why.
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

    /**
     * A list-of-objects key whose element keys come from a record ({@code nodes[]} of a Pipeline graph) or from the parser's
     * own element key census ({@code factors[]} of a Risk Score model; {@code record} null).
     */
    record Element(String key, Class<? extends Record> record, Set<String> keys) {
        Element(String key, Class<? extends Record> record) {
            this(key, record, null);
        }

        Element(String key, Set<String> keys) {
            this(key, null, keys);
        }
    }

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
            Map<String, String> elementKeys = new LinkedHashMap<>();
            if (el.record() != null)
                for (RecordComponent rc : el.record().getRecordComponents()) elementKeys.put(rc.getName(), jsonType(rc.getType()));
            else
                for (String k : new TreeSet<>(el.keys())) elementKeys.put(k, elementType(b, el.key(), k));
            for (var ek : elementKeys.entrySet()) {
                String path = el.key() + "[]." + ek.getKey();
                Map<String, Object> f = new LinkedHashMap<>();
                f.put("path", path);
                f.put("type", ek.getValue());
                boolean req = elementRequired(b, el.key(), ek.getKey());
                f.put("required", req);
                if (req) f.put("requiredBy", "parser");
                fields.put(path, f);
            }
        }

        Map<String, Object> shape = new LinkedHashMap<>();
        shape.put("source", b.source());
        shape.put("unknownKeys", unknownKeys(b));
        // the x- annotation is named only when the real parser keeps one (a module model's parser refuses it)
        if (extensionKept(b)) shape.put("extensionPrefix", AcceptedConfigKeys.EXTENSION_PREFIX);
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

    /** An element key's JSON type as the example's first element carries it; {@code any} when the example leaves it out. */
    @SuppressWarnings("unchecked")
    private static String elementType(Body b, String listKey, String key) {
        Object first = ((List<Object>) b.example().get(listKey)).get(0);
        Object v = ((Map<String, Object>) first).get(key);
        return v == null ? "any" : jsonType(v.getClass());
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
        if (v instanceof Enum<?> e) return wire(e);
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

    /** {@code true} when the real parser accepts the example plus one {@code x-} annotation key. */
    private static boolean extensionKept(Body b) {
        Map<String, Object> probe = copy(b.example());
        probe.put(AcceptedConfigKeys.EXTENSION_PREFIX + "zzProbe", "x");
        try {
            b.parse().apply(probe);
            return true;
        } catch (RuntimeException refused) {
            return false;
        }
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
        return java.util.Arrays.stream(values).map(ComponentShapesTest::wire).toList();
    }

    /** An enum value as config spells it: its {@code wire()} when it has one ({@code last-year}), else its name in lower case. */
    private static String wire(Enum<?> v) {
        try {
            return String.valueOf(v.getClass().getMethod("wire").invoke(v));
        } catch (NoSuchMethodException none) {
            return v.name().toLowerCase(java.util.Locale.ROOT);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
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
                  // DRYRUN-SAVE-NODE-MISMATCH-1: real node types, a graph the save lowers (the example used to say
                  // source.file / sink.parquet, which no module registers, and an agent extrapolated source.dataset).
                  // A Dataset is read by an acquisition with connector: dataset; thePipelineExampleIsAGraphTheSaveLowers.
                  "nodes", List.of(m("id", "read", "type", "acquisition", "config", m("connector", "dataset", "dataset", "orders")),
                                   m("id", "total", "type", "transform.summarize",
                                     "config", m("group_by", List.of("region"), "measures", List.of("sum(amount)"))),
                                   m("id", "write", "type", "sink.persistent", "config", m("database", "orders_by_region"))),
                  "edges", List.of(m("from", "read", "to", "total"), m("from", "total", "to", "write"))),
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

        componentKinds(out);
        return out;
    }

    // ── the component kinds propose_change does not offer (COMPONENT-REFERENCE-MORE-KINDS-1) ─────

    /** The store envelope every component carries ({@code ComponentRoutes.COMPONENT_ENVELOPE_KEYS}): stamped, never authored. */
    private static final Set<String> ENVELOPE = Set.of("name", "owner", "shares");

    /**
     * Component kinds ({@code ComponentStore.WRITABLE_TYPES}) with no shape of their own, each with why: a kind joins
     * {@link McpTools#COMPONENT_SHAPE_KINDS} by moving out of here. {@link #everyComponentKindIsShapedOrNamed} holds the
     * three lists to the store's, so a new component kind cannot be left out silently.
     */
    static final Map<String, String> UNSHAPED = new TreeMap<>(Map.ofEntries(
            Map.entry("channel", "the propose_change kind notification-channel is this kind's shape"),
            Map.entry("grammar", "no parser on the component save door: stored as sent, so a shape would be hand-written"),
            Map.entry("transform", "no parser on the component save door: stored as sent, so a shape would be hand-written"),
            Map.entry("sink", "no parser on the component save door (only its reserved store names are checked)"),
            Map.entry("query", "no parser on the component save door: stored as sent, so a shape would be hand-written"),
            Map.entry("requirement", "no parser on the component save door: stored as sent, so a shape would be hand-written"),
            Map.entry("link-analysis-view", "a saved console view: stored as sent, no parser on the component save door"),
            Map.entry("geo-map-view", "a saved console view: stored as sent, no parser on the component save door"),
            Map.entry("pattern-pack", "no parser on the component save door: stored as sent, so a shape would be hand-written"),
            Map.entry("preset-query", "parsed against a Dataset's roles when listed or run, not on save: no save-time parser"),
            Map.entry("rule-template", "no parser on the component save door: stored as sent, so a shape would be hand-written"),
            Map.entry("access-catalog", "no parser on the component save door: stored as sent, so a shape would be hand-written"),
            Map.entry("access-profile", "no parser on the component save door: stored as sent, so a shape would be hand-written"),
            Map.entry("insight-profile", "its validator lives in inspecto-insights, which is not on inspecto-mcp's classpath"),
            Map.entry("findings-spec", "deferred (COMPONENT-REFERENCE-GOVERNANCE-KINDS-1): FindingsSpec.fromMap, a canManageIncidents route"),
            Map.entry("workflow", "deferred (COMPONENT-REFERENCE-GOVERNANCE-KINDS-1): Workflow.fromComponent, a canAdminister route"),
            Map.entry("sla-policy", "deferred (COMPONENT-REFERENCE-GOVERNANCE-KINDS-1): SlaPolicy.fromComponent, a canAdminister route"),
            Map.entry("escalation-rule", "deferred (COMPONENT-REFERENCE-GOVERNANCE-KINDS-1): EscalationRule.fromComponent, a canAdminister route")));

    private static void componentKinds(Map<String, Map<String, Body>> out) {
        Body dataset = new Body("UnknownComponentKeys.DATASET (the Dataset key census) + DatasetEntryRules.validate and the "
                + "virtual-Dataset SQL check (ComponentRoutes.validateKind)",
                union(ShapeProbe.unknownKeysCensus("dataset"), ENVELOPE),
                body -> { ShapeProbe.component("dataset", "orders", body); refuseUnknown("dataset", body); return body; },
                m("physicalRef", "orders", "description", "One row per order",
                  "columns", List.of(m("name", "order_id", "type", "VARCHAR"), m("name", "amount", "type", "DOUBLE"))),
                List.of(), "", null, Map.of(), List.of(),
                List.of("An unknown key is refused (422) for an agent; a person's save keeps it with an `UNKNOWN_KEY` warning "
                                + "(both: `POST /validate`).",
                        "`columns`, `calculated`, `query`, `filters` and the other blocks are accepted whole: their inner keys "
                                + "are not censused.",
                        "`sql` (a virtual Dataset) needs `sourceName` and meets the SQL safety check; `basedOn` (a Mapping) names "
                                + "a Dataset the caller can see; a column's `classification` must be one the Space knows."))
                .with(Map.of(), ENVELOPE);
        out.put("dataset", ops(dataset, "create", "update"));

        Body recon = new Body("UnknownComponentKeys.RECONCILIATION (its key census) + ReconConfigLoader.bands and buildSpec "
                + "(ReconKindValidator)",
                union(ShapeProbe.unknownKeysCensus("reconciliation"), ENVELOPE),
                body -> { ShapeProbe.component("reconciliation", "orders-vs-ledger", body); refuseUnknown("reconciliation", body);
                          return body; },
                m("leftDataset", "orders", "rightDataset", "ledger", "keyColumns", List.of("order_id"),
                  "compareColumns", List.of(m("column", "amount", "agg", "sum", "toleranceType", "absolute", "tolerance", 0.01))),
                List.of(), "", null,
                Map.of("cardinality", lower(com.gamma.recon.ReconService.Cardinality.values())),
                List.of(new Element("compareColumns", ShapeProbe.unknownKeysCensus("reconciliation.compareColumns"))),
                List.of("Two or three Datasets: `leftDataset` (the anchor) and `rightDataset`, optionally `thirdDataset`, or "
                                + "`datasets` (a list, the first is the anchor).",
                        "`compareColumns[].agg` is `sum` (default) or `count`; `toleranceType` is `exact` (default), "
                                + "`absolute` or `percent` (ReconService.Spec.of); `tolerance` defaults to 0.",
                        "`bands` is `{okBelow, warnAbove}` in percent, 0 <= okBelow <= warnAbove <= 100.",
                        "An unknown key (a `compareColumns` item's too) is refused (422) for an agent; a person's save keeps it "
                                + "with an `UNKNOWN_KEY` warning."))
                .with(Map.of(), ENVELOPE);
        out.put("reconciliation", ops(recon, "create", "update"));

        Body kpi = new Body("KpiDefinition.KEYS + KpiDefinition.fromMap (ComponentRoutes.validateKind)",
                new TreeSet<>(KpiDefinition.KEYS),
                body -> { ShapeProbe.component("kpi", "orders-daily", body); return KpiDefinition.fromMap("orders-daily", body); },
                m("title", "Orders per day", "dataset", "orders", "measure", "count", "timeField", "order_date", "grain", "day"),
                List.of(), "", KpiDefinition.class,
                Map.of("grain", lower(KpiDefinition.Grain.values()), "comparison", lower(KpiDefinition.Comparison.values()),
                        "direction", lower(KpiDefinition.Direction.values())), List.of(),
                List.of("`measure` is `count` or `agg(field)`; the save also checks the Dataset exists and has the fields "
                        + "(KpiRoutes.requireMeasure).", "`requirement` links the KPI to the Requirement it delivers."))
                .with(Map.of("measure", "string"), ENVELOPE);
        out.put("kpi", ops(kpi, "create", "update"));

        Body schema = new Body("ConfigSpecs.schema() + the spec and config-safety gates (ComponentRoutes.validateKind)",
                ShapeProbe.censusKeys("schema"),
                body -> ShapeProbe.component("schema", "orders", body),
                m("raw", m("name", "ORDERS", "format", "CSV",
                        "fields", List.of(m("name", "ORDER_ID", "selector", "order_id", "type", "VARCHAR"))),
                  "mapping", m("canonicalName", "orders")),
                ConfigSpecs.schema().fields(), "", null, Map.of(), List.of(),
                List.of("`raw.fields[]` items are `{name, selector, type}` plus optional `format`, `description`, `unit` and "
                                + "`classification` (one the Space knows).",
                        "The engine parses more than the spec names (Identifiers.validateSchema at run time); a shipped schema "
                                + "may also carry `partitions[]`. The component's id is its `schema_file: schema/<id>` reference."))
                .with(Map.of(), ENVELOPE);
        out.put("schema", ops(schema, "create", "update"));

        Body mapping = new Body("MappingRules.validate (keys read inline; declared in the generator and held to its source)",
                union(Set.of("rules"), ENVELOPE),
                body -> ShapeProbe.component("mapping", "orders", body),
                m("rules", List.of(m("targetColumn", "order_id", "sourceExpression", "ORDER_ID", "transformType", "DIRECT"))),
                List.of(), "", null, Map.of(),
                List.of(new Element("rules", MAPPING_RULE_KEYS)),
                List.of("`rules[].transformType` is one of " + String.join(", ", com.gamma.etl.TransformCompiler.TRANSFORM_TYPES)
                        + " (blank = DIRECT); an `EXPR` rule's SQL is emitted as written (operator-authored, trusted).",
                        "The save checks `rules` only when it is present: a mapping without `rules` saves and maps nothing.",
                        "Preview the rules with `POST /components/mapping/validate`."))
                .with(Map.of("rules", "array"), ENVELOPE);
        out.put("mapping", ops(mapping, "create", "update"));

        Body runbook = new Body("Runbook.MODELLED + Runbook.fromMap (the save also checks each step link's target exists)",
                privateSet(com.gamma.alert.Runbook.class, "MODELLED"),
                body -> com.gamma.alert.Runbook.fromMap("orders-late", body),
                m("title", "Orders arrive late", "steps", List.of(m("text", "Check the Collector's last arrival"))),
                List.of(), "", com.gamma.alert.Runbook.class, Map.of(),
                List.of(new Element("steps", privateSet(com.gamma.alert.Runbook.class, "STEP_KEYS"))),
                List.of("`steps[].link` is `{kind, id}` with kind one of "
                        + String.join(", ", new TreeSet<>(com.gamma.alert.Runbook.LINK_KINDS)) + "; an Alert Rule names the "
                        + "Runbook in its `runbook` key."))
                .with(Map.of(), ENVELOPE);
        out.put("runbook", ops(runbook, "create", "update"));

        Body riskScore = model("risk-score", com.gamma.risk.RiskScoreModel.class, "factors", "FACTOR_KEYS",
                body -> com.gamma.risk.RiskScoreModel.fromMap("account_risk", body),
                m("entityType", "account", "highThreshold", 60,
                  "factors", List.of(m("id", "cash_in", "dataset", "transfers", "key", "payee_account", "measure", "count",
                          "weight", 0.5, "cap", 40))),
                List.of("Run by the `risk.score` Job; the save also checks the Datasets and the output store in the Space."));
        out.put("risk-score", ops(riskScore, "create", "update"));

        Body anomaly = model("anomaly-model", com.gamma.anomaly.AnomalyModel.class, "features", "FEATURE_KEYS",
                body -> com.gamma.anomaly.AnomalyModel.fromMap("wallet_activity", body),
                m("entityType", "wallet", "bucket", "day", "window", 28,
                  "features", List.of(m("id", "txn_count", "dataset", "wallet_txn", "key", "wallet_id", "time", "txn_ts",
                          "measure", "count"))),
                List.of("Run by the `anomaly.score` Job; `peers` is `{" + String.join(", ",
                        new TreeSet<>(privateSet(com.gamma.anomaly.AnomalyModel.class, "PEER_KEYS"))) + "}`."));
        out.put("anomaly-model", ops(anomaly, "create", "update"));

        Body segmentation = model("segmentation-model", com.gamma.predictive.SegmentationModel.class, "segments", "SEGMENT_KEYS",
                body -> com.gamma.predictive.SegmentationModel.fromMap("customer_rfm", body),
                m("entityType", "customer", "dataset", "orders", "key", "customer_id", "time", "order_date",
                  "frequency", "count", "monetary", "sum(amount)",
                  "segments", List.of(m("label", "Champions", "recency", "4-5", "frequency", "4-5", "monetary", "4-5"))),
                List.of("Run by the `predict.segment` Job. A score range is `*`, `n` or `a-b` inside `1..bins`; the first "
                        + "matching `segments` row names the segment."));
        out.put("segmentation-model", ops(segmentation, "create", "update"));

        Body churn = model("churn-model", com.gamma.predictive.ChurnModel.class, "inputs", "INPUT_KEYS",
                body -> com.gamma.predictive.ChurnModel.fromMap("customer_churn", body),
                m("entityType", "customer", "dataset", "orders", "key", "customer_id", "time", "order_date", "intercept", -1.0,
                  "inputs", List.of(m("name", "orders", "measure", "count", "transform", "log", "coefficient", -0.8))),
                List.of("Run by the `predict.churn` Job; nothing is fitted: the intercept and coefficients are authored.",
                        "`inputs[].transform` is none (default), log, cap (needs `cap`) or z (needs `mean` and `scale`)."));
        out.put("churn-model", ops(churn, "create", "update"));

        Body forecast = model("forecast-model", com.gamma.predictive.ForecastModel.class, null, null,
                body -> com.gamma.predictive.ForecastModel.fromMap("sku_sales", body),
                m("entityType", "product", "dataset", "sales", "key", "sku", "time", "sold_at", "measure", "count",
                  "grain", "day", "method", "seasonal-naive", "horizon", 28),
                List.of("Run by the `predict.forecast` Job; `alpha`, `beta` and `gamma` (0..1) are required by method "
                        + "`holt-winters` and refused on `seasonal-naive`.", "`history` defaults to 364 at grain `day` (the default below, from the example) and to 156 at grain `week`."));
        out.put("forecast-model", ops(forecast, "create", "update"));
    }

    /** The {@code mapping} rule keys {@code MappingRules.validate} reads ({@link #mappingRuleKeysAreReadByMappingRules} holds them). */
    static final Set<String> MAPPING_RULE_KEYS = Set.of("targetColumn", "sourceExpression", "transformType");

    /** A module model kind: its parser's private key census ({@code MODEL_KEYS}), its list element's, and its record. */
    private static Body model(String kind, Class<? extends Record> record, String listKey, String elementKeysField,
                              Function<Map<String, Object>, Object> parse, Map<String, Object> example, List<String> notes) {
        List<String> all = new ArrayList<>(notes);
        all.add("Unknown keys are refused, not ignored. The save also checks, in the Space, the Datasets it reads and its "
                + "output store.");
        return new Body(record.getSimpleName() + ".MODEL_KEYS + " + record.getSimpleName() + ".fromMap (" + kind
                + "'s validator on the component save door)",
                privateSet(record, "MODEL_KEYS"),
                body -> { ShapeProbe.component(kind, "probe", body); return parse.apply(body); },
                example, List.of(), "", record, Map.of(),
                listKey == null ? List.of() : List.of(new Element(listKey, privateSet(record, elementKeysField))),
                all).with(Map.of(), union(ENVELOPE, Set.of("id")));   // id: the URL's, stamped by the store
    }

    private static void refuseUnknown(String type, Map<String, Object> body) {
        List<String> unknown = ShapeProbe.unknownKeys(type, body);
        if (!unknown.isEmpty()) throw new IllegalArgumentException(type + " has unknown key(s) " + unknown);
    }

    /** A parser's private key census, read reflectively (the parser keeps it private; the reference reads it). */
    @SuppressWarnings("unchecked")
    private static Set<String> privateSet(Class<?> owner, String field) {
        try {
            var f = owner.getDeclaredField(field);
            f.setAccessible(true);
            return new TreeSet<>((Collection<String>) f.get(null));
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(owner.getSimpleName() + "." + field + " is gone: update ComponentShapesTest", e);
        }
    }

    private static Set<String> union(Set<String> a, Set<String> b) {
        Set<String> out = new TreeSet<>(a);
        out.addAll(b);
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
            boolean propose = McpTools.CHANGE_ROUTES.containsKey(kind.getKey());
            // a kind propose_change does not offer is named by its component type and its routes are the component routes
            doc.put("proposeChange", propose);
            Map<String, String> routes = propose ? McpTools.CHANGE_ROUTES.get(kind.getKey()) : componentRoutes(kind.getKey());
            for (String op : propose ? McpTools.operations(kind.getKey()) : List.copyOf(routes.keySet())) {
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

    /** The generic component routes ({@code ComponentRoutes}) of a kind {@code propose_change} does not offer. */
    private static Map<String, String> componentRoutes(String kind) {
        Map<String, String> out = new LinkedHashMap<>();
        out.put("create", "POST /components/" + kind);
        out.put("update", "PUT /components/" + kind + "/{id}");
        out.put("delete", "DELETE /components/" + kind + "/{id}");
        return out;
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
        assertEquals(McpTools.shapeKinds(), bodies.keySet(),
                "every propose_change kind and every McpTools.COMPONENT_SHAPE_KINDS kind needs a shape in "
                        + "ComponentShapesTest.bodies(), and no other");
        for (String kind : McpTools.CHANGE_ROUTES.keySet())
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

    /**
     * COMPONENT-REFERENCE-MORE-KINDS-1: every component kind the store writes is a propose_change kind, a shaped component
     * kind, or named in {@link #UNSHAPED} with why — never left out silently.
     */
    @Test
    void everyComponentKindIsShapedOrNamed() {
        Set<String> store = new TreeSet<>(com.gamma.pipeline.ComponentStore.WRITABLE_TYPES);
        Set<String> accounted = new TreeSet<>(McpTools.shapeKinds());
        accounted.addAll(UNSHAPED.keySet());
        Set<String> missing = new TreeSet<>(store);
        missing.removeAll(accounted);
        assertTrue(missing.isEmpty(), "component kinds with no shape and no reason in ComponentShapesTest.UNSHAPED: " + missing);
        for (String k : McpTools.COMPONENT_SHAPE_KINDS) {
            assertTrue(store.contains(k), "COMPONENT_SHAPE_KINDS names " + k + ", which is no component kind");
            assertTrue(!McpTools.CHANGE_ROUTES.containsKey(k), k + " is a propose_change kind: it needs no second listing");
        }
        for (String k : UNSHAPED.keySet()) {
            assertTrue(store.contains(k), "UNSHAPED names " + k + ", which is no component kind");
            assertTrue(!McpTools.shapeKinds().contains(k), k + " has a shape: take it off UNSHAPED");
        }
    }

    @Test
    void mappingRuleKeysAreReadByMappingRules() throws IOException {
        String src = Files.readString(Path.of("../../platform/inspecto-engine/src/main/java/com/gamma/pipeline/MappingRules.java"));
        for (String key : MAPPING_RULE_KEYS)
            assertTrue(src.contains("rule.get(\"" + key + "\")"),
                    "MappingRules no longer reads '" + key + "' from a rule: update ComponentShapesTest.MAPPING_RULE_KEYS");
    }

    /**
     * DRYRUN-SAVE-NODE-MISMATCH-1: the Pipeline example is a graph the save LOWERS, not one the codec merely parses —
     * an agent copies it, and an example naming node types no module registers taught it to guess another
     * ({@code source.dataset}). Strict, as an active Pipeline's save is.
     */
    @Test
    void thePipelineExampleIsAGraphTheSaveLowers() {
        Map<String, Object> example = copy(bodies().get("pipeline").get("create").example());
        example.putIfAbsent("name", "orders");
        PipelineEditable.lower(PipelineCodec.fromMap(example), new LinkedHashMap<>(), true);
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
