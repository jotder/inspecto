package com.gamma.pipeline;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The step-types contract (ELT amendment Phase 5, UI plan S4): the recipe-verb palette this server
 * publishes on {@code GET /pipelines/step-types} must equal the committed JSON the Angular mock serves
 * and the TS suite pins — the same one-artifact mechanism as {@link NodeAttributesContractTest}, and
 * for the same reason: neither side can drift without a suite failing, and a real divergence shows up
 * as a reviewable diff on the contract file.
 *
 * <p>Only the BUILTIN entries are pinned: plugin-contributed types vary by classpath, and this test
 * runs with none on it, so {@code stepCatalog()} here is exactly the verb table.
 *
 * <p>Regenerate deliberately with {@code -Dstep.types.write=true}, then check the TS side still agrees.
 */
class StepTypesContractTest {

    /** Shared with the TS side + the mock's `/pipelines/step-types`, so the offline preview cannot drift. */
    private static final String CONTRACT = "inspecto-ui/src/app/inspecto/mock/step-types.contract.json";

    private static final ObjectMapper JSON = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    private static Path contractPath() {
        Path dir = Path.of("").toAbsolutePath();
        for (int up = 0; up < 4 && dir != null; up++, dir = dir.getParent()) {
            Path candidate = dir.resolve(CONTRACT);
            if (Files.exists(candidate)) return candidate;
        }
        throw new AssertionError("cannot locate " + CONTRACT + " from " + Path.of("").toAbsolutePath());
    }

    @Test
    void theServedStepCatalogMatchesTheCommittedContract() throws IOException {
        String actual = JSON.writeValueAsString(PipelineProjection.stepCatalog()).replace("\r\n", "\n").trim();

        if (Boolean.getBoolean("step.types.write")) {
            Files.writeString(contractPath(), actual + "\n");
            return;
        }

        String expected = Files.readString(contractPath()).replace("\r\n", "\n").trim();
        assertEquals(expected, actual,
                "the published step catalog and " + CONTRACT + " disagree. If the Java side is right, "
                        + "regenerate with -Dstep.types.write=true and check the TS suite still passes; "
                        + "if the CLIENT is right, fix PipelineProjection/NodeAttributes instead.");
    }

    /** The verb order is the pipeline order and every verb authors a LOWERABLE type — a palette entry
     *  the save then refuses would be a dead-end affordance. {@code transform} appears TWICE (filter, join):
     *  the recipe spells a join as {@code transform: {join: …}}, so the entry is per shape while the verb
     *  stays the recipe's own word. */
    @Test
    void everyVerbAuthorsALowerableTypeInPipelineOrder() {
        List<Map<String, Object>> catalog = PipelineProjection.stepCatalog();
        List<String> verbs = catalog.stream().map(e -> (String) e.get("verb")).toList();
        assertEquals(List.of("collect", "parse", "map", "dedup", "transform", "transform", "summarize",
                "route", "sink"), verbs);
        for (Map<String, Object> e : catalog)
            assertEquals(Boolean.TRUE, e.get("lowerable"), e.get("verb") + " authors a type the save refuses");
    }

    /** {@code type}, not {@code verb}, is an entry's unique key — {@code transform} names two shapes. The UI
     *  keys its palette menu on it ({@code @for … track v.type}), and a duplicate track key is an Angular
     *  runtime error, so a future verb table that repeats a TYPE would break the menu, not just this test. */
    @Test
    void everyPaletteEntryHasAUniqueType() {
        List<String> types = PipelineProjection.stepCatalog().stream().map(e -> (String) e.get("type")).toList();
        assertEquals(types.size(), types.stream().distinct().count(), "duplicate type in the verb palette: " + types);
    }

    /** A join Step is authorable from the recipe palette at all — the authoring half of Phase 3 S2, which
     *  shipped join COMPILING while {@code transform} mapped to filter alone, leaving the verb reachable
     *  only from the demoted canvas. */
    @Test
    void theTransformVerbOffersBothFilterAndJoin() {
        List<String> transformTypes = PipelineProjection.stepCatalog().stream()
                .filter(e -> "transform".equals(e.get("verb")))
                .map(e -> (String) e.get("type"))
                .toList();
        assertEquals(List.of(BuiltinNodeType.TRANSFORM_FILTER.type(), BuiltinNodeType.TRANSFORM_JOIN.type()),
                transformTypes);
        for (Map<String, Object> e : PipelineProjection.stepCatalog())
            if (BuiltinNodeType.TRANSFORM_JOIN.type().equals(e.get("type")))
                assertFalse(((List<?>) e.get("attributes")).isEmpty(),
                        "join serves no specs, so its dialog would fall back to a free key/value editor");
    }

    /** §5's "specs must reach all seven verbs": every verb serves attributes except the two whose
     *  editors are richer surfaces than a scalar form (parse → Grammar editor, map → mapping CSV). */
    @Test
    void specsReachEveryVerbExceptTheDedicatedEditorSurfaces() {
        for (Map<String, Object> e : PipelineProjection.stepCatalog()) {
            String verb = (String) e.get("verb");
            List<?> attrs = (List<?>) e.get("attributes");
            if (verb.equals("parse") || verb.equals("map")) {
                assertTrue(attrs.isEmpty(), verb + " is authored by its dedicated editor, not a scalar spec");
            } else {
                assertFalse(attrs.isEmpty(), verb + " serves no attribute specs");
            }
        }
    }
}
