package com.gamma.etl;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Parsing D9 slice 2: the {@code children} block's closed shape and every {@code ASN1-CHILD-*} code. */
class ChildSpecTest {

    private static final Set<String> SEGS = Set.of("R", "U", "V");

    private static Map<String, Object> m(Object... kv) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) out.put((String) kv[i], kv[i + 1]);
        return out;
    }

    private static void refused(String code, Runnable r) {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, r::run);
        assertTrue(e.getMessage().startsWith("ASN1-CHILD-" + code), e.getMessage());
    }

    @Test
    void parsesParentListAndChildSegment() {
        ChildSpec s = ChildSpec.parse(m("R", m("use.u", "U", "tags.t", "V")), SEGS, null);
        assertEquals(2, s.of("R").size());
        assertEquals("U", s.of("R").get(0).segment());
        assertEquals("use", s.of("R").get(0).list()[0]);
        assertEquals(Set.of("U", "V"), s.childSegments());
        assertEquals(0, s.of("U").size());
        assertEquals(ChildSpec.DEFAULT_MAX_CHILD_ROWS, s.maxChildRows());
        assertEquals(0, ChildSpec.none().childSegments().size());
        assertEquals(5, ChildSpec.parse(null, SEGS, 5).maxChildRows());
    }

    @Test
    void childSegmentMustBeDeclared() {
        refused("UNKNOWN-SEGMENT", () -> ChildSpec.parse(m("R", m("use.u", "Nope")), SEGS, null));
        refused("UNKNOWN-SEGMENT", () -> ChildSpec.parse(m("Nope", m("use.u", "U")), SEGS, null));
        refused("UNKNOWN-SEGMENT", () -> ChildSpec.parse(m("R", m("use.u", 7)), SEGS, null));
        // unknown segment keys are not checked when the segments are not known at the call site
        ChildSpec.parse(m("R", m("use.u", "Nope")), null, null);
    }

    @Test
    void selfAndNestedExplodeAreRefused() {
        refused("SELF", () -> ChildSpec.parse(m("R", m("use.u", "R")), SEGS, null));
        refused("NESTED", () -> ChildSpec.parse(m("R", m("use.u", "U"), "U", m("x.y", "V")), SEGS, null));
    }

    @Test
    void badShapeAndPathAreRefused() {
        refused("BAD-PATH", () -> ChildSpec.parse("R", SEGS, null));
        refused("BAD-PATH", () -> ChildSpec.parse(m("R", "U"), SEGS, null));
        refused("BAD-PATH", () -> ChildSpec.parse(m("R", m()), SEGS, null));
        refused("BAD-PATH", () -> ChildSpec.parse(m("R", m("a..b", "U")), SEGS, null));
        refused("BAD-PATH", () -> ChildSpec.parse(m("R", m("$parent.x", "U")), SEGS, null));
        refused("BAD-PATH", () -> ChildSpec.parse(m("R", m("a.b.c.d.e.f.g.h.i", "U")), SEGS, null));
    }

    @Test
    void limitIsPositiveAndBelowTheCeiling() {
        refused("LIMIT", () -> ChildSpec.parse(null, SEGS, 0));
        refused("LIMIT", () -> ChildSpec.parse(null, SEGS, "abc"));
        refused("LIMIT", () -> ChildSpec.parse(null, SEGS, ChildSpec.CEILING_MAX_CHILD_ROWS + 1));
        assertEquals(ChildSpec.CEILING_MAX_CHILD_ROWS,
                ChildSpec.parse(null, SEGS, ChildSpec.CEILING_MAX_CHILD_ROWS).maxChildRows());
    }

    @Test
    void theDecodeProfileValidatesChildrenWherever_deriveIsValidated() {
        Map<String, Object> bad = m("segments", m("R", "r.toon"), "children", m("R", m("use.u", "Nope")));
        refused("UNKNOWN-SEGMENT", () -> DecodeProfile.validateDerive(bad));
        refused("LIMIT", () -> DecodeProfile.validateDerive(m("max_child_rows_per_record", 0)));
        DecodeProfile.validateDerive(m("segments", m("R", "r.toon", "U", "u.toon"), "children", m("R", m("use.u", "U"))));
    }
}
