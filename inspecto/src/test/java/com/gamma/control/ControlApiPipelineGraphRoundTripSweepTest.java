package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.service.CollectorService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Every shipped Pipeline survives a round trip through the REAL HTTP write path — {@code WB-01} of
 * {@code docs/superpower/workbench-trust-plan.md}, the guard the workbench's two standing properties
 * never had.
 *
 * <p><b>What this adds over {@code LiftLowerFixtureSweepTest}.</b> That sweep proves the same corpus
 * survives {@code PipelineEditable.toMap} → {@code PipelineCodec.fromMap} → {@code lower} <em>in
 * process</em>. It never boots the control plane, so it cannot see what the write ROUTE adds on top:
 * write-route defaults, {@code active} coercion, findings-driven rewrites, the spec + safety gate, and
 * the atomic write itself. Those are exactly the layers a workbench save goes through and the in-process
 * sweep does not. This test drives {@code GET …/graph/raw} → {@code PUT …/graph} → re-{@code GET}, over
 * real HTTP, for every fixture in {@code spaces/}.
 *
 * <p><b>What it asserts</b>, per fixture: the PUT reports {@code written:true}, and the re-read editable
 * graph is <em>identical</em> to the first read. Identity is the strong form of the two properties the
 * corpus already has (key set preserved, node configs preserved) — a writer that started dropping a key,
 * coercing a value or reordering a node would fail here and nowhere else.
 *
 * <p>✅ <b>The pin is EMPTY, and that is a result.</b> When this guard first ran (2026-09-22) it pinned
 * {@code asn1_example} as a known refusal: the arming gate did not count {@code parsing.asn1.segments}
 * as a schema while {@code POST /validate} did, so a shipped, active, working ASN.1 Pipeline could be
 * opened and never saved ({@code SAVE-GATE-VS-VALIDATE-DISAGREE-1}, 422
 * {@code ERR_ARMED_WITHOUT_SCHEMA}). {@code WB-03} fixed it the same day — ONE predicate,
 * {@code ConfigRoutes.hasSchemaSource}, called by the write gate and by {@code POST /validate} alike —
 * and the pin was dropped in that same change, which is what turned this guard from red back to green.
 * ⚠ Keep the map: the next known-and-decided refusal goes here WITH its reason and its work item, never
 * as a quietly skipped fixture. ⛔ A guard whose known-broken list silently absorbs a fix proves nothing.
 *
 * <p><b>Why the fixtures are copied and rewritten rather than driven in place.</b> Two reasons, and
 * neither is incidental:
 * <ul>
 *   <li><b>The write path WRITES.</b> Driving the committed {@code spaces/} tree would rewrite tracked
 *       fixtures on every test run — the repository's own files as test scratch.</li>
 *   <li><b>Fixture DATA paths are repo-relative and surefire's CWD is the module directory</b>, so
 *       {@code dirs.poll: spaces/default/…} resolves to {@code inspecto/spaces/default/…}. Rewriting the
 *       {@code spaces/} prefix to the absolute temp copy keeps every run out of the module dir. (Satellite
 *       refs no longer need it — they are sibling names resolved beside their config since
 *       {@code SCHEMA-FILE-RESOLVES-AGAINST-CWD-1} — but the whole space is still copied so they resolve.)</li>
 * </ul>
 * ⚠ The rewrite cannot mask a round-trip loss: both reads come from the SAME rewritten file, so anything
 * the save drops is dropped between two reads that were always going to agree otherwise.
 */
class ControlApiPipelineGraphRoundTripSweepTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private final HttpClient client = HttpClient.newHttpClient();

    /**
     * Fixtures the save route refuses today, with the reason. Each is a KNOWN defect with a signed
     * decision and a work item — never a fixture that is simply awkward to test.
     */
    private static final Map<String, String> REFUSES_UNTIL_WB_03 = Map.of();

    /** The repo's {@code spaces/} tree, relative to the surefire CWD (the {@code inspecto} module dir). */
    private static Path spacesRoot() {
        return Path.of("..", "spaces");
    }

    /**
     * Rewrite one staged line, quoting the new path when the line is a TOON <em>array row</em>.
     *
     * <p>🔴 A staged path is absolute, so on Windows it begins {@code C:/}. In a {@code key: value} line
     * that is harmless; in an array row — {@code sinks[2]{database,format}:} with rows like
     * {@code <path>,CSV} — the leading {@code C:} parses as a KEY, the rows stop being rows, and the array
     * fails to load with {@code "Array length mismatch: declared 2, found 0"}. {@code route_step} is the
     * only shipped fixture with a path inside an array row, so it was the only one to break — in this
     * harness, not in the product. Quoting the field is what the TOON writer would do.
     *
     * <p>⚠ Staging relative to {@code target/} was tried first and REJECTED: it removes the drive letter,
     * but five at-rest Step Pipelines then refused with *"must declare a top-level {@code output_store}"*
     * for reasons that were not established. An unexplained failure inside a guard is worse than the
     * problem it fixes.
     */
    private static String rewriteLine(String line, String from, String to) {
        if (!line.contains(from)) return line;
        String rewritten = line.replace(from, to);
        boolean keyed = line.stripLeading().matches("^[A-Za-z_][A-Za-z0-9_]*\\s*:.*");
        if (keyed || rewritten.contains("\"" + to)) return rewritten;
        // an array row: quote the path field, which runs to the next comma
        int at = rewritten.indexOf(to);
        int end = rewritten.indexOf(',', at);
        if (end < 0) end = rewritten.length();
        return rewritten.substring(0, at) + '"' + rewritten.substring(at, end) + '"' + rewritten.substring(end);
    }

    /**
     * Every shipped Pipeline config.
     *
     * <p>⛔ {@code _templates/} is excluded: a starter template carries {@code ${SPACE}} placeholders and a
     * bare {@code schema_file}, so it is a source for scaffolding rather than a loadable Pipeline. It is
     * not "a fixture that fails" — it is not a fixture.
     */
    private static List<Path> fixtures() throws IOException {
        try (Stream<Path> walk = Files.walk(spacesRoot())) {
            return walk.filter(p -> p.getFileName().toString().endsWith("_pipeline.toon"))
                    .filter(p -> !p.toString().replace('\\', '/').contains("/_templates/"))
                    .sorted()
                    .toList();
        }
    }

    @Test
    void everyShippedPipelineSurvivesTheHttpWritePathByteForByteInItsDecodedForm(@TempDir Path tmp)
            throws Exception {
        List<Path> fixtures = fixtures();
        assertFalse(fixtures.isEmpty(),
                "found no *_pipeline.toon under " + spacesRoot().toAbsolutePath()
                        + " \u2014 a sweep over nothing passes vacuously, which is the one way this guard could lie");

        // Stage every fixture first, then boot ONCE over all of them (see class javadoc).
        List<Path> staged = new ArrayList<>();
        Map<Path, String> idOf = new LinkedHashMap<>();
        int n = 0;
        for (Path fixture : fixtures) {
            Path copy = stage(fixture, tmp.resolve("f" + (++n)));
            staged.add(copy);
            idOf.put(copy, fixture.getFileName().toString().replace("_pipeline.toon", ""));
        }

        List<String> failures = new ArrayList<>();
        List<String> census = new ArrayList<>();
        int refused = 0;

        CollectorService svc = new CollectorService(staged, 3600, 1);
        String priorRoots = System.getProperty("assist.safety.roots");
        String priorWrite = System.getProperty("assist.write.root");
        System.setProperty("assist.safety.roots", tmp.toAbsolutePath().toString());
        System.setProperty("assist.write.root", tmp.toAbsolutePath().toString());
        ControlApi api = null;
        try {
            api = new ControlApi(svc, 0);
            api.start();
            int port = api.port();

            JsonNode listed = json(send(port, "GET", "/pipelines", null));
            assertTrue(listed.isArray() && !listed.isEmpty(),
                    "the staged copies registered no pipeline at all \u2014 GET /pipelines served: " + listed);

            for (JsonNode p : listed) {
                String name = p.get("name").asText();
                int before = failures.size();
                if (roundTrip(port, name, failures)) {
                    refused++;
                    census.add(name + " \u2192 REFUSED (pinned)");
                } else {
                    census.add(name + (failures.size() > before ? " \u2192 FAILED" : " \u2192 saved losslessly"));
                }
            }
            assertEquals(fixtures.size(), listed.size(),
                    "every staged fixture must register, or the sweep silently covers fewer than it claims;"
                            + " registered:\n  - " + String.join("\n  - ", census));
        } finally {
            if (api != null) api.close();
            svc.close();
            restore("assist.write.root", priorWrite);
            restore("assist.safety.roots", priorRoots);
        }

        // \u26a0 The census goes FIRST. The pin is bookkeeping; what the write path DID is the finding, and a
        // run that reports only the bookkeeping is a run spent to learn nothing.
        if (!failures.isEmpty()) {
            fail("the workbench write path is not lossless for " + failures.size() + " of " + fixtures.size()
                    + " shipped Pipeline(s):\n  - " + String.join("\n  - ", failures)
                    + "\n\ncensus of all " + fixtures.size() + ":\n  - " + String.join("\n  - ", census));
        }
        assertEquals(REFUSES_UNTIL_WB_03.size(), refused,
                "the pinned-refusal list and the fixtures that actually refuse must match exactly; "
                        + "a fixture that started saving is its fix landing \u2014 drop it from REFUSES_UNTIL_WB_03."
                        + "\ncensus of all " + fixtures.size() + ":\n  - " + String.join("\n  - ", census));
    }

    private static void restore(String key, String prior) {
        if (prior != null) System.setProperty(key, prior);
        else System.clearProperty(key);
    }

    /** Drive one registered pipeline. Returns true when the save was refused AND that refusal is pinned. */
    private boolean roundTrip(int port, String name, List<String> failures) throws Exception {
        HttpResponse<String> first = send(port, "GET", "/pipelines/" + name + "/graph/raw", null);
        if (first.statusCode() != 200) {
            failures.add(name + ": GET .../graph/raw answered " + first.statusCode() + " \u2014 " + brief(first));
            return false;
        }
        JsonNode before = json(first);

        HttpResponse<String> put =
                send(port, "PUT", "/pipelines/" + name + "/graph", JSON.writeValueAsString(before));

        if (put.statusCode() != 200) {
            if (REFUSES_UNTIL_WB_03.containsKey(name)) return true;   // known, decided \u2014 counted, not failed
            failures.add(name + ": PUT .../graph answered " + put.statusCode()
                    + " for the body its own GET .../graph/raw served \u2014 " + brief(put));
            return false;
        }
        if (REFUSES_UNTIL_WB_03.containsKey(name)) {
            failures.add(name + ": is pinned as refusing but SAVED \u2014 if its fix landed, drop it from "
                    + "REFUSES_UNTIL_WB_03 in that change; the pin exists to make the fix visible");
            return false;
        }

        JsonNode wrote = json(put);
        if (!wrote.path("written").asBoolean(false)) {
            failures.add(name + ": PUT answered 200 but written:false \u2014 " + brief(put));
            return false;
        }

        HttpResponse<String> second = send(port, "GET", "/pipelines/" + name + "/graph/raw", null);
        if (second.statusCode() != 200) {
            failures.add(name + ": the re-read after a successful save answered " + second.statusCode());
            return false;
        }
        JsonNode after = json(second);
        if (!before.equals(after)) {
            failures.add(name + ": the editable graph changed across a save that reported written:true \u2014 "
                    + firstDifference(before, after));
        }
        return false;
    }

    /**
     * Copy the fixture's whole space {@code config/} subtree into {@code work} and re-point its
     * repo-relative {@code spaces/…} paths at the copy, then create every directory the config names.
     *
     * @return the staged copy of {@code fixture}
     */
    private Path stage(Path fixture, Path work) throws IOException {
        Path spaceDir = fixture.getParent();
        while (spaceDir != null && !spaceDir.getFileName().toString().equals("config")) {
            spaceDir = spaceDir.getParent();
        }
        Path configRoot = spaceDir != null ? spaceDir : fixture.getParent();
        Path spaceRoot = configRoot.getParent();
        String spacePrefix = "spaces/" + spaceRoot.getFileName() + "/";
        String replacement = work.toAbsolutePath().toString().replace('\\', '/') + "/";

        Path staged = null;
        try (Stream<Path> walk = Files.walk(configRoot)) {
            for (Path src : walk.filter(Files::isRegularFile).toList()) {
                // ⚠ Relativize from the SPACE root, not the config root: the path rewrite below maps
                // `spaces/<space>/` to `work/`, so the copy must keep the `config/` component or every
                // schema_file/grammar reference lands one directory too high and the config silently
                // fails to load ("Schema file not found") — which reads as 26 broken fixtures.
                Path dest = work.resolve(spaceRoot.relativize(src).toString());
                Files.createDirectories(dest.getParent());
                if (src.toString().endsWith(".toon")) {
                    StringBuilder rewritten = new StringBuilder();
                    for (String line : Files.readString(src, StandardCharsets.UTF_8).split("\n", -1)) {
                        if (rewritten.length() > 0) rewritten.append('\n');
                        rewritten.append(rewriteLine(line, spacePrefix, replacement));
                    }
                    String text = rewritten.toString();
                    Files.writeString(dest, text, StandardCharsets.UTF_8);
                    createNamedDirectories(text, work);
                } else {
                    Files.copy(src, dest);
                }
                if (src.equals(fixture)) staged = dest;
            }
        }
        if (staged == null) throw new IOException("staging lost the fixture itself: " + fixture);
        return staged;
    }

    /**
     * Create every directory the staged config names under {@code work}.
     *
     * <p>⚠ {@code PipelineConfig.prepare()} creates only the status dir; every other {@code dirs.*} leaf
     * must exist before the service boots. This is the same fact {@code tools/seed-samples.mjs} exists
     * for, applied to a throwaway copy.
     */
    private void createNamedDirectories(String toon, Path work) throws IOException {
        String root = work.toAbsolutePath().toString().replace('\\', '/');
        for (String line : toon.split("\\R")) {
            int colon = line.indexOf(':');
            if (colon < 0) continue;
            String value = line.substring(colon + 1).trim().replace("\"", "");
            if (!value.startsWith(root)) continue;
            Path p = Path.of(value);
            Path dir = value.endsWith(".toon") || value.endsWith(".csv") ? p.getParent() : p;
            if (dir != null) Files.createDirectories(dir);
        }
    }

    private HttpResponse<String> send(int port, String method, String path, String body) throws Exception {
        HttpRequest.Builder b =
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1" + path));
        if (body != null) {
            b.header("Content-Type", "application/json").method(method, BodyPublishers.ofString(body));
        } else {
            b.method(method, BodyPublishers.noBody());
        }
        return client.send(b.build(), BodyHandlers.ofString());
    }

    private JsonNode json(HttpResponse<String> r) throws Exception {
        return V1Body.of(r.body());
    }

    private static String brief(HttpResponse<String> r) {
        String body = r.body() == null ? "" : r.body();
        return body.length() <= 400 ? body : body.substring(0, 400) + "…";
    }

    /** The first key whose value differs, so a failure names the loss instead of printing two graphs. */
    private static String firstDifference(JsonNode before, JsonNode after) {
        Map<String, String> diffs = new LinkedHashMap<>();
        before.fieldNames().forEachRemaining(f -> {
            if (!after.has(f)) diffs.put(f, "DROPPED by the save");
            else if (!before.get(f).equals(after.get(f))) diffs.put(f, "changed");
        });
        after.fieldNames().forEachRemaining(f -> {
            if (!before.has(f)) diffs.put(f, "ADDED by the save");
        });
        if (diffs.isEmpty()) return "the graphs differ but no top-level key does (nested ordering?)";
        return diffs.toString();
    }
}
