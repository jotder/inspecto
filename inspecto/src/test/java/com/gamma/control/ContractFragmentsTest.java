package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * MODULE-REORG-1 P1: the HTTP contract of a module lives WITH the module. {@code docs/api/openapi-v1.json} is the
 * GENERATED merge of the core fragment ({@code inspecto/}) and every module's
 * {@code META-INF/inspecto/openapi.fragment.json} (tools/openapi-merge.mjs). This is the Java-side half of the
 * guard that {@code tools/check-openapi-fragments.mjs} runs in CI (which also checks byte equality, a text
 * property): the fragments, read from every reactor module's source tree, carry exactly the document's content, no
 * path sits in two of them, and every route a manifest declares in {@code provides.routes} is documented in that
 * module's own fragment. The RUNTIME still serves {@code docs/api/openapi-v1.json} unchanged (ApiContractTest).
 */
class ContractFragmentsTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String RES = "src/main/resources/META-INF/inspecto/";
    private static final Pattern ID = Pattern.compile("(?m)^id:\\s*(\\S+)");
    private static final Pattern ROUTES = Pattern.compile("(?m)^\\s*routes\\[\\d+\\]:\\s*(.*)$");
    private static final Pattern ROUTE = Pattern.compile("\"([A-Z]+) ([^\"]+)\"");

    /** A route regex or an OpenAPI template, every parameter collapsed to {@code {}}. */
    static String shape(String pattern) {
        return pattern.replace("\\.", ".").replaceAll("(?:\\(\\?![^)]*\\))?\\(([^()]*)\\)", "{}").replaceAll("\\{[^}]*}", "{}");
    }

    @Test
    void fragmentsCarryExactlyTheDocumentAndEveryDeclaredRoute() throws Exception {
        Path root = ReactorModules.root();
        ObjectNode doc = (ObjectNode) JSON.readTree(root.resolve("docs/api/openapi-v1.json").toFile());
        ObjectNode paths = (ObjectNode) doc.get("paths");

        ObjectNode core = (ObjectNode) JSON.readTree(root.resolve("inspecto").resolve(RES + "openapi.fragment.json").toFile());
        core.remove("x-path-order");
        ObjectNode docRest = doc.deepCopy();
        docRest.remove("paths");
        ObjectNode coreRest = core.deepCopy();
        coreRest.remove("paths");
        assertEquals(docRest, coreRest, "info/servers/tags/components of the core fragment must equal the document's");

        Map<String, String> owner = new HashMap<>();          // path key -> fragment owner
        List<String> problems = new ArrayList<>();
        claim(core.get("paths"), "core", owner, paths, problems);

        int modulesWithFragment = 0;
        for (Path module : ReactorModules.modules()) {
            if (module.equals(root.resolve("inspecto"))) continue;      // the core fragment, claimed above
            Path manifest = module.resolve(RES + "module.toon");
            if (!Files.isRegularFile(manifest)) continue;
            String toon = Files.readString(manifest);
            Matcher id = ID.matcher(toon);
            assertTrue(id.find(), manifest + " has no id");
            String name = id.group(1);
            Matcher rm = ROUTES.matcher(toon);
            Path fragment = module.resolve(RES + "openapi.fragment.json");
            if (!Files.isRegularFile(fragment)) {
                if (rm.find()) problems.add(name + " declares routes but ships no openapi.fragment.json");
                continue;
            }
            modulesWithFragment++;
            JsonNode frag = JSON.readTree(fragment.toFile());
            assertEquals(List.of("paths"), toList(frag.fieldNames()), name + ": a module fragment holds only \"paths\"");
            claim(frag.get("paths"), name, owner, paths, problems);
            if (!rm.find()) { problems.add(name + " ships a fragment but declares no provides.routes"); continue; }
            Map<String, String> byShape = new HashMap<>();
            for (var it = frag.get("paths").fieldNames(); it.hasNext(); ) { String k = it.next(); byShape.put(shape(k), k); }
            Matcher route = ROUTE.matcher(rm.group(1));
            while (route.find()) {
                String key = byShape.get(shape(route.group(2)));
                if (key == null || !frag.get("paths").get(key).has(route.group(1).toLowerCase())) {
                    problems.add(name + ": " + route.group(1) + " " + route.group(2) + " is not documented in its own fragment");
                }
            }
        }
        assertTrue(modulesWithFragment >= 10, "module fragments not seen (" + modulesWithFragment + ") — re-anchor the scan");
        assertEquals(new TreeSet<>(toList(paths.fieldNames())), new TreeSet<>(owner.keySet()),
                "the fragments together must hold exactly the document's paths");
        assertTrue(problems.isEmpty(), () -> String.join("\n", problems));
    }

    private static void claim(JsonNode fragmentPaths, String name, Map<String, String> owner, ObjectNode docPaths, List<String> problems) {
        for (var it = fragmentPaths.fields(); it.hasNext(); ) {
            var e = it.next();
            String prior = owner.put(e.getKey(), name);
            if (prior != null) problems.add("path " + e.getKey() + " is in two fragments: " + prior + " and " + name);
            if (!e.getValue().equals(docPaths.get(e.getKey()))) {
                problems.add(name + ": path " + e.getKey() + " differs from docs/api/openapi-v1.json — run node tools/openapi-merge.mjs --write");
            }
        }
    }

    private static List<String> toList(java.util.Iterator<String> it) {
        List<String> out = new ArrayList<>();
        it.forEachRemaining(out::add);
        return out;
    }
}
