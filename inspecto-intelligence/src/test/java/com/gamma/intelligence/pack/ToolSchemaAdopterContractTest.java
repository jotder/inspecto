package com.gamma.intelligence.pack;

import com.eoiagent.tool.Tool;
import com.gamma.service.CollectorService;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pins each agent tool's declared {@code jsonSchema} against what its REAL adopter actually sends.
 *
 * <p><b>Why this exists.</b> The 2026-07-27 cross-adopter audit found two defects of one shape: the
 * declared schema disagreed with the code and with the caller. `projection_author` declared
 * {@code columns.items} as objects while its only adopter — the link-analysis query panel — sends
 * {@code string[]}, and {@code columnNames()} has always read both. Nothing failed, because
 * <b>nothing validates {@code args} against {@code jsonSchema}</b>: {@code AgentRoutes} passes them
 * verbatim and {@code runTool} invokes with them directly.
 *
 * <p><b>Why a test and not runtime validation.</b> Adding a runtime validator would not have caught
 * either defect — it would have <i>rejected the panel's legitimate payload</i>, turning a wrong
 * comment into a broken feature. The schema is the thing that was wrong, so the schema is what has
 * to be held against reality. A red here is a documentation bug; a red in production would not be.
 *
 * <p><b>What it does NOT check:</b> {@code required}. Two panes adopt a tool twice — a direct call
 * and a natural-language one that deliberately omits the payload key ({@code query_author.when},
 * {@code pipeline_author.flow}) so the model's derived args are not overwritten by the pane's. An
 * absent key is therefore legal by design, and only the type of a key that IS sent is an invariant.
 *
 * <p><b>Keep this table in step with the panes.</b> A new {@code <inspecto-ai-assist>} adopter, or a
 * changed {@code [args]}, belongs here — that is the whole point.
 */
class ToolSchemaAdopterContractTest {

    /** One adopter's payload: the arg keys it sends, mapped to the JSON type of the value. */
    private record Payload(String tool, String adopter, Map<String, String> argTypes) {}

    /**
     * Every real {@code <inspecto-ai-assist>} adopter in {@code inspecto-ui/}, read from the panes.
     * {@code kpi_report_builder} is absent on purpose: it is in the {@code AiToolName} union and has
     * an adapter branch, but no pane calls it, so there is no contract to pin.
     */
    private static final List<Payload> ADOPTERS = List.of(
            new Payload("component_draft", "components/component-form.dialog.ts",
                    Map.of("kind", "string")),
            new Payload("query_author", "studio/queries/queries.component.ts (structured)",
                    Map.of("dataset", "string", "when", "object", "name", "string")),
            new Payload("query_author", "studio/queries/queries.component.ts (natural language)",
                    Map.of("dataset", "string", "name", "string")),
            new Payload("pipeline_author", "pipelines/pipeline-editor.component.ts (check topology)",
                    Map.of("flow", "object")),
            new Payload("projection_author", "studio/link-analysis/link-analysis-query-panel.component.ts",
                    // ⚠ string[], NOT {name,type}[] — this is the pair that was out of step.
                    Map.of("datasetId", "string", "columns", "array:string")),
            new Payload("suggest_expectations", "expectations/expectation-form.dialog.ts",
                    Map.of("table", "string", "target", "string", "column", "string")));

    @Test
    void everyAdopterPayloadIsAdmittedByItsToolsDeclaredSchema() {
        Map<String, String> belt = schemasByName();
        for (Payload p : ADOPTERS) {
            String schema = belt.get(p.tool());
            assertNotNull(schema, p.tool() + " is not on the belt but " + p.adopter() + " calls it");
            p.argTypes().forEach((key, sent) -> {
                String declared = propertyType(schema, key);
                assertNotNull(declared, () -> p.tool() + " has no declared property '" + key
                        + "', but " + p.adopter() + " sends it");
                assertTrue(admits(declared, sent), () -> p.tool() + '.' + key + " is declared as "
                        + declared + " but " + p.adopter() + " sends " + sent
                        + " — the schema is describing something no caller produces");
            });
        }
    }

    // ── the tiny bit of schema reading this needs ───────────────────────────────
    // The schemas are hand-written literals in InspectoTools, so a full JSON parser buys nothing; a
    // property's declaration is always a single balanced brace group after its key.

    private static Map<String, String> schemasByName() {
        Map<String, String> out = new LinkedHashMap<>();
        for (Tool t : new InspectoPack(new CollectorService(List.of(), 3600, 1)).toolProvider().tools()) {
            out.put(t.spec().name(), t.spec().jsonSchema());
        }
        return out;
    }

    /** {@code "string"} · {@code "object"} · {@code "array:object|string"} — the declared shape of one arg. */
    private static String propertyType(String schema, String key) {
        // Only the TOP-LEVEL properties of the tool are its args; a nested object can repeat a key
        // name (`name` occurs inside more than one nested shape), and matching that would pin the
        // wrong declaration.
        String properties = braceGroupAfter(schema, "\"properties\":");
        String body = properties == null ? null : topLevelBraceGroup(properties, '"' + key + "\":");
        if (body == null) return null;
        String type = typesIn(body);
        if (!type.contains("array")) return type;
        String items = braceGroupAfter(body, "\"items\":");
        return "array:" + (items == null ? "any" : typesIn(items));
    }

    /** Whether a declared type accepts a sent one; {@code array:any} accepts any element type. */
    private static boolean admits(String declared, String sent) {
        if (!sent.startsWith("array:")) return contains(declared, sent);
        if (!declared.startsWith("array:")) return false;
        String items = declared.substring(6);
        return items.equals("any") || contains(items, sent.substring(6));
    }

    private static boolean contains(String declared, String one) {
        for (String t : declared.split("\\|")) if (t.equals(one)) return true;
        return false;
    }

    /** The `"type"` of a declaration body, scalar or union, as {@code a|b}. */
    private static String typesIn(String body) {
        Matcher m = Pattern.compile("\"type\":\\s*(\"[^\"]+\"|\\[[^]]*])").matcher(body);
        if (!m.find()) return "any";
        return m.group(1).replaceAll("[\\[\\]\"\\s]", "").replace(',', '|');
    }

    /** As {@link #braceGroupAfter}, but only matching {@code prefix} at nesting depth 0 of {@code s}. */
    private static String topLevelBraceGroup(String s, String prefix) {
        int depth = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '{') depth++;
            else if (c == '}') depth--;
            else if (depth == 0 && s.startsWith(prefix, i)) return braceGroupAfter(s.substring(i), prefix);
        }
        return null;
    }

    /** The contents of the balanced {@code {…}} that follows {@code prefix}, or null if absent. */
    private static String braceGroupAfter(String s, String prefix) {
        int at = s.indexOf(prefix);
        if (at < 0) return null;
        int open = s.indexOf('{', at + prefix.length());
        if (open < 0) return null;
        int depth = 0;
        for (int i = open; i < s.length(); i++) {
            if (s.charAt(i) == '{') depth++;
            else if (s.charAt(i) == '}' && --depth == 0) return s.substring(open + 1, i);
        }
        return null;
    }
}
