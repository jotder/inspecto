package com.gamma.pipeline;

import com.gamma.etl.PipelineConfig;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * NODE-TYPE-MIRRORS-1 (2026-09-15): the flat-config home is declared on the {@link BuiltinNodeType} case,
 * and {@code PipelineEditable} derives LOWERABLE / STEP_KIND from it. What the enum cannot check on its
 * own is pinned here: a {@code steps:} kind must be a constant the flat file's parser actually knows.
 */
class BuiltinNodeTypeFlatHomeTest {

    @Test
    void everyStepKindIsAPipelineConfigStepConstant() throws Exception {
        Set<String> known = new HashSet<>();
        for (Field f : PipelineConfig.Step.class.getDeclaredFields())
            if (Modifier.isStatic(f.getModifiers()) && f.getType() == String.class) known.add((String) f.get(null));
        assertFalse(known.isEmpty(), "PipelineConfig.Step constants not found — re-anchor this test");

        Set<String> kinds = new HashSet<>();
        for (BuiltinNodeType t : BuiltinNodeType.values()) {
            if (t.flatHome() == BuiltinNodeType.FlatHome.STEP) {
                assertNotNull(t.stepKind(), t + " declares a steps: home with no kind");
                assertTrue(known.contains(t.stepKind()),
                        t + " declares steps: kind '" + t.stepKind() + "', which PipelineConfig.Step does not know");
                assertEquals(NodeCategory.TRANSFORM, t.category(), t + ": only a transform lowers into the steps: chain");
                assertTrue(kinds.add(t.stepKind()), "two node types claim steps: kind '" + t.stepKind() + "'");
            } else {
                assertNull(t.stepKind(), t + " carries a steps: kind without a steps: home");
            }
        }
        assertEquals(8, kinds.size(), "eight built-in chain kinds as of 2026-09-15 — a change here is a change to the "
                + "flat file's chain vocabulary and must come with its lift/lower: " + kinds);
    }

    @Test
    void theEditorDerivesLowerabilityFromTheEnumNotFromASecondList() {
        for (BuiltinNodeType t : BuiltinNodeType.values())
            assertEquals(t.flatHome() != BuiltinNodeType.FlatHome.NONE, PipelineEditable.isLowerable(t.type()),
                    t + ": isLowerable disagrees with flatHome()");
    }
}
