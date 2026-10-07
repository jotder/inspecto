package com.gamma.pipeline;

import com.gamma.config.io.ConfigCodec;
import com.gamma.enrich.EnrichmentConfig;
import com.gamma.query.MeasureCompiler;
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

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The Phase-3 parity gate (S4), representation half: <b>parity of representation against the real
 * artifacts</b> the verbs claim compatibility with. (The execution half — the compiled verbs RUN
 * at rest and produce the same values as the legacy runtimes — is
 * {@code com.gamma.job.RecipeExecutionParityTest}, P3 S3d; it became possible when the A5 at-rest
 * path started executing join/summarize, 2026-08-11.)
 *
 * <ol>
 *   <li>Every {@code references} entry of every real {@code *_enrich.toon} in the repo corpus is
 *       expressible as a {@code transform.join} step whose compiled {@code processing.join.reference}
 *       carries the source spelling verbatim — the D-4 claim that the recipe join speaks
 *       {@code EnrichmentConfig}'s reference vocabulary.</li>
 *   <li>{@code summarize.measures} strings are accepted by {@code MeasureCompiler} through the same
 *       {@code count | agg(field)} shorthand split {@code MaterializeTask.compileSpec} applies — the
 *       S1 claim that a future wiring slice is byte-compatible with the {@code materialize}
 *       maintenance-task grammar.</li>
 * </ol>
 *
 * <p>The third leg of the gate needs no test here: {@code orders_enriched_rollup_pipeline.toon}
 * (an inactive draft carrying dedup + join + summarize) now sits in the walked {@code spaces/}
 * corpus, so {@link RecipeConverterTest}'s every-fixture round trip covers the new sections over a
 * real on-disk file permanently.
 */
class RecipeVerbParityTest {

    private static Path spacesRoot() {
        return Path.of("..", "spaces").toAbsolutePath().normalize();
    }

    private static List<Path> fixtures(String suffix) throws IOException {
        Path root = spacesRoot();
        if (!Files.isDirectory(root)) return List.of();
        try (Stream<Path> all = Files.walk(root)) {
            return all.filter(p -> p.getFileName().toString().endsWith(suffix)).sorted().toList();
        }
    }

    /** The D-4 compatibility claim, gated over the real corpus rather than a synthetic shape. */
    @Test
    @SuppressWarnings("unchecked")
    void everyRealEnrichmentReferenceIsExpressibleAsATransformJoin() throws Exception {
        List<Path> enrichments = fixtures("_enrich.toon");
        assumeTrue(!enrichments.isEmpty(), "no spaces/ tree next to the module — nothing to gate");

        List<String> failures = new ArrayList<>();
        int referencesSeen = 0;
        for (Path f : enrichments) {
            Map<String, Object> raw = ConfigCodec.toMap(Files.readString(f, StandardCharsets.UTF_8));
            EnrichmentConfig ec = EnrichmentConfig.fromMap(raw, null);
            for (EnrichmentConfig.Reference ref : ec.references()) {
                referencesSeen++;
                String source = ref.path() != null ? ref.path() : "references/" + ref.ref();
                Map<String, Object> recipe = new LinkedHashMap<>();
                recipe.put("name", "parity");
                recipe.put("active", false);
                recipe.put("steps", List.of(
                        step("collect", new LinkedHashMap<>()),
                        step("parse", new LinkedHashMap<>()),
                        step("transform", new LinkedHashMap<>(Map.of("join", source, "on", "ID")))));
                try {
                    Map<String, Object> out = RecipeCompiler.compile(recipe, Map.of(), false);
                    Object landed = ((Map<String, Object>) out.get("processing")).get("join");
                    String expected = ref.path() != null ? ref.path() : "reference/" + ref.ref();
                    if (!(landed instanceof Map<?, ?> jn) || !expected.equals(jn.get("reference")))
                        failures.add(f + " [" + ref.name() + "]: compiled to " + landed
                                + ", expected reference " + expected);
                } catch (RuntimeException e) {
                    failures.add(f + " [" + ref.name() + "]: " + e.getMessage());
                }
            }
        }
        assumeTrue(referencesSeen > 0, "corpus has no references-bearing enrichment — nothing to gate");
        assertTrue(failures.isEmpty(),
                failures.size() + " enrichment reference(s) the join verb cannot speak:\n"
                        + String.join("\n", failures));
    }

    /**
     * The S1 byte-compatibility claim: the fixture's {@code summarize.measures} strings, split by
     * the exact {@code count | agg(field)} contract {@code MaterializeTask.compileSpec} documents,
     * are accepted by {@code MeasureCompiler} and compile to the expected aggregate SQL.
     */
    @Test
    void theDraftFixturesSummarizeMeasuresSpeakTheMaterializeGrammar() throws Exception {
        Path fixture = spacesRoot().resolve("demo/config/orders/orders_enriched_rollup_pipeline.toon");
        assumeTrue(Files.isRegularFile(fixture), "no spaces/ tree next to the module — nothing to gate");

        Map<String, Object> raw = ConfigCodec.toMap(Files.readString(fixture, StandardCharsets.UTF_8));
        @SuppressWarnings("unchecked")
        Map<String, Object> summarize = (Map<String, Object>)
                ((Map<String, Object>) raw.get("processing")).get("summarize");
        @SuppressWarnings("unchecked")
        List<String> measureStrings = (List<String>) summarize.get("measures");
        @SuppressWarnings("unchecked")
        List<String> groupBy = (List<String>) summarize.get("group_by");
        assertFalse(measureStrings.isEmpty(), "harness precondition: the fixture declares measures");

        // MaterializeTask.compileSpec's documented shorthand: count | agg(field) — split identically.
        List<Map<String, Object>> measures = new ArrayList<>();
        for (String m : measureStrings) {
            if ("count".equals(m)) { measures.add(Map.of("agg", "count")); continue; }
            int p = m.indexOf('(');
            assertTrue(p > 0 && m.endsWith(")"),
                    "'" + m + "' is outside the materialize grammar (count or agg(field)) — "
                            + "the S1 byte-compatibility claim broke");
            measures.add(Map.of("agg", m.substring(0, p), "field", m.substring(p + 1, m.length() - 1)));
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("dataset", "orders_rollup_draft");
        body.put("measures", measures);
        body.put("groupBy", groupBy);

        String sql = MeasureCompiler.compile(MeasureCompiler.parse(body, 1000, 100_000));
        assertTrue(sql.contains("COUNT(*)"), sql);
        assertTrue(sql.contains("SUM(\"GROSS\")"), sql);
        assertTrue(sql.contains("GROUP BY \"REGION\""), sql);
    }

    private static Map<String, Object> step(String verb, Map<String, Object> cfg) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put(verb, cfg);
        return m;
    }
}
