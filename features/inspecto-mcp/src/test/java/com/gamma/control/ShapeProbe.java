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

    /** The envelope keys the component store adds to every persisted component. */
    public static Set<String> envelopeKeys() {
        return ComponentRoutes.COMPONENT_ENVELOPE_KEYS;
    }
}
