package com.gamma.control;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * `ASSURE-MAKER-CHECKER-1` S0 — <b>every config-writing control-plane route passes through a funnel, or is on
 * a justified exemption list.</b> Maker-checker is only as strong as its narrowest door: a route that writes
 * config beside the funnels is a route an approval policy cannot hold.
 *
 * <p><b>The enumeration</b> is the one {@code CapabilityManifestTest#everyMutatingRouteIsGatedExemptOrPending}
 * trusts: every {@code api.post|put|patch|delete("pattern", …)} registration site in every reactor module's
 * {@code src/main/java}. For each site the scan takes the handler expression, then the transitive closure of
 * the methods it calls that are declared in the SAME source file (a route's work lives in its own module
 * class), and reads that closure for:
 * <ul>
 *   <li>a <b>config write</b> — a TOON encode ({@code ConfigCodec.toToon(}, {@code JToon.encode(}) or a
 *       {@code ComponentStore} write ({@code store.write(TYPE|type|kind|"…", …)});</li>
 *   <li>the <b>funnel</b> — {@link SaveGate} ({@code SaveGate.check}/{@code SaveGate.introduced}), the content
 *       gate every pipeline-shaped save runs. ({@code ComponentStore.write} is the component writers' funnel
 *       by construction.)</li>
 * </ul>
 * A TOON-writing route must reach {@code SaveGate} or sit on {@link #NO_SAVE_GATE} with the reason SaveGate has
 * no arm for what it writes.
 *
 * <p>⚠ <b>Scope, stated so it is not mistaken for more:</b> the closure stops at the file boundary, so a route
 * that delegates its write to ANOTHER class is invisible here (the settings documents, whose records write
 * themselves, are the known case — they are Space preferences, not config the engine runs). The guard's
 * promise is narrower and mechanical: a new route that writes config IN ITS OWN MODULE without the funnels
 * goes red.
 */
class ConfigWriteFunnelTest {

    /** method( "pattern", — the MUTATING shape CapabilityManifestTest counts. */
    private static final Pattern SITE = Pattern.compile("api\\.(post|put|patch|delete)\\(\\s*\"([^\"]+)\"\\s*,");

    private static final Pattern TOON_WRITE = Pattern.compile("ConfigCodec\\.toToon\\(|JToon\\.encode\\(");
    private static final Pattern COMPONENT_WRITE = Pattern.compile(
            "\\.write\\(\\s*(TYPE|type|kind|KIND|[A-Z_]+_TYPE|\"[a-z-]+\")\\s*,");
    private static final Pattern SAVE_GATE = Pattern.compile("SaveGate\\.(check|introduced)\\(");
    private static final Pattern HOLD = Pattern.compile("PendingChanges\\.hold\\w*\\(");

    private static final String TAGS = "the Tag catalog / Tag Rules of the operational-object layer "
            + "(inspecto-ops) — ConfigSpecs has no tag type";
    private static final String CONNECTIONS = "Connection CRUD is secret-aware (masking, secret references) — "
            + "ConfigSpecs has no connection type and ComponentStore excludes it for the same reason";
    private static final String JOBS = "JobRoutes runs its own job gate (the job spec + ConfigSafetyValidator.checkJob, "
            + "the two checks SaveGate's job arm runs) rather than the whole SaveGate list";

    /**
     * TOON-writing routes that deliberately do NOT run {@link SaveGate}, each with its reason — they write a
     * kind SaveGate has no arm for. Keyed {@code "METHOD pattern"}. A stale row (the route no longer writes TOON,
     * or no longer exists) fails too.
     */
    static final Map<String, String> NO_SAVE_GATE = new TreeMap<>(Map.ofEntries(
            Map.entry("POST /tags", TAGS), Map.entry("POST /tags/([^/]+)/rename", TAGS),
            Map.entry("DELETE /tags/([^/]+)", TAGS), Map.entry("POST /tags/rules", TAGS),
            Map.entry("POST /cases/rules", "a Case Rule of the operational-object layer (inspecto-ops) — "
                    + "ConfigSpecs has no case-rule type"),
            Map.entry("POST /connections", CONNECTIONS), Map.entry("PUT /connections/([^/]+)", CONNECTIONS),
            Map.entry("POST /jobs", JOBS), Map.entry("PUT /jobs/([^/]+)", JOBS),
            Map.entry("POST /jobs/([^/]+)/enable", JOBS), Map.entry("POST /jobs/([^/]+)/disable", JOBS),
            Map.entry("POST /jobs/([^/]+)/reschedule", JOBS),
            Map.entry("POST /bundle/import", "a bulk import of components (each through ComponentRoutes.validateKind "
                    + "and ComponentStore), jobs and enrichments — never a pipeline-shaped save: Pipelines import "
                    + "through POST /pipelines/import, which runs SaveGate")
    ));
    record Verdict(String route, boolean toon, boolean component, boolean saveGate, boolean hold, Path file) {}

    @Test
    void everyConfigWritingRoutePassesThroughTheFunnelsOrIsExempt() throws IOException {
        List<Verdict> verdicts = scan();
        assertFalse(verdicts.isEmpty(), "no mutating registration sites found — scan broken?");
        assertTrue(verdicts.stream().anyMatch(Verdict::toon) && verdicts.stream().anyMatch(Verdict::component),
                "the scan recognised no config write at all — the write signals have drifted from the code");

        Set<String> bypassing = new LinkedHashSet<>();
        Set<String> toonWriters = new LinkedHashSet<>();
        for (Verdict v : verdicts) {
            if (!v.toon()) continue;
            toonWriters.add(v.route());
            if (!v.saveGate() && !NO_SAVE_GATE.containsKey(v.route()))
                bypassing.add(v.route() + "  [" + v.file().getFileName() + ", no SaveGate]");
        }
        assertTrue(bypassing.isEmpty(), () -> "config-writing routes that bypass the funnels — route the write "
                + "through SaveGate, or add a justified row to NO_SAVE_GATE:\n  " + String.join("\n  ", bypassing));

        Set<String> stale = new LinkedHashSet<>(NO_SAVE_GATE.keySet());
        stale.removeAll(toonWriters);
        assertTrue(stale.isEmpty(), () -> "NO_SAVE_GATE rows that no longer name a TOON-writing route: " + stale);
    }

    /** The four S0 routes by name, so "the scan went blind to them" cannot pass as "they are fine". */
    @Test
    void theFourPipelineEditsAreSeenAsConfigWritersAndPassSaveGate() throws IOException {
        Map<String, Verdict> byRoute = new LinkedHashMap<>();
        for (Verdict v : scan()) byRoute.put(v.route(), v);
        for (String r : List.of("POST /pipelines/([^/]+)/label", "POST /pipelines/([^/]+)/settings",
                "POST /pipelines/([^/]+)/save-as-template", "POST /pipelines/([^/]+)/rename")) {
            Verdict v = byRoute.get(r);
            assertTrue(v != null && v.toon(), () -> r + " is not recognised as a config write: " + v);
            assertTrue(v.saveGate(), () -> r + " does not pass through SaveGate: " + v);
        }
    }

    // ── the scan ──────────────────────────────────────────────────────────────────────────────────

    static List<Verdict> scan() throws IOException {
        List<Verdict> out = new java.util.ArrayList<>();
        Path reactor = Path.of("..").toAbsolutePath().normalize();
        try (Stream<Path> siblings = Files.list(reactor)) {
            for (Path sibling : siblings.filter(Files::isDirectory).sorted().toList()) {
                Path src = sibling.resolve(Path.of("src", "main", "java"));
                if (!Files.isDirectory(src)) continue;
                try (Stream<Path> files = Files.walk(src)) {
                    for (Path f : files.filter(p -> p.toString().endsWith(".java")).sorted().toList())
                        scanFile(f, out);
                }
            }
        }
        return out;
    }

    private static void scanFile(Path f, List<Verdict> out) throws IOException {
        String text = Files.readString(f);
        Matcher site = SITE.matcher(text);
        if (!site.find()) return;
        Map<String, String> methods = methodBodies(text);
        site.reset();
        while (site.find()) {
            String handler = argumentTail(text, site.end());
            String closure = closure(handler, methods);
            out.add(new Verdict(site.group(1).toUpperCase(Locale.ROOT) + " " + site.group(2),
                    TOON_WRITE.matcher(closure).find(), COMPONENT_WRITE.matcher(closure).find(),
                    SAVE_GATE.matcher(closure).find(), HOLD.matcher(closure).find(), f));
        }
    }

    /** The rest of the registration call's argument list, from {@code start} to its closing parenthesis. */
    private static String argumentTail(String text, int start) {
        int depth = 1;
        for (int i = start; i < text.length(); i = skip(text, i) + 1) {
            char c = text.charAt(i);
            if (c == '(') depth++;
            else if (c == ')' && --depth == 0) return text.substring(start, i);
        }
        return text.substring(start);
    }

    private static final Pattern CALL = Pattern.compile("\\b([a-zA-Z_]\\w*)\\s*\\(");

    /** {@code seed} plus the bodies of every same-file method it reaches, transitively. */
    private static String closure(String seed, Map<String, String> methods) {
        StringBuilder sb = new StringBuilder(seed);
        Set<String> seen = new java.util.HashSet<>();
        Deque<String> work = new ArrayDeque<>(List.of(seed));
        while (!work.isEmpty()) {
            Matcher m = CALL.matcher(work.pop());
            while (m.find()) {
                String name = m.group(1);
                String body = methods.get(name);
                if (body != null && seen.add(name)) {
                    sb.append('\n').append(body);
                    work.push(body);
                }
            }
        }
        return sb.toString();
    }

    private static final Pattern METHOD = Pattern.compile(
            "(?m)^[ \\t]*(?:(?:public|private|protected|static|final|synchronized|default)\\s+)*"
                    + "(?:<[^>]+>\\s+)?[\\w.<>\\[\\],? ]+?\\s+(\\w+)\\s*\\([^;{}]*\\)\\s*(?:throws\\s+[\\w.,\\s]+)?\\{");
    private static final Set<String> KEYWORDS = Set.of("if", "for", "while", "switch", "catch", "synchronized",
            "return", "new", "else", "try", "do");

    /** Method name → body (overloads concatenated), brace-matched over code only. */
    private static Map<String, String> methodBodies(String text) {
        Map<String, String> out = new LinkedHashMap<>();
        Matcher m = METHOD.matcher(text);
        while (m.find()) {
            String name = m.group(1);
            if (KEYWORDS.contains(name)) continue;
            int open = m.end() - 1, depth = 0, end = text.length();
            for (int i = open; i < text.length(); i = skip(text, i) + 1) {
                char c = text.charAt(i);
                if (c == '{') depth++;
                else if (c == '}' && --depth == 0) { end = i; break; }
            }
            out.merge(name, text.substring(open, end), (a, b) -> a + "\n" + b);
        }
        return out;
    }

    /** Index of the last character of the token at {@code i} when it opens a string, char or comment. */
    private static int skip(String t, int i) {
        char c = t.charAt(i);
        if (c == '"') {
            if (t.startsWith("\"\"\"", i)) {
                int e = t.indexOf("\"\"\"", i + 3);
                return e < 0 ? t.length() - 1 : e + 2;
            }
            for (int j = i + 1; j < t.length(); j++) {
                if (t.charAt(j) == '\\') j++;
                else if (t.charAt(j) == '"') return j;
            }
        } else if (c == '\'') {
            for (int j = i + 1; j < t.length(); j++) {
                if (t.charAt(j) == '\\') j++;
                else if (t.charAt(j) == '\'') return j;
            }
        } else if (c == '/' && i + 1 < t.length() && t.charAt(i + 1) == '/') {
            int e = t.indexOf('\n', i);
            return e < 0 ? t.length() - 1 : e;
        } else if (c == '/' && i + 1 < t.length() && t.charAt(i + 1) == '*') {
            int e = t.indexOf("*/", i + 2);
            return e < 0 ? t.length() - 1 : e + 1;
        }
        return i;
    }

    /** Census helper for a human: {@code -Dfunnel.census=true} prints every writer's verdict. */
    @Test
    void census() throws IOException {
        if (!Boolean.getBoolean("funnel.census")) return;
        for (Verdict v : scan())
            if (v.toon() || v.component())
                System.out.println("[FUNNEL] " + v.route() + " toon=" + v.toon() + " comp=" + v.component()
                        + " saveGate=" + v.saveGate() + " hold=" + v.hold() + " " + v.file().getFileName());
    }
}
