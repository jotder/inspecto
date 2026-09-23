package com.gamma.pipeline;

import com.gamma.config.io.ConfigCodec;
import com.gamma.etl.PipelineConfig;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Unification W0 (BACKLOG, "prove the lift→lower round trip lossless over the existing configs or NAME the
 * supported subset"): for every authored {@code *_pipeline.toon} in the repo's {@code spaces/} tree,
 * {@code lower(lift(cfg), original, lenient)} must reproduce the decoded original exactly (modulo the
 * always-written {@code active} default) — the editor's own open→save round trip, with no edit in between.
 *
 * <p>{@link RecipeConverterTest} gates the RECIPE projection over the same fixtures; this is the graph one,
 * and it follows {@link PipelineEditableTest#editableRoundTripIsVerbatim} exactly: {@link PipelineEditable#toMap}
 * over the loaded config AND its raw map (the editable shape {@code GET /pipelines/{name}/graph/raw} serves),
 * through the codec (plain maps only — a typed record leaking here is itself a defect), then a STRICT lower
 * over the original. ⚠ Not {@link PipelineLift#lift} alone: that lift materialises engine defaults and
 * typed records (a first cut of this test used it and every fixture "failed"), which is the projection's
 * business, not the editor's.
 */
class LiftLowerFixtureSweepTest {

    private static Path spacesRoot() {
        return Path.of("..", "spaces").toAbsolutePath().normalize();
    }

    private static List<Path> pipelineFixtures() throws IOException {
        Path root = spacesRoot();
        if (!Files.isDirectory(root)) return List.of();
        try (Stream<Path> all = Files.walk(root)) {
            return all.filter(p -> p.getFileName().toString().endsWith("_pipeline.toon")).sorted().toList();
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> rebase(Map<String, Object> m, String repoRoot) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : m.entrySet()) out.put(e.getKey(), rebaseValue(e.getValue(), repoRoot));
        return out;
    }

    @SuppressWarnings("unchecked")
    private static Object rebaseValue(Object v, String repoRoot) {
        if (v instanceof String s && s.startsWith("spaces/")) return repoRoot + s;
        if (v instanceof Map<?, ?> mm) return rebase((Map<String, Object>) mm, repoRoot);
        if (v instanceof List<?> l) {
            List<Object> out = new ArrayList<>();
            for (Object o : l) out.add(rebaseValue(o, repoRoot));
            return out;
        }
        return v;
    }

    /**
     * Prefix {@code dir} onto every relative config ref the parser resolves beside its config:
     * {@code schema_file}, each {@code schemas[].schema_file}, {@code grammar}, and every {@code segments}
     * value — under {@code processing:} and under any {@code parsing.*} block.
     */
    @SuppressWarnings("unchecked")
    private static void spellOutConfigRefs(Map<String, Object> config, String dir) {
        if (config.get("processing") instanceof Map<?, ?> proc) spellOutRefsIn((Map<String, Object>) proc, dir);
        if (config.get("parsing") instanceof Map<?, ?> parsing) {
            spellOutRefsIn((Map<String, Object>) parsing, dir);
            for (Object block : parsing.values())
                if (block instanceof Map<?, ?> b) spellOutRefsIn((Map<String, Object>) b, dir);
        }
    }

    @SuppressWarnings("unchecked")
    private static void spellOutRefsIn(Map<String, Object> m, String dir) {
        for (String k : List.of("schema_file", "grammar"))
            if (m.get(k) instanceof String v && isRelativeFileRef(v)) m.put(k, dir + v);
        if (m.get("schemas") instanceof List<?> rows)
            for (Object row : rows)
                if (row instanceof Map<?, ?> r && r.get("schema_file") instanceof String v && isRelativeFileRef(v))
                    ((Map<String, Object>) r).put("schema_file", dir + v);
        if (m.get("segments") instanceof Map<?, ?> segs)
            for (Map.Entry<String, Object> e : ((Map<String, Object>) segs).entrySet())
                if (e.getValue() instanceof String v && isRelativeFileRef(v)) e.setValue(dir + v);
    }

    /** A relative path to a file — not an absolute path, not inline grammar text, not a registry id. */
    private static boolean isRelativeFileRef(String v) {
        return v.endsWith(".toon") && !Path.of(v).isAbsolute() && !v.contains(" ");
    }

    @Test
    void everyRepoFixtureSurvivesAnUneditedLiftLowerRoundTrip() throws Exception {
        List<Path> fixtures = pipelineFixtures();
        // ASSERTION, not an assumption (2026-09-07). The corpus is COMMITTED — `spaces/**` is in the
        // repo — so an empty sweep never means "nothing to gate", it means the walk stopped finding it:
        // a module move, a surefire CWD change, a renamed directory. `assumeTrue` turned that into a
        // silent green and the gate would be off with nobody told.
        assertFalse(fixtures.isEmpty(), "found NO *_pipeline.toon under spaces/ — the walk is broken, not the corpus; this sweep would prove nothing");

        // Fixture paths are repo-relative (`spaces/default/...`) and PipelineConfig loads the schema they
        // name, but a surefire JVM's CWD is the module dir. Rebase every such string onto the repo root
        // and compare against the rebased map: a path VALUE travels verbatim through lift/lower (node
        // config is the raw section), so its spelling cannot affect what the round trip proves.
        String repoRoot = spacesRoot().getParent().toString().replace('\\', '/') + "/";
        List<String> failures = new ArrayList<>();
        for (Path f : fixtures) {
            Map<String, Object> original = rebase(
                    ConfigCodec.toMap(Files.readString(f, StandardCharsets.UTF_8)), repoRoot);
            // Every satellite ref is a sibling name resolved beside the pipeline file at load
            // (SCHEMA-FILE-RESOLVES-AGAINST-CWD-1), and fromMap has no file — spell each one out here for
            // the same reason as the rebase above.
            spellOutConfigRefs(original, f.getParent().toString().replace('\\', '/') + "/");
            try {
                PipelineConfig cfg = PipelineConfig.fromMap(original);
                Map<String, Object> editable = PipelineEditable.toMap(cfg, original);
                PipelineGraph g = PipelineCodec.fromMap(editable);
                Map<String, Object> back = PipelineEditable.lower(g, original, true);

                Map<String, Object> expected = new LinkedHashMap<>(original);
                expected.putIfAbsent("active", back.get("active"));
                if (!expected.equals(back))
                    failures.add(f.getFileName() + ":\n  expected " + expected + "\n  got      " + back);
            } catch (RuntimeException | IOException e) {
                failures.add(f.getFileName() + ": " + e);
            }
        }
        assertTrue(failures.isEmpty(),
                failures.size() + " of " + fixtures.size() + " fixtures do not survive lift→lower:\n"
                        + String.join("\n", failures));
    }
}
