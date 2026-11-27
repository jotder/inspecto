package com.gamma.control;

import com.gamma.config.spec.ConfigSpecs;

import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Test-only door to the package-private save checks the component shape reference runs (COMPONENT-REFERENCE-GENERATED-1,
 * {@code com.gamma.mcp.ComponentShapesTest}): the same idiom as {@link AgentGateProbe}, so main-code visibility stays
 * package-private.
 */
public final class ShapeProbe {

    private ShapeProbe() {}

    /** A Decision Rule body as {@code POST /decision-rules} validates and defaults it ({@code DecisionRoutes.normalize}). */
    public static Map<String, Object> decisionRule(Map<String, Object> body) {
        return DecisionRoutes.normalize(body);
    }

    /** A widget, dashboard or expectation body through the component save's kind checks (key census included); returns it. */
    public static Map<String, Object> component(String type, String id, Map<String, Object> content) {
        ComponentRoutes.validateKind(type, id, content);
        return content;
    }

    /** The top-level keys the component key census accepts for {@code type}: its spec's, the parser-only ones and the envelope. */
    public static Set<String> censusKeys(String type) {
        Set<String> keys = new TreeSet<>();
        ConfigSpecs.forType(type).fields().forEach(f -> {
            int dot = f.path().indexOf('.');
            keys.add(dot < 0 ? f.path() : f.path().substring(0, dot));
        });
        keys.addAll(ComponentRoutes.COMPONENT_PARSER_ONLY_KEYS.getOrDefault(type, Set.of()));
        keys.addAll(ComponentRoutes.COMPONENT_ENVELOPE_KEYS);
        return keys;
    }

    /** The Dataset / Reconciliation key census ({@code UnknownComponentKeys}): the keys and, for a Reconciliation, a compareColumns item's. */
    public static Set<String> unknownKeysCensus(String type) {
        return switch (type) {
            case "dataset" -> UnknownComponentKeys.DATASET;
            case "reconciliation" -> UnknownComponentKeys.RECONCILIATION;
            case "reconciliation.compareColumns" -> UnknownComponentKeys.COMPARE_COLUMN;
            default -> throw new IllegalArgumentException("no UnknownComponentKeys census for " + type);
        };
    }

    /** The keys of {@code content} the Dataset / Reconciliation census does not know: an agent's save is refused (422) on any. */
    public static java.util.List<String> unknownKeys(String type, Map<String, Object> content) {
        return UnknownComponentKeys.of(type, content);
    }

    /** An Alert Rule body through {@code AlertRoutes.parseContent} (no Space): the rule checks and its unknown-key refusal. */
    public static Map<String, Object> alertRule(Map<String, Object> body) {
        return AlertRoutes.parseContent(null, () -> null, body).toMap();
    }

    /** {@code JobRoutes.refuseNestedSettings}: a Job body's object-valued keys other than args, bind and incremental are 422. */
    public static void jobNested(Map<String, Object> body) {
        JobRoutes.refuseNestedSettings(body);
    }
}
