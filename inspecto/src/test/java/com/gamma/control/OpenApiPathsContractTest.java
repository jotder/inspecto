package com.gamma.control;

import com.fasterxml.jackson.core.util.DefaultIndenter;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.service.CollectorService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * OPENAPI-GEN-1 (decided 2026-09-10, built 2026-09-15): the OpenAPI document's <b>path + method skeleton is
 * derived from the live route table</b>, so a route can never be undocumented, while the hand-written
 * exemplars keep their request/response schemas.
 *
 * <p>Two modes, the same idiom as {@code StepTypesContractTest} / {@code ProcessorCatalogContractTest}:
 * <ul>
 *   <li>{@code -Dopenapi.paths.write=true} — boots {@link ControlApi} exactly as {@link ApiContractTest} does,
 *       enumerates every registration (plus the absent-module stubs, which mirror the optional modules'
 *       routes one for one), converts each regex pattern to an OpenAPI template and MERGES the result into
 *       {@code docs/api/openapi-v1.json}: an operation already documented is never touched; a missing one
 *       gets a skeleton marked {@code x-generated: true}. The JSON diff is the review artifact.</li>
 *   <li>default — asserts the committed document covers every live route (no third state: documented by
 *       hand, generated, or the build is red) and that no generated skeleton outlives its route.</li>
 * </ul>
 *
 * <p>⚠ Templates are matched STRUCTURALLY (every {@code {param}} collapses to {@code {}}), so a hand-written
 * {@code /config/{type}/{name}} satisfies the live {@code /config/([^/]+)/([^/]+)} without the generator
 * inventing a second spelling of the same path. Param names on generated skeletons come from the preceding
 * segment ({@code /spaces/{spaceId}}); a hand-written page may rename them freely.
 *
 * <p>⚠ This sees the CORE classpath — the optional modules' own routes are absent from this module's tests
 * ({@link ApiContractTest} explains why) — but every one of them has a 503 stub registered under the
 * identical pattern, which is how the skeleton still covers them ({@code x-optional-module: true}).
 */
class OpenApiPathsContractTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> HTTP_METHODS = Set.of("get", "put", "post", "delete", "patch");
    /** A top-level regex group in a route pattern: {@code ([^/]+)}, {@code (.+)}, {@code (a|b|c)}, optionally led by a lookahead. */
    private static final Pattern GROUP = Pattern.compile("(?:\\(\\?![^)]*\\))?\\(([^()]*)\\)");

    record Live(String method, String pattern, boolean stub) {}

    @Test
    void everyLiveRouteHasAnOperationInTheContract(@TempDir Path cfg) throws Exception {
        List<Live> live = liveRoutes(cfg);
        assertTrue(live.size() > 200, "route table not seen (" + live.size() + ") — re-anchor the reflection");

        Path file = docsApi().resolve("openapi-v1.json");
        ObjectNode doc = (ObjectNode) JSON.readTree(file.toFile());
        ObjectNode paths = (ObjectNode) doc.path("paths");

        // shape → documented path key, so a hand-written template satisfies the structurally equal route
        Map<String, String> byShape = new LinkedHashMap<>();
        for (var it = paths.fieldNames(); it.hasNext(); ) { String p = it.next(); byShape.put(shape(p), p); }

        List<String> missing = new ArrayList<>();
        Set<String> liveKeys = new LinkedHashSet<>();
        boolean write = Boolean.getBoolean("openapi.paths.write");
        for (Live r : live) {
            String template = template(r.pattern());
            String key = byShape.getOrDefault(shape(template), template);
            String method = r.method().toLowerCase(Locale.ROOT);
            liveKeys.add(method + " " + shape(template));
            ObjectNode pathNode = paths.has(key) ? (ObjectNode) paths.get(key) : null;
            if (pathNode != null && pathNode.has(method)) continue;          // documented (by hand or generated)
            if (!write) { missing.add(r.method() + " " + template); continue; }
            if (pathNode == null) { pathNode = paths.putObject(key); byShape.put(shape(key), key); }
            pathNode.set(method, skeleton(r, key));
        }

        // a generated skeleton whose route is gone is stale — hand-written pages are ratcheted elsewhere
        List<String> stale = new ArrayList<>();
        for (var it = paths.fields(); it.hasNext(); ) {
            var e = it.next();
            List<String> drop = new ArrayList<>();
            for (var ops = e.getValue().fields(); ops.hasNext(); ) {
                var op = ops.next();
                if (!HTTP_METHODS.contains(op.getKey())) continue;
                if (!op.getValue().path("x-generated").asBoolean(false)) continue;
                if (!liveKeys.contains(op.getKey() + " " + shape(e.getKey()))) {
                    if (write) drop.add(op.getKey()); else stale.add(op.getKey().toUpperCase(Locale.ROOT) + " " + e.getKey());
                }
            }
            drop.forEach(((ObjectNode) e.getValue())::remove);
        }
        if (write) {
            List<String> emptyPaths = new ArrayList<>();
            for (var it = paths.fields(); it.hasNext(); ) { var e = it.next(); if (e.getValue().isEmpty()) emptyPaths.add(e.getKey()); }
            emptyPaths.forEach(paths::remove);
            DefaultPrettyPrinter pp = new DefaultPrettyPrinter().withObjectIndenter(new DefaultIndenter("  ", "\n"));
            Files.writeString(file, JSON.writer(pp).writeValueAsString(doc) + "\n", StandardCharsets.UTF_8);
            System.out.println("OpenApiPathsContractTest: wrote " + file + " — " + paths.size() + " path(s)");
            return;
        }
        assertTrue(missing.isEmpty(), () -> missing.size() + " live route(s) have NO operation in docs/api/openapi-v1.json."
                + " Regenerate the skeleton with -Dopenapi.paths.write=true (then fill in schemas by hand), or document"
                + " them: " + missing);
        assertTrue(stale.isEmpty(), () -> "generated skeleton(s) whose route no longer exists — regenerate with"
                + " -Dopenapi.paths.write=true: " + stale);
    }

    // ── the route table, read the way ApiContractTest reads it ─────────────────────────────────

    private static List<Live> liveRoutes(Path cfg) throws Exception {
        Path pipe = PipelineConfigBatchTest.writePipeline(cfg, "");
        System.clearProperty("assist.write.root");
        List<Live> out = new ArrayList<>();
        try (CollectorService svc = new CollectorService(List.of(pipe), 3600, 1);
             ControlApi api = new ControlApi(svc, 0)) {
            Field rf = ControlApi.class.getDeclaredField("routes");
            rf.setAccessible(true);
            Field sf = ControlApi.class.getDeclaredField("stubbedRoutes");
            sf.setAccessible(true);
            Set<?> stubbed = (Set<?>) sf.get(api);
            for (Object route : (List<?>) rf.get(api)) {
                var m = route.getClass().getDeclaredMethod("method"); m.setAccessible(true);
                var p = route.getClass().getDeclaredMethod("pattern"); p.setAccessible(true);
                String method = (String) m.invoke(route);
                String regex = ((Pattern) p.invoke(route)).pattern();
                if (regex.startsWith("^")) regex = regex.substring(1);
                if (regex.endsWith("$")) regex = regex.substring(0, regex.length() - 1);
                out.add(new Live(method, regex, stubbed.contains(method + " " + regex)));
            }
        }
        return out;
    }

    // ── regex pattern → OpenAPI template ───────────────────────────────────────────────────────

    /** {@code /objects/([^/]+)/ack} → {@code /objects/{objectId}/ack}; {@code (a|b)} → {@code {action}}. */
    static String template(String pattern) {
        StringBuilder sb = new StringBuilder();
        Matcher m = GROUP.matcher(pattern);
        int last = 0;
        List<String> used = new ArrayList<>();
        while (m.find()) {
            String before = pattern.substring(last, m.start());
            sb.append(before);
            String name = m.group(1).contains("|") ? "action" : paramName(sb.toString());
            while (used.contains(name)) name = name + "2";
            used.add(name);
            sb.append('{').append(name).append('}');
            last = m.end();
        }
        sb.append(pattern.substring(last));
        return sb.toString().replace("\\.", ".");
    }

    /** The preceding static segment, singularised, plus {@code Id}: {@code /spaces/} → {@code spaceId}. */
    private static String paramName(String prefix) {
        String[] segs = prefix.split("/");
        String seg = "";
        for (int i = segs.length - 1; i >= 0; i--) if (!segs[i].isEmpty() && !segs[i].startsWith("{")) { seg = segs[i]; break; }
        if (seg.isEmpty()) return "id";
        seg = seg.replaceAll("[^A-Za-z0-9]", "");
        if (seg.endsWith("ies")) seg = seg.substring(0, seg.length() - 3) + "y";
        else if (seg.endsWith("s") && !seg.endsWith("ss")) seg = seg.substring(0, seg.length() - 1);
        return seg + "Id";
    }

    /** Every {@code {param}} collapsed, so two spellings of one path compare equal. */
    static String shape(String template) {
        return template.replaceAll("\\{[^}]*}", "{}");
    }

    private static ObjectNode skeleton(Live r, String key) {
        ObjectNode op = JSON.createObjectNode();
        String tag = key.split("/").length > 1 ? key.split("/")[1] : "root";
        op.putArray("tags").add(tag);
        op.put("summary", r.method() + " " + key + " (generated skeleton)");
        op.put("operationId", operationId(r.method(), key));
        op.put("description", "Generated from the live route table by OpenApiPathsContractTest; request and response "
                + "schemas are not yet documented — see control-api.md §5 for the exemplar-coverage decision.");
        ArrayNode params = op.putArray("parameters");
        Matcher pm = Pattern.compile("\\{([^}]+)}").matcher(key);
        while (pm.find()) {
            ObjectNode p = params.addObject();
            p.put("name", pm.group(1));
            p.put("in", "path");
            p.put("required", true);
            p.putObject("schema").put("type", "string");
        }
        if (params.isEmpty()) op.remove("parameters");
        ObjectNode def = op.putObject("responses").putObject("default");
        def.put("description", "Envelope-wrapped response (see #/components/schemas/Envelope); per-status schemas undocumented.");
        op.put("x-generated", true);
        if (r.stub()) op.put("x-optional-module", true);
        return op;
    }

    private static String operationId(String method, String key) {
        StringBuilder sb = new StringBuilder(method.toLowerCase(Locale.ROOT));
        for (String seg : key.split("/")) {
            if (seg.isEmpty()) continue;
            String s = seg.startsWith("{") ? "By" + cap(seg.substring(1, seg.length() - 1)) : cap(seg.replaceAll("[^A-Za-z0-9]+", " "));
            sb.append(s.replace(" ", ""));
        }
        return sb.toString();
    }

    private static String cap(String s) {
        StringBuilder sb = new StringBuilder();
        for (String w : s.split(" ")) if (!w.isEmpty()) sb.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1));
        return sb.toString();
    }

    /** The repo's docs/api dir, found by walking up from the module CWD. */
    private static Path docsApi() {
        Path dir = Path.of("").toAbsolutePath();
        while (dir != null) {
            Path candidate = dir.resolve("docs").resolve("api");
            if (Files.isRegularFile(candidate.resolve("openapi-v1.json"))) return candidate;
            dir = dir.getParent();
        }
        throw new IllegalStateException("docs/api/openapi-v1.json not found above " + Path.of("").toAbsolutePath());
    }
}
