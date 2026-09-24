package com.gamma.pipeline.exec;

import com.gamma.config.io.ConfigLoader;
import com.gamma.config.spec.ConfigSpec;
import com.gamma.config.spec.ConfigSpecs;
import com.gamma.config.spec.Finding;
import com.gamma.config.spec.Severity;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link ComponentSpecs}: the component-kind spec home that sits BESIDE {@link ConfigSpecs#forType}, never
 * inside it (design {@code ai-drafting-non-schema-design.md} §2.2, operator D3), and the preview-backed
 * judge for {@code transform} (Option B, D6/D7).
 */
class ComponentSpecsTest {

    private static Map<String, Object> row(String id, String amt) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("amt", amt);
        return m;
    }

    private static final List<Map<String, Object>> SAMPLE = List.of(row("1", "150"), row("2", "50"));

    // ── S0: the spec home ────────────────────────────────────────────────────────

    @Test
    void transformHasAComponentSpecThatRequiresATransformType() {
        ConfigSpec spec = ComponentSpecs.forKind("transform");
        assertNotNull(spec);
        assertEquals("transform", spec.type());
        assertTrue(spec.field("type").orElseThrow().required());

        assertTrue(ConfigLoader.filesystem().validate(spec, Map.of("type", "transform.filter")).isEmpty());
        assertFalse(ConfigLoader.filesystem().validate(spec, Map.of("where", "1=1")).isEmpty(),
                "a transform with no type must be an ERROR");
        assertFalse(ConfigLoader.filesystem().validate(spec, Map.of("type", "sink.view")).isEmpty(),
                "a non-transform type must be refused");
    }

    @Test
    void onlyTheOperatorChosenKindIsSpecced() {
        assertEquals(List.of("transform"), ComponentSpecs.KINDS);
        assertNull(ComponentSpecs.forKind("grammar"), "grammar was not chosen (D1)");
        assertNull(ComponentSpecs.forKind("sink"), "sink was not chosen (D1)");
        assertNull(ComponentSpecs.forKind(null));
    }

    /** ⛔ §2.2: a component kind must never become a config TOON type (a new /config/write surface). */
    @Test
    void aComponentKindIsNeverAConfigType() {
        for (String kind : ComponentSpecs.KINDS) {
            assertNull(ConfigSpecs.forType(kind), kind + " leaked into ConfigSpecs.forType");
            assertFalse(ConfigSpecs.TYPES.contains(kind), kind + " leaked into ConfigSpecs.TYPES");
        }
    }

    // ── S3: preview-backed findings (Option B) ───────────────────────────────────

    @Test
    void aPassingPreviewAddsNoFinding() {
        List<Finding> f = ComponentSpecs.previewFindings("transform",
                Map.of("type", "transform.filter", "where", "CAST(amt AS INT) >= 100"), SAMPLE);
        assertEquals(List.of(), f);
    }

    /** The probe is spec-clean — only the production preview can see the unknown column. */
    @Test
    void aSpecCleanDraftThatFailsThePreviewIsAnUnanchoredError() {
        Map<String, Object> draft = Map.of("type", "transform.filter", "where", "no_such_column > 1");
        assertTrue(ConfigLoader.filesystem().validate(ComponentSpecs.forKind("transform"), draft).isEmpty(),
                "precondition: the probe must pass the spec, or this test proves nothing");

        List<Finding> f = ComponentSpecs.previewFindings("transform", draft, SAMPLE);
        assertEquals(1, f.size(), () -> "findings: " + f);
        assertEquals(Severity.ERROR, f.get(0).severity());
        assertEquals("", f.get(0).fieldPath(), "Option B findings are unanchored (D7)");
        assertTrue(f.get(0).message().toLowerCase().contains("no_such_column"), f.get(0).message());
    }

    @Test
    void anUnshapeableOperatorIsAnError() {
        List<Finding> f = ComponentSpecs.previewFindings("transform",
                Map.of("type", "transform.dedup.marker"), SAMPLE);   // needs a non-empty 'keys' list
        assertEquals(Severity.ERROR, f.get(0).severity(), () -> "findings: " + f);
    }

    /** D6: with no sample the draft is not judged, and says so — it never reads as clean. */
    @Test
    void noSampleIsAWarningNotSilence() {
        for (List<Map<String, Object>> none : java.util.Arrays.asList(null, List.<Map<String, Object>>of())) {
            List<Finding> f = ComponentSpecs.previewFindings("transform", Map.of("type", "transform.filter"), none);
            assertEquals(1, f.size());
            assertEquals(Severity.WARNING, f.get(0).severity());
            assertTrue(f.get(0).message().contains("sample"), f.get(0).message());
        }
    }

    @Test
    void anUnspeccedKindHasNoPreviewJudge() {
        assertEquals(List.of(), ComponentSpecs.previewFindings("grammar", Map.of(), SAMPLE));
    }
}
