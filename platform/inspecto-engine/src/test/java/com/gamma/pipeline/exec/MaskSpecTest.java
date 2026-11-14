package com.gamma.pipeline.exec;

import com.gamma.util.ColumnClassification;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link MaskSpec#problems(Map, Predicate)}: the classes a mask may select are the ones the Space masks. */
class MaskSpecTest {

    /** The Space's masked classes: the sensitive four plus a masked Entity Type's class WALLET and its id. */
    private static final Predicate<String> SPACE = c -> ColumnClassification.SENSITIVE.contains(c) || Set.of("WALLET").contains(c);

    private static Map<String, Object> mask(String cls) {
        return Map.of("classifications", List.of(cls), "dataset", "d");
    }

    /** MASKING-UNION-RESIDUALS-1: a class the Space masks is allowed, whether sensitive or a masked type's; any case, trimmed. */
    @Test
    void aClassTheSpaceMasksIsAllowed() {
        for (String cls : List.of("WALLET", " wallet ", "MSISDN", "pii"))
            assertEquals(List.of(), MaskSpec.problems(mask(cls), SPACE), cls);
    }

    /** A class that masks nothing in the Space is an author error: refused, naming it. */
    @Test
    void aClassThatMasksNothingIsRefused() {
        for (String cls : List.of("HANDSET", "INTERNAL", "SECRET")) {
            List<String> p = MaskSpec.problems(mask(cls), SPACE);
            assertEquals(1, p.size(), cls + ": " + p);
            assertTrue(p.get(0).contains("'" + cls + "' masks nothing in this Space"), p.get(0));
        }
    }
}
