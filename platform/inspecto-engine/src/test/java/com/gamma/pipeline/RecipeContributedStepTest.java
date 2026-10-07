package com.gamma.pipeline;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Stage 2 S2-5 (D-5): a pack-contributed kind is spelled by the generic {@code - step: {kind: …}} verb,
 * round-trips recipe → flat {@code steps:} → recipe, and a built-in, unknown or kind-less step is refused.
 * (D-6, no catalog entry, is pinned by {@code ProcessorCatalogContractTest}, which stays unchanged.)
 */
class RecipeContributedStepTest {

    private static final String OWNER = "recipe-contributed-step-test";

    @BeforeEach
    void register() {
        PipelineNodeTypes.register(new PipelineNodeType() {
            @Override public String type() { return "transform.acme_score"; }
            @Override public NodeCategory category() { return NodeCategory.TRANSFORM; }
            @Override public Optional<ExecutionMode> mode() { return Optional.of(ExecutionMode.EXECUTED); }
        }, OWNER);
    }

    @AfterEach
    void unregister() {
        PipelineNodeTypes.deregister(OWNER);
    }

    private static Map<String, Object> step(String verb, Map<String, Object> cfg) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put(verb, cfg);
        return m;
    }

    private static Map<String, Object> recipeWith(Map<String, Object> middle) {
        Map<String, Object> recipe = new LinkedHashMap<>();
        recipe.put("name", "orders");
        recipe.put("trigger", Map.of("poll", "60s"));
        recipe.put("steps", new ArrayList<>(List.of(
                step("collect", new LinkedHashMap<>(Map.of("connection", "connections/sftp_prod", "files", "glob:**/*.csv"))),
                step("parse", new LinkedHashMap<>(Map.of("grammar", "grammars/delimited_pipe"))),
                middle,
                step("sink", new LinkedHashMap<>(Map.of("table", "orders", "format", "PARQUET", "database", "/data/db"))))));
        return recipe;
    }

    @Test
    @SuppressWarnings("unchecked")
    void aContributedKindCompilesToAStepsEntryAndRoundTrips() {
        Map<String, Object> cfg = new LinkedHashMap<>();
        cfg.put("kind", "acme_score");
        cfg.put("threshold", 5);
        Map<String, Object> recipe = recipeWith(step("step", cfg));

        Map<String, Object> flat = RecipeCompiler.compile(recipe, Map.of(), false);
        List<Map<String, Object>> chain = (List<Map<String, Object>>) flat.get("steps");
        assertNotNull(chain, "a contributed kind forces the steps: spelling");
        Map<String, Object> entry = chain.stream().filter(m -> m.containsKey("acme_score")).findFirst().orElseThrow();
        assertEquals(5, ((Map<String, Object>) entry.get("acme_score")).get("threshold"));
        assertFalse(((Map<String, Object>) entry.get("acme_score")).containsKey("kind"), "kind is the key, not config");

        Map<String, Object> back = RecipeConverter.toRecipe(flat);
        List<Object> steps = (List<Object>) back.get("steps");
        Map<String, Object> spelled = steps.stream().map(o -> (Map<String, Object>) o)
                .filter(m -> m.containsKey("step")).findFirst().orElseThrow(
                        () -> new AssertionError("the converter must emit the generic verb, got " + steps));
        Map<String, Object> inner = (Map<String, Object>) spelled.get("step");
        assertEquals("acme_score", inner.get("kind"));
        assertEquals(5, inner.get("threshold"));
    }

    @Test
    void aBuiltInOrUnknownOrKindlessStepIsRefused() {
        for (Map<String, Object> bad : List.of(
                new LinkedHashMap<String, Object>(Map.of("kind", "dedup")),
                new LinkedHashMap<String, Object>(Map.of("kind", "not_loaded")),
                new LinkedHashMap<String, Object>(Map.of("threshold", 1)))) {
            Map<String, Object> recipe = recipeWith(step("step", bad));
            PipelineCompileException e = assertThrows(PipelineCompileException.class,
                    () -> RecipeCompiler.compile(recipe, Map.of(), false), bad.toString());
            assertFalse(e.refusals().isEmpty());
        }
    }
}
