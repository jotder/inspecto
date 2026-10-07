package com.gamma.pipeline;

import com.gamma.config.spec.AcceptedConfigKeys;
import com.gamma.config.spec.Severity;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 🔴 <b>The hard constraint of `DUCKLE-C3-DEAD-PROPERTY-1`.</b>
 * {@code RecipeCompiler:254-257} explicitly REFUSED a blanket unknown-key refusal on {@code collect:}
 * because {@link RecipeConverter} legitimately round-trips arbitrary collector-block keys through it.
 * The dead-property census must therefore never make that refusal by the back door.
 *
 * <p>It does not, and the reason is structural rather than a carve-out: {@code collector} is an
 * ACCEPTED BLOCK, accepted <em>whole</em>, so every key inside it — specced, parser-only, or a
 * connector's own — is admitted without the checker ever descending. This test pins that, so a future
 * change to leaf granularity breaks here loudly instead of breaking every existing recipe round trip
 * (the AUTHOR-1 regression shape).
 */
class RecipeCollectRoundTripTest {

    /** A collector block whose keys span all three provenances the converter passes through verbatim. */
    private static Map<String, Object> configWithRichCollector() {
        Map<String, Object> collector = new LinkedHashMap<>();
        collector.put("connection", "sftp_prod");          // rides the ref spelling (COLLECT_SPECIAL)
        collector.put("include", "*.csv");                 // specced on the acquisition node
        collector.put("recursive_depth", 3);               // specced, advanced
        collector.put("guarantee", "AT_LEAST_ONCE");       // specced, advanced
        collector.put("post_action", new LinkedHashMap<>(Map.of("on_success", "RETAIN")));
        collector.put("stability", new LinkedHashMap<>(Map.of("window", "5s")));
        collector.put("fetch", new LinkedHashMap<>(Map.of("parallel_fetch", 4)));
        collector.put("consignment", new LinkedHashMap<>(Map.of("max_files", 10, "max_bytes", 1024)));
        collector.put("etag_header", "x-amz-meta-etag");   // a connector's own key — no spec anywhere

        Map<String, Object> config = new LinkedHashMap<>();
        config.put("name", "orders");
        config.put("active", false);
        config.put("collector", collector);
        config.put("dirs", new LinkedHashMap<>(Map.of("poll", "in", "database", "db")));
        config.put("processing", new LinkedHashMap<>(Map.of(
                "file_pattern", "glob:**/*.csv", "schema_file", "orders_schema.toon")));
        return config;
    }

    @Test
    void anArbitraryCollectorKeySurvivesTheFlatToRecipeToFlatRoundTrip() {
        Map<String, Object> config = configWithRichCollector();
        Map<String, Object> recipe = RecipeConverter.toRecipe(config);
        Map<String, Object> back = RecipeCompiler.compile(recipe, config, false);

        Object collectorBack = back.get("collector");
        assertTrue(collectorBack instanceof Map<?, ?>, "the round trip lost the collector block entirely");
        Map<?, ?> collector = (Map<?, ?>) collectorBack;
        assertEquals("x-amz-meta-etag", collector.get("etag_header"),
                "an unmodelled collector key must survive collect: verbatim — the refusal "
                        + "RecipeCompiler:254-257 exists to prevent");
        assertEquals("AT_LEAST_ONCE", collector.get("guarantee"));
        assertEquals("sftp_prod", collector.get("connection"));
    }

    /**
     * The census's half of the same contract: nothing in a rich collector block — nor anything the
     * round trip produces from it — may be reported as a key no component reads.
     */
    @Test
    void theDeadPropertyCensusFlagsNothingInARoundTrippedConfig() {
        Map<String, Object> config = configWithRichCollector();
        assertTrue(AcceptedConfigKeys.unknownKeyFindings("pipeline", config, Severity.ERROR).isEmpty(),
                "the census flagged a collector key the engine reads");

        Map<String, Object> back = RecipeCompiler.compile(RecipeConverter.toRecipe(config), config, false);
        assertTrue(AcceptedConfigKeys.unknownKeyFindings("pipeline", back, Severity.ERROR).isEmpty(),
                "the census flagged a block the round trip produced: "
                        + AcceptedConfigKeys.unknownKeyFindings("pipeline", back, Severity.ERROR));
    }
}
